import { _ as _export_sfc, r as resolveComponent, o as openBlock, c as createElementBlock, a as createElementVNode, b as createVNode, w as withCtx, t as toDisplayString, d as createTextVNode, e as createBlock, f as createCommentVNode, g as createApp } from "./_plugin-vue_export-helper-BblGwfBg.js";
var SIZES_COMMAND = 'for d in build .dart_tool .gradle android/.gradle android/build; do if [ -e "$d" ]; then du -sh "$d"; fi; done';
const _sfc_main = {
  data: function() {
    return {
      detecting: true,
      running: false,
      projectType: "",
      sizes: "",
      output: ""
    };
  },
  computed: {
    cleanCommand: function() {
      if (this.projectType === "flutter") return "flutter clean";
      if (this.projectType === "android") return "./gradlew clean";
      return "";
    },
    projectLabel: function() {
      if (this.detecting) return "正在检测项目类型";
      if (this.projectType === "flutter") return "Flutter 项目";
      if (this.projectType === "android") return "Android 项目";
      return "未识别的项目";
    },
    projectHint: function() {
      if (this.projectType === "unknown") return "未找到 pubspec.yaml 或 gradlew";
      return "读取 pubspec.yaml 与 gradlew";
    }
  },
  mounted: function() {
    this.refresh();
  },
  methods: {
    refresh: function() {
      var self = this;
      self.detecting = true;
      self.output = "";
      Promise.all([
        aicode.bash("test -f pubspec.yaml && echo flutter; test -f gradlew && echo android", { timeout: 15 }),
        aicode.bash(SIZES_COMMAND, { timeout: 30 })
      ]).then(function(results) {
        var kind = results[0] && results[0].output || "";
        if (kind.indexOf("flutter") >= 0) {
          self.projectType = "flutter";
        } else if (kind.indexOf("android") >= 0) {
          self.projectType = "android";
        } else {
          self.projectType = "unknown";
        }
        self.sizes = results[1] && results[1].output || "";
      }).catch(function(err) {
        self.projectType = "unknown";
        self.sizes = "";
        self.output = "检测失败：" + (err && err.message ? err.message : err);
      }).finally(function() {
        self.detecting = false;
      });
    },
    clean: function() {
      var self = this;
      if (!self.cleanCommand || self.running) return;
      self.running = true;
      self.output = "$ " + self.cleanCommand + "\n";
      aicode.bash(self.cleanCommand, { timeout: 300 }).then(function(res) {
        var text = res && res.output || "（无输出）";
        if (res && res.exitCode != null) {
          text += "\n[exit " + res.exitCode + "]";
        }
        if (res && res.truncated) {
          text += "\n[输出已截断]";
        }
        self.output += text;
        return aicode.bash(SIZES_COMMAND, { timeout: 30 });
      }).then(function(res) {
        self.sizes = res && res.output || "（缓存目录已清理）";
      }).catch(function(err) {
        self.output += "执行失败：" + (err && err.message ? err.message : err);
      }).finally(function() {
        self.running = false;
      });
    }
  }
};
const _hoisted_1 = {
  id: "app",
  class: "aicode-page"
};
const _hoisted_2 = { class: "page-content" };
const _hoisted_3 = { class: "row" };
const _hoisted_4 = { class: "label" };
const _hoisted_5 = { class: "hint" };
const _hoisted_6 = { class: "sizes" };
const _hoisted_7 = { class: "output" };
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_group = resolveComponent("aicode-group");
  return openBlock(), createElementBlock("div", _hoisted_1, [
    createElementVNode("div", _hoisted_2, [
      createVNode(_component_aicode_group, { title: "项目" }, {
        default: withCtx(() => [
          createElementVNode("div", _hoisted_3, [
            createElementVNode("div", null, [
              createElementVNode(
                "div",
                _hoisted_4,
                toDisplayString($options.projectLabel),
                1
                /* TEXT */
              ),
              createElementVNode(
                "div",
                _hoisted_5,
                toDisplayString($options.cleanCommand ? "清理命令：" + $options.cleanCommand : $options.projectHint),
                1
                /* TEXT */
              )
            ]),
            createVNode(_component_aicode_button, {
              variant: "secondary",
              disabled: _ctx.detecting || _ctx.running,
              onClick: $options.refresh
            }, {
              default: withCtx(() => [
                createTextVNode(
                  toDisplayString(_ctx.detecting ? "检测中…" : "刷新"),
                  1
                  /* TEXT */
                )
              ]),
              _: 1
              /* STABLE */
            }, 8, ["disabled", "onClick"])
          ])
        ]),
        _: 1
        /* STABLE */
      }),
      createVNode(_component_aicode_group, { title: "缓存占用" }, {
        default: withCtx(() => [
          createElementVNode(
            "pre",
            _hoisted_6,
            toDisplayString(_ctx.sizes || "正在统计…"),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      }),
      createVNode(_component_aicode_button, {
        variant: "primary",
        disabled: !$options.cleanCommand || _ctx.running,
        onClick: $options.clean
      }, {
        default: withCtx(() => [
          createTextVNode(
            toDisplayString(_ctx.running ? "清理中…" : "清理缓存"),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      }, 8, ["disabled", "onClick"]),
      _ctx.output ? (openBlock(), createBlock(_component_aicode_group, {
        key: 0,
        title: "输出"
      }, {
        default: withCtx(() => [
          createElementVNode(
            "pre",
            _hoisted_7,
            toDisplayString(_ctx.output),
            1
            /* TEXT */
          )
        ]),
        _: 1
        /* STABLE */
      })) : createCommentVNode("v-if", true)
    ])
  ]);
}
const App = /* @__PURE__ */ _export_sfc(_sfc_main, [["render", _sfc_render]]);
const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount("#app");
