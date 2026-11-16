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
const resolveDynamicComponent = Vue.resolveDynamicComponent;
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
const _sfc_main$2 = {
  data: function() {
    return {
      contextLoaded: false,
      canClose: true,
      isConfigPage: false,
      sheetOpen: false
    };
  },
  computed: {
    pageTitle: function() {
      return this.isConfigPage ? "插件配置" : "组件演示";
    },
    sheetTitle: function() {
      return this.isConfigPage ? "配置说明" : "底部弹窗";
    }
  },
  mounted: function() {
    this.loadContext();
  },
  methods: {
    loadContext: function() {
      var self = this;
      if (!window.aicode || typeof aicode.context !== "function") {
        self.contextLoaded = true;
        return;
      }
      aicode.context().then(function(ctx) {
        self.isConfigPage = !!ctx && ctx.pageId === "config";
        self.canClose = !self.isConfigPage;
        var path = self.isConfigPage ? "/config" : "/gallery";
        if (self.$route.path !== path) return self.$router.replace(path);
      }).catch(function() {
        self.canClose = true;
      }).finally(function() {
        self.contextLoaded = true;
      });
    },
    openSheet: function() {
      this.sheetOpen = true;
    },
    closePage: function() {
      if (window.aicode && aicode.ui && aicode.ui.close) {
        aicode.ui.close().catch(function() {
        });
      }
    }
  }
};
const _hoisted_1$2 = {
  id: "app",
  class: "aicode-page"
};
const _hoisted_2$2 = { class: "aicode-page-header" };
const _hoisted_3$2 = { class: "aicode-page-title" };
const _hoisted_4$2 = { class: "page-content" };
function _sfc_render$2(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_icon_button = resolveComponent("aicode-icon-button");
  const _component_router_view = resolveComponent("router-view");
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_bottom_sheet = resolveComponent("aicode-bottom-sheet");
  return openBlock(), createElementBlock("div", _hoisted_1$2, [
    _ctx.contextLoaded ? (openBlock(), createElementBlock(
      Fragment,
      { key: 0 },
      [
        createElementVNode("header", _hoisted_2$2, [
          _ctx.canClose ? (openBlock(), createBlock(_component_aicode_icon_button, {
            key: 0,
            label: "返回",
            onClick: $options.closePage
          }, {
            default: withCtx(() => [..._cache[3] || (_cache[3] = [
              createElementVNode(
                "svg",
                {
                  viewBox: "0 0 24 24",
                  fill: "none",
                  stroke: "currentColor",
                  "stroke-width": "2",
                  "stroke-linecap": "round",
                  "stroke-linejoin": "round",
                  "aria-hidden": "true"
                },
                [
                  createElementVNode("line", {
                    x1: "19",
                    y1: "12",
                    x2: "5",
                    y2: "12"
                  }),
                  createElementVNode("polyline", { points: "12 19 5 12 12 5" })
                ],
                -1
                /* CACHED */
              )
            ])]),
            _: 1
            /* STABLE */
          }, 8, ["onClick"])) : createCommentVNode("v-if", true),
          createElementVNode(
            "h1",
            _hoisted_3$2,
            toDisplayString($options.pageTitle),
            1
            /* TEXT */
          ),
          createVNode(_component_aicode_icon_button, {
            label: "说明",
            onClick: $options.openSheet
          }, {
            default: withCtx(() => [..._cache[4] || (_cache[4] = [
              createElementVNode(
                "svg",
                {
                  viewBox: "0 0 24 24",
                  fill: "none",
                  stroke: "currentColor",
                  "stroke-width": "2",
                  "stroke-linecap": "round",
                  "stroke-linejoin": "round",
                  "aria-hidden": "true"
                },
                [
                  createElementVNode("circle", {
                    cx: "12",
                    cy: "12",
                    r: "10"
                  }),
                  createElementVNode("line", {
                    x1: "12",
                    y1: "16",
                    x2: "12",
                    y2: "12"
                  }),
                  createElementVNode("line", {
                    x1: "12",
                    y1: "8",
                    x2: "12.01",
                    y2: "8"
                  })
                ],
                -1
                /* CACHED */
              )
            ])]),
            _: 1
            /* STABLE */
          }, 8, ["onClick"])
        ]),
        createElementVNode("div", _hoisted_4$2, [
          createVNode(_component_router_view, null, {
            default: withCtx(({ Component }) => [
              (openBlock(), createBlock(resolveDynamicComponent(Component), { onOpenSheet: $options.openSheet }, null, 40, ["onOpenSheet"]))
            ]),
            _: 1
            /* STABLE */
          })
        ]),
        createVNode(_component_aicode_bottom_sheet, {
          modelValue: _ctx.sheetOpen,
          "onUpdate:modelValue": _cache[2] || (_cache[2] = ($event) => _ctx.sheetOpen = $event),
          title: $options.sheetTitle
        }, {
          default: withCtx(() => [
            _ctx.isConfigPage ? (openBlock(), createElementBlock(
              Fragment,
              { key: 0 },
              [
                _cache[6] || (_cache[6] = createElementVNode(
                  "p",
                  { class: "aicode-text" },
                  [
                    createTextVNode("配置内容持久化存储于 "),
                    createElementVNode("code", null, "data://config.json"),
                    createTextVNode("，属于插件私有存储，卸载插件时一并清除。")
                  ],
                  -1
                  /* CACHED */
                )),
                createVNode(_component_aicode_button, {
                  variant: "primary",
                  onClick: _cache[0] || (_cache[0] = ($event) => _ctx.sheetOpen = false)
                }, {
                  default: withCtx(() => [..._cache[5] || (_cache[5] = [
                    createTextVNode(
                      "我知道了",
                      -1
                      /* CACHED */
                    )
                  ])]),
                  _: 1
                  /* STABLE */
                })
              ],
              64
              /* STABLE_FRAGMENT */
            )) : (openBlock(), createElementBlock(
              Fragment,
              { key: 1 },
              [
                _cache[8] || (_cache[8] = createElementVNode(
                  "p",
                  { class: "aicode-text" },
                  "这是宿主提供的底部弹窗组件：拖拽顶部小白条下拉可关闭，点击遮罩或右上角关闭按钮也可以退出；宽屏下会自动变成居中对话框。内容区可滚动，尺寸与设置页的 AdaptiveModalBottomSheet 一致。",
                  -1
                  /* CACHED */
                )),
                createVNode(_component_aicode_button, {
                  variant: "primary",
                  onClick: _cache[1] || (_cache[1] = ($event) => _ctx.sheetOpen = false)
                }, {
                  default: withCtx(() => [..._cache[7] || (_cache[7] = [
                    createTextVNode(
                      "关闭",
                      -1
                      /* CACHED */
                    )
                  ])]),
                  _: 1
                  /* STABLE */
                })
              ],
              64
              /* STABLE_FRAGMENT */
            ))
          ]),
          _: 1
          /* STABLE */
        }, 8, ["modelValue", "title"])
      ],
      64
      /* STABLE_FRAGMENT */
    )) : createCommentVNode("v-if", true)
  ]);
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main$2, [["render", _sfc_render$2]]);
const _sfc_main$1 = {
  emits: ["open-sheet"],
  data: function() {
    return {
      switchOn: true,
      name: "",
      description: "",
      content: "",
      scope: "project",
      inputDialogOpen: false,
      dialogName: "",
      dialogOpen: false,
      markdown: "# 标题\n\n- 列表项\n- **粗体**\n\n```\ncode\n```"
    };
  },
  computed: {
    scopeOptions: function() {
      return [
        { value: "project", label: "当前项目" },
        { value: "global", label: "全局" }
      ];
    }
  },
  methods: {
    openSheet: function() {
      this.$emit("open-sheet");
    },
    toast: function(message) {
      if (window.aicode && aicode.toast) aicode.toast(message);
    }
  }
};
const _hoisted_1$1 = { class: "gallery-view" };
const _hoisted_2$1 = { class: "demo-inline" };
const _hoisted_3$1 = { class: "demo-row" };
const _hoisted_4$1 = { class: "demo-row" };
const _hoisted_5$1 = { class: "demo-row" };
const _hoisted_6$1 = { class: "aicode-outlined-field aicode-outlined-field-body" };
const _hoisted_7$1 = { class: "demo-inline" };
function _sfc_render$1(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_row = resolveComponent("aicode-row");
  const _component_aicode_divider = resolveComponent("aicode-divider");
  const _component_aicode_swipe_row = resolveComponent("aicode-swipe-row");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_badge = resolveComponent("aicode-badge");
  const _component_aicode_switch = resolveComponent("aicode-switch");
  const _component_aicode_input = resolveComponent("aicode-input");
  const _component_aicode_field = resolveComponent("aicode-field");
  const _component_aicode_segmented = resolveComponent("aicode-segmented");
  const _component_aicode_textarea = resolveComponent("aicode-textarea");
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_empty = resolveComponent("aicode-empty");
  const _component_aicode_markdown = resolveComponent("aicode-markdown");
  const _component_aicode_dialog = resolveComponent("aicode-dialog");
  return openBlock(), createElementBlock(
    Fragment,
    null,
    [
      createElementVNode("div", _hoisted_1$1, [
        createVNode(_component_aicode_group, { title: "列表行" }, {
          default: withCtx(() => [
            createVNode(_component_aicode_row, {
              title: "默认行",
              subtitle: "标题 + 副标题 + 右箭头",
              onClick: _cache[0] || (_cache[0] = ($event) => $options.toast("点击了默认行"))
            }),
            createVNode(_component_aicode_divider),
            createVNode(_component_aicode_row, {
              title: "带徽章",
              subtitle: "插件标签与启用状态",
              badge: "插件",
              "badge-kind": "plugin",
              onClick: _cache[1] || (_cache[1] = ($event) => $options.toast("点击了带徽章行"))
            }),
            createVNode(_component_aicode_divider),
            createVNode(_component_aicode_row, {
              title: "文件图标",
              subtitle: 'icon="file"',
              icon: "file",
              onClick: _cache[2] || (_cache[2] = ($event) => $options.toast("点击了文件行"))
            }),
            createVNode(_component_aicode_divider),
            createVNode(_component_aicode_swipe_row, {
              onDelete: _cache[3] || (_cache[3] = ($event) => $options.toast("左滑删除：示例行"))
            }, {
              default: withCtx(() => [
                createVNode(_component_aicode_row, {
                  title: "左滑删除",
                  subtitle: "向左滑动露出删除按钮"
                })
              ]),
              _: 1
              /* STABLE */
            })
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "徽章" }, {
          default: withCtx(() => [
            createElementVNode("div", _hoisted_2$1, [
              createVNode(_component_aicode_badge, { text: "默认" }),
              createVNode(_component_aicode_badge, {
                text: "插件",
                kind: "plugin"
              }),
              createVNode(_component_aicode_badge, {
                text: "已启用",
                kind: "enabled"
              })
            ])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "开关" }, {
          default: withCtx(() => [
            createElementVNode("div", _hoisted_3$1, [
              _cache[19] || (_cache[19] = createElementVNode(
                "span",
                { class: "demo-label" },
                "启用示例",
                -1
                /* CACHED */
              )),
              createVNode(_component_aicode_switch, {
                modelValue: _ctx.switchOn,
                "onUpdate:modelValue": _cache[4] || (_cache[4] = ($event) => _ctx.switchOn = $event)
              }, null, 8, ["modelValue"])
            ]),
            createVNode(_component_aicode_divider),
            createElementVNode("div", _hoisted_4$1, [
              _cache[20] || (_cache[20] = createElementVNode(
                "span",
                { class: "demo-label" },
                "禁用示例",
                -1
                /* CACHED */
              )),
              createVNode(_component_aicode_switch, {
                "model-value": true,
                disabled: true
              })
            ])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "输入" }, {
          default: withCtx(() => [
            createVNode(_component_aicode_field, { label: "名称" }, {
              default: withCtx(() => [
                createVNode(_component_aicode_input, {
                  modelValue: _ctx.name,
                  "onUpdate:modelValue": _cache[5] || (_cache[5] = ($event) => _ctx.name = $event),
                  placeholder: "输入名称"
                }, null, 8, ["modelValue"])
              ]),
              _: 1
              /* STABLE */
            }),
            createVNode(_component_aicode_field, { label: "描述" }, {
              default: withCtx(() => [
                createVNode(_component_aicode_input, {
                  modelValue: _ctx.description,
                  "onUpdate:modelValue": _cache[6] || (_cache[6] = ($event) => _ctx.description = $event),
                  placeholder: "输入描述"
                }, null, 8, ["modelValue"])
              ]),
              _: 1
              /* STABLE */
            }),
            createVNode(_component_aicode_divider),
            createElementVNode("div", _hoisted_5$1, [
              _cache[21] || (_cache[21] = createElementVNode(
                "span",
                { class: "demo-label" },
                "分段按钮",
                -1
                /* CACHED */
              )),
              createVNode(_component_aicode_segmented, {
                modelValue: _ctx.scope,
                "onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => _ctx.scope = $event),
                options: $options.scopeOptions
              }, null, 8, ["modelValue", "options"])
            ])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "正文" }, {
          default: withCtx(() => [
            createElementVNode("div", _hoisted_6$1, [
              createVNode(_component_aicode_textarea, {
                modelValue: _ctx.content,
                "onUpdate:modelValue": _cache[8] || (_cache[8] = ($event) => _ctx.content = $event),
                placeholder: "输入正文"
              }, null, 8, ["modelValue"])
            ])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "按钮" }, {
          default: withCtx(() => [
            createElementVNode("div", _hoisted_7$1, [
              createVNode(_component_aicode_button, {
                variant: "primary",
                onClick: _cache[9] || (_cache[9] = ($event) => _ctx.dialogOpen = true)
              }, {
                default: withCtx(() => [..._cache[22] || (_cache[22] = [
                  createTextVNode(
                    "主要按钮",
                    -1
                    /* CACHED */
                  )
                ])]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_button, {
                onClick: _cache[10] || (_cache[10] = ($event) => $options.toast("普通按钮"))
              }, {
                default: withCtx(() => [..._cache[23] || (_cache[23] = [
                  createTextVNode(
                    "普通按钮",
                    -1
                    /* CACHED */
                  )
                ])]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_button, {
                variant: "danger",
                onClick: _cache[11] || (_cache[11] = ($event) => $options.toast("危险按钮"))
              }, {
                default: withCtx(() => [..._cache[24] || (_cache[24] = [
                  createTextVNode(
                    "危险按钮",
                    -1
                    /* CACHED */
                  )
                ])]),
                _: 1
                /* STABLE */
              })
            ])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "空态" }, {
          default: withCtx(() => [
            createVNode(_component_aicode_empty, { text: "这里没有内容" })
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "Markdown" }, {
          default: withCtx(() => [
            createVNode(_component_aicode_markdown, { text: _ctx.markdown }, null, 8, ["text"])
          ]),
          _: 1
          /* STABLE */
        }),
        createVNode(_component_aicode_group, { title: "底部弹窗与对话框" }, {
          default: withCtx(() => [
            createVNode(_component_aicode_row, {
              title: "打开底部弹窗",
              subtitle: "AicodeBottomSheet · 下拉或点击小白条关闭",
              onClick: $options.openSheet
            }, null, 8, ["onClick"]),
            createVNode(_component_aicode_divider),
            createVNode(_component_aicode_row, {
              title: "打开输入弹窗",
              subtitle: "名称输入与保存",
              onClick: _cache[12] || (_cache[12] = ($event) => {
                _ctx.dialogName = _ctx.name;
                _ctx.inputDialogOpen = true;
              })
            }),
            createVNode(_component_aicode_divider),
            createVNode(_component_aicode_row, {
              title: "打开文字确认弹窗",
              subtitle: "说明文字与确认操作",
              onClick: _cache[13] || (_cache[13] = ($event) => _ctx.dialogOpen = true)
            })
          ]),
          _: 1
          /* STABLE */
        }),
        _cache[25] || (_cache[25] = createElementVNode(
          "p",
          { class: "demo-hint" },
          "页面里所有组件都来自宿主注入的 AicodeUI，插件不需要打包 Vue 或组件代码。",
          -1
          /* CACHED */
        ))
      ]),
      createVNode(_component_aicode_dialog, {
        modelValue: _ctx.inputDialogOpen,
        "onUpdate:modelValue": _cache[15] || (_cache[15] = ($event) => _ctx.inputDialogOpen = $event),
        title: "修改名称",
        "confirm-text": "保存",
        "cancel-text": "取消",
        "confirm-disabled": !_ctx.dialogName.trim(),
        onConfirm: _cache[16] || (_cache[16] = ($event) => {
          _ctx.name = _ctx.dialogName;
          $options.toast("已保存");
        })
      }, {
        default: withCtx(() => [
          createVNode(_component_aicode_field, { label: "名称" }, {
            default: withCtx(() => [
              createVNode(_component_aicode_input, {
                modelValue: _ctx.dialogName,
                "onUpdate:modelValue": _cache[14] || (_cache[14] = ($event) => _ctx.dialogName = $event),
                placeholder: "输入名称"
              }, null, 8, ["modelValue"])
            ]),
            _: 1
            /* STABLE */
          })
        ]),
        _: 1
        /* STABLE */
      }, 8, ["modelValue", "confirm-disabled"]),
      createVNode(_component_aicode_dialog, {
        modelValue: _ctx.dialogOpen,
        "onUpdate:modelValue": _cache[17] || (_cache[17] = ($event) => _ctx.dialogOpen = $event),
        title: "确认操作",
        message: "这是宿主提供的确认对话框，样式与 App 的 AlertDialog 一致。",
        "confirm-text": "确定",
        "cancel-text": "取消",
        onConfirm: _cache[18] || (_cache[18] = ($event) => $options.toast("已确认"))
      }, null, 8, ["modelValue"])
    ],
    64
    /* STABLE_FRAGMENT */
  );
}
const GalleryView = /* @__PURE__ */ _export_sfc(_sfc_main$1, [["render", _sfc_render$1]]);
var DEFAULT_CONFIG = {
  endpoint: "https://api.example.com/v1",
  apiKey: "",
  environment: "prod",
  autoSync: true,
  verboseLog: false
};
const _sfc_main = {
  data: function() {
    return {
      config: Object.assign({}, DEFAULT_CONFIG)
    };
  },
  computed: {
    envOptions: function() {
      return [
        { value: "dev", label: "开发" },
        { value: "test", label: "测试" },
        { value: "prod", label: "生产" }
      ];
    }
  },
  mounted: function() {
    this.loadConfig();
  },
  methods: {
    loadConfig: function() {
      var self = this;
      if (!window.aicode || !window.aicode.fs) return;
      aicode.fs.read("data://config.json").then(function(res) {
        if (res && res.content) {
          try {
            self.config = Object.assign({}, DEFAULT_CONFIG, JSON.parse(res.content));
          } catch (e) {
          }
        }
      }).catch(function() {
      });
    },
    saveConfig: function() {
      var self = this;
      if (!window.aicode || !window.aicode.fs) {
        alert("保存成功 (演示模式)");
        return;
      }
      var json = JSON.stringify(self.config, null, 2);
      aicode.fs.write("data://config.json", json).then(function() {
        aicode.toast("配置已成功保存！");
      }).catch(function(err) {
        aicode.toast("保存失败：" + (err.message || err));
      });
    },
    resetConfig: function() {
      this.config = Object.assign({}, DEFAULT_CONFIG);
      if (window.aicode && aicode.toast) {
        aicode.toast("已重置为默认值");
      }
    }
  }
};
const _hoisted_1 = { class: "config-view" };
const _hoisted_2 = { class: "field-block" };
const _hoisted_3 = { class: "field-block" };
const _hoisted_4 = { class: "field-block" };
const _hoisted_5 = { class: "demo-row" };
const _hoisted_6 = { class: "demo-row" };
const _hoisted_7 = { class: "action-bar" };
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_input = resolveComponent("aicode-input");
  const _component_aicode_divider = resolveComponent("aicode-divider");
  const _component_aicode_segmented = resolveComponent("aicode-segmented");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_switch = resolveComponent("aicode-switch");
  const _component_aicode_button = resolveComponent("aicode-button");
  return openBlock(), createElementBlock("div", _hoisted_1, [
    createVNode(_component_aicode_group, { title: "服务接入凭证" }, {
      default: withCtx(() => [
        createElementVNode("div", _hoisted_2, [
          _cache[5] || (_cache[5] = createElementVNode(
            "div",
            { class: "field-title" },
            "API 端点 (Endpoint)",
            -1
            /* CACHED */
          )),
          createVNode(_component_aicode_input, {
            modelValue: _ctx.config.endpoint,
            "onUpdate:modelValue": _cache[0] || (_cache[0] = ($event) => _ctx.config.endpoint = $event),
            placeholder: "https://api.example.com/v1"
          }, null, 8, ["modelValue"])
        ]),
        createVNode(_component_aicode_divider),
        createElementVNode("div", _hoisted_3, [
          _cache[6] || (_cache[6] = createElementVNode(
            "div",
            { class: "field-title" },
            "API 密钥 (Token)",
            -1
            /* CACHED */
          )),
          createVNode(_component_aicode_input, {
            modelValue: _ctx.config.apiKey,
            "onUpdate:modelValue": _cache[1] || (_cache[1] = ($event) => _ctx.config.apiKey = $event),
            placeholder: "sk-...",
            type: "password"
          }, null, 8, ["modelValue"])
        ]),
        createVNode(_component_aicode_divider),
        createElementVNode("div", _hoisted_4, [
          _cache[7] || (_cache[7] = createElementVNode(
            "div",
            { class: "field-title" },
            "运行环境",
            -1
            /* CACHED */
          )),
          createVNode(_component_aicode_segmented, {
            modelValue: _ctx.config.environment,
            "onUpdate:modelValue": _cache[2] || (_cache[2] = ($event) => _ctx.config.environment = $event),
            options: $options.envOptions
          }, null, 8, ["modelValue", "options"])
        ])
      ]),
      _: 1
      /* STABLE */
    }),
    createVNode(_component_aicode_group, { title: "行为偏好" }, {
      default: withCtx(() => [
        createElementVNode("div", _hoisted_5, [
          _cache[8] || (_cache[8] = createElementVNode(
            "div",
            null,
            [
              createElementVNode("div", { class: "demo-label" }, "自动同步配置"),
              createElementVNode("p", { class: "demo-hint" }, "每次启动插件时从远程更新规则")
            ],
            -1
            /* CACHED */
          )),
          createVNode(_component_aicode_switch, {
            modelValue: _ctx.config.autoSync,
            "onUpdate:modelValue": _cache[3] || (_cache[3] = ($event) => _ctx.config.autoSync = $event)
          }, null, 8, ["modelValue"])
        ]),
        createVNode(_component_aicode_divider),
        createElementVNode("div", _hoisted_6, [
          _cache[9] || (_cache[9] = createElementVNode(
            "div",
            null,
            [
              createElementVNode("div", { class: "demo-label" }, "详细调试日志"),
              createElementVNode("p", { class: "demo-hint" }, "向应用日志打印完整的请求与响应包")
            ],
            -1
            /* CACHED */
          )),
          createVNode(_component_aicode_switch, {
            modelValue: _ctx.config.verboseLog,
            "onUpdate:modelValue": _cache[4] || (_cache[4] = ($event) => _ctx.config.verboseLog = $event)
          }, null, 8, ["modelValue"])
        ])
      ]),
      _: 1
      /* STABLE */
    }),
    createElementVNode("div", _hoisted_7, [
      createVNode(_component_aicode_button, {
        variant: "primary",
        style: { "flex": "1" },
        onClick: $options.saveConfig
      }, {
        default: withCtx(() => [..._cache[10] || (_cache[10] = [
          createTextVNode(
            "保存配置",
            -1
            /* CACHED */
          )
        ])]),
        _: 1
        /* STABLE */
      }, 8, ["onClick"]),
      createVNode(_component_aicode_button, {
        variant: "secondary",
        onClick: $options.resetConfig
      }, {
        default: withCtx(() => [..._cache[11] || (_cache[11] = [
          createTextVNode(
            "重置",
            -1
            /* CACHED */
          )
        ])]),
        _: 1
        /* STABLE */
      }, 8, ["onClick"])
    ]),
    _cache[12] || (_cache[12] = createElementVNode(
      "p",
      {
        class: "demo-hint",
        style: { "text-align": "center", "margin-top": "16px" }
      },
      [
        createTextVNode(" 配置页与组件演示共用 "),
        createElementVNode("code", null, "pages/index.html"),
        createTextVNode("："),
        createElementVNode("code", null, "configPage"),
        createTextVNode(" 打开时路由到 "),
        createElementVNode("code", null, "/config"),
        createTextVNode(" 渲染这里。 ")
      ],
      -1
      /* CACHED */
    ))
  ]);
}
const ConfigView = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
if (window.eruda) window.eruda.init();
const router = window.VueRouter.createRouter({
  history: window.VueRouter.createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/gallery" },
    { path: "/gallery", component: GalleryView },
    { path: "/config", component: ConfigView },
    { path: "/:pathMatch(.*)*", redirect: "/gallery" }
  ]
});
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.use(router);
router.isReady().then(function() {
  app.mount("#app");
});
