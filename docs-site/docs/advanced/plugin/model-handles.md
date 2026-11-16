# 模型句柄与流式调用

模型句柄让插件直接调用使用者已配置的 AI 供应商，不经过聊天页，也不启动宿主的 Agent 工具循环。它适合团队群聊、多模型对比、批量摘要这类由插件自己编排请求的场景。

句柄只保存「供应商 + 模型 + 默认提示词/参数」，**不保存对话历史**。插件每次请求传完整 `messages`，也可携带工具定义、工具结果、思考签名快照与图片；工具循环和审批由插件驱动。

## 先在清单声明

在 `plugin.json` 中声明 `contributes.modelAccess` 并填写用途，写法见[清单参考](/advanced/plugin/manifest#modelaccess)。安装预览会提示请求计入对应供应商用量；未声明时调用返回 `MODEL_ACCESS_NOT_DECLARED`。

## 页面与进程接口

下表使用页面侧 `aicode.model.*` 的签名。进程通过 `hostCall('host.model.方法', {参数})` 调用，参数统一放入对象：

| 方法 | 作用 |
| --- | --- |
| `listProviders()` | 返回 `{providers}`，不包含 Key、地址、自定义头 |
| `listModels({ providerId })` | 返回 `{providerId, models}`，含窗口、能力与单价元数据 |
| `create({ providerId, model, name?, systemPrompt?, params? })` | 返回 `{handle}` |
| `close(handleId)` | 返回 `{ok}`，取消句柄的流，尚在预检的请求也不能再启动；进程侧为 `host.model.close({handleId})` |
| `start({ handleId, messages, tools?, systemPrompt?, params? })` | 返回 `{streamId}`，事件由 `read` 拉取 |
| `read({ streamId, cursor?, maxEvents?, waitMs? })` | 返回 `{events, cursor, done, status?, final?, error?}`；`waitMs` 为 0–20000 毫秒 |
| `cancel(streamId)` | 返回 `{ok}`，取消请求并保留已产生的事件；进程侧为 `host.model.cancel({streamId})` |
| `stream(options, onEvent)` | 页面 SDK 封装 `start` + `read` 循环，回调事件并返回最终 read 结果，进程侧自行循环读取 |

`params` 支持 `reasoningEffort`、`maxOutputTokens`、`temperature`。模型元数据不允许自定义温度时，传 `temperature` 会返回 `PARAM_UNSUPPORTED`；`maxOutputTokens` 会被限制在模型输出上限内。

`tools` 只声明模型可用的工具，执行方式见[工具循环](#工具循环)。

## 消息格式

`messages` 支持三种角色，`content` 均为必填字符串（可以为空）：

| role | 额外字段 | 说明 |
| --- | --- | --- |
| `user` | `images?` | 用户输入；图片见[图片输入与输出](#图片输入与输出) |
| `assistant` | `toolCalls?`、`reasoning?`、`providerState?` | 上一轮模型输出；带工具调用时原样回传 |
| `tool` | `toolCallId`、`name`、`images?` | 工具结果，`toolCallId` 必须匹配前面 assistant 的某个 `toolCalls[].id` |

```js
const messages = [
  {
    role: 'user',
    content: '看下这个报错，需要的话把结论写到 notes.md',
    images: [{mimeType: 'image/png', path: '~/workspace/error.png'}]
  },
  {
    role: 'assistant',
    content: '',
    reasoning: '先读日志再决定',
    providerState: {signature: '...', thinkingBlocksJson: '...'},
    toolCalls: [{id: 'call_1', name: 'readFile', arguments: {path: '~/workspace/app.log'}}]
  },
  {
    role: 'tool',
    toolCallId: 'call_1',
    name: 'readFile',
    content: '2026-11-15 10:00:00 ERROR ...'
  }
]
```

- `toolCalls[].arguments` 必须是对象；`id` 与 `name` 必填，单条消息最多 32 个调用。
- `providerState` 是各家思考签名的快照：`signature` 与 `thinkingBlocksJson` 都可以省略，合计最多 256 KiB。带思考的供应商要求下一轮原样回传，否则签名校验失败或思考上下文丢失。
- `tool` 消息的 `toolCallId` 找不到对应的前置 assistant 调用时返回 `PARAM_INVALID`。因此回传顺序必须是「assistant（含 toolCalls）→ tool → 下一次 start」。
- `reasoning` 最多 64 KiB；超出返回 `PARAM_INVALID`。

## 工具定义

`start.tools` 是数组，最多 64 个，`name` 不能重复：

```json
[
  {"type": "app", "name": "writeFile"},
  {
    "type": "custom",
    "name": "team_vote",
    "description": "让群聊成员对给定选项投票，返回本地统计结果。",
    "inputSchema": {
      "type": "object",
      "properties": {
        "topic": {"type": "string"},
        "options": {"type": "array", "items": {"type": "string"}}
      },
      "required": ["topic", "options"]
    }
  }
]
```

| type | 宿主行为 | 插件职责 |
| --- | --- | --- |
| `app` | 从工具注册表读取内置或 MCP 工具的描述与参数 schema，转给模型；不执行 | 模型返回 `toolCalls` 后调用 `aicode.tools.execute` / `host.tools.execute` |
| `custom` | 只把 `description` 与 `inputSchema` 转给模型；不执行 | 插件自己处理调用，生成 `tool` 消息 |

- `app` 只接受内置与 MCP 工具。`plugin__` 前缀的插件工具返回 `TOOL_NOT_ALLOWED`，未知工具返回 `TOOL_NOT_FOUND`。
- `custom` 的 `name` 只能包含字母、数字、下划线与连字符，长度 1–64；`description` 最多 600 字符；单个 `inputSchema` 最多 64 KiB，全部 schema 合计最多 256 KiB。省略 `inputSchema` 时按空对象 schema 发送。
- 宿主只把定义转给供应商的 function-calling 接口，不校验模型返回的调用是否在 `tools` 里，也不自动续跑。

## 工具循环

工具循环由插件驱动：`start` → 读 `final.toolCalls` → 执行工具 → 追加 assistant 与 tool 消息 → 再 `start`。下面是一个可复用的页面循环：

```js
async function callWithTools(handleId, messages, tools, onEvent, runTool) {
  for (let round = 0; round < 4; round++) {
    const result = await aicode.model.stream({handleId, messages, tools}, onEvent)
    if (result.status !== 'success') return result
    const calls = result.final.toolCalls || []
    if (calls.length === 0) return result

    messages.push({
      role: 'assistant',
      content: result.final.content || '',
      reasoning: result.final.reasoning,
      providerState: result.final.providerState,
      toolCalls: calls
    })

    for (const call of calls) {
      const output = await runTool(call)
      messages.push({
        role: 'tool',
        toolCallId: call.id,
        name: call.name,
        content: typeof output === 'string' ? output : JSON.stringify(output)
      })
    }
  }
  throw new Error('工具轮数超过上限')
}
```

要点：

- 先把 assistant 消息追加进 `messages`，再追加对应的 tool 消息；顺序反了或漏掉 assistant，会因 `toolCallId` 对不上返回 `PARAM_INVALID`。
- `providerState` 与 `reasoning` 原样带回，供应商才能续上思考签名。
- `final.toolCalls[].arguments` 是对象；如果它因为超长变成了字符串并带 `argumentsTruncated: true`，应重新请求或跳过该调用，不要直接 `JSON.parse` 一个被截断的片段。
- 轮数上限由插件决定。示例限制 4 轮，避免模型反复调用同一工具。

## 交给宿主执行（app 工具）

`app` 工具通过宿主的工具接口执行，复用聊天页同一套权限策略：

```js
const {tools} = await aicode.tools.list({includeSchema: true})
const result = await aicode.tools.execute(
  'writeFile',
  {path: '~/workspace/notes.md', content: '# 结论\n...'},
  {sessionId: 'plugin:demo.team-chat:team-chat', timeoutMs: 120000}
)

if (result.status === 'success') {
  console.log(result.data)
}
```

工具范围、会话命名空间、超时与结果截断规则统一见[工具目录与代执行](/advanced/plugin/tools-runtime#工具目录与代执行)。将工具结果转换为 `tool` 消息后，再交给模型继续生成。

### 审批流程

工具权限策略判定为 ASK 时，`execute` 不会立即返回，而是挂起等待使用者确认。请求只出现在插件自己的会话里，**不会进入聊天页弹窗或侧边栏等待指示**，插件页面需要自己展示审批条：

```js
let approval = null
const timer = setInterval(async () => {
  const pending = await aicode.tools.pending(sessionId).catch(() => null)
  approval = pending ? pending.approval : null
  renderApproval(approval) // approval 为 null 时隐藏审批条
}, 800)

try {
  const result = await aicode.tools.execute('writeFile', args, {sessionId})
  // 执行完成
} finally {
  clearInterval(timer)
  renderApproval(null)
}
```

按钮调用 `aicode.tools.resolve(approval.id, choice)` 回传 `once` / `always` / `reject`；只有 `rememberablePatterns` 非空时才展示「始终允许」。审批字段、失效条件与错误码见[工具目录与代执行](/advanced/plugin/tools-runtime#工具目录与代执行)。

## 图片输入与输出

### 输入

`user` 与 `tool` 消息可以带 `images`，每项支持 `path` 或 `base64`：

```js
const messages = [
  {
    role: 'user',
    content: '对比这两张截图',
    images: [
      {mimeType: 'image/png', path: '~/workspace/before.png'},
      {mimeType: 'image/jpeg', base64: '/9j/4AAQSkZJRg...'}
    ]
  }
]
```

| 项 | 限制 |
| --- | --- |
| 类型 | `image/png`、`image/jpeg`、`image/webp`、`image/gif`；其它类型返回 `IMAGE_TYPE_UNSUPPORTED` |
| `path` | 优先于 `base64`；按宿主文件规则读取（真实路径、`~`、`data://`），单图最多 8 MiB，单次请求合计最多 16 MiB |
| `base64` | 解码后单图最多 1 MiB，单次请求合计最多 2 MiB；非法 Base64 返回 `PARAM_INVALID` |
| 张数 | 单条消息最多 4 张，单次请求最多 8 张 |
| 请求体 | `start` 的整个 `params` 最多 3 MiB；超出返回 `REQUEST_TOO_LARGE` |

模型必须支持视觉输入，可先用 `listModels` 的 `supportsVision` 判断。图片输入只对支持视觉的供应商生效，不支持时上游会返回错误。

### 输出

`final.images` 是模型直出的图片数组，每项为 `{mimeType, base64}`：

```js
if (result.final.images) {
  for (const image of result.final.images) {
    const img = document.createElement('img')
    img.src = `data:${image.mimeType};base64,${image.base64}`
    bubble.appendChild(img)
  }
}
```

单图 base64 最多 1.5 MiB，单次结果合计最多 2 MiB。放不下的整张丢弃并置 `imagesTruncated: true`；`imageCount` 是模型返回的原始张数，可能大于 `images.length`。

## 事件与最终结果

`read` 返回的 `events` 可能包含：

| 事件 | 字段 | 说明 |
| --- | --- | --- |
| `text` | `text` | 正文增量，宿主会合并相邻增量 |
| `reasoning` | `text` | 思考过程增量 |
| `toolCall` | `name` | 模型开始产出工具调用；宿主不执行，完整调用在 `final.toolCalls` |
| `retry` | `attempt`、`maxRetries`、`error.code`、`error.statusCode?` | 网络重试提示 |
| `keySwitch` | `index`、`total` | 多 Key 自动切换提示 |

只有上游请求已经结束、且缓冲事件已全部返回时，`read` 才返回 `done: true`。即使请求已成功、失败或取消，只要仍有事件未读，就应继续携带上一批的 `cursor` 读取；`aicode.model.stream` 会自动完成这个循环。

`done: true` 时带 `status`：

- `success`：`final` 的字段见下表。
- `error`：`error` 包含稳定 `code`、`message` 与可选的 `partialContent`。
- `cancelled`：请求被 `cancel`、页面关闭或插件停用取消。

| `final` 字段 | 说明 |
| --- | --- |
| `content` | 正文；超过 256 KiB 截断并带 `contentTruncated` |
| `reasoning?` | 思考文本；超过 64 KiB 截断并带 `reasoningTruncated` |
| `toolCalls?` | 最多 32 条；`arguments` 为对象，单条超过 64 KiB 时转为截断字符串并带 `argumentsTruncated`；参数总量超过 256 KiB 的调用被丢弃并带 `toolCallsTruncated` |
| `providerState?` | `{signature?, thinkingBlocksJson?}`，回传给下一轮 assistant |
| `images?` / `imageCount?` / `imagesTruncated?` | 模型直出图片与丢弃标记 |
| `stopReason?` / `stopDetail?` | 上游停止原因 |
| `usage` | `input`、`output`、`cachedInput`、`cacheCreation` |
| `durationMs` | 请求耗时 |

`usage` 可以配合 `listModels` 的单价做成本展示。

## 边界与限制

- `messages` 最多 200 条，单条 `content` 最多 128 KiB，正文合计最多 512 KiB；`start` 的整个 `params` 最多 3 MiB，超出返回 `REQUEST_TOO_LARGE`。
- `tools` 最多 64 个；`assistant.toolCalls` 每条消息最多 32 个，参数合计最多 64 KiB。
- 句柄无状态；宿主不保存历史，也不保存 `providerState`。跨轮上下文、工具循环和审批展示全部由插件维护。
- 每个插件最多 16 个句柄、8 条并发流；事件缓冲 1024 条 / 2 MiB，读得太慢会以 `STREAM_BUFFER_OVERFLOW` 结束。
- 5 分钟没有 `read` 的流会被自动取消；完成后的流保留 5 分钟供最后一次读取。
- 插件不能为自己注册的 provider 创建句柄（`SELF_PROVIDER_FORBIDDEN`），避免宿主调用插件服务、插件又回调宿主模型 API 的递归。
- Key 只在宿主；插件只拿到模型结果与元数据。请求仍会经过宿主的 `agent.http.before` hook 和 AI 日志，插件 prompt 文本会进入 AI 日志（base64 媒体会被脱敏）。
- 供应商被停用、删除或模型被移出列表后，下一次请求会失败；插件应处理 `PROVIDER_DISABLED`、`PROVIDER_NO_KEY`、`MODEL_NOT_AVAILABLE`。

## 常见错误

| 错误码 | 含义 |
| --- | --- |
| `MODEL_ACCESS_NOT_DECLARED` | 清单没有声明 `contributes.modelAccess` |
| `PROVIDER_NOT_FOUND` / `PROVIDER_DISABLED` | 供应商不存在或已停用 |
| `PROVIDER_NO_KEY` | 供应商没有可用 Key，或 Key 都在冷却 |
| `MODEL_NOT_AVAILABLE` / `MODEL_UNSUPPORTED` | 模型不在已添加列表，或不是聊天模型 |
| `HANDLE_NOT_FOUND` / `STREAM_NOT_FOUND` | 句柄或流不存在、已被清理，或不属于当前插件 |
| `HANDLE_LIMIT` / `STREAM_LIMIT` | 超过句柄或并发流上限 |
| `CONTEXT_TOO_LARGE` | 估算输入超过模型窗口，请求未发出 |
| `CURSOR_INVALID` | `cursor` 早于已交付位置或超过当前缓冲尾部 |
| `STREAM_BUFFER_OVERFLOW` | 事件缓冲溢出，读得太慢 |
| `MODEL_ERROR` / `ALL_KEYS_FAILED` | 上游请求失败，或所有 Key 都不可用 |
| `TOOL_NOT_FOUND` | `tools` 里的 app 工具或 `tools.execute` 的工具不存在 |
| `TOOL_NOT_ALLOWED` | 引用了 `plugin__` 工具，或调用了依赖聊天会话的工具 |
| `TOOL_DENIED` / `TOOL_USER_REJECTED` | 权限策略拒绝，或使用者拒绝 |
| `TOOL_TIMEOUT` / `TOOL_APPROVAL_TIMEOUT` | 执行超时，或等待授权超时 |
| `REQUEST_TOO_LARGE` | `start` 的整个 `params` 超过 3 MiB |
| `APPROVAL_NOT_FOUND` | 审批请求已过期、已处理或不属于当前插件会话 |
| `SESSION_FORBIDDEN` | `sessionId` 不在 `plugin:<插件ID>` 命名空间内 |
| `IMAGE_TOO_LARGE` / `IMAGE_TYPE_UNSUPPORTED` | 图片超过大小上限，或类型不支持 |
| `PARAM_INVALID` | 消息、工具、图片或参数结构不合法 |

## 相关

- 宿主工具目录与执行细节：[AI 工具与插件运行时](/advanced/plugin/tools-runtime)
- 页面桥的完整方法表：[插件页面与宿主接口](/advanced/plugin/ui-pages)
