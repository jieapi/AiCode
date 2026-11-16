<template>
  <div id="app" class="aicode-page" v-cloak>
    <div class="page-content">
      <aicode-button variant="primary" :disabled="loading" @click="load">
        {{ loading ? '读取中…' : '刷新项目信息' }}
      </aicode-button>

      <aicode-group title="仓库状态">
        <pre class="output">{{ status || '尚无输出' }}</pre>
      </aicode-group>

      <aicode-group title="跟踪的文件">
        <div class="hint" v-if="fileCount">{{ fileCount }} 个文件（最多显示 200 个）</div>
        <pre class="output">{{ files || '尚无输出' }}</pre>
      </aicode-group>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface SnapshotData {
  loading: boolean;
  status: string;
  files: string;
  fileCount: number;
}

export default defineComponent({
      data(): SnapshotData {
        return {
          loading: false,
          status: '',
          files: '',
          fileCount: 0
        };
      },
      mounted: function () {
        this.load();
      },
      methods: {
        load: function () {
          var self = this;
          if (self.loading) return;
          self.loading = true;
          Promise.all([
            aicode.bash('git status --short --branch', { timeout: 30000 }),
            aicode.bash('git ls-files | head -200', { timeout: 30000 })
          ]).then(function (results) {
            self.status = (results[0] && results[0].output) || '（无输出）';
            var files = (results[1] && results[1].output) || '';
            self.files = files || '（无输出）';
            self.fileCount = files ? files.split('\n').filter(function (line) { return line.trim(); }).length : 0;
          }).catch(function (err) {
            self.status = '读取失败：' + (err && err.message ? err.message : err);
            self.files = '';
            self.fileCount = 0;
          }).finally(function () {
            self.loading = false;
          });
        }
      }
    });
</script>

<style>
* { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    [hidden], [v-cloak] { display: none !important; }
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
    .hint {
      padding: 0 16px 12px;
      color: var(--aicode-on-surface-variant);
      font-size: calc(13px * var(--aicode-font-scale));
    }
</style>
