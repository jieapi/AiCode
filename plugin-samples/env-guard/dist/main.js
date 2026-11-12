'use strict';

const readline = require('readline');

const ENV_BASENAME = /^\.env(?:\..+)?$/i;
// 只识别命令里直接写出的 .env 引用；变量拼接、脚本间接读取不在演示范围。
const ENV_TOKEN = /(^|[\s"'`=<>|;&()/*?\\])\.env(?:\.[A-Za-z0-9._-]+)?($|[\s"'`<>|;&()/*?\\])/i;
const PATH_KEYS = ['path', 'paths', 'file', 'files', 'output_path', 'input_path', 'images', 'image'];
const COMMAND_TOOLS = new Set(['bash', 'terminal', 'shizuku']);
const DETECT_TOOLS = new Set(['list', 'search']);

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
function inspect(toolName, args) {
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
  'hooks.denyEnv': (payload) => {
    const toolName = String(payload.toolName || '').toLowerCase();
    const args = payload.arguments && typeof payload.arguments === 'object' ? payload.arguments : {};
    const hits = inspect(toolName, args);
    if (hits.length === 0) return {};
    const detail = hits[0].slice(0, 120);
    return {
      decision: 'deny',
      reason: `Env Guard 已拒绝访问 .env 文件（${detail}）：该文件可能包含密钥。`,
      logs: [`blocked ${toolName}: ${hits.join(', ')}`]
    };
  }
};

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

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
  }
});
