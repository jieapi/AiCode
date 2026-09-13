<p align="center">
  <h1 align="center">AiCode</h1>
  <p align="center">
    AI-powered coding assistant for Android · Built-in Linux terminal · AI Agent & subagents · Code editor · MCP · Git integration
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose UI" />
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="Min SDK 26 (Android 8.0)" />
  <a href="https://github.com/jieapi/aicode/releases"><img src="https://img.shields.io/github/v/release/jieapi/aicode?display_name=tag&include_prereleases" alt="Latest Release" /></a>
  <a href="https://github.com/jieapi/aicode/releases"><img src="https://img.shields.io/github/downloads/jieapi/aicode/total" alt="Total Downloads" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="AiCode home - AI chat interface with code generation and Markdown rendering" width="270"/></td>
      <td align="center"><img src="docs/screenshots/git.png" alt="AiCode Git integration - visual commit history and branch management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Home · AI Chat</td>
      <td align="center">Git · Commit History</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/container.png" alt="AiCode container settings - container image management" width="270"/></td>
      <td align="center"><img src="docs/screenshots/models.png" alt="AiCode model list - multi-provider model management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Container · Image Manager</td>
      <td align="center">Models · Provider List</td>
    </tr>
  </table>
</p>

---

## Overview

AiCode is an AI-powered coding tool that runs on Android, letting you code entirely on your phone without a computer. It bundles an Alpine Linux container and terminal, packing a full Linux development environment into your phone: the AI agent can read and write files, run shell commands, and run build tools, so writing code, debugging and building all happen right on the phone.

Terminal, AI agent, file tree with a code editor, visual Git panel and background subagents are all built in, giving you a complete development workflow on mobile. When needed, a remote SSH server can act as the execution backend, turning your phone into a mobile workstation for remote projects; on large screens it automatically switches to a side-by-side two-pane workbench.

## Advertisement

| Icon | Description |
|------|-------------|
| <img src="https://opencode.ai/favicon-96x96-v3.png" width="24" alt="OpenCode" /> | **[OpenCode Go](https://opencode.ai/go?ref=8Q5GA5B1NY)** — Low-cost subscription with generous limits and reliable access to the most capable open-source models |
| <img src="https://www.rainyun.com/favicon.ico" width="24" alt="RainYun" /> | **[RainYun](https://www.rainyun.com/logins_)** — Chinese cloud provider specializing in VPS and game hosting (one-click Minecraft and other game servers), plus bare-metal machines and object storage; discounts for new users |

## Features

### AI & Agent

- **AI Agent** — Compatible with OpenAI / Anthropic / Gemini protocols; switch between providers, rotate multiple keys of one provider automatically, and tune reasoning effort; built-in tools for file read/write/edit, shell execution, background terminal, code & web search, image recognition, todo lists, asking the user, and more; streaming output with live Markdown rendering and automatic context compression for long conversations
- **Parallel subagents** — The main session can spawn subagents with their own isolated context to research, review or compare approaches in the background without blocking your current chat; a read-only **Explore** subagent is built in, and you can define your own with a custom model, tool set and prompt — all listed under their parent session in the sidebar
- **Three run modes** — BUILD for normal development, PLAN to block every write operation at the tool layer for read-only planning, AUTO to approve everything without prompts — pick the permission scope you trust
- **Checkpoints & Undo** — File snapshots are recorded before the agent modifies code; one-tap rollback from the conversation, restoring code, chat history, or both
- **Skills & Auto Memory** — Global/project-level skills and long-term memory let the AI reuse experience and project conventions across sessions
- **MCP Protocol** — Connect to local (stdio) or remote (HTTP) MCP servers to dynamically extend AI tool capabilities
- **Tool permissions & custom prompts** — Per-tool authorization rules; system prompts can be overridden by the user and survive app upgrades

### Development Environment

- **Built-in Terminal & Container** — A local Linux container built on Termux components and PRoot with a built-in Alpine image; supports importing custom rootfs images and mounting host directories; multi-tab terminals that can stay alive in the background
- **Remote SSH Mode** — Use a remote server as the execution backend: commands via exec channel, files via SFTP, terminal via shell channel — operate on remote projects directly from your phone
- **File tree & code editor** — An inline indented file tree; tap a file for the full-screen editor with syntax highlighting for mainstream languages (Kotlin / Java / Python / JS·TS / Go / Rust / C·C++ / PHP and more), VS Code color schemes that follow the app theme, Markdown preview, undo/redo and a symbol shortcut bar; `file:line` links in AI replies open the file and jump to that line, in both local and remote SSH workspaces
- **Git Integration** — Visual management of status, branches, commit history, diffs and tags, with staging/discarding changes plus sign-off and credential configuration
- **Workspace Sync** — SFTP / FTP synchronization with a built-in FTP server for desktop file management

### Experience

- **Tablet & large screen** — Layout responds to window width: a persistent sidebar on wide screens with code or terminal open next to the chat, falling back to a single pane when the window shrinks
- **Token stats** — Usage and cost estimates per provider and model, with a drill-down into individual calls
- **Appearance & language** — Light/dark themes, preset color schemes, Material You colors, custom background images, and a bilingual (Chinese/English) UI
- **Network proxy** — Configure a global proxy and per-provider proxies separately
- **Backup & Restore** — Encrypted export/import of provider configs, credentials, chat history and workspace files

## Getting Started

| Item | Description |
|------|-------------|
| System requirements | Android 8.0+ (API 26), arm64-v8a / x86_64 |
| Download | [GitHub Releases](https://github.com/jieapi/aicode/releases/latest): pick `armsolo` for real devices, `x86solo` for emulators, `universal` for both |
| Quick start | Settings → AI Providers to add a model → Container & Image to pick local or SSH → new session and chat |
| Changelog | [Releases](https://github.com/jieapi/aicode/releases) (all versions & notes) |
| User guide | [Online docs](https://aicode.murk.top): quick start, feature manual and advanced guides (same content as the in-app docs) |

## Star

If AiCode is helpful to you, give it a [Star](https://github.com/jieapi/aicode) — it helps more developers discover the project.

## Feedback & Contribution

- **QQ group**: join the [AiCode QQ group](https://qm.qq.com/q/ByvqODJdIs) (group number: 1107110698) to chat with other users and share feedback
- **Bug reports**: open an [Issue](https://github.com/jieapi/aicode/issues) with reproduction steps, device model and OS version
- **Feature requests**: discuss your ideas in [Issues](https://github.com/jieapi/aicode/issues) first
- **Contributing**: pull requests are welcome via [Pull Requests](https://github.com/jieapi/aicode/pulls)

## Acknowledgements

- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference

## License

This project is licensed under [GPL-3.0](LICENSE).
