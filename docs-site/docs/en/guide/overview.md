# Feature Overview

This page is a quick index of the user manual, mapping the main UI workflows and settings entries to their documentation.

::: tip Version notes
Entries marked with a version (e.g. "since 1.11.0") were introduced in that version.
:::

## Core Workflows & Main UI

| Feature | Description |
| --- | --- |
| Chat & workspace | Title bar, sidebar, toolbar, message queue, workspace switching |
| Modes (three modes) | Permission control and use cases for Build / Plan / Auto |
| Checkpoints & undo | Automatic snapshot before AI edits, one-tap safe rollback |
| Terminal | Multi-tab sessions, auxiliary key bar, color and font settings |
| File browsing & code editing | Indented tree view, syntax highlighting, built-in code editor (since 1.11.0) |
| Git version management | Visual status management, branch switching, commit history, revert and delete (since 1.11.0) |
| Tablet & large screen | Responsive split panes, persistent sidebar, side-by-side workbench (since 1.11.0) |

## Settings

### AI Configuration & Extensions

| Entry | Description |
| --- | --- |
| [AI Providers](/en/guide/providers) | Connect model services, manage model lists, multi-key mode (since 1.11.0), thinking effort |
| Default & dedicated models | Default model for new sessions, plus dedicated models for image recognition, image generation and title summarization |
| MCP servers | Connect external tools, global and project-level configuration |
| Skills | On-demand specialist extension packs |
| Subagents | Spawn independent sessions to run tasks in parallel; create, edit, enable and disable in settings, with custom models and tool sets (since 1.11.0) |
| Custom prompts | Override and customize AI system prompt fragments |
| Memory & project rules | Cross-session long-term memory, plus AGENTS.md / CLAUDE.md project rules |

### Runtime

| Entry | Description |
| --- | --- |
| Container & images | Local Linux container, custom images, mounting phone directories, remote SSH backend |
| Network proxy | Global proxy and provider-level proxy (since 1.11.0) |
| Connection & sync | SFTP / FTP channels, workspace sync, built-in FTP server |

### Tools & Permissions

| Entry | Description |
| --- | --- |
| Tool authorization | Rules for which tools the AI may call |
| System permissions | Keep-alive, screen always on, notifications, storage, battery optimization |
| Logs | View runtime logs, crash reports, access the app private directory |

### Appearance & Language

| Entry | Description |
| --- | --- |
| Theme, wallpaper, language | Light/dark theme, preset colors, Monet color extraction, wallpaper, multi-language |

### System

| Entry | Description |
| --- | --- |
| Token stats | Usage, cost estimation, call details |
| Storage | Per-category usage of chats, containers, workspaces, and cleanup of temporary data |
| Backup & restore | Encrypted export/import of configuration and workspaces |
| About | Version info and update checks |

## Advanced Tutorials

| Topic | Description |
| --- | --- |
| Building Android apps in the container | Set up JDK and Android SDK, build an APK from source |
| Installing Playwright browser automation | Install Chromium inside the container and connect Playwright MCP so the AI can operate web pages |
| Custom dashboard cards | Draw balance or usage cards above the input box with scripts |