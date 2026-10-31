<template>
  <section class="hero" id="preview">
    <div class="hero__bg-grad"></div>
    <div class="hero__bg-grid bg-grid"></div>

    <div class="container">
      <div class="hero__intro reveal" v-reveal>
        <div class="hero__title-row">
          <img src="/assets/logo.png" alt="AiCode" class="hero__app-logo" />
          <h1 class="hero__title">AiCode</h1>
        </div>

        <p class="hero__subtitle">
          Android 上的通用 AI Coding Agent，把完整的 Linux 开发工作台装进手机与平板
        </p>

        <div class="hero__actions">
          <a
            href="https://github.com/jieapi/aicode/releases/latest"
            target="_blank"
            rel="noopener noreferrer"
            class="btn btn--primary btn--lg"
          >
            <i class="ri-download-2-line"></i>
            <span>免费下载 APK</span>
          </a>
          <a
            href="https://aicode.murk.top"
            target="_blank"
            rel="noopener noreferrer"
            class="btn btn--outline btn--lg btn--arrow"
          >
            <span>查看在线文档</span>
            <i class="ri-arrow-right-line"></i>
          </a>
        </div>
      </div>

      <AppReplica />
    </div>
  </section>
</template>

<script setup>
import AppReplica from "./AppReplica.vue";
</script>
