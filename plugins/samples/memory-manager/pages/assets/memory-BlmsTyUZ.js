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
var GLOBAL_ROOT = "";
var PROJECT_ROOT = "";
const _sfc_main = {
  data: function() {
    return {
      view: "list",
      globalExpanded: true,
      projectExpanded: true,
      globalItems: [],
      projectItems: [],
      currentItem: null,
      editingItem: null,
      form: { name: "", description: "", content: "", scope: "global" },
      selectionMode: false,
      selected: [],
      saving: false,
      error: "",
      notice: "",
      conflict: "",
      contextConflict: false,
      editEpoch: null,
      contextKey: null,
      contextEpoch: 0,
      contextRequest: 0,
      contextError: "",
      loadErrors: { global: "", project: "" },
      watchErrors: { global: "", project: "" },
      pendingScopes: {},
      scopeVersions: { global: 0, project: 0 },
      refreshPromise: null,
      watchStops: [],
      eventsStop: null,
      backStop: null,
      disposed: false,
      batchConfirm: false,
      batchTimer: null
    };
  },
  computed: {
    pageTitle: function() {
      if (this.view === "edit") return this.editingItem ? "编辑记忆" : "新建记忆";
      if (this.view === "detail") return this.currentItem ? this.currentItem.name : "记忆管理";
      return "记忆管理";
    },
    allItems: function() {
      return this.globalItems.concat(this.projectItems);
    },
    allSelected: function() {
      return this.allItems.length > 0 && this.selected.length === this.allItems.length;
    },
    refreshError: function() {
      return [
        this.contextError,
        this.watchErrors.global,
        this.watchErrors.project,
        this.loadErrors.global,
        this.loadErrors.project
      ].filter(Boolean).join("；");
    },
    scopeOptions: function() {
      return [
        { value: "project", label: "当前项目" },
        { value: "global", label: "全局" }
      ];
    }
  },
  methods: {
    rootFor: function(scope) {
      return scope === "project" ? PROJECT_ROOT : GLOBAL_ROOT;
    },
    scopeLabel: function(scope) {
      return scope === "project" ? "项目" : "全局";
    },
    keyFor: function(item) {
      return item.scope + ":" + item.fileName;
    },
    sanitizeName: function(raw) {
      var chars = Array.from(String(raw));
      var cleaned = chars.map(function(ch) {
        return /[\p{L}\p{N}_-]/u.test(ch) ? ch : "-";
      }).join("").replace(/^-+|-+$/g, "");
      return cleaned || "memory";
    },
    yamlScalar: function(value) {
      return '"' + String(value == null ? "" : value).replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
    },
    formatMemory: function(name, description, content) {
      return "---\nname: " + this.yamlScalar(name) + "\ndescription: " + this.yamlScalar(description) + "\n---\n" + content;
    },
    unquote: function(value) {
      var text = String(value == null ? "" : value).trim();
      if (text.length >= 2 && text.charAt(0) === '"' && text.charAt(text.length - 1) === '"') {
        return text.slice(1, -1).replace(/\\\\/g, "\\").replace(/\\"/g, '"');
      }
      return text;
    },
    parseMemory: function(text, fileName, scope) {
      var name = fileName.replace(/\.md$/i, "");
      var description = "";
      var body = text || "";
      var match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(body);
      if (match) {
        var front = match[1];
        var nameMatch = /^name:\s*(.+)$/m.exec(front);
        var descMatch = /^description:\s*(.+)$/m.exec(front);
        if (nameMatch) name = this.unquote(nameMatch[1]);
        if (descMatch) description = this.unquote(descMatch[1]);
        body = body.slice(match[0].length);
      }
      return {
        name,
        description,
        content: body.trim(),
        fileName,
        scope,
        rawContent: text,
        contextEpoch: this.contextEpoch
      };
    },
    isCurrent: function(epoch) {
      return !this.disposed && this.contextKey !== null && this.contextEpoch === epoch;
    },
    refreshContext: async function() {
      var request = ++this.contextRequest;
      try {
        var context = await aicode.context();
        if (this.disposed || request !== this.contextRequest) return false;
        if (typeof context.contextKey !== "string") throw new Error("无法获取页面上下文");
        GLOBAL_ROOT = context.home + "/.aicode/memory";
        PROJECT_ROOT = context.workspace + "/.aicode/memory";
        this.contextError = "";
        if (this.setContext(context.contextKey)) this.loadAll();
        return true;
      } catch (e) {
        if (!this.disposed && request === this.contextRequest) {
          this.contextError = "读取页面上下文失败：" + (e && e.message || e);
        }
        return false;
      }
    },
    checkContext: async function(epoch) {
      if (!this.isCurrent(epoch)) return false;
      return await this.refreshContext() && this.isCurrent(epoch);
    },
    setContext: function(key) {
      if (key === this.contextKey) return false;
      var changed = this.contextKey !== null;
      this.contextKey = key;
      this.contextEpoch++;
      this.pendingScopes = {};
      this.refreshPromise = null;
      this.loadErrors = { global: "", project: "" };
      this.stopWatching();
      if (changed) {
        this.globalItems = [];
        this.projectItems = [];
        this.currentItem = null;
        this.selected = [];
        this.selectionMode = false;
        this.batchConfirm = false;
        clearTimeout(this.batchTimer);
        this.batchTimer = null;
        if (this.view === "edit") {
          this.contextConflict = true;
          this.conflict = "工作区或执行环境已切换，草稿已保留，不能保存到新上下文。请复制所需内容后放弃编辑。";
        } else {
          this.view = "list";
          this.notice = "工作区或执行环境已切换。";
        }
      }
      this.watchScopes();
      return true;
    },
    handleLifecycle: function(event) {
      if (this.disposed || !event || event.name !== "ui.lifecycle" || !event.payload) return;
      var payload = event.payload;
      if (typeof payload.contextKey !== "string") return;
      this.contextRequest++;
      this.contextError = "";
      this.setContext(payload.contextKey);
      if (payload.visible) this.loadAll();
    },
    stopWatching: function() {
      this.watchStops.forEach(function(stop) {
        stop().catch(function() {
        });
      });
      this.watchStops = [];
    },
    watchScopes: function() {
      var self = this;
      var epoch = this.contextEpoch;
      this.watchErrors = { global: "", project: "" };
      ["global", "project"].forEach(async function(scope) {
        try {
          var stop = await aicode.fs.watch(self.rootFor(scope), { recursive: false }, function(payload) {
            if (!self.isCurrent(epoch)) return;
            if (payload && payload.contextKey != null && payload.contextKey !== self.contextKey) return;
            if (payload && payload.error) {
              self.watchErrors[scope] = self.scopeLabel(scope) + "记忆文件订阅失败：" + (payload.error.message || payload.error.code);
              return;
            }
            self.watchErrors[scope] = "";
            self.requestRefresh([scope]);
          });
          if (self.isCurrent(epoch)) self.watchStops.push(stop);
          else await stop().catch(function() {
          });
        } catch (e) {
          if (self.isCurrent(epoch)) {
            self.watchErrors[scope] = self.scopeLabel(scope) + "记忆文件订阅失败：" + (e && e.message || e);
          }
        }
      });
    },
    readMemory: async function(scope, fileName) {
      try {
        var read = await aicode.fs.read(this.rootFor(scope) + "/" + fileName, { contextKey: this.contextKey });
        return this.parseMemory(read.content, fileName, scope);
      } catch (e) {
        if (e && e.code === "NOT_FOUND") return null;
        throw e;
      }
    },
    listScope: async function(scope, epoch) {
      var result;
      try {
        result = await aicode.fs.list(this.rootFor(scope), { contextKey: this.contextKey });
      } catch (e) {
        if (e && e.code === "NOT_FOUND") return [];
        throw e;
      }
      var files = (result.entries || []).filter(function(entry) {
        return !entry.isDir && /\.md$/i.test(entry.name);
      });
      var loaded = [];
      for (var i = 0; i < files.length; i++) {
        if (!this.isCurrent(epoch)) return [];
        var item = await this.readMemory(scope, files[i].name);
        if (item) loaded.push(item);
      }
      loaded.sort(function(a, b) {
        return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
      });
      return loaded;
    },
    applyScope: function(scope, items) {
      if (scope === "project") this.projectItems = items;
      else this.globalItems = items;
      var keys = {};
      this.allItems.forEach(function(item) {
        keys[item.scope + ":" + item.fileName] = true;
      });
      this.selected = this.selected.filter(function(key) {
        return !!keys[key];
      });
      if (this.currentItem && this.currentItem.scope === scope) {
        var fileName = this.currentItem.fileName;
        this.currentItem = items.find(function(item) {
          return item.fileName === fileName;
        }) || null;
        if (!this.currentItem && this.view === "detail") {
          this.view = "list";
          this.notice = "该记忆已被删除，已返回列表。";
        }
      }
      if (this.view === "edit" && this.editingItem && this.editingItem.scope === scope && !this.contextConflict) {
        var editingName = this.editingItem.fileName;
        this.checkBaseline(items.find(function(item) {
          return item.fileName === editingName;
        }));
      }
    },
    checkBaseline: function(item) {
      if (!item) {
        this.conflict = "该记忆已被外部删除，草稿已保留，无法直接保存。请重新载入或放弃编辑。";
        return false;
      }
      if (item.rawContent !== this.editingItem.rawContent) {
        this.conflict = "该记忆的摘要或正文已被外部修改，草稿已保留，无法直接保存。请重新载入或放弃编辑。";
        return false;
      }
      return true;
    },
    requestRefresh: function(scopes) {
      if (this.disposed || this.contextKey === null) return Promise.resolve();
      var self = this;
      scopes.forEach(function(scope) {
        self.pendingScopes[scope] = true;
        self.scopeVersions[scope]++;
      });
      if (this.refreshPromise) return this.refreshPromise;
      var epoch = this.contextEpoch;
      var task = Promise.resolve().then(async function() {
        while (self.isCurrent(epoch) && Object.keys(self.pendingScopes).length) {
          var pending = Object.keys(self.pendingScopes);
          self.pendingScopes = {};
          await Promise.all(pending.map(async function(scope) {
            var version = self.scopeVersions[scope];
            try {
              var items = await self.listScope(scope, epoch);
              if (!self.isCurrent(epoch) || version !== self.scopeVersions[scope]) return;
              self.loadErrors[scope] = "";
              self.applyScope(scope, items);
            } catch (e) {
              if (self.isCurrent(epoch) && version === self.scopeVersions[scope]) {
                self.loadErrors[scope] = self.scopeLabel(scope) + "记忆刷新失败：" + (e && e.message || e);
              }
            }
          }));
        }
        if (self.refreshPromise === task) self.refreshPromise = null;
      });
      this.refreshPromise = task;
      return task;
    },
    loadAll: function() {
      return this.requestRefresh(["global", "project"]);
    },
    isSelected: function(item) {
      return this.selected.indexOf(this.keyFor(item)) >= 0;
    },
    toggleSelected: function(item) {
      var key = this.keyFor(item);
      var index = this.selected.indexOf(key);
      if (index >= 0) {
        this.selected.splice(index, 1);
      } else {
        this.selected.push(key);
      }
    },
    toggleSelectionMode: function() {
      this.selectionMode = !this.selectionMode;
      this.selected = [];
      this.batchConfirm = false;
      if (this.batchTimer) {
        clearTimeout(this.batchTimer);
        this.batchTimer = null;
      }
    },
    toggleSelectAll: function() {
      if (this.allSelected) {
        this.selected = [];
      } else {
        this.selected = this.allItems.map(function(item) {
          return item.scope + ":" + item.fileName;
        });
      }
    },
    handleRowClick: function(item) {
      if (this.selectionMode) {
        this.toggleSelected(item);
        return;
      }
      this.openDetail(item);
    },
    openDetail: function(item) {
      if (!this.isCurrent(item.contextEpoch)) return;
      this.notice = "";
      this.currentItem = item;
      this.view = "detail";
    },
    openEdit: function(item) {
      if (this.contextKey === null || this.disposed || item && !this.isCurrent(item.contextEpoch)) return;
      this.editingItem = item;
      this.editEpoch = this.contextEpoch;
      this.contextConflict = false;
      this.conflict = "";
      this.notice = "";
      this.form = {
        name: item ? item.name : "",
        description: item ? item.description : "",
        content: item ? item.content : "",
        scope: item ? item.scope : "global"
      };
      this.error = "";
      this.view = "edit";
    },
    editCurrent: function() {
      if (this.currentItem) this.openEdit(this.currentItem);
    },
    discardEdit: function() {
      if (this.saving) return;
      var key = this.editingItem && this.keyFor(this.editingItem);
      var self = this;
      var item = this.isCurrent(this.editEpoch) && !this.contextConflict && this.allItems.find(function(item2) {
        return self.keyFor(item2) === key;
      });
      this.editingItem = null;
      this.editEpoch = null;
      this.conflict = "";
      this.contextConflict = false;
      this.error = "";
      this.form = { name: "", description: "", content: "", scope: "global" };
      this.currentItem = item || null;
      this.view = item ? "detail" : "list";
    },
    reloadEdit: async function() {
      if (this.saving || this.contextConflict || !this.editingItem) return;
      var epoch = this.editEpoch;
      var item = this.editingItem;
      this.saving = true;
      this.error = "";
      try {
        if (!await this.checkContext(epoch)) return;
        var latest = await this.readMemory(item.scope, item.fileName);
        if (!this.isCurrent(epoch)) return;
        if (latest) {
          this.openEdit(latest);
        } else {
          this.saving = false;
          this.editingItem = null;
          this.discardEdit();
          this.notice = "该记忆已被删除，已返回列表。";
        }
        this.requestRefresh([item.scope]);
      } catch (e) {
        if (this.isCurrent(epoch)) this.error = "重新载入失败：" + (e && e.message || e);
      } finally {
        this.saving = false;
      }
    },
    handleBack: function() {
      if (this.saving) return true;
      if (this.view === "list") return false;
      if (this.view === "edit") {
        this.discardEdit();
        return true;
      }
      if (this.view === "detail") {
        this.view = "list";
        return true;
      }
      return false;
    },
    handleBackButton: function() {
      if (!this.handleBack()) {
        aicode.ui.close().catch(function() {
        });
      }
    },
    save: async function() {
      if (this.saving || this.view !== "edit" || this.conflict) return;
      this.error = "";
      var name = this.form.name.trim();
      if (!name) {
        this.error = "请填写记忆名称";
        return;
      }
      var epoch = this.editEpoch;
      var contextKey = this.contextKey;
      var targetScope = this.editingItem ? this.editingItem.scope : this.form.scope;
      var fileName = this.editingItem ? this.editingItem.fileName : this.sanitizeName(name) + ".md";
      var text = this.formatMemory(name, this.form.description.trim(), this.form.content);
      this.saving = true;
      try {
        if (!await this.checkContext(epoch)) return;
        if (this.editingItem) {
          var latest = await this.readMemory(targetScope, fileName);
          if (!this.isCurrent(epoch) || !this.checkBaseline(latest)) return;
        }
        if (!this.isCurrent(epoch) || this.conflict) return;
        await aicode.fs.write(this.rootFor(targetScope) + "/" + fileName, text, { contextKey });
        if (!this.isCurrent(epoch)) return;
        this.saving = false;
        this.editingItem = null;
        this.discardEdit();
        this.currentItem = null;
        this.view = "list";
        this.requestRefresh([targetScope]);
        aicode.toast("已保存").catch(function() {
        });
      } catch (e) {
        if (e && e.code === "STALE_CONTEXT") await this.refreshContext();
        if (this.isCurrent(epoch)) this.error = e && e.message || String(e);
      } finally {
        this.saving = false;
      }
    },
    deleteItem: async function(item) {
      var epoch = item.contextEpoch;
      var contextKey = this.contextKey;
      try {
        if (!await this.checkContext(epoch)) return;
        await aicode.fs.delete(this.rootFor(item.scope) + "/" + item.fileName, { contextKey });
        if (!this.isCurrent(epoch)) return;
        await aicode.toast("已删除");
        if (this.isCurrent(epoch)) await this.requestRefresh([item.scope]);
      } catch (e) {
        if (e && e.code === "STALE_CONTEXT") await this.refreshContext();
        if (this.isCurrent(epoch)) await aicode.toast("删除失败：" + (e && e.message || e));
      }
    },
    batchDelete: async function() {
      if (!this.selected.length) return;
      var self = this;
      var epoch = this.contextEpoch;
      var contextKey = this.contextKey;
      if (!this.batchConfirm) {
        this.batchConfirm = true;
        this.batchTimer = setTimeout(function() {
          self.batchConfirm = false;
          self.batchTimer = null;
        }, 3e3);
        return;
      }
      if (this.batchTimer) {
        clearTimeout(this.batchTimer);
        this.batchTimer = null;
      }
      this.batchConfirm = false;
      var targets = this.allItems.filter(function(item) {
        return self.selected.indexOf(item.scope + ":" + item.fileName) >= 0;
      });
      if (!await this.checkContext(epoch)) return;
      for (var i = 0; i < targets.length; i++) {
        if (!this.isCurrent(epoch)) return;
        try {
          await aicode.fs.delete(this.rootFor(targets[i].scope) + "/" + targets[i].fileName, { contextKey });
        } catch (e) {
          if (e && e.code === "STALE_CONTEXT") {
            await this.refreshContext();
            return;
          }
          if (this.isCurrent(epoch)) await aicode.toast("删除失败：" + (e && e.message || e));
        }
      }
      if (!this.isCurrent(epoch)) return;
      await aicode.toast("已删除");
      if (!this.isCurrent(epoch)) return;
      this.selected = [];
      this.selectionMode = false;
      await this.loadAll();
    }
  },
  mounted: function() {
    var self = this;
    if (aicode.ui && aicode.ui.onBack) {
      aicode.ui.onBack(this.handleBack).then(function(stop) {
        if (self.disposed) stop();
        else self.backStop = stop;
      });
    }
    this.eventsStop = aicode.events.on(this.handleLifecycle);
    this.refreshContext();
  },
  beforeUnmount: function() {
    this.disposed = true;
    this.contextEpoch++;
    this.contextRequest++;
    this.pendingScopes = {};
    this.stopWatching();
    if (this.eventsStop) this.eventsStop();
    if (this.backStop) this.backStop();
    clearTimeout(this.batchTimer);
  }
};
const _hoisted_1 = { class: "aicode-page-header" };
const _hoisted_2 = { class: "aicode-page-title" };
const _hoisted_3 = ["disabled"];
const _hoisted_4 = { class: "page-content" };
const _hoisted_5 = {
  key: 0,
  class: "aicode-text",
  role: "status"
};
const _hoisted_6 = {
  key: 1,
  class: "aicode-error",
  role: "alert"
};
const _hoisted_7 = { class: "view" };
const _hoisted_8 = {
  key: 0,
  class: "aicode-divider"
};
const _hoisted_9 = {
  key: 0,
  class: "aicode-divider"
};
const _hoisted_10 = { class: "view" };
const _hoisted_11 = { class: "aicode-info-row" };
const _hoisted_12 = { class: "aicode-info-row" };
const _hoisted_13 = { class: "aicode-text" };
const _hoisted_14 = { class: "view" };
const _hoisted_15 = {
  class: "aicode-error",
  role: "alert"
};
const _hoisted_16 = { class: "aicode-scope-row" };
const _hoisted_17 = { class: "aicode-outlined-field aicode-outlined-field-body" };
const _hoisted_18 = {
  key: 1,
  class: "aicode-error"
};
const _hoisted_19 = {
  key: 0,
  class: "aicode-selection-bar"
};
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_icon_button = resolveComponent("aicode-icon-button");
  const _component_aicode_row = resolveComponent("aicode-row");
  const _component_aicode_swipe_row = resolveComponent("aicode-swipe-row");
  const _component_aicode_empty = resolveComponent("aicode-empty");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_markdown = resolveComponent("aicode-markdown");
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_input = resolveComponent("aicode-input");
  const _component_aicode_field = resolveComponent("aicode-field");
  const _component_aicode_segmented = resolveComponent("aicode-segmented");
  const _component_aicode_textarea = resolveComponent("aicode-textarea");
  return openBlock(), createElementBlock(
    "div",
    {
      id: "app",
      class: normalizeClass(["aicode-page", { "selection-active": _ctx.selectionMode }])
    },
    [
      createElementVNode("header", _hoisted_1, [
        createVNode(_component_aicode_icon_button, {
          label: "返回",
          onClick: $options.handleBackButton
        }, {
          default: withCtx(() => [..._cache[8] || (_cache[8] = [
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
          _hoisted_2,
          toDisplayString($options.pageTitle),
          1
          /* TEXT */
        ),
        _ctx.view === "list" ? (openBlock(), createBlock(_component_aicode_icon_button, {
          key: 0,
          label: "多选",
          class: normalizeClass({ active: _ctx.selectionMode }),
          onClick: $options.toggleSelectionMode
        }, {
          default: withCtx(() => [..._cache[9] || (_cache[9] = [
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
                createElementVNode("polyline", { points: "9 11 12 14 22 4" }),
                createElementVNode("path", { d: "M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" })
              ],
              -1
              /* CACHED */
            )
          ])]),
          _: 1
          /* STABLE */
        }, 8, ["class", "onClick"])) : createCommentVNode("v-if", true),
        _ctx.view === "list" ? (openBlock(), createBlock(_component_aicode_icon_button, {
          key: 1,
          label: "新建",
          onClick: _cache[0] || (_cache[0] = ($event) => $options.openEdit(null))
        }, {
          default: withCtx(() => [..._cache[10] || (_cache[10] = [
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
        _ctx.view === "detail" ? (openBlock(), createBlock(_component_aicode_icon_button, {
          key: 2,
          label: "编辑",
          onClick: $options.editCurrent
        }, {
          default: withCtx(() => [..._cache[11] || (_cache[11] = [
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
                createElementVNode("path", { d: "M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" })
              ],
              -1
              /* CACHED */
            )
          ])]),
          _: 1
          /* STABLE */
        }, 8, ["onClick"])) : createCommentVNode("v-if", true),
        _ctx.view === "edit" ? (openBlock(), createElementBlock("button", {
          key: 3,
          class: "aicode-text-button",
          disabled: _ctx.saving || !!_ctx.conflict || _ctx.contextKey === null,
          onClick: _cache[1] || (_cache[1] = (...args) => $options.save && $options.save(...args))
        }, "保存", 8, _hoisted_3)) : createCommentVNode("v-if", true)
      ]),
      createElementVNode("div", _hoisted_4, [
        _ctx.notice ? (openBlock(), createElementBlock(
          "p",
          _hoisted_5,
          toDisplayString(_ctx.notice),
          1
          /* TEXT */
        )) : createCommentVNode("v-if", true),
        $options.refreshError ? (openBlock(), createElementBlock(
          "p",
          _hoisted_6,
          toDisplayString($options.refreshError),
          1
          /* TEXT */
        )) : createCommentVNode("v-if", true),
        withDirectives(createElementVNode(
          "section",
          _hoisted_7,
          [
            createVNode(_component_aicode_group, {
              title: "全局记忆",
              collapsible: "",
              expanded: _ctx.globalExpanded,
              "onUpdate:expanded": _cache[2] || (_cache[2] = ($event) => _ctx.globalExpanded = $event)
            }, {
              default: withCtx(() => [
                _ctx.globalItems.length ? (openBlock(true), createElementBlock(
                  Fragment,
                  { key: 0 },
                  renderList(_ctx.globalItems, (item, index) => {
                    return openBlock(), createElementBlock(
                      Fragment,
                      {
                        key: item.fileName
                      },
                      [
                        index > 0 ? (openBlock(), createElementBlock("div", _hoisted_8)) : createCommentVNode("v-if", true),
                        createVNode(_component_aicode_swipe_row, {
                          disabled: _ctx.selectionMode,
                          onDelete: ($event) => $options.deleteItem(item)
                        }, {
                          default: withCtx(() => [
                            createVNode(_component_aicode_row, {
                              title: item.name,
                              subtitle: item.description || "（无描述）",
                              badge: $options.scopeLabel(item.scope),
                              selectable: _ctx.selectionMode,
                              selected: $options.isSelected(item),
                              onClick: ($event) => $options.handleRowClick(item)
                            }, null, 8, ["title", "subtitle", "badge", "selectable", "selected", "onClick"])
                          ]),
                          _: 2
                          /* DYNAMIC */
                        }, 1032, ["disabled", "onDelete"])
                      ],
                      64
                      /* STABLE_FRAGMENT */
                    );
                  }),
                  128
                  /* KEYED_FRAGMENT */
                )) : (openBlock(), createBlock(_component_aicode_empty, {
                  key: 1,
                  text: "暂无全局记忆"
                }))
              ]),
              _: 1
              /* STABLE */
            }, 8, ["expanded"]),
            createVNode(_component_aicode_group, {
              title: "项目记忆",
              collapsible: "",
              expanded: _ctx.projectExpanded,
              "onUpdate:expanded": _cache[3] || (_cache[3] = ($event) => _ctx.projectExpanded = $event)
            }, {
              default: withCtx(() => [
                _ctx.projectItems.length ? (openBlock(true), createElementBlock(
                  Fragment,
                  { key: 0 },
                  renderList(_ctx.projectItems, (item, index) => {
                    return openBlock(), createElementBlock(
                      Fragment,
                      {
                        key: item.fileName
                      },
                      [
                        index > 0 ? (openBlock(), createElementBlock("div", _hoisted_9)) : createCommentVNode("v-if", true),
                        createVNode(_component_aicode_swipe_row, {
                          disabled: _ctx.selectionMode,
                          onDelete: ($event) => $options.deleteItem(item)
                        }, {
                          default: withCtx(() => [
                            createVNode(_component_aicode_row, {
                              title: item.name,
                              subtitle: item.description || "（无描述）",
                              badge: $options.scopeLabel(item.scope),
                              selectable: _ctx.selectionMode,
                              selected: $options.isSelected(item),
                              onClick: ($event) => $options.handleRowClick(item)
                            }, null, 8, ["title", "subtitle", "badge", "selectable", "selected", "onClick"])
                          ]),
                          _: 2
                          /* DYNAMIC */
                        }, 1032, ["disabled", "onDelete"])
                      ],
                      64
                      /* STABLE_FRAGMENT */
                    );
                  }),
                  128
                  /* KEYED_FRAGMENT */
                )) : (openBlock(), createBlock(_component_aicode_empty, {
                  key: 1,
                  text: "当前项目暂无项目记忆"
                }))
              ]),
              _: 1
              /* STABLE */
            }, 8, ["expanded"])
          ],
          512
          /* NEED_PATCH */
        ), [
          [vShow, _ctx.view === "list"]
        ]),
        withDirectives(createElementVNode(
          "section",
          _hoisted_10,
          [
            createVNode(_component_aicode_group, {
              title: "信息",
              collapsible: false
            }, {
              default: withCtx(() => [
                createElementVNode("div", _hoisted_11, [
                  _cache[12] || (_cache[12] = createElementVNode(
                    "span",
                    null,
                    "范围",
                    -1
                    /* CACHED */
                  )),
                  createElementVNode(
                    "span",
                    null,
                    toDisplayString(_ctx.currentItem ? $options.scopeLabel(_ctx.currentItem.scope) : ""),
                    1
                    /* TEXT */
                  )
                ]),
                _cache[14] || (_cache[14] = createElementVNode(
                  "div",
                  { class: "aicode-divider aicode-divider-flat" },
                  null,
                  -1
                  /* CACHED */
                )),
                createElementVNode("div", _hoisted_12, [
                  _cache[13] || (_cache[13] = createElementVNode(
                    "span",
                    null,
                    "文件",
                    -1
                    /* CACHED */
                  )),
                  createElementVNode(
                    "span",
                    null,
                    toDisplayString(_ctx.currentItem ? _ctx.currentItem.fileName : ""),
                    1
                    /* TEXT */
                  )
                ])
              ]),
              _: 1
              /* STABLE */
            }),
            createVNode(_component_aicode_group, {
              title: "摘要",
              collapsible: false
            }, {
              default: withCtx(() => [
                createElementVNode(
                  "p",
                  _hoisted_13,
                  toDisplayString(_ctx.currentItem ? _ctx.currentItem.description || "（无描述）" : ""),
                  1
                  /* TEXT */
                )
              ]),
              _: 1
              /* STABLE */
            }),
            createVNode(_component_aicode_group, {
              title: "指令正文",
              collapsible: false
            }, {
              default: withCtx(() => [
                createVNode(_component_aicode_markdown, {
                  text: _ctx.currentItem ? _ctx.currentItem.content : ""
                }, null, 8, ["text"])
              ]),
              _: 1
              /* STABLE */
            })
          ],
          512
          /* NEED_PATCH */
        ), [
          [vShow, _ctx.view === "detail"]
        ]),
        withDirectives(createElementVNode(
          "section",
          _hoisted_14,
          [
            _ctx.conflict ? (openBlock(), createBlock(_component_aicode_group, {
              key: 0,
              title: "编辑冲突",
              collapsible: false
            }, {
              default: withCtx(() => [
                createElementVNode(
                  "p",
                  _hoisted_15,
                  toDisplayString(_ctx.conflict),
                  1
                  /* TEXT */
                ),
                !_ctx.contextConflict ? (openBlock(), createBlock(_component_aicode_button, {
                  key: 0,
                  compact: "",
                  disabled: _ctx.saving,
                  onClick: $options.reloadEdit
                }, {
                  default: withCtx(() => [..._cache[15] || (_cache[15] = [
                    createTextVNode(
                      "重新载入",
                      -1
                      /* CACHED */
                    )
                  ])]),
                  _: 1
                  /* STABLE */
                }, 8, ["disabled", "onClick"])) : createCommentVNode("v-if", true),
                createVNode(_component_aicode_button, {
                  compact: "",
                  disabled: _ctx.saving,
                  onClick: $options.discardEdit
                }, {
                  default: withCtx(() => [..._cache[16] || (_cache[16] = [
                    createTextVNode(
                      "放弃编辑",
                      -1
                      /* CACHED */
                    )
                  ])]),
                  _: 1
                  /* STABLE */
                }, 8, ["disabled", "onClick"])
              ]),
              _: 1
              /* STABLE */
            })) : createCommentVNode("v-if", true),
            createVNode(_component_aicode_group, {
              title: "基本信息",
              collapsible: false
            }, {
              default: withCtx(() => [
                createVNode(_component_aicode_field, { label: "名称" }, {
                  default: withCtx(() => [
                    createVNode(_component_aicode_input, {
                      modelValue: _ctx.form.name,
                      "onUpdate:modelValue": _cache[4] || (_cache[4] = ($event) => _ctx.form.name = $event),
                      disabled: !!_ctx.editingItem || _ctx.saving,
                      placeholder: "例如 project-conventions"
                    }, null, 8, ["modelValue", "disabled"])
                  ]),
                  _: 1
                  /* STABLE */
                }),
                createVNode(_component_aicode_field, { label: "描述" }, {
                  default: withCtx(() => [
                    createVNode(_component_aicode_input, {
                      modelValue: _ctx.form.description,
                      "onUpdate:modelValue": _cache[5] || (_cache[5] = ($event) => _ctx.form.description = $event),
                      disabled: _ctx.saving,
                      placeholder: "一句话摘要，会注入系统提示词"
                    }, null, 8, ["modelValue", "disabled"])
                  ]),
                  _: 1
                  /* STABLE */
                }),
                createElementVNode("div", _hoisted_16, [
                  _cache[17] || (_cache[17] = createElementVNode(
                    "span",
                    { class: "aicode-scope-label" },
                    "作用域",
                    -1
                    /* CACHED */
                  )),
                  createVNode(_component_aicode_segmented, {
                    modelValue: _ctx.form.scope,
                    "onUpdate:modelValue": _cache[6] || (_cache[6] = ($event) => _ctx.form.scope = $event),
                    options: $options.scopeOptions,
                    disabled: !!_ctx.editingItem || _ctx.saving
                  }, null, 8, ["modelValue", "options", "disabled"])
                ])
              ]),
              _: 1
              /* STABLE */
            }),
            createVNode(_component_aicode_group, {
              title: "指令正文",
              collapsible: false
            }, {
              default: withCtx(() => [
                createElementVNode("div", _hoisted_17, [
                  createVNode(_component_aicode_textarea, {
                    modelValue: _ctx.form.content,
                    "onUpdate:modelValue": _cache[7] || (_cache[7] = ($event) => _ctx.form.content = $event),
                    disabled: _ctx.saving,
                    placeholder: "记忆的具体内容"
                  }, null, 8, ["modelValue", "disabled"])
                ])
              ]),
              _: 1
              /* STABLE */
            }),
            _ctx.error ? (openBlock(), createElementBlock(
              "p",
              _hoisted_18,
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
      ]),
      _ctx.selectionMode ? (openBlock(), createElementBlock("div", _hoisted_19, [
        createElementVNode(
          "span",
          null,
          "已选 " + toDisplayString(_ctx.selected.length) + " 项",
          1
          /* TEXT */
        ),
        createVNode(_component_aicode_button, {
          compact: "",
          onClick: $options.toggleSelectAll
        }, {
          default: withCtx(() => [
            createTextVNode(
              toDisplayString($options.allSelected ? "取消全选" : "全选"),
              1
              /* TEXT */
            )
          ]),
          _: 1
          /* STABLE */
        }, 8, ["onClick"]),
        createVNode(_component_aicode_button, {
          compact: "",
          variant: "danger",
          disabled: !_ctx.selected.length,
          onClick: $options.batchDelete
        }, {
          default: withCtx(() => [
            createTextVNode(
              toDisplayString(_ctx.batchConfirm ? "确认删除" : "删除"),
              1
              /* TEXT */
            )
          ]),
          _: 1
          /* STABLE */
        }, 8, ["disabled", "onClick"])
      ])) : createCommentVNode("v-if", true)
    ],
    2
    /* CLASS */
  );
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
