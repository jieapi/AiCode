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
import SiteNav from "./components/SiteNav.vue";
import HeroSection from "./components/HeroSection.vue";
import FeaturesSection from "./components/FeaturesSection.vue";
import DownloadSection from "./components/DownloadSection.vue";
import SponsorsSection from "./components/SponsorsSection.vue";
import CtaSection from "./components/CtaSection.vue";
import SiteFooter from "./components/SiteFooter.vue";
</script>
