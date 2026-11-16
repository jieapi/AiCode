<template>
  <div id="app">
    <div class="shell" :class="{ expanded, ready }">
      <div class="mark">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z"/>
          <path d="M8.5 12.2l2.3 2.3 4.7-4.7"/>
        </svg>
      </div>
      <span class="badge" :class="{ show: count > 0 }">{{ badgeText }}</span>
      <div class="panel" :aria-hidden="!(expanded && ready)">
        <div class="header">
          <div class="heading">
            <div class="title">{{ title }}</div>
            <div class="subtitle">{{ subtitle }}</div>
          </div>
          <button class="collapse" type="button" aria-label="收起审批面板" title="收起" @click="collapse">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M6 12h12"/></svg>
          </button>
        </div>
        <div class="body">
          <div v-if="!request" class="empty"><strong>暂无待审批请求</strong>有新的授权请求时会自动展开。<br>拖动标题栏可以移动面板。</div>
          <template v-else>
            <div class="tool">{{ request.toolName }}</div>
            <pre ref="previewEl">{{ request.preview }}</pre>
          </template>
        </div>
        <div v-if="request" class="actions">
          <button class="deny" type="button" :disabled="buttonsDisabled" @click="decide('deny')">{{ denyText }}</button>
          <button class="allow" type="button" :disabled="buttonsDisabled" @click="decide('allow')">{{ allowText }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { JsonValue, OverlayMessage } from '@jieapi/plugin-sdk/page';

const BALL_SIZE = 48;
const PANEL_WIDTH = 320;
const PANEL_HEIGHT = 280;

interface ApprovalRequest {
  requestId: string;
  toolName: string;
  preview: string;
  mode: string;
  agentName: string | null;
}

interface ApprovalStateMessage {
  type: 'approvalState';
  request?: ApprovalRequest | null;
  count?: number;
}

interface ResizeMessage {
  type: 'resize';
  width: number;
  height: number;
  x: number;
  y: number;
}

interface DragEndMessage {
  type: 'dragEnd';
  x: number;
  y: number;
}

interface AppStateMessage {
  type: 'appState';
  foreground?: boolean;
}

interface SimpleMessage {
  type: 'ballReady' | 'ballClick' | 'click' | 'foreground' | 'displayError';
  requestId?: string;
}

type IncomingMessage = ApprovalStateMessage | ResizeMessage | DragEndMessage | AppStateMessage | SimpleMessage;
type OverlayPayload = Record<string, JsonValue>;

interface Point {
  x: number;
  y: number;
}

const expanded = ref<boolean>(false);
const ready = ref<boolean>(false);
const transitioning = ref<boolean>(false);
const submitting = ref<boolean>(false);
const foreground = ref<boolean>(false);
const initialized = ref<boolean>(false);
const request = ref<ApprovalRequest | null>(null);
const count = ref<number>(0);
const denyText = ref<string>('拒绝');
const allowText = ref<string>('同意');
const previewEl = ref<HTMLElement | null>(null);

let minimizedRequestId: string | null = null;
let ballAnchor: Point | null = null;
let panelOrigin: Point | null = null;
let transitionSeq = 0;
let offMessage: (() => void) | null = null;
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const badgeText = computed(() => (count.value > 99 ? '99+' : String(count.value)));
const title = computed(() => (request.value ? '审批请求' : '后台审批'));
const subtitle = computed(() => {
  if (!request.value) return '暂无待审批';
  return (count.value > 1 ? count.value + ' 项待审批' : '等待你的确认') +
    (request.value.agentName ? ' · ' + request.value.agentName : '');
});
const buttonsDisabled = computed(
  () => !request.value || submitting.value || transitioning.value || !expanded.value || foreground.value
);

function post(payload: OverlayPayload): Promise<void> {
  return aicode.overlay.postMessage(payload as unknown as JsonValue);
}

function updateDragRegion(): Promise<void> {
  return aicode.overlay.setDragRegion({
    left: 0,
    top: 0,
    width: expanded.value ? PANEL_WIDTH - 48 : BALL_SIZE,
    height: BALL_SIZE
  });
}

async function setExpanded(value: boolean): Promise<void> {
  if (!initialized.value || expanded.value === value) return;
  expanded.value = value;
  transitioning.value = true;
  const seq = ++transitionSeq;
  ready.value = false;
  await nextTick();
  try {
    await updateDragRegion();
    if (seq !== transitionSeq) return;
    await aicode.overlay.resize(value ? PANEL_WIDTH : BALL_SIZE, value ? PANEL_HEIGHT : BALL_SIZE, {
      animate: !reducedMotion,
      x: !value && ballAnchor ? ballAnchor.x : undefined,
      y: !value && ballAnchor ? ballAnchor.y : undefined
    });
  } catch (error) {
    if (seq !== transitionSeq) return;
    transitioning.value = false;
    expanded.value = false;
    ready.value = false;
    if (request.value) post({ type: 'displayError', requestId: request.value.requestId }).catch(() => {});
  }
}

function applyState(message: ApprovalStateMessage): void {
  const previousId = request.value?.requestId;
  request.value = message.request ?? null;
  count.value = Math.max(0, Number(message.count) || 0);
  if (previousId !== (request.value && request.value.requestId)) {
    submitting.value = false;
    denyText.value = '拒绝';
    allowText.value = '同意';
    nextTick(() => {
      if (previewEl.value) previewEl.value.scrollTop = 0;
    }).catch(() => {});
  }
  if (request.value && request.value.requestId !== minimizedRequestId && !foreground.value) {
    void setExpanded(true);
  } else if (!request.value && previousId) {
    minimizedRequestId = null;
    void setExpanded(false);
  }
}

function decide(decision: 'allow' | 'deny'): void {
  if (buttonsDisabled.value) return;
  const requestId = request.value.requestId;
  submitting.value = true;
  if (decision === 'allow') allowText.value = '已同意';
  else denyText.value = '已拒绝';
  post({ type: 'decision', requestId, decision }).catch(() => {
    if (!request.value || request.value.requestId !== requestId) return;
    submitting.value = false;
    denyText.value = '拒绝';
    allowText.value = '同意';
  });
}

function collapse(): void {
  minimizedRequestId = request.value && request.value.requestId;
  void setExpanded(false);
}

function handleMessage(rawMessage: OverlayMessage): void {
  const message = rawMessage as unknown as IncomingMessage;
  if (message.type === 'approvalState') {
    applyState(message);
  } else if (message.type === 'click' && !expanded.value && !transitioning.value) {
    minimizedRequestId = null;
    void setExpanded(true);
    if (count.value > 0) post({ type: 'ballClick' }).catch(() => {});
  } else if (message.type === 'resize') {
    const targetWidth = expanded.value ? PANEL_WIDTH : BALL_SIZE;
    const targetHeight = expanded.value ? PANEL_HEIGHT : BALL_SIZE;
    if (message.width !== targetWidth || message.height !== targetHeight) return;
    transitioning.value = false;
    ready.value = expanded.value;
    if (expanded.value) {
      panelOrigin = { x: message.x, y: message.y };
    } else {
      ballAnchor = { x: message.x, y: message.y };
    }
    updateDragRegion().catch(() => {});
  } else if (message.type === 'dragEnd') {
    const position = { x: message.x, y: message.y };
    if (expanded.value && panelOrigin && ballAnchor) {
      ballAnchor.x += position.x - panelOrigin.x;
      ballAnchor.y += position.y - panelOrigin.y;
      panelOrigin = position;
    } else if (!expanded.value) {
      ballAnchor = position;
    }
  } else if (message.type === 'appState') {
    foreground.value = message.foreground === true;
    if (foreground.value) {
      void setExpanded(false);
      post({ type: 'foreground' }).catch(() => {});
    } else if (request.value && request.value.requestId !== minimizedRequestId) {
      void setExpanded(true);
    }
  }
}

onMounted(async () => {
  offMessage = aicode.overlay.onMessage(handleMessage);
  try {
    const self = await aicode.overlay.self();
    foreground.value = self.foreground === true;
    ballAnchor = { x: self.x, y: self.y };
    await aicode.overlay.resize(BALL_SIZE, BALL_SIZE);
    await updateDragRegion();
    initialized.value = true;
    if (request.value && !foreground.value) void setExpanded(true);
    await post({ type: 'ballReady' });
  } catch (error) {
    if (request.value) post({ type: 'displayError', requestId: request.value.requestId }).catch(() => {});
  }
});

onBeforeUnmount(() => {
  if (offMessage) offMessage();
});
</script>

<style>
html, body {
      margin: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: transparent;
      color: #fff;
      font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
      user-select: none;
      -webkit-user-select: none;
      -webkit-tap-highlight-color: transparent;
    }
    * { box-sizing: border-box; }
    .shell {
      position: absolute;
      left: 2px;
      top: 2px;
      width: 316px;
      height: 276px;
      overflow: hidden;
      border-radius: 18px;
      clip-path: inset(0 272px 232px 0 round 22px);
      background: #3d6deb;
      box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .18);
      transition: clip-path .28s cubic-bezier(.2, 0, 0, 1), background .28s ease;
    }
    .shell.expanded {
      clip-path: inset(0 round 18px);
      background: rgba(28, 30, 38, .98);
    }
    .mark {
      position: absolute;
      z-index: 1;
      left: 0;
      top: 0;
      width: 44px;
      height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
      pointer-events: none;
    }
    .mark svg { width: 22px; height: 22px; }
    .badge {
      position: absolute;
      z-index: 2;
      top: 0;
      left: 26px;
      min-width: 16px;
      height: 16px;
      padding: 0 4px;
      border-radius: 20px;
      background: #f05d69;
      font-size: 10px;
      font-weight: 600;
      line-height: 16px;
      text-align: center;
      opacity: 0;
      transform: scale(.6);
      transition: opacity .12s ease, transform .18s ease;
    }
    .badge.show { opacity: 1; transform: scale(1); }
    .shell.expanded .badge { opacity: 0; transform: scale(.6); }
    .panel {
      width: 316px;
      height: 276px;
      display: flex;
      flex-direction: column;
      opacity: 0;
      pointer-events: none;
      transform: translateY(6px);
      transition: opacity .09s ease, transform .18s ease;
    }
    .shell.expanded.ready .panel {
      opacity: 1;
      pointer-events: auto;
      transform: translateY(0);
      transition: opacity .16s ease, transform .2s ease;
    }
    .header {
      flex: none;
      height: 48px;
      display: flex;
      align-items: center;
      padding: 0 4px 0 46px;
      gap: 8px;
    }
    .heading { flex: 1; min-width: 0; }
    .title { font-size: 13px; font-weight: 600; }
    .subtitle {
      margin-top: 2px;
      color: rgba(255, 255, 255, .5);
      font-size: 10px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    button {
      border: 0;
      color: #fff;
      font: inherit;
      cursor: pointer;
      touch-action: manipulation;
      transition: background .15s ease, transform .12s ease, opacity .15s ease;
    }
    button:active { transform: scale(.96); }
    button:focus-visible { outline: 2px solid #adc4ff; outline-offset: -3px; }
    button:disabled { opacity: .5; cursor: default; }
    .collapse {
      flex: none;
      width: 40px;
      height: 40px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 12px;
      background: transparent;
    }
    .collapse:active { background: rgba(255, 255, 255, .1); }
    .collapse svg { width: 18px; height: 18px; }
    .body {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 4px 12px 0;
    }
    .tool {
      flex: none;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: rgba(255, 255, 255, .65);
      font: 600 12px/18px ui-monospace, SFMono-Regular, Menlo, monospace;
    }
    pre {
      flex: 1;
      min-height: 0;
      margin: 0;
      overflow: auto;
      padding: 9px 10px;
      border-radius: 10px;
      background: rgba(255, 255, 255, .065);
      color: rgba(255, 255, 255, .85);
      font: 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
      white-space: pre-wrap;
      word-break: break-word;
      overscroll-behavior: contain;
    }
    .actions { flex: none; display: flex; gap: 8px; padding: 12px; }
    .actions button { flex: 1; height: 42px; border-radius: 12px; font-size: 13px; font-weight: 600; }
    .deny { background: rgba(255, 255, 255, .1); }
    .allow { background: #3d7eff; }
    .empty {
      margin: auto;
      color: rgba(255, 255, 255, .5);
      font-size: 12px;
      line-height: 1.8;
      text-align: center;
    }
    .empty strong { display: block; color: rgba(255, 255, 255, .8); font-size: 13px; }
    [hidden] { display: none !important; }
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { transition: none !important; }
    }
</style>
