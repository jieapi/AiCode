<template>
  <div id="app" class="aicode-page" v-cloak>
    <div class="page-content">
      <aicode-group title="项目">
        <div class="row">
          <div>
            <div class="label">{{ projectLabel }}</div>
            <div class="hint">{{ cleanCommand ? '清理命令：' + cleanCommand : projectHint }}</div>
          </div>
          <aicode-button variant="secondary" :disabled="detecting || running" @click="refresh">
            {{ detecting ? '检测中…' : '刷新' }}
          </aicode-button>
        </div>
      </aicode-group>

      <aicode-group title="缓存占用">
        <pre class="sizes">{{ sizes || '正在统计…' }}</pre>
      </aicode-group>

      <aicode-button variant="primary" :disabled="!cleanCommand || running" @click="clean">
        {{ running ? '清理中…' : '清理缓存' }}
      </aicode-button>

      <aicode-group title="输出" v-if="output">
        <pre class="output">{{ output }}</pre>
      </aicode-group>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface CleanData {
  detecting: boolean;
  running: boolean;
  projectType: string;
  sizes: string;
  output: string;
}

var SIZES_COMMAND =
      'for d in build .dart_tool .gradle android/.gradle android/build; do ' +
      'if [ -e "$d" ]; then du -sh "$d"; fi; done';

export default defineComponent({
      data(): CleanData {
        return {
          detecting: true,
          running: false,
          projectType: '',
          sizes: '',
          output: ''
        };
      },
      computed: {
        cleanCommand: function () {
          if (this.projectType === 'flutter') return 'flutter clean';
          if (this.projectType === 'android') return './gradlew clean';
          return '';
        },
        projectLabel: function () {
          if (this.detecting) return '正在检测项目类型';
          if (this.projectType === 'flutter') return 'Flutter 项目';
          if (this.projectType === 'android') return 'Android 项目';
          return '未识别的项目';
        },
        projectHint: function () {
          if (this.projectType === 'unknown') return '未找到 pubspec.yaml 或 gradlew';
          return '读取 pubspec.yaml 与 gradlew';
        }
      },
      mounted: function () {
        this.refresh();
      },
      methods: {
        refresh: function () {
          var self = this;
          self.detecting = true;
          self.output = '';
          Promise.all([
            aicode.bash('test -f pubspec.yaml && echo flutter; test -f gradlew && echo android', { timeout: 15 }),
            aicode.bash(SIZES_COMMAND, { timeout: 30 })
          ]).then(function (results) {
            var kind = (results[0] && results[0].output) || '';
            if (kind.indexOf('flutter') >= 0) {
              self.projectType = 'flutter';
            } else if (kind.indexOf('android') >= 0) {
              self.projectType = 'android';
            } else {
              self.projectType = 'unknown';
            }
            self.sizes = (results[1] && results[1].output) || '';
          }).catch(function (err) {
            self.projectType = 'unknown';
            self.sizes = '';
            self.output = '检测失败：' + (err && err.message ? err.message : err);
          }).finally(function () {
            self.detecting = false;
          });
        },
        clean: function () {
          var self = this;
          if (!self.cleanCommand || self.running) return;
          self.running = true;
          self.output = '$ ' + self.cleanCommand + '\n';
          aicode.bash(self.cleanCommand, { timeout: 300 })
            .then(function (res) {
              var text = (res && res.output) || '（无输出）';
              if (res && res.exitCode != null) {
                text += '\n[exit ' + res.exitCode + ']';
              }
              if (res && res.truncated) {
                text += '\n[输出已截断]';
              }
              self.output += text;
              return aicode.bash(SIZES_COMMAND, { timeout: 30 });
            })
            .then(function (res) {
              self.sizes = (res && res.output) || '（缓存目录已清理）';
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
    .sizes {
      margin: 0;
      padding: 12px 16px;
      white-space: pre-wrap;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: calc(12px * var(--aicode-font-scale));
      color: var(--aicode-on-surface-variant);
    }
    .output {
      margin: 0;
      padding: 12px 16px;
      white-space: pre-wrap;
      word-break: break-word;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: calc(12px * var(--aicode-font-scale));
      color: var(--aicode-on-surface-variant);
      max-height: 320px;
      overflow: auto;
    }
</style>
