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
const createBlock = Vue.createBlock;
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
export {
  _export_sfc as _,
  createElementVNode as a,
  createVNode as b,
  createElementBlock as c,
  createTextVNode as d,
  createBlock as e,
  createCommentVNode as f,
  createApp as g,
  openBlock as o,
  resolveComponent as r,
  toDisplayString as t,
  withCtx as w
};
