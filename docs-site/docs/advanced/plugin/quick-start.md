# 快速开始

本篇使用独立模板创建一个 **TypeScript + Vue** 插件，完成构建、侧载和首次验证。源码与构建细节见 [TypeScript + Vue 开发与构建](/advanced/plugin/source-build)。

## 1. 创建项目

在安装了 Node.js 18+ 和 npm 的开发环境中执行：

```bash
npx degit jieapi/AiCode/plugins/template my-plugin
cd my-plugin
npm install
```

也可以手动复制仓库的 `plugins/template/` 目录。模板使用已发布的 `@jieapi/plugin-sdk`，可独立开发。

修改项目标识：

| 文件 | 要修改的内容 |
| --- | --- |
| `plugin.json` | `id`、`publisher`、`repository`、`name`、`description`、`version` |
| `package.json` | `name`、`version` |

`publisher` 要与 `id` 的第一段一致，`repository` 填写自己的 HTTPS 仓库地址。完整字段规则见 [清单参考](/advanced/plugin/manifest)。

## 2. 构建

在项目目录执行：

```bash
npm run typecheck
npm run build
```

模板生成以下运行文件：

```text
my-plugin/
├── plugin.json
├── schemas/
└── dist/
    ├── main.js
    └── pages/
        ├── config.html
        └── assets/
```

清单已将 `runtime.entry` 指向 `dist/main.js`，将 `contributes.configPage` 指向 `dist/pages/config.html`。修改输出位置时，清单路径也要一起调整。

## 3. 开发者侧载

侧载用于开发期间验证插件，项目目录根部必须包含 `plugin.json`。

1. 在 AiCode 的「设置 → 插件」中点击右上角设置按钮，开启「开发者侧载」。
2. 将项目放入 `~/.aicode/plugin-dev/my-plugin/`，并确保包含最新的构建产物。
3. 回到插件页，在「开发中」标签打开项目详情，点击「安装」并启用。

本地模式下，侧载目录位于手机容器；远程 SSH 模式下，侧载目录位于远端服务器的用户 home。开发目录与侧载目录不同时，需要把清单、构建产物和清单引用的其他文件同步过去。

侧载会跳过 `node_modules`、`.git` 等目录；运行期依赖使用「准备依赖」，见[依赖准备](/advanced/plugin/tools-runtime#依赖准备)。侧载项目不参与在线更新，下拉「开发中」列表可重新扫描目录。

## 4. 验证与修改

打开插件详情中的配置页，确认页面可加载、配置可保存、运行时调用可返回结果。再在 AI 对话中调用插件工具，按提示确认工具授权。

按功能修改源码：

| 内容 | 位置 |
| --- | --- |
| 运行时入口与 handler | `src/index.ts`、`src/handlers/*.ts` |
| Vue 页面 | `src/pages/*.vue`、同目录的 `.ts` 和 `.html` |
| 工具参数 | `schemas/*.json` |
| 能力与入口声明 | `plugin.json` |

持续开发时运行：

```bash
npm run watch
```

保持「开发者侧载」开启，构建产物保存后会自动重载；也可在项目详情手动点击「重载」。宿主读取构建产物，请编辑 `src/`，不要直接修改生成的 `dist/`。

页面与桥接口需要在 AiCode 中验证。运行失败时，按[日志与调试](/advanced/plugin/packaging#日志与调试)排查。

## 5. 打包交付

功能验证通过后，按[打包、导入与排错](/advanced/plugin/packaging)准备 `.zip` 并导入真机验证。添加其他能力时，可回到[能力索引](/advanced/plugin/#能力索引)查找对应参考页。
