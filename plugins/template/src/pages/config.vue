<template>
  <div class="aicode-page">
    <header class="aicode-page-header">
      <h1 class="aicode-page-title">Hello 插件</h1>
      <aicode-badge text="配置页示例" kind="plugin" />
    </header>

    <aicode-group title="运行环境">
      <aicode-info-row label="插件 ID" :value="ctx ? ctx.pluginId : '加载中…'" />
      <aicode-info-row label="页面 ID" :value="ctx ? ctx.pageId : '加载中…'" />
      <aicode-info-row label="工作区" :value="ctx ? ctx.workspaceName : '—'" />
    </aicode-group>

    <aicode-group title="配置">
      <aicode-field label="问候语">
        <aicode-input v-model="greeting" placeholder="输入一句问候" />
      </aicode-field>
      <div class="actions">
        <aicode-button variant="primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </aicode-button>
        <aicode-button :disabled="calling" @click="callEcho">
          {{ calling ? '调用中…' : '调用 echo 工具' }}
        </aicode-button>
      </div>
      <p class="hint">
        配置写入插件私有目录 <code>data://config.json</code>，卸载插件时自动清理。
      </p>
    </aicode-group>

    <aicode-group title="结果">
      <pre v-if="output">{{ output }}</pre>
      <aicode-empty v-else text="点击按钮查看结果" />
    </aicode-group>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { AicodePageContext } from '@jieapi/plugin-sdk/page';

const ctx = ref<AicodePageContext | null>(null);
const greeting = ref('你好，AiCode');
const saving = ref(false);
const calling = ref(false);
const output = ref('');

onMounted(async () => {
  try {
    const context = await aicode.context();
    if ('overlayId' in context) return;
    ctx.value = context;
    const file = await aicode.fs.read('data://config.json');
    const saved = JSON.parse(file.content) as { greeting?: string };
    if (saved.greeting) greeting.value = saved.greeting;
  } catch {
    // 首次运行还没有配置文件，保留默认问候语即可。
  }
});

async function save(): Promise<void> {
  saving.value = true;
  try {
    await aicode.fs.write(
      'data://config.json',
      JSON.stringify({ greeting: greeting.value }, null, 2)
    );
    output.value = '已写入 data://config.json';
    await aicode.toast('已保存');
  } catch (error) {
    output.value = '保存失败：' + (error as Error).message;
  } finally {
    saving.value = false;
  }
}

async function callEcho(): Promise<void> {
  calling.value = true;
  try {
    const result = await aicode.invoke<{ text: string }>('tools.echo', { text: greeting.value });
    output.value = 'tools.echo 返回：' + JSON.stringify(result, null, 2);
  } catch (error) {
    output.value = '调用失败：' + (error as Error).message;
  } finally {
    calling.value = false;
  }
}
</script>

<style scoped>
.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.hint {
  margin: 12px 0 0;
  color: var(--aicode-text-secondary, #59636e);
  font-size: 13px;
  line-height: 1.6;
}

pre {
  margin: 0;
  padding: 12px;
  border-radius: 10px;
  overflow: auto;
  background: var(--aicode-surface, #f6f8fa);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
}
</style>
