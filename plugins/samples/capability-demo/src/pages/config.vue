<template>
  <div id="app">
    <h2>能力演示</h2>
    <p>先在「管理」页开启 Shizuku 权限，再点下面的按钮。提示条会出现在对话页底部。</p>
    <button type="button" @click="showTip">显示提示条</button>
    <button type="button" @click="runShizuku">执行 Shizuku 查询</button>
    <button type="button" @click="openConsole">打开调试控制台</button>
    <pre>{{ output }}</pre>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const output = ref<string>('尚未操作');

function showError(text: string): void {
  output.value = text;
  if (window.eruda) eruda.show();
}

function onWindowError(event: ErrorEvent): void {
  showError('页面错误：' + event.message);
}

function onUnhandledRejection(event: PromiseRejectionEvent): void {
  const reason = event.reason as { message?: string } | string | undefined;
  const detail = typeof reason === 'string' ? reason : reason?.message;
  showError('未处理异常：' + (detail ?? String(reason)));
}

async function showTip(): Promise<void> {
  console.log('[capability-demo] tip clicked');
  try {
    const result = await aicode.tip({
      title: '能力演示',
      text: '这条提示来自插件配置页，点「知道了」可以验证按钮回调。',
      action: { label: '知道了', handler: 'tools.tipAction' }
    });
    output.value = JSON.stringify(result, null, 2);
  } catch (error) {
    output.value = '提示失败：' + (error instanceof Error ? error.message : String(error));
  }
}

async function runShizuku(): Promise<void> {
  console.log('[capability-demo] shizuku clicked');
  try {
    const result = await aicode.shizuku.exec('settings get global zen_mode');
    output.value = JSON.stringify(result, null, 2);
  } catch (error) {
    output.value = 'Shizuku 失败：' + (error instanceof Error ? error.message : String(error));
  }
}

function openConsole(): void {
  if (window.eruda) {
    eruda.show();
  } else {
    output.value = '调试控制台未加载：请安装最新 APK';
  }
}

onMounted(() => {
  if (window.eruda) {
    eruda.init();
  }

  console.log('[capability-demo] config page loaded', {
    hasAicode: !!window.aicode,
    hasTip: !!(window.aicode && window.aicode.tip),
    hasShizuku: !!(window.aicode && window.aicode.shizuku),
    hasEruda: !!window.eruda
  });

  window.addEventListener('error', onWindowError);
  window.addEventListener('unhandledrejection', onUnhandledRejection);

  if (!window.aicode) {
    output.value = '页面 SDK 未加载：请安装最新 APK 后重试';
  } else if (typeof aicode.tip !== 'function' || !aicode.shizuku) {
    output.value = '当前 APK 缺少 tip / Shizuku 页面接口，请安装最新 APK';
  } else {
    aicode.context().then((context) => {
      if (output.value === '尚未操作') {
        output.value = '页面已连接：' + (context.pageId || context.pluginId || 'ok');
      }
    }).catch((error: unknown) => {
      output.value = '页面连接失败：' + (error instanceof Error ? error.message : String(error));
    });
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('error', onWindowError);
  window.removeEventListener('unhandledrejection', onUnhandledRejection);
});
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
    button {
      margin: 6px 8px 6px 0;
      padding: 10px 16px;
      border: 0;
      border-radius: 10px;
      background: var(--aicode-primary, #0969da);
      color: #ffffff;
      font-size: 15px;
    }
    button:disabled { opacity: .55; }
    pre {
      margin-top: 12px;
      padding: 12px;
      border-radius: 10px;
      overflow: auto;
      background: var(--aicode-surface, #f6f8fa);
      font-size: 12px;
      white-space: pre-wrap;
    }
</style>
