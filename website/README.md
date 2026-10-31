# AiCode 官网

AiCode 官方网站，基于 Vue 3 + Vite 5，构建时预渲染为静态 HTML（SSG）。

## 开发

```bash
npm install
npm run dev      # http://localhost:5173
```

中文默认地址为 `/`，英文独立地址为 `/en/`（含末尾斜杠）；两种语言都可以直接访问，也可通过顶部语言入口切换。

## 构建

```bash
npm run build
```

构建分三步：

1. `vite build` 产出客户端资源到 `dist/`
2. `vite build --ssr src/entry-server.js` 产出 `entry-server.js` 到 `.ssr/`
3. `scripts/prerender.mjs` 使用同一份未注入的 Vite HTML 模板，分别渲染中文 `/` 和英文 `/en/`，生成 `dist/index.html` 与 `dist/en/index.html`

`dist/` 即可直接部署。两份页面的 HTML 语言、标题、描述与正文都与各自语言一致，不依赖客户端 JavaScript 才能显示译文；图片、脚本与样式继续共用根路径下的资源。

构建时按以下顺序读取官网站点源，用于生成两页各自的 canonical，以及包含中文、英文和指向中文首页的 `x-default` 的双向 hreflang：

1. 优先使用环境变量 `SITE_URL`：填写实际官网的完整 `http://` 或 `https://` 站点源，可带根目录末尾的 `/`，不得包含其他路径、查询参数、片段或用户名密码。显式填写不合法时构建会报错退出。
2. 未设置 `SITE_URL` 时，使用 Vercel 提供的 `VERCEL_PROJECT_PRODUCTION_URL` 稳定生产域名，自动补上 `https://`。不使用随机预览地址 `VERCEL_URL`，也不要把文档站地址当作官网站点源。

本地未配置上述两项时，构建仍会成功并生成两种语言页面与语言切换链接，但会打印一条缺少官网域名的警告，不输出 canonical 和 hreflang 绝对地址，以免生成错误的 SEO 链接。正式部署前应配置站点源并重新构建。

## 部署到 Vercel

在 Vercel 项目里设置：

- Root Directory：`website`
- Framework Preset：`Vite`
- Build Command：`npm run build`
- Output Directory：`dist`

`vercel.json` 已包含上述构建配置，并统一使用末尾斜杠，英文正式地址为 `/en/`。

Vercel 默认可通过系统环境变量 `VERCEL_PROJECT_PRODUCTION_URL` 提供稳定生产域名，无需填写随机预览域名；若项目关闭了系统环境变量自动暴露，请启用它或手动配置 `SITE_URL`。使用自定义官网域名时，在项目的环境变量中设置 `SITE_URL` 覆盖自动域名，并重新部署，让中英文页面的 SEO 链接统一指向该正式官网。

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
