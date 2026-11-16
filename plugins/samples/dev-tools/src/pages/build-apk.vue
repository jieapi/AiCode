<template>
  <div id="app" class="aicode-page" v-cloak>
    <div class="page-content">
      <aicode-group title="项目">
        <div class="row">
          <div>
            <div class="label">{{ projectLabel }}</div>
            <div class="hint">{{ projectHint }}</div>
          </div>
          <aicode-button variant="secondary" :disabled="detecting || running" @click="detect">
            {{ detecting ? '检测中…' : '重新检测' }}
          </aicode-button>
        </div>
      </aicode-group>

      <aicode-group title="构建">
        <div class="field">
          <div class="field-title">构建任务</div>
          <aicode-select v-model="task" :options="tasks" :disabled="!ready || running"></aicode-select>
          <div class="hint">单次构建最长 5 分钟，超时会中断；更长的构建请在终端中执行。</div>
        </div>
      </aicode-group>

      <aicode-button variant="primary" :disabled="!ready || running" @click="build">
        {{ running ? '构建中…' : '开始构建' }}
      </aicode-button>

      <aicode-group title="输出" v-if="output">
        <pre class="output">{{ output }}</pre>
      </aicode-group>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface BuildTask {
  value: string;
  label: string;
}

interface BuildData {
  detecting: boolean;
  running: boolean;
  projectType: string;
  task: string;
  output: string;
}

var FLUTTER_TASKS = [
      { value: 'flutter build apk --debug', label: 'Flutter · Debug APK' },
      { value: 'flutter build apk --release', label: 'Flutter · Release APK' }
    ];
    var ANDROID_TASKS = [
      { value: './gradlew assembleDebug', label: 'Android · Debug APK' },
      { value: './gradlew assembleRelease', label: 'Android · Release APK' }
    ];

export default defineComponent({
      data(): BuildData {
        return {
          detecting: true,
          running: false,
          projectType: '',
          task: '',
          output: ''
        };
      },
      computed: {
        ready: function () {
          return this.projectType === 'flutter' || this.projectType === 'android';
        },
        tasks: function () {
          if (this.projectType === 'flutter') return FLUTTER_TASKS;
          if (this.projectType === 'android') return ANDROID_TASKS;
          return [];
        },
        projectLabel: function () {
          if (this.detecting) return '正在检测项目类型';
          if (this.projectType === 'flutter') return 'Flutter 项目';
          if (this.projectType === 'android') return 'Android 项目';
          return '未识别的项目';
        },
        projectHint: function () {
          if (this.detecting) return '读取 pubspec.yaml 与 gradlew';
          if (this.projectType === 'flutter') return '已找到 pubspec.yaml';
          if (this.projectType === 'android') return '已找到 gradlew';
          return '未找到 pubspec.yaml 或 gradlew，无法构建';
        }
      },
      mounted: function () {
        this.detect();
      },
      methods: {
        detect: function () {
          var self = this;
          self.detecting = true;
          self.output = '';
          aicode.bash('test -f pubspec.yaml && echo flutter; test -f gradlew && echo android', { timeout: 15 })
            .then(function (res) {
              var out = (res && res.output) || '';
              if (out.indexOf('flutter') >= 0) {
                self.projectType = 'flutter';
              } else if (out.indexOf('android') >= 0) {
                self.projectType = 'android';
              } else {
                self.projectType = 'unknown';
              }
              self.task = self.tasks.length ? self.tasks[0].value : '';
            })
            .catch(function (err) {
              self.projectType = 'unknown';
              self.task = '';
              self.output = '检测失败：' + (err && err.message ? err.message : err);
            })
            .finally(function () {
              self.detecting = false;
            });
        },
        build: function () {
          var self = this;
          if (!self.ready || self.running) return;
          self.running = true;
          self.output = '$ ' + self.task + '\n';
          aicode.bash(self.task, { timeout: 300 })
            .then(function (res) {
              var text = (res && res.output) || '（无输出）';
              if (res && res.exitCode != null) {
                text += '\n[exit ' + res.exitCode + ']';
              }
              if (res && res.truncated) {
                text += '\n[输出已截断]';
              }
              self.output += text;
            })
            .catch(function (err) {
              self.output += '执行失败：' + (err && err.message ? err.message : err);
            })
            .finally(function () {
              self.running = false;
            });
        }
      }
    });
</script>

<style>
* { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    [v-cloak] { display: none !important; }
    body {
      background: var(--aicode-page-bg);
      color: var(--aicode-on-surface);
      font-family: var(--aicode-font-sans);
      font-size: calc(14px * var(--aicode-font-scale));
      line-height: 1.5;
    }
    .page-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      width: 100%;
      max-width: 860px;
      margin: 0 auto;
      padding: 12px 16px 36px;
    }
    .row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 16px;
    }
    .label { font-weight: 500; }
    .hint {
      margin-top: 2px;
      color: var(--aicode-on-surface-variant);
      font-size: calc(13px * var(--aicode-font-scale));
    }
    .field { padding: 12px 16px; }
    .field-title {
      margin-bottom: 6px;
      color: var(--aicode-on-surface-variant);
      font-size: calc(13px * var(--aicode-font-scale));
    }
    .output {
      margin: 0;
      padding: 12px 16px;
      white-space: pre-wrap;
      word-break: break-word;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: calc(12px * var(--aicode-font-scale));
      color: var(--aicode-on-surface-variant);
      max-height: 360px;
      overflow: auto;
    }
</style>
