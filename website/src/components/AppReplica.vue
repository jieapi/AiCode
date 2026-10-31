<template>
  <div class="replica-stage reveal" v-reveal style="--delay: 0.15s">
    <div class="replica-glow"></div>

    <div class="aicode-device" :class="{ 'drawer-open': drawerOpen }">
      <!-- Android Status Bar -->
      <div class="ac-statusbar" :class="{ 'is-surface': statusbarSurface }">
        <div class="ac-statusbar__left">
          <span>{{ clockText }}</span>
        </div>
        <div class="ac-statusbar__right">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor">
            <path d="M12 21L1 9l2.4-2.4C6.1 4.3 9 3 12 3s5.9 1.3 8.6 3.6L23 9 12 21z" />
          </svg>
          <svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor">
            <path d="M2 22h20V2L2 22z" />
          </svg>
          <svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor">
            <path d="M16 4h-2V2h-4v2H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z" />
          </svg>
        </div>
      </div>

      <!-- SCREEN 1: Main Chat Screen (Sidebar + Chat Area) -->
      <div class="ac-workspace ac-page is-active">
        <div class="ac-scrim" @click="drawerOpen = false"></div>

        <!-- Left Sidebar (ChatDrawer) -->
        <aside class="ac-drawer" aria-label="侧边栏">
          <!-- Segmented Tabs: 会话 | 文件 -->
          <div class="ac-seg-tabs" role="tablist">
            <span
              class="ac-seg-tabs__pill"
              :style="{ transform: `translateX(${drawerTab * 100}%)` }"
            ></span>
            <button
              type="button"
              class="ac-seg-tabs__btn"
              :class="{ 'is-active': drawerTab === 0 }"
              @click="drawerTab = 0"
            >会话</button>
            <button
              type="button"
              class="ac-seg-tabs__btn"
              :class="{ 'is-active': drawerTab === 1 }"
              @click="drawerTab = 1"
            >文件</button>
          </div>

          <!-- Tab 0: Sessions -->
          <div class="ac-drawer__pane" :class="{ 'is-hidden': drawerTab !== 0 }">
            <div class="ac-search">
              <svg class="ac-search__icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
              <input type="text" class="ac-search__input" placeholder="搜索聊天记录" aria-label="搜索聊天记录" />
            </div>

            <div class="ac-session-group">
              <div class="ac-session-group__label">今天</div>
              <ul class="ac-session-list">
                <li>
                  <button type="button" class="ac-session-item is-active">
                    <span class="ac-session-item__title">打招呼</span>
                  </button>
                </li>
              </ul>
            </div>
          </div>

          <!-- Tab 1: File Tree -->
          <div class="ac-drawer__pane" :class="{ 'is-hidden': drawerTab !== 1 }">
            <div class="ac-file-tree">
              <div class="ac-file-tree__root">~/workspace</div>
              <div class="ac-file-row">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"></polyline></svg>
                <svg class="ac-folder-icon" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
                <span>app/src/main</span>
              </div>
              <div class="ac-file-row ac-file-row--indent-1">
                <svg class="ac-code-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                <span>MainActivity.kt</span>
              </div>
              <div class="ac-file-row ac-file-row--indent-1">
                <svg class="ac-code-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                <span>AIChatPanel.kt</span>
              </div>
              <div class="ac-file-row">
                <svg class="ac-code-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                <span>build.gradle.kts</span>
              </div>
              <div class="ac-file-row">
                <svg class="ac-code-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                <span>README.md</span>
              </div>
            </div>
          </div>

          <!-- Bottom Card: 浏览器 & 设置 -->
          <div class="ac-drawer-card">
            <button type="button" class="ac-drawer-card__row" @click="openSubpage('browser')">
              <span class="ac-drawer-card__left">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="10"></circle>
                  <line x1="2" y1="12" x2="22" y2="12"></line>
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                </svg>
                <span>浏览器</span>
              </span>
              <svg class="ac-drawer-card__chevron" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
            </button>
            <div class="ac-drawer-card__divider"></div>
            <button type="button" class="ac-drawer-card__row">
              <span class="ac-drawer-card__left">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="3"></circle>
                  <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                </svg>
                <span>设置</span>
              </span>
              <svg class="ac-drawer-card__chevron" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
            </button>
          </div>
        </aside>

        <!-- Right Main Chat Area -->
        <div class="ac-main">
          <div class="ac-chat-pane">
            <!-- ChatHeader -->
            <header class="ac-header">
              <button type="button" class="ac-icon-btn ac-header__menu" aria-label="打开侧边栏" @click="drawerOpen = true">
                <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
              </button>

              <div class="ac-header__titles">
                <div class="ac-header__title">打招呼</div>
                <div class="ac-header__sub">
                  <span class="ac-model-logo"><DeepSeekLogo /></span>
                  <span>deepseek-flash</span>
                </div>
              </div>

              <div class="ac-header__actions">
                <button type="button" class="ac-icon-btn" aria-label="新建会话" title="新建会话" @click="resetChat">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                </button>
                <button type="button" class="ac-icon-btn" :class="{ 'is-active': activeSubpage === 'git' }" aria-label="打开 Git" title="打开 Git" @click="openSubpage('git')">
                  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="3" x2="6" y2="15"></line><circle cx="18" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle><path d="M18 9a9 9 0 0 1-9 9"></path></svg>
                </button>
                <button type="button" class="ac-icon-btn" :class="{ 'is-active': activeSubpage === 'terminal' }" aria-label="打开终端" title="打开终端" @click="openSubpage('terminal')">
                  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"></polyline><line x1="12" y1="19" x2="20" y2="19"></line></svg>
                </button>
              </div>
            </header>

            <!-- Chat Messages Stream -->
            <div ref="messagesEl" class="ac-messages">
              <ChatMessage
                v-for="(message, index) in chatMessages"
                :key="index"
                :message="message"
              />
            </div>

            <!-- ChatInputBar -->
            <div class="ac-input-wrap">
              <div class="ac-input-card">
                <input
                  v-model="inputText"
                  type="text"
                  class="ac-input-field"
                  placeholder="输入消息，与 AI 对话..."
                  aria-label="输入消息"
                  @keydown.enter.prevent="sendMessage"
                />
                <div class="ac-input-toolbar">
                  <div class="ac-input-toolbar__left">
                    <button
                      type="button"
                      class="ac-mode-pill"
                      :class="mode.cls"
                      title="点击切换 BUILD / PLAN / AUTO"
                      @click="cycleMode"
                    >{{ mode.name }}</button>
                    <button type="button" class="ac-tool-icon-btn" aria-label="切换模型" title="点击切换模型">
                      <span class="ac-input-model-logo"><DeepSeekLogo /></span>
                    </button>
                    <button type="button" class="ac-tool-icon-btn" aria-label="切换工作区" title="工作区">
                      <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
                    </button>
                    <button type="button" class="ac-tool-icon-btn" aria-label="思考强度" title="思考强度">
                      <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>
                    </button>
                  </div>
                  <div class="ac-input-toolbar__right">
                    <button type="button" class="ac-tool-icon-btn" aria-label="添加附件">
                      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                    </button>
                    <button type="button" class="ac-send-btn" aria-label="发送消息" @click="sendMessage">
                      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"></line><polyline points="5 12 12 5 19 12"></polyline></svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Right Split Workbench Pane (PC/Tablet Split, Mobile Fullscreen) -->
          <div class="ac-workbench-pane" :class="{ 'is-hidden': activeSubpage === null }">
            <GitScreen :class="{ 'is-active': activeSubpage === 'git' }" @back="activeSubpage = null" />
            <TerminalScreen :class="{ 'is-active': activeSubpage === 'terminal' }" @back="activeSubpage = null" />
            <BrowserScreen :class="{ 'is-active': activeSubpage === 'browser' }" @back="activeSubpage = null" />
          </div>
        </div>
      </div>

      <!-- Toast inside App Device -->
      <div class="ac-toast" :class="{ 'is-shown': toastVisible }">{{ toastText }}</div>

      <!-- Android Home Gesture Bar -->
      <div class="ac-home-indicator" :class="{ 'is-surface': homeIndicatorSurface }">
        <span></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import ChatMessage from "./ChatMessage.vue";
import DeepSeekLogo from "./DeepSeekLogo.vue";
import GitScreen from "./GitScreen.vue";
import TerminalScreen from "./TerminalScreen.vue";
import BrowserScreen from "./BrowserScreen.vue";

const initialMessages = () => [
  { role: "user", text: "你好", time: "19:09" },
  {
    role: "ai",
    text: "你好！有什么可以帮你的吗？",
    tokens: "↑ 6.4k ↓ 9",
    cache: "32%",
    duration: "4s"
  }
];

const chatMessages = ref(initialMessages());
const inputText = ref("");
const drawerOpen = ref(false);
const drawerTab = ref(0);
const activeSubpage = ref(null);
const isPhone = ref(false);
const toastText = ref("");
const toastVisible = ref(false);
const messagesEl = ref(null);
const currentTime = ref(null);

const clockText = computed(() => {
  if (!currentTime.value) return "--:--";
  const hours = String(currentTime.value.getHours()).padStart(2, "0");
  const minutes = String(currentTime.value.getMinutes()).padStart(2, "0");
  return `${hours}:${minutes}`;
});

const modes = [
  { name: "BUILD", cls: "is-build" },
  { name: "PLAN", cls: "is-plan" },
  { name: "AUTO", cls: "is-auto" }
];
const modeIndex = ref(0);
const mode = computed(() => modes[modeIndex.value]);

const statusbarSurface = computed(
  () => isPhone.value && (activeSubpage.value === "terminal" || activeSubpage.value === "browser")
);
const homeIndicatorSurface = computed(
  () => isPhone.value && activeSubpage.value === "browser"
);

function openSubpage(target) {
  if (!isPhone.value && activeSubpage.value === target) {
    activeSubpage.value = null;
  } else {
    activeSubpage.value = target;
  }
  drawerOpen.value = false;
}

function cycleMode() {
  modeIndex.value = (modeIndex.value + 1) % modes.length;
}

let toastTimer = null;
function showToast(message) {
  toastText.value = message;
  toastVisible.value = true;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastVisible.value = false;
  }, 1800);
}

let replyTimer = null;
function sendMessage() {
  const text = inputText.value.trim();
  if (!text) return;

  inputText.value = "";
  const sentAt = new Date();
  const time = `${String(sentAt.getHours()).padStart(2, "0")}:${String(sentAt.getMinutes()).padStart(2, "0")}`;
  chatMessages.value.push({ role: "user", text, time });

  clearTimeout(replyTimer);
  replyTimer = setTimeout(() => {
    chatMessages.value.push({
      role: "ai",
      text: "收到，我可以直接在内置 Linux 容器或远程 SSH 工作区中帮你读写文件、运行命令或管理 Git。",
      tokens: "↑ 6.8k ↓ 42",
      cache: "78%",
      duration: "2s"
    });
  }, 360);
}

function resetChat() {
  chatMessages.value = initialMessages();
  showToast("已重置当前对话");
}

function scrollToBottom() {
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
  }
}

watch(
  chatMessages,
  async () => {
    await nextTick();
    scrollToBottom();
  },
  { deep: true }
);

let frameQuery = null;
let clockTimer = null;

function tickClock() {
  currentTime.value = new Date();
  clockTimer = setTimeout(tickClock, 60000 - (Date.now() % 60000));
}

function syncFormFactor(event) {
  isPhone.value = event.matches;
  if (!event.matches) drawerOpen.value = false;
}

onMounted(() => {
  scrollToBottom();
  tickClock();
  frameQuery = window.matchMedia("(max-width: 860px)");
  isPhone.value = frameQuery.matches;
  if (frameQuery.addEventListener) {
    frameQuery.addEventListener("change", syncFormFactor);
  } else {
    frameQuery.addListener(syncFormFactor);
  }
});

onBeforeUnmount(() => {
  clearTimeout(toastTimer);
  clearTimeout(replyTimer);
  clearTimeout(clockTimer);
  if (!frameQuery) return;
  if (frameQuery.removeEventListener) {
    frameQuery.removeEventListener("change", syncFormFactor);
  } else {
    frameQuery.removeListener(syncFormFactor);
  }
});
</script>
