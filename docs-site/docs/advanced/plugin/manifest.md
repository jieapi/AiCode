# plugin.json 清单参考

`plugin.json` 是插件的唯一入口声明。宿主仅加载清单中列出的内容；无法识别的字段和贡献类型将直接拒绝安装，不会静默忽略。

## 顶层字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `manifestVersion` | 是 | 固定为整数 `1` |
| `id` | 是 | 稳定插件标识，格式 `发布者.插件名` |
| `name` | 是 | 展示名称，字符串或按语言的对象，最多 80 字符，不能换行 |
| `version` | 是 | 语义化版本，如 `1.0.0`、`1.1.0-beta.1` |
| `publisher` | 是 | 发布者标识，必须等于 `id` 的第一段 |
| `repository` | 是 | 源码仓库，必须是 `https://` 地址，不能包含账号、查询参数或片段 |
| `description` | 是 | 用途说明，字符串或按语言的对象，最多 600 字符，允许换行 |
| `license` | 是 | 许可证名称，最多 100 字符 |
| `icon` | 否 | 插件列表与详情图标：内置图标名，或包内 `.png` / `.webp` 相对路径 |
| `engines` | 是 | 目前仅接受 `{"pluginApi": "版本范围"}` |
| `runtime` | 有工具或 hook 时必填 | 业务进程的入口与启动方式 |
| `contributes` | 是 | 提供的内容，至少一项 |
| `configPage` | 否 | 插件配置页，包内 HTML 相对路径；也可声明在 `contributes.configPage` 中 |

### 插件图标

`icon` 只接受两种值：

- 内置图标名：小写字母开头，仅含小写字母、数字和连字符，最多 32 个字符，如 `terminal`、`book`、`settings`、`cloud`、`tool`、`monitor`、`package`；未知名称回退 `package`。
- 包内图片：以 `.png` 或 `.webp` 结尾的相对路径，如 `icons/logo.png`。图片必须位于包内，单文件不超过 256 KiB；宿主按最大边 160px 采样解码，无需提供多套尺寸。

不支持 SVG、JPEG 等其它格式，清单里写这些后缀会直接拒绝安装。图片解码失败或未声明 `icon` 时显示宿主默认的 `package` 图标。插件 HTML 页面内部仍可自行使用包内 SVG 图片，那与清单图标无关。

### id 与 publisher

- `id` 形如 `community.demo-kit`：两段均以小写字母开头，仅含小写字母、数字和连字符，每段最多 32 个字符；
- `publisher` 必须等于 `id` 的第一段；
- 插件 ID 同时用作安装目录名，安装后不应依赖重命名区分版本；
- 局部 ID（技能、工具、页面等）由字母开头，仅含字母、数字、下划线、连字符，最多 64 个字符，同一类型内不可重复。

### 多语言文字

`name`、`description` 以及各贡献的 `title`、`description` 均可声明为字符串或语言对象：

```json
{
  "name": {"default": "Demo Kit", "zh-CN": "演示套件"},
  "description": {"default": "Demo plugin.", "zh-CN": "演示插件。"}
}
```

取值顺序为：完整语言标签（`zh-CN`）→ 语言（`zh`）→ `default` → `en` → 第一项。标签大小写和 `-`/`_` 不敏感，最多 16 种语言。

### 版本范围

`engines.pluginApi` 支持 `^1.0.0`、`~1.0.0`、`>=1.0.0 <2.0.0`、精确版本 `1.0.0`。不支持 `1.x`、`*`、`||` 和区间简写。宿主当前提供 `1.0.0`，范围必须包含该版本。

## runtime

```json
{
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "resident": true,
    "dependencies": {"tool": "npm", "file": "package.json"}
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `type` | 是 | `javascript`（用 `node` 启动）或 `python`（用 `python3` 启动） |
| `entry` | 是 | 包内入口文件；JavaScript 用 `.js`/`.mjs`/`.cjs`，Python 用 `.py`，最大 512 KiB |
| `command` | 否 | 覆盖默认启动命令的字符串数组，直接交给 `execve`，不经 shell；最多 16 段，每段最多 200 字符，不得为绝对路径或包含 `..` |
| `dependencies` | 否 | 依赖输入：`tool` 为 `npm` 或 `pip`，`file` 分别为 `.json`（如 `package.json`）或 `.txt`（如 `requirements.txt`） |
| `resident` | 否 | 为 `true` 时进程不参与 10 分钟空闲回收，首次启动后常驻；停用、卸载、版本替换或 App 进程结束仍会关闭 |

存在 `contributes.tools` 或 `contributes.hooks` 时必须提供 `runtime`；纯声明式插件可省略。

## contributes

`contributes` 是一个对象，可用键如下。每类最多 64 项，至少要有其中一项：

| 键 | 类型 | 作用 |
| --- | --- | --- |
| `skills` | 数组 | 技能 Markdown |
| `agents` | 数组 | 子代理定义 |
| `tools` | 数组 | AI 可调用工具 |
| `hooks` | 数组 | 生命周期 hook |
| `mcp` | 数组 | MCP 服务器配置 |
| `providers` | 数组 | OpenAI 兼容提供商 |
| `workspaceTools` | 数组 | 右侧项目工具抽屉中的页面 |
| `settingsPages` | 数组 | 设置页入口 |
| `shizuku` | 对象 | 声明使用 Shizuku |
| `overlay` | 对象 | 声明系统浮层 |
| `configPage` | 字符串 | 插件详情中的配置页 |

### skills / agents

```json
{
  "skills": [{"id": "project-notes", "file": "skills/project-notes/SKILL.md"}],
  "agents": [{"id": "reviewer", "file": "agents/reviewer.md"}]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一类型内唯一 |
| `file` | 是 | 包内 `.md` 相对路径，最大 512 KiB |

技能和子代理的格式见[技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp)。

### tools

```json
{
  "tools": [
    {
      "id": "echo",
      "title": {"default": "Echo", "zh-CN": "回显"},
      "description": {"default": "Echo the text.", "zh-CN": "回显文本。"},
      "inputSchema": "schemas/echo.input.json",
      "handler": "tools.echo"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，决定 AI 工具名的一部分 |
| `title` | 是 | 展示名称，最多 80 字符 |
| `description` | 否 | 供 AI 参考的用途说明，最多 600 字符；省略时为空字符串 |
| `inputSchema` | 是 | 包内 `.json` JSON Schema 文件，最大 256 KiB，根必须是 `{"type": "object"}` |
| `handler` | 是 | 入口脚本 handler 表中的键，如 `tools.echo` |

工具需要 `runtime`。声明不会执行 handler，首次调用时才会启动进程。

### hooks

```json
{
  "hooks": [
    {"id": "run-log", "event": "agent.run.finished", "handler": "hooks.runFinished"},
    {"id": "guard-bash", "event": "agent.tool.before", "handler": "hooks.guardBash", "mode": "transform"},
    {"id": "review", "event": "agent.permission.review", "handler": "hooks.review", "mode": "review"}
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `event` | 是 | 必须是宿主支持的事件，参见[生命周期 Hooks](/advanced/plugin/hooks) |
| `handler` | 是 | 入口脚本 handler 表中的键 |
| `mode` | 否 | `observe`（默认）、`transform`、`review`；可写事件必须显式声明对应模式，声明错误将导致拒绝安装 |

### mcp

```json
{
  "mcp": [{"id": "project-context", "file": "mcp/project-context.json"}]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `file` | 是 | 包内 `.json` 文件，最大 256 KiB，内容为现有 MCP 配置格式 |

### providers

```json
{
  "providers": [
    {
      "id": "gateway",
      "name": {"zh-CN": "本地网关", "en": "Local Gateway"},
      "description": {"zh-CN": "本机 OpenAI 兼容服务"},
      "format": "openai",
      "baseUrl": "http://127.0.0.1:18080/v1"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `name` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `format` | 是 | 目前仅支持 `openai` |
| `baseUrl` | 是 | `http`/`https` 绝对地址，不能包含账号、查询参数或片段，最长 2048 字符 |

插件负责启动兼容服务并保证宿主可访问。启用插件后，设置 → 提供商中将出现带「插件」标记的条目；停用或卸载将删除该条目。

### workspaceTools

```json
{
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
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `icon` | 否 | 图标名，默认 `play` |
| `category` | 否 | 分类名，默认 `通用` |
| `pageFile` | 是 | 包内 `.html`/`.htm` 页面，最大 512 KiB |

### settingsPages

```json
{
  "settingsPages": [
    {
      "id": "memory",
      "title": {"zh-CN": "记忆管理", "en": "Memory"},
      "anchor": "settings.ai",
      "page": "pages/memory.html",
      "icon": "book"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID |
| `title` | 是 | 本地化名称，最多 80 字符 |
| `anchor` | 是 | 仅可取以下六个分组之一 |
| `page` | 是 | 包内 `.html`/`.htm` 页面，最大 512 KiB |
| `icon` | 否 | 图标名，默认 `book`，最长 32 字符 |

| `anchor` | 设置分组 |
| --- | --- |
| `settings.general` | 通用 |
| `settings.ai` | AI 配置 |
| `settings.environment` | 运行环境 |
| `settings.permissions` | 权限与后台 |
| `settings.data` | 数据与诊断 |
| `settings.help` | 帮助与关于 |

### configPage

```json
{
  "contributes": {
    "configPage": "pages/config.html"
  }
}
```

也可写在顶层：

```json
{
  "configPage": "pages/config.html",
  "contributes": {"skills": [{"id": "hello", "file": "skills/hello/SKILL.md"}]}
}
```

配置页仅出现在「设置 → 插件」的插件详情中，与启停、卸载等原生项通过分段控件切换，不会混入设置主菜单。必须是包内 `.html`/`.htm` 文件。

### shizuku

```json
{
  "contributes": {
    "shizuku": {"purpose": "切换免打扰与查询系统设置"}
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符，展示在授权界面 |

声明不会自动获得授权。使用者需在插件详情中单独开启 Shizuku 权限，且系统中的 Shizuku 服务须正在运行。

### overlay

```json
{
  "contributes": {
    "overlay": {
      "purpose": "悬浮小组件与状态 HUD",
      "surfaces": [
        {"id": "pet", "page": "pages/pet.html", "width": 240, "height": 320, "draggable": true, "snap": "x"}
      ]
    }
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符 |
| `surfaces` | 是 | 至少 1 个、最多 3 个浮层页面 |

每个 surface：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一插件内唯一 |
| `page` | 是 | 包内 `.html`/`.htm` 页面 |
| `width` | 否 | 默认宽度，单位 dp，默认 240，范围 48–720 |
| `height` | 否 | 默认高度，单位 dp，默认 320，范围 48–1200 |
| `draggable` | 否 | 是否允许拖动，默认 `true` |
| `snap` | 否 | 松手吸附轴：`none`、`x`、`y`、`both`，默认 `none` |

浮层需要使用者单独授权，且 App 需获得「显示在其他应用上层」的系统权限。

## 完整示例

下面的清单同时声明了技能、子代理、工具、hook、MCP、工作区工具、设置页、配置页、提供商、Shizuku 和浮层，用于说明各字段的相对位置。实际插件无需全部声明。

```json
{
  "manifestVersion": 1,
  "id": "community.workbench-kit",
  "name": {"default": "Workbench Kit", "zh-CN": "工作台套件"},
  "version": "1.0.0",
  "publisher": "community",
  "repository": "https://github.com/example/workbench-kit",
  "description": {"default": "Project tools and pages.", "zh-CN": "项目工具与页面。"},
  "license": "MIT",
  "engines": {"pluginApi": "^1.0.0"},
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "configPage": "pages/config.html",
  "contributes": {
    "skills": [{"id": "notes", "file": "skills/notes/SKILL.md"}],
    "agents": [{"id": "reviewer", "file": "agents/reviewer.md"}],
    "tools": [
      {
        "id": "project_info",
        "title": "项目信息",
        "description": "查询当前项目状态",
        "inputSchema": "schemas/project-info.input.json",
        "handler": "tools.projectInfo"
      }
    ],
    "hooks": [
      {"id": "run-log", "event": "agent.run.finished", "handler": "hooks.runFinished"}
    ],
    "mcp": [{"id": "context", "file": "mcp/project-context.json"}],
    "workspaceTools": [
      {
        "id": "build",
        "title": "项目操作",
        "description": "构建与清理",
        "pageFile": "pages/build.html"
      }
    ],
    "settingsPages": [
      {
        "id": "settings",
        "title": "工作台设置",
        "anchor": "settings.ai",
        "page": "pages/settings.html",
        "icon": "settings"
      }
    ],
    "providers": [
      {
        "id": "gateway",
        "name": "本地网关",
        "format": "openai",
        "baseUrl": "http://127.0.0.1:18080/v1"
      }
    ],
    "shizuku": {"purpose": "查询系统设置"},
    "overlay": {
      "purpose": "状态悬浮球",
      "surfaces": [{"id": "ball", "page": "pages/ball.html", "width": 48, "height": 48}]
    }
  }
}
```

## 会被拒绝的声明方式

| 声明方式 | 结果 |
| --- | --- |
| 顶层或贡献中出现未支持的字段 | `UNSUPPORTED_FIELD`，提示具体路径 |
| `manifestVersion` 不是 `1` | 拒绝安装 |
| `id` 不是两段、包含大写或下划线 | `FIELD_INVALID` |
| `publisher` 与 `id` 第一段不一致 | `FIELD_INVALID` |
| `repository` 不是 https、包含账号或查询参数 | `FIELD_INVALID` |
| 技能/子代理的 `file` 不是 `.md` | `FIELD_INVALID` |
| 工具 `inputSchema` 不是 `.json` 或根不是 object | `FIELD_INVALID` / `CONTENT_INVALID` |
| 存在 `tools` 或 `hooks` 但未声明 `runtime` | `FIELD_MISSING` |
| hook 的 `event` 不在支持列表，或 `mode` 与事件不匹配 | `FIELD_INVALID` |
| 同一类型内 `id` 重复 | `DUPLICATE_ID` |
| `contributes` 为空或仅包含未支持的类型 | `FIELD_INVALID` |

更完整的导入报错排查参见[打包、导入与排错](/advanced/plugin/packaging)。
