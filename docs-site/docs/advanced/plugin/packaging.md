# 打包、导入与排错

## 打包

插件的运行时入口由 TypeScript 构建产生时，请先在仓库根目录执行 `npm --prefix plugins run build:plugins`，再打包目录；构建流程参见[用 TypeScript 编写与构建插件](/advanced/plugin/source-build)。

在插件目录中执行，注意命令末尾的 `.`：

```bash
cd demo-kit
zip -r ../demo-kit-1.0.0.zip .
```

容器中未安装 `zip` 时，可使用 Python：

```bash
python3 -c "import shutil; shutil.make_archive('../demo-kit-1.0.0', 'zip', '.')"
```

打包后请检查压缩包根目录：

```text
demo-kit-1.0.0.zip
├── plugin.json
├── dist/
├── schemas/
└── ...
```

若打开 zip 后看到的是 `demo-kit/plugin.json`，说明嵌套了一层目录，导入时将提示「压缩包根目录里没有 plugin.json」。

建议排除 `node_modules`、`.git`、构建缓存和本地测试数据。依赖应通过 `runtime.dependencies` 声明，由使用者点击「准备依赖」安装，或由作者在发布前 bundle 进入口。

## 安装限制

任意一项超出限制都将拒绝整个包：

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB |
| 解压后总大小 | 32 MiB |
| 路径 | 不能是绝对路径，不能含 `..`、`.`、空段或反斜杠，长度不超过 200，层级不超过 8 |
| 重复文件 | 不允许，包括仅大小写不同的情况 |

单文件上限：

| 内容 | 上限 |
| --- | --- |
| `plugin.json` | 256 KiB |
| 运行时入口 | 512 KiB |
| 技能 / 子代理 Markdown | 512 KiB |
| 页面 HTML | 512 KiB |
| 工具 JSON Schema | 256 KiB |
| MCP 配置 | 256 KiB |

## 导入与更新

打开「设置 → 插件」，点击右上角导入并选择 `.zip`。安装确认页将列出插件提供的能力，确认后启用。

- 同一 `id` 的新版本将覆盖安装，版本号不变时也会替换原目录；
- 重复导入同一包会再次进入预览确认，确认后才执行覆盖安装；
- 停用插件仅关闭其能力，不删除数据；卸载将清理插件目录和 `data://` 数据；
- 插件数据、授权和运行状态由宿主另存，不写回安装包。

## 开发者侧载

反复打包并导入效率较低时，可使用开发者侧载：

1. 在「设置 → 插件」中点击右上角设置按钮，在「插件设置」中开启「开发者侧载」；
2. 将项目目录放入容器的 `~/.aicode/plugin-dev/<项目名>/`，每个目录均须包含 `plugin.json`；
3. 回到插件页，在「开发中」标签页打开项目详情，点击「安装」或「重载」。开启期间保存文件将自动重载，下拉该列表可重新扫描目录。

侧载不参与在线更新，普通 `.zip` 导入仍可使用。侧载打包会跳过 `node_modules`、`.git` 等目录，但最终仍受上述安装限制约束。

远程 SSH 模式下，`~/.aicode/plugin-dev` 位于远端服务器上。

## 导入失败排查

| 提示 | 常见原因 |
| --- | --- |
| 压缩包根目录里没有 plugin.json | 打包时多嵌套了一层目录 |
| plugin.json 缺少 / 无效字段 | 提示中会写出具体路径，请按 [清单参考](/advanced/plugin/manifest) 对照 |
| 此版本不支持的字段 / 内容类型 | 使用了当前版本未实现的能力；未知字段不会被忽略 |
| hooks 或 uiEntries 声明了 handler 但未声明运行时 | 带 handler 的贡献均需要 `runtime`，请在清单中补充入口 |
| `uiEntries` 的 `slot` 无效 | 仅可取 `settings.general` / `settings.ai` / `settings.environment` / `settings.permissions` / `settings.data` / `settings.help` / `drawer.footer` / `chat.header.menu` / `chat.welcome` / `chat.session.menu` / `files.tree.menu` / `editor.selection.menu` / `backup.data` |
| `uiEntries` 的 `page` 不是 HTML | `page` 必须指向包内 `.html` 或 `.htm` |
| `uiEntries` 同时声明或缺少入口类型 | `page`、`handler` 必须且只能出现一个 |
| `uiEntries` 在设置页槽位使用 handler | `settings.*` 槽位只接受 `page` 入口 |
| `uiEntries` 在文件树/选区槽位使用 page | `files.tree.menu`、`editor.selection.menu` 只接受 `handler` |
| `backup.data` 写了 page / handler / confirm | `backup.data` 只接受数据选项，由宿主渲染开关 |
| 声明了备份事件但未声明 `contributes.backup` | `backup.export.before` 与 `backup.import.finished` 需要同时声明 `contributes.backup`，否则安装会被拒绝 |
| `uiEntries` 的 `confirm` 没有配 handler | `confirm` 只用于动作入口 |
| `workspaceTools` 的 `pageFile` 不是 HTML | `pageFile` 必须指向包内 `.html` 或 `.htm` |
| 内容无效 | 技能或子代理正文为空、子代理声明了 `provider` / `model`、AI 工具缺少运行时入口、schema 根不是 `type: object`、MCP 文件未包含有效 server |
| 清单引用的文件不存在 | `file` 路径与压缩包中的实际路径不符，请注意大小写 |
| 同一类型内 id 重复 | 技能、工具、页面等在各自类型内不可重名 |
| 路径不安全 | 包内路径含 `..`、绝对路径、反斜杠或层级过深 |
| 文件太大 | 请对照上述单文件上限 |
| 重复文件 | 压缩包中出现同名文件，包括仅大小写不同的情况 |

错误提示中的字段路径（如 `contributes.tools[0].inputSchema`）即清单中的位置，可直接按路径检查。

## 日志

插件进程启动、调用失败、hook 超时和浮层创建失败均会写入应用日志。请打开「设置 → 日志」并搜索插件 ID 或 `Plugin` 关键字。

handler 可返回 `logs` 以主动写入日志：

```js
return {ok: true, logs: ['provider started', 'port=18080']};
```

`logs` 最多 50 行、单行 500 字符。

## 调试建议

- 先使用最小插件验证清单与运行时，再逐项添加能力；
- 工具 handler 可先在容器中通过 `node dist/main.js` 手动运行消息循环，确认能够响应 `tool.invoke`；
- 页面可先在桌面浏览器中调整样式，但桥接口仅能在 App 中使用；
- 侧载时应保持「开发者侧载」开启，保存后将自动重载；
- 修改 `plugin.json` 后必须重载或重新导入；宿主不会监听清单变化；
- 浮层、Shizuku、提供商均依赖额外授权或服务状态；失败时应先查看日志中的错误码。

## 发布前检查

- `plugin.json` 位于压缩包根部；
- `id`、`publisher`、`version` 正确，`repository` 为可访问的 https 地址；
- 所有 `file`、`page`、`pageFile`、`inputSchema` 路径存在且大小写一致；
- 技能与子代理正文非空，子代理未声明 `provider` / `model`；
- 工具 schema 根为 `type: object`；
- 带 handler 的贡献均声明 `runtime`；
- 未将 `node_modules`、密钥、测试数据打入包中；
- 发布到 GitHub Release 时，资产名使用 `{id}_{version}.zip`；
- 在插件索引中更新自己的 `repository` 和 `version`；
- 在真机上完整验证安装、启用、调用、停用与卸载流程。
