'use strict';

const readline = require('readline');

const ENDPOINT = 'https://httpbin.org/bearer';
const SECRET_KEY = 'api-token';

let hostSeq = 0;
const hostPending = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

async function readToken() {
  const result = await hostCall('host.secrets.get', { key: SECRET_KEY });
  return String((result && result.value) || '');
}

async function keyNames() {
  const result = await hostCall('host.secrets.list');
  return Array.isArray(result && result.keys) ? result.keys : [];
}

// 用已保存的 Key 请求需要 Bearer 认证的接口；Key 只从宿主加密存储读出后发往该地址，
// 返回值与页面都不回显原文，避免凭据出现在对话记录或页面上。
async function callEndpoint() {
  const token = await readToken();
  if (!token) {
    return { ok: false, error: 'NO_TOKEN', message: '还没有保存 API Key，先在配置页保存一条。' };
  }
  try {
    const response = await hostCall('host.http.request', {
      url: ENDPOINT,
      headers: { Authorization: 'Bearer ' + token },
      timeout: 20
    });
    let body = {};
    try {
      body = JSON.parse(String(response.body || '{}'));
    } catch (e) {
      body = {};
    }
    return {
      ok: response.status === 200 && body.authenticated === true,
      status: response.status,
      authenticated: body.authenticated === true
    };
  } catch (error) {
    return { ok: false, error: error.code || 'HTTP_FAILED', message: String(error.message || error) };
  }
}

const handlers = {
  'tools.whoami': async () => {
    const result = await callEndpoint();
    if (result.ok) return { authenticated: true, status: result.status };
    return {
      authenticated: false,
      status: result.status === undefined ? null : result.status,
      message: result.message || 'API Key 未被接受'
    };
  },

  'actions.saveKey': async (args) => {
    const token = String(args.token || '').trim();
    if (!token) return { ok: false, message: 'API Key 不能为空' };
    await hostCall('host.secrets.set', { key: SECRET_KEY, value: token });
    return { ok: true, keys: await keyNames() };
  },

  'actions.deleteKey': async () => {
    await hostCall('host.secrets.delete', { key: SECRET_KEY });
    return { ok: true, keys: await keyNames() };
  },

  'actions.listKeys': async () => ({ ok: true, keys: await keyNames() }),

  'actions.test': async () => callEndpoint()
};

function handleHostRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'ui.invoke') {
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
      error: { code: error && error.code ? error.code : 'PLUGIN_ERROR', message: String((error && error.message) || error) }
    }));
}

function handleHostResponse(id, message) {
  const pending = hostPending.get(id);
  if (!pending) return;
  hostPending.delete(id);
  if (message.error) {
    const error = new Error(message.error.message || 'host error');
    error.code = message.error.code || 'HOST_ERROR';
    pending.reject(error);
  } else {
    pending.resolve(message.result === undefined ? null : message.result);
  }
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
