import { readFileSync } from 'fs';
import path from 'path';
import { definePlugin, emit, hostCall, log, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const DATA_DIR =
  process.env.AICODE_DATA_DIR ||
  path.join(process.env.HOME || '/root', '.aicode', 'plugins', 'data', 'demo.ssh-manager');
const CONFIG_PATH = path.join(DATA_DIR, 'servers.json');
const CONNECT_TIMEOUT_MS = 25000;
const DEFAULT_EXEC_TIMEOUT_SECONDS = 30;
const MAX_EXEC_TIMEOUT_SECONDS = 120;
const MAX_OUTPUT_CHARS = 20000;

interface ServerConfig {
  id?: string;
  name?: string;
  host?: string;
  port?: number;
  username?: string;
  enabled?: boolean;
  auth?: string;
  privateKeyPath?: string;
  passphrase?: string;
  password?: string;
}

interface PublicServer {
  id: string;
  name: string;
  host: string;
  port: number;
  username: string;
  enabled: boolean;
  auth: 'password' | 'key';
  status: string;
  error: string | null;
}

interface SshChannel {
  on(event: 'data', listener: (data: Buffer | string) => void): void;
  on(event: 'close', listener: (code: number | null) => void): void;
  stderr: SshChannel;
  close(): void;
}

interface SshClient {
  on(event: 'ready', listener: () => void): void;
  on(event: 'error', listener: (error: Error) => void): void;
  on(event: 'close', listener: () => void): void;
  connect(config: ConnectionConfig): void;
  end(): void;
  exec(
    command: string,
    options: { pty: boolean },
    callback: (error: Error | null, channel: SshChannel) => void
  ): void;
}

interface Ssh2Module {
  Client: new () => SshClient;
}

interface ConnectionConfig {
  host: string;
  port: number;
  username: string;
  readyTimeout: number;
  keepaliveInterval: number;
  keepaliveCountMax: number;
  password?: string;
  privateKey?: Buffer;
  passphrase?: string;
}

interface Connection {
  client: SshClient;
  status: 'connecting' | 'online' | 'offline';
  error: string | null;
  pending: Promise<Connection> | null;
  server: ServerConfig;
  configKey: string;
  closed: boolean;
}

interface ExecResult {
  output: string;
  exitCode: number | null;
  timedOut: boolean;
}

interface ServersResult {
  servers: PublicServer[];
}

interface ServerStatusResult extends ExecResult {
  server: PublicServer;
}

interface ExecToolResult extends ExecResult {
  serverId: string;
  command: string;
}

type ToolStatusResult = ServersResult | ServerStatusResult;

interface StatusArgs extends PluginArgs {
  serverId?: unknown;
}

interface ExecArgs extends PluginArgs {
  serverId?: unknown;
  command?: unknown;
  timeout?: unknown;
}

const connections = new Map<string, Connection>();
let ssh2: Ssh2Module | null = null;

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

function readServers(): ServerConfig[] {
  try {
    const parsed: unknown = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));
    if (typeof parsed !== 'object' || parsed === null) return [];
    const servers = (parsed as { servers?: unknown }).servers;
    return Array.isArray(servers) ? (servers as ServerConfig[]) : [];
  } catch {
    return [];
  }
}

function expandHome(value: unknown): string {
  const text = String(value ?? '').trim();
  if (text === '~') return process.env.HOME || '/root';
  if (text.startsWith('~/')) return path.join(process.env.HOME || '/root', text.slice(2));
  if (text.startsWith('data://')) return path.join(DATA_DIR, text.slice('data://'.length));
  return text;
}

function getSsh2(): Ssh2Module {
  if (!ssh2) {
    try {
      ssh2 = require('ssh2') as Ssh2Module;
    } catch {
      throw new Error('ssh2 未安装，请先在插件详情点「准备依赖」');
    }
  }
  return ssh2;
}

function connectionStatus(serverId: string): string {
  return connections.get(serverId)?.status ?? 'offline';
}

function connectionKey(server: ServerConfig): string {
  return [
    server.host,
    server.port,
    server.username,
    server.auth,
    server.privateKeyPath,
    server.passphrase,
    server.password
  ]
    .map((value) => String(value ?? ''))
    .join('\u0000');
}

function publicServer(server: ServerConfig): PublicServer {
  const serverId = String(server.id ?? '');
  const conn = connections.get(serverId);
  return {
    id: serverId,
    name: String(server.name || server.host || server.id || ''),
    host: String(server.host || ''),
    port: Number(server.port) || 22,
    username: String(server.username || ''),
    enabled: server.enabled !== false,
    auth: server.auth === 'password' ? 'password' : 'key',
    status: connectionStatus(serverId),
    error: conn?.error ?? null
  };
}

function publicServers(): PublicServer[] {
  return readServers().map(publicServer);
}

function emitStatus(serverId: string, status: string, error?: unknown): void {
  emit('ssh.status', {
    serverId,
    status,
    error: error ? String(error).slice(0, 300) : null
  });
}

function buildConnectionConfig(server: ServerConfig): ConnectionConfig {
  const config: ConnectionConfig = {
    host: String(server.host || ''),
    port: Number(server.port) || 22,
    username: String(server.username || ''),
    readyTimeout: CONNECT_TIMEOUT_MS,
    keepaliveInterval: 10000,
    keepaliveCountMax: 3
  };
  if (!config.host) throw new Error(`服务器 ${server.id} 缺少 host`);
  if (!config.username) throw new Error(`服务器 ${server.id} 缺少 username`);
  if (server.auth === 'password') {
    config.password = String(server.password || '');
  } else {
    const keyPath = expandHome(server.privateKeyPath);
    if (!keyPath) throw new Error(`服务器 ${server.id} 缺少 privateKeyPath`);
    config.privateKey = readFileSync(keyPath);
    if (server.passphrase) config.passphrase = String(server.passphrase);
  }
  return config;
}

function connectServer(server: ServerConfig): Promise<Connection> {
  if (!server.id) return Promise.reject(new Error('invalid server'));
  const serverId = String(server.id);
  const configKey = connectionKey(server);
  const existing = connections.get(serverId);
  if (existing && existing.configKey === configKey) {
    if (existing.status === 'online') return Promise.resolve(existing);
    if (existing.pending) return existing.pending;
  } else if (existing) {
    closeServer(serverId);
  }

  let Client: new () => SshClient;
  try {
    Client = getSsh2().Client;
  } catch (error) {
    emitStatus(serverId, 'offline', errorMessage(error));
    return Promise.reject(error);
  }

  const client = new Client();
  const conn: Connection = {
    client,
    status: 'connecting',
    error: null,
    pending: null,
    server,
    configKey,
    closed: false
  };
  connections.set(serverId, conn);
  emitStatus(serverId, 'connecting');

  const pending = new Promise<Connection>((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      try {
        client.end();
      } catch {
        // ignore
      }
      finish(new Error('连接超时'));
    }, CONNECT_TIMEOUT_MS + 5000);

    function finish(error: Error | null): void {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.pending = null;
      if (conn.closed) {
        reject(error ?? new Error('连接已关闭'));
        return;
      }
      if (error) {
        conn.status = 'offline';
        conn.error = errorMessage(error).slice(0, 300);
        emitStatus(serverId, 'offline', conn.error);
        reject(error);
      } else {
        conn.status = 'online';
        conn.error = null;
        emitStatus(serverId, 'online');
        resolve(conn);
      }
    }

    client.on('ready', () => finish(null));
    client.on('error', (error: Error) => finish(error));
    client.on('close', () => {
      if (conn.closed || connections.get(serverId) !== conn) return;
      const wasOnline = conn.status === 'online';
      conn.status = 'offline';
      conn.pending = null;
      if (wasOnline) emitStatus(serverId, 'offline', conn.error);
    });

    try {
      client.connect(buildConnectionConfig(server));
    } catch (error) {
      finish(error as Error);
    }
  });

  conn.pending = pending;
  pending.catch(() => {});
  return pending;
}

function closeServer(serverId: string): void {
  const conn = connections.get(serverId);
  if (!conn) return;
  conn.closed = true;
  try {
    conn.client.end();
  } catch {
    // ignore
  }
  connections.delete(serverId);
  emitStatus(serverId, 'offline');
}

function bootstrap(): void {
  const servers = readServers();
  const enabled = servers.filter((server) => server.enabled !== false);
  for (const server of enabled) {
    connectServer(server).catch((error) => {
      void hostCall('host.notify', {
        title: 'SSH 连接失败',
        body: `${server.name || server.host || server.id}: ${errorMessage(error).slice(0, 200)}`,
        key: `ssh-${server.id}`
      }).catch(() => {});
    });
  }
}

async function ensureConnection(server: ServerConfig): Promise<Connection> {
  const serverId = String(server.id ?? '');
  const conn = connections.get(serverId);
  if (conn && conn.status === 'online') return conn;
  return connectServer(server);
}

function execRemote(
  server: ServerConfig,
  command: string,
  timeoutSeconds: number
): Promise<ExecResult> {
  return ensureConnection(server).then(
    (conn) =>
      new Promise<ExecResult>((resolve) => {
        let settled = false;
        let output = '';
        let exitCode: number | null = null;
        let timedOut = false;
        let stream: SshChannel | undefined;
        const timer = setTimeout(() => {
          timedOut = true;
          try {
            stream?.close();
          } catch {
            // ignore
          }
          finish();
        }, Math.max(1, timeoutSeconds) * 1000);

        function append(data: unknown): void {
          if (output.length >= MAX_OUTPUT_CHARS) return;
          output += String(data);
          if (output.length > MAX_OUTPUT_CHARS) {
            output = output.slice(0, MAX_OUTPUT_CHARS) + '\n...(输出已截断)';
          }
        }

        function finish(): void {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          resolve({ output, exitCode, timedOut });
        }

        conn.client.exec(command, { pty: false }, (error, channel) => {
          if (error) {
            clearTimeout(timer);
            settled = true;
            resolve({ output: errorMessage(error), exitCode: null, timedOut: false });
            return;
          }
          stream = channel;
          channel.on('data', append);
          channel.stderr.on('data', append);
          channel.on('close', (code: number | null) => {
            exitCode = typeof code === 'number' ? code : null;
            finish();
          });
        });
      })
  );
}

function findServer(serverId: string): ServerConfig | undefined {
  return readServers().find((server) => String(server.id) === String(serverId));
}

const handlers = {
  'hooks.boot': (): { started: true } => {
    void Promise.resolve()
      .then(bootstrap)
      .catch((error) => {
        log(`bootstrap failed: ${errorMessage(error)}`);
      });
    return { started: true };
  },

  'tools.list': (): ServersResult => ({ servers: publicServers() }),

  'tools.status': async (rawArgs: PluginArgs): Promise<ToolStatusResult> => {
    const args = rawArgs as StatusArgs;
    const serverId = readString(args, 'serverId');
    if (!serverId) return { servers: publicServers() };
    const server = findServer(serverId);
    if (!server) throw new Error(`未知 serverId: ${serverId}`);
    const result = await execRemote(server, 'uptime; df -h /', 15);
    return { server: publicServer(server), ...result };
  },

  'tools.exec': async (rawArgs: PluginArgs): Promise<ExecToolResult> => {
    const args = rawArgs as ExecArgs;
    const serverId = readString(args, 'serverId');
    const server = findServer(serverId);
    if (!server) throw new Error(`未知 serverId: ${serverId}`);
    const command = readString(args, 'command').trim();
    if (!command) throw new Error('command 不能为空');
    if (command.length > 4000) throw new Error('command 过长');
    const timeout = Math.min(
      MAX_EXEC_TIMEOUT_SECONDS,
      Math.max(1, Number(args.timeout) || DEFAULT_EXEC_TIMEOUT_SECONDS)
    );
    const result = await execRemote(server, command, timeout);
    return { serverId, command, ...result };
  },

  'ui.status': (): ServersResult => ({ servers: publicServers() }),

  'ui.connect': async (rawArgs: PluginArgs): Promise<{ server: PublicServer }> => {
    const args = rawArgs as StatusArgs;
    const server = findServer(readString(args, 'serverId'));
    if (!server) throw new Error('未知服务器');
    await connectServer(server);
    return { server: publicServer(server) };
  },

  'ui.disconnect': (rawArgs: PluginArgs): { ok: true } => {
    const args = rawArgs as StatusArgs;
    closeServer(readString(args, 'serverId'));
    return { ok: true };
  },

  'ui.reload': async (): Promise<ServersResult> => {
    const servers = readServers();
    const byId = new Map(servers.map((server) => [String(server.id), server]));
    for (const id of Array.from(connections.keys())) {
      const server = byId.get(id);
      const conn = connections.get(id);
      if (!server || server.enabled === false || !conn || conn.configKey !== connectionKey(server)) {
        closeServer(id);
      }
    }
    const enabled = servers.filter((server) => server.enabled !== false);
    await Promise.allSettled(enabled.map((server) => connectServer(server)));
    return { servers: publicServers() };
  }
} satisfies Record<string, PluginHandler>;

process.on('unhandledRejection', (error: unknown) => {
  log(`ssh-manager unhandled rejection: ${errorMessage(error)}`);
});

definePlugin({ handlers });
