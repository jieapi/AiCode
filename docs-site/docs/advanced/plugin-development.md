# 开发插件

::: warning 预览功能
目前插件可以提供**技能**、**子代理**、**工作区工具**和**AI 工具**，只能从本地 `.zip` 导入。MCP 服务器、hooks、提供商、在线分发等还没有实现，写进清单会被拒绝安装。AI 工具的业务逻辑在独立的 JavaScript 沙箱中执行，不会进入宿主进程。使用方法见[插件](/guide/plugins)。
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
├── skills/
│   └── project-notes/
│       └── SKILL.md
└── agents/
    └── builder.md
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
| `engines.pluginApi` | 是 | 需要的插件接口版本范围，目前宿主提供 `1.0.0`，写 `^1.0.0` |
| `permissions` | 是 | 要向使用者申请的权限，见下 |
| `contributes` | 是 | 提供的内容，至少一项 |

不认识的字段一律拒绝安装，而不是忽略——所以不要写 `runtime`、`official` 之类目前不支持的字段。

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
| AI 工具 `contributes.tools` | `tools.register` | `{"ids": [工具 id…]}` |

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

## 工作区工具页面

工作区工具通过 `workspace.tools` 和 `contributes.workspaceTools` 声明，`pageFile` 指向包内 AdaptiveCard JSON。用户可以在右侧项目工具抽屉里添加并运行；页面里的 `submit` 按钮把 `commandTemplate` 插值后在终端标签执行。命令模板属于可执行内容，安装预览会显示工具条目，但最终命令仍需用户点击运行。

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
| 单个文件 | 4 MiB（技能 / 子代理的 Markdown 文件 512 KiB，`plugin.json` 256 KiB，运行时入口 512 KiB，工具 schema 256 KiB） |
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
| 此版本不支持的字段 / 内容类型 / 权限 | 用了目前未实现的能力，如 `mcp`、`hooks`、`providers`、`workspace.commands` |
| 内容无效 | 技能或子代理正文为空、子代理写了 `provider` / `model`、AI 工具缺少运行时入口或 schema 不是 `type: object` |
| 清单引用的文件不存在 | `file` 路径与压缩包里的实际路径不符，注意大小写 |
