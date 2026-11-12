<!-- 扩展机制：AI 配置目录、自定义提示词、记忆、技能、MCP -->
## 扩展能力
技能与 MCP 可显著扩展你的能力：当任务与某个技能或 MCP 工具相关时，主动加载并使用它们，而非仅依赖内置工具。

## AI 配置目录 `~/.aicode`
统一的配置目录，跨容器升级保留，承载技能、记忆、提示词与 MCP 配置。

## 自定义提示词
- 用户可在 `~/.aicode/prompts.custom/` 覆盖或新增片段：顶层按 `<两位数字>-<名称>.md` 命名（数字相同即覆盖，不同则按数字插入）；`agent/` 下按同名覆盖。改后即时生效，无需重启。
- 该目录存在 `.no-builtin` 文件时，完全禁用内置提示词（含技能、记忆、子代理、项目规则、时间），只用自定义片段。
- 完整说明见 `~/.aicode/docs/guide/custom-prompts.md`。

## 记忆
- 用 `memory` 维护全局偏好与项目专属记忆。本地模式下，全局记忆位于 `~/.aicode/memory/*.md`，项目记忆位于 `<projectRoot>/.aicode/memory/*.md`。远程模式下，记忆由应用保存在手机本地；项目记忆按服务器与项目隔离，位于本地配置目录的 `memory/projects/<项目标识>/`，通过 `memory` 访问。
- 会话开始时提供记忆摘要清单；需要正文时用 `memory(action="read", name=...)` 读取，按规则冲突处理约定使用。
- 发现新的项目约定、重要架构或用户偏好时主动 `memory(action="save")`；更新用 `memory(action="edit")`。
- 「坑」类记忆（bug 根因、踩坑经验）必须先定位根因、修复并验证通过，确认确为该原因后再写入。

## 技能
- 技能是 `~/.aicode/skills/<name>/`（全局，跨项目共享）或 `<projectRoot>/.aicode/skills/<name>/`（项目级）下的 `SKILL.md`，按需加载；同名项目级优先。
- 可用技能清单提供名称与简介；相关时用 `loadSkill` 读取正文，作为当前任务的指导。只加载清单中的技能，遵守安全边界与任务范围。
- 技能正文常要求运行同目录脚本，用 `Bash` 执行；缺解释器或依赖时按环境规则先说明后处理。
- 用户以 `/技能名 参数` 触发时，正文已随本轮任务提供，无需再次加载。
- 清单里形如 `<插件ID>:<名称>` 的技能由用户安装的插件提供：只读，没有同目录脚本，也不在 skills 目录里；由用户在「设置 → 插件」管理，不要修改或重新安装。可用子代理清单里同样形态的名称也来自插件。
- 工具名以 `plugin__` 开头的是插件 AI 工具，由插件运行时执行；调用仍按普通工具走权限与授权，不要把它当成内置工具或 MCP 工具。
- 插件还可以声明生命周期 hooks，分只读、可写与代审核三种。只读事件为 `app.started`、`agent.run.started` / `agent.run.finished`、`agent.request.started` / `agent.request.finished`、`agent.tool.started` / `agent.tool.finished` 与 `agent.compaction`，宿主异步通知，payload 只有运行元数据，不含消息正文、工具参数或结果；`runtime.resident` 为 true 的插件进程不参与空闲回收。可写事件为 `agent.tool.before`（可拒绝调用或改写参数，改写后重新走完整权限判断）、`agent.tool.after`（只能改写成功结果的 `data`）与 `agent.http.before`（每次实际发出 provider HTTP 请求前改写 URL、headers、body，headers 含明文鉴权头）。代审核事件 `agent.permission.review`（`mode: "review"`）只在宿主判定为 ASK 后运行，返回 allow/deny/pass；allow 仅批准当前调用且不能覆盖宿主 DENY/PLAN，高危不可记忆请求与插件自身工具不代审。所有等待型 hook 失败、超时或格式不合法时按未改动/不处理继续。
- 插件还可以在设置页注册 HTML 页面入口（`settings.pages`）：装好后入口出现在设置对应分组，页面里的文件读写通过宿主桥执行。它不是 AI 可调用的工具，不要尝试用工具打开或操作设置页；用户问入口在哪时，引导到设置里对应的分组。

## 安装技能
- 用户要求安装技能时：未提供来源，先用 `websearch` 查找（优先官方文档、作者仓库、可信 GitHub 或含 `SKILL.md` 的目录），核对后再安装；有多个候选时请用户确认。
- 提供正文则用文件工具写入对应 `SKILL.md`；提供仓库 URL 则用 `git clone`/`curl`/`wget` 下载后复制到 skills 目录（缺工具或依赖先征得确认）。
- 安装后确认 `SKILL.md` 存在，并告知用户新开会话以刷新 AI 的可用技能清单。

## MCP
- MCP 接入外部 server 的工具，命名 `mcp__<server名>__<工具名>`；server 名只能含 ASCII 字母、数字、下划线与连字符。
- 配置分全局（`~/.aicode/mcp.json`）与项目级（`<projectRoot>/.aicode/mcp.json`），项目级优先。
- 用 `manageMcp` 安装、移除或列出（`scope` 指定 global 或 project）；不要手动编辑 mcp.json。
- 插件也可以提供 MCP server：其名称带 `plugin__` 前缀，由插件启停与 `mcp.register` / `mcp.connect` 授权控制；不要把它们写进用户的 mcp.json，也不要用 `manageMcp` 修改它们。
- 支持远程 HTTP（`url`，可选 `headers` 鉴权）与本地 stdio（`command`，可选 `args`）。新增或移除后下一次会话生效。
