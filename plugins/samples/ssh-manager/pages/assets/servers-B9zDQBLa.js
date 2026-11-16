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
const normalizeClass = Vue.normalizeClass;
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
const renderList = Vue.renderList;
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
const vShow = Vue.vShow;
Vue.version;
Vue.warn;
Vue.watch;
Vue.watchEffect;
Vue.watchPostEffect;
Vue.watchSyncEffect;
Vue.withAsyncContext;
const withCtx = Vue.withCtx;
Vue.withDefaults;
const withDirectives = Vue.withDirectives;
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
function emptyForm() {
  return {
    id: "",
    name: "",
    host: "",
    port: 22,
    username: "root",
    auth: "key",
    privateKeyPath: "data://id_ed25519",
    passphrase: "",
    password: "",
    enabled: true,
    status: "offline",
    error: ""
  };
}
function plainServer(server) {
  return {
    id: server.id || "",
    name: server.name || "",
    host: server.host || "",
    port: Number(server.port) || 22,
    username: server.username || "",
    auth: server.auth === "password" ? "password" : "key",
    privateKeyPath: server.privateKeyPath || "",
    passphrase: server.passphrase || "",
    password: server.password || "",
    enabled: server.enabled !== false
  };
}
const _sfc_main = {
  data: function() {
    return {
      view: "list",
      servers: [],
      current: null,
      form: emptyForm(),
      editingId: "",
      busy: false,
      saving: false,
      error: ""
    };
  },
  computed: {
    pageTitle: function() {
      if (this.view === "edit") return this.editingId ? "编辑服务器" : "新建服务器";
      if (this.view === "detail") return this.current ? this.current.name || this.current.host : "SSH 服务器";
      return "SSH 服务器";
    },
    authOptions: function() {
      return [
        { value: "key", label: "私钥" },
        { value: "password", label: "密码" }
      ];
    }
  },
  mounted: function() {
    var self = this;
    this.load().then(function() {
      return self.refreshStatus();
    });
    if (window.aicode && aicode.ui && aicode.ui.onBack) {
      aicode.ui.onBack(this.handleBack);
    }
    if (window.aicode && aicode.events) {
      aicode.events.on(function(event) {
        if (!event || event.name !== "ssh.status" || !event.payload) return;
        var payload = event.payload;
        var server = self.servers.find(function(item) {
          return item.id === payload.serverId;
        });
        if (!server) return;
        server.status = payload.status;
        server.error = payload.error || "";
        if (self.current && self.current.id === server.id) self.current = server;
      });
    }
  },
  methods: {
    load: function() {
      var self = this;
      return aicode.fs.read("data://servers.json").then(function(res) {
        var parsed = {};
        try {
          parsed = res && res.content ? JSON.parse(res.content) : {};
        } catch (e) {
        }
        self.servers = (Array.isArray(parsed.servers) ? parsed.servers : []).map(function(server) {
          var item = plainServer(server);
          item.status = "offline";
          return item;
        });
      }).catch(function() {
        self.servers = [];
      });
    },
    mergeStatus: function(list) {
      var byId = {};
      (list || []).forEach(function(server) {
        byId[server.id] = server;
      });
      this.servers.forEach(function(server) {
        var live = byId[server.id];
        if (!live) return;
        server.status = live.status || "offline";
        server.error = live.error || "";
      });
      if (this.current) {
        var currentId = this.current.id;
        var updated = this.servers.find(function(server) {
          return server.id === currentId;
        });
        if (updated) this.current = updated;
      }
    },
    refreshStatus: function() {
      var self = this;
      return aicode.invoke("ui.status").then(function(res) {
        if (res && Array.isArray(res.servers)) self.mergeStatus(res.servers);
      }).catch(function(e) {
        self.error = e.message || String(e);
      });
    },
    saveConfig: function() {
      return aicode.fs.write("data://servers.json", JSON.stringify({ servers: this.servers }, null, 2));
    },
    openDetail: function(server) {
      this.current = server;
      this.view = "detail";
      this.error = "";
    },
    openEdit: function(server) {
      this.error = "";
      if (server) {
        this.editingId = server.id;
        this.form = Object.assign(emptyForm(), server);
        delete this.form.status;
        delete this.form.error;
      } else {
        this.editingId = "";
        this.form = emptyForm();
      }
      this.view = "edit";
    },
    handleBack: function() {
      if (this.view === "list") return false;
      if (this.view === "edit") {
        this.view = this.editingId ? "detail" : "list";
      } else {
        this.view = "list";
      }
      return true;
    },
    handleBackButton: function() {
      if (!this.handleBack()) {
        aicode.ui.close().catch(function() {
        });
      }
    },
    save: function() {
      var self = this;
      this.error = "";
      if (!this.form.name || !this.form.host || !this.form.username) {
        this.error = "名称、Host、用户名不能为空";
        return;
      }
      this.saving = true;
      this.form.port = Number(this.form.port) || 22;
      var data = plainServer(this.form);
      var index = this.servers.findIndex(function(item) {
        return item.id === self.editingId;
      });
      if (index >= 0) {
        data.id = this.editingId;
        this.servers.splice(index, 1, data);
      } else {
        data.id = "srv-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 7);
        this.servers.push(data);
      }
      this.saveConfig().then(function() {
        return aicode.invoke("ui.reload");
      }).then(function(res) {
        if (res && Array.isArray(res.servers)) self.mergeStatus(res.servers);
        self.view = "list";
        aicode.toast("已保存");
      }).catch(function(e) {
        self.error = e.message || String(e);
      }).then(function() {
        self.saving = false;
      });
    },
    remove: function(server) {
      var self = this;
      this.servers = this.servers.filter(function(item) {
        return item.id !== server.id;
      });
      this.current = null;
      this.view = "list";
      this.saveConfig().then(function() {
        aicode.invoke("ui.disconnect", { serverId: server.id }).catch(function() {
        });
        return self.refreshStatus();
      }).catch(function(e) {
        self.error = e.message || String(e);
      });
    },
    connect: function(server) {
      var self = this;
      if (!server) return;
      this.busy = true;
      aicode.invoke("ui.connect", { serverId: server.id }).then(function() {
        return self.refreshStatus();
      }).catch(function(e) {
        aicode.toast(e.message || String(e));
      }).then(function() {
        self.busy = false;
      });
    },
    disconnect: function(server) {
      var self = this;
      if (!server) return;
      aicode.invoke("ui.disconnect", { serverId: server.id }).then(function() {
        return self.refreshStatus();
      }).catch(function(e) {
        aicode.toast(e.message || String(e));
      });
    },
    statusLabel: function(status) {
      if (status === "online") return "已连接";
      if (status === "connecting") return "连接中";
      return "未连接";
    }
  }
};
const _hoisted_1 = {
  id: "app",
  class: "aicode-page"
};
const _hoisted_2 = { class: "aicode-page-header" };
const _hoisted_3 = { class: "aicode-page-title" };
const _hoisted_4 = ["disabled"];
const _hoisted_5 = { class: "page-content" };
const _hoisted_6 = { class: "view" };
const _hoisted_7 = {
  key: 0,
  class: "aicode-divider"
};
const _hoisted_8 = { class: "view" };
const _hoisted_9 = { class: "aicode-info-row" };
const _hoisted_10 = { class: "aicode-info-row" };
const _hoisted_11 = { class: "aicode-info-row" };
const _hoisted_12 = { class: "aicode-info-row" };
const _hoisted_13 = { class: "aicode-info-row" };
const _hoisted_14 = {
  key: 0,
  class: "warning"
};
const _hoisted_15 = { class: "action-bar" };
const _hoisted_16 = { class: "view" };
const _hoisted_17 = { class: "aicode-scope-row" };
const _hoisted_18 = {
  key: 0,
  class: "hint"
};
const _hoisted_19 = {
  key: 1,
  class: "aicode-error"
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_icon_button = resolveComponent("aicode-icon-button");
  const _component_aicode_row = resolveComponent("aicode-row");
  const _component_aicode_swipe_row = resolveComponent("aicode-swipe-row");
  const _component_aicode_empty = resolveComponent("aicode-empty");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_input = resolveComponent("aicode-input");
  const _component_aicode_field = resolveComponent("aicode-field");
  const _component_aicode_segmented = resolveComponent("aicode-segmented");
  const _component_aicode_switch = resolveComponent("aicode-switch");
  return openBlock(), createElementBlock("div", _hoisted_1, [
    createElementVNode("header", _hoisted_2, [
      createVNode(_component_aicode_icon_button, {
        label: "返回",
        onClick: $options.handleBackButton
      }, {
        default: withCtx(() => [..._cache[14] || (_cache[14] = [
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
      }, 8, ["onClick"]),
      createElementVNode(
        "h1",
        _hoisted_3,
        toDisplayString($options.pageTitle),
        1
        /* TEXT */
      ),
      _ctx.view === "list" ? (openBlock(), createBlock(_component_aicode_icon_button, {
        key: 0,
        label: "新建",
        onClick: _cache[0] || (_cache[0] = ($event) => $options.openEdit(null))
      }, {
        default: withCtx(() => [..._cache[15] || (_cache[15] = [
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
                x1: "12",
                y1: "5",
                x2: "12",
                y2: "19"
              }),
              createElementVNode("line", {
                x1: "5",
                y1: "12",
                x2: "19",
                y2: "12"
              })
            ],
            -1
            /* CACHED */
          )
        ])]),
        _: 1
        /* STABLE */
      })) : createCommentVNode("v-if", true),
      _ctx.view === "edit" ? (openBlock(), createElementBlock("button", {
        key: 1,
        class: "aicode-text-button",
        disabled: _ctx.saving,
        onClick: _cache[1] || (_cache[1] = (...args) => $options.save && $options.save(...args))
      }, "保存", 8, _hoisted_4)) : createCommentVNode("v-if", true)
    ]),
    createElementVNode("div", _hoisted_5, [
      withDirectives(createElementVNode(
        "section",
        _hoisted_6,
        [
          createVNode(_component_aicode_group, { title: "SSH 服务器" }, {
            default: withCtx(() => [
              _ctx.servers.length ? (openBlock(true), createElementBlock(
                Fragment,
                { key: 0 },
                renderList(_ctx.servers, (server, index) => {
                  return openBlock(), createElementBlock(
                    Fragment,
                    {
                      key: server.id
                    },
                    [
                      index > 0 ? (openBlock(), createElementBlock("div", _hoisted_7)) : createCommentVNode("v-if", true),
                      createVNode(_component_aicode_swipe_row, {
                        onDelete: ($event) => $options.remove(server)
                      }, {
                        default: withCtx(() => [
                          createVNode(_component_aicode_row, {
                            title: server.name || server.host,
                            subtitle: server.username + "@" + server.host + ":" + server.port,
                            badge: $options.statusLabel(server.status),
                            onClick: ($event) => $options.openDetail(server)
                          }, null, 8, ["title", "subtitle", "badge", "onClick"])
                        ]),
                        _: 2
                        /* DYNAMIC */
                      }, 1032, ["onDelete"])
                    ],
                    64
                    /* STABLE_FRAGMENT */
                  );
                }),
                128
                /* KEYED_FRAGMENT */
              )) : (openBlock(), createBlock(_component_aicode_empty, {
                key: 1,
                text: "暂无 SSH 服务器"
              }))
            ]),
            _: 1
            /* STABLE */
          }),
          _cache[16] || (_cache[16] = createElementVNode(
            "p",
            { class: "hint" },
            "App 启动时会自动连接启用项；连接由常驻插件进程维护。",
            -1
            /* CACHED */
          )),
          _cache[17] || (_cache[17] = createElementVNode(
            "div",
            { class: "warning" },
            [
              createTextVNode(" 密码和私钥路径明文保存在插件数据目录 "),
              createElementVNode("code", null, "data://servers.json"),
              createTextVNode("，不会进入 AiCode 备份。建议使用受限账号，私钥放在插件数据目录。 ")
            ],
            -1
            /* CACHED */
          ))
        ],
        512
        /* NEED_PATCH */
      ), [
        [vShow, _ctx.view === "list"]
      ]),
      withDirectives(createElementVNode(
        "section",
        _hoisted_8,
        [
          createVNode(_component_aicode_group, {
            title: "连接信息",
            collapsible: false
          }, {
            default: withCtx(() => [
              createElementVNode("div", _hoisted_9, [
                _cache[18] || (_cache[18] = createElementVNode(
                  "span",
                  null,
                  "名称",
                  -1
                  /* CACHED */
                )),
                createElementVNode(
                  "span",
                  null,
                  toDisplayString(_ctx.current ? _ctx.current.name : ""),
                  1
                  /* TEXT */
                )
              ]),
              _cache[23] || (_cache[23] = createElementVNode(
                "div",
                { class: "aicode-divider aicode-divider-flat" },
                null,
                -1
                /* CACHED */
              )),
              createElementVNode("div", _hoisted_10, [
                _cache[19] || (_cache[19] = createElementVNode(
                  "span",
                  null,
                  "地址",
                  -1
                  /* CACHED */
                )),
                createElementVNode(
                  "span",
                  null,
                  toDisplayString(_ctx.current ? _ctx.current.username + "@" + _ctx.current.host + ":" + _ctx.current.port : ""),
                  1
                  /* TEXT */
                )
              ]),
              _cache[24] || (_cache[24] = createElementVNode(
                "div",
                { class: "aicode-divider aicode-divider-flat" },
                null,
                -1
                /* CACHED */
              )),
              createElementVNode("div", _hoisted_11, [
                _cache[20] || (_cache[20] = createElementVNode(
                  "span",
                  null,
                  "认证",
                  -1
                  /* CACHED */
                )),
                createElementVNode(
                  "span",
                  null,
                  toDisplayString(_ctx.current && _ctx.current.auth === "password" ? "密码" : "私钥"),
                  1
                  /* TEXT */
                )
              ]),
              _cache[25] || (_cache[25] = createElementVNode(
                "div",
                { class: "aicode-divider aicode-divider-flat" },
                null,
                -1
                /* CACHED */
              )),
              createElementVNode("div", _hoisted_12, [
                _cache[21] || (_cache[21] = createElementVNode(
                  "span",
                  null,
                  "状态",
                  -1
                  /* CACHED */
                )),
                createElementVNode(
                  "span",
                  {
                    class: normalizeClass(["status", _ctx.current ? _ctx.current.status : "offline"])
                  },
                  toDisplayString($options.statusLabel(_ctx.current ? _ctx.current.status : "offline")),
                  3
                  /* TEXT, CLASS */
                )
              ]),
              _cache[26] || (_cache[26] = createElementVNode(
                "div",
                { class: "aicode-divider aicode-divider-flat" },
                null,
                -1
                /* CACHED */
              )),
              createElementVNode("div", _hoisted_13, [
                _cache[22] || (_cache[22] = createElementVNode(
                  "span",
                  null,
                  "启动连接",
                  -1
                  /* CACHED */
                )),
                createElementVNode(
                  "span",
                  null,
                  toDisplayString(_ctx.current && _ctx.current.enabled !== false ? "是" : "否"),
                  1
                  /* TEXT */
                )
              ])
            ]),
            _: 1
            /* STABLE */
          }),
          _ctx.current && _ctx.current.error ? (openBlock(), createElementBlock(
            "p",
            _hoisted_14,
            toDisplayString(_ctx.current.error),
            1
            /* TEXT */
          )) : createCommentVNode("v-if", true),
          createElementVNode("div", _hoisted_15, [
            createVNode(_component_aicode_button, {
              disabled: _ctx.busy || !_ctx.current || _ctx.current.status === "online",
              onClick: _cache[2] || (_cache[2] = ($event) => $options.connect(_ctx.current))
            }, {
              default: withCtx(() => [..._cache[27] || (_cache[27] = [
                createTextVNode(
                  "连接",
                  -1
                  /* CACHED */
                )
              ])]),
              _: 1
              /* STABLE */
            }, 8, ["disabled"]),
            createVNode(_component_aicode_button, {
              disabled: _ctx.busy || !_ctx.current || _ctx.current.status === "offline",
              onClick: _cache[3] || (_cache[3] = ($event) => $options.disconnect(_ctx.current))
            }, {
              default: withCtx(() => [..._cache[28] || (_cache[28] = [
                createTextVNode(
                  "断开",
                  -1
                  /* CACHED */
                )
              ])]),
              _: 1
              /* STABLE */
            }, 8, ["disabled"]),
            createVNode(_component_aicode_button, {
              onClick: _cache[4] || (_cache[4] = ($event) => $options.openEdit(_ctx.current))
            }, {
              default: withCtx(() => [..._cache[29] || (_cache[29] = [
                createTextVNode(
                  "编辑",
                  -1
                  /* CACHED */
                )
              ])]),
              _: 1
              /* STABLE */
            })
          ])
        ],
        512
        /* NEED_PATCH */
      ), [
        [vShow, _ctx.view === "detail"]
      ]),
      withDirectives(createElementVNode(
        "section",
        _hoisted_16,
        [
          createVNode(_component_aicode_group, {
            title: "基本信息",
            collapsible: false
          }, {
            default: withCtx(() => [
              createVNode(_component_aicode_field, { label: "名称" }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.name,
                    "onUpdate:modelValue": _cache[5] || (_cache[5] = ($event) => _ctx.form.name = $event),
                    placeholder: "production"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_field, { label: "Host" }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.host,
                    "onUpdate:modelValue": _cache[6] || (_cache[6] = ($event) => _ctx.form.host = $event),
                    placeholder: "192.168.1.10"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_field, { label: "端口" }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.port,
                    "onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => _ctx.form.port = $event),
                    modelModifiers: { number: true },
                    placeholder: "22"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_field, { label: "用户名" }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.username,
                    "onUpdate:modelValue": _cache[8] || (_cache[8] = ($event) => _ctx.form.username = $event),
                    placeholder: "root"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              }),
              createVNode(_component_aicode_field, { label: "认证方式" }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_segmented, {
                    modelValue: _ctx.form.auth,
                    "onUpdate:modelValue": _cache[9] || (_cache[9] = ($event) => _ctx.form.auth = $event),
                    options: $options.authOptions
                  }, null, 8, ["modelValue", "options"])
                ]),
                _: 1
                /* STABLE */
              }),
              _ctx.form.auth === "key" ? (openBlock(), createBlock(_component_aicode_field, {
                key: 0,
                label: "私钥路径"
              }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.privateKeyPath,
                    "onUpdate:modelValue": _cache[10] || (_cache[10] = ($event) => _ctx.form.privateKeyPath = $event),
                    placeholder: "data://id_ed25519"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              })) : createCommentVNode("v-if", true),
              _ctx.form.auth === "key" ? (openBlock(), createBlock(_component_aicode_field, {
                key: 1,
                label: "私钥口令（可选）"
              }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.passphrase,
                    "onUpdate:modelValue": _cache[11] || (_cache[11] = ($event) => _ctx.form.passphrase = $event),
                    type: "password"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              })) : createCommentVNode("v-if", true),
              _ctx.form.auth === "password" ? (openBlock(), createBlock(_component_aicode_field, {
                key: 2,
                label: "密码"
              }, {
                default: withCtx(() => [
                  createVNode(_component_aicode_input, {
                    modelValue: _ctx.form.password,
                    "onUpdate:modelValue": _cache[12] || (_cache[12] = ($event) => _ctx.form.password = $event),
                    type: "password"
                  }, null, 8, ["modelValue"])
                ]),
                _: 1
                /* STABLE */
              })) : createCommentVNode("v-if", true),
              createElementVNode("div", _hoisted_17, [
                _cache[30] || (_cache[30] = createElementVNode(
                  "span",
                  { class: "aicode-scope-label" },
                  "App 启动时连接",
                  -1
                  /* CACHED */
                )),
                createVNode(_component_aicode_switch, {
                  modelValue: _ctx.form.enabled,
                  "onUpdate:modelValue": _cache[13] || (_cache[13] = ($event) => _ctx.form.enabled = $event)
                }, null, 8, ["modelValue"])
              ])
            ]),
            _: 1
            /* STABLE */
          }),
          _ctx.form.auth === "key" ? (openBlock(), createElementBlock("p", _hoisted_18, "私钥建议使用 data:// 路径，文件放在插件数据目录，避免容器重建后丢失。")) : createCommentVNode("v-if", true),
          _ctx.error ? (openBlock(), createElementBlock(
            "p",
            _hoisted_19,
            toDisplayString(_ctx.error),
            1
            /* TEXT */
          )) : createCommentVNode("v-if", true)
        ],
        512
        /* NEED_PATCH */
      ), [
        [vShow, _ctx.view === "edit"]
      ])
    ])
  ]);
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
