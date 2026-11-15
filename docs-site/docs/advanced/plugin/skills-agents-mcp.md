# 技能、子代理与 MCP

这三类能力均为纯声明：仅在清单中引用包内文件，无需 `runtime`，也不会在容器中启动插件进程。适用于将现有工作流、审查角色或外部工具直接打包。

## 技能

技能为 AI 提供一组按需加载的指令。其格式与「设置 → 技能」中的 `SKILL.md` 相同：顶部为 YAML，正文为指令。

`skills/project-notes/SKILL.md`：

```markdown
---
name: project-notes
description: 当用户要求总结、归档、回顾当前讨论，或说「记一下」「写进笔记」时使用；将结论整理为条目写入 NOTES.md
---

将当前对话的结论整理为一份项目笔记，写入工作区根目录的 `NOTES.md`。

## 操作步骤

1. 首先读取 `NOTES.md`（不存在则新建），保留已有内容，不得覆盖历史记录。
2. 在文件末尾追加一节，标题形如 `## 2026-11-10 插件演示`，日期取当天。
3. 每节仅记录结论：已完成事项、原因、后续步骤。不写寒暄，不复述推理过程，不粘贴大段代码。
4. 列表项以动词开头，一条一个意思；超过 5 条时按主题分组。
5. 若 `NOTES.md` 已存在同一天的同名小节，应合并至该节，不得重复追加。

完成后以一句话告知用户写入的文件及条目数量。
```

清单声明：

```json
{
  "contributes": {
    "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}]
  }
}
```

规则：

- 插件技能的名称由宿主生成，格式为 `插件ID:内容id`，如 `community.demo-kit:project-notes`。文件中的 `name` 会被忽略；
- `description` 决定 AI 何时加载该技能，会被压缩为一行写入系统提示词。应明确说明「何时使用」，避免仅写功能名称；
- 正文不得为空；
- 技能不得依赖同目录的脚本：安装包不会被放入容器，AI 无法在容器中找到插件包。需要脚本时，应在正文中写明命令或操作步骤，由 AI 在容器中执行；
- 技能正文最大 512 KiB。

适合纳入技能的内容：项目约定、发布检查清单、固定的排查步骤、某类任务的写作规范。

## 子代理

子代理是一个独立会话，可指定工具集、运行模式和注入的上下文。其格式与「设置 → 子代理」中的定义文件相同：YAML frontmatter 加正文提示词。

`agents/reviewer.md`：

```markdown
---
name: reviewer
description: 只读审查指定文件或改动，列出问题、风险与建议，不修改代码。适用于主代理准备提交前的复核
mode: plan
tools: [readFile, list, search]
inject: [base, projectRules]
---

作为代码审查子代理，仅执行只读操作，不修改任何文件。

## 工作方式

- 按派发说明定位文件或改动范围，先阅读原文，再下结论，不凭文件名推断实现。
- 检查：明显缺陷、边界条件、资源泄漏、并发问题、安全风险（命令注入、越权、凭据外发）、与项目约定不一致之处。
- 每条问题给出 `文件路径:行号`、问题、影响和建议改法，不提供补丁。
- 按严重程度排序。未发现问题时应明确说明，不得凑数。
- 除非项目规则有明确要求，否则不评论纯风格偏好。

## 输出

开头以一句话给出结论（可提交 / 有阻塞问题 / 有建议），随后分条列出。已确认事实与推测应分开表述，推测标注「疑似」。
```

清单声明：

```json
{
  "contributes": {
    "agents": [{"id": "reviewer", "file": "agents/reviewer.md"}]
  }
}
```

可用字段：

| 字段 | 说明 |
| --- | --- |
| `description` | 何时派发该子代理，注入主代理的可用子代理清单 |
| `mode` | `build`、`plan`、`auto`；仅可比主会话更严格，不可放大 |
| `tools` | 工具白名单；省略或空表示继承全量工具 |
| `disallowedTools` | 工具黑名单，先于白名单生效；支持 `mcp__server__*` 通配 |
| `inject` | 注入的提示词片段：`base`、`mainRules`、`skills`、`memory`、`projectRules` |
| `reasoningEffort` | 思考强度 `low`、`medium`、`high`；省略继承主会话 |

两条硬性限制：

- **不得声明 `provider` 和 `model`**。二者会动用使用者的付费渠道，插件子代理一律沿用主会话的模型。声明后将被拒绝安装；
- `mode` 仅可收紧。例如主会话为 BUILD 时，子代理可使用 `plan`；主会话为 PLAN 时，不得声明 `build`。

子代理同样由宿主命名为 `插件ID:内容id`，派发时应使用完整名称。`task` 工具始终被剔除，子代理不得嵌套派发子代理。

`inject` 的可选值：

| 值 | 注入内容 |
| --- | --- |
| `base` | 子代理专用精简基线：工具用法、路径约定、安全边界 |
| `mainRules` | 主代理的完整静态规则基线；需要子代理行为与主代理完全一致时使用 |
| `skills` | 可用技能清单，子代理可使用 `loadSkill` |
| `memory` | 全局与项目记忆摘要 |
| `projectRules` | 项目规则（`AGENTS.md` / `CLAUDE.md`） |

省略 `inject` 时使用默认组合 `[base, skills, memory, projectRules]`。`base` 与 `mainRules` 可同时声明；同时声明时 `mainRules` 在前。

## MCP 服务器

插件可使用现有 `mcp.json` 格式提供 MCP server。配置文件放在包中，例如 `mcp/project-context.json`：

```json
{
  "mcpServers": {
    "project-context": {
      "url": "https://example.com/mcp",
      "headers": {"Authorization": "Bearer <token>"}
    }
  }
}
```

stdio 服务器：

```json
{
  "mcpServers": {
    "local-tools": {
      "command": "node",
      "args": ["server.js"],
      "env": {"LOG_LEVEL": "info"}
    }
  }
}
```

清单声明：

```json
{
  "contributes": {
    "mcp": [{"id": "project-context", "file": "mcp/project-context.json"}]
  }
}
```

行为：

- 支持 HTTP 和 stdio。stdio 将在当前工作区的容器或 SSH 后端启动进程；
- 安装预览会显示 MCP 名称和连接目标；
- 插件贡献的 server 名称将添加命名空间：`plugin__{插件ID}__{贡献id}__{原始server名}`，不会覆盖用户在「设置 → MCP」中手动创建的配置；同名冲突时用户配置优先；
- 工具将以 `mcp__<命名空间后的server>__<tool>` 的形式出现在 AI 工具清单中；
- 插件停用、撤权或卸载时，对应的 MCP 连接和工具一并移除；
- stdio 配置中的 `command`、`args`、`env` 会原样用于启动进程。仅应安装可信来源的插件；
- MCP 配置文件最大 256 KiB。

MCP server 由宿主按现有连接链路管理，无需插件的 `runtime`。如果 server 本身需要插件进程配合（例如同包内的本地服务），应单独声明 `runtime`，并通过 `app.started` 或工具启动。

## 能力选择

| 目标效果 | 适用能力 |
| --- | --- |
| 为 AI 添加操作规范、检查清单 | 技能 |
| 使 AI 派发只读审查子代理、限定工具与模式 | 子代理 |
| 接入已有的外部工具服务 | MCP |
| 需要执行包内自定义逻辑、读写宿主 API | [AI 工具与插件运行时](/advanced/plugin/tools-runtime) |
| 需要在工具执行前后拦截或代审核 | [生命周期 Hooks](/advanced/plugin/hooks) |
