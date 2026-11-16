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
Vue.renderList;
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
Vue.vModelText;
Vue.vShow;
Vue.version;
Vue.warn;
Vue.watch;
Vue.watchEffect;
Vue.watchPostEffect;
Vue.watchSyncEffect;
Vue.withAsyncContext;
Vue.withCtx;
Vue.withDefaults;
Vue.withDirectives;
Vue.withKeys;
Vue.withMemo;
Vue.withModifiers;
Vue.withScopeId;
const _hoisted_1 = { id: "app" };
const _hoisted_2 = ["aria-hidden"];
const _hoisted_3 = { class: "header" };
const _hoisted_4 = { class: "heading" };
const _hoisted_5 = { class: "title" };
const _hoisted_6 = { class: "subtitle" };
const _hoisted_7 = { class: "body" };
const _hoisted_8 = {
  key: 0,
  class: "empty"
};
const _hoisted_9 = { class: "tool" };
const _hoisted_10 = {
  key: 0,
  class: "actions"
};
const _hoisted_11 = ["disabled"];
const _hoisted_12 = ["disabled"];
const BALL_SIZE = 48;
const PANEL_WIDTH = 320;
const PANEL_HEIGHT = 280;
const _sfc_main = {
  __name: "ball",
  setup(__props) {
    const expanded = ref(false);
    const ready = ref(false);
    const transitioning = ref(false);
    const submitting = ref(false);
    const foreground = ref(false);
    const initialized = ref(false);
    const request = ref(null);
    const count = ref(0);
    const denyText = ref("拒绝");
    const allowText = ref("同意");
    const previewEl = ref(null);
    let minimizedRequestId = null;
    let ballAnchor = null;
    let panelOrigin = null;
    let transitionSeq = 0;
    let offMessage = null;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const badgeText = computed(() => count.value > 99 ? "99+" : String(count.value));
    const title = computed(() => request.value ? "审批请求" : "后台审批");
    const subtitle = computed(() => {
      if (!request.value) return "暂无待审批";
      return (count.value > 1 ? count.value + " 项待审批" : "等待你的确认") + (request.value.agentName ? " · " + request.value.agentName : "");
    });
    const buttonsDisabled = computed(
      () => !request.value || submitting.value || transitioning.value || !expanded.value || foreground.value
    );
    function post(payload) {
      return aicode.overlay.postMessage(payload);
    }
    function updateDragRegion() {
      return aicode.overlay.setDragRegion({
        left: 0,
        top: 0,
        width: expanded.value ? PANEL_WIDTH - 48 : BALL_SIZE,
        height: BALL_SIZE
      });
    }
    async function setExpanded(value) {
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
          x: !value && ballAnchor ? ballAnchor.x : null,
          y: !value && ballAnchor ? ballAnchor.y : null
        });
      } catch (error) {
        if (seq !== transitionSeq) return;
        transitioning.value = false;
        expanded.value = false;
        ready.value = false;
        if (request.value) post({ type: "displayError", requestId: request.value.requestId }).catch(() => {
        });
      }
    }
    function applyState(message) {
      const previousId = request.value && request.value.requestId;
      request.value = message.request || null;
      count.value = Math.max(0, Number(message.count) || 0);
      if (previousId !== (request.value && request.value.requestId)) {
        submitting.value = false;
        denyText.value = "拒绝";
        allowText.value = "同意";
        nextTick(() => {
          if (previewEl.value) previewEl.value.scrollTop = 0;
        });
      }
      if (request.value && request.value.requestId !== minimizedRequestId && !foreground.value) {
        void setExpanded(true);
      } else if (!request.value && previousId) {
        minimizedRequestId = null;
        void setExpanded(false);
      }
    }
    function decide(decision) {
      if (buttonsDisabled.value) return;
      const requestId = request.value.requestId;
      submitting.value = true;
      if (decision === "allow") allowText.value = "已同意";
      else denyText.value = "已拒绝";
      post({ type: "decision", requestId, decision }).catch(() => {
        if (!request.value || request.value.requestId !== requestId) return;
        submitting.value = false;
        denyText.value = "拒绝";
        allowText.value = "同意";
      });
    }
    function collapse() {
      minimizedRequestId = request.value && request.value.requestId;
      void setExpanded(false);
    }
    function handleMessage(message) {
      if (!message) return;
      if (message.type === "approvalState") {
        applyState(message);
      } else if (message.type === "click" && !expanded.value && !transitioning.value) {
        minimizedRequestId = null;
        void setExpanded(true);
        if (count.value > 0) post({ type: "ballClick" }).catch(() => {
        });
      } else if (message.type === "resize") {
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
        updateDragRegion().catch(() => {
        });
      } else if (message.type === "dragEnd") {
        const position = { x: message.x, y: message.y };
        if (expanded.value && panelOrigin && ballAnchor) {
          ballAnchor.x += position.x - panelOrigin.x;
          ballAnchor.y += position.y - panelOrigin.y;
          panelOrigin = position;
        } else if (!expanded.value) {
          ballAnchor = position;
        }
      } else if (message.type === "appState") {
        foreground.value = message.foreground === true;
        if (foreground.value) {
          void setExpanded(false);
          post({ type: "foreground" }).catch(() => {
          });
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
        await post({ type: "ballReady" });
      } catch (error) {
        if (request.value) post({ type: "displayError", requestId: request.value.requestId }).catch(() => {
        });
      }
    });
    onBeforeUnmount(() => {
      if (offMessage) offMessage();
    });
    return (_ctx, _cache) => {
      return openBlock(), createElementBlock("div", _hoisted_1, [
        createElementVNode(
          "div",
          {
            class: normalizeClass(["shell", { expanded: expanded.value, ready: ready.value }])
          },
          [
            _cache[4] || (_cache[4] = createElementVNode(
              "div",
              { class: "mark" },
              [
                createElementVNode("svg", {
                  viewBox: "0 0 24 24",
                  fill: "none",
                  stroke: "currentColor",
                  "stroke-width": "1.8",
                  "stroke-linecap": "round",
                  "stroke-linejoin": "round",
                  "aria-hidden": "true"
                }, [
                  createElementVNode("path", { d: "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" }),
                  createElementVNode("path", { d: "M8.5 12.2l2.3 2.3 4.7-4.7" })
                ])
              ],
              -1
              /* CACHED */
            )),
            createElementVNode(
              "span",
              {
                class: normalizeClass(["badge", { show: count.value > 0 }])
              },
              toDisplayString(badgeText.value),
              3
              /* TEXT, CLASS */
            ),
            createElementVNode("div", {
              class: "panel",
              "aria-hidden": !(expanded.value && ready.value)
            }, [
              createElementVNode("div", _hoisted_3, [
                createElementVNode("div", _hoisted_4, [
                  createElementVNode(
                    "div",
                    _hoisted_5,
                    toDisplayString(title.value),
                    1
                    /* TEXT */
                  ),
                  createElementVNode(
                    "div",
                    _hoisted_6,
                    toDisplayString(subtitle.value),
                    1
                    /* TEXT */
                  )
                ]),
                createElementVNode("button", {
                  class: "collapse",
                  type: "button",
                  "aria-label": "收起审批面板",
                  title: "收起",
                  onClick: collapse
                }, [..._cache[2] || (_cache[2] = [
                  createElementVNode(
                    "svg",
                    {
                      viewBox: "0 0 24 24",
                      fill: "none",
                      stroke: "currentColor",
                      "stroke-width": "1.8",
                      "stroke-linecap": "round",
                      "aria-hidden": "true"
                    },
                    [
                      createElementVNode("path", { d: "M6 12h12" })
                    ],
                    -1
                    /* CACHED */
                  )
                ])])
              ]),
              createElementVNode("div", _hoisted_7, [
                !request.value ? (openBlock(), createElementBlock("div", _hoisted_8, [..._cache[3] || (_cache[3] = [
                  createElementVNode(
                    "strong",
                    null,
                    "暂无待审批请求",
                    -1
                    /* CACHED */
                  ),
                  createTextVNode(
                    "有新的授权请求时会自动展开。",
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
                    "拖动标题栏可以移动面板。",
                    -1
                    /* CACHED */
                  )
                ])])) : (openBlock(), createElementBlock(
                  Fragment,
                  { key: 1 },
                  [
                    createElementVNode(
                      "div",
                      _hoisted_9,
                      toDisplayString(request.value.toolName),
                      1
                      /* TEXT */
                    ),
                    createElementVNode(
                      "pre",
                      {
                        ref_key: "previewEl",
                        ref: previewEl
                      },
                      toDisplayString(request.value.preview),
                      513
                      /* TEXT, NEED_PATCH */
                    )
                  ],
                  64
                  /* STABLE_FRAGMENT */
                ))
              ]),
              request.value ? (openBlock(), createElementBlock("div", _hoisted_10, [
                createElementVNode("button", {
                  class: "deny",
                  type: "button",
                  disabled: buttonsDisabled.value,
                  onClick: _cache[0] || (_cache[0] = ($event) => decide("deny"))
                }, toDisplayString(denyText.value), 9, _hoisted_11),
                createElementVNode("button", {
                  class: "allow",
                  type: "button",
                  disabled: buttonsDisabled.value,
                  onClick: _cache[1] || (_cache[1] = ($event) => decide("allow"))
                }, toDisplayString(allowText.value), 9, _hoisted_12)
              ])) : createCommentVNode("v-if", true)
            ], 8, _hoisted_2)
          ],
          2
          /* CLASS */
        )
      ]);
    };
  }
};
const app = createApp(_sfc_main);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
