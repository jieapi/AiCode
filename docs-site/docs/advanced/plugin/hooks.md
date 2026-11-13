# 生命周期 Hooks

Hooks 使插件可在 AI 运行的固定节点接收通知，或在工具执行前后、provider 请求发出前、权限确认前介入。Hooks 运行在插件运行时进程中，与 AI 工具共用同一 handler 表。

## 三种模式

| 模式 | 行为 | 适用 |
| --- | --- | --- |
| `observe`（默认） | 宿主异步派发，插件仅返回日志或自身结果，不影响主流程 | 记录、统计、通知 |
| `transform` | 宿主在固定拦截点等待结果，校验通过后应用；失败、超时或格式错误保持原值 | 拒绝危险调用、改写参数、脱敏结果、改写请求 |
| `review` | 仅在宿主判定需要使用者确认（ASK）后运行，返回 allow / deny / pass | 代审核 |

`transform` 与 `review` 事件必须在清单中显式声明 `mode`；声明错误将导致拒绝安装。

## 只读观察事件

| 事件 | 时机 |
| --- | --- |
| `app.started` | App 主进程启动完成时；用于常驻插件初始化 |
| `plugin.installed` | 插件安装或版本替换完成后；只通知该插件自身 |
| `plugin.uninstalled` | 插件卸载生效前；安装目录与数据目录仍可访问 |
| `plugin.enabled` | 插件启用生效后；只通知该插件自身 |
| `plugin.disabled` | 插件停用生效前；此时插件仍可运行 |
| `agent.run.started` | 一轮对话开始处理时 |
| `agent.run.finished` | 一轮对话结束时；成功、失败或被取消都会发出 |
| `agent.request.started` | 一次模型请求即将发出时 |
| `agent.request.finished` | 一次模型响应结束；成功、失败或取消都会发出 |
| `agent.tool.started` | 一次工具调用真正开始执行时 |
| `agent.tool.finished` | 一次工具调用结束；成功或失败 |
| `agent.compaction` | 上下文被压缩时 |

只读事件的 payload 不包含消息正文、工具参数、工具结果和模型输出。

`plugin.installed` 与 `plugin.enabled` 异步派发，不阻塞安装或启用。`plugin.uninstalled` 与 `plugin.disabled` 在状态变更前同步等待，给插件清理自己数据的机会；单次最多等待 10 秒，超时或失败后继续卸载/停用，插件不能阻止操作。这四个事件只派发给事件对应的插件，不广播给其他插件。

## 可写事件

| 事件 | 时机 | 可返回的变更 |
| --- | --- | --- |
| `agent.tool.before` | 工具执行前、权限判定前 | `decision: "deny"` 阻止执行；`arguments` 改写参数 |
| `agent.tool.after` | 工具成功结束后 | `data` 改写成功结果的数据 |
| `agent.http.before` | 每次实际发出 provider HTTP 请求前 | `url`、`headers`、`body` 改写请求 |

`agent.tool.before` 发生在权限判定之前。改写参数后，宿主会按最终参数重新执行完整权限判断与模式限制，原批准不会沿用。

`agent.tool.after` 仅处理成功结果，不得将错误改为成功，也不得伪造附件或图片。改写后的结果同时用于界面展示和回放给模型。

`agent.http.before` 挂载在共享 HTTP 客户端上，覆盖对话、标题生成、上下文压缩、模型列表/测试与图片请求。每次实际发送都会执行；网络重试或更换 Key 会重复触发，handler 应保持幂等。

::: warning 高权限能力
`agent.http.before` 的 payload 包含明文 `Authorization` / `x-api-key` 等鉴权头和完整请求体（可能含对话内容）。启用这类插件等同于将这些信息交给插件，仅应安装可信来源。
:::

## 代审核事件

| 事件 | 时机 | 可返回的决定 |
| --- | --- | --- |
| `agent.permission.review` | 宿主判定为 ASK 后、弹出用户确认前 | `allow` / `deny` / `pass` |

- `allow` 仅批准当前一次调用，不写入「始终允许」规则，也不改变后续调用；
- `deny` 仅拒绝当前工具调用，返回错误码 `PLUGIN_REVIEW_DENIED`，其他工具继续审批与执行；
- `pass` 继续询问下一个代审核插件，全部不处理才进入原有用户确认界面；
- 插件不得修改工具参数，也不得覆盖宿主已做出的 DENY、ALLOW/自动放行或 PLAN 模式限制；
- 全部 ASK 均已开放：Shizuku、`elevate` 提权、配置修改、删除子代理、危险 rm 均可代审；`planMode` 模式切换仍由使用者本人处理；插件不得代审自身提供的工具。

## 事件内容

所有 `agent.*` 事件均包含以下公共字段：

| 字段 | 说明 |
| --- | --- |
| `runId` | 本轮运行的标识，同一次运行的各事件一致 |
| `sessionId` | 会话标识；主会话与子代理会话各有一个 |
| `scope` | `main` 或 `subagent` |
| `agentName` | 子代理名称；主会话为 null |
| `mode` | 事件发生时的模式：`BUILD`、`PLAN` 或 `AUTO` |

各事件的专属字段：

| 事件 | 额外字段 |
| --- | --- |
| `agent.run.started` | `status=started`、`toolCalls=0`、`errorCode=null` |
| `agent.run.finished` | `status`（`success`/`error`/`cancelled`）、`toolCalls`、`errorCode` |
| `agent.request.started` | `requestId`、`messageCount`、`toolCount`、`estimatedInputTokens` |
| `agent.request.finished` | `requestId`、`status`、`inputTokens`、`outputTokens`、`toolCalls`、`durationMs`、`stopReason`、`errorCode` |
| `agent.tool.started` | `callId`、`toolName` |
| `agent.tool.finished` | `callId`、`toolName`、`status`（`success`/`error`）、`durationMs` |
| `agent.compaction` | `compacted=true`、`estimatedInputTokens` |
| `app.started` | `launchId`、`startedAt`、`appVersion`、`process`（不含 run/session 公共字段） |
| `plugin.installed` | `pluginId`、`version`、`installedAt`、`previousVersion`（首次安装为 null） |
| `plugin.uninstalled` | `pluginId`、`version` |
| `plugin.enabled` | `pluginId`、`version` |
| `plugin.disabled` | `pluginId`、`version` |
| `agent.tool.before` | `callId`、`toolName`、`arguments` |
| `agent.tool.after` | `callId`、`toolName`、`status=success`、`data` |
| `agent.http.before` | `url`、`method`、`headers`、`body` |
| `agent.permission.review` | `callId`、`toolName`、`arguments`、`appForeground` |

## 声明

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "contributes": {
    "hooks": [
      {"id": "run-finished", "event": "agent.run.finished", "handler": "hooks.runFinished"},
      {"id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform"},
      {"id": "redact-result", "event": "agent.tool.after", "handler": "hooks.redactResult", "mode": "transform"},
      {"id": "review", "event": "agent.permission.review", "handler": "hooks.reviewPermission", "mode": "review"}
    ]
  }
}
```

`handler` 是运行时 handler 表中的键。hook 与 AI 工具共用同一进程，声明本身不会执行代码。

## 示例

### 记录每轮运行结果

```js
const handlers = {
  'hooks.runFinished': (payload) => ({
    logs: [`run finished ${payload.status} toolCalls=${payload.toolCalls}`]
  })
};
```

### 拒绝危险命令

```js
const handlers = {
  'hooks.guardBash': (payload) => {
    if (payload.toolName === 'bash' && /rm\s+-rf/.test(payload.arguments.command ?? '')) {
      return {decision: 'deny', reason: '禁止递归删除'};
    }
    return {arguments: payload.arguments};
  }
};
```

`decision` 仅可为 `allow` 或 `deny`，缺省为 `allow`。`arguments` 必须是 JSON 对象，且不得修改工具名称。

### 脱敏工具结果

```js
const handlers = {
  'hooks.redactResult': (payload) => ({
    data: {
      text: String(payload.data?.text ?? '').replace(/ghp_[A-Za-z0-9]+/g, '***')
    }
  })
};
```

仅可修改 `data`。`status`、图片附件、文件卡片与错误结果不可修改。

### 改写 provider 请求

```js
const handlers = {
  'hooks.routeRequest': (payload) => {
    if (!String(payload.url).includes('/chat/completions')) return {};
    return {
      headers: {...payload.headers, 'X-Route': 'team-a'},
      body: JSON.stringify({...JSON.parse(payload.body), temperature: 0.2})
    };
  }
};
```

`headers` 是完整替换表，而非增量合并；最多 64 项，值为字符串。`Host`、`Content-Length`、`Transfer-Encoding`、`Connection`、`Expect`、`Upgrade` 不允许改写。`body` 必须是合法 JSON 对象文本，最大 4 MiB；请求方法固定为 `POST`，插件不得修改。

宿主仅校验 JSON 对象、大小与字段白名单，不校验 provider 语义。改写消息可能破坏工具调用配对或 Anthropic 签名；修改工具名可能与宿主工具注册表不一致；修改提示词不会同步更新 token 估算与压缩决策。插件需自行保证 body 与目标端点兼容。

### 代审核

```js
const handlers = {
  'hooks.reviewPermission': (payload) => {
    if (payload.toolName === 'bash' && payload.arguments.command === 'ls') {
      return {decision: 'allow', reason: '只读列目录'};
    }
    return {decision: 'pass'};
  }
};
```

### 后台审批

需要用户在后台确认时，插件可创建 `visibilityPolicy: "background"` 的浮层并轮询 `host.app.isForeground`；App 回到前台后应返回 `pass`，将请求交回应用内确认框，避免审批持续至超时。

完整实现参见 `plugin-samples/background-approval`：`app.started` 时创建悬浮球，`agent.permission.review` 将审批请求展开为面板，用户在浮层上选择同意或拒绝；App 在前台时直接返回 `pass`。

## 执行顺序与超时

- 多个可写 hook 按插件 ID 升序、各自清单顺序执行，后一个 hook 读取前一个改写后的值；
- 单次调用上限 10 秒，一次拦截合计上限 20 秒；
- 超时、异常或返回值格式不合法时跳过该 hook 并保持当前值，不会因此自动放行或拒绝；
- 代审核同样顺序执行，首个 `allow`/`deny` 生效；单次最多等待 5 分钟、总预算 5 分钟；超时或异常按 `pass` 继续，最终无人处理时回到用户确认；
- 只读 hook 异步派发，不进入 Agent 主流程；单项超时为 30 秒，超时将关闭该插件的运行时；连续失败目前不会自动停用插件。

## 调试输出

handler 可返回 `{logs: ["..."]}`，宿主将每行写入应用日志，最多 50 行、单行 500 字符。可写 hook 的 `decision`、`arguments`、`data`、`url`、`headers`、`body` 与 `logs` 可同时返回；代审核的 `decision`、`reason` 与 `logs` 可同时返回。

## 边界

- 不得提权：可写 hook 仅可拒绝或缩小调用，不得批准工具、不得将宿主拒绝改为允许、不得绕过 PLAN 模式或权限策略；
- 改写参数后必须重新执行完整权限判断；审批后不会静默改写参数；
- `agent.tool.after` 仅处理成功结果；
- `agent.http.before` 可读取并改写鉴权头，但不得修改请求方法，也不得将请求改为非 JSON；
- 代审核可批准原本需要用户确认的调用，属于高权限能力；它不得修改参数、不得覆盖宿主 DENY/PLAN、不得写入永久允许规则，也不得代审自身提供的工具；
- hook 与 AI 工具共用同一插件进程，进程拥有容器内的文件与命令能力。仅安装可信插件。
