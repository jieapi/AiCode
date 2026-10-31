<template>
  <header
    ref="navElement"
    class="site-nav"
    :class="{ 'is-scrolled': scrolled, 'is-menu-open': menuOpen }"
    @keydown.esc="closeMenu(true)"
    @focusout="handleFocusOut"
  >
    <div class="container site-nav__inner">
      <a href="#top" class="site-nav__brand" @click="closeMenu()">
        <img src="/assets/logo.png" :alt="copy.site.nav.logoAlt" class="site-nav__logo" />
        <span>AiCode</span>
      </a>

      <nav id="site-navigation" class="site-nav__links" :class="{ 'is-open': menuOpen }" :aria-label="copy.site.nav.label">
        <a
          v-for="link in links"
          :key="link.href"
          :href="link.href"
          class="site-nav__link"
          :target="link.external ? '_blank' : null"
          :rel="link.external ? 'noopener noreferrer' : null"
          @click="closeMenu()"
        >
          <span>{{ link.label }}</span>
          <i v-if="link.external" class="site-nav__external ri-arrow-right-up-line" aria-hidden="true"></i>
        </a>
        <a
          :href="alternateHref"
          :hreflang="alternateLocale"
          class="nav-icon-btn site-nav__language site-nav__language--mobile"
          :aria-label="copy.site.nav.language.label"
          :title="copy.site.nav.language.title"
          @click="navigateLocale"
        >
          <span :lang="alternateLocale">{{ copy.site.nav.language.mobile }}</span>
        </a>
      </nav>

      <div class="site-nav__spacer"></div>

      <div class="site-nav__actions">
        <a
          :href="alternateHref"
          :hreflang="alternateLocale"
          class="nav-icon-btn site-nav__language site-nav__language--desktop"
          :aria-label="copy.site.nav.language.label"
          :title="copy.site.nav.language.title"
          @click="navigateLocale"
        >
          <span :lang="alternateLocale">{{ copy.site.nav.language.desktop }}</span>
        </a>
        <button
          type="button"
          class="nav-icon-btn"
          :aria-label="theme === 'dark' ? copy.site.nav.lightMode : copy.site.nav.darkMode"
          :title="theme === 'dark' ? copy.site.nav.lightMode : copy.site.nav.darkMode"
          @click="toggleTheme"
        >
          <i :class="theme === 'dark' ? 'ri-sun-line' : 'ri-moon-line'" aria-hidden="true"></i>
        </button>
        <a
          href="https://github.com/jieapi/aicode"
          target="_blank"
          rel="noopener noreferrer"
          class="nav-icon-btn"
          :aria-label="copy.site.nav.github"
          :title="copy.site.nav.github"
        >
          <i class="ri-github-fill" aria-hidden="true"></i>
        </a>
        <button
          ref="menuButton"
          type="button"
          class="nav-icon-btn site-nav__mobile-btn"
          :aria-label="menuOpen ? copy.site.nav.closeMenu : copy.site.nav.openMenu"
          :aria-expanded="menuOpen"
          aria-controls="site-navigation"
          @click="toggleMenu"
        >
          <i :class="menuOpen ? 'ri-close-line' : 'ri-menu-3-line'" aria-hidden="true"></i>
        </button>
      </div>
    </div>
  </header>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useLocale } from "../composables/useLocale.js";
import { useTheme } from "../composables/useTheme";

const { copy, syncLocation, alternateLocale, alternateHref } = useLocale();
const { theme, toggleTheme } = useTheme();
const menuOpen = ref(false);
const scrolled = ref(false);
const navElement = ref(null);
const menuButton = ref(null);

const links = computed(() => [
  { label: copy.value.site.nav.links.preview, href: "#preview" },
  { label: copy.value.site.nav.links.features, href: "#features" },
  { label: copy.value.site.nav.links.download, href: "#download" },
  { label: copy.value.site.nav.links.sponsors, href: "#sponsors" },
  { label: copy.value.site.nav.links.docs, href: "https://aicode.murk.top", external: true },
  { label: copy.value.site.nav.links.changelog, href: "https://github.com/jieapi/aicode/releases", external: true }
]);

function navigateLocale(event) {
  if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  window.history.pushState(null, "", alternateHref.value);
  syncLocation(window.location);
}

async function toggleMenu(event) {
  menuOpen.value = !menuOpen.value;
  if (menuOpen.value && event.detail === 0) {
    await nextTick();
    navElement.value?.querySelector(".site-nav__link")?.focus();
  }
}

function closeMenu(restoreFocus = false) {
  if (!menuOpen.value) return;
  menuOpen.value = false;
  if (restoreFocus) menuButton.value?.focus();
}

function handlePointerDown(event) {
  if (!navElement.value?.contains(event.target)) closeMenu();
}

function handleFocusOut(event) {
  if (!navElement.value?.contains(event.relatedTarget)) closeMenu();
}

function syncScroll() {
  scrolled.value = window.scrollY > 16;
}

function handleResize() {
  if (window.innerWidth > 1080) closeMenu();
}

onMounted(() => {
  syncScroll();
  window.addEventListener("scroll", syncScroll, { passive: true });
  window.addEventListener("resize", handleResize);
  document.addEventListener("pointerdown", handlePointerDown);
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", syncScroll);
  window.removeEventListener("resize", handleResize);
  document.removeEventListener("pointerdown", handlePointerDown);
});
</script>

<style scoped>
.site-nav__language {
  width: auto;
  min-width: 44px;
  min-height: 44px;
  padding-inline: 12px;
  font-size: 14px;
  font-weight: 500;
}

.site-nav__language--mobile {
  display: none;
}

@media (max-width: 1080px) {
  .site-nav__language--desktop {
    display: none;
  }

  .site-nav__language--mobile {
    display: inline-flex;
    justify-content: flex-start;
    width: 100%;
    padding-inline: 16px;
  }
}
</style>
