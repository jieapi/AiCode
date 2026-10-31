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
        <img src="/assets/logo.png" alt="AiCode Logo" class="site-nav__logo" />
        <span>AiCode</span>
      </a>

      <nav id="site-navigation" class="site-nav__links" :class="{ 'is-open': menuOpen }" aria-label="主导航">
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
      </nav>

      <div class="site-nav__spacer"></div>

      <div class="site-nav__actions">
        <button
          type="button"
          class="nav-icon-btn"
          :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
          :title="theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme"
        >
          <i :class="theme === 'dark' ? 'ri-sun-line' : 'ri-moon-line'" aria-hidden="true"></i>
        </button>
        <a
          href="https://github.com/jieapi/aicode"
          target="_blank"
          rel="noopener noreferrer"
          class="nav-icon-btn"
          aria-label="GitHub 仓库"
          title="GitHub 仓库"
        >
          <i class="ri-github-fill" aria-hidden="true"></i>
        </a>
        <button
          ref="menuButton"
          type="button"
          class="nav-icon-btn site-nav__mobile-btn"
          :aria-label="menuOpen ? '关闭导航菜单' : '打开导航菜单'"
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
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useTheme } from "../composables/useTheme";

const { theme, toggleTheme } = useTheme();
const menuOpen = ref(false);
const scrolled = ref(false);
const navElement = ref(null);
const menuButton = ref(null);

const links = [
  { label: "界面预览", href: "#preview" },
  { label: "功能特性", href: "#features" },
  { label: "快速开始", href: "#download" },
  { label: "赞助与合作", href: "#sponsors" },
  { label: "在线文档", href: "https://aicode.murk.top", external: true },
  { label: "更新日志", href: "https://github.com/jieapi/aicode/releases", external: true }
];

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
