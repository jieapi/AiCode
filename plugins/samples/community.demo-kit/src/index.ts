import { definePlugin, emit, hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const ENV_BASENAME = /^\.env(?:\..+)?$/i;
const ENV_TOKEN = /(^|[\s"'`=<>|;&()/*?\\])\.env(?:\.[A-Za-z0-9._-]+)?($|[\s"'`<>|;&()/*?\\])/i;
const PATH_KEYS = ['path', 'paths', 'file', 'files', 'output_path', 'input_path', 'images', 'image'];
const COMMAND_TOOLS = new Set(['bash', 'terminal', 'shizuku']);
const DETECT_TOOLS = new Set(['list', 'search']);

interface EchoArgs extends PluginArgs {
  text?: unknown;
}

interface NotifyArgs extends PluginArgs {
  title?: unknown;
  body?: unknown;
  key?: unknown;
}

interface DenyEnvArgs extends PluginArgs {
  toolName?: unknown;
  arguments?: unknown;
}

interface DenyResult {
  decision?: 'deny';
  reason?: string;
  logs?: string[];
}

interface EchoResult {
  text: string;
  length: number;
  plugin: string;
  runtime: string;
}

let lastRun: Record<string, unknown> | null = null;

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

function flattenStrings(value: unknown, out: string[] = []): string[] {
  if (typeof value === 'string') {
    out.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) flattenStrings(item, out);
  }
  return out;
}

function basename(value: unknown): string {
  const normalized = String(value)
    .trim()
    .replace(/^['"]|['"]$/g, '')
    .replace(/\\/g, '/')
    .replace(/\/+$/g, '');
  const parts = normalized.split('/');
  return parts[parts.length - 1] || '';
}

function isEnvPath(value: unknown): boolean {
  return ENV_BASENAME.test(basename(value));
}

function inspectEnvAccess(toolName: string, args: Record<string, unknown>): string[] {
  const hits: string[] = [];
  for (const key of PATH_KEYS) {
    if (args[key] === undefined) continue;
    for (const value of flattenStrings(args[key])) {
      if (isEnvPath(value)) hits.push(`${key}=${value}`);
    }
  }

  if (DETECT_TOOLS.has(toolName) && typeof args.args === 'string' && ENV_TOKEN.test(args.args)) {
    hits.push('args');
  }

  if (COMMAND_TOOLS.has(toolName)) {
    for (const key of ['command', 'input']) {
      if (typeof args[key] === 'string' && ENV_TOKEN.test(args[key])) hits.push(key);
    }
  }

  return hits;
}

const handlers = {
  'tools.echo': (rawArgs: PluginArgs): EchoResult => {
    const args = rawArgs as EchoArgs;
    const text = readString(args, 'text');
    void hostCall('host.fs.write', {
      path: 'data://echo.log',
      content: text + '\n'
    }).catch(() => {});
    emit('echo.done', { length: text.length });
    return { text, length: text.length, plugin: 'community.demo-kit', runtime: 'process' };
  },

  'tools.lastRun': (): Record<string, unknown> | null => lastRun,

  'tools.notify': async (rawArgs: PluginArgs): Promise<{ ok: boolean }> => {
    const args = rawArgs as NotifyArgs;
    await hostCall('host.notify', {
      title: readString(args, 'title'),
      body: readString(args, 'body'),
      key: args.key === undefined ? undefined : String(args.key)
    });
    return { ok: true };
  },

  'hooks.runFinished': (rawArgs: PluginArgs): string => {
    lastRun = rawArgs;
    return 'stored';
  },

  'hooks.denyEnv': (rawArgs: PluginArgs): DenyResult => {
    const args = rawArgs as DenyEnvArgs;
    const toolName = readString(args, 'toolName').toLowerCase();
    const toolArgs =
      typeof args.arguments === 'object' && args.arguments !== null
        ? (args.arguments as Record<string, unknown>)
        : {};
    const hits = inspectEnvAccess(toolName, toolArgs);
    if (hits.length === 0) return {};
    const detail = hits[0].slice(0, 120);
    return {
      decision: 'deny',
      reason: `插件演示套件已拒绝访问 .env 文件（${detail}）：该文件可能包含密钥。`,
      logs: [`blocked ${toolName}: ${hits.join(', ')}`]
    };
  }
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
