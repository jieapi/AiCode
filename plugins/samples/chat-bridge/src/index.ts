import { definePlugin, emit, type JsonValue, type PluginHandler } from '@aicode/plugin-sdk';

const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
let reviewSeq = 0;

interface ReviewPayload {
  requestId: string;
  sessionId: string | null;
  runId: string | null;
  scope: string;
  agentName: string | null;
  toolName: string;
  arguments: JsonValue;
  appForeground: boolean;
}

interface PendingReview {
  resolve: (result: { decision: string; reason: string }) => void;
  timer: ReturnType<typeof setTimeout>;
  payload: ReviewPayload;
}

const pendingReviews = new Map<string, PendingReview>();

function finishReview(requestId: string, decision: string): boolean {
  const entry = pendingReviews.get(requestId);
  if (!entry) return false;
  clearTimeout(entry.timer);
  pendingReviews.delete(requestId);
  entry.resolve({ decision, reason: 'page approval' });
  emit('chat.permission.resolved', { requestId, decision });
  return true;
}

const handlers: Record<string, PluginHandler> = {
  'hooks.reviewPermission': (args) => {
    const requestId = String(args.callId || 'review-' + ++reviewSeq);
    const payload: ReviewPayload = {
      requestId,
      sessionId: args.sessionId ? String(args.sessionId) : null,
      runId: args.runId ? String(args.runId) : null,
      scope: args.scope ? String(args.scope) : 'main',
      agentName: args.agentName ? String(args.agentName) : null,
      toolName: args.toolName ? String(args.toolName) : 'tool',
      arguments: (args.arguments as JsonValue) ?? {},
      appForeground: args.appForeground === true
    };
    emit('chat.permission.requested', payload as unknown as JsonValue);
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!pendingReviews.has(requestId)) return;
        pendingReviews.delete(requestId);
        emit('chat.permission.expired', { requestId });
        resolve({ decision: 'pass', reason: 'page approval timeout' });
      }, REVIEW_TIMEOUT_MS);
      pendingReviews.set(requestId, { resolve, timer, payload });
    });
  },

  'ui.permissionDecision': (args) => {
    const requestId = String(args.requestId || '');
    const decision =
      args.decision === 'allow' || args.decision === 'deny' ? args.decision : 'pass';
    if (!finishReview(requestId, decision)) {
      return { ok: false, error: 'REVIEW_NOT_FOUND' };
    }
    return { ok: true, decision };
  },

  'ui.pageReady': () => ({
    pendingReviews: Array.from(pendingReviews.values()).map((entry) => entry.payload)
  }),

  'actions.notifySession': (args) => ({
    message: args.sessionId
      ? `已触发会话动作 ${String(args.sessionId).slice(0, 8)}`
      : '未收到会话上下文'
  })
};

definePlugin({ handlers });
