'use strict';

const readline = require('readline');

const BALL_SIZE = 48;
// 宿主给代审核 5 分钟；插件提前收尾，避免宿主已超时后浮层还挂在屏幕上。
const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
const FOREGROUND_POLL_MS = 1000;
const PREVIEW_LIMIT = 1800;

let hostSeq = 0;
const hostPending = new Map();

const state = {
  ballId: null,
  ballPromise: null,
  active: null,
  queue: [],
  pollTimer: null,
  pumping: false,
  publishing: Promise.resolve(),
  requestSeq: 0
};

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

function summarizeArguments(value) {
  let text;
  try {
    text = JSON.stringify(value === undefined ? {} : value, null, 2);
  } catch (e) {
    text = String(value);
  }
  if (text.length > PREVIEW_LIMIT) text = text.slice(0, PREVIEW_LIMIT) + '\n…';
  return text;
}

async function isAppForeground() {
  try {
    return (await hostCall('host.app.isForeground')) === true;
  } catch (e) {
    // 状态未知时按前台处理，交回宿主确认，避免审批被后台浮层挂住。
    return true;
  }
}

async function ensureBall() {
  if (state.ballId) return state.ballId;
  if (!state.ballPromise) {
    state.ballPromise = hostCall('host.overlay.create', {
      surface: 'ball',
      width: BALL_SIZE,
      height: BALL_SIZE,
      visibilityPolicy: 'background'
    }).then((info) => {
      state.ballId = String(info.overlayId);
      return state.ballId;
    }).finally(() => {
      state.ballPromise = null;
    });
  }
  return state.ballPromise;
}

async function postBall(payload) {
  await ensureBall();
  try {
    await hostCall('host.overlay.postMessage', { surface: 'ball', payload });
  } catch (error) {
    if (error.code !== 'OVERLAY_NOT_FOUND') throw error;
    state.ballId = null;
    await ensureBall();
    await hostCall('host.overlay.postMessage', { surface: 'ball', payload });
  }
}

function publishState() {
  // 串行发布完整快照，避免上一条审批的收起消息覆盖下一条请求。
  state.publishing = state.publishing.catch(() => {}).then(() => {
    if (!state.active && state.queue.length > 0) return;
    return postBall({
      type: 'approvalState',
      request: state.active ? state.active.request : null,
      count: state.queue.length + (state.active ? 1 : 0)
    });
  });
  return state.publishing;
}

function refreshState() {
  void publishState().catch(() => {});
}

function stopForegroundPoll() {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}

function startForegroundPoll() {
  stopForegroundPoll();
  state.pollTimer = setInterval(async () => {
    const active = state.active;
    if (!active) {
      stopForegroundPoll();
      return;
    }
    if (await isAppForeground() && state.active === active) {
      finishActive('pass', 'App 已回到前台');
    }
  }, FOREGROUND_POLL_MS);
}

function finishActive(decision, reason) {
  const active = state.active;
  if (!active) return;
  state.active = null;
  clearTimeout(active.timer);
  stopForegroundPoll();
  active.resolve({ decision, reason, logs: [reason] });
  if (state.queue.length > 0) {
    void pump();
  } else {
    refreshState();
  }
}

function drainQueue(decision, reason) {
  const pending = state.queue.splice(0);
  pending.forEach((item) => {
    clearTimeout(item.timer);
    item.resolve({ decision, reason, logs: [reason] });
  });
  refreshState();
}

function onQueuedTimeout(item) {
  if (state.active === item) {
    finishActive('pass', '后台审批等待超时');
    return;
  }
  const index = state.queue.indexOf(item);
  if (index < 0) return;
  state.queue.splice(index, 1);
  item.resolve({ decision: 'pass', reason: '后台审批等待超时', logs: ['后台审批等待超时'] });
  refreshState();
  if (!state.active) void pump();
}

async function pump() {
  if (state.pumping || state.active || state.queue.length === 0) return;
  state.pumping = true;
  try {
    if (await isAppForeground()) {
      drainQueue('pass', 'App 处于前台，交回应用内确认');
      return;
    }
    const next = state.queue.shift();
    if (!next) return;
    state.active = next;
    startForegroundPoll();
    await publishState();
  } catch (e) {
    finishActive('pass', '审批浮层不可用: ' + (e.code || e.message));
  } finally {
    state.pumping = false;
    if (!state.active && state.queue.length > 0) void pump();
  }
}

const handlers = {
  'hooks.boot': async () => {
    try {
      await ensureBall();
      await publishState();
      return { ok: true, overlayId: state.ballId };
    } catch (e) {
      return { ok: false, code: e.code || 'OVERLAY_FAILED', message: String(e.message || e) };
    }
  },

  'hooks.reviewPermission': async (payload) => {
    if (payload.appForeground === true || await isAppForeground()) {
      return { decision: 'pass', logs: ['App 在前台，交回应用内确认'] };
    }
    try {
      await ensureBall();
    } catch (e) {
      return { decision: 'pass', logs: ['浮层不可用: ' + (e.code || e.message)] };
    }
    const requestId = String(payload.callId || ('review-' + (++state.requestSeq)));
    const request = {
      requestId,
      toolName: String(payload.toolName || 'unknown'),
      preview: summarizeArguments(payload.arguments),
      mode: String(payload.mode || ''),
      agentName: payload.agentName ? String(payload.agentName) : null
    };
    return new Promise((resolve) => {
      const item = { request, resolve, timer: null };
      item.timer = setTimeout(() => onQueuedTimeout(item), REVIEW_TIMEOUT_MS);
      state.queue.push(item);
      if (state.active) refreshState();
      void pump();
    });
  },

  'overlay.message': async (args) => {
    const payload = args.payload && typeof args.payload === 'object' ? args.payload : {};
    const overlayId = args.overlayId ? String(args.overlayId) : '';
    if (state.ballId && overlayId && overlayId !== state.ballId) return { ok: true };
    if (payload.type === 'ballReady' || payload.type === 'ballClick') {
      await publishState();
      if (!state.active && state.queue.length > 0) void pump();
      return { ok: true };
    }
    if (payload.type === 'foreground') {
      const active = state.active;
      if (active && await isAppForeground() && state.active === active) {
        finishActive('pass', 'App 已回到前台');
      }
      return { ok: true };
    }
    if (payload.type === 'displayError') {
      if (state.active && payload.requestId === state.active.request.requestId) {
        finishActive('pass', '无法展开审批浮层');
      }
      return { ok: true };
    }
    if (payload.type === 'decision' && state.active) {
      if (String(payload.requestId || '') !== state.active.request.requestId) {
        return { ok: false, error: 'stale request' };
      }
      if (payload.decision !== 'allow' && payload.decision !== 'deny') {
        return { ok: false, error: 'invalid decision' };
      }
      const allow = payload.decision === 'allow';
      finishActive(
        allow ? 'allow' : 'deny',
        allow ? '用户在后台审批浮层中同意' : '用户在后台审批浮层中拒绝'
      );
      return { ok: true };
    }
    return { ok: true };
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
