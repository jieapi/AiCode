<template>
  <section class="quick-start section-y bg-muted-section" id="download" aria-labelledby="quick-start-title">
    <div class="container">
      <header class="quick-start__header reveal" v-reveal>
        <div>
          <h2 class="quick-start__title" id="quick-start-title">{{ copy.site.download.title }}</h2>
          <p class="quick-start__subtitle">{{ copy.site.download.subtitle }}</p>
        </div>
        <p class="quick-start__requirement">
          <i class="ri-android-line" aria-hidden="true"></i>
          <span>{{ copy.site.download.android }} <span class="quick-start__api">{{ copy.site.download.api }}</span></span>
        </p>
      </header>

      <div class="quick-start__panel reveal" v-reveal style="--delay: 0.05s">
        <ol class="quick-start__steps" role="list">
          <li v-for="step in steps" :key="step.id" class="quick-start__step">
            <span class="quick-start__number" aria-hidden="true">{{ step.num }}</span>
            <div class="quick-start__step-copy">
              <h3 class="quick-start__step-title">{{ step.title }}</h3>
              <p class="quick-start__step-description" v-html="step.desc"></p>
            </div>
          </li>
        </ol>

        <div class="quick-start__downloads">
          <div class="quick-start__download-copy">
            <h3 class="quick-start__download-title">{{ copy.site.download.packageTitle }}</h3>
            <p class="quick-start__download-description">{{ copy.site.download.packageDescription }}</p>
          </div>
          <div class="quick-start__actions">
            <a
              href="https://github.com/jieapi/aicode/releases/latest"
              target="_blank"
              rel="noopener noreferrer"
              class="btn btn--lg quick-start__download-primary"
            >
              <i class="ri-github-fill" aria-hidden="true"></i>
              <span>{{ copy.site.download.github }}</span>
            </a>
            <a
              href="https://aicode.murk.top/download"
              target="_blank"
              rel="noopener noreferrer"
              class="quick-start__download-secondary"
            >
              <span>{{ copy.site.download.mirror }}</span>
              <i class="ri-arrow-right-up-line" aria-hidden="true"></i>
            </a>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from "vue";
import { useLocale } from "../composables/useLocale.js";

const { copy } = useLocale();

const steps = computed(() => [
  { id: "install", num: "01" },
  { id: "provider", num: "02" },
  { id: "environment", num: "03" }
].map((step) => ({ ...step, ...copy.value.site.download.steps[step.id] })));
</script>

<style scoped>
.quick-start {
  --quick-start-inset: 32px;
  --quick-start-radius: 20px;
}

.quick-start__header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 32px;
}

.quick-start__title {
  margin-bottom: 12px;
  color: var(--fg);
  font-size: clamp(26px, 3.4vw, 38px);
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.2;
}

.quick-start__subtitle {
  color: var(--fg-secondary);
  font-size: 16px;
  line-height: 1.7;
}

.quick-start__requirement {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  padding-bottom: 2px;
  color: var(--fg-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.quick-start__requirement i {
  color: var(--primary);
  font-size: 18px;
}

.quick-start__api {
  white-space: nowrap;
}

.quick-start__panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--quick-start-radius);
}

.quick-start__steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  list-style: none;
}

.quick-start__step {
  min-width: 0;
  padding: var(--quick-start-inset);
}

.quick-start__step + .quick-start__step {
  border-inline-start: 1px solid var(--border-subtle);
}

.quick-start__number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-bottom: 20px;
  border-radius: 8px;
  background: var(--primary-soft);
  color: var(--primary);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.quick-start__step-copy {
  min-width: 0;
}

.quick-start__step-title {
  margin-bottom: 8px;
  color: var(--fg);
  font-size: 17px;
  font-weight: 600;
  line-height: 1.5;
}

.quick-start__step-description {
  color: var(--fg-secondary);
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: break-word;
}

.quick-start__step-description :deep(code) {
  padding: 2px 5px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--fg);
  font-size: 0.86em;
  white-space: nowrap;
}

.quick-start__downloads {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 24px;
  padding: 24px var(--quick-start-inset);
  border-top: 1px solid var(--border);
  border-radius: 0 0 var(--quick-start-radius) var(--quick-start-radius);
  background: var(--bg-muted);
}

.quick-start__download-copy {
  min-width: 0;
}

.quick-start__download-title {
  margin-bottom: 4px;
  color: var(--fg);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
}

.quick-start__download-description {
  max-width: 440px;
  color: var(--fg-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.quick-start__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 20px;
  min-width: 0;
}

.quick-start__download-primary {
  min-width: 0;
  max-width: 100%;
  min-height: 48px;
  height: auto;
  padding: 12px 20px;
  border-radius: 12px;
  background: var(--fg);
  color: var(--bg);
  box-shadow: none;
  font-size: 14px;
  line-height: 1.5;
  white-space: normal;
  text-align: center;
}

.quick-start__download-primary i {
  flex-shrink: 0;
  font-size: 18px;
}

.quick-start__download-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 8px 0;
  border-radius: 4px;
  color: var(--fg-secondary);
  font-size: 14px;
  font-weight: 500;
  line-height: 1.5;
  transition: color 0.2s ease;
}

.quick-start__download-secondary i {
  flex-shrink: 0;
  font-size: 16px;
}

.quick-start__download-primary:focus-visible,
.quick-start__download-secondary:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 4px;
}

@media (hover: hover) {
  .quick-start__download-primary:hover {
    background: var(--fg-secondary);
  }

  .quick-start__download-secondary:hover {
    color: var(--fg);
    text-decoration: underline;
    text-decoration-color: var(--primary);
    text-underline-offset: 4px;
  }
}

@media (max-width: 900px) {
  .quick-start {
    --quick-start-inset: 24px;
  }

  .quick-start__steps {
    grid-template-columns: minmax(0, 1fr);
    padding: 0 var(--quick-start-inset);
  }

  .quick-start__step {
    display: grid;
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 16px;
    padding: 24px 0;
  }

  .quick-start__step + .quick-start__step {
    border-inline-start: 0;
    border-top: 1px solid var(--border-subtle);
  }

  .quick-start__number {
    margin-bottom: 0;
  }

  .quick-start__downloads {
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }
}

@media (max-width: 640px) {
  .quick-start {
    --quick-start-inset: 20px;
  }

  .quick-start__header {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 24px;
  }

  .quick-start__subtitle {
    font-size: 15px;
  }

  .quick-start__actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }

  .quick-start__download-primary {
    width: 100%;
  }
}
</style>
