<template>
  <div id="app" class="aicode-page" v-cloak>
    <template v-if="contextLoaded">
      <header class="aicode-page-header">
        <aicode-icon-button v-if="canClose" label="返回" @click="closePage">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <line x1="19" y1="12" x2="5" y2="12"></line>
            <polyline points="12 19 5 12 12 5"></polyline>
          </svg>
        </aicode-icon-button>
        <h1 class="aicode-page-title">{{ pageTitle }}</h1>
        <aicode-icon-button label="说明" @click="openSheet">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
        </aicode-icon-button>
      </header>

      <div class="page-content">
        <router-view v-slot="{ Component }">
          <component :is="Component" @open-sheet="openSheet" />
        </router-view>
      </div>

      <aicode-bottom-sheet v-model="sheetOpen" :title="sheetTitle">
        <template v-if="isConfigPage">
          <p class="aicode-text">配置内容持久化存储于 <code>data://config.json</code>，属于插件私有存储，卸载插件时一并清除。</p>
          <aicode-button variant="primary" @click="sheetOpen = false">我知道了</aicode-button>
        </template>
        <template v-else>
          <p class="aicode-text">这是宿主提供的底部弹窗组件：拖拽顶部小白条下拉可关闭，点击遮罩或右上角关闭按钮也可以退出；宽屏下会自动变成居中对话框。内容区可滚动，尺寸与设置页的
            AdaptiveModalBottomSheet 一致。</p>
          <aicode-button variant="primary" @click="sheetOpen = false">关闭</aicode-button>
        </template>
      </aicode-bottom-sheet>
    </template>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface GalleryIndexData {
  contextLoaded: boolean;
  canClose: boolean;
  isConfigPage: boolean;
  sheetOpen: boolean;
}

export default defineComponent({
  data(): GalleryIndexData {
    return {
      contextLoaded: false,
      canClose: true,
      isConfigPage: false,
      sheetOpen: false
    };
  },
  computed: {
    pageTitle: function () {
      return this.isConfigPage ? '插件配置' : '组件演示';
    },
    sheetTitle: function () {
      return this.isConfigPage ? '配置说明' : '底部弹窗';
    }
  },
  mounted: function () {
    this.loadContext();
  },
  methods: {
    loadContext: function () {
      var self = this;
      if (!window.aicode || typeof aicode.context !== 'function') {
        self.contextLoaded = true;
        return;
      }
      aicode.context().then(function (ctx) {
        self.isConfigPage = !!ctx && ctx.pageId === 'config';
        self.canClose = !self.isConfigPage;
        var path = self.isConfigPage ? '/config' : '/gallery';
        if (self.$route.path !== path) return self.$router.replace(path);
      }).catch(function () {
        self.canClose = true;
      }).finally(function () {
        self.contextLoaded = true;
      });
    },
    openSheet: function () {
      this.sheetOpen = true;
    },
    closePage: function () {
      if (window.aicode && aicode.ui && aicode.ui.close) {
        aicode.ui.close().catch(function () {});
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
    .demo-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 16px;
    }
    .demo-label {
      color: var(--aicode-on-surface);
      font-size: calc(15px * var(--aicode-font-scale));
      font-weight: 500;
    }
    .demo-hint {
      margin: 4px 0 0;
      color: var(--aicode-on-surface-variant);
      font-size: calc(13px * var(--aicode-font-scale));
    }
</style>
