# AiCode 官网

AiCode 官方网站，基于 Vue 3 + Vite 5，构建时预渲染为静态 HTML（SSG）。

## 开发

```bash
npm install
npm run dev      # http://localhost:5173
```

## 构建

```bash
npm run build
```

构建分三步：

1. `vite build` 产出客户端资源到 `dist/`
2. `vite build --ssr src/entry-server.js` 产出 `entry-server.js` 到 `.ssr/`
3. `scripts/prerender.mjs` 把服务端渲染结果注入 `dist/index.html`

`dist/` 即可直接部署，页面首屏是完整 HTML，不依赖客户端渲染。

## 部署到 Vercel

在 Vercel 项目里设置：

- Root Directory：`website`
- Framework Preset：`Vite`
- Build Command：`npm run build`
- Output Directory：`dist`

`vercel.json` 已包含上述构建配置。

## 目录结构

```
src/
  App.vue                 # 页面骨架
  main.js                 # 客户端入口（有预渲染内容时走 hydration）
  entry-server.js         # 构建期 SSR 入口
  styles.css              # 全站样式（全局复用）
  components/
    SiteNav.vue           # 顶部导航、主题切换、移动端菜单
    HeroSection.vue       # Hero 区
    AppReplica.vue        # 1:1 交互式 App 演示（抽屉 / 子页 / 聊天）
    GitScreen.vue         # Git 子页（静态标记）
    TerminalScreen.vue    # 终端子页（静态标记）
    BrowserScreen.vue     # 浏览器子页（静态标记）
    ChatMessage.vue       # 聊天气泡
    FeaturesSection.vue   # 功能特性（数据驱动）
    DownloadSection.vue   # 快速开始
    SponsorsSection.vue   # 赞助商（数据驱动）
    CtaSection.vue        # 底部 CTA
    SiteFooter.vue        # 页脚
  composables/useTheme.js # 明暗主题与 localStorage
  directives/reveal.js    # 滚动入场动画指令
public/assets/            # 图片等静态资源
```
