<script setup lang="ts">
import { useData } from 'vitepress'
import { computed } from 'vue'

const { lang, page } = useData()

const show = computed(() => {
  return lang.value === 'en' && !page.value.isNotFound
})
</script>

<template>
  <div v-if="show" class="translation-banner">
    <p>
      <strong>English pages are still being adapted.</strong>
      Only a few core pages are translated so far; the rest of the manual remains in Chinese and will be translated progressively.
    </p>
  </div>
</template>

<style scoped>
.translation-banner {
  margin-top: 16px;
  padding: 10px 16px;
  border: 1px solid var(--vp-c-brand-1, #3c8772);
  background-color: var(--vp-c-bg-soft, rgba(100, 108, 255, 0.08));
  border-radius: 8px;
}

.translation-banner p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--vp-c-text-2);
}
</style>