<template>
  <div id="app">
    <div class="app">
        <header>
          <div>
            <h1>对话桥</h1>
            <p class="subtitle">给 AI 下指令，读取回复；可作为 QQ / 微信机器人接入示例</p>
          </div>
          <div class="header-actions">
            <span id="status" class="status" :class="statusKind">{{ statusText }}</span>
            <button id="new-session" class="ghost" type="button" @click="newSession">新会话</button>
          </div>
        </header>

        <section v-if="currentPermission" id="permission" class="permission">
          <h2>需要授权：{{ currentPermission.toolName || '工具' }}</h2>
          <p>AI 想调用这个工具。允许只对本次调用生效；拒绝后 AI 会收到失败结果。</p>
          <pre>{{ permissionArgs }}</pre>
          <div v-if="permissionError" class="permission-error">{{ permissionError }}</div>
          <div class="permission-actions">
            <button type="button" class="deny" :disabled="resolvingPermission" @click="resolvePermission(currentPermission.requestId, 'deny')">拒绝</button>
            <button type="button" :disabled="resolvingPermission" @click="resolvePermission(currentPermission.requestId, 'allow')">{{ resolvingPermission ? '处理中…' : '允许本次' }}</button>
          </div>
        </section>

        <main ref="messagesEl" id="messages" class="messages" aria-live="polite">
          <div v-if="!visibleMessages.length && !pending" class="empty">
            <strong>对话桥已就绪</strong><br>输入一条指令开始。<br>回复会显示在这里，也可以点“复制”交给外部机器人。
          </div>
          <template v-for="message in visibleMessages" :key="message.id">
            <div v-if="message.role === 'tool'" class="tool-row">
              工具 {{ message.toolName || 'tool' }}{{ message.isError ? ' 失败' : ' 完成' }}
            </div>
            <div v-else-if="message.role === 'system'" class="tool-row" :class="{ error: message.error }">{{ message.content }}</div>
            <div v-else class="bubble" :class="[message.role, { error: message.error }]">
              <div class="bubble-text">{{ message.content || '' }}</div>
              <button v-if="message.role === 'assistant' && message.content" type="button" class="copy" @click="copyText(message.content, message.id)">
                {{ copiedId === message.id ? '已复制' : '复制' }}
              </button>
            </div>
          </template>
          <div v-if="pending" class="tool-row pending">AI 正在执行…</div>
        </main>

        <details class="hint">
          <summary>接入提示：外部消息如何变成 AI 回复</summary>
          外部消息到达后调用 <code>aicode.chat.sendMessage(sessionId, text)</code>；<code>agent.run.finished</code> hook 会通知页面；随后调用 <code>aicode.chat.listMessages</code>，把最后一条 assistant 文本回传给 QQ / 微信。页面关闭后轮询停止，机器人服务应改用插件进程或自己的后端常驻。
        </details>

        <footer class="composer">
          <textarea id="input" v-model="inputText" rows="1" placeholder="输入指令，例如：列出当前目录并总结项目结构" @keydown.enter="onInputKeydown"></textarea>
          <div class="composer-actions">
            <button v-if="pending" id="stop" class="ghost" type="button" @click="stopRun">停止</button>
            <button id="send" type="button" :disabled="!apiReady() || pending" @click="sendMessage">发送</button>
          </div>
        </footer>
      </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { AicodeEvent, JsonValue } from '@jieapi/plugin-sdk/page';

const STORAGE_PATH = 'data://chat-bridge.json';
const POLL_MS = 1000;
const IDLE_FINISH_MS = 10000;
const MAX_MESSAGES = 300;

interface ChatItem {
  id: string;
  role: string;
  content: string;
  timestamp: number;
  local?: boolean;
  error?: boolean;
  isCompactionMarker?: boolean;
  isContextSummary?: boolean;
  isCompacted?: boolean;
}

interface PermissionRequest {
  requestId: string;
  sessionId?: string | null;
  toolName?: string;
  arguments?: JsonValue;
}

interface RunFinishedPayload {
  sessionId?: string;
  status?: string;
  error?: string;
  errorCode?: string;
}

interface UiLifecyclePayload {
  visible?: boolean;
  reason?: string;
}

const messages = ref<ChatItem[]>([]);
const pending = ref<boolean>(false);
const statusText = ref<string>('连接中…');
const statusKind = ref<string>('');
const inputText = ref<string>('');
const permissionRequests = ref<PermissionRequest[]>([]);
const resolvingPermission = ref<boolean>(false);
const permissionError = ref<string>('');
const copiedId = ref<string | null>(null);
const messagesEl = ref<HTMLElement | null>(null);

let sessionId: string | null = null;
let lastTimestamp = 0;
let lastActivityAt = 0;
let pageVisible = true;
let polling = false;
let initializing = false;
let optimisticId: string | null = null;
let turnId = 0;
let sawAssistantSinceSend = false;
let runFinished = false;
let offEvents: (() => void) | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let finishTimer: ReturnType<typeof setInterval> | null = null;

const visibleMessages = computed(() => messages.value.filter((message) => {
  return !message.isCompactionMarker && !message.isContextSummary && !message.isCompacted &&
    (message.role === 'user' || message.role === 'assistant' || message.role === 'tool' || message.role === 'system');
}));

const currentPermission = computed(() => permissionRequests.value[0] || null);

const permissionArgs = computed(() => {
  if (!currentPermission.value) return '';
  let args = '';
  try {
    args = JSON.stringify(currentPermission.value.arguments || {}, null, 2);
  } catch (error) {
    args = String(currentPermission.value.arguments || '');
  }
  return args.length > 2000 ? args.slice(0, 2000) + '\n…' : args;
});

function apiReady(): boolean {
  return Boolean(window.aicode && aicode.chat);
}

function setStatus(text: string, kind = ''): void {
  statusText.value = text;
  statusKind.value = kind || '';
}

function addSystem(text: string, kind = ''): void {
  messages.value.push({
    id: 'local-' + Date.now() + '-' + Math.random().toString(36).slice(2),
    role: 'system',
    content: text,
    timestamp: Date.now(),
    local: true,
    error: kind === 'error'
  });
}

function queuePermission(payload: PermissionRequest): void {
  if (!payload || !payload.requestId || !sessionId) return;
  if (payload.sessionId && payload.sessionId !== sessionId) return;
  if (permissionRequests.value.some((item) => item.requestId === payload.requestId)) return;
  permissionRequests.value.push(payload);
  permissionError.value = '';
  pending.value = true;
  setStatus('等待授权：' + (payload.toolName || '工具'), 'busy');
}

function removePermission(requestId: string): void {
  permissionRequests.value = permissionRequests.value.filter((item) => item.requestId !== requestId);
}

function clearPermissions(): void {
  permissionRequests.value = [];
  permissionError.value = '';
}

async function resolvePermission(requestId: string, decision: 'allow' | 'deny'): Promise<void> {
  if (resolvingPermission.value) return;
  if (!apiReady() || typeof aicode.invoke !== 'function') {
    permissionError.value = '当前 APK 不支持插件代审核。';
    return;
  }
  resolvingPermission.value = true;
  permissionError.value = '';
  try {
    const result = await aicode.invoke('ui.permissionDecision', {
      requestId: requestId,
      decision: decision
    });
    if (!result || result.ok !== true) {
      throw new Error((result && result.error) || 'REVIEW_NOT_FOUND');
    }
    removePermission(requestId);
    setStatus(decision === 'allow' ? '已允许，等待 AI 继续…' : '已拒绝，等待 AI 处理…', 'busy');
  } catch (error) {
    permissionError.value = '处理失败：' + (error.message || error);
  } finally {
    resolvingPermission.value = false;
  }
}

async function syncPendingReviews(): Promise<void> {
  if (!apiReady() || typeof aicode.invoke !== 'function' || !sessionId) return;
  try {
    const result = await aicode.invoke('ui.pageReady', {});
    const list = (result && result.pendingReviews) || [];
    list.forEach(queuePermission);
  } catch (error) {
    // 运行时不可用时忽略；权限仍由宿主自己的确认 UI 处理。
  }
}

function fallbackCopy(text: string): void {
  const area = document.createElement('textarea');
  area.value = text;
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try { document.execCommand('copy'); } catch (e) { /* ignore */ }
  document.body.removeChild(area);
}

async function copyText(text: string, id: string): Promise<void> {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    try {
      await navigator.clipboard.writeText(text);
    } catch (error) {
      fallbackCopy(text);
    }
  } else {
    fallbackCopy(text);
  }
  copiedId.value = id;
  setTimeout(() => {
    if (copiedId.value === id) copiedId.value = null;
  }, 1200);
}

function mergeMessages(incoming: ChatItem[]): boolean {
  if (!incoming || !incoming.length) return false;
  let changed = false;
  incoming.forEach((message) => {
    if (!message || !message.id) return;
    const index = messages.value.findIndex((item) => item.id === message.id);
    if (index >= 0) {
      messages.value[index] = message;
    } else {
      messages.value.push(message);
      changed = true;
    }
    if (message.timestamp > lastTimestamp) lastTimestamp = message.timestamp;
    if (message.role === 'assistant' && message.content && message.content.trim()) {
      sawAssistantSinceSend = true;
    }
    if (message.role === 'user' && optimisticId) {
      const optimisticIndex = messages.value.findIndex((item) => item.id === optimisticId);
      if (optimisticIndex >= 0 && messages.value[optimisticIndex].content === message.content) {
        messages.value.splice(optimisticIndex, 1);
        optimisticId = null;
        changed = true;
      }
    }
  });
  messages.value.sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
  if (messages.value.length > MAX_MESSAGES) {
    messages.value = messages.value.slice(-MAX_MESSAGES);
  }
  if (changed) lastActivityAt = Date.now();
  return changed;
}

function maybeFinish(): void {
  if (!pending.value) return;
  if (runFinished) {
    pending.value = false;
    setStatus('已完成');
    return;
  }
  if (sawAssistantSinceSend && Date.now() - lastActivityAt > IDLE_FINISH_MS) {
    pending.value = false;
    setStatus('已回复（未收到完成事件）');
  }
}

async function pollOnce(): Promise<void> {
  if (!apiReady() || !sessionId || !pageVisible || polling) return;
  polling = true;
  try {
    const result = await aicode.chat.listMessages(sessionId, {
      after: lastTimestamp,
      limit: 100
    });
    mergeMessages(result.messages || []);
    maybeFinish();
  } catch (error) {
    if (error.code === 'SESSION_NOT_FOUND' || error.code === 'SESSION_FORBIDDEN') {
      try {
        await startFreshSession();
      } catch (freshError) {
        setStatus('会话不可用', 'error');
      }
    } else {
      setStatus('读取失败：' + (error.message || error), 'error');
    }
  } finally {
    polling = false;
  }
}

async function readStoredSession(): Promise<string | null> {
  try {
    const result = await aicode.fs.read(STORAGE_PATH);
    const data = JSON.parse(result.content || '{}');
    return data.sessionId || null;
  } catch (error) {
    return null;
  }
}

async function storeSession(id: string): Promise<void> {
  try {
    await aicode.fs.write(STORAGE_PATH, JSON.stringify({
      sessionId: id,
      updatedAt: Date.now()
    }, null, 2));
  } catch (error) {
    // 数据目录不可写时仍允许当前页面继续使用内存中的会话。
  }
}

async function loadSession(id: string): Promise<boolean> {
  try {
    const result = await aicode.chat.listMessages(id, { limit: 50 });
    sessionId = id;
    messages.value = (result.messages || []).filter((message) => {
      return !message.isCompactionMarker && !message.isContextSummary && !message.isCompacted;
    });
    lastTimestamp = 0;
    messages.value.forEach((message) => {
      if (message.timestamp > lastTimestamp) lastTimestamp = message.timestamp;
    });
    sawAssistantSinceSend = false;
    runFinished = false;
    pending.value = false;
    setStatus('已连接');
    return true;
  } catch (error) {
    if (error.code === 'SESSION_NOT_FOUND' || error.code === 'SESSION_FORBIDDEN') {
      return false;
    }
    throw error;
  }
}

async function startFreshSession(): Promise<string> {
  const result = await aicode.chat.createSession({ title: '对话桥' });
  sessionId = result.session.id;
  messages.value = [];
  lastTimestamp = 0;
  pending.value = false;
  runFinished = false;
  sawAssistantSinceSend = false;
  optimisticId = null;
  clearPermissions();
  await storeSession(sessionId);
  setStatus('新会话');
  return sessionId;
}

async function initialize(): Promise<void> {
  if (!apiReady()) {
    setStatus('API 不可用', 'error');
    messages.value = [{
      id: 'local-api',
      role: 'system',
      content: '当前 APK 没有 aicode.chat 接口，请安装最新版本。',
      timestamp: Date.now(),
      local: true,
      error: true
    }];
    return;
  }
  if (initializing) return;
  initializing = true;
  try {
    const context = await aicode.context();
    pageVisible = context.visible !== false;
    setStatus('连接中…');
    const stored = await readStoredSession();
    const loaded = stored ? await loadSession(stored) : false;
    if (!loaded) await startFreshSession();
    await pollOnce();
    await syncPendingReviews();
  } catch (error) {
    setStatus('初始化失败', 'error');
    addSystem('初始化失败：' + (error.message || error), 'error');
  } finally {
    initializing = false;
  }
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.shiftKey) return;
  event.preventDefault();
  void sendMessage();
}

async function sendMessage(): Promise<void> {
  const text = inputText.value.trim();
  if (!text || pending.value || !apiReady()) return;
  if (!sessionId) await startFreshSession();

  optimisticId = 'local-' + Date.now();
  messages.value.push({
    id: optimisticId,
    role: 'user',
    content: text,
    timestamp: Date.now(),
    local: true
  });
  inputText.value = '';
  pending.value = true;
  runFinished = false;
  sawAssistantSinceSend = false;
  lastActivityAt = Date.now();
  turnId += 1;
  setStatus('已发送，等待 AI…', 'busy');

  try {
    await aicode.chat.sendMessage(sessionId, text);
  } catch (error) {
    pending.value = false;
    messages.value = messages.value.filter((message) => message.id !== optimisticId);
    optimisticId = null;
    inputText.value = text;
    setStatus('发送失败', 'error');
    addSystem('发送失败：' + (error.message || error), 'error');
  }
}

async function stopRun(): Promise<void> {
  if (!sessionId || !apiReady()) return;
  try {
    await aicode.chat.stop(sessionId);
    pending.value = false;
    runFinished = true;
    clearPermissions();
    setStatus('已请求停止');
    addSystem('已请求停止当前 AI 轮次。');
  } catch (error) {
    setStatus('停止失败', 'error');
    addSystem('停止失败：' + (error.message || error), 'error');
  }
}

async function newSession(): Promise<void> {
  if (!apiReady()) return;
  try {
    await startFreshSession();
  } catch (error) {
    setStatus('新建会话失败', 'error');
    addSystem('新建会话失败：' + (error.message || error), 'error');
  }
}

function onRunFinished(payload: RunFinishedPayload): void {
  if (!payload || payload.sessionId !== sessionId) return;
  const finishedTurn = turnId;
  runFinished = true;
  clearPermissions();
  if (payload.status !== 'success') {
    const reason = payload.errorCode
      ? ('（' + payload.errorCode + '）')
      : (payload.error ? ('（' + payload.error + '）') : '');
    addSystem('本轮已结束：' + payload.status + reason, 'error');
  }
  // 完成事件之后再做一次拉取，确保最后一条 assistant 消息已经落库。
  setTimeout(() => {
    if (finishedTurn !== turnId) return;
    pollOnce().then(() => {
      if (finishedTurn !== turnId) return;
      pending.value = false;
      if (payload.status === 'success') {
        setStatus('已完成');
        if (!sawAssistantSinceSend) addSystem('本轮没有文本回复。');
      }
    });
  }, 300);
}

function handleEvent(event: AicodeEvent): void {
  if (!event) return;
  if (event.name === 'chat.run.finished') {
    onRunFinished(event.payload);
  } else if (event.name === 'chat.permission.requested') {
    queuePermission(event.payload);
  } else if (event.name === 'chat.permission.resolved') {
    removePermission(event.payload && event.payload.requestId);
  } else if (event.name === 'chat.permission.expired') {
    removePermission(event.payload && event.payload.requestId);
    addSystem('审批超时，已交回 App。请返回聊天页确认。', 'error');
    setStatus('审批已交回 App', 'error');
  } else if (event.name === 'ui.lifecycle') {
    const payload = event.payload || {};
    if (payload.reason === 'hidden') {
      pageVisible = false;
    } else if (payload.reason === 'shown' || payload.reason === 'resumed') {
      pageVisible = true;
      void pollOnce();
    } else if (payload.reason === 'contextChanged') {
      startFreshSession().catch(() => {
        setStatus('切换工作区后创建会话失败', 'error');
      });
    }
  }
}

async function scrollMessages(): Promise<void> {
  await nextTick();
  if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
}

watch(messages, () => {
  void scrollMessages();
}, { deep: true, flush: 'post' });

watch(pending, () => {
  void scrollMessages();
}, { flush: 'post' });

onMounted(() => {
  if (window.aicode && aicode.events) {
    offEvents = aicode.events.on(handleEvent);
  }
  pollTimer = setInterval(pollOnce, POLL_MS);
  finishTimer = setInterval(maybeFinish, 1000);
  void initialize();
});

onBeforeUnmount(() => {
  if (offEvents) offEvents();
  clearInterval(pollTimer);
  clearInterval(finishTimer);
});
</script>

<style>
:root { color-scheme: light dark; }
    * { box-sizing: border-box; }
    html, body { height: 100%; margin: 0; }
    body {
      font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
      background: var(--aicode-page-bg, var(--aicode-bg, #ffffff));
      color: var(--aicode-on-surface, #1f2328);
      overflow: hidden;
    }
    .app {
      position: fixed;
      inset: 0;
      max-width: 860px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      background: var(--aicode-page-bg, var(--aicode-bg, #ffffff));
    }
    header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      padding: 16px 16px 10px;
    }
    h1 { margin: 0; font-size: 19px; line-height: 1.3; }
    .subtitle {
      margin: 3px 0 0;
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 12px;
    }
    .header-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
    .status {
      max-width: 150px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      padding: 4px 9px;
      border-radius: 999px;
      background: var(--aicode-surface, #f6f8fa);
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 11px;
    }
    .status.busy { color: var(--aicode-primary, #0969da); }
    .status.error { color: var(--aicode-error, #cf222e); }
    button {
      appearance: none;
      border: 1px solid transparent;
      border-radius: 10px;
      padding: 9px 14px;
      font-size: 14px;
      font-family: inherit;
      background: var(--aicode-primary, #0969da);
      color: var(--aicode-on-primary, #ffffff);
      cursor: pointer;
    }
    button:disabled { opacity: .55; cursor: default; }
    button.ghost {
      background: transparent;
      color: var(--aicode-on-surface, #1f2328);
      border-color: var(--aicode-outline, #d0d7de);
      padding: 7px 11px;
      font-size: 12px;
    }
    .messages {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: 8px 16px 18px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      scroll-behavior: smooth;
    }
    .empty {
      margin: auto;
      max-width: 360px;
      padding: 20px;
      text-align: center;
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 13px;
      line-height: 1.7;
    }
    .empty strong { color: var(--aicode-on-surface, #1f2328); }
    .bubble {
      max-width: 86%;
      padding: 10px 12px;
      border-radius: 14px;
      font-size: 14px;
      line-height: 1.55;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      position: relative;
    }
    .bubble.user {
      align-self: flex-end;
      background: var(--aicode-primary, #0969da);
      color: var(--aicode-on-primary, #ffffff);
      border-bottom-right-radius: 4px;
    }
    .bubble.assistant {
      align-self: flex-start;
      background: var(--aicode-surface, #f6f8fa);
      border: 1px solid var(--aicode-outline, #d0d7de);
      border-bottom-left-radius: 4px;
    }
    .bubble.assistant .copy {
      display: block;
      margin-top: 6px;
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--aicode-primary, #0969da);
      font-size: 11px;
      text-align: left;
    }
    .bubble.error { border-color: var(--aicode-error, #cf222e); }
    .tool-row {
      align-self: center;
      max-width: 90%;
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 11px;
      text-align: center;
    }
    .tool-row.pending { color: var(--aicode-primary, #0969da); }
    .tool-row.error { color: var(--aicode-error, #cf222e); }
    .hint {
      margin: 0 16px;
      padding: 9px 11px;
      border-radius: 10px;
      background: var(--aicode-surface, #f6f8fa);
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 11px;
      line-height: 1.55;
    }
    .hint summary { cursor: pointer; user-select: none; }
    .hint code {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 10px;
    }
    .permission {
      margin: 0 16px 10px;
      padding: 12px;
      border: 1px solid var(--aicode-warning, #9a6700);
      border-radius: 12px;
      background: var(--aicode-warning-soft, rgba(154, 103, 0, 0.12));
    }
    .permission h2 { margin: 0 0 4px; font-size: 14px; }
    .permission p {
      margin: 0 0 8px;
      color: var(--aicode-subtle-text, var(--aicode-on-surface-variant, #59636e));
      font-size: 12px;
      line-height: 1.5;
    }
    .permission pre {
      max-height: 120px;
      margin: 0 0 10px;
      padding: 8px;
      overflow: auto;
      border-radius: 8px;
      background: var(--aicode-surface, #f6f8fa);
      color: var(--aicode-on-surface, #1f2328);
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
      line-height: 1.45;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    .permission-actions { display: flex; justify-content: flex-end; gap: 8px; }
    .permission-actions button {
      padding: 7px 12px;
      font-size: 12px;
    }
    .permission-actions button.deny {
      background: transparent;
      color: var(--aicode-error, #cf222e);
      border-color: var(--aicode-error, #cf222e);
    }
    .permission-error {
      margin-top: 8px;
      color: var(--aicode-error, #cf222e);
      font-size: 12px;
    }
    .composer {
      display: flex;
      align-items: flex-end;
      gap: 8px;
      padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
    }
    textarea {
      flex: 1;
      min-height: 44px;
      max-height: 120px;
      resize: none;
      padding: 11px 12px;
      border: 1px solid var(--aicode-outline, #d0d7de);
      border-radius: 12px;
      background: var(--aicode-card-surface, var(--aicode-surface, #ffffff));
      color: inherit;
      font: inherit;
      font-size: 14px;
      line-height: 1.4;
      outline: none;
    }
    textarea:focus { border-color: var(--aicode-primary, #0969da); }
    .composer-actions { display: flex; gap: 6px; flex-shrink: 0; }
    @media (max-width: 520px) {
      header { padding-top: 12px; }
      .subtitle { display: none; }
      .status { max-width: 110px; }
      .bubble { max-width: 92%; }
    }
</style>
