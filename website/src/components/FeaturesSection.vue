<template>
  <section class="section-y" id="features">
    <div class="container">
      <div class="section-header reveal" v-reveal>
        <h2 class="section-header__title">{{ copy.site.features.title }}</h2>
        <p class="section-header__subtitle">{{ copy.site.features.subtitle }}</p>
      </div>

      <div class="features-grid">
        <article
          v-for="feature in features"
          :key="feature.id"
          class="feature-card reveal"
          v-reveal
          :style="{ '--delay': feature.delay }"
        >
          <div class="feature-card__icon"><i :class="feature.icon"></i></div>
          <h3 class="feature-card__title">{{ feature.title }}</h3>
          <p class="feature-card__desc">{{ feature.desc }}</p>
          <div class="feature-card__orb"></div>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from "vue";
import { useLocale } from "../composables/useLocale.js";

const { copy } = useLocale();

const features = computed(() => [
  { id: "agent", icon: "ri-robot-2-line", delay: "0.05s" },
  { id: "modes", icon: "ri-shield-check-line", delay: "0.1s" },
  { id: "terminal", icon: "ri-terminal-box-line", delay: "0.15s" },
  { id: "remote", icon: "ri-server-line", delay: "0.2s" },
  { id: "editor", icon: "ri-git-merge-line", delay: "0.25s" },
  { id: "models", icon: "ri-puzzle-2-line", delay: "0.3s" }
].map((feature) => ({ ...feature, ...copy.value.site.features.items[feature.id] })));
</script>
