# 示例库

仓库的 `plugin-samples/` 目录提供一组可直接侧载的完整插件。每个示例仅演示一至两项能力，可先运行再修改。

## 运行示例

1. 打开「设置 → 插件」，点击右上角设置按钮并开启「开发者侧载」；
2. 将示例目录复制到容器的 `~/.aicode/plugin-dev/<项目名>/`；
3. 在弹层中点击「安装」或「重载」；
4. 修改文件并保存后将自动重载。

也可将目录打包为 `.zip`，并按[快速开始](/advanced/plugin/quick-start)的方式导入。

## 示例索引

| 示例 | 插件 ID | 演示能力 | 建议阅读 |
| --- | --- | --- | --- |
| `community.demo-kit` | `community.demo-kit` | 技能、子代理、AI 工具、工作区工具、只读与可写 hook、配置页 | 综合入门 |
| `capability-demo` | `demo.capabilities` | 对话提示条、Shizuku 授权、会话/消息 API、配置页 | Shizuku、`host.tip` 与 `aicode.chat` |
| `chat-bridge` | `demo.chat-bridge` | 设置页对话界面、会话长按动作、`aicode.chat`、`chat.run.finished` 完成通知 | 会话与消息 API |
| `team-chat` | `demo.team-chat` | 设置页多模型群聊、模型句柄流式轮流请求、工具调用与审批、图片输入 | 模型句柄 |
| `backup-helper` | `jieapi.backup-helper` | 把记忆、技能、MCP、子代理写入宿主备份包；`backup.data` 数据选项、备份与还原事件、独立授权 | 备份事件与授权 |
| `dev-tools` | `community.dev-tools` | 两个工作区工具页面，调用 `aicode.bash` | 工作区工具 |
| `ui-gallery` | `demo.gallery` | 设置页、侧栏与顶栏槽位入口、配置页、宿主 Vue 组件库 | 页面组件 |
| `memory-manager` | `demo.memory` | 设置页读写全局与项目记忆、文件订阅、返回键 | 设置页与文件订阅 |
| `background-approval` | `demo.background-approval` | 后台审批浮层、代审核 hook、`app.isForeground` | 代审核 |
| `provider-demo` | `demo.provider` | OpenAI 兼容提供商、常驻进程、`app.started` | 提供商 |
| `ssh-manager` | `demo.ssh-manager` | 常驻进程、npm 依赖、设置页、三个 AI 工具 | 常驻与依赖 |

## community.demo-kit

一个包同时包含技能、子代理、AI 工具、工作区工具、只读与可写 hook 和配置页，适合首次通读。

- 技能 `skills/project-notes/SKILL.md`：将讨论结论追加到 `NOTES.md`；
- 子代理 `agents/reviewer.md`：`mode: plan`、只读工具、`inject: [base, projectRules]`；
- AI 工具：`echo` 回显、`last-run` 读取 hook 保存的元数据、`notify` 调用 `host.notify`；
- 工作区工具 `project-snapshot`：在终端标签中查看分支、改动和文件列表；
- 只读 hook `agent.run.finished`：将运行元数据存入进程变量；
- 可写 hook `agent.tool.before`：`mode: "transform"`，检查工具参数中的路径字段与命令字段，命中 `.env` 或 `.env.*` 时返回 `decision: "deny"`，并用 `logs` 记录被拦截的工具；
- 配置页 `pages/config.html`。

`.env` 拦截只检查路径与命令字段，不扫描 `content` / `edits`，避免正常代码提及 `.env` 时被误拦；实际策略应按项目需要调整。

`tools.echo` 同时演示三项操作：返回结构化结果、写入 `data://echo.log`、发送 `plugin.event`：

```js
'tools.echo': (args) => {
  const text = String(args.text ?? '');
  void hostCall('host.fs.write', {path: 'data://echo.log', content: text + '\n'}).catch(() => {});
  send({
    jsonrpc: '2.0',
    method: 'plugin.event',
    params: {name: 'echo.done', payload: {length: text.length}}
  });
  return {text, length: text.length, plugin: 'community.demo-kit', runtime: 'process'};
}
```

## capability-demo

演示插件级 Shizuku 授权、对话提示条、输入框写入 API 与文件/选区动作入口。

- `show-tip` 工具调用 `host.tip`，在对话中显示一条可关闭提示；
- `shizuku-command` 工具通过 `host.shizuku.exec` 执行只读 `settings` 命令；
- 清单声明 `contributes.shizuku.purpose`，使用者需在插件详情中单独开启权限；
- `chat.welcome` 动作「填入演示提示词」调用 `host.chat.setDraft`，把文本写入当前输入框；
- `files.tree.menu` 动作「查看文件信息」根据长按路径调用 `host.fs.stat`；
- `editor.selection.menu` 动作「解释选中代码」把选中文本交给 `host.chat.setDraft`；
- 配置页可直接触发提示、Shizuku 与「创建会话并发送」，后者依次调用 `aicode.chat.createSession`、`openSession` 和 `sendMessage`，便于验证会话 API。

## chat-bridge

演示给 AI 下达指令并读取回复的网页对话界面，可作为 QQ / 微信机器人接入的消息回路示例。

## team-chat

用模型句柄实现的多人多模型群聊：两个成员各自选择供应商、模型与提示词，在同一段群聊历史里轮流发言。

- `contributes.modelAccess` 声明模型访问用途，安装预览会提示请求计入供应商用量；
- 页面调用 `aicode.model.listProviders` / `listModels` 加载可选项，`create` 为每个成员建句柄；
- 发送前把相邻同角色消息合并，避免多个成员连续发言时出现连续的 assistant 消息；
- 发送时按成员顺序调用 `aicode.model.stream`，把前一个成员的回复写回群聊历史，再交给下一个成员；
- 每个成员都带 `team_vote` 自定义工具（插件本地生成投票结果）与 `writeFile` 宿主工具：模型返回 `toolCalls` 后，页面追加 assistant 与 tool 消息再请求；`writeFile` 经 `aicode.tools.execute` 交给宿主，ASK 时轮询 `aicode.tools.pending` 显示审批条，按钮调用 `aicode.tools.resolve`；
- 支持附带图片：文件选择后经 `FileReader` 读成 base64，随 user 消息的 `images` 传入；`final.images` 以 data URL 显示；
- 页面关闭时宿主会自动取消该页面发起的流，句柄由插件停用或显式 `close` 释放。

## backup-helper

真实备份扩展数据的插件：把全局与当前工作区的记忆、技能、MCP 配置、子代理写入**宿主备份包**，并在导入还原后写回。

- `contributes.backup` 声明用途；使用者需在插件详情「权限」中开启「备份与还原事件」；
- `backup.data` 数据选项出现在备份页「选择导出数据」组：打开后，导出宿主备份时插件会参与；
- `backup.export.before` 时读取 `~/.aicode/{memory,skills,agents,mcp.json}` 与 `<workspace>/.aicode/{memory,skills,agents,mcp.json}`，写入 `data://backup-export`；宿主把该目录打包为归档内的 `plugins/jieapi.backup-helper/` 区段；
- `backup.import.finished` 时从宿主解开的 `data://backup-import` 写回真实路径；若宿主本次已恢复 MCP（`stats.mcpServers > 0`），跳过区段里的 `mcp.json`，避免覆盖宿主备份；
- 单文件超过 512 KiB、目录读取失败或超过 1500 个文件会跳过并记录在区段 manifest；
- 导出/导入暂存目录由宿主在操作结束后清理。

- 在设置 → AI 中打开「对话桥」设置页；首次打开创建会话，之后复用 `data://chat-bridge.json` 里的会话 id；
- 页面调用 `aicode.chat.createSession`、`sendMessage`、`listMessages`、`stop`，并在当前工作区范围内读写消息；
- 页面监听宿主的 `chat.run.started` / `chat.run.finished` 事件，收到完成后拉取最终 assistant 文本；同时保留轮询兜底；
- 同时声明 `agent.permission.review` 代审核 hook：AI 遇到需要确认的工具时，页面弹出「允许本次 / 拒绝」卡片，通过 `aicode.invoke('ui.permissionDecision')` 回传决定；4.5 分钟未处理会返回 `pass`，交回宿主自己的确认 UI；
- 页面提供复制回复按钮，方便把结果回传到外部机器人。真实 QQ / 微信接入需要把同样的「收到消息 → sendMessage → 等完成 → 读取回复」回路放到常驻进程或自己的服务里；
- 同时演示 `chat.session.menu` 动作入口：长按会话选「会话动作示例」，handler 返回 `{message}`，宿主用 toast 显示。

## dev-tools

纯页面插件，无需 runtime。两个工作区工具：

- `build-apk`：识别 Flutter/Android 项目，执行构建；
- `clean-cache`：查看构建缓存占用并清理。

页面使用 `aicode.bash` 进行项目类型检测和短命令，并以较长超时执行构建。命令与文件操作仅在用户点击按钮后发生。

## ui-gallery

演示槽位入口和插件配置页：

- `uiEntries` 将「组件演示」挂载到 `settings.help`、侧栏底部与聊天顶栏菜单；
- `configPage` 演示配置读取、保存、重置；
- 页面展示宿主 Vue 组件库的输入框、文本域、对话框和底部弹层。

配置持久化使用 `data://config.json`：

```js
const result = await aicode.fs.read('data://config.json');
await aicode.fs.write('data://config.json', JSON.stringify(config, null, 2));
```

## memory-manager

设置页中的记忆管理器：

- 读写全局 `$HOME/.aicode/memory/` 与工作区内的 `.aicode/memory/`；
- 使用 `aicode.fs.watch` 监听记忆目录变化；
- 使用 `aicode.context()` 的 `contextKey` 拦截旧项目操作；
- 使用 `aicode.ui.onBack` 接管页面返回；
- 使用 `aicode.ui.close()` 退出插件页。

可作为「设置页、文件读写与订阅刷新」组合的参考。

## background-approval

后台审批场景：

- `app.started` 时创建 `visibilityPolicy: "background"` 的悬浮球；
- `agent.permission.review` 收到审批请求后展开面板，用户选择同意或拒绝；
- App 在前台时直接返回 `pass`，交回应用内确认框；
- 使用 `host.app.isForeground` 判断前后台，避免审批持续至超时。

## provider-demo

将插件内置的 OpenAI 兼容回声服务注册为提供商：

- `providers` 声明 `baseUrl: http://127.0.0.1:18080/v1`；
- `resident: true` 通过 `app.started` hook 自动启动服务；
- `start` 工具用于安装后不重启 App 时手动启动；
- 配置页显示服务状态。

启用后前往「设置 → 提供商」，找到带「插件」标记的条目，拉取模型后即可对话。

## ssh-manager

常驻进程与依赖准备的示例：

- `runtime.resident: true`，`dependencies` 指向 `package.json`；
- `app.started` 时连接已启用的 SSH 服务器；
- 设置页管理服务器配置；
- 三个 AI 工具：服务器列表、状态检查、远程执行。

依赖在插件详情页点击「准备依赖」安装，不随包提交 `node_modules`。

## 从示例创建自有插件

1. 复制最接近的示例目录，修改 `plugin.json` 的 `id`、`publisher`、`name`、`version`；
2. 保留需要的 `contributes`，删除不需要的贡献和对应文件；
3. 先通过侧载确认可安装、可启用；
4. 再逐项修改 handler、页面与 schema；
5. 最后按[打包、导入与排错](/advanced/plugin/packaging)检查并构建正式包。
