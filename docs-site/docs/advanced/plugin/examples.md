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
| `community.demo-kit` | `community.demo-kit` | 技能、子代理、AI 工具、工作区工具、只读 hook、配置页 | 综合入门 |
| `capability-demo` | `demo.capabilities` | 对话提示条、Shizuku 授权、配置页 | Shizuku 与 `host.tip` |
| `dev-tools` | `community.dev-tools` | 两个工作区工具页面，调用 `aicode.bash` | 工作区工具 |
| `ui-gallery` | `demo.gallery` | 设置页入口、配置页、宿主 Vue 组件库 | 页面组件 |
| `memory-manager` | `demo.memory` | 设置页读写全局与项目记忆、文件订阅、返回键 | 设置页与文件订阅 |
| `desktop-pet` | `demo.desktop-pet` | 单浮层 surface、长按展开菜单、拖拽、点击、运行状态 hook | 系统浮层 |
| `background-approval` | `demo.background-approval` | 后台审批浮层、代审核 hook、`app.isForeground` | 代审核 |
| `provider-demo` | `demo.provider` | OpenAI 兼容提供商、常驻进程、`app.started` | 提供商 |
| `env-guard` | `demo.env-guard` | `agent.tool.before` 可写 hook 拒绝 `.env` 访问 | 可写 hook |
| `ssh-manager` | `demo.ssh-manager` | 常驻进程、npm 依赖、设置页、三个 AI 工具 | 常驻与依赖 |
| `runtime-env` | `demo.runtime-env` | 设置页查看运行时版本 | 纯页面插件 |

## community.demo-kit

一个包同时包含技能、子代理、AI 工具、工作区工具、只读 hook 和配置页，适合首次通读。

- 技能 `skills/project-notes/SKILL.md`：将讨论结论追加到 `NOTES.md`；
- 子代理 `agents/reviewer.md`：`mode: plan`、只读工具、`inject: [base, projectRules]`；
- AI 工具：`echo` 回显、`last-run` 读取 hook 保存的元数据、`notify` 调用 `host.notify`；
- 工作区工具 `project-snapshot`：在终端标签中查看分支、改动和文件列表；
- 只读 hook `agent.run.finished`：将运行元数据存入进程变量；
- 配置页 `pages/config.html`。

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

演示插件级 Shizuku 授权和对话提示条。

- `show-tip` 工具调用 `host.tip`，在对话中显示一条可关闭提示；
- `shizuku-command` 工具通过 `host.shizuku.exec` 执行只读 `settings` 命令；
- 清单声明 `contributes.shizuku.purpose`，使用者需在插件详情中单独开启权限；
- 配置页可直接触发，便于验证授权。

## dev-tools

纯页面插件，无需 runtime。两个工作区工具：

- `build-apk`：识别 Flutter/Android 项目，执行构建；
- `clean-cache`：查看构建缓存占用并清理。

页面使用 `aicode.bash` 进行项目类型检测和短命令，并以较长超时执行构建。命令与文件操作仅在用户点击按钮后发生。

## ui-gallery

演示设置页入口和插件配置页：

- `settingsPages` 将「组件演示」挂载到 `settings.help`；
- `configPage` 演示配置读取、保存、重置；
- 页面展示宿主 Vue 组件库的输入框、文本域、对话框和底部弹层。

配置持久化使用 `data://config.json`：

```js
const result = await aicode.fs.read('data://config.json');
await aicode.fs.write('data://config.json', JSON.stringify(config, null, 2));
```

## memory-manager

设置页中的记忆管理器：

- 读写全局 `aicode://memory/` 与工作区 `workspace://.aicode/memory/`；
- 使用 `aicode.fs.watch` 监听记忆目录变化；
- 使用 `aicode.context()` 的 `contextKey` 拦截旧项目操作；
- 使用 `aicode.ui.onBack` 接管页面返回；
- 使用 `aicode.ui.close()` 退出插件页。

可作为「设置页、文件读写与订阅刷新」组合的参考。

## desktop-pet

宿主托管浮层的完整示例：

- 单个 surface：可拖拽的宠物，长按后在下方展开菜单，收起后恢复位置；
- 眨眼、摆耳、摇尾、拖拽回弹与贴边翻身动画，侧躺时适配窗口留白；
- 空闲 3 分钟自动休息，睡眠标志只在休息时出现；睡眠时每次抚摸有 30% 概率醒来，喂食和新任务也会唤醒；
- `app.started` 时按配置创建浮层；
- `agent.run.started`、`agent.tool.started`、`agent.tool.finished`、`agent.run.finished` 驱动表情；
- 浮层页面使用 `overlay.self()`、`setDragRegion`、`onMessage`、`postMessage` 与进程通信。

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

## env-guard

可写 hook 的最小示例：

- 声明 `agent.tool.before`、`mode: "transform"`；
- 检查工具参数中的路径字段和命令字段，命中 `.env` 或 `.env.*` 时返回 `decision: "deny"`；
- 返回 `logs` 记录被拦截的工具。

该示例仅检查路径与命令字段，不扫描 `content` / `edits`，避免正常代码提及 `.env` 时被误拦。实际策略应按项目需要调整。

## ssh-manager

常驻进程与依赖准备的示例：

- `runtime.resident: true`，`dependencies` 指向 `package.json`；
- `app.started` 时连接已启用的 SSH 服务器；
- 设置页管理服务器配置；
- 三个 AI 工具：服务器列表、状态检查、远程执行。

依赖在插件详情页点击「准备依赖」安装，不随包提交 `node_modules`。

## runtime-env

纯设置页插件，展示如何在页面中执行短命令并渲染结果：

- `settingsPages` 挂载到 `settings.environment`；
- 页面使用 `aicode.bash` 查询 Java、Python、Node.js 等版本；
- 不包含 runtime 和 AI 工具。

## 从示例创建自有插件

1. 复制最接近的示例目录，修改 `plugin.json` 的 `id`、`publisher`、`name`、`version`；
2. 保留需要的 `contributes`，删除不需要的贡献和对应文件；
3. 先通过侧载确认可安装、可启用；
4. 再逐项修改 handler、页面与 schema；
5. 最后按[打包、导入与排错](/advanced/plugin/packaging)检查并构建正式包。
