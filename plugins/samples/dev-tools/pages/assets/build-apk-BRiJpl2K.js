import { _ as _export_sfc, r as resolveComponent, o as openBlock, c as createElementBlock, a as createElementVNode, b as createVNode, w as withCtx, t as toDisplayString, d as createTextVNode, e as createBlock, f as createCommentVNode, g as createApp } from "./_plugin-vue_export-helper-BblGwfBg.js";
var FLUTTER_TASKS = [
  { value: "flutter build apk --debug", label: "Flutter · Debug APK" },
  { value: "flutter build apk --release", label: "Flutter · Release APK" }
];
var ANDROID_TASKS = [
  { value: "./gradlew assembleDebug", label: "Android · Debug APK" },
  { value: "./gradlew assembleRelease", label: "Android · Release APK" }
];
const _sfc_main = {
  data: function() {
    return {
      detecting: true,
      running: false,
      projectType: "",
      task: "",
      output: ""
    };
  },
  computed: {
    ready: function() {
      return this.projectType === "flutter" || this.projectType === "android";
    },
    tasks: function() {
      if (this.projectType === "flutter") return FLUTTER_TASKS;
      if (this.projectType === "android") return ANDROID_TASKS;
      return [];
    },
    projectLabel: function() {
      if (this.detecting) return "正在检测项目类型";
      if (this.projectType === "flutter") return "Flutter 项目";
      if (this.projectType === "android") return "Android 项目";
      return "未识别的项目";
    },
    projectHint: function() {
      if (this.detecting) return "读取 pubspec.yaml 与 gradlew";
      if (this.projectType === "flutter") return "已找到 pubspec.yaml";
      if (this.projectType === "android") return "已找到 gradlew";
      return "未找到 pubspec.yaml 或 gradlew，无法构建";
    }
  },
  mounted: function() {
    this.detect();
  },
  methods: {
    detect: function() {
      var self = this;
      self.detecting = true;
      self.output = "";
      aicode.bash("test -f pubspec.yaml && echo flutter; test -f gradlew && echo android", { timeout: 15 }).then(function(res) {
        var out = res && res.output || "";
        if (out.indexOf("flutter") >= 0) {
          self.projectType = "flutter";
        } else if (out.indexOf("android") >= 0) {
          self.projectType = "android";
        } else {
          self.projectType = "unknown";
        }
        self.task = self.tasks.length ? self.tasks[0].value : "";
      }).catch(function(err) {
        self.projectType = "unknown";
        self.task = "";
        self.output = "检测失败：" + (err && err.message ? err.message : err);
      }).finally(function() {
        self.detecting = false;
      });
    },
    build: function() {
      var self = this;
      if (!self.ready || self.running) return;
      self.running = true;
      self.output = "$ " + self.task + "\n";
      aicode.bash(self.task, { timeout: 300 }).then(function(res) {
        var text = res && res.output || "（无输出）";
        if (res && res.exitCode != null) {
          text += "\n[exit " + res.exitCode + "]";
        }
        if (res && res.truncated) {
          text += "\n[输出已截断]";
        }
        self.output += text;
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
const _hoisted_6 = { class: "field" };
const _hoisted_7 = { class: "output" };
function _sfc_render(_ctx, _cache, $props, $setup, $data, $options) {
  const _component_aicode_button = resolveComponent("aicode-button");
  const _component_aicode_group = resolveComponent("aicode-group");
  const _component_aicode_select = resolveComponent("aicode-select");
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
                toDisplayString($options.projectHint),
                1
                /* TEXT */
              )
            ]),
            createVNode(_component_aicode_button, {
              variant: "secondary",
              disabled: _ctx.detecting || _ctx.running,
              onClick: $options.detect
            }, {
              default: withCtx(() => [
                createTextVNode(
                  toDisplayString(_ctx.detecting ? "检测中…" : "重新检测"),
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
      createVNode(_component_aicode_group, { title: "构建" }, {
        default: withCtx(() => [
          createElementVNode("div", _hoisted_6, [
            _cache[1] || (_cache[1] = createElementVNode(
              "div",
              { class: "field-title" },
              "构建任务",
              -1
              /* CACHED */
            )),
            createVNode(_component_aicode_select, {
              modelValue: _ctx.task,
              "onUpdate:modelValue": _cache[0] || (_cache[0] = ($event) => _ctx.task = $event),
              options: $options.tasks,
              disabled: !$options.ready || _ctx.running
            }, null, 8, ["modelValue", "options", "disabled"]),
            _cache[2] || (_cache[2] = createElementVNode(
              "div",
              { class: "hint" },
              "单次构建最长 5 分钟，超时会中断；更长的构建请在终端中执行。",
              -1
              /* CACHED */
            ))
          ])
        ]),
        _: 1
        /* STABLE */
      }),
      createVNode(_component_aicode_button, {
        variant: "primary",
        disabled: !$options.ready || _ctx.running,
        onClick: $options.build
      }, {
        default: withCtx(() => [
          createTextVNode(
            toDisplayString(_ctx.running ? "构建中…" : "开始构建"),
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
