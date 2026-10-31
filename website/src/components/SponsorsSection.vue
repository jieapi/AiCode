<template>
  <section class="section-y" id="sponsors">
    <div class="container">
      <div class="section-header reveal" v-reveal>
        <h2 class="section-header__title">{{ copy.site.sponsors.title }}</h2>
        <p class="section-header__subtitle">{{ copy.site.sponsors.subtitle }}</p>
      </div>

      <div class="sponsors-grid">
        <a
          v-for="sponsor in sponsors"
          :key="sponsor.href"
          :href="sponsor.href"
          target="_blank"
          rel="noopener noreferrer"
          class="sponsor-card reveal"
          v-reveal
          :style="{ '--delay': sponsor.delay }"
        >
          <div class="sponsor-card__top">
            <div class="sponsor-card__head">
              <span class="sponsor-card__name">{{ sponsor.name }}</span>
              <span class="sponsor-card__tag">{{ sponsor.tag }}</span>
            </div>
            <p class="sponsor-card__desc" v-html="sponsor.desc"></p>
          </div>
          <span class="sponsor-card__link">
            {{ sponsor.linkText }} <i class="ri-arrow-right-up-line"></i>
          </span>
        </a>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from "vue";
import { useLocale } from "../composables/useLocale.js";

const { copy } = useLocale();

const sponsors = computed(() => [
  { id: "rainyun", href: "https://www.rainyun.com/logins_", delay: "0.05s" },
  { id: "qiniu", href: "https://s.qiniu.com/vUryau", delay: "0.1s" }
].map((sponsor) => ({ ...sponsor, ...copy.value.site.sponsors.items[sponsor.id] })));
</script>
