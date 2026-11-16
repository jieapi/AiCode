# 用 TypeScript 编写与构建插件

插件进程只要求 `runtime.entry` 指向一个可被 `node` 启动的 JavaScript 文件，因此可以在仓库里用 TypeScript 分模块编写，再打包成单个 `dist/main.js`。宿主不读取源码，构建产物与手写的入口完全等价。

仓库的 `plugins/` 目录包含这套工具链，新插件可以直接使用。

## 目录结构

```text
capability-demo/
├── plugin.json          # runtime.entry 仍指向 dist/main.js
├── src/
│   ├── index.ts         # 入口：注册 handlers
│   └── handlers/
│       ├── tools.ts
│       └── actions.ts
└── dist/
    └── main.js          # 构建产物，随插件包发布
```

`src/` 不参与宿主运行；`dist/main.js` 必须随包发布。清单中的 `runtime.entry` 不需要修改。

## 工具链

| 路径 | 作用 |
| --- | --- |
| `plugins/sdk/` | 仓库内共享的插件 SDK：JSON-RPC 循环、`hostCall`、`emit`、类型定义 |
| `plugins/build.mjs` | esbuild / Vite 构建脚本，构建运行时与页面，扫描 `plugins/samples/` 与 `plugin-official/` |
| `plugins/build-pages.mjs` | Vite 页面构建：SFC → `pages/*.html` + `pages/assets/*` |
| `plugins/tsconfig.json` | 插件源码的类型检查配置 |
| `plugins/template/` | 独立插件项目模板，不依赖主仓库 |

构建脚本与 `plugins/tsconfig.json` 已把 `@aicode/plugin-sdk` 映射到 `plugins/sdk/src/index.ts`，插件目录无需安装该包。

首次使用在仓库根目录安装 `plugins/` 下的开发依赖：

```bash
npm --prefix plugins install
```

依赖只用于构建与类型检查，不会进入插件包，也不影响 App 的 Gradle 构建。

## 独立插件项目（不克隆主仓库）

如果你在单独的插件项目里开发，不需要主仓库，直接用 `plugins/template/` 作为起点：

```bash
npx degit jieapi/AiCode/plugins/template my-plugin
cd my-plugin
npm install
npm run build
```

也可以手动复制 `plugins/template/` 目录。模板自带 `package.json`、`build.mjs`、`build-pages.mjs`、`tsconfig.json`、一个 echo 工具和一个配置页示例 `src/pages/config.vue`，依赖已发布的 `@jieapi/plugin-sdk`。

开始前修改：

- `plugin.json` 的 `id`、`publisher`、`repository`、`version`；
- `src/index.ts` 和 `src/handlers/*.ts` 里的 handler；
- `schemas/*.json` 的入参定义；
- `package.json` 的 `name`、`version`；`private` 可以保留。

模板的命令：

```bash
npm run build      # 生成 dist/main.js 与 dist/pages/
npm run watch      # 监听源码变化
npm run typecheck  # 类型检查
```

打包时保留 `plugin.json`、`dist/`、`schemas/`、`pages/` 等运行时文件，排除 `node_modules`、`src` 和构建缓存；声明了 `runtime.dependencies` 时保留 `package.json`。

## 编写入口

`src/index.ts` 注册 handler 并启动消息循环：

```ts
import { definePlugin } from '@aicode/plugin-sdk';
import { tools } from './handlers/tools';

definePlugin({
  handlers: { ...tools }
});
```

handler 的键仍要与 `plugin.json` 中的 `handler` 一致：

```ts
import { hostCall, type PluginHandler } from '@aicode/plugin-sdk';

export const tools: Record<string, PluginHandler> = {
  'tools.echo': async (args) => {
    const result = await hostCall<{ content: string }>('host.fs.read', {
      path: String(args.path)
    });
    return { text: result.content };
  }
};
```

SDK 导出的接口：

| 导出 | 说明 |
| --- | --- |
| `definePlugin({ handlers })` | 注册 handler 并开始读取宿主请求；每个进程只能调用一次 |
| `hostCall<T>(method, params?)` | 发送 `host.*` 请求，返回 `result`；宿主返回错误时 Promise reject |
| `emit(name, payload?)` | 发送 `plugin.event` 通知，推送给同插件已打开的页面 |
| `log(message)` | 写入 stderr，由宿主转入应用日志 |
| `PluginHandler` / `PluginArgs` | handler 与入参的类型 |

## 构建

```bash
npm --prefix plugins run build:plugins                          # 构建全部插件与页面
npm --prefix plugins run build:plugins -- --plugin capability-demo
npm --prefix plugins run watch:plugins                          # 监听运行时与页面源码
npm --prefix plugins run build:plugins -- --minify              # 压缩产物
npm --prefix plugins run typecheck:plugins                      # 只做类型检查
```

运行时构建入口优先取 `src/index.ts`，其次 `src/main.ts`。输出路径读取 `plugin.json` 的 `runtime.entry`，未声明时写入 `dist/main.js`。产物是单文件 CommonJS，构建目标为 Node 12，SDK 与纯 JS 依赖都会被打入，运行时不需要 `node_modules`。

运行时入口与页面产物各有 512 KiB 上限；超过时构建失败，请拆分逻辑或启用 `--minify`。

## 页面构建

页面以 Vue 单文件组件编写。一个页面由三个同目录文件组成：

```text
src/pages/
├── config.html     # 页面壳：外部 /__aicode/* 脚本 + <div id="app">
├── config.js       # createApp(App).mount('#app')
└── config.vue      # template、script、style
```

页面壳保留宿主资源，不放业务标记：

```html
<div id="app"></div>
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/aicode-ui-vue.js"></script>
<script src="/__aicode/aicode.js"></script>
<script type="module" src="./config.js"></script>
```

`config.js` 使用全局 Vue 创建应用；需要 `aicode-*` 组件时注册组件库：

```js
import { createApp } from 'vue';
import App from './config.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
```

构建时 Vite 与 `@vitejs/plugin-vue` 编译 SFC，`vue` 的导入被映射到宿主全局 `window.Vue`，页面产物因此不需要再携带一份 Vue runtime。输出是 `pages/<页面名>.html` 加 `pages/assets/*.js|css`，HTML 用相对路径引用资产；宿主 `/plugin/` 映射插件目录，可以直接加载这些文件，**不要求页面单文件**。

`/__aicode/*` 绝对路径不会被打包，运行时由宿主提供。页面脚本支持普通 JavaScript、ES module 与 SFC。`pages/` 下的 HTML 和 assets 都是构建产物，不要直接编辑。


`--watch` 会监听 `src/pages` 并重建页面。

模板的 `src/pages/config.vue` 是一个可直接运行的配置页示例：读取 `aicode.context()`、用 `aicode.fs` 读写 `data://config.json`、调用 runtime 的 `tools.echo`，并使用 `aicode-*` 组件；`npm run build` 会同时生成 `dist/main.js` 与 `dist/pages/config.html`，模板统一把运行时与页面产物放在 `dist/` 下。

## 页面类型提示

页面桥的类型由 SDK 按子路径导出：

| 入口 | 作用 |
| --- | --- |
| `@jieapi/plugin-sdk/page` | 声明 `window.aicode`、全局 `aicode` 与 `AicodeUI`，覆盖 `context`、`fs`、`chat`、`model`、`tools`、`overlay` 等全部页面桥方法、返回值与事件 payload |
| `@jieapi/plugin-sdk/page/vue` | 把 `aicode-*` 组件并入 Vue 的 `GlobalComponents`，模板里可补全组件名与 props |

模板的 `types/env.d.ts` 已经引入这两个入口。页面脚本改用 `<script setup lang="ts">` 或 `.ts` 后即可获得参数、返回值与错误码提示，`npm run typecheck` 会把类型错误当作失败。类型声明只用于编辑器与类型检查，`vue` 也只是类型依赖；构建产物仍使用宿主注入的 `/__aicode/vue.global.prod.js`。

## 第三方依赖

在插件目录执行 `npm install <包名>` 后直接 `import` 即可，esbuild 会把纯 JavaScript 依赖打包进产物。

含原生二进制、动态 `require` 或运行期读取资源文件的依赖无法可靠打包，应改用 `runtime.dependencies` 声明，让使用者在安装后准备依赖：

```json
{
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js",
    "dependencies": {"tool": "npm", "file": "package.json"}
  }
}
```

这种模式下代码中的 `require('原生模块')` 会保留，`node_modules` 由宿主的「准备依赖」安装到插件目录，不会随包发布。构建脚本会读取 `runtime.dependencies`，把插件 `package.json` 的 `dependencies` 自动标记为 external，避免把依赖打进产物。

## 开发与侧载

1. 将插件目录放入容器的 `~/.aicode/plugin-dev/<项目名>/`，目录根部包含 `plugin.json`；
2. 在仓库根目录运行 `npm --prefix plugins run watch:plugins`，构建结果写入该插件的 `dist/main.js` 与 `pages/*.html`；
3. 在「设置 → 插件 → 开发中」点击「安装」或「重载」；
4. 修改源码后等构建完成，再重载侧载项目。

如果仓库目录与侧载目录不是同一个位置，需要把构建后的 `dist/` 和 `pages/` 同步过去；宿主读取的是侧载目录中的文件。

## 打包与发布

先构建，再打包：

```bash
npm --prefix plugins run build:plugins
python3 scripts/package-plugins.py capability-demo
```

`src/` 可以留在包里，但运行时只使用 `dist/main.js`。发布前确认：

- `dist/main.js` 是最新构建产物；
- `plugin.json` 的 `runtime.entry` 与产物路径一致；
- 产物未超过 512 KiB；
- 打包时没有把 `node_modules`、测试数据或密钥带入。

## 迁移手写入口

1. 新建 `src/index.ts`，按功能把 handler 拆到 `src/handlers/*.ts`；
2. 用 `definePlugin` 注册全部 handler，键保持不变；
3. 运行 `npm --prefix plugins run build:plugins -- --plugin <目录名>` 生成 `dist/main.js`；
4. 逐项验证工具调用、hook、页面 `aicode.invoke` 与事件推送；
5. 保留原 `plugin.json`，替换手写入口为构建产物。

## 边界

- 页面 HTML（`pages/*.html`）、技能 Markdown、JSON Schema、MCP 配置仍直接编辑，不经过构建；
- 宿主不会运行 TypeScript 源码，提交或发布前必须生成 `dist/main.js`；
- SDK 只封装通用协议，`host.*` 方法名与参数以[AI 工具与插件运行时](/advanced/plugin/tools-runtime)为准。

可参考[示例库](/advanced/plugin/examples)中的 `capability-demo`，它使用本页的源码结构。

## 发布 SDK（维护者）

`plugins/sdk` 是可发布的 npm 包 `@jieapi/plugin-sdk`。发布前先构建并检查包内容：

```bash
npm --prefix plugins run build:sdk
cd plugins/sdk
npm pack --dry-run
npm publish
```

`prepublishOnly` 会再次执行构建，`files` 只包含 `dist/` 与 `LICENSE`。SDK 以 MIT 协议发布，主项目仍保持 GPL-3.0；第三方插件可以闭源，只需保留 SDK 的版权与许可声明。
