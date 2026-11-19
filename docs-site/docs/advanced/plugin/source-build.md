# TypeScript + Vue 开发与构建

推荐用 **TypeScript 编写插件逻辑，Vue 3 单文件组件编写页面**：运行时由 esbuild 打包，页面由 Vite 编译；宿主加载 JavaScript 和 HTML 产物。首次创建和侧载项目见[快速开始](/advanced/plugin/quick-start)，本篇说明源码组织与构建约定。

## 项目结构

独立模板 `plugins/template/` 的主要文件如下：

```text
my-plugin/
├── plugin.json
├── package.json
├── build.mjs
├── build-pages.mjs
├── tsconfig.json
├── types/
│   └── env.d.ts
├── schemas/
│   └── echo.input.json
├── src/
│   ├── index.ts
│   ├── handlers/
│   │   └── tools.ts
│   └── pages/
│       ├── config.html
│       ├── config.ts
│       └── config.vue
└── dist/
    ├── main.js
    └── pages/
        ├── config.html
        └── assets/
```

- `src/`、`types/` 和构建脚本用于开发；`dist/` 是生成产物，修改源码后重新构建。
- `plugin.json` 的 `runtime.entry` 指向 `dist/main.js`，页面入口指向 `dist/pages/*.html`。
- Markdown、JSON Schema 和 MCP 配置直接编辑，按清单引用随包交付。

## 运行时与 SDK

独立项目使用 npm 包 `@jieapi/plugin-sdk`，当前契约版本为 `0.2.0`，依赖范围使用 `^0.2.0`，对应清单 `engines.pluginApi: "^1.0.0"`。SDK 包版本与宿主插件 API 版本不是同一编号。在 `src/index.ts` 注册 handler：

```ts
import { definePlugin } from '@jieapi/plugin-sdk';
import { tools } from './handlers/tools';

definePlugin({ handlers: tools });
```

`src/handlers/tools.ts`：

```ts
import { hostCall, type PluginHandler } from '@jieapi/plugin-sdk';

export const tools: Record<string, PluginHandler> = {
  'tools.echo': async (args) => {
    const text = String(args.text ?? '');
    await hostCall('host.fs.write', {
      path: 'data://echo.txt',
      content: text
    });
    return { text };
  }
};
```

handler 的键必须与清单中的 `handler` 一致。工具参数由 `inputSchema` 描述，页面也可用 `aicode.invoke('tools.echo', {text: 'hello'})` 调用同一 handler。

| SDK 导出 | 用途 |
| --- | --- |
| `definePlugin({ handlers })` | 注册 handler 并启动 JSON-RPC 消息循环，每个进程调用一次 |
| `hostCall<T>(method, params?)` | 调用宿主 `host.*` 方法并取得 `result`；错误会使 Promise reject |
| `emit(name, payload?)` | 向同插件已加载的页面推送事件 |
| `log(message)` | 写 stderr，由宿主转入应用日志 |
| `PluginHandler` / `PluginArgs` | handler 与入参类型 |

运行时构建为单文件 CommonJS，目标为 Node 12，SDK 和可打包的纯 JavaScript 依赖会一起进入产物。宿主方法与运行时约定见 [AI 工具与插件运行时](/advanced/plugin/tools-runtime)。

## 页面构建

每个页面在 `src/pages/` 中使用同名的三个文件：

| 文件 | 职责 |
| --- | --- |
| `config.html` | HTML 壳，加载宿主资源和 `.ts` 入口 |
| `config.ts` | 创建 Vue 应用并注册组件库 |
| `config.vue` | 用 `<template>`、`<script setup lang="ts">` 和 `<style>` 编写界面 |

### HTML 壳

`src/pages/config.html`：

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="/__aicode/theme.css">
  <link rel="stylesheet" href="/__aicode/ui.css">
</head>
<body>
  <div id="app"></div>
  <script src="/__aicode/vue.global.prod.js"></script>
  <script src="/__aicode/aicode-ui-vue.js"></script>
  <script src="/__aicode/aicode.js"></script>
  <script type="module" src="./config.ts"></script>
</body>
</html>
```

`/__aicode/*` 由宿主提供，不参与打包。页面使用宿主 Vue runtime，业务代码和其他资源随插件发布。

### Vue 入口

`src/pages/config.ts`：

```ts
import { createApp } from 'vue';
import App from './config.vue';

const app = createApp(App);
if (window.AicodeUI) app.use(window.AicodeUI);
app.mount('#app');
```

构建脚本将 `vue` 导入映射到 `window.Vue`，页面产物不会再携带一份 Vue runtime。

### 单文件组件

`src/pages/config.vue` 中可直接调用有类型提示的页面桥：

```vue
<template>
  <main class="aicode-page">
    <aicode-field label="名称">
      <aicode-input v-model="name" placeholder="输入名称" />
    </aicode-field>
    <aicode-button @click="save">保存</aicode-button>
    <p>{{ status }}</p>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const name = ref('');
const status = ref('');

async function save(): Promise<void> {
  try {
    await aicode.fs.write('data://config.json', JSON.stringify({ name: name.value }));
    status.value = '已保存';
  } catch (error) {
    status.value = '保存失败：' + (error as Error).message;
  }
}
</script>
```

Vite 扫描 `src/pages/*.html`，每个入口须有同名 `.vue` 文件。输出 HTML 通过相对路径引用 `assets/*.js` 和 `assets/*.css`，页面可以由多个文件组成。独立模板输出到 `dist/pages/`；主仓库工具链输出到插件根部的 `pages/`。

页面挂载位置、桥接口与返回键见[插件页面与宿主接口](/advanced/plugin/ui-pages)，主题和组件用法见[页面样式、组件与事件](/advanced/plugin/ui-components)。

## 页面类型提示

模板的 `types/env.d.ts` 已引入：

```ts
import '@jieapi/plugin-sdk/page';
import '@jieapi/plugin-sdk/page/vue';
```

| 类型入口 | 提供内容 |
| --- | --- |
| `@jieapi/plugin-sdk/page` | `window.aicode`、全局 `aicode`、`AicodeUI`，以及桥方法、返回值和事件 payload |
| `@jieapi/plugin-sdk/page/vue` | Vue `GlobalComponents` 中的 `aicode-*` 组件、props 与事件类型，包括 `v-model` 更新事件 |

类型声明仅用于开发，不会进入页面产物。模板的 `npm run typecheck` 使用 `tsc` 检查 `.ts` 文件；Vue SFC 还需结合 Vue 编辑器的类型提示、页面构建和真机验证。

## 构建命令

独立项目在项目根目录执行：

| 命令 | 用途 |
| --- | --- |
| `npm run build` | 构建运行时与页面 |
| `npm run watch` | 监听源码并重新构建 |
| `npm run typecheck` | 检查 TypeScript 类型 |
| `npm run build -- --minify` | 压缩产物 |

构建后按[开发者侧载](/advanced/plugin/quick-start#_3-开发者侧载)验证；开发目录与侧载目录分开时，需同步最新产物。发布文件与大小限制统一见[打包文档](/advanced/plugin/packaging)。

## 第三方依赖

纯 JavaScript 依赖可在项目中通过 `npm install <包名>` 安装并 `import`，由 esbuild 打包进运行时。

含原生二进制、动态 `require` 或运行期资源的包需要保留为运行期依赖：

1. 在 `runtime.dependencies` 中声明依赖文件，写法见[依赖准备](/advanced/plugin/tools-runtime#依赖准备)。
2. 构建时将这些包设为 esbuild 的 `external`。独立模板需在 `build.mjs` 中配置；主仓库工具链会读取声明了 `runtime.dependencies` 的插件，将其 `package.json` 的 `dependencies` 自动设为 external。
3. 发布时保留依赖文件，使用者在插件详情点击「准备依赖」后再运行。