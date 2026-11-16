import { definePlugin, hostCall, type PluginArgs, type PluginHandler } from '@jieapi/plugin-sdk';

const BALL_SIZE = 48;
const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
const FOREGROUND_POLL_MS = 1000;
const PREVIEW_LIMIT = 1800;

type ReviewDecision = 'allow' | 'deny' | 'pass';

interface ReviewRequest {
  requestId: string;
  toolName: string;
  preview: string;
  mode: string;
  agentName: string | null;
}

interface ReviewResult {
  decision: ReviewDecision;
  reason: string;
  logs: string[];
}

interface QueueItem {
  request: ReviewRequest;
  resolve: (result: ReviewResult) => void;
  timer: ReturnType<typeof setTimeout>;
}

interface OverlayInfo {
  overlayId: string;
}

interface ApprovalStatePayload {
  type: 'approvalState';
  request: ReviewRequest | null;
  count: number;
}

interface ActionPayload {
  type: 'ballReady' | 'ballClick' | 'foreground' | 'displayError';
  requestId?: string;
}

interface DecisionPayload {
  type: 'decision';
  requestId: string;
  decision: ReviewDecision;
}

type OverlayPayload = ApprovalStatePayload | ActionPayload | DecisionPayload;

interface ReviewPayload extends PluginArgs {
  callId?: unknown;
  toolName?: unknown;
  arguments?: unknown;
  mode?: unknown;
  agentName?: unknown;
  appForeground?: unknown;
}

interface OverlayMessage extends PluginArgs {
  overlayId?: unknown;
  payload?: unknown;
}

interface ApprovalState {
  ballId: string | null;
  ballPromise: Promise<string> | null;
  active: QueueItem | null;
  queue: QueueItem[];
  pollTimer: ReturnType<typeof setInterval> | null;
  pumping: boolean;
  publishing: Promise<void>;
  requestSeq: number;
}

const state: ApprovalState = {
  ballId: null,
  ballPromise: null,
  active: null,
  queue: [],
  pollTimer: null,
  pumping: false,
  publishing: Promise.resolve(),
  requestSeq: 0
};

function errorCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === 'string' && code ? code : undefined;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function readString(args: PluginArgs, key: string, fallback = ''): string {
  const value = args[key];
  return value === undefined || value === null ? fallback : String(value);
}

function summarizeArguments(value: unknown): string {
  let text: string;
  try {
    text = JSON.stringify(value === undefined ? {} : value, null, 2);
  } catch {
    text = String(value);
  }
  if (text.length > PREVIEW_LIMIT) text = text.slice(0, PREVIEW_LIMIT) + '\n…';
  return text;
}

async function isAppForeground(): Promise<boolean> {
  try {
    return (await hostCall<boolean>('host.app.isForeground')) === true;
  } catch {
    return true;
  }
}

async function ensureBall(): Promise<string> {
  if (state.ballId) return state.ballId;
  if (!state.ballPromise) {
    state.ballPromise = hostCall<OverlayInfo>('host.overlay.create', {
      surface: 'ball',
      width: BALL_SIZE,
      height: BALL_SIZE,
      visibilityPolicy: 'background'
    })
      .then((info) => {
        state.ballId = String(info.overlayId);
        return state.ballId;
      })
      .finally(() => {
        state.ballPromise = null;
      });
  }
  return state.ballPromise;
}

async function postBall(payload: OverlayPayload): Promise<void> {
  await ensureBall();
  try {
    await hostCall('host.overlay.postMessage', { surface: 'ball', payload });
  } catch (error) {
    if (errorCode(error) !== 'OVERLAY_NOT_FOUND') throw error;
    state.ballId = null;
    await ensureBall();
    await hostCall('host.overlay.postMessage', { surface: 'ball', payload });
  }
}

function publishState(): Promise<void> {
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

function refreshState(): void {
  void publishState().catch(() => {});
}

function stopForegroundPoll(): void {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}

function startForegroundPoll(): void {
  stopForegroundPoll();
  state.pollTimer = setInterval(async () => {
    const active = state.active;
    if (!active) {
      stopForegroundPoll();
      return;
    }
    if ((await isAppForeground()) && state.active === active) {
      finishActive('pass', 'App 已回到前台');
    }
  }, FOREGROUND_POLL_MS);
}

function finishActive(decision: ReviewDecision, reason: string): void {
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

function drainQueue(decision: ReviewDecision, reason: string): void {
  const pending = state.queue.splice(0);
  pending.forEach((item) => {
    clearTimeout(item.timer);
    item.resolve({ decision, reason, logs: [reason] });
  });
  refreshState();
}

function onQueuedTimeout(item: QueueItem): void {
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

async function pump(): Promise<void> {
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
  } catch (error) {
    const detail = errorCode(error) ?? errorMessage(error);
    finishActive('pass', '审批浮层不可用: ' + detail);
  } finally {
    state.pumping = false;
    if (!state.active && state.queue.length > 0) void pump();
  }
}

function reviewPayload(args: ReviewPayload): ReviewRequest {
  return {
    requestId: args.callId ? String(args.callId) : 'review-' + ++state.requestSeq,
    toolName: readString(args, 'toolName', 'unknown'),
    preview: summarizeArguments(args.arguments),
    mode: readString(args, 'mode'),
    agentName: args.agentName ? String(args.agentName) : null
  };
}

const handlers = {
  'hooks.boot': async (): Promise<{ ok: boolean; overlayId?: string | null; code?: string; message?: string }> => {
    try {
      await ensureBall();
      await publishState();
      return { ok: true, overlayId: state.ballId };
    } catch (error) {
      return {
        ok: false,
        code: errorCode(error) ?? 'OVERLAY_FAILED',
        message: errorMessage(error)
      };
    }
  },

  'hooks.reviewPermission': async (rawPayload: PluginArgs): Promise<ReviewResult> => {
    const payload = rawPayload as ReviewPayload;
    if (payload.appForeground === true || (await isAppForeground())) {
      return { decision: 'pass', reason: '', logs: ['App 在前台，交回应用内确认'] };
    }
    try {
      await ensureBall();
    } catch (error) {
      return {
        decision: 'pass',
        reason: '',
        logs: ['浮层不可用: ' + (errorCode(error) ?? errorMessage(error))]
      };
    }
    const request = reviewPayload(payload);
    return new Promise<ReviewResult>((resolve) => {
      const item: QueueItem = {
        request,
        resolve,
        timer: setTimeout(() => onQueuedTimeout(item), REVIEW_TIMEOUT_MS)
      };
      state.queue.push(item);
      if (state.active) refreshState();
      void pump();
    });
  },

  'overlay.message': async (rawArgs: PluginArgs): Promise<{ ok: boolean; error?: string }> => {
    const args = rawArgs as OverlayMessage;
    const payload: Record<string, unknown> =
      typeof args.payload === 'object' && args.payload !== null
        ? (args.payload as Record<string, unknown>)
        : {};
    const overlayId = args.overlayId ? String(args.overlayId) : '';
    if (state.ballId && overlayId && overlayId !== state.ballId) return { ok: true };
    if (payload.type === 'ballReady' || payload.type === 'ballClick') {
      await publishState();
      if (!state.active && state.queue.length > 0) void pump();
      return { ok: true };
    }
    if (payload.type === 'foreground') {
      const active = state.active;
      if (active && (await isAppForeground()) && state.active === active) {
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
      if (String(payload.requestId ?? '') !== state.active.request.requestId) {
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
} satisfies Record<string, PluginHandler>;

definePlugin({ handlers });
