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
Vue.createCommentVNode;
const createElementBlock = Vue.createElementBlock;
const createElementVNode = Vue.createElementVNode;
Vue.createHydrationRenderer;
Vue.createPropsRestProxy;
Vue.createRenderer;
Vue.createSSRApp;
Vue.createSlots;
Vue.createStaticVNode;
Vue.createTextVNode;
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
Vue.nextTick;
Vue.nodeOps;
Vue.normalizeClass;
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
const _sfc_main = {
  __name: "config",
  setup(__props) {
    const output = ref("尚未操作");
    function showError(text) {
      output.value = text;
      if (window.eruda) eruda.show();
    }
    function onWindowError(event) {
      showError("页面错误：" + (event.message || event));
    }
    function onUnhandledRejection(event) {
      showError("未处理异常：" + (event.reason && event.reason.message || event.reason));
    }
    async function showTip() {
      console.log("[capability-demo] tip clicked");
      try {
        const result = await aicode.tip({
          title: "能力演示",
          text: "这条提示来自插件配置页，点「知道了」可以验证按钮回调。",
          action: { label: "知道了", handler: "tools.tipAction" }
        });
        output.value = JSON.stringify(result, null, 2);
      } catch (error) {
        output.value = "提示失败：" + (error.message || error);
      }
    }
    async function runShizuku() {
      console.log("[capability-demo] shizuku clicked");
      try {
        const result = await aicode.shizuku.exec("settings get global zen_mode");
        output.value = JSON.stringify(result, null, 2);
      } catch (error) {
        output.value = "Shizuku 失败：" + (error.message || error);
      }
    }
    function openConsole() {
      if (window.eruda) {
        eruda.show();
      } else {
        output.value = "调试控制台未加载：请安装最新 APK";
      }
    }
    onMounted(() => {
      if (window.eruda) {
        eruda.init();
      }
      console.log("[capability-demo] config page loaded", {
        hasAicode: !!window.aicode,
        hasTip: !!(window.aicode && window.aicode.tip),
        hasShizuku: !!(window.aicode && window.aicode.shizuku),
        hasEruda: !!window.eruda
      });
      window.addEventListener("error", onWindowError);
      window.addEventListener("unhandledrejection", onUnhandledRejection);
      if (!window.aicode) {
        output.value = "页面 SDK 未加载：请安装最新 APK 后重试";
      } else if (typeof aicode.tip !== "function" || !aicode.shizuku) {
        output.value = "当前 APK 缺少 tip / Shizuku 页面接口，请安装最新 APK";
      } else {
        aicode.context().then((context) => {
          if (output.value === "尚未操作") {
            output.value = "页面已连接：" + (context.pageId || context.pluginId || "ok");
          }
        }).catch((error) => {
          output.value = "页面连接失败：" + (error.message || error);
        });
      }
    });
    onBeforeUnmount(() => {
      window.removeEventListener("error", onWindowError);
      window.removeEventListener("unhandledrejection", onUnhandledRejection);
    });
    return (_ctx, _cache) => {
      return openBlock(), createElementBlock("div", _hoisted_1, [
        _cache[0] || (_cache[0] = createElementVNode(
          "h2",
          null,
          "能力演示",
          -1
          /* CACHED */
        )),
        _cache[1] || (_cache[1] = createElementVNode(
          "p",
          null,
          "先在「管理」页开启 Shizuku 权限，再点下面的按钮。提示条会出现在对话页底部。",
          -1
          /* CACHED */
        )),
        createElementVNode("button", {
          type: "button",
          onClick: showTip
        }, "显示提示条"),
        createElementVNode("button", {
          type: "button",
          onClick: runShizuku
        }, "执行 Shizuku 查询"),
        createElementVNode("button", {
          type: "button",
          onClick: openConsole
        }, "打开调试控制台"),
        createElementVNode(
          "pre",
          null,
          toDisplayString(output.value),
          1
          /* TEXT */
        )
      ]);
    };
  }
};
const app = createApp(_sfc_main);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
