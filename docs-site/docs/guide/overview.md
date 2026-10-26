# 功能总览

本页作为使用手册的快速导览索引，整理了主界面核心工作流与设置页各功能入口对应的文档。如需直接观看操作演示，可先浏览[视频教程](/guide/video-tutorials)。

::: tip 版本说明
标了版本的条目（如「1.11.0 起」）表示该功能自该版本起引入。
:::

## 核心工作流与主界面

| 功能 | 说明 |
| --- | --- |
| 聊天交互与工作区 | 标题栏、侧边栏、工具栏、消息队列、工作区切换 → [文档](/guide/chat) |
| 运行模式（三种模式） | Build / Plan / Auto 的权限控制与场景 → [文档](/guide/modes) |
| 会话目标（Goal） | `/goal` 设定目标，AI 自主循环推进至达成或预算耗尽（1.13.0 起）→ [文档](/guide/goal) |
| 检查点与撤销 | AI 修改前自动快照，支持一键安全回滚 → [文档](/guide/checkpoint) |
| 终端交互 | 多标签会话、辅助按键栏、配色与字体设置 → [文档](/guide/terminal) |
| 内置浏览器 | WebView 浏览器，支持 AI 自动化操作网页（导航、点击、填表、截图等）（1.12.0 起）→ [文档](/guide/browser) |
| 文件浏览与代码编辑 | 缩进树形目录、语法高亮、内置代码编辑器（1.11.0 起），以及工作区文件在手机上的位置与访问 → [文档](/guide/files) |
| Git 版本管理 | 可视化状态管理、分支切换、提交历史、回退与删除（1.11.0 起）→ [文档](/guide/git) |
| 平板与大屏适配 | 响应式分栏、常驻侧边栏、并排双栏工作台（1.11.0 起）→ [文档](/guide/tablet) |

## 设置项与扩展能力速查

### 通用设置

| 入口 | 说明 |
| --- | --- |
| 偏好设置 | 全局偏好：无效模型清理、启动时会话、回车发送、首字与间隔超时、重试次数、自动压缩阈值、sendFile 单文件上限（1.12.0 起）→ [文档](/guide/general) |
| 外观主题、背景图、语言 | 主题明暗、预设配色、莫奈取色、背景图与多语言 → [文档](/guide/appearance) |
| 终端设置 | 终端配色、字体大小与光标样式 → [文档](/guide/terminal) |

### AI 配置与扩展

| 入口 | 说明 |
| --- | --- |
| AI 供应商 | 接入模型服务、管理模型列表、多 Key（1.11.0 起）、思考强度 → [文档](/guide/providers) |
| 模型思考模式 | 各模型真实支持的思考档位、能否关闭思考、怎么判断是否生效 → [文档](/advanced/reasoning-modes) |
| 默认与专用模型 | 新会话默认模型，以及识图、生图、标题总结的专用模型 → [文档](/guide/default-models) |
| MCP 服务器 | 接入外部工具，全局与项目两级配置 → [文档](/guide/mcp) |
| 技能 | 按需加载的专项能力扩展包 → [文档](/guide/skills) |
| 子代理 | 派生独立会话并行执行任务，可在设置内新建、编辑与启停，支持自定义模型与工具集（1.11.0 起）→ [文档](/guide/subagent) |
| 插件 | 导入 `.zip` 插件包，一次为 AI 增加若干技能和子代理，统一启停、授权与卸载（预览功能）→ [文档](/guide/plugins) |
| Shizuku 工具 | AI 的 `Shizuku` 工具：用 Shizuku 执行系统命令（1.12.0 起）→ [文档](/guide/shizuku) |
| 自定义提示词 | 覆盖与定制 AI 系统的提示词片段 → [文档](/guide/custom-prompts) |
| 记忆与项目规则 | 跨会话长期记忆，以及 AGENTS.md / CLAUDE.md 项目规则 → [文档](/guide/memory) |

### 运行环境

| 入口 | 说明 |
| --- | --- |
| 容器与镜像 | 本地 Linux 容器、自定义镜像、挂载手机目录、远程 SSH 后端 → [文档](/guide/container) |
| 网络代理 | 全局代理与供应商级代理（1.11.0 起）→ [文档](/guide/proxy) |
| 连接与同步 | SFTP / FTP 通道、工作区同步、内置 FTP 服务端 → [文档](/guide/sync) |

### 权限与后台

| 入口 | 说明 |
| --- | --- |
| 工具授权 | AI 调用工具的授权规则 → [文档](/guide/permissions) |
| 软件权限 | 安装未知应用、存储、电池优化、自启动、Shizuku 等系统权限 → [文档](/guide/app-permissions) |
| 后台运行 | 后台保活、屏幕常亮、AI 完成通知、实时通知、授权审批通知 → [文档](/guide/background-run) |

### 数据与诊断

| 入口 | 说明 |
| --- | --- |
| Token 统计 | 用量、费用估算、调用明细 → [文档](/guide/token-stats) |
| 存储空间 | 按类别看聊天、容器、工作区等占用，并清理临时数据（1.12.0 起）→ [文档](/guide/storage) |
| 备份与还原 | 加密导出导入配置与工作区 → [文档](/guide/backup) |
| 日志 | 查看运行日志、崩溃报告 → [文档](/guide/logs) |
| 常见错误提示 | 重试气泡分类含义、常见 HTTP 错误码与排查 → [文档](/guide/error-codes) |

### 帮助与关于

| 入口 | 说明 |
| --- | --- |
| 关于 | 版本信息、检查更新与首次启动确认流程 → [文档](/guide/about) |
| 用户协议 | 使用规则、AI 与操作风险、第三方费用及开源权利 → [文档](/terms) |
| 隐私政策 | 数据保存、联网传输、日志、备份与删除 → [文档](/privacy) |

## 进阶教程

| 主题 | 说明 |
| --- | --- |
| 在容器中编译 Android 应用 | 搭 JDK 与 Android SDK，从源码出 APK → [文档](/advanced/build-android-app) |
| 在容器中编译 Flutter 应用 | 装 JDK、Android SDK 与 Flutter SDK，构建 Flutter debug APK → [文档](/advanced/build-flutter-app) |
| 在容器中构建 Flutter release APK | 用 qemu 转译补齐 AOT 编译器，本地构建 Flutter release 包 → [文档](/advanced/build-flutter-release) |
| 安装 Playwright 浏览器自动化 | 容器内装 Chromium 并接入 Playwright MCP，让 AI 操作网页 → [文档](/advanced/playwright-mcp) |
| 用 adb 无线调试其他设备 | 在容器里用 adb 连接另一台手机或本机，安装应用、抓日志、截屏 → [文档](/advanced/adb-wireless-debug) |
| 自定义面板 | 用脚本在输入框上方画余额或用量卡片 → [文档](/advanced/dashboard-cards) |
| 开发插件 | 用 `plugin.json` 和 Markdown 文件打包技能与子代理，权限声明、目录结构与导入排错 → [文档](/advanced/plugin-development) |
