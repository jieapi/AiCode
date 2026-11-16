# 打包、导入与排错

开发期间使用[开发者侧载](/advanced/plugin/quick-start#_3-开发者侧载)，交付时将清单和运行文件打成 `.zip`。源码的构建约定见 [TypeScript + Vue 开发与构建](/advanced/plugin/source-build)。

## 打包

### 独立项目

在插件项目根目录先检查类型并构建，再打包运行文件。以下命令适用于独立模板的默认结构：

```bash
npm run typecheck
npm run build
zip -r ../my-plugin-1.0.0.zip plugin.json dist schemas
```

根据清单补充需要发布的目录与文件：

| 内容 | 是否放入包中 |
| --- | --- |
| `plugin.json`、运行时入口、页面 HTML 与 assets | 必须包含清单引用的文件与其依赖资源 |
| `skills/`、`agents/`、`mcp/`、`schemas/` 等 | 声明了对应能力时包含 |
| `package.json` / 锁文件、`requirements.txt` | 使用 `runtime.dependencies` 时保留 |
| `src/`、开发类型、构建脚本 | 运行时不使用，可留在源码仓库 |
| `node_modules`、虚拟环境、`.git`、缓存、测试数据、密钥 | 排除 |

压缩包根部必须直接包含 `plugin.json`，不能是 `my-plugin/plugin.json`。页面 assets 也要随包发布，不能只放 HTML。

### 主仓库项目

位于 `plugins/samples/` 的项目可在仓库根目录使用打包脚本，`<目录名>` 替换为实际目录名：

```bash
python3 scripts/package-plugins.py <目录名>
```

脚本先构建，再生成 `plugin-packages/<插件ID>-<版本>.zip`；省略目录名会打包全部项目。脚本会跳过 `node_modules`、`.git` 等目录，并检查压缩包大小与文件数量。

## 安装限制

任意一项超出限制都将拒绝整个包，侧载也受这些限制约束：

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB |
| 解压后总大小 | 32 MiB |
| 路径 | 不能是绝对路径，不能含 `..`、`.`、空段或反斜杠，长度不超过 200，层级不超过 8 |
| 重复文件 | 不允许，包括仅大小写不同的情况 |

声明文件另有单文件上限：

| 内容 | 上限 |
| --- | --- |
| `plugin.json` | 256 KiB |
| 运行时入口 | 512 KiB |
| 技能 / 子代理 Markdown | 512 KiB |
| 页面 HTML | 512 KiB |
| 工具 JSON Schema | 256 KiB |
| MCP 配置 | 256 KiB |

## 导入与更新

将 `.zip` 传输到手机，打开「设置 → 插件」，点击右上角导入并选择文件。安装确认页会列出插件提供的能力，确认后启用。

- 同一 `id` 的包会替换原安装目录，版本号相同时也可覆盖；每次导入都要重新确认。
- 停用仅关闭能力；卸载会清理插件目录和 `data://` 数据。
- 插件数据、授权与运行状态由宿主另存，不写回安装包。
- 侧载项目不参与在线更新，普通 `.zip` 导入仍可使用。

## 导入失败排查

| 提示 | 检查内容 |
| --- | --- |
| 压缩包根目录里没有 plugin.json | 打包时是否多嵌套了一层目录 |
| plugin.json 缺少 / 无效字段 | 按错误中的字段路径对照[清单参考](/advanced/plugin/manifest) |
| 此版本不支持的字段 / 内容类型 | 当前宿主是否支持该能力；未知字段会拒绝安装 |
| 声明了 handler 但未声明运行时 | 带 handler 的工具、Hook 和动作均需要 `runtime` |
| uiEntries 声明无效 | 按[槽位规则](/advanced/plugin/manifest#uientries)检查 `slot` 与 `page` / `handler` 的组合，`backup.data` 为数据选项 |
| 声明了备份事件但未声明 contributes.backup | 补充[备份声明](/advanced/plugin/manifest#backup) |
| 内容无效 | Markdown 正文是否为空、子代理是否指定了 `provider` / `model`、schema 根是否为 object、MCP 配置是否有效 |
| 清单引用的文件不存在 | 路径、构建产物和大小写是否一致 |
| 同一类型内 id 重复 | 技能、工具、页面等在各自类型内是否重名 |
| 路径不安全 / 文件太大 / 重复文件 | 对照[安装限制](#安装限制)检查包内容 |

例如 `contributes.tools[0].inputSchema` 指向第一个工具的参数文件声明，可直接定位修改。

## 日志与调试

打开「设置 → 日志」，搜索插件 ID 或 `Plugin`，查看进程启动、调用失败、Hook 超时和浮层创建错误。

- TypeScript 运行时可用 SDK 的 `log(message)` 写入日志；handler 返回 `logs` 的规则见 [handler 约定](/advanced/plugin/tools-runtime#handler-约定)。
- 运行时启动失败时，检查本地容器是否已准备 Node.js / Python 环境，以及运行期依赖是否已安装。
- 页面空白时，检查构建是否完成、清单页面路径与 assets 是否齐全，并在 AiCode 真机上验证宿主桥调用。
- 侧载未更新时，确认「开发者侧载」仍开启，构建结果已同步到侧载目录；必要时在项目详情手动重载。
- Shizuku、浮层或备份事件失败时，先检查插件详情中的独立授权；提供商调用失败时，检查兼容服务是否就绪。

## 发布前检查

- 清单中的标识、版本、仓库地址正确，引用文件全部存在且大小写一致。
- 运行时和页面已经重新构建，文件大小符合限制。
- Markdown 正文、工具 schema、MCP 配置通过对应规则检查。
- 包中包含页面 assets 与必要的依赖文件，排除了密钥和开发缓存。
- 在真机验证安装、启用、工具调用、页面操作、停用与卸载。
- 发布到 GitHub Release 时，资产名使用 `{id}_{version}.zip`；在插件索引中更新 `repository` 和 `version`。
