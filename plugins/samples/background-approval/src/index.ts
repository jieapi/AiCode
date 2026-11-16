import { definePlugin, hostCall, type PluginArgs, type PluginHandler } from '@aicode/plugin-sdk';

const BALL_SIZE = 48;
const REVIEW_TIMEOUT_MS = 4.5 * 60 * 1000;
const FOREGROUND_POLL_MS = 1000;
const PREVIEW_LIMIT = 1800;

interface ReviewRequest {
  requestId: string;
  toolName: string;
  preview: string;
  mode: string;
  agentName: string | null;
}

interface ReviewResult {
  decision: string;
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

const state: {
  ballId: string | null;
  ballPromise: Promise<string> | null;
  active: QueueItem | null;
  queue: QueueItem[];
  pollTimer: ReturnType<typeof setInterval> | null;
  pumping: boolean;
  publishing: Promise<void>;
  requestSeq: number;
} = {
  ballId: null,
  ballPromise: null,
  active: null,
  queue: [],
  pollTimer: null,
  pumping: false,
  publishing: Promise.resolve(),
  requestSeq: 0
};

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

async function postBall(payload: Record<string, unknown>): Promise<void> {
  await ensureBall();
  try {
    await hostCall('host.overlay.postMessage', { surface: 'ball', payload });
  } catch (error) {
    if ((error as { code?: string }).code !== 'OVERLAY_NOT_FOUND') throw error;
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

function finishActive(decision: string, reason: string): void {
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

function drainQueue(decision: string, reason: string): void {
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
    const detail = (error as { code?: string; message?: string }).code ||
      (error as { message?: string }).message;
    finishActive('pass', '审批浮层不可用: ' + detail);
  } finally {
    state.pumping = false;
    if (!state.active && state.queue.length > 0) void pump();
  }
}

function reviewPayload(args: PluginArgs): ReviewRequest {
  return {
    requestId: String(args.callId || 'review-' + ++state.requestSeq),
    toolName: String(args.toolName || 'unknown'),
    preview: summarizeArguments(args.arguments),
    mode: String(args.mode || ''),
    agentName: args.agentName ? String(args.agentName) : null
  };
}

const handlers: Record<string, PluginHandler> = {
  'hooks.boot': async () => {
    try {
      await ensureBall();
      await publishState();
      return { ok: true, overlayId: state.ballId };
    } catch (error) {
      return {
        ok: false,
        code: (error as { code?: string }).code || 'OVERLAY_FAILED',
        message: String((error as Error).message || error)
      };
    }
  },

  'hooks.reviewPermission': async (payload) => {
    if (payload.appForeground === true || (await isAppForeground())) {
      return { decision: 'pass', logs: ['App 在前台，交回应用内确认'] };
    }
    try {
      await ensureBall();
    } catch (error) {
      return {
        decision: 'pass',
        logs: ['浮层不可用: ' + ((error as { code?: string }).code || (error as Error).message)]
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

  'overlay.message': async (args) => {
    const payload =
      args.payload && typeof args.payload === 'object'
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

definePlugin({ handlers });
