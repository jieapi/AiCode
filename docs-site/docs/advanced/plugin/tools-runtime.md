# AI 工具与插件运行时

AI 工具使模型能够调用插件自身代码。业务逻辑运行在容器内的插件子进程中，通过按行 JSON-RPC 与宿主通信；同一进程还可响应 hook 和页面调用。

## 声明工具

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "contributes": {
    "tools": [
      {
        "id": "echo",
        "title": {"default": "Echo", "zh-CN": "回显"},
        "description": {"default": "Echo the given text.", "zh-CN": "将传入的文本原样回显。"},
        "inputSchema": "schemas/echo.input.json",
        "handler": "tools.echo"
      }
    ]
  }
}
```

`schemas/echo.input.json` 必须是根为 object 的标准 JSON Schema：

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

## 进程协议

宿主与插件均按行读写 JSON，一行一条消息，不可跨行。

宿主发往插件：

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `tool.invoke` | `{handler, args}` | 执行 AI 工具 |
| `hook.invoke` | `{handler, args}` | 执行生命周期 hook |
| `ui.invoke` | `{handler, args}` | 页面通过 `aicode.invoke` 调用进程 |

插件使用同一条 `id` 返回 `result` 或 `error`：

```json
{"jsonrpc":"2.0","id":1,"result":{"text":"hello"}}
{"jsonrpc":"2.0","id":2,"error":{"code":"PLUGIN_ERROR","message":"something failed"}}
```

插件发往宿主：

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `host.fs.read` / `write` / `list` / `stat` / `delete` | 见下表 | 读写四个虚拟根 |
| `host.fs.mkdir` / `rename` / `copy` / `append` / `readBinary` / `writeBinary` | 见下表 | 目录、移动、复制、追加与二进制读写 |
| `host.app.isForeground` | 无 | App 是否有 Activity 处于 STARTED |
| `host.bash` | `{command, timeout?}` | 在当前工作区执行一次性命令 |
| `host.terminal.run` / `poll` / `stop` / `close` / `write` / `list` | 同页面桥 | 管理共享终端标签 |
| `host.notify` | `{title, body, key?}` | 发送系统通知 |
| `host.tip` | `{title?, text, action?}` | 在对话中显示提示条 |
| `host.shizuku.exec` | `{command, timeout?}` | 经 Shizuku 执行 Android 命令 |
| `host.chat.*` | 同页面桥 | 会话、消息、运行状态与审批 |
| `host.clipboard.read` / `write`、`host.share.text`、`host.ui.openUrl` | 见下表 | 系统剪贴板、分享与打开链接 |
| `host.app.info`、`host.log` | 见下表 | App 信息与宿主日志 |
| `host.secrets.get` / `set` / `delete` / `list` | 见下表 | 插件私有加密键值存储 |
| `host.tools.list`、`host.mcp.status` | 见下表 | 工具与 MCP 状态 introspection |
| `host.http.request` | 见下表 | 宿主代理 HTTP 请求 |
| `host.overlay.create` / `close` / `postMessage` / `update` | 同页面桥 | 管理系统浮层 |
| `plugin.event` | `{name, payload}` | 通知，推送给同插件已打开的页面 |

`host.*` 是普通 JSON-RPC 请求，需要插件自行生成 `id`，并将宿主响应与推送分开处理。最小 JavaScript 消息循环参见[快速开始](/advanced/plugin/quick-start)。

### host API 细节

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
| `host.bash` | `{command, timeout?}` | `{output, exitCode, truncated}`；默认 30 秒，范围 1–300 秒；命令最长 4000 字符 |
| `host.terminal.run` | `{command, title?}` | `{tabId, state}`；命令最长 4000 字符 |
| `host.terminal.poll` | `{tabId, offset}` | `{output, offset, state, exitCode?, truncated}`；仅保留最近 128 KiB |
| `host.terminal.stop` | `{tabId}` | 发送 Ctrl-C，2 秒未退出则关闭 |
| `host.terminal.close` | `{tabId}` | 关闭标签 |
| `host.terminal.write` | `{tabId, text, appendNewline?}` | 向标签写入输入；文本最多 4000 字符 |
| `host.terminal.list` | 无 | 列出该插件自己的标签 `{tabId, title, command, state}` |
| `host.notify` | `{title, body, key?}` | 标题最多 100 字，正文最多 1000 字，`key` 最多 64 字；同一 `key` 覆盖上一条 |
| `host.tip` | `{title?, text, action?{label, handler}}` | 标题最多 80 字，正文最多 500 字，按钮文字最多 24 字；同插件仅保留最新一条，队列最多 3 条 |
| `host.shizuku.exec` | `{command, timeout?}` | 默认 120 秒；需要插件声明 `shizuku` 且使用者单独授权 |
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
| `host.clipboard.read` / `write` | `{}` / `{text}` | 剪贴板文本，上限 1 MiB |
| `host.share.text` | `{text, title?}` | 拉起系统分享；文本最多 100000 字符 |
| `host.ui.openUrl` | `{url}` | 仅 http/https，最长 4096 字符 |
| `host.app.info` | 无 | 包名、版本、SDK、语言、前后台 |
| `host.log` | `{level, message, tag?}` | 写宿主日志；正文最多 2000 字符 |
| `host.secrets.get` / `set` / `delete` | `{key}` / `{key, value}` / `{key}` | 加密键值；值最多 64 KiB |
| `host.secrets.list` | 无 | `{keys}`，只返回键名 |
| `host.tools.list` | 无 | `{tools}`；最多 200 项 |
| `host.mcp.status` | 无 | `{servers}` 连接状态 |
| `host.http.request` | `{url, method?, headers?, body?, timeout?}` | `{status, headers, body, truncated}`；不跟随重定向、不注入凭据 |
| `host.overlay.create` | `{surface?, width?, height?, visibilityPolicy?}` | 创建清单声明的浮层 |
| `host.overlay.update` | `{overlayId, width?, height?, visible?, visibilityPolicy?, dx?, dy?}` | 更新浮层 |
| `host.overlay.close` | `{overlayId}` 或 `{surface}` | 关闭浮层 |
| `host.overlay.postMessage` | `{overlayId?, surface?, payload}` | 向浮层页面发送消息 |

路径必须以虚拟根开头：

| 根 | 位置 | 读写 |
| --- | --- | --- |
| `data://` | 插件自身的数据目录，卸载时清理 | 读写 |
| `plugin://` | 插件安装目录 | 只读 |
| `workspace://` | 当前工作区 | 读写 |
| `aicode://` | 全局 `.aicode` 目录 | 读写 |

`aicode://` 下的 `plugins/`、`permissions.json`、`mcp.json`、`skills/`、`agents/`、`prompts.custom/`、`docs/` 禁止访问。单次读取上限 512 KiB，写入上限 1 MiB，目录最多返回 500 条。

工具调用单次超时为 120 秒。超时或进程异常将结束该次调用并返回工具错误；常驻进程不会因该次超时被直接终止。

`host.chat.sendMessage` 只表示请求已入队，不等待 AI 完成；可用 `host.chat.waitForRun`、页面事件 `chat.run.finished`、已有的 `agent.run.finished` hook 或 `host.chat.listMessages` 获取结果。`sendMessage` / `stop` / `openSession` / `deleteSession` 需要聊天页 ViewModel 存活（App 主界面存在时即满足），否则返回 `CHAT_UI_UNAVAILABLE`。消息与手动输入走同一套授权、模式、hooks 与队列，不会绕过宿主审批。

`host.chat.listApprovals` 返回该会话待处理的工具授权、提问与计划审批；对应 resolve 接口只接受该会话当前的 requestId，不能用它批准其他会话或已过期的请求。页面内处理工具授权也可以继续使用 `agent.permission.review` hook。

`host.http.request` 只接受 http/https，不跟随重定向、不注入宿主凭据；URL 最长 4096 字符，headers 最多 64 项且禁止 Host/Content-Length 等受控头，请求体与响应体各最多 1 MiB，超时 1–60 秒。

## handler 约定

以下示例沿用[快速开始](/advanced/plugin/quick-start)中的 `hostCall` 与消息循环。

handler 可返回字符串、数字、布尔、数组或对象。抛出的异常会作为工具错误返回给 AI。

```js
const handlers = {
  'tools.echo': (args) => ({text: String(args.text ?? '')}),
  'tools.notify': async (args) => {
    await hostCall('host.notify', {
      title: String(args.title ?? ''),
      body: String(args.body ?? ''),
      key: args.key === undefined ? undefined : String(args.key)
    });
    return {ok: true};
  },
  'tools.run': async (args) => {
    const result = await hostCall('host.bash', {
      command: String(args.command),
      timeout: 60
    });
    return result;
  }
};
```

需要调试时可返回 `logs`：

```js
const handlers = {
  'tools.echo': (args) => ({
    text: String(args.text ?? ''),
    logs: ['echo called', 'length=' + String(args.text ?? '').length]
  })
};
```

`logs` 最多 50 行、单行 500 字符，写入应用日志，不会作为工具结果展示给 AI。

页面也可调用同一进程：

```js
const result = await aicode.invoke('tools.echo', {text: 'hi'});
```

进程需处理 `ui.invoke`，否则返回 `METHOD_UNKNOWN`。`aicode.invoke` 单次最多等待 60 秒。

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

在插件详情页点击「准备依赖」，宿主将在插件目录中执行 `npm ci`/`npm install`，或 `python3 -m venv` 加 `pip install`。容器缺少 node/npm 或 python3 时仅提示前往终端执行「环境安装」，不会自动安装系统包。

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

`app.started` 仅在 App 主进程启动完成时派发一次，payload 包含 `launchId`、`startedAt`、`appVersion`、`process`。handler 应在完成初始化后尽快返回；需要常驻的服务应在进程内自行转入后台。观察 hook 单次超时为 30 秒，超时将关闭该插件进程。容器未初始化或缺少 node/python 时，hook 将失败并记录日志；之后调用工具或 hook 仍会按需启动进程。

## 安全边界

- 插件进程与 Bash 同级，拥有容器内的文件、网络和命令能力；
- `host.fs` 的路径限制仅约束宿主接口，不约束进程自身；
- 命令与参数会原样执行，请勿在 `command` 中拼接未经校验的用户输入；
- 仅安装可信来源的插件。

## 相关

- 工具调用前后的拦截与改写：[生命周期 Hooks](/advanced/plugin/hooks)
- 插件进程向页面推送事件：[页面样式、组件与事件](/advanced/plugin/ui-components)
- 将由进程启动的本地服务注册为提供商：[Shizuku 与插件提供商](/advanced/plugin/shizuku-providers)
