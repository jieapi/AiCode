# 插件系统计划：总览

状态：讨论稿 v0.8，2026-11-12。技能与子代理、工作区工具、AI 工具与运行时、MCP 服务器、生命周期 hooks 已在 `feat/plugin-system` 分支实现；只读 hooks 已通过冒烟编译、插件单测与真机验证（2026-11-10），可写 hooks、HTTP hook 与代审核已实现。2026-11-12 已定：通用 UI 唯一形态为 HTML 页面，删除 `engines.structuredUi`，`pluginApi` 重置为 1.0.0；插件启用即信任、全局安装与启用；不在 F-Droid 上架；代审核开放高危 ASK。其余仍为设计规划；进度见第 8 节。

本文档用于持续讨论，不代表已经批准实施，也不代表下面的建议都已确定。后续直接修改对应主题文件，避免把尚未决定的事项写成既定约束。

工作方式：计划保存在 `docs/plugin/`，可拆分文件。第一切片已实施；进入下一个阶段前先确认范围，未实施部分仍以计划为准。

## 1. 阅读顺序

| 文件 | 内容 |
| --- | --- |
| [01-architecture.md](01-architecture.md) | 宿主、运行时、插件包、数据边界与现有代码接入点 |
| [02-extensions-and-hooks.md](02-extensions-and-hooks.md) | 工具、MCP、Skill、子代理与 Agent 生命周期 |
| [03-permissions-and-interaction.md](03-permissions-and-interaction.md) | 插件权限、代审核流程、双向交互、通知与 Shizuku |
| [04-distribution.md](04-distribution.md) | 多索引、官方标识建议、非官方逐次确认与在线安装更新 |
| [05-roadmap-and-decisions.md](05-roadmap-and-decisions.md) | 实施阶段、验收标准、决策记录与待确认问题 |
| [06-structured-ui.md](06-structured-ui.md) | DIY 通用化、自由布局、样式动效、状态绑定、表单和事件 |
| [07-build-plugins.md](07-build-plugins.md) | 右抽屉插件入口、业务逻辑、命令能力与用户终端联动 |
| [08-plugin-management.md](08-plugin-management.md) | 设置内插件列表、配置/权限操作、仓库与在线管理入口 |
| [09-provider-plugins.md](09-provider-plugins.md) | 注册提供商、App 兼容接口与插件自行处理上游协议 |
| [10-plugin-format.md](10-plugin-format.md) | 源码/发布包目录、plugin.json 字段、权限/贡献与完整示例 |
| [11-index-format.md](11-index-format.md) | 索引目录、入口/插件/版本文件、摘要链与在线安装更新示例 |
| [12-service-dependencies.md](12-service-dependencies.md) | 插件服务依赖、用户手动准备、锁文件和环境状态 |

## 2. 状态约定与术语

- **已确认**：用户明确提出或确认的需求。
- **建议**：基于需求与现有代码提出的方案，可以修改，不视为用户已接受。
- **待确认**：缺少用户定义，或需要技术验证后才能决定。
- **宿主**：AiCode Android App。
- **插件作者**：编写和发布第三方插件的人。
- **使用者**：安装插件、授予权限、使用 AiCode 的用户。
- **插件能力**：插件声明的宿主能力；当前采用启用即信任，安装后随插件生效，不等于 Android 系统权限，也不等于 AI 工具调用的审批结果。
- **代审核**：插件按声明参与宿主 ASK 决策；启用即生效，不单独授权。

文中的接口、事件和组件名称均为规划用名；标注为“现有”的代码链接除外，不表示这些新接口已经存在。源码、发布包、清单和索引已补具体结构草案及 JSON 示例，优先阅读 10/11；它们不是已实现 SDK、完整 UI 协议或已批准的正式格式。

## 3. 已确认需求

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| R01 | 面向不可信第三方 | 不能假定插件代码可信，需明确隔离与授权边界 |
| R02 | 插件始终在手机端运行 | 切换 SSH 模式不将插件运行时迁到远端 |
| R03 | 注册工具 | 注册后可供 AI 发现和调用 |
| R04 | 注册 MCP | 复用现有 MCP 功能，不另造一套协议或擅自限定成 HTTP-only |
| R05 | 注册 Skill | 纳入现有技能使用流程 |
| R06 | 注册子代理 | 纳入现有自定义子代理发现与派发流程 |
| R07 | 构建插件形态 | 右抽屉列表入口，点击打开插件自带的 HTML 页面；设置页入口与插件配置页也使用 HTML |
| R08 | 扩展 Agent 生命周期 | 包含请求前、完成时、hooks 等；每个时机可读和可修改什么仍需细化 |
| R09 | 参与权限审核 | 按下节已确认的委托顺序，不是替换整个权限策略引擎；已开放高危 ASK |
| R10 | 双向交互 | 插件可向 App 显示 tips、发送通知，App 可把事件或操作结果回传插件 |
| R11 | 调用 Shizuku | 插件声明用途/范围，授权后范围内直接执行，不逐次弹窗；系统授权与撤销仍需检查 |
| R12 | 声明能力与安装确认 | 安装确认时展示将获得的贡献与高危能力；采用启用即信任，安装后全部贡献生效 |
| R13 | 多索引与独立源码仓库 | 可配置多个索引仓库，索引仓库只存索引，每个插件一个独立源码仓库 |
| R14 | UI 通用化 | 插件页面、设置页入口与配置页统一使用 HTML + 宿主组件库 + 页面桥 |
| R15 | 丰富页面 UI | HTML/CSS 自由布局、外观动效、动态内容与表单；不再实施声明式 UI 引擎 |
| R16 | 插件提供具体业务 | 插件声明 UI 并实现业务逻辑，App 不内置具体构建/依赖下载业务 |
| R17 | 插件调用 Bash / terminal | 使用通用能力执行构建、下载依赖等命令 |
| R18 | 命令跟随工作区 | 本地模式走本地容器，SSH 模式走远端；插件 UI 和运行逻辑仍在手机 |
| R19 | 范围内直接执行命令 | 插件明确声明且获用户授权后，范围内命令不逐条弹窗确认 |
| R20 | 设置内插件管理 | 设置 → 插件 → 插件列表，显示名称/版本等，提供插件配置、插件权限等管理操作 |
| R21 | 展示官方标识 | 使用官方索引地址白名单，自定义索引一律非官方；官方索引仓库尚未创建 |
| R22 | 非官方安装确认 | 点击安装后显示宿主确认弹窗，手动输入该插件源码仓库地址 |
| R23 | 在线安装与更新 | 可从配置的索引在线安装、检查和应用更新，不等于授权无交互自动更新 |
| R24 | 非官方每次更新确认 | 每次非官方更新都再次弹窗、输入源码仓库地址，不复用首次确认 |
| R25 | 用户终端联动 | 复用与 AI 一致的 Bash/terminal 能力，新建共享标签供用户查看交互，不要求接管已有用户标签 |
| R26 | 注册提供商 | 插件对接 App 当前支持的接口，实际的上游协议、鉴权与转换由插件自行处理，不要求 App 内置新上游适配器 |
| R27 | 插件服务依赖 | 可安装外部服务的 npm/pip 等依赖到独立目录/虚拟环境；本轮不加入 Node/Python/JDK 等系统工具自动安装 |
| R28 | 手动准备环境 | 使用者点击“准备环境”后执行，未准备/失败不启动依赖服务，不在安装或渲染阶段自动安装 |

## 4. 已确认的代审核顺序

```text
AI 请求调用工具
  ↓
App 按现有规则判断
  ├─ 不允许：原样拒绝，不交给插件改成允许
  ├─ 可以直接执行：沿原流程执行
  └─ 需要使用者确认
       ↓
     按使用者设置的顺序询问已启用并声明代审核的插件
       ├─ 同意：批准本次调用，停止询问后续插件
       ├─ 拒绝：仅拒绝本次调用，停止询问后续插件
       └─ 不处理：询问下一个；全部不处理时进入现有 UI
```

**已确认**：首个同意或拒绝生效；没有适用插件或全部不处理才进入 UI。插件拒绝只影响当前工具，其他工具继续审批、执行，AI 可以继续处理结果，不因此停止整批或本轮。

已实现：异常/超时按 `pass` 继续，最终回 UI；`allow` 只批准当前调用，不写永久允许规则；插件不能代审自己提供的工具，`planMode` 也不代审。

## 5. 已定方案摘要

1. **通用 UI 唯一形态是 HTML 页面。** 插件自带 HTML/CSS/JS，宿主提供 WebView 容器、`aicode.js` 页面桥与 Vue 组件库；不再做声明式 UI 引擎。
2. **插件逻辑跑在本地容器的 node/python 子进程。** 通过 NDJSON JSON-RPC 调用 handler，`host.fs.*`/`host.bash`/`host.notify` 是宿主能力；进程不构成安全沙箱，启用即信任。
3. **启用即信任、全局安装与启用。** 安装确认展示贡献与高危能力，不再逐项授权；`pluginApi` 统一为 1.0.0。
4. **代审核是宿主 ASK 之后的一步。** 已开放全部 ASK（含高危）；插件不能代审自身工具、不能覆盖宿主 DENY、不写永久规则。
5. **命令侧 `bash` 同步 + `terminal.run` 共享标签。** 由插件显式选择；标签生命周期插件维护，后台默认隐藏、终端设置开启后可见。
6. **提供商使用 OpenAI 兼容服务。** 插件在清单登记 `baseUrl` 与模型，宿主复用现有 OpenAI 适配器；插件负责服务启动、就绪与上游转换。
7. **分发使用官方索引地址白名单。** 在线索引本轮不做；非官方逐次手输、多索引、独立源码仓库保留规划。
8. **不在 F-Droid 上架。**

## 6. 仍未定与不擅自扩展的内容

- hooks 响应 transform、消息级改写与持久化规则未开放；当前只覆盖工具参数、成功结果 `data` 与 provider HTTP 请求。
- SDK 许可证、包签名/来源连续性后置，发布 SDK 或在线索引前必须定。
- 在线索引的官方索引仓库尚未创建；白名单地址、发布流程与撤回策略待定。
- 服务依赖的锁格式、缓存/更新复用、空间与失败恢复仍需验证；本轮不自动安装系统工具。
- 外部 MCP stdio 与技能脚本不做强隔离，风险按“与 Bash 同级”告知，不宣称沙箱。
- 插件安装/更新的版本切换对在途会话的影响未定；先保证不重复执行、旧回包不写入新页面。
- 非官方逐次手输的地址等价形式、是否允许粘贴、官方自动更新细节待定。

## 7. 与现有项目约定的关系

插件运行位置遵循 R02，宿主执行命令的目标遵循 R18，两者不冲突。现有全局 Skill、子代理等资源会跟随执行环境，这不等于插件包也必须上传到服务器。建议将插件贡献作为单独来源，既保留已有资源行为，也让插件本体保持在手机端；具体见架构和扩展章节。

本目录是工程规划，不是面向使用者发布的功能文档。实施时必须同步使用文档、提示词和双语文案，并遵守项目的编译、测试、迁移与 RC 发布规则；第一切片已同步 `docs-site/docs/guide/plugins.md`、`docs-site/docs/advanced/plugin-development.md`、双语字符串与 `prompts/70-skills-and-mcp.md`，hooks 切片已同步这两页文档与同一提示词。

## 8. 实现进度

### 8.1 已实现范围

| 范围 | 状态 | 代码 |
| --- | --- | --- |
| 清单解析与校验、SemVer 范围、本地化文本 | 已实现（单测覆盖） | `app/.../feature/plugin/domain/` 的 `PluginManifestParser`、`SemVer`、`LocalizedText` |
| 安全 ZIP 读取、安装记录库（原子写 JSON） | 已实现（单测覆盖） | `feature/plugin/data/` 的 `PluginPackageReader`、`PluginRegistryStore` |
| 插件管理器：预览、安装、替换、卸载、启停 | 已实现（单测覆盖） | `feature/plugin/domain/PluginManager.kt` |
| 贡献：技能、子代理 | 已实现 | `PluginSkillSource`、`PluginAgentSource`，接入 `SkillRepository`、`AgentDefinitionRepository` |
| 设置 → 插件：列表、详情、导入预览 | 已实现，未真机验证 | `feature/plugin/presentation/`，接线在 `SettingsScreen.kt` |
| 贡献：工作区工具（HTML 页面 + 页面桥） | 已实现；页面统一为 HTML，命令由页面调用宿主 `bash`，运行态回传待做 | `contributes.workspaceTools`、`workspace.tools`、`PluginContentLoader.loadWorkspaceTool`、`ProjectToolsRepository` |
| 右抽屉项目工具入口、全部工具页、工具详情页 | 已提交（`c0223c8e`），未真机验证 | `feature/plugin/presentation/drawer/`、`MainActivity.kt` 的右抽屉与路由 |
| 插件共享终端标签（`terminal.run/poll/stop/close`，默认隐藏） | 已实现，待真机验证 | `PluginTerminalRunner`、`TerminalSessionProvider.startPluginCommand`、终端设置「显示插件运行」 |
| 贡献：AI 工具（`runtime` + `tools.register` + 容器进程 handler） | 已实现；2026-11-10 真机验证的是旧沙箱实现，容器进程版待真机验证 | `feature/plugin/runtime/` 的 `PluginRuntimeManager`、`PluginToolAdapter`、`PluginToolManager` |
| 贡献：MCP 服务器（HTTP/stdio，复用 McpManager） | 已实现，冒烟编译与插件单测通过，待真机验证 | `contributes.mcp`、`mcp.register` / `mcp.connect`、`PluginContentLoader.loadMcp`、`McpManager.effectiveServers` |
| 贡献：生命周期 hooks（只读；`contributes.hooks`，事件 `agent.run.started` / `agent.run.finished`、`agent.request.started` / `agent.request.finished`、`agent.tool.started` / `agent.tool.finished`、`agent.compaction`） | 已实现，冒烟编译、插件单测与真机验证均通过（2026-11-10） | `PluginHookDispatcher`、`PluginManifestParser` 的 hooks 解析、`StatefulAgentWorkflow.executeEvents` 的派发 |
| 贡献：可写 hooks（`mode: "transform"`；`agent.tool.before` / `agent.tool.after`，可拒绝/改参数/改成功结果 data） | 已实现，冒烟编译与单测待本次验证 | `PluginHookDispatcher.beforeToolCall/afterToolCall`、`PluginHookTransform`、`StatefulAgentWorkflow` 的权限与批次结果路径 |
| 贡献：HTTP 请求 hooks（`agent.http.before`，可改写 URL/headers/body） | 已实现，冒烟编译与单测待本次验证 | `PluginHttpHookInterceptor`、`PluginHookDispatcher.beforeHttpRequest`、`PluginHookTransform.parseHttp` |
| 贡献：代审核 hooks（`agent.permission.review`，全部 ASK 三态 allow/deny/pass） | 已实现，冒烟编译与插件单测通过；真机验证待补 | `PluginHookDispatcher.reviewPermission`、`PluginHookTransform.parseReview`、`StatefulAgentWorkflow.requestPermissionIfNeeded` |
| 启动事件与常驻运行时（`app.started`、`runtime.resident`） | 已实现，冒烟编译与单测待本次验证 | `AIEditorApp.onCreate`、`PluginHookDispatcher.dispatchAppStarted`、`PluginRuntimeManager.pruneIdle` |
| 设置页入口与插件配置页（HTML） | 已实现 | `contributes.settingsPages`、`configPage`、`PluginWebPanelScreen` |
| 贡献：插件提供商（OpenAI 兼容服务 + 自动注册为可写提供商） | 部分实现，待真机验证 | `contributes.providers`、`PluginProviderRegistrar`、设置页插件标记 |
| 服务环境、tips / Shizuku | 未实现 | 见 [03](03-permissions-and-interaction.md)、[12](12-service-dependencies.md) |
| 开发者侧载 / 调试模式 | 未实现（已定要做） | 普通 `.zip` 导入已可用 |
| 在线索引安装 / 更新、官方标识、非官方逐次手输确认 | 本轮不做，保留阶段 7 | 见 [04](04-distribution.md)、[11](11-index-format.md) |

实现范围的精确说明（哪些字段会被接受、哪些会被拒绝、各项限额）见 [10-plugin-format.md](10-plugin-format.md) 第 12 节。

### 8.2 阶段进度

阶段定义见 [05](05-roadmap-and-decisions.md) 第 1 节。状态只描述代码与验证事实，不表示工期。

| 阶段 | 状态 | 当前落点 |
| --- | --- | --- |
| 0 产品与安全定义 | 已收敛 | 2026-11-12 已定主要边界；SDK 许可证、包签名、响应 transform 仍后置，见 05 第 4 节 |
| 1 运行时与 UI 协议 PoC | 部分完成 | 运行时已改为本地容器内的插件子进程（javascript/python），可引用依赖并通过 JSON-RPC 调用宿主文件/命令 API；真机验证待补 |
| 2 插件包、管理与授权 | 部分完成 | 本地 `.zip` 导入、替换、启停、启用即信任、全局作用域已实现；在线索引本轮不做 |
| 3 通用 UI | 已定方案 | 唯一形态是 HTML 页面（WebView + 宿主 bridge + Vue 组件库）；声明式结构化 UI 引擎不再实施 |
| 4 工具、内容与提供商 | 部分完成 | 技能、子代理、工作区工具、AI 工具、MCP 已实现；插件提供商已接入 OpenAI 兼容服务并自动注册为可写提供商，服务就绪探测待验证 |
| 5 右抽屉、配置与受控命令 | 部分完成 | 右抽屉、全部工具页、详情页已接通；`bash` 同步 + `terminal.run` 共享标签已实现，标签默认隐藏、终端设置开启后可见；待真机验证 |
| 6 生命周期、代审核与双向交互 | 部分完成 | 只读 hooks 已扩到八个事件（App 启动、运行开始/结束、请求开始/结束、工具开始/结束、上下文压缩）。可写 hooks 已实现：`agent.tool.before` 可拒绝或改写参数（改写后重新走完整权限判断），`agent.tool.after` 可改写成功结果的 `data`，`agent.http.before` 可改写 provider 请求的 URL/headers/body。代审核 `agent.permission.review` 已开放全部 ASK（含 Shizuku、elevate、配置修改、删除子代理、危险 rm）的 allow/deny/pass；`planMode` 与插件自身工具仍不代审。单 hook 10 秒、合计 20 秒，失败按未改动/不处理继续。响应 transform、tips 与 Shizuku 未开始 |
| 7 目录分发、发布与安全验收 | 本轮不做 | 在线索引保留规划；官方标识用官方索引地址白名单，仓库尚未创建；不在 F-Droid 上架 |

### 8.3 当前进度

当前 AI 工具与 hooks 统一在本地容器的插件子进程里运行（`runtime` + `dist/main.js`），通过 NDJSON JSON-RPC 调用 handler，并可通过 `host.fs.*`/`host.bash`/`host.terminal.*` 使用宿主能力。工作区工具页面统一为 HTML，命令由页面通过宿主桥执行；共享终端标签已实现，运行状态由插件页面轮询展示。

2026-11-12 设计收敛：通用 UI 唯一形态定为 HTML 页面（WebView + 宿主 bridge + Vue 组件库），删除 `engines.structuredUi`，`pluginApi` 重置为 1.0.0（此前 1.1.0-1.5.0 的功能全部归入 1.0.0）；插件启用即信任、全局安装与启用；不在 F-Droid 上架；官方标识用官方索引地址白名单（仓库未创建）；在线索引本轮不做；备份不含插件；外部 MCP/技能脚本不做强隔离；tips 用对话内提示条；Shizuku 由插件声明范围，授权后范围内直接执行；增加开发者侧载；代审核开放高危 ASK。命令侧定为 `bash` 同步 + `terminal.run` 共享标签，由插件显式选择，标签生命周期由插件维护，后台运行默认隐藏、终端设置开启后可见。提供商改为插件提供 OpenAI 兼容服务，宿主只登记 baseUrl 与模型。

2026-11-12：插件提供商改为自动注册为可写提供商——清单支持 `contributes.providers[]`（`id`/`name`/`description?`/`format`/`baseUrl`），只接受 OpenAI 兼容格式；插件启用或安装时在提供商表中创建 `plugin:<插件ID>:<局部ID>` 记录，复用 `OpenAIAdapter`，用户可像普通渠道一样改名、改地址、改凭据、拉取与选择模型；插件停用/卸载时删除该插件注册的记录。设置页保留“插件”标记。服务未启动时请求失败，不回退其他渠道。编译与插件单测通过，真机验证待补。

2026-11-12：新增插件共享终端标签——`terminal.run` 创建带插件归属的后台标签，`terminal.poll` 按偏移读增量输出与状态，`terminal.stop` 发 Ctrl-C（超时关闭），`terminal.close` 由插件维护生命周期。标签默认不在终端页显示，需在「终端设置 → 显示插件运行」开启；`TerminalSessionProvider.listTabs` 不返回插件标签，AI 的 terminal 工具不能操作。页面桥与插件进程分别暴露 `terminal.*` 与 `host.terminal.*`。编译通过，待真机验证。

2026-11-12：新增 `app.started` 启动事件与 `runtime.resident`（PluginApi 1.5.0）——App 主进程 onCreate 完成后派发一次，payload 为 `launchId`/`startedAt`/`appVersion`/`process`；声明该事件的插件会在此时拉起容器内进程。`runtime.resident: true` 的进程跳过 10 分钟空闲回收，停用/卸载/版本替换与 App 进程结束仍会关闭。容器未就绪时启动 hook 失败只记日志，后续调用仍按需启动。

2026-11-12：新增 `agent.permission.review` 代审核（当时标注 PluginApi 1.4.0，现统一为 1.0.0）——`mode: "review"`，只在宿主权限判定为 ASK 后、弹出确认前运行；返回 `allow`/`deny`/`pass`，首个 allow/deny 生效，pass 继续下一个，全部不处理回退 UI。allow 只批准当前调用、不写入永久允许规则；deny 错误码 `PLUGIN_REVIEW_DENIED`，只影响当前工具。超时/异常/格式错误按 pass。已开放全部 ASK（含 Shizuku、elevate、配置修改、删除子代理与危险 rm）；`planMode` 与插件自身工具仍不代审；按插件 ID 升序、单 hook 10 秒/合计 20 秒。

2026-11-12：新增 `agent.http.before` HTTP 请求 hook（PluginApi 1.3.0）——挂在 AgentModule 共享 OkHttpClient 上，覆盖对话、标题、压缩、模型列表/测试与图片请求；payload 为 `url`、`method`、`headers`、`body`，可返回 `{url?, headers?, body?}`。URL 限 http/https、headers 最多 64 项且禁止改 Host/Content-Length 等受控头、body 必须是 JSON 对象且 ≤4 MiB；方法固定 POST。每次实际发送都会执行，重试与换 Key 会重复触发；失败、超时或格式错误保持原请求。headers 含明文鉴权头，属于高权限能力。

2026-11-12：新增可写 hooks 第一版——清单 `contributes.hooks[]` 增加 `mode`（缺省 `observe`，可写必须显式 `transform`），新增 `agent.tool.before` 与 `agent.tool.after`。`agent.tool.before` 在权限判定前运行，可返回 `{decision:"deny",reason}` 或 `{arguments}`；改写参数后重新走完整权限判断，拒绝返回 `PLUGIN_HOOK_DENIED`。`agent.tool.after` 只处理成功结果，可返回 `{data}` 改写 `data`，不能改状态、图片或附件。插件按 ID 升序、清单顺序执行，单 hook 10 秒、合计 20 秒，超时/异常/格式错误保持原值继续；转换结果有 256 KiB 上限，只接受白名单字段。插件详情把可写 hook 标注为「可写 Hook」。

2026-11-12：运行时重构为容器内进程——移除 AndroidX JavaScriptSandbox 与 isolate，`runtime.type` 支持 `javascript`（node）与 `python`（python3）；插件目录与依赖装在 `~/.aicode/plugins`，可声明 npm/pip 依赖并在设置页手动准备；host API 复用页面桥的四根与保护目录；`plugin.event` 可推送到同插件已打开的页面。编译与插件单测通过，真机验证待补。

2026-11-10：新增生命周期 hooks 第一切片（已通过冒烟编译、插件单测与真机验证）——清单支持 `contributes.hooks[]`（每项 `{id,event,handler}`，`handler` 为 `dist/main.js` 的 `globalThis.aicode.handlers` 键）与权限 `agent.events`（`scope.ids` 覆盖全部 hook id，`scope.events` 覆盖 hooks 使用的事件集合，必须同时申请）；hooks 需要 `runtime`。首个切片只暴露 `agent.run.started` / `agent.run.finished`，payload 只有 `runId`、`sessionId`、`mode`、`status`、`toolCalls`、`errorCode`，`status` 为 `started` / `success` / `error` / `cancelled`，不含消息正文；宿主异步派发，失败只记日志、不阻塞 Agent；只读，不能修改请求或自动批准工具。同日随后把只读事件扩到七个：新增 `agent.request.started` / `agent.request.finished`、`agent.tool.started` / `agent.tool.finished` 与 `agent.compaction`，payload 增加 `scope`（`main`/`subagent`）、`agentName`、请求与工具标识、usage 与耗时；hook 可返回 `{logs:[...]}` 写应用日志（上限 50 行、单行 500 字符）。扩展已通过冒烟编译与插件单测，真机验证待补。

2026-11-10：同日新增 MCP 切片——清单支持 `contributes.mcp` 与 `mcp.register` / `mcp.connect`；插件 MCP 文件复用 `McpConfigParser` 与现有 HTTP/stdio 链路，server 名加 `plugin__` 命名空间，用户同名配置优先；插件启停/卸载时 McpManager 重连并移除工具。已通过冒烟编译与插件单测，待真机验证。

2026-11-10：新增 AI 工具切片——清单支持 `runtime`、`contributes.tools` 与 `tools.register`；运行时每个插件一个 isolate，宿主注入 `__aicodeInvoke` 调用 `globalThis.aicode.handlers`；工具按 `plugin__` 命名空间注册进 `ToolRegistry`，默认 ASK，执行前复核插件启用与授权。`assembleUniversalDebug` 通过，69 个插件单测通过。同日真机验证通过：AI 工具 echo 返回内容、长度与 `runtime: javascript` 正确；技能 project-notes 成功写入 `NOTES.md`；子代理 reviewer 完成只读审查。

2026-10-28：抽屉与入口交互修正已完成——删除聊天页头部工具图标、左右抽屉宽度统一、列表图标统一主题色、左抽屉边缘手势恢复、右抽屉改为聊天页右半屏左滑打开、平板端接入右抽屉、详情返回恢复抽屉与滚动位置。首次真机反馈右边缘手势打不开，已将起点从右边缘 24dp 放宽到右半屏任意位置，并改为直接读指针位移。`assembleUniversalDebug` 与 `assembleArmsoloDebug` 均通过，已打 armsolo debug 包待真机验证；尚未提交。

### 8.4 待办（按依赖顺序）

1. **命令执行与共享终端标签**（已实现，待真机验证）
   - `bash` 保持一次性同步返回；`terminal.run` 创建共享标签，由插件显式选择。
   - 标签带插件归属，`TerminalSessionProvider.listTabs` 不返回；默认隐藏，终端设置开启「显示插件运行」后可见；停用/卸载关闭。
   - 输出：`TerminalSessionProvider.tabOutput` 的原始字节流累积到 128 KiB 有界缓冲，`terminal.poll(tabId, offset)` 返回增量、状态与退出码。
   - 状态机：运行中 / 已完成 / 已取消；本地与远程都以 `[command exited: N]` 兜底收尾，通道异常时不误报成功。
   - 停止：`writeBytesToTab(tabId, 0x03)` 发 Ctrl-C，2 秒未退出则 `closeTab`；`close` 由插件维护生命周期。
   - 待真机验证：长输出、Ctrl-C、SSH 模式、终端设置开关、插件停用清理。
2. **提供商注册**（部分实现）
   - 插件在 `contributes.providers` 登记 OpenAI 兼容服务的 `baseUrl`；宿主复用 `OpenAIAdapter`，不新增上游适配器。
   - 插件启用或安装时自动注册为可写提供商记录，停用或卸载时删除；设置页保留插件标记，模型与选中模型走现有存储。
   - 待补：服务就绪检测、SSH 场景与真机验证；停用或卸载时不静默回退其他付费渠道。
3. **tips 与 Shizuku**
   - tips：对话内可关闭提示条，支持操作按钮；与系统通知（`host.notify`）分开。
   - Shizuku：插件声明用途/范围，授权后范围内直接执行，不逐次弹窗；系统授权、撤销与范围变化仍需重新检查。
4. **开发者侧载 / 调试模式**
   - 设置开关，支持本地目录或 `.zip` 安装与热重载，带明显风险提示；不参与在线更新。
5. **安全与发布前检查**
   - 页面桥的 `bash`/`fs.*` 在执行前复核插件仍启用；当前页面打开后停用插件仍可调用。
   - 插件进程 stdout 增加单行长度上限；补强杀、崩溃恢复、OOM 与资源边界验证。
   - 命令与输出写入日志时处理敏感信息；插件可执行命令的风险要在安装确认与用户文档中持续说明。
   - 同步 `docs-site/docs/guide/plugins.md` 与 `advanced/plugin-development.md`：移除运行面板、1.1.0、沙箱等过时描述。
6. **已完成的清理项**
   - 抽屉交互、工具数据对账、`removeToolFromProject`、工作区切换重读、不可用标注、详情页空态已完成；参数类型与快速运行问题随 JSON 页面移除而消失。

### 8.5 需求与阶段对应

- 命令执行与共享标签、提供商、tips/Shizuku 是下一阶段优先项；开发者侧载与安全加固随实现补。
- 在线安装、更新与官方标识属于阶段 7，本轮不做；官方索引仓库建好后再补地址与发布流程。
- 声明式结构化 UI 不再实施，HTML 页面协议是唯一形态。
- hooks 响应 transform 仍后置，见 [05](05-roadmap-and-decisions.md) 决策表。

### 8.6 维护规则

- 每完成一个阶段或阶段内一项，更新 8.2 的状态与 8.3 的当前落点；已完成项注明日期与验证方式（单测 / 冒烟编译 / 真机）。
- 未提交的代码写「在途」，不写成「已实现」。
- 新增需求先归入 8.4 或对应阶段，再进入实现。
