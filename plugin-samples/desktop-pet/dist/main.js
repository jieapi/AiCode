'use strict';

const readline = require('readline');

const SETTINGS_PATH = 'data://pet-settings.json';
const DEFAULTS = { autoStart: false, width: 110, height: 130, visibilityPolicy: 'always' };

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

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function readSettings() {
  try {
    const file = await hostCall('host.fs.read', { path: SETTINGS_PATH });
    const parsed = JSON.parse(String(file.content || '{}'));
    return Object.assign({}, DEFAULTS, parsed);
  } catch (e) {
    return Object.assign({}, DEFAULTS);
  }
}

async function boot() {
  const settings = await readSettings();
  if (settings.autoStart !== true) {
    return { ok: true, skipped: true };
  }
  const width = Number(settings.width) || DEFAULTS.width;
  const height = Number(settings.height) || DEFAULTS.height;
  const visibilityPolicy = String(settings.visibilityPolicy || DEFAULTS.visibilityPolicy);
  let lastError = null;
  // App 进程启动时可能还没进入前台；前台服务被系统挡掉时短暂重试。
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const info = await hostCall('host.overlay.create', {
        surface: 'pet',
        width,
        height,
        visibilityPolicy
      });
      return { ok: true, overlayId: info.overlayId, width, height };
    } catch (error) {
      lastError = error;
      if (error.code === 'OVERLAY_SERVICE_FAILED' && attempt < 2) {
        await sleep(1200);
        continue;
      }
      break;
    }
  }
  return {
    ok: false,
    code: (lastError && lastError.code) || 'BOOT_FAILED',
    message: String((lastError && lastError.message) || lastError || 'unknown')
  };
}

const handlers = {
  'hooks.boot': () => boot(),
  // 浮层页面如果回传交互，这里先确认收到；需要状态动画时可在这里扩展。
  'overlay.message': () => ({ ok: true })
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
