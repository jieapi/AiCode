# 开发插件

::: warning 预览功能
目前插件可以提供**技能**、**子代理**、**工作区工具**、**设置页入口（HTML 页面）**、**AI 工具**、**MCP 服务器**和**只读的生命周期 hooks**（观察每轮运行开始与结束），只能从本地 `.zip` 导入。提供商、代审核、在线分发等还没有实现，写进清单会被拒绝安装。hooks 是第一版，只接收运行元数据，看不到消息正文，也不能改动请求或替你批准工具。AI 工具与 hooks 的业务逻辑在独立的 JavaScript 沙箱中执行；设置页页面在 WebView 里运行，通过宿主注入的 `aicode` 接口读写文件与提示；MCP 服务器复用现有的 HTTP/stdio 连接链路。使用方法见[插件](/guide/plugins)。
:::

一个插件就是一个 `.zip` 包，根目录放一份 `plugin.json` 说明包里有什么，其余是 Markdown、页面 JSON、工具 schema，以及可选的 `dist/main.js` 业务入口。

## 目录结构

```text
demo-kit/
├── plugin.json
├── dist/
│   └── main.js
├── schemas/
│   └── echo.input.json
├── mcp/
│   └── project-context.json
├── skills/
│   └── project-notes/
│       └── SKILL.md
├── agents/
│   └── builder.md
└── ui/
    └── memory.html
```

没有 `runtime` 的插件是纯声明式包，可以只放技能、子代理和工作区工具页面。

`plugin.json` 必须直接位于压缩包根部，不能再套一层 `demo-kit/` 目录。

## plugin.json

```json
{
  "manifestVersion": 1,
  "id": "community.demo-kit",
  "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
  "version": "1.0.0",
  "publisher": "community",
  "repository": "https://github.com/example/demo-kit",
  "description": "为项目笔记和构建任务提供技能与子代理",
  "license": "MIT",
  "engines": {"pluginApi": "^1.0.0"},
  "permissions": [
    {
      "id": "skills.register",
      "required": true,
      "reason": "提供项目笔记技能",
      "scope": {"ids": ["project-notes"]}
    },
    {
      "id": "agents.register",
      "required": false,
      "reason": {"default": "Provide a build sub-agent", "zh": "提供构建子代理"},
      "scope": {"ids": ["builder"]}
    }
  ],
  "contributes": {
    "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
    "agents": [{"id": "builder", "file": "agents/builder.md"}]
  }
}
```

### 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `manifestVersion` | 是 | 固定为 `1` |
| `id` | 是 | `发布者.插件名`，两段都以小写字母开头，只含小写字母、数字、连字符，如 `community.demo-kit` |
| `publisher` | 是 | 必须等于 `id` 的第一段 |
| `name` / `description` | 是 | 展示给使用者的文字，可写成字符串，或按语言写成对象（见下） |
| `version` | 是 | 语义化版本，如 `1.0.0`、`1.1.0-beta.1` |
| `repository` | 是 | 你的源码仓库，必须是 `https://` 地址，不能带账号、`?` 或 `#`。AiCode 只展示它，**不核验** |
| `license` | 是 | 许可证名称，如 `MIT` |
| `engines.pluginApi` | 是 | 需要的插件接口版本范围，目前宿主提供 `1.1.0`；设置页入口需要 `>=1.1.0 <2.0.0`，旧插件写 `^1.0.0` 仍然可用 |
| `permissions` | 是 | 要向使用者申请的权限，见下 |
| `contributes` | 是 | 提供的内容，至少一项 |

不认识的字段一律拒绝安装，而不是忽略——所以不要写 `official`、`permissionReviewer` 之类目前不支持的字段。

### 多语言文字

`name`、`description` 和权限的 `reason` 都可以写成按语言区分的对象：

```json
"name": {"default": "Demo Kit", "zh-CN": "演示套件"}
```

AiCode 按「完整语言标签（`zh-CN`）→ 语言（`zh`）→ `default` → `en` → 第一项」的顺序挑选。语言标签写成 `zh`、`zh-CN`、`en` 这类即可，大小写和 `-`/`_` 不敏感。`name` 不能换行，`description` 可以。

### 版本范围写法

`engines.pluginApi` 支持 `^1.0.0`、`~1.0.0`、`>=1.0.0 <2.0.0`、精确版本 `1.0.0`。不支持 `1.x`、`*`、`||`、`1.0.0 - 2.0.0`。

## 权限与内容必须一一对应

每一类内容都要有对应的注册权限，`scope.ids` 要和 `contributes` 里该类的 `id` 完全一致——多一个少一个都会被拒绝：

| 内容 | 权限 | `scope` |
| --- | --- | --- |
| 技能 `contributes.skills` | `skills.register` | `{"ids": [技能 id…]}` |
| 子代理 `contributes.agents` | `agents.register` | `{"ids": [子代理 id…]}` |
| 工作区工具 `contributes.workspaceTools` | `workspace.tools` | `{"ids": [工具 id…]}` |
| 设置页入口 `contributes.settingsPages` | `settings.pages` | `{"ids": [页面 id…]}` |
| AI 工具 `contributes.tools` | `tools.register` | `{"ids": [工具 id…]}` |
| MCP 服务器 `contributes.mcp` | `mcp.register` + `mcp.connect` | `{"ids": [MCP id…]}`，两项权限覆盖同一组 id |
| 生命周期 hooks `contributes.hooks` | `agent.events` | `{"ids": [hook id…], "events": [事件名…]}`，`ids` 覆盖全部 hook，`events` 覆盖 hooks 用到的事件 |

- `required: true`：使用者不同意就装不了，也无法在装好后正常工作；
- `required: false`：使用者可以不开，不开时对应的内容不生效，插件的其余部分照常。**可选权限默认是关闭的**，所以别把核心功能放在可选权限后面。
- 内容的 `id` 以字母开头，只含字母、数字、下划线、连字符。

## 技能文件

和[技能](/guide/skills)里的 `SKILL.md` 一样：顶部 YAML，正文是指令。

```markdown
---
description: 记录项目笔记；当用户要求总结或归档当前讨论时使用
---
把本次讨论的结论整理成要点，写入 `NOTES.md`……
```

- 技能的名字由 AiCode 生成为 `插件ID:内容id`（如 `community.demo-kit:project-notes`），文件里的 `name` 会被忽略；
- `description` 决定 AI 什么时候加载这个技能，会被压成一行写进系统提示词，请写清「何时使用」；
- 正文不能为空；
- 技能不能依赖同目录的脚本：插件包不会被放进容器，AI 也不会在容器里找到它。需要脚本时请把做法写进正文，让 AI 当场执行。

## 子代理文件

格式与[子代理](/guide/subagent)的定义文件相同：YAML 配置加正文提示词。可用的配置有 `description`、`mode`、`tools`、`disallowedTools`、`inject`、`reasoningEffort`。有两点不同：

- **不能写 `provider` 和 `model`**。它们会动用使用者的付费渠道，插件子代理一律沿用主会话的模型。写了会被拒绝安装；
- `mode` 只能收紧（如 `plan`），不能放大超过主会话。

子代理同样由 AiCode 命名为 `插件ID:内容id`，派发时用这个完整名字。

## MCP 服务器

插件可以用现有 `mcp.json` 格式提供 MCP server，文件放在包里：

```json
{
  "mcpServers": {
    "project-context": {
      "url": "https://example.com/mcp",
      "headers": {"Authorization": "Bearer …"}
    }
  }
}
```

清单声明方式和权限：

```json
{
  "permissions": [
    {"id": "mcp.register", "required": true, "reason": "注册项目上下文服务", "scope": {"ids": ["project-context"]}},
    {"id": "mcp.connect", "required": true, "reason": "连接项目上下文服务", "scope": {"ids": ["project-context"]}}
  ],
  "contributes": {
    "mcp": [{"id": "project-context", "file": "mcp/project-context.json"}]
  }
}
```

- 支持 HTTP 和 stdio；stdio 会在当前工作区的容器或 SSH 后端启动进程。
- 注册与连接是两项权限，只有都授予后 server 才会连接；安装预览会显示 MCP 名称和连接目标。
- 插件贡献的 server 名会加 `plugin__` 命名空间，不会覆盖你在「设置 → MCP」里手工创建的配置；同名冲突时用户配置优先。
- 插件停用、撤权或卸载时，对应的 MCP 连接和工具会一起移除。
- stdio 配置里的 `command`、`args`、`env` 会原样用于启动进程；只安装你信任的插件。

## AI 工具与运行时

AI 工具需要 `runtime` 和 `tools.register`，包内放一个已打包的 JavaScript 入口。AiCode 在独立的 JavaScript 沙箱里加载入口，通过 `globalThis.aicode.handlers` 调用工具；沙箱没有 Android Context、文件或网络接口。

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "permissions": [
    {"id": "tools.register", "required": true, "reason": "提供回显工具", "scope": {"ids": ["echo"]}}
  ],
  "contributes": {
    "tools": [
      {
        "id": "echo",
        "title": "回显",
        "description": "把传入的文本原样回显",
        "inputSchema": "schemas/echo.input.json",
        "handler": "tools.echo"
      }
    ]
  }
}
```

`schemas/echo.input.json` 必须是顶层 `type: object` 的 JSON Schema。`dist/main.js` 导出 handler 表：

```js
globalThis.aicode = {
  handlers: {
    "tools.echo": (args) => ({ text: String(args.text ?? "") })
  }
};
```

- handler 可以返回字符串、数字、布尔、数组或对象；抛出的异常会作为工具错误返回给 AI；
- 支持异步 handler 取决于设备 WebView 是否支持 Promise 返回；不支持时同步 handler 仍可用；
- 工具对 AI 暴露的名字由宿主生成，形如 `plugin__community_demo-kit__echo`，不会和内置工具重名；
- 每次调用仍走工具授权，PLAN 模式下按外部工具拦截；
- 沙箱是弱隔离：设备不支持 JavaScriptEngine 时工具不会注册；运行超时或沙箱崩溃会返回错误，不能保证强制终止所有死循环。

## 生命周期 hooks

hooks 让插件在 Agent 运行的关键节点收到只读通知，用来记录日志或统计。当前只支持**只读观察**，事件有七个：

| 事件 | 时机 |
| --- | --- |
| `agent.run.started` | 一轮对话开始处理时 |
| `agent.run.finished` | 一轮对话结束时；成功、失败或被取消都会发出 |
| `agent.request.started` | 一次模型请求即将发出时 |
| `agent.request.finished` | 一次模型响应结束；成功、失败或取消都会发出 |
| `agent.tool.started` | 一次工具调用真正开始执行时 |
| `agent.tool.finished` | 一次工具调用结束；成功或失败 |
| `agent.compaction` | 上下文被压缩时 |

hooks 需要 `runtime` 和 `agent.events` 权限，`handler` 写 `dist/main.js` 里 `globalThis.aicode.handlers` 的键：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "permissions": [
    {
      "id": "agent.events",
      "required": false,
      "reason": "记录每轮运行、请求与工具状态",
      "scope": {
        "ids": ["run-started", "run-finished", "tool-finished"],
        "events": ["agent.run.started", "agent.run.finished", "agent.tool.finished"]
      }
    }
  ],
  "contributes": {
    "hooks": [
      {"id": "run-started", "event": "agent.run.started", "handler": "hooks.runStarted"},
      {"id": "run-finished", "event": "agent.run.finished", "handler": "hooks.runFinished"},
      {"id": "tool-finished", "event": "agent.tool.finished", "handler": "hooks.toolFinished"}
    ]
  }
}
```

```js
globalThis.aicode = {
  handlers: {
    "hooks.runStarted": (payload) => ({ logs: [`run started ${payload.runId} mode=${payload.mode}`] }),
    "hooks.runFinished": (payload) => {
      const line = `run finished ${payload.status} toolCalls=${payload.toolCalls}`;
      return { logs: [line] };
    },
    "hooks.toolFinished": (payload) => ({ logs: [`tool ${payload.toolName} ${payload.status} ${payload.durationMs}ms`] })
  }
};
```

`scope.ids` 要覆盖全部 hook 的 `id`，`scope.events` 要覆盖 hooks 用到的事件集合，两项都不能多写或漏写；只声明事件而没有对应 hook、或只写 hooks 而不申请 `agent.events`，都会拒绝安装。

### 事件内容

所有事件都带这些公共字段：

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
| `agent.request.finished` | `requestId`、`status`（`success`/`error`/`cancelled`）、`inputTokens`、`outputTokens`、`toolCalls`、`durationMs`、`stopReason`、`errorCode` |
| `agent.tool.started` | `callId`、`toolName` |
| `agent.tool.finished` | `callId`、`toolName`、`status`（`success`/`error`）、`durationMs` |
| `agent.compaction` | `compacted=true`、`estimatedInputTokens` |

payload 里**没有**消息正文、工具参数、工具结果和模型输出，插件拿不到你在对话里说了什么，也看不到工具改了什么。

### 调试输出

handler 可以返回 `{ logs: ["..."] }`，宿主把每行写进应用日志（最多 50 行、单行 500 字符），其余返回值被忽略。这是 hooks 唯一的输出通道，没有文件、网络或 Android 接口。

### 边界

- 宿主异步派发，hook 不进入 Agent 主流程：卡住或抛错只记日志，不阻塞这一轮对话，也不改变运行结果；
- **只读**：不能修改请求、消息、工具参数或响应，不能自动批准任何工具，也不能影响工具授权与模式限制；
- 本切片没有 `transform`：能改写内容的 hooks 属于后续能力，写进清单会被拒绝安装；
- hook 与 AI 工具共用插件的 JavaScript 沙箱，同样没有 Android Context、文件或网络接口，设备不支持 JavaScriptEngine 时 hooks 不会触发；
- 单项调用超时 30 秒，超时会关闭该插件的运行时；连续失败目前不会自动停用插件。

## 工作区工具页面

工作区工具通过 `workspace.tools` 和 `contributes.workspaceTools` 声明，`pageFile` 指向包内 AdaptiveCard JSON。用户可以在右侧项目工具抽屉里添加并运行；页面里的 `submit` 按钮把 `commandTemplate` 插值后在终端标签执行。命令模板属于可执行内容，安装预览会显示工具条目，但最终命令仍需用户点击运行。

运行后宿主把终端原始输出清洗成日志回填到详情页的运行面板（最多保留最后 500 行），并从命令结束时打印的 `[command exited: N]` 标记识别退出码；非零退出显示为失败，不会误报成功。命令在保活标签里执行，结束后 shell 仍可继续使用，面板停止接收后续输出。

## 设置页入口与 HTML 面板

插件可以在设置页注册页面入口，用 HTML 写自己的界面。需要 `settings.pages` 权限和 `contributes.settingsPages`：

```json
{
  "permissions": [
    {
      "id": "settings.pages",
      "required": true,
      "reason": "在设置中提供记忆管理页面",
      "scope": { "ids": ["memory"] }
    }
  ],
  "contributes": {
    "settingsPages": [
      {
        "id": "memory",
        "title": { "zh-CN": "记忆管理", "en": "Memory" },
        "anchor": "settings.ai",
        "page": "ui/memory.html",
        "icon": "book"
      }
    ]
  }
}
```

`anchor` 只能取下列值，宿主按它把入口放进对应的设置分组：

| 值 | 分组 |
| --- | --- |
| `settings.general` | 通用 |
| `settings.ai` | AI 配置 |
| `settings.environment` | 运行环境 |
| `settings.permissions` | 权限与后台 |
| `settings.data` | 数据与诊断 |
| `settings.help` | 帮助与关于 |

`page` 必须是包内的 `.html` / `.htm` 文件，最大 512 KiB。安装后设置页对应分组末尾会出现入口，副标题标明插件来源；停用或卸载插件后入口消失。

### 页面里的宿主接口

宿主向页面注入 `window.aicode`，所有方法返回 Promise：

| 方法 | 作用 |
| --- | --- |
| `aicode.context()` | 插件 id、页面 id、版本、语言、主题、工作区名 |
| `aicode.fs.read(path)` | 读取文件，返回 `{ content, size }` |
| `aicode.fs.write(path, content)` | 写入文件（原子写） |
| `aicode.fs.list(path)` | 列出目录，返回 `{ entries: [{ name, isDir, size }] }` |
| `aicode.fs.delete(path)` | 删除文件或目录 |
| `aicode.fs.stat(path)` | 返回 `{ exists, isDir, size }` |
| `aicode.toast(message)` | 轻提示 |
| `aicode.ui.close()` | 退出插件页，回到设置页（App 级返回） |
| `aicode.ui.onBack(handler)` | 接管页面内返回；`handler` 同步返回 `true` 表示已处理 |

返回键按这个顺序处理：WebView 自身历史（如 hash 路由）→ `onBack` 注册的处理器 → 退出插件页。页面有多层视图时用 `onBack` 接管返回，回到最外层时返回 `false`，交给宿主退出；顶栏自绘的返回按钮在首页调用 `aicode.ui.close()`，在子页调用自己的路由返回。

路径必须以虚拟根开头：

| 根 | 位置 | 读写 |
| --- | --- | --- |
| `data://` | 插件自己的数据目录，卸载时清理 | 读写 |
| `plugin://` | 插件安装目录 | 只读 |
| `workspace://` | 当前工作区 | 读写 |
| `aicode://` | 全局 `.aicode` 目录 | 读写 |

单次读取上限 512 KiB，写入上限 1 MiB，目录最多返回 500 条。

### 主题与样式

页面引用宿主动态生成的 `/__aicode/theme.css`，它提供跟随 App 主题的 CSS 变量：

```html
<link rel="stylesheet" href="/__aicode/theme.css">
<script src="/__aicode/aicode.js"></script>
```

可用变量包括 `--aicode-bg`、`--aicode-surface`、`--aicode-on-surface`、`--aicode-primary`、`--aicode-error`、`--aicode-success`、`--aicode-warning`、`--aicode-radius-*`、`--aicode-space-*`、`--aicode-font-scale`。深浅色切换时宿主直接更新变量，页面不会重新加载。

### 限制

- 页面资源只能从插件包经虚拟域加载，外部链接会被宿主拦截；
- 页面里的 JavaScript 与插件 AI 工具的沙箱是两套环境：页面有 DOM 与桥，AI 工具 handler 没有 DOM，两边不能直接互相调用；
- 宿主只对已启用且授予 `settings.pages` 的插件注入桥；插件停用后入口消失，页面请求会被拒绝；
- 设置页入口最多按声明顺序展示，超出宿主限制的入口会被忽略。

## 打包

在目录里执行（注意最后的 `.`，确保 `plugin.json` 在压缩包根部）：

```bash
cd demo-kit
zip -r ../demo-kit-1.0.0.zip .
```

容器里没有 `zip` 时可以先 `apk add zip`，或用 Python：

```bash
python3 -c "import shutil; shutil.make_archive('../demo-kit-1.0.0', 'zip', '.')"
```

也可以让 AI 在容器里帮你写目录和文件并打包；**导入和授权始终要你自己在「设置 → 插件」里确认**，AI 不能替你安装。

## 安全限制

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB（技能 / 子代理的 Markdown 文件 512 KiB，`plugin.json` 256 KiB，运行时入口 512 KiB，工具 schema / MCP 配置 256 KiB） |
| 解压后总大小 | 32 MiB |
| 路径 | 不能是绝对路径，不能含 `..`、`.`、空段或反斜杠，长度 ≤ 200，层级 ≤ 8 |
| 重复文件 | 不允许，包括只有大小写不同的 |

超出任何一项都会拒绝整个包。

## 导入失败时的排查

| 提示 | 常见原因 |
| --- | --- |
| 压缩包根目录里没有 plugin.json | 打包时多套了一层目录 |
| plugin.json 缺少 / 无效字段 | 提示里会写出具体路径，如 `permissions[0].scope.ids` |
| 权限范围与提供的内容不一致 | `scope.ids` 与 `contributes` 的 id 不是同一组 |
| 此版本不支持的字段 / 内容类型 / 权限 | 用了目前未实现的能力，如 `providers`、`workspace.commands`、`permissionReviewer` |
| `agent.events` 权限范围与 hooks 不一致 | `scope.ids` 要覆盖所有 hook 的 `id`，`scope.events` 要覆盖 hooks 使用的事件；多写没声明的 id 或事件同样会被拒绝 |
| hooks 声明了 handler 却没有运行时 | 带 handler 的贡献都需要 `runtime`，在清单里补上 `dist/main.js` 入口 |
| `settingsPages` 的 `anchor` 无效 | 只能取 `settings.general` / `settings.ai` / `settings.environment` / `settings.permissions` / `settings.data` / `settings.help` |
| `settingsPages` 的 `page` 不是 HTML | `page` 必须指向包内的 `.html` 或 `.htm` 文件 |
| `settings.pages` 范围与页面不一致 | `scope.ids` 要覆盖全部 `settingsPages` 的 id，多写没声明的 id 也会被拒绝 |
| 内容无效 | 技能或子代理正文为空、子代理写了 `provider` / `model`、AI 工具缺少运行时入口或 schema 不是 `type: object`、MCP 文件没有有效 server |
| 清单引用的文件不存在 | `file` 路径与压缩包里的实际路径不符，注意大小写 |
