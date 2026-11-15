# 模型句柄与流式调用

模型句柄让插件直接调用使用者已配置的 AI 供应商，不经过聊天页，也不启动 Agent 工具循环。它适合团队群聊、多模型对比、批量摘要这类由插件自己编排请求的场景。

句柄只保存「供应商 + 模型 + 默认提示词/参数」，**不保存对话历史**。插件每次请求传完整 `messages`，群聊记录由插件自己管理。

## 先在清单声明

调用模型句柄的插件必须在 `plugin.json` 中声明 `contributes.modelAccess`。安装预览会展示用途，并提示请求会计入对应供应商的用量。

```json
{
  "contributes": {
    "modelAccess": {
      "purpose": "在团队群聊中轮流调用多个模型"
    }
  }
}
```

`purpose` 必填，最多 200 字符。未声明的插件调用 `host.model.*` / `aicode.model.*` 会返回 `MODEL_ACCESS_NOT_DECLARED`。

## 页面与进程接口

页面使用 `aicode.model.*`，插件 runtime 使用 `host.model.*`，方法同名：

| 方法 | 作用 |
| --- | --- |
| `listProviders()` | 列出已配置供应商，不返回 Key、地址、自定义头 |
| `listModels({ providerId })` | 列出该供应商已添加的模型及窗口、能力、单价 |
| `create({ providerId, model, name?, systemPrompt?, params? })` | 创建句柄 |
| `close(handleId)` | 关闭句柄并取消它的流；进程侧为 `host.model.close({ handleId })` |
| `start({ handleId, messages, systemPrompt?, params? })` | 发起一次流式补全，立即返回 `streamId` |
| `read({ streamId, cursor?, maxEvents?, waitMs? })` | 按 cursor 读取一批事件 |
| `cancel({ streamId })` | 取消请求，保留已产生的事件 |

`params` 支持 `reasoningEffort`、`maxOutputTokens`、`temperature`。模型元数据不允许自定义温度时，传 `temperature` 会返回 `PARAM_UNSUPPORTED`；`maxOutputTokens` 会被限制在模型输出上限内。

## 页面示例

```js
const { providers } = await aicode.model.listProviders()
const provider = providers.find((item) => item.enabled && item.hasKey)
const { models } = await aicode.model.listModels({ providerId: provider.id })

const { handle } = await aicode.model.create({
  providerId: provider.id,
  model: models[0].id,
  systemPrompt: '你是群聊里的产品经理，回答要简短。'
})

const result = await aicode.model.stream(
  {
    handleId: handle.id,
    messages: [{ role: 'user', content: '我们下一版先做什么？' }]
  },
  (event) => {
    if (event.type === 'text') appendToBubble(event.text)
    if (event.type === 'reasoning') showThinking(event.text)
    if (event.type === 'retry') showStatus(`第 ${event.attempt} 次重试`)
  }
)

if (result.status === 'success') {
  addMessage(result.final.content, result.final.usage)
}
```

`aicode.model.stream` 内部是 `start` + `read` 循环，返回最后一次 `read` 的结果。需要自己控制读取节奏时，直接调用 `start` / `read` / `cancel`。

## 进程示例

runtime 通过按行 JSON-RPC 调用宿主。下面是团队群聊按成员轮流请求的核心循环：

```js
async function call(method, params) {
  // 按你的 runtime 结构发送 {id, method, params}，等待同 id 的响应。
}

const { handle } = await call('host.model.create', {
  providerId: 'provider-id',
  model: 'model-id',
  systemPrompt: '你是群聊里的架构师。'
})

const { streamId } = await call('host.model.start', {
  handleId: handle.id,
  messages: groupHistory
})

let cursor = 0
let final = null
while (true) {
  const result = await call('host.model.read', { streamId, cursor, waitMs: 15000 })
  for (const event of result.events) {
    if (event.type === 'text') postToPage(event.text)
  }
  cursor = result.cursor
  if (result.done) {
    final = result.final
    break
  }
}
```

把多个成员各自 `create` 成句柄，即可轮流调用；每个句柄可以有自己的 `systemPrompt` 和模型。

## 事件与最终结果

`read` 返回的 `events` 可能包含：

| 事件 | 字段 | 说明 |
| --- | --- | --- |
| `text` | `text` | 正文增量，宿主会合并相邻增量 |
| `reasoning` | `text` | 思考过程增量 |
| `toolCall` | `name` | 模型开始产出工具调用；当前版本不执行工具 |
| `retry` | `attempt`、`maxRetries`、`error.code`、`error.statusCode?` | 网络重试提示 |
| `keySwitch` | `index`、`total` | 多 Key 自动切换提示 |

`done: true` 时带 `status`：

- `success`：`final` 包含 `content`、`reasoning?`、`toolCalls?`、`stopReason?`、`usage`、`durationMs`。
- `error`：`error` 包含稳定 `code`、`message` 与可选的 `partialContent`。
- `cancelled`：请求被 `cancel`、页面关闭或插件停用取消。

`usage` 字段为 `input`、`output`、`cachedInput`、`cacheCreation`，插件可以用它和 `listModels` 的单价做成本展示。

## 边界与限制

- v1 只支持文本 `user` / `assistant` 消息，不执行工具、不处理图片附件。
- 句柄无状态；跨轮上下文由插件每次传入。当前版本不保证跨轮回传各家的 thinking 签名，因此不适合直接承载带工具的 Agent 循环。
- 请求上限：200 条消息、单条 64 KiB、总计 256 KiB；超出返回 `PARAM_INVALID`。
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
