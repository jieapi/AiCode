'use strict';

const readline = require('readline');
const fs = require('fs');
const path = require('path');

const DATA_DIR = process.env.AICODE_DATA_DIR ||
  path.join(process.env.HOME || '/root', '.aicode', 'plugins', 'data', 'demo.ssh-manager');
const CONFIG_PATH = path.join(DATA_DIR, 'servers.json');
const CONNECT_TIMEOUT_MS = 25000;
const DEFAULT_EXEC_TIMEOUT_SECONDS = 30;
const MAX_EXEC_TIMEOUT_SECONDS = 120;
const MAX_OUTPUT_CHARS = 20000;

const connections = new Map();
const hostPending = new Map();
let hostSeq = 0;
let ssh2 = null;

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function emit(name, payload) {
  send({ jsonrpc: '2.0', method: 'plugin.event', params: { name, payload } });
}

function log(message) {
  console.error(`[ssh-manager] ${message}`);
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

function readServers() {
  try {
    const parsed = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
    return Array.isArray(parsed.servers) ? parsed.servers : [];
  } catch (e) {
    return [];
  }
}

function expandHome(value) {
  const text = String(value || '').trim();
  if (text === '~') return process.env.HOME || '/root';
  if (text.startsWith('~/')) return path.join(process.env.HOME || '/root', text.slice(2));
  if (text.startsWith('data://')) return path.join(DATA_DIR, text.slice('data://'.length));
  return text;
}

function getSsh2() {
  if (!ssh2) {
    try {
      ssh2 = require('ssh2');
    } catch (e) {
      throw new Error('ssh2 未安装，请先在插件详情点「准备依赖」');
    }
  }
  return ssh2;
}

function connectionStatus(serverId) {
  return connections.get(serverId)?.status || 'offline';
}

function connectionKey(server) {
  return [
    server.host, server.port, server.username, server.auth,
    server.privateKeyPath, server.passphrase, server.password
  ].map((value) => String(value == null ? '' : value)).join('\u0000');
}

function publicServer(server) {
  const conn = connections.get(server.id);
  return {
    id: String(server.id || ''),
    name: String(server.name || server.host || server.id || ''),
    host: String(server.host || ''),
    port: Number(server.port) || 22,
    username: String(server.username || ''),
    enabled: server.enabled !== false,
    auth: server.auth === 'password' ? 'password' : 'key',
    status: connectionStatus(server.id),
    error: conn?.error || null
  };
}

function publicServers() {
  return readServers().map(publicServer);
}

function emitStatus(serverId, status, error) {
  emit('ssh.status', {
    serverId,
    status,
    error: error ? String(error).slice(0, 300) : null
  });
}

function buildConnectionConfig(server) {
  const config = {
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
    config.privateKey = fs.readFileSync(keyPath);
    if (server.passphrase) config.passphrase = String(server.passphrase);
  }
  return config;
}

function connectServer(server) {
  if (!server || !server.id) return Promise.reject(new Error('invalid server'));
  const configKey = connectionKey(server);
  const existing = connections.get(server.id);
  if (existing && existing.configKey === configKey) {
    if (existing.status === 'online') return Promise.resolve(existing);
    if (existing.pending) return existing.pending;
  } else if (existing) {
    closeServer(server.id);
  }

  let Client;
  try {
    Client = getSsh2().Client;
  } catch (e) {
    emitStatus(server.id, 'offline', e.message);
    return Promise.reject(e);
  }

  const client = new Client();
  const conn = {
    client,
    status: 'connecting',
    error: null,
    pending: null,
    server,
    configKey,
    closed: false
  };
  connections.set(server.id, conn);
  emitStatus(server.id, 'connecting');

  const pending = new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      try { client.end(); } catch (e) { /* ignore */ }
      finish(new Error('连接超时'));
    }, CONNECT_TIMEOUT_MS + 5000);

    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      conn.pending = null;
      if (conn.closed) {
        if (error) reject(error);
        else reject(new Error('连接已关闭'));
        return;
      }
      if (error) {
        conn.status = 'offline';
        conn.error = String(error.message || error).slice(0, 300);
        emitStatus(server.id, 'offline', conn.error);
        reject(error);
      } else {
        conn.status = 'online';
        conn.error = null;
        emitStatus(server.id, 'online');
        resolve(conn);
      }
    }

    client.on('ready', () => finish(null));
    client.on('error', (error) => finish(error));
    client.on('close', () => {
      if (conn.closed || connections.get(server.id) !== conn) return;
      const wasOnline = conn.status === 'online';
      conn.status = 'offline';
      conn.pending = null;
      if (wasOnline) emitStatus(server.id, 'offline', conn.error);
    });

    try {
      client.connect(buildConnectionConfig(server));
    } catch (e) {
      finish(e);
    }
  });

  conn.pending = pending;
  pending.catch(() => {});
  return pending;
}

function closeServer(serverId) {
  const conn = connections.get(serverId);
  if (!conn) return;
  conn.closed = true;
  try { conn.client.end(); } catch (e) { /* ignore */ }
  connections.delete(serverId);
  emitStatus(serverId, 'offline');
}

function bootstrap() {
  const servers = readServers();
  const enabled = servers.filter((server) => server.enabled !== false);
  for (const server of enabled) {
    connectServer(server).catch((error) => {
      void hostCall('host.notify', {
        title: 'SSH 连接失败',
        body: `${server.name || server.host || server.id}: ${String(error.message || error).slice(0, 200)}`,
        key: `ssh-${server.id}`
      }).catch(() => {});
    });
  }
}

async function ensureConnection(server) {
  const conn = connections.get(server.id);
  if (conn && conn.status === 'online') return conn;
  return connectServer(server);
}

function execRemote(server, command, timeoutSeconds) {
  return ensureConnection(server).then((conn) => new Promise((resolve) => {
    let settled = false;
    let output = '';
    let exitCode = null;
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try { stream?.close(); } catch (e) { /* ignore */ }
      finish();
    }, Math.max(1, timeoutSeconds) * 1000);

    function append(data) {
      if (output.length >= MAX_OUTPUT_CHARS) return;
      output += String(data);
      if (output.length > MAX_OUTPUT_CHARS) {
        output = output.slice(0, MAX_OUTPUT_CHARS) + '\n...(输出已截断)';
      }
    }

    function finish() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ output, exitCode, timedOut });
    }

    let stream;
    conn.client.exec(command, { pty: false }, (error, channel) => {
      if (error) {
        clearTimeout(timer);
        settled = true;
        resolve({ output: String(error.message || error), exitCode: null, timedOut: false });
        return;
      }
      stream = channel;
      channel.on('data', append);
      channel.stderr.on('data', append);
      channel.on('close', (code) => {
        exitCode = code;
        finish();
      });
    });
  }));
}

function findServer(serverId) {
  return readServers().find((server) => String(server.id) === String(serverId));
}

const handlers = {
  'hooks.boot': () => {
    void Promise.resolve().then(bootstrap).catch((error) => {
      log(`bootstrap failed: ${error.message || error}`);
    });
    return { started: true };
  },

  'tools.list': () => ({ servers: publicServers() }),

  'tools.status': async (args) => {
    const serverId = args.serverId === undefined ? '' : String(args.serverId);
    if (!serverId) return { servers: publicServers() };
    const server = findServer(serverId);
    if (!server) throw new Error(`未知 serverId: ${serverId}`);
    const result = await execRemote(server, 'uptime; df -h /', 15);
    return { server: publicServer(server), ...result };
  },

  'tools.exec': async (args) => {
    const serverId = String(args.serverId || '');
    const server = findServer(serverId);
    if (!server) throw new Error(`未知 serverId: ${serverId}`);
    const command = String(args.command || '').trim();
    if (!command) throw new Error('command 不能为空');
    if (command.length > 4000) throw new Error('command 过长');
    const timeout = Math.min(
      MAX_EXEC_TIMEOUT_SECONDS,
      Math.max(1, Number(args.timeout) || DEFAULT_EXEC_TIMEOUT_SECONDS)
    );
    const result = await execRemote(server, command, timeout);
    return { serverId, command, ...result };
  },

  'ui.status': () => ({ servers: publicServers() }),

  'ui.connect': async (args) => {
    const server = findServer(String(args.serverId || ''));
    if (!server) throw new Error('未知服务器');
    await connectServer(server);
    return { server: publicServer(server) };
  },

  'ui.disconnect': (args) => {
    closeServer(String(args.serverId || ''));
    return { ok: true };
  },

  'ui.reload': async () => {
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
};

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke' && method !== 'ui.invoke') {
    send({ jsonrpc: '2.0', id, error: { code: 'METHOD_UNKNOWN', message: method } });
    return;
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    send({ jsonrpc: '2.0', id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
    return;
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({ jsonrpc: '2.0', id, result: result === undefined ? null : result }))
    .catch((error) => send({
      jsonrpc: '2.0',
      id,
      error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) }
    }));
}

function handleHostResponse(id, message) {
  const pending = hostPending.get(String(id));
  if (!pending) return;
  hostPending.delete(String(id));
  if (message.error) pending.reject(new Error(message.error.message || 'host error'));
  else pending.resolve(message.result === undefined ? null : message.result);
}

process.on('unhandledRejection', (error) => {
  log(`unhandled rejection: ${error && error.message ? error.message : error}`);
});

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (e) {
    return;
  }
  if (message.method && message.id !== undefined) {
    handleHostRequest(message.id, message.method, message.params || {});
  } else if (message.method) {
    return;
  } else if (message.id !== undefined) {
    handleHostResponse(message.id, message);
  }
});
