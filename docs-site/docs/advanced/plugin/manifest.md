# plugin.json 清单参考

`plugin.json` 是插件的唯一入口声明。宿主仅加载清单中列出的内容；无法识别的字段和贡献类型将直接拒绝安装，不会静默忽略。

清单有配套的 JSON Schema（`plugins/schema/plugin.schema.json`）：在编辑器里给 `plugin.json` 加上 `"$schema": "…/plugin.schema.json"`（相对路径指向该文件）后，字段名写错或类型不对会直接红线，还有字段补全与枚举提示。`$schema` 只给编辑器用，宿主会忽略它的内容。

独立项目不在本仓库里，相对路径指不到那份 schema，可以把它复制进自己的仓库，或改成仓库的 raw 地址（`https://raw.githubusercontent.com/jieapi/AiCode/main/plugins/schema/plugin.schema.json`）；使用 `plugins/template` 开始的项目默认已指向仓库内路径，搬出去时改这一行即可。

## 顶层字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `$schema` | 否 | JSON Schema 地址，仅编辑器使用；宿主忽略 |
| `manifestVersion` | 是 | 固定为整数 `1` |
| `id` | 是 | 稳定插件标识，格式 `发布者.插件名` |
| `name` | 是 | 展示名称，字符串或按语言的对象，最多 80 字符，不能换行 |
| `version` | 是 | 语义化版本，如 `1.0.0`、`1.1.0-beta.1` |
| `publisher` | 是 | 发布者标识，必须等于 `id` 的第一段 |
| `repository` | 是 | 源码仓库，必须是 `https://` 地址，不能包含账号、查询参数或片段 |
| `description` | 是 | 用途说明，字符串或按语言的对象，最多 600 字符，允许换行 |
| `license` | 是 | 许可证名称，最多 100 字符 |
| `icon` | 否 | 插件列表与详情图标：内置图标名，或包内 `.png` / `.webp` 相对路径 |
| `engines` | 是 | 目前仅接受 `{"pluginApi": "版本范围"}` |
| `runtime` | 有工具、Hook 或动作型入口时必填 | 业务进程的入口与启动方式 |
| `contributes` | 是 | 提供的内容，至少一项 |
| `configPage` | 否 | 插件配置页，包内 HTML 相对路径；也可声明在 `contributes.configPage` 中 |

### 插件图标

`icon` 只接受两种值：

- 内置图标名：小写字母开头，仅含小写字母、数字和连字符，最多 32 个字符，如 `terminal`、`book`、`settings`、`cloud`、`tool`、`monitor`、`package`；未知名称回退 `package`。
- 包内图片：以 `.png` 或 `.webp` 结尾的相对路径，如 `icons/logo.png`。图片必须位于包内，单文件不超过 256 KiB；宿主按最大边 160px 采样解码，无需提供多套尺寸。

不支持 SVG、JPEG 等其它格式，清单里写这些后缀会直接拒绝安装。图片解码失败或未声明 `icon` 时显示宿主默认的 `package` 图标。插件 HTML 页面内部仍可自行使用包内 SVG 图片，那与清单图标无关。

### id 与 publisher

- `id` 形如 `community.demo-kit`：两段均以小写字母开头，仅含小写字母、数字和连字符，每段最多 32 个字符；
- `publisher` 必须等于 `id` 的第一段；
- 插件 ID 同时用作安装目录名，安装后不应依赖重命名区分版本；
- 局部 ID（技能、工具、页面等）由字母开头，仅含字母、数字、下划线、连字符，最多 64 个字符，同一类型内不可重复。

### 多语言文字

`name`、`description` 以及各贡献的 `title`、`description` 均可声明为字符串或语言对象：

```json
{
  "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
  "description": {"default": "Demo plugin.", "zh-CN": "演示插件。"}
}
```

取值顺序为：完整语言标签（`zh-CN`）→ 语言（`zh`）→ `default` → `en` → 第一项。标签大小写和 `-`/`_` 不敏感，最多 16 种语言。

### 版本范围

`engines.pluginApi` 支持 `^1.0.0`、`~1.0.0`、`>=1.0.0 <2.0.0`、精确版本 `1.0.0`。不支持 `1.x`、`*`、`||` 和区间简写。宿主插件 API 为 `1.0.0`，范围必须包含该版本。

## runtime

```json
{
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "resident": true,
    "dependencies": {"tool": "npm", "file": "package.json"}
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `type` | 是 | `javascript`（用 `node` 启动）或 `python`（用 `python3` 启动） |
| `entry` | 是 | 包内入口文件；JavaScript 用 `.js`/`.mjs`/`.cjs`，Python 用 `.py`，最大 512 KiB |
| `command` | 否 | 覆盖默认启动命令的字符串数组，直接交给 `execve`，不经 shell；最多 16 段，每段最多 200 字符，不得为绝对路径或包含 `..` |
| `dependencies` | 否 | 依赖输入：`tool` 为 `npm` 或 `pip`，`file` 分别为 `.json`（如 `package.json`）或 `.txt`（如 `requirements.txt`） |
| `resident` | 否 | 为 `true` 时进程不参与 10 分钟空闲回收，首次启动后常驻；停用、卸载、版本替换或 App 进程结束仍会关闭 |

存在 `contributes.tools`、`contributes.hooks`、`contributes.executionBackends`、`contributes.workflows`、`contributes.sessions` 或使用 `handler` 的 `uiEntries` 时必须提供 `runtime`。TypeScript 源码构建见[运行时与 SDK](/advanced/plugin/source-build#运行时与-sdk)。

## contributes

`contributes` 是一个对象，可用键如下。每类最多 64 项，至少要有其中一项：

| 键 | 类型 | 作用 |
| --- | --- | --- |
| `skills` | 数组 | 技能 Markdown |
| `agents` | 数组 | 子代理定义 |
| `tools` | 数组 | AI 可调用工具 |
| `hooks` | 数组 | 生命周期 hook |
| `modelAccess` | 对象 | 声明调用使用者已配置的 AI 供应商 |
| `mcp` | 数组 | MCP 服务器配置 |
| `providers` | 数组 | OpenAI 兼容提供商 |
| `executionBackends` | 数组 | 自定义执行后端（文件、命令、终端与工作区），最多 32 项 |
| `workflows` | 数组 | 自定义 Agent 工作流，最多 32 项 |
| `sessions` | 数组 | 外部会话源（增量同步与历史分页），最多 32 项 |
| `workspaceTools` | 数组 | 右侧项目工具抽屉中的页面 |
| `uiEntries` | 数组 | 挂载到宿主槽位的页面入口 |
| `shizuku` | 对象 | 声明使用 Shizuku |
| `overlay` | 对象 | 声明系统浮层 |
| `backup` | 对象 | 声明接收备份与还原事件 |
| `configPage` | 字符串 | 插件详情中的配置页 |

### skills / agents

```json
{
  "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
  "agents": [{"id": "reviewer", "file": "agents/reviewer.md"}]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一类型内唯一 |
| `file` | 是 | 包内 `.md` 相对路径，最大 512 KiB |

技能和子代理的格式见[技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp)。

### tools

```json
{
  "tools": [
    {
      "id": "echo",
      "title": {"default": "Echo", "zh-CN": "回显"},
      "description": {"default": "Echo the text.", "zh-CN": "回显文本。"},
      "inputSchema": "schemas/echo.input.json",
      "handler": "tools.echo"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，决定 AI 工具名的一部分 |
| `title` | 是 | 展示名称，最多 80 字符 |
| `description` | 否 | 供 AI 参考的用途说明，最多 600 字符；省略时为空字符串 |
| `inputSchema` | 是 | 包内 `.json` JSON Schema 文件，最大 256 KiB，根必须是 `{"type": "object"}` |
| `handler` | 是 | 入口脚本 handler 表中的键，如 `tools.echo` |

工具需要 `runtime`。声明不会执行 handler，首次调用时才会启动进程。

### hooks

```json
{
  "hooks": [
    {"id": "run-log", "event": "agent.run.finished", "handler": "hooks.runFinished"},
    {"id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform"},
    {"id": "review", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review"}
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `event` | 是 | 必须是宿主支持的事件，参见[生命周期 Hooks](/advanced/plugin/hooks) |
| `handler` | 是 | 入口脚本 handler 表中的键 |
| `mode` | 否 | `observe`（默认）、`transform`、`review`；可写事件必须显式声明对应模式，声明错误将导致拒绝安装 |

`backup.export.before` 与 `backup.import.finished` 会带出完整备份包或写入暂存区，必须同时声明 `contributes.backup` 并由使用者授权；若声明了这两个事件却没有 `contributes.backup`，安装会被拒绝。

### modelAccess

```json
{
  "modelAccess": {
    "purpose": "在团队群聊中轮流调用多个模型"
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符；显示在安装预览与插件详情 |

声明后可调用 `host.model.*` / `aicode.model.*`，请求计入对应供应商用量，插件拿不到 Key；未声明时返回 `MODEL_ACCESS_NOT_DECLARED`。调用方式见[模型句柄与流式调用](/advanced/plugin/model-handles)。

### mcp

```json
{
  "mcp": [{"id": "project-context", "file": "mcp/project-context.json"}]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `file` | 是 | 包内 `.json` 文件，最大 256 KiB，内容为现有 MCP 配置格式 |

### providers

```json
{
  "providers": [
    {
      "id": "gateway",
      "name": {"zh-CN": "本地网关", "en": "Local Gateway"},
      "description": {"zh-CN": "本机 OpenAI 兼容服务"},
      "format": "openai",
      "baseUrl": "http://127.0.0.1:18080/v1"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `name` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `format` | 是 | 目前仅支持 `openai` |
| `baseUrl` | 是 | `http`/`https` 绝对地址，不能包含账号、查询参数或片段，最长 2048 字符 |

宿主按此声明注册提供商；服务启动、模型与凭据维护见[插件提供商](/advanced/plugin/shizuku-providers#插件提供商)。插件 provider 的请求会自动携带 `X-AiCode-Session-Id` 请求头，值为当前 App 会话 ID，插件网关可用它把请求路由到对应会话。

### executionBackends

`executionBackends` 最多声明 32 项，由插件提供全局执行后端，启用后使用者可在工作区/执行环境中切换，使文件树、代码编辑器、Git、终端与内置工具通过插件读写目标环境。必须提供 `runtime`。

```json
{
  "executionBackends": [
    {
      "id": "cloud-box",
      "title": {"default": "Cloud Sandbox", "zh-CN": "云沙盒"},
      "description": "在远端沙盒中执行命令与读写文件",
      "capabilities": {
        "filesystem": true,
        "command": true,
        "terminal": true,
        "fileWatch": false,
        "portForward": false,
        "isRemoteEnvironment": true,
        "workspaceDiscovery": "hybrid",
        "projectConfigLocation": "appPrivateDir"
      },
      "handlers": {
        "fs": "backend.fs",
        "exec": "backend.exec",
        "execStream": "backend.execStream",
        "execHealth": "backend.health",
        "terminal": "backend.terminal",
        "workspaceList": "backend.wsList",
        "workspaceCreate": "backend.wsCreate",
        "workspaceDelete": "backend.wsDelete",
        "workspaceSelect": "backend.wsSelect"
      }
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一插件内唯一 |
| `title` | 是 | 本地化展示名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `capabilities` | 否 | 后端能力声明与工作区行为；省略对象或子字段时采用下表默认值 |
| `handlers` | 是 | runtime handler 映射，必须满足启用能力的要求；默认需要 `fs` 和 `exec` |

`capabilities` 字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `filesystem` | `true` | 是否支持文件访问；为 `true` 时必填 `handlers.fs` |
| `command` | `true` | 是否支持命令执行；为 `true` 时必填 `handlers.exec` |
| `terminal` | `false` | 是否支持终端 PTY；为 `true` 时必填 `handlers.terminal` |
| `fileWatch` | `false` | 插件后端文件变更监听当前未开放，只能省略或为 `false` |
| `portForward` | `false` | 插件后端端口转发当前未开放，只能省略或为 `false` |
| `isRemoteEnvironment` | `true` | 是否属于远程环境（用于区分默认工作区类型与界面展示） |
| `workspaceDiscovery` | `"scan"` | 工作区发现模式：`scan`（宿主拉取）、`register`（插件主动推送）或 `hybrid`（两者结合） |
| `projectConfigLocation` | `"appPrivateDir"` | 项目级 `.aicode` 存储位置：`workspaceDir`（工作区目录）或 `appPrivateDir`（App 私有目录），只接受 camelCase；当前 `workspaceDir` 仅允许显式声明 `isRemoteEnvironment: false` 的后端使用 |

`handlers` 支持以下可选键（按声明的能力提供）：`fs`、`exec`、`execStream`、`execHealth`、`terminal`、`workspaceList`、`workspaceCreate`、`workspaceDelete`、`workspaceSelect`。

当 `workspaceDiscovery` 为 `register` 或 `hybrid` 时，插件可通过以下宿主方法增量维护该后端下的工作区列表：

- `host.workspaces.upsert({ backendId, workspaces })`：按必填的 `id` 增量插入或更新工作区；
- `host.workspaces.remove({ backendId, workspaceIds })`：按工作区 ID 移除条目；
- `host.workspaces.set({ backendId, workspaces })`：全量替换该后端的工作区列表。

工作区条目结构、参数限制与 [UDS 二进制通道](/advanced/plugin/tools-runtime#uds-二进制通道) 见[工作区与外部会话](/advanced/plugin/tools-runtime#工作区与外部会话)。

### workflows

`workflows` 声明由插件接管的 Agent 推理与工具循环工作流。用户新建或切换会话工作流后，每轮执行由宿主运行器统一调度，并将插件推送的流式事件映射为标准聊天消息与工具进度卡片。必须提供 `runtime`。

```json
{
  "workflows": [
    {
      "id": "codex-loop",
      "title": {"default": "Codex Loop", "zh-CN": "Codex 工作流"},
      "description": "将单轮 Agent 推理托管给外部工作流引擎",
      "capabilities": {
        "streaming": true,
        "tools": true,
        "compaction": true,
        "titleGeneration": false
      },
      "handlers": {
        "startTurn": "wf.startTurn",
        "cancelTurn": "wf.cancelTurn",
        "compact": "wf.compact",
        "generateTitle": "wf.title"
      }
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，注册后的完整工作流 ID 为 `<插件ID>:<id>` |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `capabilities` | 否 | 能力标记：`streaming`（默认 `true`）、`tools`（默认 `true`）、`compaction`（默认 `false`）、`titleGeneration`（默认 `false`） |
| `handlers.startTurn` | 是 | 启动单轮执行的 handler |
| `handlers.cancelTurn` | 是 | 中断指定 `turnId` 的 handler |
| `handlers.compact` | 否 | 响应 `/compress` 上下文压缩的 handler |
| `handlers.generateTitle` | 否 | 生成会话标题的 handler；未提供或返回空时回退宿主默认逻辑 |

`startTurn` 建议通过 [UDS 二进制通道](/advanced/plugin/tools-runtime#uds-二进制通道) 的流式请求返回事件（未建连时自动回退至 NDJSON 事件推送），支持的事件 `type` 包括：`assistant.delta`、`reasoning.delta`、`assistant.message`、`tool.preparing`、`tool.started`、`tool.progress`、`tool.finished`、`retrying`、`keySwitched`、`contextUsage`、`compaction.started`、`compaction.finished`、`compaction.failed`、`modeChanged`、`completed`、`failed`。可在响应或结束事件中附带 `workflowState`，由宿主持久化并在下一轮原样回传。

### sessions

`sessions` 声明一个外部会话源，用于在侧栏展示由插件或远端系统托管的会话分组、分页读取历史消息，以及响应刷新与增删改置顶操作。必须提供 `runtime`。

```json
{
  "sessions": [
    {
      "id": "harness",
      "title": {"default": "Harness", "zh-CN": "外部会话"},
      "description": "同步远端外部会话列表与历史消息",
      "handlers": {
        "refresh": "sessions.refresh",
        "readMessages": "sessions.readMessages",
        "createSession": "sessions.create",
        "deleteSession": "sessions.delete",
        "renameSession": "sessions.rename",
        "pinSession": "sessions.pin"
      }
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，最多声明 32 项 |
| `title` | 是 | 本地化分组标题，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `handlers.readMessages` | 是 | 分页读取指定外部会话历史消息的 handler |
| `handlers.refresh` | 否 | 宿主触发列表刷新时调用的 handler |
| `handlers.createSession` | 否 | 新建外部会话的 handler |
| `handlers.deleteSession` | 否 | 删除外部会话的 handler |
| `handlers.renameSession` | 否 | 重命名外部会话的 handler |
| `handlers.pinSession` | 否 | 置顶或取消置顶外部会话的 handler |

`sessions` 仅负责会话目录与历史读取，不再耦合消息发送通道；在外部会话中发送消息时，若会话绑定了 `workflowId`（或使用默认工作流），会由宿主运行器调度对应工作流执行并在内存中投影更新。

插件侧可通过以下宿主方法增量维护外部会话分组：

- `host.sessions.upsert`：按 `sessionId` 合并新增或更新会话摘要；
- `host.sessions.remove`：移除指定 `sessionIds`；
- `host.sessions.replaceAll`：全量对齐该会话源的会话列表；
- `host.sessions.setLoading`、`host.sessions.setError`、`host.sessions.setThinking`：控制分组加载态、错误横幅与会话等待气泡。

详细参数见[工作区与外部会话](/advanced/plugin/tools-runtime#工作区与外部会话)。

### 主动授权请求

插件需要用户授权时，可以使用原生授权卡片：

```ts
const result = await hostCall('host.permissions.request', {
  sessionId: appSessionId,
  requestId: remoteRequestId,
  title: '执行远端命令',
  description: '运行项目测试',
  operation: {kind: 'command', command: 'npm test', workingDirectory: '/project'},
});
if (result.decision === 'allow') {
  // 将本次决定回传给实际执行方。
}
```

`sessionId` 必须是当前工作区会话 ID。返回 `allow` / `deny` / `cancel`，只批准本次请求，不产生永久授权，也不覆盖宿主工具的权限判断。超时、工作区切换、会话移除或插件停用时取消。可用 `host.permissions.cancel({requestId})` 撤回；页面对应 `aicode.permissions.request(options)` / `cancel(requestId)`。卡片由 App 固定标注插件来源。请求默认等待 2 分钟，可用 `timeoutMs` 设置 1 秒至 10 分钟。

### workspaceTools

```json
{
  "workspaceTools": [
    {
      "id": "build-apk",
      "title": {"default": "Build APK", "zh-CN": "构建 APK"},
      "description": {"default": "Build the current project.", "zh-CN": "构建当前项目。"},
      "icon": "hammer",
      "category": "构建",
      "pageFile": "pages/build-apk.html"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `icon` | 否 | 图标名，默认 `play` |
| `category` | 否 | 分类名，默认 `通用` |
| `pageFile` | 是 | 包内 `.html`/`.htm` 页面，最大 512 KiB |

### uiEntries

```json
{
  "uiEntries": [
    {
      "id": "memory",
      "title": {"zh-CN": "记忆管理", "en": "Memory"},
      "slot": "settings.ai",
      "page": "pages/memory.html",
      "icon": "book"
    },
    {
      "id": "sync",
      "title": {"zh-CN": "同步笔记", "en": "Sync notes"},
      "slot": "drawer.footer",
      "icon": "refresh",
      "handler": "actions.sync",
      "confirm": true
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一插件的入口不可重名 |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `slot` | 是 | 宿主槽位，仅可取下表的值 |
| `page` | 二选一 | 包内 `.html`/`.htm` 页面，最大 512 KiB |
| `handler` | 二选一 | runtime 的 handler 名；需要声明 `runtime` |
| `confirm` | 否 | 仅动作入口：执行前弹确认框 |
| `icon` | 否 | 图标名，默认 `book`，最长 32 字符 |

除 `backup.data` 外，`page` 与 `handler` 必须且只能出现一个。`settings.*` 槽位只接受 `page`；`files.tree.menu` 与 `editor.selection.menu` 只接受 `handler`；`backup.data` 只接受数据选项（不能写 `page` / `handler` / `confirm`）。动作上下文与返回行为见[槽位入口](/advanced/plugin/ui-pages#槽位入口)。

| `slot` | 位置 |
| --- | --- |
| `settings.general` | 设置页「通用」分组末尾 |
| `settings.ai` | 设置页「AI 配置」分组末尾 |
| `settings.environment` | 设置页「运行环境」分组末尾 |
| `settings.permissions` | 设置页「权限与后台」分组末尾 |
| `settings.data` | 设置页「数据与诊断」分组末尾 |
| `settings.help` | 设置页「帮助与关于」分组末尾 |
| `drawer.footer` | 侧栏底部，浏览器与设置之上 |
| `chat.header.menu` | 聊天顶栏「更多」菜单底部 |
| `chat.welcome` | 聊天空态建议区，最多展示 3 条 |
| `chat.session.menu` | 会话长按菜单，删除项之前 |
| `files.tree.menu` | 文件树文件/目录长按菜单，插件项在删除之前 |
| `editor.selection.menu` | 代码编辑器选中文本后的浮动菜单 |
| `backup.data` | 备份页「选择导出数据」组末尾，只接受数据选项 |

同一插件在每个槽位最多声明 2 个入口（`backup.data` 最多 1 个）；超出会在安装时拒绝。入口顺序由宿主决定：宿主自己的项始终在前，插件项按插件显示名与声明顺序排列，插件不能指定排序或插入位置。

### configPage

```json
{
  "contributes": {
    "configPage": "pages/config.html"
  }
}
```

也可写在顶层：

```json
{
  "configPage": "pages/config.html",
  "contributes": {"skills": [{"id": "hello", "file": "skills/hello/SKILL.md"}]}
}
```

配置页仅出现在「设置 → 插件」的插件详情中，与启停、卸载等原生项通过分段控件切换，不会混入设置主菜单。必须是包内 `.html`/`.htm` 文件。

### shizuku

```json
{
  "contributes": {
    "shizuku": {"purpose": "切换免打扰与查询系统设置"}
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符，展示在授权界面 |

声明不会自动获得授权。使用者需在插件详情中单独开启 Shizuku 权限，且系统中的 Shizuku 服务须正在运行。

### overlay

```json
{
  "contributes": {
    "overlay": {
      "purpose": "悬浮小组件与状态 HUD",
      "surfaces": [
        {"id": "pet", "page": "pages/pet.html", "width": 240, "height": 320, "draggable": true, "snap": "x"}
      ]
    }
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符 |
| `surfaces` | 是 | 至少 1 个、最多 3 个浮层页面 |

每个 surface：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一插件内唯一 |
| `page` | 是 | 包内 `.html`/`.htm` 页面 |
| `width` | 否 | 默认宽度，单位 dp，默认 240，范围 48–720 |
| `height` | 否 | 默认高度，单位 dp，默认 320，范围 48–1200 |
| `draggable` | 否 | 是否允许拖动，默认 `true` |
| `snap` | 否 | 松手吸附轴：`none`、`x`、`y`、`both`，默认 `none` |

浮层需要使用者单独授权，且 App 需获得「显示在其他应用上层」的系统权限。

### backup

```json
{
  "contributes": {
    "backup": {"purpose": "把额外数据随备份一起导出"},
    "hooks": [
      {"id": "before-export", "event": "backup.export.before", "handler": "hooks.onExportBefore"},
      {"id": "after-import", "event": "backup.import.finished", "handler": "hooks.onImport"}
    ],
    "uiEntries": [
      {"id": "extra-data", "title": "记忆 / 技能", "slot": "backup.data", "icon": "package"}
    ]
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符，展示在授权界面 |

声明不会自动获得授权。使用者需在插件详情中单独开启「备份与还原事件」授权，停用期间不派发，重新启用后恢复；卸载插件会撤销授权。

使用者在备份页勾选 `backup.data` 数据选项后，插件参与导出。暂存目录、备份区段与事件 payload 见[备份与还原事件](/advanced/plugin/hooks#备份与还原事件)。

## 会被拒绝的声明方式

| 声明方式 | 结果 |
| --- | --- |
| 顶层或贡献中出现未支持的字段 | `UNSUPPORTED_FIELD`，提示具体路径 |
| `manifestVersion` 不是 `1` | 拒绝安装 |
| `id` 不是两段、包含大写或下划线 | `FIELD_INVALID` |
| `publisher` 与 `id` 第一段不一致 | `FIELD_INVALID` |
| `repository` 不是 https、包含账号或查询参数 | `FIELD_INVALID` |
| 技能/子代理的 `file` 不是 `.md` | `FIELD_INVALID` |
| 工具 `inputSchema` 不是 `.json` 或根不是 object | `FIELD_INVALID` / `CONTENT_INVALID` |
| 存在 `tools`、`hooks`、`executionBackends`、`workflows` 或 `sessions` 但未声明 `runtime` | `FIELD_MISSING` |
| `executionBackends` 声明了能力但缺少对应 `handlers.fs` / `exec` / `terminal` | `FIELD_MISSING` |
| `executionBackends` 的 `fileWatch` 或 `portForward` 为 `true`，或远端后端使用 `workspaceDir` | `FIELD_INVALID` |
| `workflows` 缺少 `handlers.startTurn` / `cancelTurn` 或 `sessions` 缺少 `handlers.readMessages` | `FIELD_MISSING` |
| hook 的 `event` 不在支持列表，或 `mode` 与事件不匹配 | `FIELD_INVALID` |
| 同一类型内 `id` 重复 | `DUPLICATE_ID` |
| `contributes` 为空或仅包含未支持的类型 | `FIELD_INVALID` |

更完整的导入报错排查参见[打包、导入与排错](/advanced/plugin/packaging)。
