import { definePlugin, emit, type JsonValue, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
let reviewSeq = 0;

type ReviewDecision = 'allow' | 'deny' | 'pass';

interface ReviewResult {
  decision: ReviewDecision;
  reason: string;
}

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
  resolve: (result: ReviewResult) => void;
  timer: ReturnType<typeof setTimeout>;
  payload: ReviewPayload;
}

interface ReviewPermissionArgs extends PluginArgs {
  callId?: unknown;
  sessionId?: unknown;
  runId?: unknown;
  scope?: unknown;
  agentName?: unknown;
  toolName?: unknown;
  arguments?: unknown;
  appForeground?: unknown;
}

interface PermissionDecisionArgs extends PluginArgs {
  requestId?: unknown;
  decision?: unknown;
}

const pendingReviews = new Map<string, PendingReview>();

function toJsonValue(value: unknown): JsonValue {
  if (value === null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return value;
  }
  if (Array.isArray(value)) return value.map(toJsonValue);
  if (typeof value === 'object') {
    const result: Record<string, JsonValue> = {};
    for (const [key, item] of Object.entries(value)) result[key] = toJsonValue(item);
    return result;
  }
  return null;
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

function finishReview(requestId: string, decision: ReviewDecision): boolean {
  const entry = pendingReviews.get(requestId);
  if (!entry) return false;
  clearTimeout(entry.timer);
  pendingReviews.delete(requestId);
  entry.resolve({ decision, reason: 'page approval' });
  emit('chat.permission.resolved', { requestId, decision });
  return true;
}

const handlers = {
  'hooks.reviewPermission': (rawArgs: PluginArgs): Promise<ReviewResult> => {
    const args = rawArgs as ReviewPermissionArgs;
    const requestId = args.callId ? String(args.callId) : 'review-' + ++reviewSeq;
    const payload: ReviewPayload = {
      requestId,
      sessionId: args.sessionId ? String(args.sessionId) : null,
      runId: args.runId ? String(args.runId) : null,
      scope: args.scope ? String(args.scope) : 'main',
      agentName: args.agentName ? String(args.agentName) : null,
      toolName: args.toolName ? String(args.toolName) : 'tool',
      arguments: toJsonValue(args.arguments ?? {}),
      appForeground: args.appForeground === true
    };
    emit('chat.permission.requested', payload as unknown as JsonValue);
    return new Promise<ReviewResult>((resolve) => {
      const timer = setTimeout(() => {
        if (!pendingReviews.has(requestId)) return;
        pendingReviews.delete(requestId);
        emit('chat.permission.expired', { requestId });
        resolve({ decision: 'pass', reason: 'page approval timeout' });
      }, REVIEW_TIMEOUT_MS);
      pendingReviews.set(requestId, { resolve, timer, payload });
    });
  },

  'ui.permissionDecision': (rawArgs: PluginArgs): { ok: boolean; decision?: ReviewDecision; error?: string } => {
    const args = rawArgs as PermissionDecisionArgs;
    const requestId = readString(args, 'requestId');
    const decision: ReviewDecision =
      args.decision === 'allow' || args.decision === 'deny' ? args.decision : 'pass';
    if (!finishReview(requestId, decision)) {
      return { ok: false, error: 'REVIEW_NOT_FOUND' };
    }
    return { ok: true, decision };
  },

  'ui.pageReady': (): { pendingReviews: ReviewPayload[] } => ({
    pendingReviews: Array.from(pendingReviews.values()).map((entry) => entry.payload)
  }),

  'actions.notifySession': (rawArgs: PluginArgs): { message: string } => {
    const args = rawArgs as PluginArgs;
    return {
      message: args.sessionId
        ? `已触发会话动作 ${String(args.sessionId).slice(0, 8)}`
        : '未收到会话上下文'
    };
  }
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
