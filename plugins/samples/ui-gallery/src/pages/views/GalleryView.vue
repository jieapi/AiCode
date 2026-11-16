<template>
  <div class="gallery-view">
    <aicode-group title="列表行">
      <aicode-row title="默认行" subtitle="标题 + 副标题 + 右箭头" @click="toast('点击了默认行')"></aicode-row>
      <aicode-divider></aicode-divider>
      <aicode-row title="带徽章" subtitle="插件标签与启用状态" badge="插件" badge-kind="plugin"
        @click="toast('点击了带徽章行')"></aicode-row>
      <aicode-divider></aicode-divider>
      <aicode-row title="文件图标" subtitle='icon="file"' icon="file" @click="toast('点击了文件行')"></aicode-row>
      <aicode-divider></aicode-divider>
      <aicode-swipe-row @delete="toast('左滑删除：示例行')">
        <aicode-row title="左滑删除" subtitle="向左滑动露出删除按钮"></aicode-row>
      </aicode-swipe-row>
    </aicode-group>

    <aicode-group title="徽章">
      <div class="demo-inline">
        <aicode-badge text="默认"></aicode-badge>
        <aicode-badge text="插件" kind="plugin"></aicode-badge>
        <aicode-badge text="已启用" kind="enabled"></aicode-badge>
      </div>
    </aicode-group>

    <aicode-group title="开关">
      <div class="demo-row">
        <span class="demo-label">启用示例</span>
        <aicode-switch v-model="switchOn"></aicode-switch>
      </div>
      <aicode-divider></aicode-divider>
      <div class="demo-row">
        <span class="demo-label">禁用示例</span>
        <aicode-switch :model-value="true" :disabled="true"></aicode-switch>
      </div>
    </aicode-group>

    <aicode-group title="输入">
      <aicode-field label="名称">
        <aicode-input v-model="name" placeholder="输入名称"></aicode-input>
      </aicode-field>
      <aicode-field label="描述">
        <aicode-input v-model="description" placeholder="输入描述"></aicode-input>
      </aicode-field>
      <aicode-divider></aicode-divider>
      <div class="demo-row">
        <span class="demo-label">分段按钮</span>
        <aicode-segmented v-model="scope" :options="scopeOptions"></aicode-segmented>
      </div>
    </aicode-group>

    <aicode-group title="正文">
      <div class="aicode-outlined-field aicode-outlined-field-body">
        <aicode-textarea v-model="content" placeholder="输入正文"></aicode-textarea>
      </div>
    </aicode-group>

    <aicode-group title="按钮">
      <div class="demo-inline">
        <aicode-button variant="primary" @click="dialogOpen = true">主要按钮</aicode-button>
        <aicode-button @click="toast('普通按钮')">普通按钮</aicode-button>
        <aicode-button variant="danger" @click="toast('危险按钮')">危险按钮</aicode-button>
      </div>
    </aicode-group>

    <aicode-group title="空态">
      <aicode-empty text="这里没有内容"></aicode-empty>
    </aicode-group>

    <aicode-group title="Markdown">
      <aicode-markdown :text="markdown"></aicode-markdown>
    </aicode-group>

    <aicode-group title="底部弹窗与对话框">
      <aicode-row title="打开底部弹窗" subtitle="AicodeBottomSheet · 下拉或点击小白条关闭" @click="openSheet"></aicode-row>
      <aicode-divider></aicode-divider>
      <aicode-row title="打开输入弹窗" subtitle="名称输入与保存" @click="dialogName = name; inputDialogOpen = true"></aicode-row>
      <aicode-divider></aicode-divider>
      <aicode-row title="打开文字确认弹窗" subtitle="说明文字与确认操作" @click="dialogOpen = true"></aicode-row>
    </aicode-group>

    <p class="demo-hint">页面里所有组件都来自宿主注入的 AicodeUI，插件不需要打包 Vue 或组件代码。</p>
  </div>

  <aicode-dialog v-model="inputDialogOpen" title="修改名称"
    confirm-text="保存" cancel-text="取消" :confirm-disabled="!dialogName.trim()"
    @confirm="name = dialogName; toast('已保存')">
    <aicode-field label="名称">
      <aicode-input v-model="dialogName" placeholder="输入名称"></aicode-input>
    </aicode-field>
  </aicode-dialog>

  <aicode-dialog v-model="dialogOpen" title="确认操作"
    message="这是宿主提供的确认对话框，样式与 App 的 AlertDialog 一致。"
    confirm-text="确定" cancel-text="取消" @confirm="toast('已确认')"></aicode-dialog>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface GalleryViewData {
  switchOn: boolean;
  name: string;
  description: string;
  content: string;
  scope: string;
  inputDialogOpen: boolean;
  dialogName: string;
  dialogOpen: boolean;
  markdown: string;
}

export default defineComponent({
  emits: ['open-sheet'],
  data(): GalleryViewData {
    return {
      switchOn: true,
      name: '',
      description: '',
      content: '',
      scope: 'project',
      inputDialogOpen: false,
      dialogName: '',
      dialogOpen: false,
      markdown: '# 标题\n\n- 列表项\n- **粗体**\n\n```\ncode\n```'
    };
  },
  computed: {
    scopeOptions: function () {
      return [
        { value: 'project', label: '当前项目' },
        { value: 'global', label: '全局' }
      ];
    }
  },
  methods: {
    openSheet: function () {
      this.$emit('open-sheet');
    },
    toast: function (message: string) {
      if (window.aicode && aicode.toast) aicode.toast(message);
    }
  }
});
</script>

<style>
.gallery-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
    .demo-inline {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      padding: 12px 16px;
    }
</style>
