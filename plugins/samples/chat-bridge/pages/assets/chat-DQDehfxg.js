(function polyfill() {
  const relList = document.createElement("link").relList;
  if (relList && relList.supports && relList.supports("modulepreload")) {
    return;
  }
  for (const link of document.querySelectorAll('link[rel="modulepreload"]')) {
    processPreload(link);
  }
  new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type !== "childList") {
        continue;
      }
      for (const node of mutation.addedNodes) {
        if (node.tagName === "LINK" && node.rel === "modulepreload")
          processPreload(node);
      }
    }
  }).observe(document, { childList: true, subtree: true });
  function getFetchOpts(link) {
    const fetchOpts = {};
    if (link.integrity) fetchOpts.integrity = link.integrity;
    if (link.referrerPolicy) fetchOpts.referrerPolicy = link.referrerPolicy;
    if (link.crossOrigin === "use-credentials")
      fetchOpts.credentials = "include";
    else if (link.crossOrigin === "anonymous") fetchOpts.credentials = "omit";
    else fetchOpts.credentials = "same-origin";
    return fetchOpts;
  }
  function processPreload(link) {
    if (link.ep)
      return;
    link.ep = true;
    const fetchOpts = getFetchOpts(link);
    fetch(link.href, fetchOpts);
  }
})();
const Vue = window.Vue;
if (!Vue) throw new Error("Aicode page requires /__aicode/vue.global.prod.js");
Vue.BaseTransition;
Vue.BaseTransitionPropsValidators;
Vue.Comment;
Vue.DeprecationTypes;
Vue.EffectScope;
Vue.ErrorCodes;
Vue.ErrorTypeStrings;
const Fragment = Vue.Fragment;
Vue.KeepAlive;
Vue.ReactiveEffect;
Vue.Static;
Vue.Suspense;
Vue.Teleport;
Vue.Text;
Vue.TrackOpTypes;
Vue.Transition;
Vue.TransitionGroup;
Vue.TriggerOpTypes;
Vue.VueElement;
Vue.__esModule;
Vue.assertNumber;
Vue.callWithAsyncErrorHandling;
Vue.callWithErrorHandling;
Vue.camelize;
Vue.capitalize;
Vue.cloneVNode;
Vue.compatUtils;
Vue.compile;
const computed = Vue.computed;
const createApp = Vue.createApp;
Vue.createBlock;
const createCommentVNode = Vue.createCommentVNode;
const createElementBlock = Vue.createElementBlock;
const createElementVNode = Vue.createElementVNode;
Vue.createHydrationRenderer;
Vue.createPropsRestProxy;
Vue.createRenderer;
Vue.createSSRApp;
Vue.createSlots;
Vue.createStaticVNode;
const createTextVNode = Vue.createTextVNode;
Vue.createVNode;
Vue.customRef;
Vue.defineAsyncComponent;
Vue.defineComponent;
Vue.defineCustomElement;
Vue.defineEmits;
Vue.defineExpose;
Vue.defineModel;
Vue.defineOptions;
Vue.defineProps;
Vue.defineSSRCustomElement;
Vue.defineSlots;
Vue.devtools;
Vue.effect;
Vue.effectScope;
Vue.getCurrentInstance;
Vue.getCurrentScope;
Vue.getCurrentWatcher;
Vue.getTransitionRawChildren;
Vue.guardReactiveProps;
Vue.h;
Vue.handleError;
Vue.hasInjectionContext;
Vue.hydrate;
Vue.hydrateOnIdle;
Vue.hydrateOnInteraction;
Vue.hydrateOnMediaQuery;
Vue.hydrateOnVisible;
Vue.initCustomFormatter;
Vue.initDirectivesForSSR;
Vue.inject;
Vue.isMemoSame;
Vue.isProxy;
Vue.isReactive;
Vue.isReadonly;
Vue.isRef;
Vue.isRuntimeOnly;
Vue.isShallow;
Vue.isVNode;
Vue.markRaw;
Vue.mergeDefaults;
Vue.mergeModels;
Vue.mergeProps;
const nextTick = Vue.nextTick;
Vue.nodeOps;
const normalizeClass = Vue.normalizeClass;
Vue.normalizeProps;
Vue.normalizeStyle;
Vue.onActivated;
Vue.onBeforeMount;
const onBeforeUnmount = Vue.onBeforeUnmount;
Vue.onBeforeUpdate;
Vue.onDeactivated;
Vue.onErrorCaptured;
const onMounted = Vue.onMounted;
Vue.onRenderTracked;
Vue.onRenderTriggered;
Vue.onScopeDispose;
Vue.onServerPrefetch;
Vue.onUnmounted;
Vue.onUpdated;
Vue.onWatcherCleanup;
const openBlock = Vue.openBlock;
Vue.patchProp;
Vue.popScopeId;
Vue.provide;
Vue.proxyRefs;
Vue.pushScopeId;
Vue.queuePostFlushCb;
Vue.reactive;
Vue.readonly;
const ref = Vue.ref;
Vue.registerRuntimeCompiler;
Vue.render;
const renderList = Vue.renderList;
Vue.renderSlot;
Vue.resolveComponent;
Vue.resolveDirective;
Vue.resolveDynamicComponent;
Vue.resolveFilter;
Vue.resolveTransitionHooks;
Vue.setBlockTracking;
Vue.setDevtoolsHook;
Vue.setTransitionHooks;
Vue.shallowReactive;
Vue.shallowReadonly;
Vue.shallowRef;
Vue.ssrContextKey;
Vue.ssrUtils;
Vue.stop;
const toDisplayString = Vue.toDisplayString;
Vue.toHandlerKey;
Vue.toHandlers;
Vue.toRaw;
Vue.toRef;
Vue.toRefs;
Vue.toValue;
Vue.transformVNodeArgs;
Vue.triggerRef;
Vue.unref;
Vue.useAttrs;
Vue.useCssModule;
Vue.useCssVars;
Vue.useHost;
Vue.useId;
Vue.useModel;
Vue.useSSRContext;
Vue.useShadowRoot;
Vue.useSlots;
Vue.useTemplateRef;
Vue.useTransitionState;
Vue.vModelCheckbox;
Vue.vModelDynamic;
Vue.vModelRadio;
Vue.vModelSelect;
const vModelText = Vue.vModelText;
Vue.vShow;
Vue.version;
Vue.warn;
const watch = Vue.watch;
Vue.watchEffect;
Vue.watchPostEffect;
Vue.watchSyncEffect;
Vue.withAsyncContext;
Vue.withCtx;
Vue.withDefaults;
const withDirectives = Vue.withDirectives;
const withKeys = Vue.withKeys;
Vue.withMemo;
Vue.withModifiers;
Vue.withScopeId;
const _hoisted_1 = { id: "app" };
const _hoisted_2 = { class: "app" };
const _hoisted_3 = { class: "header-actions" };
const _hoisted_4 = {
  key: 0,
  id: "permission",
  class: "permission"
};
const _hoisted_5 = {
  key: 0,
  class: "permission-error"
};
const _hoisted_6 = { class: "permission-actions" };
const _hoisted_7 = ["disabled"];
const _hoisted_8 = ["disabled"];
const _hoisted_9 = {
  key: 0,
  class: "empty"
};
const _hoisted_10 = {
  key: 0,
  class: "tool-row"
};
const _hoisted_11 = { class: "bubble-text" };
const _hoisted_12 = ["onClick"];
const _hoisted_13 = {
  key: 1,
  class: "tool-row pending"
};
const _hoisted_14 = { class: "composer" };
const _hoisted_15 = { class: "composer-actions" };
const _hoisted_16 = ["disabled"];
const STORAGE_PATH = "data://chat-bridge.json";
const POLL_MS = 1e3;
const IDLE_FINISH_MS = 1e4;
const MAX_MESSAGES = 300;
const _sfc_main = {
  __name: "chat",
  setup(__props) {
    const messages = ref([]);
    const pending = ref(false);
    const statusText = ref("连接中…");
    const statusKind = ref("");
    const inputText = ref("");
    const permissionRequests = ref([]);
    const resolvingPermission = ref(false);
    const permissionError = ref("");
    const copiedId = ref(null);
    const messagesEl = ref(null);
    let sessionId = null;
    let lastTimestamp = 0;
    let lastActivityAt = 0;
    let pageVisible = true;
    let polling = false;
    let initializing = false;
    let optimisticId = null;
    let turnId = 0;
    let sawAssistantSinceSend = false;
    let runFinished = false;
    let offEvents = null;
    let pollTimer = null;
    let finishTimer = null;
    const visibleMessages = computed(() => messages.value.filter((message) => {
      return !message.isCompactionMarker && !message.isContextSummary && !message.isCompacted && (message.role === "user" || message.role === "assistant" || message.role === "tool" || message.role === "system");
    }));
    const currentPermission = computed(() => permissionRequests.value[0] || null);
    const permissionArgs = computed(() => {
      if (!currentPermission.value) return "";
      let args = "";
      try {
        args = JSON.stringify(currentPermission.value.arguments || {}, null, 2);
      } catch (error) {
        args = String(currentPermission.value.arguments || "");
      }
      return args.length > 2e3 ? args.slice(0, 2e3) + "\n…" : args;
    });
    function apiReady() {
      return window.aicode && aicode.chat;
    }
    function setStatus(text, kind) {
      statusText.value = text;
      statusKind.value = kind || "";
    }
    function addSystem(text, kind) {
      messages.value.push({
        id: "local-" + Date.now() + "-" + Math.random().toString(36).slice(2),
        role: "system",
        content: text,
        timestamp: Date.now(),
        local: true,
        error: kind === "error"
      });
    }
    function queuePermission(payload) {
      if (!payload || !payload.requestId || !sessionId) return;
      if (payload.sessionId && payload.sessionId !== sessionId) return;
      if (permissionRequests.value.some((item) => item.requestId === payload.requestId)) return;
      permissionRequests.value.push(payload);
      permissionError.value = "";
      pending.value = true;
      setStatus("等待授权：" + (payload.toolName || "工具"), "busy");
    }
    function removePermission(requestId) {
      permissionRequests.value = permissionRequests.value.filter((item) => item.requestId !== requestId);
    }
    function clearPermissions() {
      permissionRequests.value = [];
      permissionError.value = "";
    }
    async function resolvePermission(requestId, decision) {
      if (resolvingPermission.value) return;
      if (!apiReady() || typeof aicode.invoke !== "function") {
        permissionError.value = "当前 APK 不支持插件代审核。";
        return;
      }
      resolvingPermission.value = true;
      permissionError.value = "";
      try {
        const result = await aicode.invoke("ui.permissionDecision", {
          requestId,
          decision
        });
        if (!result || result.ok !== true) {
          throw new Error(result && result.error || "REVIEW_NOT_FOUND");
        }
        removePermission(requestId);
        setStatus(decision === "allow" ? "已允许，等待 AI 继续…" : "已拒绝，等待 AI 处理…", "busy");
      } catch (error) {
        permissionError.value = "处理失败：" + (error.message || error);
      } finally {
        resolvingPermission.value = false;
      }
    }
    async function syncPendingReviews() {
      if (!apiReady() || typeof aicode.invoke !== "function" || !sessionId) return;
      try {
        const result = await aicode.invoke("ui.pageReady", {});
        const list = result && result.pendingReviews || [];
        list.forEach(queuePermission);
      } catch (error) {
      }
    }
    function fallbackCopy(text) {
      const area = document.createElement("textarea");
      area.value = text;
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      try {
        document.execCommand("copy");
      } catch (e) {
      }
      document.body.removeChild(area);
    }
    async function copyText(text, id) {
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
    function mergeMessages(incoming) {
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
        if (message.role === "assistant" && message.content && message.content.trim()) {
          sawAssistantSinceSend = true;
        }
        if (message.role === "user" && optimisticId) {
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
    function maybeFinish() {
      if (!pending.value) return;
      if (runFinished) {
        pending.value = false;
        setStatus("已完成");
        return;
      }
      if (sawAssistantSinceSend && Date.now() - lastActivityAt > IDLE_FINISH_MS) {
        pending.value = false;
        setStatus("已回复（未收到完成事件）");
      }
    }
    async function pollOnce() {
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
        if (error.code === "SESSION_NOT_FOUND" || error.code === "SESSION_FORBIDDEN") {
          try {
            await startFreshSession();
          } catch (freshError) {
            setStatus("会话不可用", "error");
          }
        } else {
          setStatus("读取失败：" + (error.message || error), "error");
        }
      } finally {
        polling = false;
      }
    }
    async function readStoredSession() {
      try {
        const result = await aicode.fs.read(STORAGE_PATH);
        const data = JSON.parse(result.content || "{}");
        return data.sessionId || null;
      } catch (error) {
        return null;
      }
    }
    async function storeSession(id) {
      try {
        await aicode.fs.write(STORAGE_PATH, JSON.stringify({
          sessionId: id,
          updatedAt: Date.now()
        }, null, 2));
      } catch (error) {
      }
    }
    async function loadSession(id) {
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
        setStatus("已连接");
        return true;
      } catch (error) {
        if (error.code === "SESSION_NOT_FOUND" || error.code === "SESSION_FORBIDDEN") {
          return false;
        }
        throw error;
      }
    }
    async function startFreshSession() {
      const result = await aicode.chat.createSession({ title: "对话桥" });
      sessionId = result.session.id;
      messages.value = [];
      lastTimestamp = 0;
      pending.value = false;
      runFinished = false;
      sawAssistantSinceSend = false;
      optimisticId = null;
      clearPermissions();
      await storeSession(sessionId);
      setStatus("新会话");
      return sessionId;
    }
    async function initialize() {
      if (!apiReady()) {
        setStatus("API 不可用", "error");
        messages.value = [{
          id: "local-api",
          role: "system",
          content: "当前 APK 没有 aicode.chat 接口，请安装最新版本。",
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
        setStatus("连接中…");
        const stored = await readStoredSession();
        const loaded = stored ? await loadSession(stored) : false;
        if (!loaded) await startFreshSession();
        await pollOnce();
        await syncPendingReviews();
      } catch (error) {
        setStatus("初始化失败", "error");
        addSystem("初始化失败：" + (error.message || error), "error");
      } finally {
        initializing = false;
      }
    }
    function onInputKeydown(event) {
      if (event.shiftKey) return;
      event.preventDefault();
      void sendMessage();
    }
    async function sendMessage() {
      const text = inputText.value.trim();
      if (!text || pending.value || !apiReady()) return;
      if (!sessionId) await startFreshSession();
      optimisticId = "local-" + Date.now();
      messages.value.push({
        id: optimisticId,
        role: "user",
        content: text,
        timestamp: Date.now(),
        local: true
      });
      inputText.value = "";
      pending.value = true;
      runFinished = false;
      sawAssistantSinceSend = false;
      lastActivityAt = Date.now();
      turnId += 1;
      setStatus("已发送，等待 AI…", "busy");
      try {
        await aicode.chat.sendMessage(sessionId, text);
      } catch (error) {
        pending.value = false;
        messages.value = messages.value.filter((message) => message.id !== optimisticId);
        optimisticId = null;
        inputText.value = text;
        setStatus("发送失败", "error");
        addSystem("发送失败：" + (error.message || error), "error");
      }
    }
    async function stopRun() {
      if (!sessionId || !apiReady()) return;
      try {
        await aicode.chat.stop(sessionId);
        pending.value = false;
        runFinished = true;
        clearPermissions();
        setStatus("已请求停止");
        addSystem("已请求停止当前 AI 轮次。");
      } catch (error) {
        setStatus("停止失败", "error");
        addSystem("停止失败：" + (error.message || error), "error");
      }
    }
    async function newSession() {
      if (!apiReady()) return;
      try {
        await startFreshSession();
      } catch (error) {
        setStatus("新建会话失败", "error");
        addSystem("新建会话失败：" + (error.message || error), "error");
      }
    }
    function onRunFinished(payload) {
      if (!payload || payload.sessionId !== sessionId) return;
      const finishedTurn = turnId;
      runFinished = true;
      clearPermissions();
      if (payload.status !== "success") {
        const reason = payload.errorCode ? "（" + payload.errorCode + "）" : payload.error ? "（" + payload.error + "）" : "";
        addSystem("本轮已结束：" + payload.status + reason, "error");
      }
      setTimeout(() => {
        if (finishedTurn !== turnId) return;
        pollOnce().then(() => {
          if (finishedTurn !== turnId) return;
          pending.value = false;
          if (payload.status === "success") {
            setStatus("已完成");
            if (!sawAssistantSinceSend) addSystem("本轮没有文本回复。");
          }
        });
      }, 300);
    }
    function handleEvent(event) {
      if (!event) return;
      if (event.name === "chat.run.finished") {
        onRunFinished(event.payload);
      } else if (event.name === "chat.permission.requested") {
        queuePermission(event.payload);
      } else if (event.name === "chat.permission.resolved") {
        removePermission(event.payload && event.payload.requestId);
      } else if (event.name === "chat.permission.expired") {
        removePermission(event.payload && event.payload.requestId);
        addSystem("审批超时，已交回 App。请返回聊天页确认。", "error");
        setStatus("审批已交回 App", "error");
      } else if (event.name === "ui.lifecycle") {
        const payload = event.payload || {};
        if (payload.reason === "hidden") {
          pageVisible = false;
        } else if (payload.reason === "shown" || payload.reason === "resumed") {
          pageVisible = true;
          void pollOnce();
        } else if (payload.reason === "contextChanged") {
          startFreshSession().catch(() => {
            setStatus("切换工作区后创建会话失败", "error");
          });
        }
      }
    }
    async function scrollMessages() {
      await nextTick();
      if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
    }
    watch(messages, () => {
      void scrollMessages();
    }, { deep: true, flush: "post" });
    watch(pending, () => {
      void scrollMessages();
    }, { flush: "post" });
    onMounted(() => {
      if (window.aicode && aicode.events) {
        offEvents = aicode.events.on(handleEvent);
      }
      pollTimer = setInterval(pollOnce, POLL_MS);
      finishTimer = setInterval(maybeFinish, 1e3);
      void initialize();
    });
    onBeforeUnmount(() => {
      if (offEvents) offEvents();
      clearInterval(pollTimer);
      clearInterval(finishTimer);
    });
    return (_ctx, _cache) => {
      return openBlock(), createElementBlock("div", _hoisted_1, [
        createElementVNode("div", _hoisted_2, [
          createElementVNode("header", null, [
            _cache[3] || (_cache[3] = createElementVNode(
              "div",
              null,
              [
                createElementVNode("h1", null, "对话桥"),
                createElementVNode("p", { class: "subtitle" }, "给 AI 下指令，读取回复；可作为 QQ / 微信机器人接入示例")
              ],
              -1
              /* CACHED */
            )),
            createElementVNode("div", _hoisted_3, [
              createElementVNode(
                "span",
                {
                  id: "status",
                  class: normalizeClass(["status", statusKind.value])
                },
                toDisplayString(statusText.value),
                3
                /* TEXT, CLASS */
              ),
              createElementVNode("button", {
                id: "new-session",
                class: "ghost",
                type: "button",
                onClick: newSession
              }, "新会话")
            ])
          ]),
          currentPermission.value ? (openBlock(), createElementBlock("section", _hoisted_4, [
            createElementVNode(
              "h2",
              null,
              "需要授权：" + toDisplayString(currentPermission.value.toolName || "工具"),
              1
              /* TEXT */
            ),
            _cache[4] || (_cache[4] = createElementVNode(
              "p",
              null,
              "AI 想调用这个工具。允许只对本次调用生效；拒绝后 AI 会收到失败结果。",
              -1
              /* CACHED */
            )),
            createElementVNode(
              "pre",
              null,
              toDisplayString(permissionArgs.value),
              1
              /* TEXT */
            ),
            permissionError.value ? (openBlock(), createElementBlock(
              "div",
              _hoisted_5,
              toDisplayString(permissionError.value),
              1
              /* TEXT */
            )) : createCommentVNode("v-if", true),
            createElementVNode("div", _hoisted_6, [
              createElementVNode("button", {
                type: "button",
                class: "deny",
                disabled: resolvingPermission.value,
                onClick: _cache[0] || (_cache[0] = ($event) => resolvePermission(currentPermission.value.requestId, "deny"))
              }, "拒绝", 8, _hoisted_7),
              createElementVNode("button", {
                type: "button",
                disabled: resolvingPermission.value,
                onClick: _cache[1] || (_cache[1] = ($event) => resolvePermission(currentPermission.value.requestId, "allow"))
              }, toDisplayString(resolvingPermission.value ? "处理中…" : "允许本次"), 9, _hoisted_8)
            ])
          ])) : createCommentVNode("v-if", true),
          createElementVNode(
            "main",
            {
              ref_key: "messagesEl",
              ref: messagesEl,
              id: "messages",
              class: "messages",
              "aria-live": "polite"
            },
            [
              !visibleMessages.value.length && !pending.value ? (openBlock(), createElementBlock("div", _hoisted_9, [..._cache[5] || (_cache[5] = [
                createElementVNode(
                  "strong",
                  null,
                  "对话桥已就绪",
                  -1
                  /* CACHED */
                ),
                createElementVNode(
                  "br",
                  null,
                  null,
                  -1
                  /* CACHED */
                ),
                createTextVNode(
                  "输入一条指令开始。",
                  -1
                  /* CACHED */
                ),
                createElementVNode(
                  "br",
                  null,
                  null,
                  -1
                  /* CACHED */
                ),
                createTextVNode(
                  "回复会显示在这里，也可以点“复制”交给外部机器人。 ",
                  -1
                  /* CACHED */
                )
              ])])) : createCommentVNode("v-if", true),
              (openBlock(true), createElementBlock(
                Fragment,
                null,
                renderList(visibleMessages.value, (message) => {
                  return openBlock(), createElementBlock(
                    Fragment,
                    {
                      key: message.id
                    },
                    [
                      message.role === "tool" ? (openBlock(), createElementBlock(
                        "div",
                        _hoisted_10,
                        " 工具 " + toDisplayString(message.toolName || "tool") + toDisplayString(message.isError ? " 失败" : " 完成"),
                        1
                        /* TEXT */
                      )) : message.role === "system" ? (openBlock(), createElementBlock(
                        "div",
                        {
                          key: 1,
                          class: normalizeClass(["tool-row", { error: message.error }])
                        },
                        toDisplayString(message.content),
                        3
                        /* TEXT, CLASS */
                      )) : (openBlock(), createElementBlock(
                        "div",
                        {
                          key: 2,
                          class: normalizeClass(["bubble", [message.role, { error: message.error }]])
                        },
                        [
                          createElementVNode(
                            "div",
                            _hoisted_11,
                            toDisplayString(message.content || ""),
                            1
                            /* TEXT */
                          ),
                          message.role === "assistant" && message.content ? (openBlock(), createElementBlock("button", {
                            key: 0,
                            type: "button",
                            class: "copy",
                            onClick: ($event) => copyText(message.content, message.id)
                          }, toDisplayString(copiedId.value === message.id ? "已复制" : "复制"), 9, _hoisted_12)) : createCommentVNode("v-if", true)
                        ],
                        2
                        /* CLASS */
                      ))
                    ],
                    64
                    /* STABLE_FRAGMENT */
                  );
                }),
                128
                /* KEYED_FRAGMENT */
              )),
              pending.value ? (openBlock(), createElementBlock("div", _hoisted_13, "AI 正在执行…")) : createCommentVNode("v-if", true)
            ],
            512
            /* NEED_PATCH */
          ),
          _cache[6] || (_cache[6] = createElementVNode(
            "details",
            { class: "hint" },
            [
              createElementVNode("summary", null, "接入提示：外部消息如何变成 AI 回复"),
              createTextVNode(" 外部消息到达后调用 "),
              createElementVNode("code", null, "aicode.chat.sendMessage(sessionId, text)"),
              createTextVNode("；"),
              createElementVNode("code", null, "agent.run.finished"),
              createTextVNode(" hook 会通知页面；随后调用 "),
              createElementVNode("code", null, "aicode.chat.listMessages"),
              createTextVNode("，把最后一条 assistant 文本回传给 QQ / 微信。页面关闭后轮询停止，机器人服务应改用插件进程或自己的后端常驻。 ")
            ],
            -1
            /* CACHED */
          )),
          createElementVNode("footer", _hoisted_14, [
            withDirectives(createElementVNode(
              "textarea",
              {
                id: "input",
                "onUpdate:modelValue": _cache[2] || (_cache[2] = ($event) => inputText.value = $event),
                rows: "1",
                placeholder: "输入指令，例如：列出当前目录并总结项目结构",
                onKeydown: withKeys(onInputKeydown, ["enter"])
              },
              null,
              544
              /* NEED_HYDRATION, NEED_PATCH */
            ), [
              [vModelText, inputText.value]
            ]),
            createElementVNode("div", _hoisted_15, [
              pending.value ? (openBlock(), createElementBlock("button", {
                key: 0,
                id: "stop",
                class: "ghost",
                type: "button",
                onClick: stopRun
              }, "停止")) : createCommentVNode("v-if", true),
              createElementVNode("button", {
                id: "send",
                type: "button",
                disabled: !apiReady() || pending.value,
                onClick: sendMessage
              }, "发送", 8, _hoisted_16)
            ])
          ])
        ])
      ]);
    };
  }
};
const app = createApp(_sfc_main);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
