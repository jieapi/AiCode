# 插件页面与宿主接口

插件使用 HTML/WebView 构建界面，共有三类入口：工作区工具、槽位入口（设置页 / 侧栏 / 顶栏 / 空态 / 会话菜单 / 文件树 / 编辑器选区 / 备份页）、插件配置页。页面通过宿主注入的 `window.aicode` 桥读写数据、执行命令、订阅变化。

页面源码可以放在 `src/pages/`，用 Vue SFC + Vite 构建成 `pages/*.html` 与 `pages/assets/*`；构建方式见[用 TypeScript 编写与构建插件](/advanced/plugin/source-build#页面构建)。

## 三种页面

| 类型 | 声明位置 | 入口 | 典型用途 |
| --- | --- | --- | --- |
| 工作区工具 | `contributes.workspaceTools` | 右侧项目工具抽屉，用户添加后打开 | 构建、清理、部署、项目状态 |
| 槽位入口 | `contributes.uiEntries` | 设置页分组、侧栏底部、聊天顶栏菜单、空态建议、会话长按、文件树长按、编辑器选区菜单、备份页 | 记忆管理、快捷动作、工具面板 |
| 插件配置页 | `configPage` | 「设置 → 插件」的插件详情中 | 插件自身的参数、密钥、开关 |

三种页面使用同一套桥和主题，区别在于入口位置与生命周期。

### 工作区工具

```json
{
  "contributes": {
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
}
```

用户可在右侧项目工具抽屉中添加并打开页面。页面通过桥读写数据、执行命令或发送事件；命令与文件操作仅在用户打开页面并点击对应操作后发生。

短命令使用 `aicode.bash` 同步执行；需要后台运行、实时输出或停止时，使用 `aicode.terminal.run` 创建共享标签，再通过 `terminal.poll(tabId, offset)` 按偏移读取增量输出、`terminal.stop` 发送 Ctrl-C、`terminal.close` 关闭。标签默认不在终端页显示；用户在「设置 → 终端设置 → 显示插件运行」开启后才可见。标签归属于创建它的插件，其他插件与 AI 的终端工具不得操作。

### 槽位入口

一个入口可以是页面或动作：

```json
{
  "contributes": {
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
        "handler": "actions.sync"
      }
    ]
  }
}
```

`page` / `handler` 必须且只能出现一个：

| 字段 | 行为 |
| --- | --- |
| `page` | 打开包内 HTML 页面，最大 512 KiB |
| `handler` | 调用插件 runtime 的 `ui.invoke`，需要声明 `runtime` |

动作入口可用 `confirm: true` 让宿主在执行前弹确认框。handler 返回 `{message: "..."}` 时，宿主会用 toast 显示这条消息；失败请用 JSON-RPC error 返回，不要在成功结果里包一层 `ok: false`。动作的 `args` 包含 `entryId`、`slot`，以及当前槽位可提供的上下文：`sessionId`、`path`、`isDirectory`、`selection`、`language`、`lineStart`、`lineEnd`。handler 内可正常调用 `host.*`，包括 `host.tip`、`host.notify` 和 `host.chat.*`。

`slot` 仅可取下列值，宿主据此将入口放入固定位置：

| 值 | 位置 |
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
| `editor.selection.menu` | 代码编辑器选中文本后的浮动菜单，窄屏与右栏编辑器共用 |
| `backup.data` | 备份页「选择导出数据」组末尾，只接受数据选项（宿主渲染开关） |

`settings.*` 只接受 `page` 入口；`files.tree.menu`、`editor.selection.menu` 只接受 `handler` 动作；`backup.data` 只接受没有 `page` / `handler` 的数据选项；其余聊天区槽位接受页面与动作。同一插件在每个槽位最多 2 个入口（`backup.data` 最多 1 个）；同一槽位内入口顺序由宿主按插件显示名与声明顺序决定，插件不能指定排序或插入位置。侧栏底部最多内联展示 3 条，其余收进「更多插件」；顶栏菜单没有插件项时整段隐藏。

安装后入口立即出现，副标题标明插件来源；停用或卸载插件后入口消失。大屏下从侧栏、顶栏、会话菜单或文件树打开的页面在右侧工作台内展示，设置页入口沿用设置页自己的分栏布局，窄屏使用全屏页面；编辑器选区入口在编辑器浮动菜单内打开。

文件树/编辑器动作的 `path` 是当前执行环境的真实路径：本地模式为容器内路径，SSH 模式为远端服务器路径，可直接传给 `host.fs.*`；目录项同时带 `isDirectory`。

备份页的 `backup.data` 是数据选项，不是动作：宿主在「选择导出数据」里渲染开关，勾选后插件数据会随宿主备份一起写入归档。要处理备份包内容，需要声明 `contributes.backup` 并由使用者授权，再监听 `backup.export.before` / `backup.import.finished`，详见[生命周期 Hooks](/advanced/plugin/hooks)。

### 插件配置页

```json
{
  "contributes": {
    "configPage": "pages/config.html"
  }
}
```

配置页仅出现在「设置 → 插件」的插件详情中，与启停、卸载等原生项通过分段控件切换，不会混入设置主菜单。配置页仅在插件启用时加载和预热，适用于填写 API Key、开关和选项。停用、卸载或替换安装会使旧页面桥失效并取消该桥的在途任务、文件订阅和模型流；重新启用后需要重新打开页面，旧桥不会恢复授权。

### 一个加载入口承载多个视图

同一插件的多个入口不必各留一份 HTML 壳和启动脚本。可以让 `uiEntries[].page` 与 `configPage` 指向同一个 HTML 加载器，在加载器里用 `aicode.context()` 的 `pageId` 判断入口（配置页为 `config`），再用 vue-router 的 hash 路由（`#/gallery` / `#/config`）选择独立视图。路由由入口决定，页面上不必放切换控件；视图代码仍可拆成多个 `.vue` 文件，共用一份入口和公共样式；清单里的多个入口可以指向同一个页面文件，宿主仍按入口分别创建页面桥与缓存。

示例见[示例库](/advanced/plugin/examples)中的 `ui-gallery`。

## 加载资源

页面中引用宿主提供的脚本和样式：

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="/__aicode/theme.css">
  <script src="/__aicode/aicode.js"></script>
</head>
<body>
  <main id="app"></main>
</body>
</html>
```

`/__aicode/theme.css` 提供跟随 App 主题的 CSS 变量，`/__aicode/aicode.js` 注入 `window.aicode`。页面资源仅能从插件包经虚拟域加载，外部链接会被宿主拦截。

页面文件最大 512 KiB。`uiEntries` 的 `page` 入口和 `workspaceTools` 的 `pageFile` 必须是包内 `.html`/`.htm`；`handler` 入口不涉及页面文件。

## 页面路由

宿主还提供 `/__aicode/vue-router.global.prod.js`（Vue Router 4.x 的官方 global prod 构建）。需要路由的页面在 Vue 之后引入它：

```html
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/vue-router.global.prod.js"></script>
```

然后使用 `VueRouter.createRouter(VueRouter.createWebHashHistory(), routes)` 与 `<router-view>`。必须使用 hash 模式：`createWebHistory()` 生成的路径会被 WebView 的主框架导航拦截。系统返回手势仍通过 `aicode.ui.onBack` 交给页面，页面需要把返回接到 `router.back()` 或 `router.replace('/gallery')`。

## 安全边界与资源缓存

插件页面运行在虚拟域 `https://appassets.androidplatform.net` 下，宿主对脚本来源与桥的可见范围做了限制：

- 页面脚本应随插件包提供。HTML 响应带 `Content-Security-Policy`：`script-src` 只允许虚拟域与内联脚本，远程脚本、`<iframe>` 与 `<object>` 会被直接阻断；需要联网时使用 `fetch` / `XMLHttpRequest`（`connect-src` 允许 http/https）或 `aicode.http.request`。
- 桥 `window.aicode` 只对虚拟域主 frame 生效。WebView 支持 WebMessageListener 时，宿主在回调里校验 `isMainFrame` 与来源的 scheme/host，子 frame 与其他来源的调用会被丢弃；系统不支持时回退 `addJavascriptInterface`，此时桥对所有 frame 可见，安全性依赖上述 CSP 只允许包内脚本执行。
- 插件包资源与宿主 `/__aicode/` 资源在进程内缓存字节，避免每次拦截请求都读盘：插件资源按 canonical path + mtime + size 缓存，宿主资源按路径缓存，LRU 上限约 64 项 / 8 MiB。响应头仍为 `Cache-Control: no-store`，插件同版本覆盖与 APK 更新后不会命中旧浏览器缓存；`/__aicode/theme.css` 跟随主题动态生成，不缓存。

## 宿主接口

所有方法均返回 Promise。失败时 reject 一个包含 `code` 与 `message` 的 Error。每次调用都会校验页面所属插件仍启用且属于同一次安装；停用或替换后的旧页面不能继续读写文件、执行命令或调用其他宿主能力。

### 基础

| 方法 | 作用 |
| --- | --- |
| `aicode.context()` | 返回 `pluginId`、`pageId`、`slot`、`sessionId`、`path`、`isDirectory`、`selection`、`language`、`lineStart`、`lineEnd`、`version`、`languageTag`、`theme`、`workspaceName`、`contextKey`、`visible`、`home`、`workspace`、`dataDir`；未使用的字段不出现 |
| `aicode.toast(message)` | 轻量提示 |
| `aicode.tip({title?, text, action?{label, handler}})` | 在对话中显示可关闭提示条；按钮点击回调同插件进程的 `handler` |
| `aicode.app.isForeground()` | App 当前是否处于前台（有 Activity 处于 STARTED） |
| `aicode.ui.close()` | 关闭插件页并回到打开前的位置（设置页、侧栏或顶栏对应的聊天页） |
| `aicode.ui.setOverlay(visible, rect)` | 打开或关闭页面内全屏弹层并上报矩形；宿主在全局背景图上按矩形挖孔 |
| `aicode.ui.onBack(handler)` | 接管页面内返回；`handler` 同步返回 `true` 表示已处理，返回注销函数 |
| `aicode.applyTheme(variables)` | 设置 CSS 变量并派发 `aicode:theme` 事件 |

### 文件

| 方法 | 作用 |
| --- | --- |
| `aicode.fs.read(path, options?)` | 读取文件，返回 `{content, size}` |
| `aicode.fs.write(path, content, options?)` | 原子写入文件 |
| `aicode.fs.list(path, options?)` | 列出目录，返回 `{entries: [{name, isDir, size}]}` |
| `aicode.fs.delete(path, options?)` | 删除文件或目录 |
| `aicode.fs.stat(path, options?)` | 返回 `{exists, isDir, size}` |
| `aicode.fs.mkdir(path, options?)` | 创建目录（含父目录） |
| `aicode.fs.rename(from, to, options?)` | 移动/重命名；目标已存在时返回 `FILE_EXISTS` |
| `aicode.fs.copy(from, to, options?)` | 复制文件或目录；`options.overwrite` 默认 false |
| `aicode.fs.append(path, content, options?)` | 追加文本；文件总大小不得超过 1 MiB |
| `aicode.fs.readBinary(path, options?)` | 读取二进制，返回 `{base64, size}`；上限 512 KiB |
| `aicode.fs.writeBinary(path, base64, options?)` | 写入 Base64 二进制；上限 1 MiB |
| `aicode.fs.watch(path, options?, handler)` | 订阅文件来源变化，Promise 解析为取消订阅函数 |

文件路径是当前执行环境的真实绝对路径：本地模式为容器内路径，SSH 模式为远端服务器路径。`~` 展开到当前 home，裸相对路径挂到工作区根。插件自己的数据目录有稳定的虚拟根 `data://`，与插件 ID 和执行环境无关。

| 位置 | 路径写法 | 读写 |
| --- | --- | --- |
| 插件自身数据目录，卸载时清理 | `data://…` 或 `context().dataDir` | 读写 |
| 当前工作区 | `context().workspace` | 读写 |
| AI 配置目录 | `context().home + '/.aicode'` | 读写 |
| 容器或服务器上的其它路径 | 直接写绝对路径 | 读写 |

单次读取上限 512 KiB，写入上限 1 MiB，目录最多返回 500 条。本地模式下宿主只映射容器 rootfs、工作区与 `~/.aicode`，App 自身的其它私有目录没有对应的容器路径，因此不可达。

文件方法可在最后一个 `options` 参数中传入 `{contextKey}`。上下文已经变化时请求返回 `STALE_CONTEXT`，用于拦截仍在排队的旧项目操作：

```js
const ctx = await aicode.context();
const file = await aicode.fs.read(ctx.workspace + '/README.md', {contextKey: ctx.contextKey});
```

### 命令与终端

| 方法 | 作用 |
| --- | --- |
| `aicode.bash(command, options?)` | 在当前工作区执行一次性命令，返回 `{output, exitCode, truncated}`；`options.timeout` 以秒计，默认 30、上限 300 |
| `aicode.terminal.run(command, options?)` | 创建共享终端标签，返回 `{tabId, state}`；`options.title` 可选 |
| `aicode.terminal.poll(tabId, offset)` | 返回 `{output, offset, state, exitCode?, truncated}`；`state` 为 `running` / `finished` / `cancelled` |
| `aicode.terminal.stop(tabId)` | 发送 Ctrl-C；2 秒仍未退出则关闭标签 |
| `aicode.terminal.close(tabId)` | 关闭标签 |
| `aicode.terminal.write(tabId, text, options?)` | 向标签写入输入并回车；`options.appendNewline` 默认 true，文本上限 4000 字符 |
| `aicode.terminal.list()` | 列出当前插件创建的标签 `{tabId, title, command, state}` |

`aicode.bash` 适用于版本检测、状态查询等短命令，不应启动常驻进程或交互式程序。命令执行环境的初始化由终端页负责；未初始化时退出码为 `null`，输出中会包含引导提示。

终端输出按偏移轮询；宿主仅保留最近 128 KiB，超出后 `truncated` 为 true。关闭页面不会停止命令；插件停用或卸载时，宿主将关闭其创建的全部标签。

### 会话与消息

插件可以在当前工作区创建会话、读取会话与消息，并向会话发送一条用户消息来启动 AI 轮次。发送的消息与手动输入完全同路径：会正常落库、进入上下文、消耗 token，并经过工具授权、PLAN/AUTO 模式、hooks 与队列，不会绕过宿主审批。

| 方法 | 作用 |
| --- | --- |
| `aicode.chat.listSessions(options?)` | 返回 `{sessions}`；`options.includeSubagents` 默认 false，`options.limit` 默认 50、上限 200 |
| `aicode.chat.createSession(options?)` | 返回 `{session}`；`options.title` 最多 100 字，`options.mode` 仅支持 `build` / `plan`（`auto` 只能由使用者手动选择） |
| `aicode.chat.listMessages(sessionId, options?)` | 返回 `{sessionId, messages, hasMore}`；`options.limit` 默认 50、上限 200，`options.before` / `options.after` 为毫秒时间戳，二者互斥 |
| `aicode.chat.sendMessage(sessionId, text)` | 向会话追加用户消息并启动 AI 轮次；`text` 最多 32000 字符 |
| `aicode.chat.setDraft(text, options?)` | 把文本写入输入框；`options.sessionId` 缺省当前会话，`options.append` 为 true 时追加。需要聊天页存活 |
| `aicode.chat.stop(sessionId)` | 停止该会话正在运行的 AI 轮次 |
| `aicode.chat.openSession(sessionId)` | 把聊天页切换到该会话 |
| `aicode.chat.getSession(sessionId)` | 返回单个会话 |
| `aicode.chat.renameSession(sessionId, title)` | 重命名；标题最多 100 字 |
| `aicode.chat.pinSession(sessionId, pinned)` | 置顶/取消置顶 |
| `aicode.chat.deleteSession(sessionId)` | 删除会话及其消息；由聊天页取消在途任务并清理运行时状态 |
| `aicode.chat.getRunState(sessionId)` | 返回 `{sessionId, run}`，`run` 含 `running`、`status`、`startedAt`、`finishedAt`、`error?` |
| `aicode.chat.waitForRun(sessionId, options?)` | 等待本轮结束；`options.timeoutMs` 默认 120000、范围 1000–600000；返回 `{timedOut, run}` |
| `aicode.chat.listApprovals(sessionId)` | 返回 `{tool, question, plan}`，分别对应待确认工具、提问与计划审批 |
| `aicode.chat.resolveToolApproval(sessionId, requestId, choice)` | `choice` 为 `once` / `always` / `reject`；`always` 仅在可记忆时可用 |
| `aicode.chat.resolveQuestion(sessionId, requestId, answers)` | `answers` 为 `[{question, selected: [...], customText?}]` |
| `aicode.chat.resolvePlanApproval(sessionId, requestId, choice)` | `choice` 为 `approve` / `refine` |

会话与消息读取仅限当前工作区；跨工作区的会话 id 返回 `SESSION_FORBIDDEN`。`sendMessage` / `stop` / `openSession` 需要聊天页 ViewModel 存活（App 主界面存在时即满足），否则返回 `CHAT_UI_UNAVAILABLE`；请求队列已满时返回 `CHAT_BUSY`。常见错误码还包括 `PLUGIN_INACTIVE`、`WORKSPACE_UNAVAILABLE`、`SESSION_NOT_FOUND`、`MODE_UNSUPPORTED` 与 `PARAM_INVALID`。

`sendMessage` 只表示请求已入队，不等待 AI 完成。插件可以监听页面的 `chat.run.finished` 事件、已有的 `agent.run.finished` hook（payload 含 `sessionId`），或调用 `waitForRun` / `listMessages`。消息对象包含 `id`、`role`、`content`、`timestamp`、`toolName`、`isError`、压缩标记、token 计数，以及可选的 `reasoning`（最多 8000 字符）、`toolCalls`（最多 50 条，单条参数最多 4000 字符）与 `attachments`（最多 20 条，不含宿主本地路径）。

```js
const created = await aicode.chat.createSession({title: '插件快捷提问', mode: 'build'});
await aicode.chat.openSession(created.session.id);
await aicode.chat.sendMessage(created.session.id, '用一句话介绍当前项目');
```

完整对话界面与「完成事件 + 轮询兜底」示例见[示例库](/advanced/plugin/examples)的 `chat-bridge`。

`sendMessage` 启动的 AI 可能调用需要授权的工具。插件页面是全屏 WebView 时，聊天页的授权弹窗不可见；插件应声明 `agent.permission.review` 并在页面内展示允许/拒绝卡片（`chat-bridge` 已示范），或调用 `listApprovals` + `resolveToolApproval` 在页面内处理。计划审批与 `AskUserQuestion` 也分别通过 `plan` / `question` 与对应 resolve 方法处理。否则轮次会停在等待授权，直到用户处理或超时。

页面还会收到 `chat.run.started` / `chat.run.finished` 事件（payload 带 `sessionId`、`status`、`error?`），不需要插件 runtime 就能判断轮次是否结束。

### 模型句柄

声明 `contributes.modelAccess` 后，页面可以直接调用已配置的供应商与模型，不经过聊天页或 Agent 循环。句柄只保存供应商、模型与默认提示词；对话历史由插件每次传入。`start` 还可以携带工具定义、工具结果、思考签名与图片，工具循环由插件自己驱动。

| 方法 | 作用 |
| --- | --- |
| `aicode.model.listProviders()` | 返回 `{providers}`；不含 Key、地址与自定义头 |
| `aicode.model.listModels({providerId})` | 返回 `{providerId, models}`，含窗口、能力与单价元数据 |
| `aicode.model.create({providerId, model, name?, systemPrompt?, params?})` | 返回 `{handle}`；每个插件最多 16 个 |
| `aicode.model.close(handleId)` | 关闭句柄并取消它的流 |
| `aicode.model.start({handleId, messages, tools?, systemPrompt?, params?})` | 返回 `{streamId}`；立即返回，事件由 `read` 拉取 |
| `aicode.model.read({streamId, cursor?, maxEvents?, waitMs?})` | 返回 `{events, cursor, done, status?, final?, error?}` |
| `aicode.model.cancel(streamId)` | 取消请求，保留已产生的事件 |
| `aicode.model.stream(options, onEvent)` | `start` + `read` 循环，逐条回调事件并返回最终 read 结果 |

`messages` 支持 `user`、`assistant`、`tool` 三种角色：`user` 可带 `images`；`assistant` 可带 `toolCalls`、`reasoning` 与 `providerState`；`tool` 必带 `toolCallId` 与 `name`。`tools` 里 `{type: 'app', name}` 引用内置 / MCP 工具，`{type: 'custom', name, description?, inputSchema?}` 由插件自己执行。`final` 带 `toolCalls`，并新增 `providerState` 与 `images`（base64）。完整消息格式、工具循环、审批与限额见[模型句柄与流式调用](/advanced/plugin/model-handles)。

### 工具与 MCP

| 方法 | 作用 |
| --- | --- |
| `aicode.tools.list(options?)` | 列出宿主注册工具；`options.includeSchema` 为 true 时附带参数 schema |
| `aicode.tools.execute(name, args, options?)` | 代执行一次工具调用；`options.sessionId` / `context` / `timeoutMs` 可选 |
| `aicode.tools.pending(sessionId?)` | 返回 `{approval}`；没有待审批请求时为 `null` |
| `aicode.tools.resolve(requestId, choice)` | 回传审批结果，`choice` 为 `once` / `always` / `reject` |

`execute` 只接受内置与 MCP 工具，`plugin__` 前缀返回 `TOOL_NOT_ALLOWED`；`askUserQuestion`、`planMode`、`goalStatus`、`todo`、`task`、`messageParent` 依赖聊天会话，同样返回 `TOOL_NOT_ALLOWED`。`sessionId` 缺省为 `plugin:<插件ID>`，只能使用该会话或其 `:` 子会话，否则返回 `SESSION_FORBIDDEN`。`timeoutMs` 默认 120000、范围 1000–600000，同时约束等待授权与执行本身。

权限策略为 ASK 时 `execute` 会挂起，且不会弹出聊天页审批框。页面需要轮询 `pending` 并自行渲染审批条：

```js
const sessionId = 'plugin:' + (await aicode.context()).pluginId + ':my-page'
const timer = setInterval(async () => {
  const pending = await aicode.tools.pending(sessionId).catch(() => null)
  renderApproval(pending ? pending.approval : null) // 按钮调用 aicode.tools.resolve(approval.id, 'once')
}, 800)

try {
  const result = await aicode.tools.execute('writeFile', {path, content}, {sessionId})
  appendToolResult(result)
} finally {
  clearInterval(timer)
  renderApproval(null)
}
```

`approval` 含 `id`、`toolName`、`title`、`summary`、`details`、`argsPreview`、`rememberablePatterns`、`rememberDisabledReason?`；`rememberablePatterns` 为空时不要展示「始终允许」。结果对象为 `{status, data?, message?, code?, images?, imagesTruncated?, dataTruncated?}`，`status` 为 `success` / `error` / `partial`。`aicode.mcp.status()` 返回 MCP server 连接状态。

### 系统、密钥与网络

| 方法 | 作用 |
| --- | --- |
| `aicode.clipboard.read()` / `write(text)` | 读取或写入系统剪贴板，上限 1 MiB |
| `aicode.share.text(text, {title?})` | 拉起系统分享面板；文本最多 100000 字符 |
| `aicode.ui.openUrl(url)` | 用系统浏览器打开 http/https 链接；最长 4096 字符 |
| `aicode.app.info()` | 返回包名、版本、SDK、语言与前后台状态 |
| `aicode.log.write(level, message, {tag?})` | 写宿主日志；`level` 为 debug/info/warn/error，正文最多 2000 字符 |
| `aicode.secrets.get(key)` / `set(key, value)` / `delete(key)` / `list()` | 插件私有加密键值存储；值最多 64 KiB，`list` 只返回键名 |
| `aicode.http.request({url, method?, headers?, body?, timeout?})` | 宿主代理 HTTP 请求；仅 http/https，不跟随重定向，不注入宿主凭据；响应体上限 1 MiB |

密钥值由 Android Keystore 加密后存放在插件私有目录，明文只在请求瞬间存在于内存；不要在 `data://` 明文保存 API Key。HTTP 请求的 headers 由插件自行提供，插件应避免把密钥写进日志。

### 进程调用

| 方法 | 作用 |
| --- | --- |
| `aicode.invoke(handler, args)` | 调用同一插件常驻进程的 handler，返回其 `result` |

仅允许调用当前页面对应插件的进程，不可跨插件。宿主会按需启动插件 runtime，单次等待上限为 60 秒。插件进程需处理 `ui.invoke`。

### Shizuku

| 方法 | 作用 |
| --- | --- |
| `aicode.shizuku.exec(command, options)` | 经 Shizuku 在 Android 系统执行；`options.timeout` 以秒计 |

需要插件声明 `contributes.shizuku`，且使用者已在插件详情中单独授权。系统中的 Shizuku 服务须正在运行。

### 浮层

| 方法 | 作用 |
| --- | --- |
| `aicode.overlay.create({surface?, width?, height?, visibilityPolicy?})` | 创建清单声明的系统浮层，返回 `{overlayId, width, height, ...}` |
| `aicode.overlay.update(overlayId, {width?, height?, visible?, visibilityPolicy?, dx?, dy?})` | 更新浮层尺寸、位置、可见性或显示时机 |
| `aicode.overlay.close(overlayId)` | 关闭指定浮层；浮层页面内可省略 id 关闭自身 |
| `aicode.overlay.self()` | 浮层页面读取自身 id、尺寸、位置与密度 |
| `aicode.overlay.setDragRegion({left, top, width, height})` | 浮层页面声明宿主接管拖拽的区域，单位为 CSS 像素 |
| `aicode.overlay.resize(width, height, options?)` | 浮层页面调整自身尺寸，单位为 dp；`options` 可指定 `{animate: true, x, y}` |
| `aicode.overlay.setVisibilityPolicy(policy)` | 设置显示时机：`always` / `foreground` / `background` |
| `aicode.overlay.moveBy(dx, dy)` | 请求移动窗口 |
| `aicode.overlay.onMessage(handler)` | 接收宿主消息：`state`、`resize`、`appState`、`click`、`dragStart`、`dragEnd`、`longPress`、`snap` |
| `aicode.overlay.postMessage(payload)` | 将交互回传至插件进程的 `overlay.message` handler |

浮层页面不得调用 Shizuku、Shell 或终端。详见[系统浮层](/advanced/plugin/overlay)。

### 事件

| 方法 | 作用 |
| --- | --- |
| `aicode.events.on(handler)` | 监听插件进程推送的事件和宿主生命周期事件，返回取消订阅函数 |

事件包括插件进程发送的 `plugin.event`、文件订阅的 `fs.changed`，以及页面的 `ui.lifecycle`。详细用法参见[页面样式、组件与事件](/advanced/plugin/ui-components)。

## 返回键

返回键按以下顺序处理：

1. WebView 自身历史（如 hash 路由）；
2. `aicode.ui.onBack` 注册的处理器，后注册的优先；
3. 退出插件页。

页面存在多层视图时，使用 `onBack` 接管返回；回到最外层时返回 `false`，交由宿主退出。顶栏自绘的返回按钮应在首页调用 `aicode.ui.close()`，在子页调用自身的路由返回。

```js
const off = aicode.ui.onBack(() => {
  if (drawerOpen) {
    drawerOpen = false;
    return true;
  }
  return false;
});
```

## 页面与进程

页面与 AI 工具运行在同一插件的容器进程体系中：

- 页面具有 DOM 与桥，可使用 `aicode.invoke` 调用同插件进程的 `ui.invoke`；
- AI 工具 handler 不具备 DOM；
- 页面不可直接访问容器文件系统，必须通过 `aicode.fs` 或由进程代为处理；
- 关闭页面不会停止进程；进程空闲 10 分钟后才会回收（`resident` 除外）。

适合在页面中实现的逻辑：表单、列表、预览、配置、需要用户点击确认的操作。适合在进程中实现的逻辑：文件处理、长命令、网络请求、需要跨页面共享的状态。

## 完整示例：配置页

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="/__aicode/theme.css">
  <script src="/__aicode/aicode.js"></script>
</head>
<body>
  <main class="page">
    <h2>插件设置</h2>
    <label>
      服务地址
      <input id="endpoint" placeholder="https://example.com">
    </label>
    <button id="save">保存</button>
    <p id="status"></p>
  </main>

  <script>
    const endpoint = document.getElementById('endpoint');
    const status = document.getElementById('status');

    async function load() {
      const result = await aicode.fs.read('data://config.json').catch(() => null);
      if (result) {
        try {
          endpoint.value = JSON.parse(result.content).endpoint || '';
        } catch (e) {
          status.textContent = '配置格式损坏';
        }
      }
    }

    document.getElementById('save').addEventListener('click', async () => {
      await aicode.fs.write('data://config.json', JSON.stringify({endpoint: endpoint.value.trim()}, null, 2));
      status.textContent = '已保存';
      aicode.toast('配置已保存');
    });

    load();
  </script>

  <style>
    .page {
      box-sizing: border-box;
      width: 100%;
      max-width: 860px;
      margin: 0 auto;
      padding: 16px;
    }
  </style>
</body>
</html>
```

`data://` 是插件自身的数据目录，卸载时清理，也与插件 ID 解耦；需要在卸载后保留的配置应放在 `context().home + '/.aicode'` 下插件自己的命名空间内。

## 限制

- 页面资源仅能从插件包经虚拟域加载，外部链接会被宿主拦截；
- 页面脚本必须随包提供，远程脚本、iframe 与 object 会被 CSP 阻断；
- 桥只对虚拟域主 frame 生效，子 frame 无法调用宿主接口；
- 槽位入口在插件停用后消失，页面请求将被拒绝；
- `uiEntries`、`workspaceTools`、`configPage` 的页面都必须是包内 HTML，单文件最大 512 KiB；
- 页面不可绕过宿主桥直接读取容器文件。
