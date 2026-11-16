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
      token: "",
      keys: [],
      busy: false,
      result: "尚未验证"
    };
  },
  mounted: function() {
    this.refreshKeys();
  },
  methods: {
    invoke: function(handler, args) {
      if (!window.aicode || typeof aicode.invoke !== "function") {
        return Promise.reject(new Error("当前 APK 不支持插件运行时调用"));
      }
      return aicode.invoke(handler, args);
    },
    refreshKeys: async function() {
      try {
        var result = await this.invoke("actions.listKeys");
        this.keys = result && result.keys || [];
      } catch (error) {
        this.result = "读取凭据失败：" + (error.message || error);
      }
    },
    saveKey: async function() {
      if (this.busy) return;
      this.busy = true;
      try {
        var result = await this.invoke("actions.saveKey", { token: this.token });
        if (!result || result.ok !== true) {
          throw new Error(result && result.message || "保存失败");
        }
        this.keys = result.keys || [];
        this.token = "";
        aicode.toast("API Key 已保存");
      } catch (error) {
        this.result = "保存失败：" + (error.message || error);
      } finally {
        this.busy = false;
      }
    },
    deleteKey: async function() {
      if (this.busy) return;
      this.busy = true;
      try {
        var result = await this.invoke("actions.deleteKey");
        this.keys = result && result.keys || [];
        this.result = "已删除保存的 API Key";
        aicode.toast("已删除");
      } catch (error) {
        this.result = "删除失败：" + (error.message || error);
      } finally {
        this.busy = false;
      }
    },
    testKey: async function() {
      if (this.busy) return;
      this.busy = true;
      this.result = "请求中…";
      try {
        var result = await this.invoke("actions.test");
        this.result = result.ok ? "HTTP " + result.status + "，Key 已被接受" : "HTTP " + (result.status === void 0 ? "-" : result.status) + "，" + (result.message || "Key 未被接受");
      } catch (error) {
        this.result = "请求失败：" + (error.message || error);
      } finally {
        this.busy = false;
      }
    }
  }
};
const _hoisted_1 = {
  id: "app",
  class: "aicode-page"
};
const _hoisted_2 = { class: "page-content" };
const _hoisted_3 = { class: "field-block" };
const _hoisted_4 = { class: "action-bar" };
const _hoisted_5 = { class: "result" };
const _hoisted_6 = {
  class: "action-bar",
  style: { "padding": "0 16px 12px" }
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_input = resolveComponent("aicode-input");
  const _component_aicode_divider = resolveComponent("aicode-divider");
  const _component_aicode_info_row = resolveComponent("aicode-info-row");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_button = resolveComponent("aicode-button");
  return openBlock(), createElementBlock("div", _hoisted_1, [
    createElementVNode("div", _hoisted_2, [
      createVNode(_component_aicode_group, { title: "接入凭据" }, {
        default: withCtx(() => [
          createElementVNode("div", _hoisted_3, [
            _cache[1] || (_cache[1] = createElementVNode(
              "div",
              { class: "field-title" },
              "API Key",
              -1
              /* CACHED */
            )),
            createVNode(_component_aicode_input, {
              modelValue: _ctx.token,
              "onUpdate:modelValue": _cache[0] || (_cache[0] = ($event) => _ctx.token = $event),
              type: "password",
              placeholder: "粘贴服务商签发的 Key"
            }, null, 8, ["modelValue"]),
            _cache[2] || (_cache[2] = createElementVNode(
              "p",
              { class: "field-hint" },
              "保存后写入宿主加密存储，页面与 AI 都不会再读到原文。",
              -1
              /* CACHED */
            ))
          ]),
          createVNode(_component_aicode_divider),
          createVNode(_component_aicode_info_row, {
            label: "已保存的键",
            value: _ctx.keys.length ? _ctx.keys.join("、") : "无"
          }, null, 8, ["value"])
        ]),
        _: 1
        /* STABLE */
      }),
      createElementVNode("div", _hoisted_4, [
        createVNode(_component_aicode_button, {
          variant: "primary",
          style: { "flex": "1" },
          disabled: _ctx.busy,
          onClick: $options.saveKey
        }, {
          default: withCtx(() => [..._cache[3] || (_cache[3] = [
            createTextVNode(
              "保存",
              -1
              /* CACHED */
            )
          ])]),
          _: 1
          /* STABLE */
        }, 8, ["disabled", "onClick"]),
        createVNode(_component_aicode_button, {
          variant: "secondary",
          disabled: _ctx.busy || !_ctx.keys.length,
          onClick: $options.deleteKey
        }, {
          default: withCtx(() => [..._cache[4] || (_cache[4] = [
            createTextVNode(
              "删除",
              -1
              /* CACHED */
            )
          ])]),
          _: 1
          /* STABLE */
        }, 8, ["disabled", "onClick"])
      ]),
      createVNode(_component_aicode_group, { title: "验证" }, {
        default: withCtx(() => [
          createVNode(_component_aicode_info_row, {
            label: "请求地址",
            value: "httpbin.org/bearer"
          }),
          createVNode(_component_aicode_divider),
          createElementVNode(
            "pre",
            _hoisted_5,
            toDisplayString(_ctx.result),
            1
            /* TEXT */
          ),
          createElementVNode("div", _hoisted_6, [
            createVNode(_component_aicode_button, {
              variant: "primary",
              style: { "flex": "1" },
              disabled: _ctx.busy,
              onClick: $options.testKey
            }, {
              default: withCtx(() => [..._cache[5] || (_cache[5] = [
                createTextVNode(
                  "测试连接",
                  -1
                  /* CACHED */
                )
              ])]),
              _: 1
              /* STABLE */
            }, 8, ["disabled", "onClick"])
          ])
        ]),
        _: 1
        /* STABLE */
      }),
      _cache[6] || (_cache[6] = createElementVNode(
        "p",
        {
          class: "field-hint",
          style: { "text-align": "center" }
        },
        [
          createTextVNode(" 「验证 API Key」工具与「测试连接」走同一条请求链路："),
          createElementVNode("code", null, "host.secrets.get"),
          createTextVNode(" 读 Key，"),
          createElementVNode("code", null, "host.http.request"),
          createTextVNode(" 发起请求。 ")
        ],
        -1
        /* CACHED */
      ))
    ])
  ]);
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
