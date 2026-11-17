# 生命周期 Hooks

Hooks 使插件可在 AI 运行的固定节点接收通知，或在工具执行前后、provider 请求发出前、权限确认前介入。Hooks 与 AI 工具共用同一运行时和 handler 表，声明写法见[清单参考](/advanced/plugin/manifest#hooks)，TypeScript 注册方式见[运行时与 SDK](/advanced/plugin/source-build#运行时与-sdk)。

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
| `backup.export.before` | 一次备份准备写入归档前；需声明 `contributes.backup` 并由使用者授权 |
| `backup.import.finished` | 一次还原完成、插件区段已解到暂存目录后；需声明 `contributes.backup` 并由使用者授权 |

只读事件的 payload 不包含消息正文、工具参数、工具结果和模型输出。

`plugin.installed` 与 `plugin.enabled` 异步派发，不阻塞安装或启用。`plugin.uninstalled` 与 `plugin.disabled` 在状态变更前同步等待，给插件清理自己数据的机会；单次最多等待 10 秒，超时或失败后继续卸载/停用，插件不能阻止操作。这四个事件只派发给事件对应的插件，不广播给其他插件。

### 备份与还原事件

备份事件用于读写该插件自己的备份区段，区段内容由插件决定，可能包含敏感配置或记录：

- 插件必须声明 `contributes.backup`（用途说明），并由使用者在插件详情的「权限」中单独开启「备份与还原事件」；未授权不会派发。
- 插件还要在 `backup.data` 槽位声明一个数据选项（`id` / `title` / `icon`，不能写 `page` 或 `handler`）。宿主在备份页「选择导出数据」渲染开关；勾选后该插件参与本次导出与还原。
- 导出前宿主调用 `backup.export.before`，payload 的 `dir` 是 `data://backup-export`；插件把额外数据写入其中（建议用 `global/…` 与 `project/…` 两个子目录）。宿主将其打包为归档内的 `plugins/<pluginId>/…` 区段。
- 导入时宿主先把该区段解到 `data://backup-import`，还原完主数据后再调用 `backup.import.finished`；插件从 `dir` 把数据写回真实路径。
- 暂存目录由宿主在操作结束后清理；如果宿主本次已经恢复了 MCP（`stats.mcpServers > 0`），插件应跳过自己区段里的 `mcp.json`，避免覆盖宿主版本。
- 事件由宿主同步等待：导出前把数据写入暂存区，导入后再写回真实路径；失败或超时只写日志，不阻塞备份与还原。

`backup`、备份 Hooks 与数据选项的声明见[备份清单](/advanced/plugin/manifest#backup)。

插件把数据写入 `dir`，宿主负责打包；导入时从 `dir` 读回。数据量应控制在单文件 8 MiB、单插件 64 MiB 以内。宿主在读取前及读取过程中限制大小；超限文件会跳过并写入日志，超过单插件总量后不再继续打包该插件的后续文件。

```js
const handlers = {
  'hooks.onExportBefore': async (payload) => {
    await hostCall('host.fs.write', {
      path: payload.dir + '/global/memory/note.md',
      content: '...'
    });
    return {logs: ['extra data staged']};
  },
  'hooks.onImport': async (payload) => {
    const file = await hostCall('host.fs.read', {path: payload.dir + '/global/memory/note.md'});
    await hostCall('host.fs.write', {path: '~/.aicode/memory/note.md', content: file.content});
    return {logs: [`restored ${payload.files} files`]};
  }
};
```

## 可写事件

| 事件 | 时机 | 可返回的变更 |
| --- | --- | --- |
| `agent.tool.before` | 工具执行前、权限判定前 | `decision: "deny"` 阻止执行；`arguments` 改写参数；`delegate: {handler}` 选择插件执行方 |
| `agent.tool.after` | 工具成功结束后 | `data` 改写成功结果的数据 |
| `agent.http.before` | 每次实际发出 provider HTTP 请求前 | `url`、`headers`、`body` 改写请求 |

`agent.tool.before` 发生在权限判定之前。改写参数后，宿主会按最终参数重新执行完整权限判断与模式限制，原批准不会沿用。

`agent.tool.after` 仅处理成功结果，不得将错误改为成功，也不得伪造附件或图片。改写后的结果同时用于界面展示和回放给模型。

`agent.http.before` 处理共享 HTTP 客户端中符合条件的 POST 请求：请求体不超过 4 MiB，且去除前导空白后以 `{` 开头；GET、超限或其他正文格式会跳过。对话、标题生成、上下文压缩、测试和图片请求中符合条件的调用均可触发；网络重试或更换 Key 可能重复触发，handler 应保持幂等。超时预算见[执行顺序与超时](#执行顺序与超时)。

`agent.http.before` 的敏感请求头在 payload 中只以占位符 `{{Token}}` 出现：App 实际会添加的 `Authorization`（OpenAI/MCP）、`x-api-key`（Anthropic）与 `x-goog-api-key`（Gemini）；provider 自定义 header 中如果包含同一份密钥值，也会被替换为占位符。插件原样回传 `{{Token}}`，且请求仍发往原 origin 时，宿主才恢复真实值；插件无法读到真实鉴权头。

::: warning 请求体可见
payload 仍包含完整请求体（可能含对话内容），仅应安装可信来源的插件。
:::

### 委托工具执行

声明 `agent.tool.before`、`mode: "transform"` 的插件可以返回 `delegate: {handler: "tools.remote"}`。before handler 只能选择执行方，不应在这里执行工具或产生工具副作用。宿主收齐最终参数并重新完成权限与模式检查后，才调用选中的执行 handler；`decision: "allow"` 也不能跳过检查。委托执行受原工具相同的权限边界约束，不扩大 PLAN 或 DENY 的权限。

多个 hook 按原顺序处理，首个合法委托固定归属其插件、版本与 handler；后续 hook 仍可改参数或拒绝，但不能抢占执行方。执行时使用最终参数。宿主生成独立执行标识并绑定运行、工具调用与插件，不能指定其他插件。插件停用、更新、执行错误、超时或取消均不会回退本地工具。委托保留原工具卡片、进度与 started/finished 通知，但不创建本地文件修改检查点。

使用 SDK 的 `toolDelegate` 包装执行 handler：异步生成器 `yield` 进度文本，`return` 最终 JSON 数据，抛异常表示失败。需要取消的网络请求、子进程等应监听 `signal` 并终止实际工作；宿主取消等待不等于能强制终止插件的任意外部副作用。

```ts
import { definePlugin, toolDelegate } from '@jieapi/plugin-sdk';

definePlugin({ handlers: {
  'hooks.route': (payload) => payload.toolName === 'readFile'
    ? { delegate: { handler: 'tools.remote' } }
    : {},
  'tools.remote': toolDelegate(async function* ({ arguments: args, signal }) {
    yield 'Reading remote file';
    const response = await fetch(String(args.path), { signal });
    if (!response.ok) throw new Error(`Read failed: ${response.status}`);
    return { content: await response.text() };
  })
} });
```

非 SDK 实现使用同一 handler 的 `start` / `next` / `cancel` 操作：请求包含 `executionId`、`runId`、`callId`、`toolName`，只有 start 包含最终 `arguments`。响应必须回传相同 executionId，格式为 `{type: "progress", chunk}`、`{type: "pending"}`、`{type: "completed", data}` 或 `{type: "error", message}`。next 应长轮询约 25 秒，单次 RPC 上限 35 秒；同一 executionId 不得重复启动或并发读取。SDK 每次只拉取一个增量，限制单条进度为 64 Ki 字符，避免无界队列。完成、失败或取消后的执行标识在当前进程内保留 31 分钟用于拒绝重复启动；进程重启不保证跨进程去重，执行方仍应对外部副作用使用业务幂等标识。宿主总执行上限 30 分钟，异常或取消后最多等待 2 秒发送 cancel；SDK 通过 AbortSignal 通知执行方并清理状态。

## 代审核事件

| 事件 | 时机 | 可返回的决定 |
| --- | --- | --- |
| `agent.permission.review` | 宿主判定为 ASK 后、弹出用户确认前 | `allow` / `deny` / `pass` |

- `allow` 仅批准当前一次调用，不写入「始终允许」规则，也不改变后续调用；
- `deny` 仅拒绝当前工具调用，返回错误码 `PLUGIN_REVIEW_DENIED`，其他工具继续审批与执行；
- `pass` 继续询问下一个代审核插件，全部不处理才进入原有用户确认界面；
- 插件不得修改工具参数，也不得覆盖宿主已做出的 DENY、ALLOW/自动放行或 PLAN 模式限制；
- 全部 ASK 均已开放：Shizuku、`elevate` 提权、配置修改、删除子代理、危险 rm 均可代审；`planMode` 模式切换仍由使用者本人处理；插件不得代审自身提供的工具，也不得代审已委托给自身执行的原生工具调用。

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
| `backup.export.before` | `dir`=`data://backup-export` |
| `backup.import.finished` | `dir`=`data://backup-import`、`files`（区段文件数）、`stats`（宿主还原统计） |
| `agent.tool.before` | `callId`、`toolName`、`arguments` |
| `agent.tool.after` | `callId`、`toolName`、`status=success`、`data` |
| `agent.http.before` | `url`、`method`、`headers`、`body` |
| `agent.permission.review` | `callId`、`toolName`、`arguments`、`appForeground` |

## 处理事件

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

`headers` 是增量合并表：以原请求头为基底，返回的同名头覆盖原值，新头追加，未返回的头保持原值，插件不能借此删除原有头；最多 64 项，值为字符串。敏感头只有在最终 URL 与原 URL 的 scheme、host、port 完全一致，且回传值恰为 `{{Token}}` 时，才沿用真实值；返回其他值的敏感头被忽略。改写 URL 到其他 origin 时，原始敏感头会从最终请求中剥离，不会转发给第三方端点。`Host`、`Content-Length`、`Transfer-Encoding`、`Connection`、`Expect`、`Upgrade` 不允许改写。`body` 必须是合法 JSON 对象文本，最大 4 MiB；请求方法固定为 `POST`，插件不得修改。

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

## 执行顺序与超时

- 多个可写 hook 按插件 ID 升序、各自清单顺序执行，后一个 hook 读取前一个改写后的值；
- 其余 transform hook（`agent.tool.before` / `agent.tool.after`）单次调用上限 10 秒，一次拦截合计上限 20 秒；
- `agent.http.before` 使用独立预算：单次调用上限 3 秒，一次请求合计上限 5 秒（受 OkHttp 线程同步等待约束）；
- 超时、异常或返回值格式不合法时跳过该 hook 并保持当前值，不会因此自动放行或拒绝；
- 同一 `pluginId/localId` 的 hook 连续 3 次调用失败或超时后熔断 60 秒，期间跳过该 hook；成功后计数清零，冷却结束后的首次调用作为半开尝试；`agent.permission.review` 的 allow/deny/pass 结果不算失败，只有调用本身失败才累计；
- 代审核同样顺序执行，首个 `allow`/`deny` 生效；单次最多等待 5 分钟、总预算 5 分钟；超时或异常按 `pass` 继续，最终无人处理时回到用户确认；
- 只读 hook 异步派发，不进入 Agent 主流程；单项超时为 30 秒，超时将关闭该插件的运行时；同时执行的 hook 运行时调用最多 8 个，等待并发额度也计入各自超时预算；连续失败不会自动停用插件。

## 调试输出

handler 可返回 `logs` 写入应用日志，格式与限额见 [handler 约定](/advanced/plugin/tools-runtime#handler-约定)。可写 Hook 的 `decision`、`arguments`、`data`、`url`、`headers`、`body` 与 `logs` 可同时返回；代审核的 `decision`、`reason` 与 `logs` 可同时返回。

Transform Hook 不批准工具或绕过模式限制，改写参数后仍由宿主重新判断权限。代审核属于高权限能力，仅应启用可信插件。
