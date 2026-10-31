<template>
  <section class="section-y" id="sponsors">
    <div class="container">
      <div class="section-header reveal" v-reveal>
        <h2 class="section-header__title">赞助商与合作伙伴</h2>
        <p class="section-header__subtitle">感谢以下服务商对 AiCode 项目的支持</p>
      </div>

      <div class="sponsors-grid">
        <a
          v-for="sponsor in sponsors"
          :key="sponsor.name"
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
const sponsors = [
  {
    name: "雨云 RainYun",
    tag: "服务器赞助商",
    href: "https://www.rainyun.com/logins_",
    desc: "国产云服务商，主营云服务器与游戏云（Minecraft 等预装服务端一键开服），兼有裸金属物理机与对象存储，享新用户优惠。",
    linkText: "访问雨云",
    delay: "0.05s"
  },
  {
    name: "七牛云 AI",
    tag: "合作伙伴",
    href: "https://s.qiniu.com/vUryau",
    desc: "新用户注册赠 300 万 Token（2 年有效），支持 50+ 热门大模型，可直接接入 AiCode 使用。",
    linkText: "了解详情",
    delay: "0.1s"
  }
];
</script>
