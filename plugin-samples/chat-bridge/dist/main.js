'use strict';

const readline = require('readline');

const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
let reviewSeq = 0;
const pendingReviews = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function sendEvent(name, payload) {
  send({
    jsonrpc: '2.0',
    method: 'plugin.event',
    params: { name, payload }
  });
}

function finishReview(requestId, decision) {
  const entry = pendingReviews.get(requestId);
  if (!entry) return false;
  clearTimeout(entry.timer);
  pendingReviews.delete(requestId);
  entry.resolve({ decision, reason: 'page approval' });
  sendEvent('chat.permission.resolved', { requestId, decision });
  return true;
}

const handlers = {
  // 宿主判定需要使用者确认工具调用时调用。把请求推给网页，等网页允许/拒绝；
  // 超时返回 pass，交回宿主自己的确认 UI。
  'hooks.reviewPermission': (args) => {
    const requestId = String(args.callId || ('review-' + (++reviewSeq)));
    const payload = {
      requestId,
      sessionId: args.sessionId || null,
      runId: args.runId || null,
      scope: args.scope || 'main',
      agentName: args.agentName || null,
      toolName: args.toolName || 'tool',
      arguments: args.arguments || {},
      appForeground: args.appForeground === true
    };
    sendEvent('chat.permission.requested', payload);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!pendingReviews.has(requestId)) return;
        pendingReviews.delete(requestId);
        sendEvent('chat.permission.expired', { requestId });
        resolve({ decision: 'pass', reason: 'page approval timeout' });
      }, REVIEW_TIMEOUT_MS);
      pendingReviews.set(requestId, { resolve, timer, payload });
    });
  },

  // 页面上的允许/拒绝按钮通过 aicode.invoke 调用。
  'ui.permissionDecision': (args) => {
    const requestId = String(args.requestId || '');
    const decision = args.decision === 'allow' || args.decision === 'deny'
      ? args.decision
      : 'pass';
    if (!finishReview(requestId, decision)) {
      return { ok: false, error: 'REVIEW_NOT_FOUND' };
    }
    return { ok: true, decision };
  },

  // 页面加载或重连时同步仍在等待的审批，避免刷新后卡片消失、hook 只能等超时。
  'ui.pageReady': () => ({
    pendingReviews: Array.from(pendingReviews.values()).map((entry) => entry.payload)
  }),

  // 槽位动作示例：宿主会把返回的 message 直接作为 toast 显示。
  'actions.notifySession': (args) => ({
    message: args.sessionId
      ? `已触发会话动作 ${String(args.sessionId).slice(0, 8)}`
      : '未收到会话上下文'
  })
};

function handleHostRequest(id, method, params) {
  if (method !== 'hook.invoke' && method !== 'ui.invoke') {
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
