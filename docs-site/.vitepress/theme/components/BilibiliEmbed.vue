<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{ bvid: string }>()

const loaded = ref(false)
const src = computed(
  () => `https://player.bilibili.com/player.html?bvid=${props.bvid}&autoplay=0&danmaku=0`
)
</script>

<template>
  <div class="bili-embed">
    <iframe
      class="bili-embed__frame"
      :src="src"
      scrolling="no"
      frameborder="no"
      framespacing="0"
      allowfullscreen="true"
      @load="loaded = true"
    />
    <div v-show="!loaded" class="bili-embed__skeleton">
      <span class="bili-embed__play">
        <svg viewBox="0 0 24 24" width="32" height="32" aria-hidden="true">
          <path d="M8 5v14l11-7z" fill="currentColor" />
        </svg>
      </span>
      <span class="bili-embed__hint">视频加载中…</span>
    </div>
  </div>
</template>

<style scoped>
.bili-embed {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  margin: 16px 0;
  overflow: hidden;
  border-radius: 8px;
  background-color: var(--vp-c-bg-soft);
}

.bili-embed__frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
}

.bili-embed__skeleton {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  overflow: hidden;
  background-color: var(--vp-c-bg-soft);
}

.bili-embed__skeleton::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    transparent 30%,
    var(--vp-c-bg) 50%,
    transparent 70%
  );
  opacity: 0.6;
  animation: bili-shimmer 1.6s ease-in-out infinite;
}

.bili-embed__play {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  color: #fff;
  background-color: #fb7299;
  opacity: 0.9;
}

.bili-embed__hint {
  position: relative;
  font-size: 13px;
  color: var(--vp-c-text-2);
}

@keyframes bili-shimmer {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(100%);
  }
}
</style>
