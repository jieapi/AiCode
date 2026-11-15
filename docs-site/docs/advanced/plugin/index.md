# 插件开发

插件通过一个 `.zip` 包向 AiCode 提供技能、子代理、AI 工具、页面、MCP 服务器、提供商、生命周期 hooks、系统浮层等能力。安装后，使用者可在「设置 → 插件」中统一启用、停用和卸载。

本目录按能力组织，各篇分别说明声明方式、运行机制与示例。首次开发建议从[快速开始](/advanced/plugin/quick-start)阅读；如需查找特定能力，可直接参阅下面的能力总表。

::: warning 预览功能
插件系统仍在快速演进。当前版本支持本地 `.zip` 导入与在线插件商店；清单中出现本目录未列出的字段将被拒绝安装，而不会被静默忽略。启用插件等同于信任其全部声明，安装前请确认来源。
:::

## 能力总表

| 能力 | 声明位置 | 运行位置 | 需要 `runtime` | 详见 |
| --- | --- | --- | --- | --- |
| 技能 | `contributes.skills` | AI 系统提示词，按需加载 | 否 | [技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp) |
| 子代理 | `contributes.agents` | 独立子会话 | 否 | [技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp) |
| MCP 服务器 | `contributes.mcp` | 容器或 SSH 后端 | 否 | [技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp) |
| AI 工具 | `contributes.tools` | 容器内的插件进程 | 是 | [AI 工具与插件运行时](/advanced/plugin/tools-runtime) |
| 生命周期 hook | `contributes.hooks` | 容器内的插件进程 | 是 | [生命周期 Hooks](/advanced/plugin/hooks) |
| 工作区工具 | `contributes.workspaceTools` | 右侧项目工具抽屉中的 WebView 页面 | 否 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 槽位入口 | `contributes.uiEntries` | 设置页分组、侧栏底部、聊天顶栏菜单、空态建议、会话长按菜单、文件树长按、编辑器选区菜单、备份页 | 否 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 插件配置页 | `configPage` | 插件详情中的配置分段 | 否 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 插件事件 | 进程发送 `plugin.event` | 推送给同插件已打开的页面 | 是 | [页面样式、组件与事件](/advanced/plugin/ui-components) |
| 文件订阅 | 页面调用 `aicode.fs.watch` | 页面，宿主负责监听 | 否 | [页面样式、组件与事件](/advanced/plugin/ui-components) |
| 会话与消息 | 页面 `aicode.chat.*` / 进程 `host.chat.*` | 聊天页 ViewModel | 可无 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 运行状态与审批 | 页面 `aicode.chat.*` / 进程 `host.chat.*` | 聊天页 ViewModel | 可无 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 模型句柄 | `contributes.modelAccess` + 页面 `aicode.model.*` / 进程 `host.model.*` | 宿主直接调用已配置供应商，支持工具与图片 | 可无 | [模型句柄与流式调用](/advanced/plugin/model-handles) |
| 系统、密钥与 HTTP | `aicode.clipboard/share/secrets/http` / `host.*` | 宿主 | 否 | [插件页面与宿主接口](/advanced/plugin/ui-pages) |
| 系统浮层 | `contributes.overlay` | 宿主托管的系统窗口 | 可无 | [系统浮层](/advanced/plugin/overlay) |
| Shizuku 命令 | `contributes.shizuku` | Android 系统 | 否 | [Shizuku 与插件提供商](/advanced/plugin/shizuku-providers) |
| 备份与还原事件 | `contributes.backup` + `backup.export.before` / `backup.import.finished` | 容器内的插件进程 | 是 | [生命周期 Hooks](/advanced/plugin/hooks) |
| OpenAI 兼容提供商 | `contributes.providers` | 插件自行启动的服务 | 通常需要 | [Shizuku 与插件提供商](/advanced/plugin/shizuku-providers) |

一个插件可同时声明多类能力，无需按类型拆分为多个包。纯声明式插件可仅包含 Markdown、HTML 和配置；需要业务逻辑时再增加 `runtime`。

## 插件目录结构

```text
demo-kit/
├── plugin.json
├── dist/
│   └── main.js
├── schemas/
│   └── echo.input.json
├── skills/
│   └── project-notes/
│       └── SKILL.md
├── agents/
│   └── reviewer.md
├── mcp/
│   └── project-context.json
└── pages/
    └── config.html
```

`plugin.json` 必须位于压缩包根部，不可再嵌套一层目录。清单声明包内内容，宿主仅按清单加载，不会扫描未声明的文件。

## 信任边界

插件启用后，清单声明的技能、子代理、工具、页面、hook、MCP 和提供商将一并生效，不再逐项授权。安装确认仅展示其提供的能力。

- 插件页面通过宿主桥读写当前执行环境的真实路径：本地模式为容器内路径，SSH 模式为远端服务器路径；`~` 展开到当前 home，裸相对路径挂到工作区根；插件自己的数据目录用稳定的 `data://` 虚拟根，与插件 ID 和执行环境解耦。页面可执行 `aicode.bash`、创建共享终端标签，也可调用同插件进程的 handler。
- 插件可以读取当前工作区的会话与消息、创建会话并向会话发送消息；发送与手动输入同路径，会正常消耗 token 并触发工具授权，不能绕过权限与 PLAN 模式。
- 插件进程与 Bash 同级，拥有容器内的文件、网络和命令能力；宿主桥不再做路径白名单，App 自身的其它私有目录在本地模式下没有对应的容器路径，因此不可达。
- Shizuku 和系统浮层是插件级独立授权，启用插件不会自动获得，需由使用者打开插件详情后单独开启。
- 代审核 hook 可批准原本需要使用者确认的调用；HTTP hook 可读取并改写请求体，敏感鉴权头在 payload 中为 `{{Token}}` 占位符，跨 origin 改写会剥离原始敏感头。仅应安装可信来源的插件。

## 学习路径

1. [快速开始](/advanced/plugin/quick-start)：创建可导入的最小插件，并了解开发者侧载。
2. [plugin.json 清单参考](/advanced/plugin/manifest)：查阅各字段的必填性、取值与限制。
3. 按需阅读能力章节：声明式能力参见[技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp)，业务逻辑参见[AI 工具与插件运行时](/advanced/plugin/tools-runtime)，拦截与代审核参见[生命周期 Hooks](/advanced/plugin/hooks)。
4. 需要界面时，参见[插件页面与宿主接口](/advanced/plugin/ui-pages)和[页面样式、组件与事件](/advanced/plugin/ui-components)；需要悬浮窗时参见[系统浮层](/advanced/plugin/overlay)。
5. 最后阅读[打包、导入与排错](/advanced/plugin/packaging)，以及[示例库](/advanced/plugin/examples)中的完整项目。

## 示例库

仓库的 `plugin-samples/` 目录提供覆盖各项能力的完整插件，可侧载后直接修改：

| 示例 | 演示内容 |
| --- | --- |
| `community.demo-kit` | 技能、只读子代理、工作区工具、AI 工具、只读与可写 hook、插件事件 |
| `capability-demo` | 对话提示条、插件级 Shizuku 授权、配置页 |
| `chat-bridge` | 设置页对话界面、会话长按动作、`aicode.chat`、`chat.run.finished` 完成通知 |
| `dev-tools` | 两个工作区工具页面，调用 `aicode.bash` 与终端 |
| `ui-gallery` | 设置页、侧栏与顶栏槽位入口、插件配置页、宿主 Vue 组件库 |
| `memory-manager` | 设置页读写全局与项目记忆 |
| `desktop-pet` | 宿主托管浮层、长按展开菜单、拖拽、点击互动、`app.started` 与 `plugin.enabled` 自启动 |
| `background-approval` | 后台审批浮层、代审核 hook、`app.isForeground` |
| `provider-demo` | OpenAI 兼容提供商、常驻进程、`app.started` |
| `ssh-manager` | 常驻进程、npm 依赖准备、设置页与三个 AI 工具 |

各示例的声明与关键代码摘要见[示例库](/advanced/plugin/examples)。

## 版本与兼容

清单中的 `engines.pluginApi` 声明所需的插件接口版本。当前宿主提供 `1.0.0`，可使用 `^1.0.0` 或包含 `1.0.0` 的范围。宿主将拒绝不兼容的插件，不会尝试降级运行。
