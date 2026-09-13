import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  srcDir: './docs',
  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-Hans'
    },
    en: {
      label: 'English',
      lang: 'en',
      title: 'AiCode',
      titleTemplate: ':title | AiCode - AI Coding Tool & Terminal on Android',
      description: 'AiCode is an open-source AI coding and mobile workstation tool for Android. It ships with a built-in Linux container and terminal, letting the AI Agent read and write files, run shell commands and builds on its own — with deep integration of the MCP protocol, Git version control and remote SSH development.',
      head: [
        ['meta', { name: 'keywords', content: 'AiCode, Android AI coding, mobile development, Linux terminal, PRoot, Termux, AI Agent, MCP, code editor, Git client, mobile workstation, remote SSH' }],
        ['meta', { property: 'og:title', content: 'AiCode - AI Coding Tool & Linux Terminal on Android' }],
        ['meta', { property: 'og:description', content: 'An open-source AI coding tool for Android with a built-in Linux container and terminal. AI Agent edits code, runs commands and builds; supports MCP tools, Git and remote SSH.' }],
        ['meta', { name: 'twitter:title', content: 'AiCode - AI Coding Tool & Linux Terminal on Android' }],
        ['meta', { name: 'twitter:description', content: 'An open-source AI coding tool for Android with a built-in Linux container and terminal. AI Agent edits code, runs commands and builds; supports MCP tools, Git and remote SSH.' }]
      ],
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/en/guide/quick-start', activeMatch: '/en/guide/' },
          { text: 'Download', link: 'https://github.com/jieapi/aicode/releases/latest' }
        ],
        sidebar: {
          '/en/guide/': [
            {
              text: 'Getting Started',
              items: [
                { text: 'Quick Start', link: '/en/guide/quick-start' },
                { text: 'Feature Overview', link: '/en/guide/overview' },
                { text: 'Free Providers', link: '/en/guide/free-providers' }
              ]
            },
            {
              text: 'Models & Usage',
              items: [
                { text: 'AI Providers & Models', link: '/en/guide/providers' }
              ]
            }
          ]
        },
        outline: { label: 'On this page', level: [2, 3] },
        docFooter: { prev: 'Previous', next: 'Next' },
        lastUpdatedText: 'Last updated',
        darkModeSwitchLabel: 'Theme',
        lightModeSwitchTitle: 'Switch to light mode',
        darkModeSwitchTitle: 'Switch to dark mode',
        sidebarMenuLabel: 'Menu',
        returnToTopLabel: 'Back to top',
        editLink: {
          pattern: 'https://github.com/jieapi/aicode/edit/main/docs-site/docs/:path',
          text: 'Edit this page on GitHub'
        },
        footer: {
          message: 'Open source under GPL-3.0',
          copyright: 'Copyright © 2026-present AiCode'
        }
      }
    }
  },
  title: 'AiCode',
  titleTemplate: ':title | AiCode - 手机端 AI 编程工具与终端',
  description: 'AiCode 是一款开源的 Android 端 AI 编程与移动工作站工具。内置 Linux 容器与终端环境，AI Agent 可自主读写文件、执行 Shell 命令与运行构建，深度集成 MCP 协议、Git 版本控制与远程 SSH 开发。',
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: 'https://aicode.murk.top'
  },
  head: [
    ['link', { rel: 'icon', href: '/logo.png', type: 'image/png' }],
    ['meta', { name: 'theme-color', content: '#3c8772' }],
    ['meta', { name: 'keywords', content: 'AiCode, Android AI 编程, 手机编程, 移动端开发, Linux 终端, PRoot, Termux, AI Agent, MCP 协议, 手机代码编辑器, Git 客户端, 移动工作站, 手机跑终端, 远程 SSH 开发' }],
    ['meta', { name: 'author', content: 'AiCode Team' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'AiCode' }],
    ['meta', { property: 'og:title', content: 'AiCode - 手机端 AI 编程工具与内置 Linux 终端' }],
    ['meta', { property: 'og:description', content: 'Android 端全功能 AI 编程工具：内置 Linux 容器与终端、AI Agent 自动修改代码与执行命令、MCP 工具扩展、Git 版本管理与远程 SSH 连接。' }],
    ['meta', { property: 'og:image', content: 'https://aicode.murk.top/logo.png' }],
    ['meta', { property: 'og:url', content: 'https://aicode.murk.top/' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'AiCode - 手机端 AI 编程工具与内置 Linux 终端' }],
    ['meta', { name: 'twitter:description', content: 'Android 端全功能 AI 编程工具：内置 Linux 容器与终端、AI Agent 自动修改代码与执行命令、MCP 工具扩展、Git 版本管理与远程 SSH 连接。' }],
    ['meta', { name: 'twitter:image', content: 'https://aicode.murk.top/logo.png' }]
  ],
  themeConfig: {
    nav: [
      { text: '使用手册', link: '/guide/quick-start', activeMatch: '/guide/' },
      { text: '进阶教程', link: '/advanced/build-android-app', activeMatch: '/advanced/' },
      { text: '下载', link: 'https://github.com/jieapi/aicode/releases/latest' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '快速上手', link: '/guide/quick-start' },
            { text: '功能总览', link: '/guide/overview' },
            { text: '视频教程', link: '/guide/video-tutorials' },
            { text: '免费提供商', link: '/guide/free-providers' },
          ]
        },
        {
          text: '核心工作流',
          items: [
            { text: '聊天界面导览', link: '/guide/chat' },
            { text: '三种模式（Build / Plan / Auto）', link: '/guide/modes' },
            { text: '检查点与撤销', link: '/guide/checkpoint' },
            { text: '终端', link: '/guide/terminal' },
            { text: '文件浏览与代码编辑', link: '/guide/files' },
            { text: 'Git 版本管理', link: '/guide/git' },
            { text: '平板与大屏适配', link: '/guide/tablet' }
          ]
        },
        {
          text: '模型与用量',
          items: [
            { text: 'AI 提供商与模型', link: '/guide/providers' },
            { text: '默认与专用模型', link: '/guide/default-models' },
            { text: 'Token 统计与费用', link: '/guide/token-stats' }
          ]
        },
        {
          text: '执行环境',
          items: [
            { text: '容器与镜像', link: '/guide/container' },
            { text: '远程 SSH 模式', link: '/guide/remote-ssh' },
            { text: '工作区同步', link: '/guide/sync' },
            { text: '网络代理', link: '/guide/proxy' }
          ]
        },
        {
          text: '扩展能力',
          items: [
            { text: 'MCP 服务器', link: '/guide/mcp' },
            { text: '技能', link: '/guide/skills' },
            { text: '子代理', link: '/guide/subagent' },
            { text: '自定义提示词', link: '/guide/custom-prompts' },
            { text: '记忆与项目规则', link: '/guide/memory' }
          ]
        },
        {
          text: '设置与维护',
          items: [
            { text: '工具授权', link: '/guide/permissions' },
            { text: '软件权限', link: '/guide/app-permissions' },
            { text: '外观与语言', link: '/guide/appearance' },
            { text: '常见错误提示', link: '/guide/error-codes' },
            { text: '日志与故障排查', link: '/guide/logs' },
            { text: '存储空间', link: '/guide/storage' },
            { text: '备份与还原', link: '/guide/backup' },
            { text: '关于与更新', link: '/guide/about' }
          ]
        }
      ],
      '/advanced/': [
        {
          text: '环境搭建',
          items: [
            { text: '在容器中编译 Android 应用', link: '/advanced/build-android-app' },
            { text: '在容器中编译 Flutter 应用', link: '/advanced/build-flutter-app' },
            { text: '安装 Playwright 浏览器自动化', link: '/advanced/playwright-mcp' }
          ]
        },
        {
          text: '扩展开发',
          items: [
            { text: '自定义面板', link: '/advanced/dashboard-cards' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/jieapi/aicode' }
    ],
    search: {
      provider: 'local'
    },
    outline: { label: '页面导航', level: [2, 3] },
    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdatedText: '最后更新于',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
    sidebarMenuLabel: '菜单',
    returnToTopLabel: '回到顶部',
    editLink: {
      pattern: 'https://github.com/jieapi/aicode/edit/main/docs-site/docs/:path',
      text: '在 GitHub 上编辑此页'
    },
    footer: {
      message: '基于 GPL-3.0 协议开源',
      copyright: 'Copyright © 2026-至今 AiCode'
    }
  },
  vite: {
    plugins: [
      {
        name: 'serve-raw-md',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url && req.url.startsWith('/md/')) {
              const rel = req.url.slice(4).split('?')[0].split('#')[0]
              const filePath = resolve(__dirname, '../docs', decodeURIComponent(rel))
              if (existsSync(filePath) && statSync(filePath).isFile()) {
                res.setHeader('Content-Type', 'text/plain; charset=utf-8')
                res.setHeader('Access-Control-Allow-Origin', '*')
                res.end(readFileSync(filePath, 'utf-8'))
                return
              }
            }
            next()
          })
        },
        configurePreviewServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url && req.url.startsWith('/md/')) {
              const rel = req.url.slice(4).split('?')[0].split('#')[0]
              const filePath = resolve(__dirname, 'dist/md', decodeURIComponent(rel))
              if (existsSync(filePath) && statSync(filePath).isFile()) {
                res.setHeader('Content-Type', 'text/plain; charset=utf-8')
                res.setHeader('Access-Control-Allow-Origin', '*')
                res.end(readFileSync(filePath, 'utf-8'))
                return
              }
            }
            next()
          })
        }
      }
    ]
  }
})
