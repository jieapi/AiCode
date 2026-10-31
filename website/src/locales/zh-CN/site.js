export default {
  meta: {
    title: "AiCode 官方网站 — Android 上的开源 AI Coding Agent",
    description: "AiCode 官方网站。运行在 Android 上的通用开源 AI Coding Agent，内置完整 Alpine Linux 容器与多标签终端，支持本地开发与远程 SSH、可视化 Git、MCP 协议与多供应商模型。"
  },
  nav: {
    logoAlt: "AiCode 标志",
    label: "主导航",
    links: {
      preview: "界面预览",
      features: "功能特性",
      download: "快速开始",
      sponsors: "赞助与推荐",
      docs: "在线文档",
      changelog: "更新日志"
    },
    lightMode: "切换浅色模式",
    darkMode: "切换深色模式",
    github: "GitHub 仓库",
    openMenu: "打开导航菜单",
    closeMenu: "关闭导航菜单",
    language: {
      desktop: "EN",
      mobile: "English",
      label: "切换为英文",
      title: "切换为英文"
    }
  },
  hero: {
    subtitle: "Android 上的通用 AI Coding Agent，把完整的 Linux 开发工作台装进手机与平板",
    download: "免费下载 APK",
    docs: "查看在线文档"
  },
  features: {
    title: "功能特性",
    subtitle: "围绕移动端真实软件开发打造的 AI Agent 与开发环境",
    items: {
      agent: {
        title: "AI Agent 与内置浏览器",
        desc: "内置文件读写与编辑、Shell 执行、后台终端、代码与网页搜索、图片识别、待办清单与多标签 WebView 浏览器；支持派生独立上下文的子代理在后台并行调研。"
      },
      modes: {
        title: "三种模式与检查点撤销",
        desc: "提供 BUILD 正常开发、PLAN 只读规划（工具层拦截写操作）、AUTO 免授权全放行三种模式；Agent 改代码前自动记录文件快照，对话中可一键回滚代码、对话或两者。"
      },
      terminal: {
        title: "内置终端与 Linux 容器",
        desc: "基于 Termux 与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端支持多标签与后台常驻运行。"
      },
      remote: {
        title: "远程 SSH 与 Shizuku 后端",
        desc: "可把远程 SSH 服务器作为执行后端，命令、文件与终端直接作用于远端项目；也支持借助 Shizuku 在宿主 Android 系统上执行 shell 与 adb 调试命令。"
      },
      editor: {
        title: "代码编辑器、Git 与同步",
        desc: "缩进式文件树点开即进全屏语法高亮编辑器，支持平板双栏并排显示；内置可视化 Git 管理状态、分支、提交历史与差异，支持 SFTP / FTP 同步与内置 FTP 服务端。"
      },
      models: {
        title: "模型无关、MCP 与技能记忆",
        desc: "兼容 OpenAI / Anthropic / Gemini 协议与自定义供应商，支持多 Key 自动轮换与 Token 费用统计；支持连接本地与远程 MCP 服务器、全局/项目级技能（Skills）与长期记忆。"
      }
    }
  },
  download: {
    title: "快速开始",
    subtitle: "无需电脑或额外环境准备，三步开启开发。",
    android: "Android 8.0+",
    api: "（API 26）",
    steps: {
      install: {
        title: "下载安装 APK",
        desc: "真机推荐 <code>armsolo</code> 精简包；模拟器选 <code>x86solo</code>，或选 <code>universal</code> 通用包。"
      },
      provider: {
        title: "配置 AI 供应商",
        desc: "在「设置 → AI 供应商」选择内置预设或自定义端点，填入自己的 API Key 并启用模型。"
      },
      environment: {
        title: "选择环境，开始对话",
        desc: "默认使用内置 Alpine 本地容器；也可在「容器与镜像」切换远程 SSH 服务器，新建会话即可开始开发。"
      }
    },
    packageTitle: "获取最新安装包",
    packageDescription: "由 GitHub Actions 自动构建发布，也可通过文档镜像下载。",
    github: "GitHub 下载",
    mirror: "文档镜像下载"
  },
  sponsors: {
    title: "赞助与推荐",
    subtitle: "感谢雨云提供服务器赞助，以下同时列出推荐服务。",
    items: {
      rainyun: {
        name: "雨云 RainYun",
        tag: "服务器赞助商",
        desc: "国产云服务商，主营云服务器与游戏云（Minecraft 等预装服务端一键开服），兼有裸金属物理机与对象存储，享新用户优惠。",
        linkText: "访问雨云"
      },
      qiniu: {
        name: "七牛云 AI",
        tag: "邀请推广",
        desc: "新用户注册赠 300 万 Token（2 年有效），支持 50+ 热门大模型，可直接接入 AiCode 使用。",
        linkText: "了解详情"
      }
    }
  },
  cta: {
    title: "在手机上开启你的下一个项目",
    subtitle: "如果 AiCode 对你有帮助，欢迎到 GitHub 点亮 Star。",
    download: "下载最新版本",
    github: "GitHub Star"
  },
  footer: {
    backToTop: "AiCode，返回顶部",
    description: "Android 上的通用 AI Coding Agent，提供 Linux 开发环境，支持本地与远程 SSH。",
    navigation: "页脚导航",
    columns: {
      quickLinks: {
        title: "快速入口",
        links: {
          releases: "GitHub Releases",
          download: "安装包下载",
          guide: "使用指南",
          quickStart: "快速上手"
        }
      },
      contribute: {
        title: "反馈与贡献",
        links: {
          issues: "问题与建议",
          pulls: "贡献代码"
        }
      },
      credits: {
        title: "致谢项目",
        links: {
          opencode: "OpenCode",
          termux: "Termux",
          kelivo: "Kelivo"
        }
      }
    },
    copyright: "© 2026 AiCode · GPL-3.0 开源许可",
    usage: "仅供学习、研究与合法的软件开发用途"
  }
};
