<template>
  <SiteNav />
  <main id="top">
    <HeroSection />
    <FeaturesSection />
    <DownloadSection />
    <SponsorsSection />
    <CtaSection />
  </main>
  <SiteFooter />
</template>

<script setup>
import { onBeforeUnmount, onMounted, watch } from "vue";
import { LOCALE_PATHS, useLocale } from "./composables/useLocale.js";
import SiteNav from "./components/SiteNav.vue";
import HeroSection from "./components/HeroSection.vue";
import FeaturesSection from "./components/FeaturesSection.vue";
import DownloadSection from "./components/DownloadSection.vue";
import SponsorsSection from "./components/SponsorsSection.vue";
import CtaSection from "./components/CtaSection.vue";
import SiteFooter from "./components/SiteFooter.vue";

const { locale, copy, syncLocation } = useLocale();

function syncAddress() {
  syncLocation(window.location);
}

onMounted(() => {
  syncAddress();
  window.addEventListener("popstate", syncAddress);
  window.addEventListener("hashchange", syncAddress);

  watch(locale, (value) => {
    document.documentElement.lang = value;
    document.title = copy.value.site.meta.title;
    document.querySelector('meta[name="description"]').content = copy.value.site.meta.description;
    const canonical = document.querySelector('link[rel="canonical"]');
    if (canonical) canonical.href = new URL(LOCALE_PATHS[value], canonical.href).href;
  }, { immediate: true });
});

onBeforeUnmount(() => {
  window.removeEventListener("popstate", syncAddress);
  window.removeEventListener("hashchange", syncAddress);
});
</script>
