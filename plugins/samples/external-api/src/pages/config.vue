<template>
  <div id="app" class="aicode-page" v-cloak>
    <div class="page-content">
      <aicode-group title="接入凭据">
        <div class="field-block">
          <div class="field-title">API Key</div>
          <aicode-input v-model="token" type="password" placeholder="粘贴服务商签发的 Key"></aicode-input>
          <p class="field-hint">保存后写入宿主加密存储，页面与 AI 都不会再读到原文。</p>
        </div>
        <aicode-divider></aicode-divider>
        <aicode-info-row label="已保存的键" :value="keys.length ? keys.join('、') : '无'"></aicode-info-row>
      </aicode-group>

      <div class="action-bar">
        <aicode-button variant="primary" style="flex: 1;" :disabled="busy" @click="saveKey">保存</aicode-button>
        <aicode-button variant="secondary" :disabled="busy || !keys.length" @click="deleteKey">删除</aicode-button>
      </div>

      <aicode-group title="验证">
        <aicode-info-row label="请求地址" value="httpbin.org/bearer"></aicode-info-row>
        <aicode-divider></aicode-divider>
        <pre class="result">{{ result }}</pre>
        <div class="action-bar" style="padding: 0 16px 12px;">
          <aicode-button variant="primary" style="flex: 1;" :disabled="busy" @click="testKey">测试连接</aicode-button>
        </div>
      </aicode-group>

      <p class="field-hint" style="text-align: center;">
        「验证 API Key」工具与「测试连接」走同一条请求链路：<code>host.secrets.get</code> 读 Key，<code>host.http.request</code> 发起请求。
      </p>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface ExternalConfigData {
  token: string;
  keys: string[];
  busy: boolean;
  result: string;
}

export default defineComponent({
      data(): ExternalConfigData {
        return {
          token: '',
          keys: [],
          busy: false,
          result: '尚未验证'
        };
      },
      mounted: function () {
        this.refreshKeys();
      },
      methods: {
        invoke: function (handler, args) {
          if (!window.aicode || typeof aicode.invoke !== 'function') {
            return Promise.reject(new Error('当前 APK 不支持插件运行时调用'));
          }
          return aicode.invoke(handler, args);
        },
        refreshKeys: async function () {
          try {
            var result = await this.invoke('actions.listKeys');
            this.keys = (result && result.keys) || [];
          } catch (error) {
            this.result = '读取凭据失败：' + (error.message || error);
          }
        },
        saveKey: async function () {
          if (this.busy) return;
          this.busy = true;
          try {
            var result = await this.invoke('actions.saveKey', { token: this.token });
            if (!result || result.ok !== true) {
              throw new Error((result && result.message) || '保存失败');
            }
            this.keys = result.keys || [];
            this.token = '';
            aicode.toast('API Key 已保存');
          } catch (error) {
            this.result = '保存失败：' + (error.message || error);
          } finally {
            this.busy = false;
          }
        },
        deleteKey: async function () {
          if (this.busy) return;
          this.busy = true;
          try {
            var result = await this.invoke('actions.deleteKey');
            this.keys = (result && result.keys) || [];
            this.result = '已删除保存的 API Key';
            aicode.toast('已删除');
          } catch (error) {
            this.result = '删除失败：' + (error.message || error);
          } finally {
            this.busy = false;
          }
        },
        testKey: async function () {
          if (this.busy) return;
          this.busy = true;
          this.result = '请求中…';
          try {
            var result = await this.invoke('actions.test');
            this.result = result.ok
              ? 'HTTP ' + result.status + '，Key 已被接受'
              : 'HTTP ' + (result.status === undefined ? '-' : result.status) + '，' + (result.message || 'Key 未被接受');
          } catch (error) {
            this.result = '请求失败：' + (error.message || error);
          } finally {
            this.busy = false;
          }
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
      -webkit-text-size-adjust: 100%;
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
    .field-block { padding: 12px 16px; }
    .field-title {
      font-size: calc(13px * var(--aicode-font-scale));
      color: var(--aicode-on-surface-variant);
      margin-bottom: 6px;
    }
    .field-hint {
      margin: 6px 0 0;
      color: var(--aicode-on-surface-variant);
      font-size: calc(12px * var(--aicode-font-scale));
    }
    .action-bar {
      display: flex;
      gap: 12px;
      margin-top: 8px;
    }
    .result {
      margin: 0;
      padding: 12px 16px;
      white-space: pre-wrap;
      word-break: break-all;
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: calc(12px * var(--aicode-font-scale));
      color: var(--aicode-on-surface-variant);
    }
</style>
