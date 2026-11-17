# 插件页面与宿主接口

推荐使用 **Vue 3 + TypeScript** 编写页面，构建后由 WebView 加载 HTML。页面通过宿主注入的 `window.aicode` 桥读写数据、执行命令、订阅变化；源码结构见[页面构建](/advanced/plugin/source-build#页面构建)。

本篇说明入口行为与页面桥接口。清单字段和槽位取值统一见 [plugin.json 清单参考](/advanced/plugin/manifest)。

## 页面入口

| 类型 | 声明位置 | 入口 | 典型用途 |
| --- | --- | --- | --- |
| 工作区工具 | `contributes.workspaceTools` | 右侧项目工具抽屉，用户添加后打开 | 构建、清理、部署、项目状态 |
| 槽位入口 | `contributes.uiEntries` | 设置页分组、侧栏底部、聊天顶栏菜单、空态建议、会话长按、文件树长按、编辑器选区菜单、备份页 | 记忆管理、快捷动作、工具面板 |
| 插件配置页 | `configPage` | 「设置 → 插件」的插件详情中 | 插件自身的参数、密钥、开关 |

三种页面使用同一套桥和主题，区别在于入口位置与生命周期。

### 工作区工具

使用 `contributes.workspaceTools` 声明 `pageFile`，写法见[工作区工具字段](/advanced/plugin/manifest#workspacetools)。使用者在右侧项目工具抽屉中添加后打开页面，再触发页面提供的操作。

短命令使用 `aicode.bash`，持续任务使用共享终端，接口见[命令与终端](#命令与终端)。

### 槽位入口

使用 `contributes.uiEntries` 将页面或动作挂到宿主固定位置。`page` 打开 HTML，`handler` 调用 runtime 的 `ui.invoke`；槽位取值、入口数量及组合规则见[槽位字段](/advanced/plugin/manifest#uientries)。`backup.data` 是宿主渲染的备份数据开关，流程见[备份与还原事件](/advanced/plugin/hooks#备份与还原事件)。

动作入口可用 `confirm: true` 在执行前弹确认框。handler 返回 `{message: "..."}` 时，宿主会用 toast 显示；失败应抛出异常，由 SDK 转为 JSON-RPC error。动作的 `args` 包含 `entryId`、`slot`，以及当前槽位提供的 `sessionId`、`path`、`isDirectory`、`selection`、`language`、`lineStart`、`lineEnd`。handler 可以调用 `host.*`。

侧栏底部最多内联展示 3 条，其余收进「更多插件」；顶栏菜单没有插件项时整段隐藏。

安装后入口立即出现，副标题标明插件来源；停用或卸载插件后入口消失。大屏下从侧栏、顶栏、会话菜单或文件树打开的页面在右侧工作台内展示，设置页入口沿用设置页自己的分栏布局，窄屏使用全屏页面；编辑器选区入口在编辑器浮动菜单内打开。

文件树/编辑器动作的 `path` 是当前执行环境的真实路径：本地模式为容器内路径，SSH 模式为远端服务器路径，可直接传给 `host.fs.*`；目录项同时带 `isDirectory`。

### 插件配置页

通过 `configPage` 指定页面，声明位置见[配置页字段](/advanced/plugin/manifest#configpage)。

配置页仅出现在「设置 → 插件」的插件详情中，与启停、卸载等原生项通过分段控件切换，不会混入设置主菜单。配置页适用于填写 API Key、开关和选项，仅在插件启用且首次切到配置标签时加载；停留在原生管理标签时不会提前执行页面脚本。停用、卸载或替换安装会使旧页面桥失效并取消该桥的在途任务、文件订阅和模型流；重新启用后需要重新打开页面，旧桥不会恢复授权。

### 一个加载入口承载多个视图

多个入口可共用 HTML 壳和启动脚本：

1. 将 `uiEntries[].page` 与 `configPage` 指向同一个 HTML 加载器。
2. 用 `aicode.context().pageId` 区分入口，配置页的值为 `config`。
3. 按入口选择 Vue Router 的 hash 路由，各视图拆为独立 `.vue` 文件并共用样式。

宿主仍按入口分别创建页面桥与缓存；不同入口可以直接打开各自视图。

## 加载资源

HTML 壳的完整写法见[页面构建](/advanced/plugin/source-build#html-壳)。`/__aicode/theme.css` 提供主题变量，`/__aicode/aicode.js` 注入页面桥，`/__aicode/vue.global.prod.js` 与 `aicode-ui-vue.js` 提供 Vue 和组件库。

页面入口使用包内 `.html` / `.htm`，资源经宿主虚拟域加载；页面大小限制见[安装限制](/advanced/plugin/packaging#安装限制)。

## 页面路由

宿主还提供 `/__aicode/vue-router.global.prod.js`（Vue Router 4.x 的官方 global prod 构建）。需要路由的页面在 Vue 之后引入它：

```html
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/vue-router.global.prod.js"></script>
```

通过 `VueRouter.createRouter({history: VueRouter.createWebHashHistory(), routes})` 创建路由，并在组件中放置 `<router-view>`。必须使用 hash 模式：`createWebHistory()` 生成的路径会被 WebView 主框架导航拦截。页面的返回处理见[返回键](#返回键)。

## 安全边界与资源缓存

插件页面运行在虚拟域 `https://appassets.androidplatform.net` 下，宿主对脚本来源与桥的可见范围做了限制：

- 页面脚本应随插件包提供。HTML 响应带 `Content-Security-Policy`：`script-src` 只允许虚拟域与内联脚本，远程脚本、`<iframe>` 与 `<object>` 会被直接阻断；需要联网时使用 `fetch` / `XMLHttpRequest`（`connect-src` 允许 http/https）或 `aicode.http.request`。
- 桥 `window.aicode` 只对虚拟域主 frame 生效。WebView 支持 WebMessageListener 时，宿主在回调里校验 `isMainFrame` 与来源的 scheme/host，子 frame 与其他来源的调用会被丢弃；系统不支持时回退 `addJavascriptInterface`，此时桥对所有 frame 可见，安全性依赖上述 CSP 只允许包内脚本执行。
- 插件包资源与宿主 `/__aicode/` 资源在进程内缓存字节，避免每次拦截请求都读盘：插件资源按 canonical path + mtime + size 缓存，宿主资源按路径缓存，LRU 上限约 64 项 / 8 MiB。响应头仍为 `Cache-Control: no-store`，插件同版本覆盖与 APK 更新后不会命中旧浏览器缓存；`/__aicode/theme.css` 跟随主题动态生成，不缓存。

## 宿主接口

宿主请求返回 Promise，失败时 reject 一个包含 `code` 与 `message` 的 Error。`events.on` 同步返回取消函数，`ui.onBack` 返回的 Promise 解析为取消函数。每次宿主请求都会校验页面所属插件仍启用且属于同一次安装，停用或替换后的旧页面不能继续调用宿主能力。

TypeScript 页面可直接使用 SDK 的 `@jieapi/plugin-sdk/page` 入口：它按本页的方法表声明了 `window.aicode`，并为 `fs.changed`、`chat.run.finished` 等事件提供 payload 类型，配置方式见[页面类型提示](/advanced/plugin/source-build#页面类型提示)。

### 基础

| 方法 | 作用 |
| --- | --- |
| `aicode.context()` | 返回 `pluginId`、`pageId`、`slot`、`sessionId`、`path`、`isDirectory`、`selection`、`language`、`lineStart`、`lineEnd`、`version`、`languageTag`、`theme`、`workspaceName`、`contextKey`、`visible`、`home`、`workspace`、`dataDir`；未使用的字段不出现 |
| `aicode.toast(message)` | 轻量提示 |
| `aicode.tip({title?, text, action?{label, handler}})` | 在对话中显示可关闭提示条；按钮点击回调同插件进程的 `handler` |
| `aicode.app.isForeground()` | App 当前是否处于前台（有 Activity 处于 STARTED） |
| `aicode.ui.close()` | 关闭插件页并回到打开前的位置（设置页、侧栏或顶栏对应的聊天页） |
| `aicode.ui.setOverlay(visible, rect)` | 打开或关闭页面内全屏弹层并上报矩形；宿主在全局背景图上按矩形挖孔 |
| `aicode.ui.onBack(handler)` | 接管页面内返回；`handler` 同步返回 `true` 表示已处理，Promise 解析为注销函数 |
| `aicode.applyTheme(variables)` | 设置 CSS 变量并派发 `aicode:theme` 事件 |

普通页面的上下文类型为 `AicodePageContext`；浮层页面返回 `AicodeOverlayContext`，仅含 `pluginId`、`overlayId`、`pageId`、`version`、`density`。TypeScript 中可用 `'overlayId' in context` 区分两者，浮层能力见[浮层页面](/advanced/plugin/overlay#浮层页面)。

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

标签默认不在终端页显示，使用者可在「设置 → 终端设置 → 显示插件运行」开启。标签归属于创建它的插件，其他插件与 AI 的终端工具不得操作。

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
| `aicode.chat.deleteSession(sessionId)` | 返回 `{sessionId, accepted}`，表示删除请求已入队；由聊天页取消在途任务并删除会话及消息 |
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

`sendMessage` 启动的 AI 可能调用需要授权的工具。插件页面是全屏 WebView 时，聊天页的授权弹窗不可见；插件应声明 `agent.permission.review` 并在页面内展示允许/拒绝卡片，或调用 `listApprovals` + `resolveToolApproval` 在页面内处理。计划审批与 `AskUserQuestion` 也分别通过 `plan` / `question` 与对应 resolve 方法处理。否则轮次会停在等待授权，直到用户处理或超时。

页面还会收到 `chat.run.started` / `chat.run.finished` 事件（payload 带 `sessionId`、`status`、`error?`），不需要插件 runtime 就能判断轮次是否结束。

### 模型句柄

声明 `contributes.modelAccess` 后，使用 `aicode.model.*` 直接调用已配置模型。方法表、消息格式、流式事件与工具循环统一见[模型句柄与流式调用](/advanced/plugin/model-handles)。

### 插件提供商

使用 `aicode.providers.*` 维护本插件声明的提供商，接口见[模型与凭据维护](/advanced/plugin/shizuku-providers#由插件维护模型与凭据)。这与读取已配置模型的 `model.*` 是两类能力，不需要 `contributes.modelAccess`。

### 工具与 MCP

| 方法 | 作用 |
| --- | --- |
| `aicode.tools.list(options?)` | 列出宿主注册工具；`options.includeSchema` 为 true 时附带参数 schema |
| `aicode.tools.execute(name, args, options?)` | 代执行一次工具调用；`options.sessionId` / `context` / `timeoutMs` 可选 |
| `aicode.tools.pending(sessionId?)` | 返回 `{approval}`；没有待审批请求时 `approval` 为 `null` |
| `aicode.tools.resolve(requestId, choice)` | 回传审批结果，`choice` 为 `once` / `always` / `reject` |

`execute` 只接受内置与 MCP 工具，权限策略为 ASK 时挂起等待页面处理，不会弹出聊天页审批框。页面应轮询 `pending` 并自行展示审批条，再调用 `resolve` 回传决定。

会话命名空间、工具范围、结果格式与错误码统一见[工具目录与代执行](/advanced/plugin/tools-runtime#工具目录与代执行)，页面审批用法见[审批流程](/advanced/plugin/model-handles#审批流程)。`aicode.mcp.status()` 返回 MCP server 连接状态。

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

使用 `aicode.shizuku.exec` 执行 Android 命令，声明、授权与参数见 [Shizuku](/advanced/plugin/shizuku-providers#shizuku)。

### 浮层

使用 `aicode.overlay.*` 管理窗口、尺寸、拖拽和消息，接口与浮层专用限制见[系统浮层](/advanced/plugin/overlay)。

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
const off = await aicode.ui.onBack(() => {
  if (drawerOpen) {
    drawerOpen = false;
    return true;
  }
  return false;
});
```

## 页面与进程

| 页面（WebView） | 插件运行时（容器进程） |
| --- | --- |
| 具有 DOM，适合表单、列表、预览和交互 | 不具有 DOM，适合文件处理、后台任务与跨页面共享状态 |
| 通过 `aicode.fs` 等宿主桥访问执行环境 | 通过 SDK 的 `hostCall` 或进程自身能力处理逻辑 |
| 用 `aicode.invoke` 调用同插件 handler | 响应 `ui.invoke`，也可主动推送页面事件 |

关闭页面不会停止运行时，进程回收与常驻行为见[运行时入口](/advanced/plugin/tools-runtime#运行时入口)。配置页的 Vue 写法统一见[单文件组件](/advanced/plugin/source-build#单文件组件)。

需要在卸载后保留的配置，可放在 `context().home + '/.aicode'` 下插件自己的命名空间内；`data://` 数据随卸载清理。
