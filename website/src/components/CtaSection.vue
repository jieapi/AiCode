<template>
  <section class="cta-section" aria-labelledby="community-title">
    <div class="container">
      <div class="cta-section__content reveal" v-reveal>
        <div class="cta-section__intro">
          <h2 class="cta-section__title" id="community-title">{{ copy.site.cta.title }}</h2>
          <p class="cta-section__subtitle">
            {{ copy.site.cta.subtitle }}
          </p>
        </div>
        <div class="cta-section__actions">
          <a
            href="https://github.com/jieapi/aicode/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            class="btn btn--lg btn--primary"
          >
            <i class="ri-download-2-line" aria-hidden="true"></i>
            <span>{{ copy.site.cta.download }}</span>
          </a>
          <div class="cta-section__links">
            <a
              href="https://github.com/jieapi/aicode"
              target="_blank"
              rel="noopener noreferrer"
              class="cta-section__link"
            >
              <i class="ri-star-line" aria-hidden="true"></i>
              <span>{{ copy.site.cta.github }}</span>
            </a>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { useLocale } from "../composables/useLocale.js";

const { copy } = useLocale();
</script>
