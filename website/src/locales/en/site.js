export default {
  meta: {
    title: "AiCode Official Website — Open-source AI Coding Agent for Android",
    description: "The official website of AiCode, a general-purpose, open-source AI Coding Agent for Android. Includes a full Alpine Linux container and multi-tab terminals, with local development, remote SSH, visual Git, MCP and support for multiple AI providers."
  },
  nav: {
    logoAlt: "AiCode logo",
    label: "Main navigation",
    links: {
      preview: "Preview",
      features: "Features",
      download: "Quick start",
      sponsors: "Sponsors & recommendations",
      docs: "Docs (Chinese)",
      changelog: "Changelog"
    },
    lightMode: "Switch to light mode",
    darkMode: "Switch to dark mode",
    github: "GitHub repository",
    openMenu: "Open navigation menu",
    closeMenu: "Close navigation menu",
    language: {
      desktop: "中文",
      mobile: "中文",
      label: "Switch to Chinese",
      title: "Switch to Chinese"
    }
  },
  hero: {
    subtitle: "A general-purpose AI Coding Agent for Android, bringing a complete Linux development workspace to your phone and tablet",
    download: "Download APK for free",
    docs: "Read docs (Chinese)"
  },
  features: {
    title: "Features",
    subtitle: "An AI Agent and development environment built for real software development on mobile",
    items: {
      agent: {
        title: "AI Agent & built-in browser",
        desc: "Built-in file reading, writing and editing, Shell execution, background terminals, code and web search, image recognition, task lists and a multi-tab WebView browser. Spawn sub-agents with independent context for parallel background research."
      },
      modes: {
        title: "Three modes & checkpoint undo",
        desc: "BUILD for regular development, read-only PLAN with write operations blocked at the tool layer, and AUTO with all actions allowed without approval. The Agent automatically snapshots files before editing code, so you can roll back code, the conversation or both in one click."
      },
      terminal: {
        title: "Built-in terminal & Linux containers",
        desc: "Local Linux containers powered by Termux and PRoot, with a built-in Alpine image, custom rootfs imports and host directory mounts. Terminals support multiple tabs and persistent background sessions."
      },
      remote: {
        title: "Remote SSH & Shizuku backends",
        desc: "Use a remote SSH server as the execution backend, with commands, files and terminals operating directly on remote projects. Shizuku also lets you run shell and adb debugging commands on the host Android system."
      },
      editor: {
        title: "Code editor, Git & sync",
        desc: "Open files from an indented tree in a full-screen editor with syntax highlighting, or use a side-by-side layout on tablets. Visual Git tools cover status, branches, commit history and diffs, alongside SFTP / FTP sync and a built-in FTP server."
      },
      models: {
        title: "Model-agnostic, MCP, Skills & memory",
        desc: "Compatible with OpenAI / Anthropic / Gemini protocols and custom providers, with automatic rotation across multiple API Keys and Token cost tracking. Connect local or remote MCP servers, use global or project-level Skills, and keep long-term memory."
      }
    }
  },
  download: {
    title: "Quick start",
    subtitle: "Start developing in three steps, with no computer or extra setup required.",
    android: "Android 8.0+",
    api: "(API 26)",
    steps: {
      install: {
        title: "Download and install the APK",
        desc: "Choose the slim <code>armsolo</code> package for a physical device, <code>x86solo</code> for an emulator, or <code>universal</code> for both."
      },
      provider: {
        title: "Set up an AI provider",
        desc: "In Settings → AI providers, choose a built-in preset or a custom endpoint, enter your own API Key and enable a model."
      },
      environment: {
        title: "Choose an environment and start chatting",
        desc: "The built-in Alpine container is used by default. You can also switch to a remote SSH server in Containers & images, then start a new chat to begin development."
      }
    },
    packageTitle: "Get the latest APK",
    packageDescription: "Automatically built and published with GitHub Actions. A mirror is also available on the Chinese docs site.",
    github: "Download from GitHub",
    mirror: "Docs mirror (Chinese)"
  },
  sponsors: {
    title: "Sponsors & recommendations",
    subtitle: "Thanks to RainYun for sponsoring our servers. Recommended services are listed below as well.",
    items: {
      rainyun: {
        name: "RainYun",
        tag: "Server sponsor",
        desc: "A cloud provider based in China offering cloud servers and game hosting, with one-click setup for preinstalled Minecraft and other game servers, plus bare-metal servers and object storage. New-user discounts are available.",
        linkText: "Visit RainYun"
      },
      qiniu: {
        name: "Qiniu AI",
        tag: "Referral link",
        desc: "New users receive 3 million Tokens valid for 2 years. Supports 50+ popular AI models and connects directly to AiCode.",
        linkText: "Learn more"
      }
    }
  },
  cta: {
    title: "Start your next project on your phone",
    subtitle: "If AiCode helps you, give it a Star on GitHub.",
    download: "Download the latest version",
    github: "Star on GitHub"
  },
  footer: {
    backToTop: "AiCode, back to top",
    description: "A general-purpose AI Coding Agent for Android with a Linux development environment, supporting local work and remote SSH.",
    navigation: "Footer navigation",
    columns: {
      quickLinks: {
        title: "Quick links",
        links: {
          releases: "GitHub Releases",
          download: "APK downloads (Chinese)",
          guide: "User guide (Chinese)",
          quickStart: "Getting started (Chinese)"
        }
      },
      contribute: {
        title: "Feedback & contributions",
        links: {
          issues: "Issues & suggestions",
          pulls: "Contribute code"
        }
      },
      credits: {
        title: "Acknowledgments",
        links: {
          opencode: "OpenCode",
          termux: "Termux",
          kelivo: "Kelivo"
        }
      }
    },
    copyright: "© 2026 AiCode · GPL-3.0 open-source license",
    usage: "For learning, research and lawful software development only"
  }
};
