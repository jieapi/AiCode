import { definePlugin, emit, hostCall, type PluginHandler } from '@aicode/plugin-sdk';

const ENV_BASENAME = /^\.env(?:\..+)?$/i;
const ENV_TOKEN = /(^|[\s"'`=<>|;&()/*?\\])\.env(?:\.[A-Za-z0-9._-]+)?($|[\s"'`<>|;&()/*?\\])/i;
const PATH_KEYS = ['path', 'paths', 'file', 'files', 'output_path', 'input_path', 'images', 'image'];
const COMMAND_TOOLS = new Set(['bash', 'terminal', 'shizuku']);
const DETECT_TOOLS = new Set(['list', 'search']);

let lastRun: unknown = null;

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

const handlers: Record<string, PluginHandler> = {
  'tools.echo': (args) => {
    const text = String(args.text ?? '');
    void hostCall('host.fs.write', {
      path: 'data://echo.log',
      content: text + '\n'
    }).catch(() => {});
    emit('echo.done', { length: text.length });
    return { text, length: text.length, plugin: 'community.demo-kit', runtime: 'process' };
  },
  'tools.lastRun': () => lastRun,
  'tools.notify': async (args) => {
    await hostCall('host.notify', {
      title: String(args.title ?? ''),
      body: String(args.body ?? ''),
      key: args.key === undefined ? undefined : String(args.key)
    });
    return { ok: true };
  },
  'hooks.runFinished': (args) => {
    lastRun = args;
    return 'stored';
  },
  'hooks.denyEnv': (payload) => {
    const toolName = String(payload.toolName || '').toLowerCase();
    const args =
      payload.arguments && typeof payload.arguments === 'object'
        ? (payload.arguments as Record<string, unknown>)
        : {};
    const hits = inspectEnvAccess(toolName, args);
    if (hits.length === 0) return {};
    const detail = hits[0].slice(0, 120);
    return {
      decision: 'deny',
      reason: `插件演示套件已拒绝访问 .env 文件（${detail}）：该文件可能包含密钥。`,
      logs: [`blocked ${toolName}: ${hits.join(', ')}`]
    };
  }
};

definePlugin({ handlers });
