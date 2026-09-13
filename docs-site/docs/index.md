---
layout: home
title: AiCode - 手机端 AI 编程工具与内置 Linux 终端
titleTemplate: false
description: AiCode 是一款开源的 Android 端 AI 编程工具，无需电脑，在手机上就能完成完整开发流程。内置 Linux 容器与终端，AI Agent 可直接读写代码并执行构建；支持 Git 版本控制、MCP 协议与远程 SSH 开发。
head:
  - - meta
    - name: keywords
      content: AiCode, Android AI 编程, 手机编程, 移动端开发, Linux 终端, PRoot, Termux, AI Agent, MCP 协议, 手机代码编辑器, Git 客户端, 移动工作站, 手机跑终端, 远程 SSH 开发

hero:
  name: AiCode
  text: 手机上的 AI 编程工具与移动工作站
  tagline: 无需电脑，在手机上即可完成从写代码、调试到提交的完整开发流程。内置 Linux 容器与终端，AI Agent 可自主读写文件、执行 Shell 命令与运行构建，还支持 Git、MCP、远程 SSH 与完整模型生态。
  image:
    src: /logo.png
    alt: AiCode
  actions:
    - theme: brand
      text: 快速上手
      link: /guide/quick-start
    - theme: alt
      text: 下载 APK
      link: https://aicode.murk.top/download
    - theme: alt
      text: GitHub
      link: https://github.com/jieapi/aicode

features:
  - title: AI Agent
    details: 支持 OpenAI / Anthropic / Gemini 兼容协议，多提供商随时切换；内置文件读写、Shell 执行、后台终端、搜索与图片识别等工具，流式输出，长对话自动压缩上下文。
  - title: 内置终端与 Linux 容器
    details: 基于 Termux 组件与 PRoot 的本地容器，内置 Alpine 镜像，可导入自定义 rootfs、挂载宿主目录，终端支持后台常驻。
  - title: 远程 SSH 模式
    details: 以远程服务器作为执行后端，命令走 exec channel、文件走 SFTP、终端走 shell channel，在手机上直接操作远程项目。
  - title: 检查点与撤销
    details: AI 改代码前自动记录文件快照，对话中可一键回滚，支持只恢复代码、只恢复对话，或两者同时恢复。
  - title: MCP、技能与记忆
    details: 可连接本地 stdio 与远程 HTTP 的 MCP 服务器动态扩展工具；支持全局与项目级技能和长期记忆，让 AI 跨会话复用经验。
  - title: Git 与工作区同步
    details: 内置状态、分支、提交、差异与标签管理；支持 SFTP / FTP 同步，并自带 FTP 服务端方便电脑侧管理文件。
---
