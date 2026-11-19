# AI 工具与插件运行时

AI 工具使模型能够调用插件自身代码，同一运行时还可响应 Hook 和页面调用。推荐用 TypeScript + SDK 注册 handler，源码写法见[运行时与 SDK](/advanced/plugin/source-build#运行时与-sdk)；本篇说明工具行为、宿主接口与底层协议。

## 声明工具

在 `contributes.tools` 中声明 `handler` 和 `inputSchema`，并提供 `runtime`；字段写法见[工具清单](/advanced/plugin/manifest#tools)。

`inputSchema` 指向包内 JSON 文件，例如 `schemas/echo.input.json`：

```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string", "description": "要回显的文本"}
  },
  "required": ["text"]
}
```

`handler` 是入口脚本 handler 表中的键。工具对 AI 暴露的名称由宿主生成：

```text
plugin__<清洗后的插件ID>__<清洗后的工具id>
```

例如 `demo.hello` 的 `echo` 显示为 `plugin__demo_hello__echo`。点号及其他非法字符将被替换为下划线，插件不可覆盖内置工具名称。

首次调用会显示工具授权提示；PLAN 模式下按外部工具拦截。每次调用都会重新执行权限判断，不会因上次批准而放行。

## 运行时入口

`runtime` 支持：

| 类型 | 启动命令 | 入口后缀 |
| --- | --- | --- |
| `javascript` | `node <entry>` | `.js`、`.mjs`、`.cjs` |
| `python` | `python3 <entry>` | `.py` |

可使用 `command` 覆盖启动命令；数组直接交给 `execve`，不经 shell。入口文件最大 512 KiB，工作目录为插件自身的安装目录。

`resident: true` 表示进程不参与 10 分钟空闲回收。插件停用、卸载、版本替换或 App 进程结束时仍会关闭。常驻进程会持续占用内存和容器资源，仅在确需常驻服务时启用。

工具调用单次超时为 120 秒。超时或进程异常将结束该次调用并返回工具错误；常驻进程不会因该次超时被直接终止。

## 进程协议

TypeScript SDK 负责消息收发；使用其他语言或自行接入协议时，宿主与插件均按行读写 JSON，一行一条消息，不可跨行。

宿主发往插件：

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `tool.invoke` | `{handler, args}` | 执行 AI 工具 |
| `hook.invoke` | `{handler, args}` | 执行生命周期 hook |
| `ui.invoke` | `{handler, args}` | 页面通过 `aicode.invoke` 调用进程 |
| `backend.invoke` | `{handler, args}` | 执行后端文件、命令、终端与工作区操作 |
| `workflow.invoke` | `{handler, args}` | 执行 Agent 工作流轮次、取消、压缩或标题生成 |

插件使用同一条 `id` 返回 `result` 或 `error`：

```json
{"jsonrpc":"2.0","id":1,"result":{"text":"hello"}}
{"jsonrpc":"2.0","id":2,"error":{"code":"PLUGIN_ERROR","message":"something failed"}}
```

进程调用宿主使用 `host.*` 请求，SDK 的 `hostCall` 会生成 `id` 并匹配响应。自行实现协议时，也需要区分响应与通知。

页面事件使用 SDK 的 `emit`，用法见[插件事件](/advanced/plugin/ui-components#插件事件)。自行实现运行时时，按行输出不带 `id` 的通知：

```json
{"jsonrpc":"2.0","method":"plugin.event","params":{"name":"records.changed","payload":{"count":4}}}
```

### UDS 二进制通道

对于高吞吐场景（`executionBackends` 文件字节传输、流式命令输出、`pty` 终端以及 `workflows` 流式事件），宿主在启动插件进程时会通过环境变量注入 Unix Domain Socket（`AF_UNIX`）通道参数：

| 环境变量 | 说明 |
| --- | --- |
| `AICODE_IPC_SOCKET` | 容器内 Unix Domain Socket 文件路径 |
| `AICODE_IPC_TOKEN` | 建连首帧握手用的鉴权令牌 |

使用 `@jieapi/plugin-sdk` 的 `definePlugin(...)` 时，SDK 会自动在 `AICODE_IPC_SOCKET` 创建监听服务端并在就绪后向 `stdout` 发送 `{"jsonrpc":"2.0","method":"ipc.ready","params":{"socket":...}}`。宿主连接后立即发送 `PING` 帧携带 `{"token": "..."}`，校验通过后回复 `PONG` 完成握手；此后 `hostCall`、`emit` 与 `backend.invoke` / `workflow.invoke` 均优先复用该二进制通道，未连接或通道不可用时无缝回退到 `stdin`/`stdout` NDJSON。

二进制帧采用 16 字节大端（Big-Endian）帧头，将调度元数据与原始二进制载荷分离（免去 Base64 编码与 JSON 转义开销）：

```text
+------------+---------+------+----------+----------+------------+-------------------+-------------------+
| Magic (2B) | Ver(1B) | Type | StreamId | MetaLen  | PayloadLen | Meta (UTF-8 JSON) | Payload (Raw)     |
| 0x4143     | 0x01    | 1B   | 4B (u32) | 4B (u32) | 4B (u32)   | MetaLen bytes     | PayloadLen bytes  |
+------------+---------+------+----------+----------+------------+-------------------+-------------------+
```

- **帧类型（`Type`）**：`REQ (1)`、`RESP (2)`、`ERR (3)`、`STREAM_DATA (4)`、`STREAM_END (5)`、`EVENT (6)`、`PING (7)`、`PONG (8)`。
- **分片与限额**：`Meta` 上限 `1 MiB`，单帧 `Payload` 上限 `16 MiB`，默认分片大小 `64 KiB`。大流量按 `StreamId` 多路复用并与控制帧分队列写出，避免文件读写或日志流阻塞取消、审批等控制请求。
- **SDK 辅助能力**：可使用 `binaryResult(payload, meta)` 直接返回 `Uint8Array` 二进制载荷；`workflow.invoke` handler 支持返回异步生成器（`async function*`）逐条 `yield` 工作流事件；高频增量输出可使用 `createEventCoalescer` 进行微批合并。

## host API 细节

进程侧统一通过 `hostCall('host.方法', {参数})` 调用；页面桥的部分方法使用位置参数，不能直接照搬调用形式。

方法名与入参在 SDK 里有类型声明（`@jieapi/plugin-sdk` 的 `HostMethodMap`）：不写泛型时，方法名有补全、拼错会在编译期报错，参数与返回值自动推导；需要自己指定返回类型时仍可写 `hostCall<T>('host.方法', {参数})`，这种写法只校验字段名与字段类型，不校验字段与方法是否对应——能用推导就别写泛型。

自行实现协议时，方法名以宿主实际支持为准，写错会在运行期返回 `METHOD_UNKNOWN`。下表与 SDK 声明一一对应。

### 方法废弃与迁移

宿主移除某个 `host.*` 方法前会先标废弃：插件仍能编译、调用仍然有效，但编辑器会在调用处标删除线，悬停能看到取代它的方法与计划移除的版本（`1.4.0` 这种）。看到这类提示请尽快迁移：要到后续版本宿主才真正删除分支，那之后调用返回 `METHOD_UNKNOWN`。

`engines.pluginApi` 不会跟着单个方法的废弃升高——只废弃一两个方法却在清单上报不兼容，会连带拒绝只用其他 API 的插件，所以废弃走方法级过渡，不靠整体版本门禁。

### 文件

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.fs.read` | `{path}` | `{content, size}`；单次最多 512 KiB |
| `host.fs.write` | `{path, content}` | 原子写；内容最多 1 MiB |
| `host.fs.list` | `{path}` | `{entries: [{name, isDir, size}]}`；最多 500 条 |
| `host.fs.stat` | `{path}` | `{exists, isDir, size}` |
| `host.fs.delete` | `{path}` | 删除文件或目录 |
| `host.fs.mkdir` | `{path}` | 创建目录（含父目录） |
| `host.fs.rename` | `{from, to}` | 移动/重命名；目标存在返回 `FILE_EXISTS` |
| `host.fs.copy` | `{from, to, overwrite?}` | 复制文件或目录；`overwrite` 默认 false |
| `host.fs.append` | `{path, content}` | 追加文本；总大小不超过 1 MiB |
| `host.fs.readBinary` | `{path}` | `{base64, size}`；单次最多 512 KiB |
| `host.fs.writeBinary` | `{path, base64}` | Base64 写入；解码后最多 1 MiB |

宿主文件接口的路径规则与[页面文件接口](/advanced/plugin/ui-pages#文件)相同，支持真实路径、`~` 和 `data://`。进程另有 `AICODE_DATA_DIR` 与 `AICODE_WORKSPACE` 环境变量；SSH 模式下，`host.fs.*` 操作远端路径，进程自身的文件操作仍发生在手机容器。

### 命令与终端

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.bash` | `{command, timeout?}` | `{output, exitCode, truncated}`；默认 30 秒，范围 1–300 秒；命令最长 4000 字符 |
| `host.terminal.run` | `{command, title?}` | `{tabId, state}`；命令最长 4000 字符 |
| `host.terminal.poll` | `{tabId, offset}` | `{output, offset, state, exitCode?, truncated}`；仅保留最近 128 KiB |
| `host.terminal.stop` | `{tabId}` | 发送 Ctrl-C，2 秒未退出则关闭 |
| `host.terminal.close` | `{tabId}` | 关闭标签 |
| `host.terminal.write` | `{tabId, text, appendNewline?}` | 向标签写入输入；文本最多 4000 字符 |
| `host.terminal.list` | 无 | 列出该插件自己的标签 `{tabId, title, command, state}` |

终端标签的归属、显示和生命周期与[页面终端接口](/advanced/plugin/ui-pages#命令与终端)相同。

### 会话与消息

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.chat.listSessions` | `{includeSubagents?, limit?}` | `{sessions}`；仅当前工作区，`limit` 默认 50、上限 200 |
| `host.chat.createSession` | `{title?, mode?}` | `{session}`；`mode` 仅 `build` / `plan` |
| `host.chat.listMessages` | `{sessionId, limit?, before?, after?}` | `{sessionId, messages, hasMore}`；`before` / `after` 互斥 |
| `host.chat.sendMessage` | `{sessionId, text}` | `{sessionId, accepted}`；启动普通 AI 轮次，需要聊天页存活 |
| `host.chat.setDraft` | `{text, sessionId?, append?}` | `{sessionId?, accepted}`；写入输入框，`append` 为 true 时追加，需要聊天页存活 |
| `host.chat.stop` | `{sessionId}` | `{sessionId, accepted}`；停止该会话的 AI 轮次 |
| `host.chat.openSession` | `{sessionId}` | `{sessionId, accepted}`；切换聊天页当前会话 |
| `host.chat.getSession` | `{sessionId}` | `{session}` |
| `host.chat.renameSession` | `{sessionId, title}` | 重命名；标题最多 100 字 |
| `host.chat.pinSession` | `{sessionId, pinned}` | 置顶/取消置顶 |
| `host.chat.deleteSession` | `{sessionId}` | 删除会话；需要聊天页存活 |
| `host.chat.getRunState` | `{sessionId}` | `{sessionId, run}` |
| `host.chat.waitForRun` | `{sessionId, timeoutMs?}` | `{timedOut, run}`；默认 120 秒，范围 1–600 秒 |
| `host.chat.listApprovals` | `{sessionId}` | `{tool, question, plan}` |
| `host.chat.resolveToolApproval` | `{sessionId, requestId, choice}` | `once` / `always` / `reject` |
| `host.chat.resolveQuestion` | `{sessionId, requestId, answers}` | 回答 `AskUserQuestion` |
| `host.chat.resolvePlanApproval` | `{sessionId, requestId, choice}` | `approve` / `refine` |

会话范围、消息格式、运行状态与审批行为见[页面会话接口](/advanced/plugin/ui-pages#会话与消息)。进程侧可用 `host.chat.waitForRun` 或 `agent.run.finished` Hook 等待结果；`sendMessage` 仅表示入队，不等待 AI 完成。`sendMessage` / `stop` / `openSession` / `deleteSession` 需要聊天页存活，否则返回 `CHAT_UI_UNAVAILABLE`。

### 系统、密钥与网络

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.notify` | `{title, body, key?}` | 标题最多 100 字，正文最多 1000 字，`key` 最多 64 字；同一 `key` 覆盖上一条 |
| `host.tip` | `{title?, text, action?{label, handler}}` | 标题最多 80 字，正文最多 500 字，按钮文字最多 24 字；同插件仅保留最新一条，队列最多 3 条 |
| `host.app.isForeground` | 无 | App 是否有 Activity 处于 STARTED |
| `host.clipboard.read` / `write` | `{}` / `{text}` | 剪贴板文本，上限 1 MiB |
| `host.share.text` | `{text, title?}` | 拉起系统分享；文本最多 100000 字符 |
| `host.ui.openUrl` | `{url}` | 仅 http/https，最长 4096 字符 |
| `host.app.info` | 无 | 包名、版本、SDK、语言、前后台 |
| `host.log` | `{level, message, tag?}` | 写宿主日志；正文最多 2000 字符 |
| `host.secrets.get` / `set` / `delete` | `{key}` / `{key, value}` / `{key}` | 加密键值；值最多 64 KiB |
| `host.secrets.list` | 无 | `{keys}`，只返回键名 |
| `host.http.request` | `{url, method?, headers?, body?, timeout?}` | `{status, headers, body, truncated}`；不跟随重定向、不注入凭据 |

`host.http.request` 只接受 http/https；URL 最长 4096 字符，headers 最多 64 项且禁止 Host/Content-Length 等受控头，请求体与响应体各最多 1 MiB，超时 1–60 秒。

### 工具与 MCP

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.tools.list` | `{includeSchema?}` | `{tools}`；最多 200 项，描述最多 500 字符；`includeSchema` 为 true 时附带参数 schema |
| `host.tools.execute` | `{name, arguments, sessionId?, context?, timeoutMs?}` | 工具结果；只允许内置 / MCP 工具，ASK 时挂起等待审批 |
| `host.tools.pending` | `{sessionId?}` | `{approval}`；没有待审批请求时 `approval` 为 `null` |
| `host.tools.resolve` | `{requestId, choice}` | `{ok}`；`choice` 为 `once` / `always` / `reject` |
| `host.mcp.status` | 无 | `{servers}` 连接状态 |

### 专用能力接口

| 接口 | 参考文档 |
| --- | --- |
| `host.model.*`：模型列表、句柄与流式请求 | [模型句柄与流式调用](/advanced/plugin/model-handles#页面与进程接口) |
| `host.providers.*`：维护本插件提供商的模型与凭据 | [模型与凭据维护](/advanced/plugin/shizuku-providers#由插件维护模型与凭据) |
| `host.shizuku.exec`：Android 系统命令 | [Shizuku](/advanced/plugin/shizuku-providers#shizuku) |
| `host.overlay.*`：系统浮层管理与消息 | [进程接口](/advanced/plugin/overlay#进程接口) |

### 端口映射

| 方法 | 参数 | 返回 / 限制 |
| --- | --- | --- |
| `host.ports.forward` | `{port, host?, localPort?}` | `{id, localPort, remoteHost, remotePort, url, direct}`；`host` 默认 `127.0.0.1`，`localPort` 被占用时向后顺延，以返回值为准 |
| `host.ports.close` | `{id}` | `{closed}`；只能关闭本插件建立的映射 |
| `host.ports.list` | 无 | 本插件的映射数组 |

`host.ports.forward` 把远程服务器视角的 `host:port` 映射到手机 `127.0.0.1:localPort`，复用宿主已建立的 SSH 连接，插件不接触服务器凭据。仅绑定 `127.0.0.1`，不向局域网暴露；同一插件重复映射同一目标会复用原映射；每插件最多 8 条、全局 32 条。

本地容器模式下容器与 App 处于同一网络命名空间，无需隧道：返回 `direct: true`，`url` 可直接访问。SSH 模式下建隧道前会先探测目标，目标未监听或服务器 `AllowTcpForwarding no` 时立即返回 `TARGET_UNREACHABLE`；连接断开或切换执行环境时宿主统一回收映射，不会跨连接残留。

### 工作区与外部会话

声明了 `contributes.executionBackends` 或 `contributes.sessions` 的已激活插件，可通过以下方法增量维护自己的工作区与外部会话分组（`backendId` / `sourceId` 必须归属当前插件，支持本地 ID 或 `<插件ID>:<局部ID>`）：

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `host.workspaces.upsert` | `{backendId, workspaces}` | 按必填的工作区 `id` 增量插入或更新工作区；单次最多 500 条 |
| `host.workspaces.remove` | `{backendId, workspaceIds}` | 按 ID 批量移除该执行后端下的工作区 |
| `host.workspaces.set` | `{backendId, workspaces}` | 全量替换该执行后端下的工作区列表 |
| `host.sessions.upsert` | `{sourceId, sessions}` | 按 `sessionId` 增量新增或更新外部会话摘要（局部 Diff 更新） |
| `host.sessions.remove` | `{sourceId, sessionIds}` | 按 `sessionId` 批量移除指定外部会话 |
| `host.sessions.replaceAll` | `{sourceId, sessions}` | 全量替换该会话源的会话列表（首次拉取或重同步使用） |
| `host.sessions.setLoading` | `{sourceId, loading}` | 设置外部会话分组的加载指示状态 |
| `host.sessions.setError` | `{sourceId, error}` | 设置或清除外部会话分组的错误提示（传 `null` 清除） |
| `host.sessions.setThinking` | `{sourceId, sessionId, visible, text?}` | 控制外部会话聊天页的等待思考气泡展示与文案 |

`workspaces` 数组中的每个 `WorkspaceItem` 包含必填的 `id`、`name`、`path`，以及可选的 `group`、`available`（默认 `true`）和嵌套的 `capabilities`（可选布尔字段 `createChild`、`delete`、`git`），能力字段不能平铺在条目顶层。例如：

```json
{
  "backendId": "cloud-box",
  "workspaces": [{
    "id": "demo",
    "name": "Demo",
    "path": "/srv/demo",
    "group": "cloud",
    "available": true,
    "capabilities": { "createChild": true, "delete": false, "git": true }
  }]
}
```

移除条目使用 `host.workspaces.remove({backendId: 'cloud-box', workspaceIds: ['demo']})`，参数名不是 `ids`。`host.sessions.setThinking` 的 `visible` 是必填布尔值。

外部会话摘要可关联 `workflowId`、`backendId`、`workspaceId` 等元数据，供用户在聊天页继续发送消息时由宿主路由到对应工作流。`readMessages` 返回消息的时间字段为 `createdAt`，分页的 `nextCursor` 可为任意 JSON 值（对象、数组、字符串、数字、布尔或 `null`），下一页读取时由宿主原样传入 `cursor`，不要自行转为字符串：

```json
{
  "messages": [{
    "id": "m1",
    "role": "assistant",
    "createdAt": 1700000000000,
    "blocks": [{ "type": "text", "text": "Hello" }]
  }],
  "hasMore": true,
  "nextCursor": { "page": 2, "boundary": ["m1", 123] }
}
```

### 执行后端 handler 参数

后端调用通过 `backend.invoke` 分发，`args.backendId` 为后端完整 ID。后端 `fs` handler 与插件调用的 `host.fs.*` 是两套不同接口，不要混用操作名或参数。

`fs` 的正式 `op` 为 `readFile`、`writeFile`、`readBytes`、`writeBytes`、`listFiles`、`listFilesRecursive`、`stat`、`fingerprint`、`permissions`、`resolvePath`、`delete`、`deleteRecursively`、`rename`、`copy`、`move`、`mkdirs`。所有操作都带 `path`；其余参数为：

| 操作 | 额外参数 |
| --- | --- |
| `writeFile` | `overwrite: boolean`、`encoding: string`；内容通过二进制 payload 传输 |
| `writeBytes` | `overwrite: boolean`；字节通过二进制 payload 传输 |
| `readBytes` | 可选 `maxBytes: number` |
| `listFilesRecursive` | `maxDepth: number` |
| `resolvePath` | `followFinalLink: boolean` |
| `rename` | `newPath: string` |
| `copy`、`move` | `newPath: string`、`overwrite: boolean` |

`permissions` 属于正式操作名；当前宿主读取权限时从 `stat` 返回的 `permissions` 字段取值。`readFile` / `readBytes` 可通过二进制 payload 返回内容，`stat` / `listFiles` 等元数据操作返回 JSON。文件监听与插件后端端口转发当前未开放，清单 `fileWatch` / `portForward` 只能省略或为 `false`，不能通过声明 `true` 启用。

`exec` 与 `execStream` 收到 `{backendId, command, projectPath?, timeoutMs, unbounded?}`；工作目录字段为 `projectPath`，超时单位为毫秒。`unbounded: true` 表示同步执行请求扩大输出限制，不代表无限超时。同步结果为 `{output, exitCode, truncated?}`；流式命令返回文本块，退出帧使用 `{type: 'exit', exitCode}`。

`terminal` 当前操作为 `start`、`status`、`send`、`write`、`writeBytes`、`read`、`stream`、`list`、`close`。`start` 使用 `command`、可选 `title` / `notify` / `sourceSessionId` / `ownerPluginId`；除 `start` / `list` 外均使用 `tabId`，`send` 还需要 `input` 与 `appendNewline`，`write` 需要 `text`，`writeBytes` 使用二进制 payload。不要使用未开放的 `open` / `resize` 等操作。

### 工具目录与代执行

`host.tools.list({includeSchema?})` 返回宿主注册的工具：`name`、`description`、`type`（`builtin` / `mcp` / `plugin`）、`permissionPolicy`（如 `ask`、`auto_approve`）与 `capabilities`。`includeSchema: true` 时额外返回 `inputSchema`；单个 schema 超过 64 KiB 或累计超过 256 KiB 时省略该项，不会报错。最多返回 200 项。

`host.tools.execute({name, arguments, sessionId?, context?, timeoutMs?})` 把一次工具调用交给宿主：

| 项 | 行为 |
| --- | --- |
| 工具范围 | 只允许内置与 MCP 工具；`plugin__` 前缀返回 `TOOL_NOT_ALLOWED`，未知工具返回 `TOOL_NOT_FOUND` |
| 会话型工具 | `askUserQuestion`、`planMode`、`goalStatus`、`todo`、`task`、`messageParent` 依赖聊天会话，经此执行返回 `TOOL_NOT_ALLOWED` |
| 会话命名空间 | `sessionId` 缺省 `plugin:<插件ID>`，只能使用该会话或它的 `:` 子会话，其它值返回 `SESSION_FORBIDDEN` |
| 上下文 | `context` 支持 `path`、`selection`、`language`；`projectRoot` 始终是当前工作区 |
| 超时 | `timeoutMs` 默认 120000，范围 1000–600000，同时约束等待授权与执行本身 |
| 结果 | `{status, data?, message?, code?, images?, imagesTruncated?, dataTruncated?}`；`status` 为 `success` / `error` / `partial` |

`data` 超过 256 KiB 时转为截断字符串并带 `dataTruncated: true`；结果图片为 `{mimeType, base64, path?}`，单图超过 1.5 MiB 或合计超过 2 MiB 时整张省略并带 `imagesTruncated: true`。`arguments` 超过 2 MiB 返回 `PARAM_INVALID`。

权限策略判定为 ASK 时，`execute` 挂起等待确认，请求不进入聊天页弹窗队列，也不会点亮侧边栏等待指示。插件需轮询 `host.tools.pending({sessionId})`，由自己的页面展示 `approval`：

| 字段 | 说明 |
| --- | --- |
| `id` | 请求 ID，传给 `resolve` |
| `toolName` | 工具名 |
| `title` / `summary` | 标题与摘要 |
| `details` / `argsPreview` | 详细信息与参数预览，各最多 4000 字符 |
| `sessionId` | 发起请求的插件会话 |
| `rememberablePatterns` | 可记住的授权范围；为空时不展示「始终允许」 |
| `rememberDisabledReason?` | 不能记住时的原因 |

使用者点击后调用 `host.tools.resolve({requestId, choice})`，`choice` 为 `once` / `always` / `reject`；`always` 仅在 `rememberablePatterns` 非空时有效。`pending` 只返回指定会话的请求，`resolve` 只接受当前插件会话命名空间内的请求。

等待授权超时返回 `TOOL_APPROVAL_TIMEOUT`，执行超时返回 `TOOL_TIMEOUT`，策略拒绝返回 `TOOL_DENIED`，使用者拒绝返回 `TOOL_USER_REJECTED`；请求已过期、已处理或不属于当前插件时返回 `APPROVAL_NOT_FOUND`。

工具调用绑定发起时的工作区与执行环境。评估或等待审批期间切换工作区、本地 / SSH 模式或 SSH 服务器，会使旧请求失效；应提示使用者在新环境重新发起，不要自动重试写操作。失效请求不会执行，也不会把「始终允许」写入新项目。

页面侧使用 `aicode.tools.list` / `execute` / `pending` / `resolve`，调用形式见[页面工具接口](/advanced/plugin/ui-pages#工具与-mcp)。页面审批流程与模型工具循环见[模型句柄与流式调用](/advanced/plugin/model-handles#审批流程)。

## handler 约定

`hostCall` 从插件 SDK 导入，注册方式见[运行时与 SDK](/advanced/plugin/source-build#运行时与-sdk)。

handler 可返回字符串、数字、布尔、数组或对象。抛出的异常会作为工具错误返回给 AI。

```ts
import { definePlugin, hostCall } from '@jieapi/plugin-sdk';

definePlugin({
  handlers: {
    'tools.notify': async (args) => {
      await hostCall('host.notify', {
        title: String(args.title ?? ''),
        body: String(args.body ?? ''),
        key: args.key === undefined ? undefined : String(args.key)
      });
      return {ok: true, logs: ['notification sent']};
    }
  }
});
```

`logs` 最多 50 行、单行 500 字符，写入应用日志，不会作为工具结果展示给 AI。

页面调用同一 handler 的方式见[进程调用](/advanced/plugin/ui-pages#进程调用)。SDK 已处理 `ui.invoke`；自行实现协议时缺少该方法会返回 `METHOD_UNKNOWN`。

## 依赖准备

插件依赖第三方库时，请勿提交 `node_modules` 或虚拟环境。应声明依赖输入：

```json
{
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "dependencies": {"tool": "npm", "file": "package.json"}
  }
}
```

或：

```json
{
  "runtime": {
    "type": "python",
    "entry": "main.py",
    "dependencies": {"tool": "pip", "file": "requirements.txt"}
  }
}
```

在插件详情页点击「准备依赖」，宿主会按依赖输入文件和 lockfile 的指纹创建独立缓存，并在插件安装目录中建立引用；代码替换不会删除可复用的 `node_modules` 或 Python 虚拟环境。npm 只安装 `dependencies`，不会安装 `devDependencies`；使用 lockfile 时执行 `npm ci --omit=dev`，没有 lockfile 时执行 `npm install --omit=dev`。因此构建期 SDK、TypeScript 和 bundler 可以放在 `devDependencies`，不要求发布到运行时 npm registry；运行时实际需要的库必须放在 `dependencies`，并在构建时 external。Python runtime 会优先使用缓存虚拟环境中的解释器。准备结果会保存，刷新或重启后仍可识别；准备期间若插件已替换或卸载，旧任务的结果不会覆盖新安装的状态。容器缺少 node/npm 或 python3 时仅提示前往终端执行「环境安装」，不会自动安装系统包。

## 常驻与自启动

需要长期运行的服务（例如本地提供商、文件监听）应设置 `resident: true`，并声明 `app.started` hook：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js", "resident": true},
  "contributes": {
    "hooks": [{"id": "boot", "event": "app.started", "handler": "hooks.boot"}]
  }
}
```

`app.started` 仅在 App 主进程启动完成时派发一次，安装后需要立即启动时应另提供手动入口。handler 完成初始化后应尽快返回，服务在进程内继续运行；payload 与超时规则见 [Hooks 文档](/advanced/plugin/hooks#执行顺序与超时)。容器未初始化或缺少 node/python 时，Hook 会失败并记录日志，后续调用工具或 Hook 仍会按需启动进程。

## 安全边界

- 插件进程与 Bash 同级，拥有容器内的文件、网络和命令能力；
- 页面与宿主接口的限制不构成对插件进程的沙箱；
- 命令与参数会原样执行，请勿在 `command` 中拼接未经校验的用户输入；
- 仅安装可信来源的插件。

## 相关

- 工具调用前后的拦截与改写：[生命周期 Hooks](/advanced/plugin/hooks)
- 插件进程向页面推送事件：[页面样式、组件与事件](/advanced/plugin/ui-components)
- 将由进程启动的本地服务注册为提供商：[Shizuku 与插件提供商](/advanced/plugin/shizuku-providers)
