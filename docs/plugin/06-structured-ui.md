# 插件页面：HTML 与宿主桥

状态：已定，2026-11-12。通用 UI 唯一形态是 HTML 页面；本文描述当前实现与边界，不再规划声明式 UI 引擎。旧的“结构化声明 + 状态绑定”草案已取消。

## 1. 已定形态

- 插件页面就是包内的 HTML/CSS/JS，宿主用 WebView 加载。
- 宿主提供 `aicode.js` 页面桥、Vue 全局构建与 `aicode-ui-vue.js` 组件库；主题跟随 App 配色。
- 页面类型：`configPage`（插件配置）、`contributes.settingsPages`（设置页入口）、`contributes.workspaceTools`（右抽屉工具）。
- 业务逻辑和状态由插件页面自己维护；宿主不解析 UI 声明，也不代插件重绘页面。
- 不再实施结构化节点、状态绑定、声明式表单/布局引擎。

## 2. 页面桥

页面通过 `__aicodeBridge.postMessage(json)` 发请求，宿主回 `window.__aicodeResolve(id, payload)`；封装在 `aicode.js`。

| 方法 | 说明 |
| --- | --- |
| `context` | `pluginId`、`pageId`、`version`、`languageTag`、`theme`、`workspaceName` |
| `toast` | 宿主 Toast |
| `ui.close` | 关闭页面 |
| `ui.overlay` | 页面内全屏弹层开关与矩形；宿主在背景图对应区域挖洞 |
| `fs.read` / `fs.write` / `fs.list` / `fs.delete` / `fs.stat` | 经 `PluginHostPaths` 读写插件目录、工作区与 `.aicode`；保护目录拒绝 |
| `bash` | 在当前工作区执行一次性同步命令，返回 `{output, exitCode, truncated}`；受超时与长度限制 |
| `terminal.run` | 新建共享终端标签，返回 `{tabId, state}`；由插件显式选择，命令在后台运行 |
| `terminal.poll` | 按 `tabId` 与 `offset` 读取增量输出、状态与退出码；输出有界，超出时 `truncated` 为 true |
| `terminal.stop` | 发送 Ctrl-C；超时仍未退出则关闭标签 |
| `terminal.close` | 关闭标签；标签生命周期由插件维护 |
| `plugin.invoke` | 调用同插件常驻进程的 `ui.invoke` handler，单次 60 秒；不能跨插件 |
| `aicode.events.on` | 接收 `plugin.event`；只转发同插件事件 |

插件创建的终端标签默认不在用户终端页显示；在「设置 → 终端设置 → 显示插件运行」开启后才可见。标签归属创建它的插件，其他插件与 AI 的 terminal 工具不能列出或操作。

## 3. 主题与组件

- 宿主注入 CSS 变量（背景、表面、文字、主色、间距、圆角、字号缩放），页面使用这些变量。
- `aicode-ui-vue.js` 提供按钮、输入、开关、列表、卡片、徽章等组件；视觉复用 App 语义色。
- `AicodeMarkdown` 复用宿主内置的 `marked`、`DOMPurify` 与 `highlight.js`，并随主题加载 GitHub Markdown / highlight 样式；插件 WebView 的静态资源统一放在 `assets/plugin-ui/`，经 `/__aicode/` 白名单提供。
- 页面自行处理窄屏/平板、键盘遮挡与长列表滚动；宿主只提供 WebView 容器、标题栏与返回。
- WebView 缓存按插件版本失效；插件更新后不会继续加载旧页面资源。

## 4. 安全边界

- **启用即信任。** 页面桥注入到已启用插件的页面，不按页面逐项授权；安装确认展示插件能力。
- 页面可读写范围由 `PluginHostPaths` 限制：插件安装目录只读、插件数据目录、当前工作区与 `.aicode`；`permissions.json`、`mcp.json`、技能与提示词目录等保护路径始终拒绝。
- `bash` 与 `fs.*` 使用当前工作区；SSH 模式下命令走远端，插件页面本身仍在手机端。
- 命令与 MCP 进程内部不受宿主路径限制；不能宣称“页面在沙箱里”。
- 页面不能通过 UI 文本或样式改变宿主权限、伪造授权界面或访问其他插件。

## 5. 生命周期与数据

- 配置页数据由插件自己保存（通常写插件数据目录或工作区 `.aicode`）；宿主不解析配置字段。
- 页面关闭、插件停用/卸载/更新时，宿主清理 WebView 缓存；已打开页面的在途 `plugin.invoke` 结果不应写入新页面。
- 插件后台进程按版本启动，空闲 10 分钟回收；`resident: true` 常驻。页面调用 `plugin.invoke` 会刷新使用时间。
- 命令任务的生命周期后续由 `terminal.run` 的插件参数维护。

## 6. 验收要点

- 手机与平板、明暗主题、字体缩放、键盘弹出时页面可用。
- 页面只加载包内资源；相对路径不能越出安装目录。
- 桥调用失败有明确错误码；超时、过大参数、非法路径被拒绝。
- 停用/卸载插件后入口消失；已打开页面不能继续调用宿主能力（页面桥需在执行前复核，见 [00-overview.md](00-overview.md) 第 8.4 节）。
