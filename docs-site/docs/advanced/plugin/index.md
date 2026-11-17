# 插件开发

插件通过 `.zip` 包向 AiCode 添加技能、子代理、AI 工具、页面、MCP、提供商、Hooks、执行后端、Agent 工作流、外部会话源和系统浮层，由使用者在「设置 → 插件」中统一启停与卸载。一个插件可以组合多项能力。

::: warning 预览功能
插件系统仍在快速演进。启用插件等同于信任其全部声明，安装前请确认来源；接口版本与清单限制见 [plugin.json 清单参考](/advanced/plugin/manifest)。
:::

## 推荐组合：TypeScript + Vue

| 部分 | 推荐方式 | 交付产物 |
| --- | --- | --- |
| 插件逻辑 | TypeScript + 插件 SDK | esbuild 生成 JavaScript 运行时入口 |
| 页面界面 | Vue 3 单文件组件 + TypeScript，优先使用宿主 `aicode-*` 组件 | Vite 生成 HTML、JavaScript 和 CSS |
| 项目起点 | 仓库中的 `plugins/template/` 独立模板 | 清单、运行时与页面一起打包 |

宿主运行构建后的 JavaScript 和 HTML，源码由开发者维护。仅提供技能、子代理或 MCP 配置时，可直接编写 Markdown / JSON，按需增加运行时和页面。

## 开发流程

| 阶段 | 阅读内容 |
| --- | --- |
| 跑通项目 | [快速开始](/advanced/plugin/quick-start)：创建模板项目、构建并侧载验证 |
| 编写功能 | [TypeScript + Vue 开发与构建](/advanced/plugin/source-build)：源码结构、SDK、页面构建与类型提示 |
| 交付插件 | [打包、导入与排错](/advanced/plugin/packaging)：发布文件、安装限制、日志与检查清单 |

清单字段按需查阅 [plugin.json 清单参考](/advanced/plugin/manifest)，具体能力按下表选择。

## 能力索引

| 目标 | 声明或接口 | 参考文档 |
| --- | --- | --- |
| 添加操作规范、审查角色或外部工具服务 | `skills`、`agents`、`mcp` | [技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp) |
| 让 AI 调用插件逻辑 | `tools` + `runtime` | [AI 工具与插件运行时](/advanced/plugin/tools-runtime) |
| 监听运行、拦截请求、代审核或扩展备份 | `hooks`、`backup` + `runtime` | [生命周期 Hooks](/advanced/plugin/hooks) |
| 添加工作区工具、设置页、菜单动作或配置页 | `workspaceTools`、`uiEntries`、`configPage` | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 读写文件、运行命令、管理会话、保存密钥或请求网络 | 页面 `aicode.*` / 进程 `host.*` | [页面接口](/advanced/plugin/ui-pages#宿主接口)、[进程接口](/advanced/plugin/tools-runtime#host-api-细节) |
| 使用主题、组件、插件事件与文件订阅 | `aicode-*`、`events`、`fs.watch` | [页面样式、组件与事件](/advanced/plugin/ui-components) |
| 直接调用已配置的模型并编排工具循环 | `modelAccess` + `model.*` | [模型句柄与流式调用](/advanced/plugin/model-handles) |
| 创建 App 外的悬浮窗口 | `overlay` | [系统浮层](/advanced/plugin/overlay) |
| 执行 Android 命令或接入 OpenAI 兼容服务 | `shizuku`、`providers` | [Shizuku 与插件提供商](/advanced/plugin/shizuku-providers) |
| 接入自定义执行后端（远程沙箱、容器与工作区） | `executionBackends` + `runtime` | [执行后端清单](/advanced/plugin/manifest#executionbackends)、[工作区接口](/advanced/plugin/tools-runtime#工作区与外部会话) |
| 接入自定义 Agent 工作流 | `workflows` + `runtime` | [Agent 工作流清单](/advanced/plugin/manifest#workflows) |
| 接入外部会话列表与历史消息 | `sessions` + `runtime` | [外部会话源清单](/advanced/plugin/manifest#sessions)、[外部会话接口](/advanced/plugin/tools-runtime#工作区与外部会话) |
| 使用 UDS 二进制通道传输大文件与高频流 | `AICODE_IPC_SOCKET` | [UDS 二进制通道](/advanced/plugin/tools-runtime#uds-二进制通道) |

能力声明位于 `contributes`，`runtime` 单独放在清单顶层。带 `handler` 的工具、Hook、动作入口以及执行后端、工作流、外部会话源都需要声明 `runtime`，纯页面可以直接调用宿主桥。

## 三轴扩展能力

针对远程环境、外部 Agent 引擎与外部会话托管场景，插件系统将以下三项职责拆分为彼此正交的能力轴，可单独声明，也可按需组合：

| 能力轴 | 清单字段 | 职责边界 | 宿主接管与复用能力 |
| --- | --- | --- | --- |
| **执行后端** | `contributes.executionBackends` | 文件读写、命令执行、可选终端 PTY 与工作区发现 | 复用工作区文件树、代码编辑器、Git、内置工具与工作区切换体验 |
| **Agent 工作流** | `contributes.workflows` | 接收本轮用户输入与上下文，驱动推理与工具循环并产出流式事件 | 统一接入运行态托管、后台保活、并发互斥、中止与权限审批交互 |
| **外部会话源** | `contributes.sessions` | 同步外部会话列表、分页读取历史消息并处理新建/删除/重命名/置顶 | 侧栏独立分组与增量 Diff 刷新，不绑定特定传输或执行通道 |

常见组合方式：

- **仅声明 `executionBackends`**：将远端机器、云沙箱或自定义容器接入 AiCode，文件浏览、编辑器、Git 与内置 Agent 推理循环仍由宿主提供。
- **仅声明 `workflows`**：在本地或现有远程工作区上运行外部 Agent 引擎，会话列表与消息仍由宿主本地数据库保存。
- **同时声明 `executionBackends`、`workflows` 与 `sessions`**：将外部已有运行体的工作区、历史会话与流式执行完整接入 AiCode。

## 信任与授权

- 启用后，插件声明的能力一起生效；插件进程具有容器内的文件、网络和命令能力，页面也可通过宿主桥操作当前执行环境。
- 向会话发送消息会正常消耗 token，并经过宿主的模式与工具权限判断；模型句柄请求计入对应供应商用量。
- Shizuku、系统浮层、备份与还原事件需要使用者在插件详情中单独授权。
- 代审核可以批准待确认调用，HTTP Hook 可以读取和改写请求体；具体边界见 [Hooks 文档](/advanced/plugin/hooks)。

## Demo 插件

仓库的 `plugins/samples/` 目录提供 Demo 插件，可按需查看源码。
