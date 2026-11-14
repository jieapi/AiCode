'use strict';

const readline = require('readline');

let lastRun = null;
let hostSeq = 0;
const hostPending = new Map();

const ENV_BASENAME = /^\.env(?:\..+)?$/i;
// 只识别命令里直接写出的 .env 引用；变量拼接、脚本间接读取不在演示范围。
const ENV_TOKEN = /(^|[\s"'`=<>|;&()/*?\\])\.env(?:\.[A-Za-z0-9._-]+)?($|[\s"'`<>|;&()/*?\\])/i;
const PATH_KEYS = ['path', 'paths', 'file', 'files', 'output_path', 'input_path', 'images', 'image'];
const COMMAND_TOOLS = new Set(['bash', 'terminal', 'shizuku']);
const DETECT_TOOLS = new Set(['list', 'search']);

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

// 宿主 API 是普通 JSON-RPC 请求，插件可以像调用本地函数一样使用。
function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

function flattenStrings(value, out = []) {
  if (typeof value === 'string') {
    out.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) flattenStrings(item, out);
  }
  return out;
}

function basename(path) {
  const normalized = String(path)
    .trim()
    .replace(/^['"]|['"]$/g, '')
    .replace(/\\/g, '/')
    .replace(/\/+$/g, '');
  const parts = normalized.split('/');
  return parts[parts.length - 1] || '';
}

function isEnvPath(value) {
  return ENV_BASENAME.test(basename(value));
}

// 只检查路径与命令字段，不扫描 content / edits，避免正常代码提到 .env 被误拦。
function inspectEnvAccess(toolName, args) {
  const hits = [];
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
  'tools.echo': (args) => {
    const text = String(args.text ?? '');
    void hostCall('host.fs.write', {
      path: 'data://echo.log',
      content: text + '\n'
    }).catch(() => {});
    send({
      jsonrpc: '2.0',
      method: 'plugin.event',
      params: { name: 'echo.done', payload: { length: text.length } }
    });
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
    const args = payload.arguments && typeof payload.arguments === 'object' ? payload.arguments : {};
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

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke') {
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
  const pending = hostPending.get(id);
  if (!pending) return;
  hostPending.delete(id);
  if (message.error) pending.reject(new Error(message.error.message || 'host error'));
  else pending.resolve(message.result === undefined ? null : message.result);
}

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
    handleHostResponse(String(message.id), message);
  }
});
