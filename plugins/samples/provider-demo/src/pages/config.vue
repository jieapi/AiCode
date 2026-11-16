<template>
  <h2>演示提供商</h2>
  <p>插件内置一个本机 OpenAI 兼容回声服务，只把最后一条用户消息原样返回。</p>
  <ul>
    <li>地址：<code>http://127.0.0.1:18080/v1</code></li>
    <li>模型：<code>demo-echo</code></li>
    <li>访问令牌：<code>demo-local-token</code></li>
  </ul>
  <p>启动服务后会自动把模型和访问令牌同步到宿主里的插件提供商条目。</p>
  <p>安装后如果没有重启 App，服务可能尚未启动；点下面的按钮手动启动。</p>
  <button :disabled="starting" @click="start">{{ starting ? '启动中…' : '启动服务' }}</button>
  <pre>{{ output }}</pre>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const starting = ref<boolean>(false);
const output = ref<string>('尚未启动');

async function start(): Promise<void> {
  if (starting.value) return;
  if (!window.aicode) {
    output.value = '页面 SDK 未加载';
    return;
  }
  starting.value = true;
  output.value = '启动中…';
  try {
    const result = await window.aicode.invoke('provider.start');
    output.value = JSON.stringify(result, null, 2);
    window.aicode.toast('演示提供商已启动');
  } catch (error) {
    output.value = '启动失败：' + (error instanceof Error ? error.message : String(error));
  } finally {
    starting.value = false;
  }
}
</script>

<style>
:root { color-scheme: light dark; }
body {
  margin: 0;
  padding: 20px;
  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  line-height: 1.6;
  color: var(--aicode-text, #1f2328);
  background: var(--aicode-background, #ffffff);
}
h2 { margin: 0 0 8px; font-size: 20px; }
p, li { color: var(--aicode-text-secondary, #59636e); }
code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
button {
  margin-top: 12px;
  padding: 10px 16px;
  border: 0;
  border-radius: 10px;
  background: var(--aicode-primary, #0969da);
  color: #ffffff;
  font-size: 15px;
}
button:disabled { opacity: .55; }
pre {
  margin-top: 16px;
  padding: 12px;
  border-radius: 10px;
  overflow: auto;
  background: var(--aicode-surface, #f6f8fa);
  font-size: 12px;
  white-space: pre-wrap;
}
</style>
