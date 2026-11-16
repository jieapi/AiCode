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
Vue.Fragment;
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
Vue.computed;
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
const createVNode = Vue.createVNode;
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
Vue.nextTick;
Vue.nodeOps;
Vue.normalizeClass;
Vue.normalizeProps;
Vue.normalizeStyle;
Vue.onActivated;
Vue.onBeforeMount;
Vue.onBeforeUnmount;
Vue.onBeforeUpdate;
Vue.onDeactivated;
Vue.onErrorCaptured;
Vue.onMounted;
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
Vue.ref;
Vue.registerRuntimeCompiler;
Vue.render;
Vue.renderList;
Vue.renderSlot;
const resolveComponent = Vue.resolveComponent;
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
const withCtx = Vue.withCtx;
Vue.withDefaults;
Vue.withDirectives;
Vue.withKeys;
Vue.withMemo;
Vue.withModifiers;
Vue.withScopeId;
const _export_sfc = (sfc, props) => {
  const target = sfc.__vccOpts || sfc;
  for (const [key, val] of props) {
    target[key] = val;
  }
  return target;
};
const _sfc_main = {
  data: function() {
    return {
      loading: false,
      status: "",
      files: "",
      fileCount: 0
    };
  },
  mounted: function() {
    this.load();
  },
  methods: {
    load: function() {
      var self = this;
      if (self.loading) return;
      self.loading = true;
      Promise.all([
        aicode.bash("git status --short --branch", { timeout: 3e4 }),
        aicode.bash("git ls-files | head -200", { timeout: 3e4 })
      ]).then(function(results) {
        self.status = results[0] && results[0].output || "（无输出）";
        var files = results[1] && results[1].output || "";
        self.files = files || "（无输出）";
        self.fileCount = files ? files.split("\n").filter(function(line) {
          return line.trim();
        }).length : 0;
      }).catch(function(err) {
        self.status = "读取失败：" + (err && err.message ? err.message : err);
        self.files = "";
        self.fileCount = 0;
      }).finally(function() {
        self.loading = false;
      });
    }
  }
};
const _hoisted_1 = {
  id: "app",
  class: "aicode-page"
};
const _hoisted_2 = { class: "page-content" };
const _hoisted_3 = { class: "output" };
const _hoisted_4 = {
  key: 0,
  class: "hint"
};
const _hoisted_5 = { class: "output" };
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_group = resolveComponent("aicode-group");
  return openBlock(), createElementBlock("div", _hoisted_1, [
    createElementVNode("div", _hoisted_2, [
      createVNode(_component_aicode_button, {
        variant: "primary",
        disabled: _ctx.loading,
        onClick: $options.load
      }, {
        default: withCtx(() => [
          createTextVNode(
            toDisplayString(_ctx.loading ? "读取中…" : "刷新项目信息"),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      }, 8, ["disabled", "onClick"]),
      createVNode(_component_aicode_group, { title: "仓库状态" }, {
        default: withCtx(() => [
          createElementVNode(
            "pre",
            _hoisted_3,
            toDisplayString(_ctx.status || "尚无输出"),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      }),
      createVNode(_component_aicode_group, { title: "跟踪的文件" }, {
        default: withCtx(() => [
          _ctx.fileCount ? (openBlock(), createElementBlock(
            "div",
            _hoisted_4,
            toDisplayString(_ctx.fileCount) + " 个文件（最多显示 200 个）",
            1
            /* TEXT */
          )) : createCommentVNode("v-if", true),
          createElementVNode(
            "pre",
            _hoisted_5,
            toDisplayString(_ctx.files || "尚无输出"),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      })
    ])
  ]);
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
