<script setup lang="ts">
import { computed, ref, onBeforeUnmount } from 'vue'
import { useData } from 'vitepress'

const props = defineProps<{
  mode: 'aside' | 'doc'
}>()

const { page, frontmatter, site } = useData()

const isDoc = computed(() => {
  return frontmatter.value.layout !== 'home' && !page.value.isNotFound && !!page.value.relativePath
})

const mdUrl = computed(() => {
  const base = site.value.base || '/'
  const rel = page.value.relativePath || ''
  return `${base}md/${rel}`.replace(/\/+/g, '/')
})

const copyStatus = ref<'idle' | 'copying' | 'copied' | 'error'>('idle')
let timer: ReturnType<typeof setTimeout> | null = null

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})

const handleCopy = async () => {
  if (copyStatus.value === 'copying') return
  copyStatus.value = 'copying'
  try {
    const res = await fetch(mdUrl.value)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const text = await res.text()
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    copyStatus.value = 'copied'
  } catch (err) {
    console.error('复制 Markdown 失败:', err)
    copyStatus.value = 'error'
  } finally {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      copyStatus.value = 'idle'
    }, 2000)
  }
}
</script>

<template>
  <div v-if="isDoc" class="vp-raw md-actions-wrapper" :class="`is-${mode}`">
    <div class="md-actions-bar">
      <a
        :href="mdUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="md-btn md-btn-open"
        title="在新标签页以纯 Markdown 打开此文档"
      >
        <svg class="md-icon" viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
          <path d="M14.85 3H1.15C.52 3 0 3.52 0 4.15v7.69C0 12.48.52 13 1.15 13h13.69c.64 0 1.15-.52 1.15-1.15V4.15C16 3.52 15.48 3 14.85 3zM9 11H7V8L5.5 9.9 4 8v3H2V5h2l1.5 2L7 5h2v6zm2.99.5L9.5 8H11V5h2v3h1.5l-2.51 3.5z"/>
        </svg>
        <span class="md-btn-text">以 Markdown 打开</span>
        <svg class="external-icon" viewBox="0 0 24 24" width="12" height="12" stroke="currentColor" stroke-width="2" fill="none">
          <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path>
          <polyline points="15 3 21 3 21 9"></polyline>
          <line x1="10" y1="14" x2="21" y2="3"></line>
        </svg>
      </a>

      <span class="md-divider" aria-hidden="true" />

      <button
        type="button"
        class="md-btn md-btn-copy"
        :class="{ 'is-copied': copyStatus === 'copied', 'is-error': copyStatus === 'error' }"
        :title="copyStatus === 'copied' ? '已复制到剪贴板' : '复制 Markdown 原文'"
        @click="handleCopy"
      >
        <svg v-if="copyStatus === 'copied'" class="status-icon" viewBox="0 0 24 24" width="13" height="13" stroke="currentColor" stroke-width="2.5" fill="none">
          <polyline points="20 6 9 17 4 12"></polyline>
        </svg>
        <svg v-else class="copy-icon" viewBox="0 0 24 24" width="13" height="13" stroke="currentColor" stroke-width="2" fill="none">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
        <span class="md-btn-text">
          {{ copyStatus === 'copied' ? '已复制' : (copyStatus === 'copying' ? '复制中' : (copyStatus === 'error' ? '失败' : '复制')) }}
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.md-actions-wrapper {
  margin-bottom: 16px;
}

/* 宽屏时：展示 aside，隐藏 doc-before */
@media (min-width: 960px) {
  .md-actions-wrapper.is-doc {
    display: none;
  }
  .md-actions-wrapper.is-aside {
    display: block;
  }
}

/* 窄屏时：展示 doc-before，隐藏 aside */
@media (max-width: 959px) {
  .md-actions-wrapper.is-doc {
    display: flex;
    justify-content: flex-start;
  }
  .md-actions-wrapper.is-aside {
    display: none;
  }
}

.md-actions-bar {
  display: inline-flex;
  align-items: center;
  box-sizing: border-box;
  background-color: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  padding: 3px 4px;
  transition: border-color 0.2s, background-color 0.2s, box-shadow 0.2s;
  width: 100%;
}

.is-doc .md-actions-bar {
  width: auto;
}

.md-actions-bar:hover {
  border-color: var(--vp-c-brand-1);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.md-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  color: var(--vp-c-text-2);
  border-radius: 6px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-decoration: none !important;
  transition: color 0.15s, background-color 0.15s;
  white-space: nowrap;
}

.md-btn:hover {
  color: var(--vp-c-text-1);
  background-color: var(--vp-c-default-soft);
}

.md-btn-open {
  flex: 1;
  justify-content: center;
}

.md-btn-open:hover {
  color: var(--vp-c-brand-1);
}

.md-divider {
  width: 1px;
  height: 14px;
  background-color: var(--vp-c-divider);
  margin: 0 2px;
  flex-shrink: 0;
}

.md-btn-copy {
  flex-shrink: 0;
}

.md-btn-copy.is-copied {
  color: var(--vp-c-brand-1);
  font-weight: 600;
}

.md-btn-copy.is-error {
  color: var(--vp-c-danger-1, #e03131);
}

.md-icon {
  flex-shrink: 0;
  opacity: 0.85;
}

.external-icon {
  flex-shrink: 0;
  opacity: 0.6;
  transition: transform 0.15s, opacity 0.15s;
}

.md-btn-open:hover .external-icon {
  opacity: 1;
  transform: translate(1px, -1px);
}

.copy-icon,
.status-icon {
  flex-shrink: 0;
}
</style>
