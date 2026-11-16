# 系统浮层

系统浮层是宿主托管的 Android 悬浮窗，页面仍为插件包内的 HTML。适用于桌宠、状态 HUD、后台审批面板等需要显示在 App 之外的组件。

## 声明

使用 `contributes.overlay` 声明用途与 surface，字段、尺寸范围和数量限制见[清单参考](/advanced/plugin/manifest#overlay)。全局最多同时存在 8 个插件浮层窗口，窗口不可全屏，也不保证覆盖安全界面。

## 授权

声明不会自动创建窗口，需同时满足以下条件：

1. 使用者在插件详情中单独开启该插件的浮层授权；
2. App 已获得系统的「显示在其他应用上层」权限。

任一条件不满足时，`overlay.create` 将返回 `OVERLAY_NOT_AUTHORIZED` 或 `OVERLAY_SYSTEM_PERMISSION_MISSING`。插件停用、卸载或撤销授权时，宿主将关闭其创建的窗口。

## 创建与更新

普通页面通过 `aicode.overlay.create({surface?, width?, height?, visibilityPolicy?})` 创建清单中的 surface：

```js
const overlay = await aicode.overlay.create({
  surface: 'pet',
  width: 120,
  height: 120,
  visibilityPolicy: 'always'
});
```

返回 `{overlayId, width, height, ...}`。未指定尺寸时使用清单默认值；超出 48–720 × 48–1200 将返回 `PARAM_INVALID`。

更新：

```js
await aicode.overlay.update(overlay.overlayId, {
  width: 160,
  height: 160,
  visible: true,
  visibilityPolicy: 'foreground',
  dx: 12,
  dy: -8
});
```

关闭：

```js
await aicode.overlay.close(overlay.overlayId);
// 浮层页面内可省略 id，关闭自身
await aicode.overlay.close();
```

浮层页面读取自身信息：

```js
const self = await aicode.overlay.self();
// { overlayId, pluginId, width, height, x, y, density, draggable, snap, visibilityPolicy, foreground }
```

窗口位置按 `pluginId:surfaceId` 自动保存，重启或自动拉起时恢复。插件拖动窗口或调用 `moveBy` 后均会更新保存位置。

## 进程接口

进程通过 SDK 的 `hostCall` 调用：

| 方法 | 参数 | 作用 |
| --- | --- | --- |
| `host.overlay.create` | `{surface?, width?, height?, visibilityPolicy?}` | 返回 `{overlayId, width, height, ...}` |
| `host.overlay.update` | `{overlayId, width?, height?, visible?, visibilityPolicy?, dx?, dy?}` | 更新窗口 |
| `host.overlay.close` | `{overlayId}` 或 `{surface}` | 关闭窗口 |
| `host.overlay.postMessage` | `{overlayId?, surface?, payload}` | 向浮层页面发送消息 |

## 浮层页面

浮层同样推荐使用 Vue + TypeScript，HTML 壳与构建方式见[页面构建](/advanced/plugin/source-build#页面构建)。透明背景与交互可写在 `.vue` 中：

```vue
<template>
  <button class="ball" @click="sendClick">{{ label }}</button>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';

const label = ref('AI');
const off = aicode.overlay.onMessage((message) => {
  if (message.type === 'state') label.value = message.busy ? '忙' : 'AI';
});

onMounted(async () => {
  const rect = document.body.getBoundingClientRect();
  await aicode.overlay.setDragRegion({left: 0, top: 0, width: rect.width, height: rect.height});
});
onUnmounted(off);

async function sendClick(): Promise<void> {
  await aicode.overlay.postMessage({type: 'click'});
}
</script>

<style>
html, body, #app {
  margin: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: transparent;
}
.ball {
  width: 100%;
  height: 100%;
  border: 0;
  border-radius: 50%;
  background: var(--aicode-primary);
  color: var(--aicode-on-surface);
}
</style>
```

浮层页面支持以下操作：

- `overlay.self()` 读取自身 id、尺寸、位置与密度；
- `overlay.setDragRegion(rect)` 声明宿主接管拖拽的区域，单位为 CSS 像素；
- `overlay.resize(width, height, options?)` 调整自身窗口尺寸，单位为 dp；
- `overlay.setVisibilityPolicy(policy)` 设置显示时机；
- `overlay.moveBy(dx, dy)` 请求移动；
- `overlay.onMessage(handler)` 接收 `state`、`resize`、`appState`、`click`、`dragStart`、`dragEnd`、`longPress`、`snap`；
- `overlay.postMessage(payload)` 将交互回传至插件进程的 `overlay.message` handler。

浮层页面不得调用 Shizuku、Shell 或终端。需要这些能力时，应由浮层通过 `postMessage` 将请求发送给插件进程执行。

## 靠边吸附

`surface.snap` 声明拖动松手后的吸附轴：

| 值 | 行为 |
| --- | --- |
| `none` | 不吸附，松手位置即最终位置，默认 |
| `x` | 吸附到左或右边缘 |
| `y` | 吸附到上或下边缘 |
| `both` | 吸附到 40dp 内最近的一条边 |

只有窗口边缘距离屏幕边缘 40dp 以内时才触发吸附；拖到中间松手会停在原位，不会自动贴边。吸附由宿主完成：松手后取 40dp 内最近的边，以 280ms 过渡移动窗口，并把最终位置写入保存位置。插件不参与窗口几何计算，也不需要知道屏幕尺寸。

吸附开始时页面收到事件：

```js
{type: 'snap', edge: 'left' | 'right' | 'top' | 'bottom', x, y}
```

`x`/`y` 是目标位置的 dp 坐标，与 `self()` 一致。页面按 `edge` 切换贴边后的表现，例如翻身朝向、趴下动画、只在靠边半区绘制内容。宿主只保证窗口贴着屏幕边缘，不会让窗口越出屏幕。

`dragEnd` 的 `x`/`y` 同样是 dp，与 `snap`、`self()` 保持一致；拖动开始后窗口尚未吸附，需要恢复非贴边形态时监听 `dragStart`。

```js
let edge = null;

aicode.overlay.onMessage((message) => {
  if (message.type === 'snap') {
    edge = message.edge;
    document.body.dataset.edge = edge;
  } else if (message.type === 'dragStart') {
    edge = null;
    delete document.body.dataset.edge;
  }
});
```

吸附后调用 `overlay.resize`，宿主会让窗口继续贴着同一条边；显式传入 `options.x` 或 `options.y` 时以传入位置为准。

## 尺寸动画

`overlay.resize` 未指定 `options` 时立即调整；指定 `{animate: true}` 时，宿主在原窗口内以 280ms 同步过渡尺寸与位置，页面不会重新加载。

```js
await aicode.overlay.resize(320, 480, {animate: true, x: 40, y: 120});
```

- `options.x/y` 为目标位置，单位与 `self()` 中的位置相同，均为 dp；
- 宿主会自动将目标窗口限制在屏幕范围内；
- 变形完成后页面收到 `{type: "resize", width, height, x, y}`，位置为最终 dp 坐标；
- 调用返回并不表示动画已经结束；
- 系统窗口仅在变形开始和结束时调整布局，中间通过内容裁剪与平移过渡；Android 14 及以上会关闭系统额外的位置过渡；
- 页面应使用固定内容尺寸配合裁剪和渐显，避免在动画中反复排版；
- 新的尺寸请求会从当前状态继续，关闭窗口时停止动画。

## 显示时机

`visibilityPolicy` 由插件决定，宿主不预设业务：

| 值 | 行为 |
| --- | --- |
| `always` | 始终显示 |
| `foreground` | 仅在 App 前台显示 |
| `background` | 仅在 App 后台显示 |

App 前后台状态以 `ProcessLifecycleOwner` 为准。插件也可轮询 `aicode.app.isForeground()` 或进程的 `host.app.isForeground` 自行判断。

## 自动启动

插件可以在 `app.started` Hook 中读取自身设置，再调用 `host.overlay.create`。清单中的 Hook 与常驻配置见[常驻与自启动](/advanced/plugin/tools-runtime#常驻与自启动)，对应 handler 例如：

```ts
import { definePlugin, hostCall } from '@jieapi/plugin-sdk';

definePlugin({
  handlers: {
    'hooks.boot': async () => {
      const enabled = await hostCall<{content: string}>('host.fs.read', {path: 'data://enabled'})
        .then((result) => result.content.trim() === 'true')
        .catch(() => false);
      if (!enabled) return {logs: ['overlay disabled']};
      await hostCall('host.overlay.create', {
        surface: 'ball',
        visibilityPolicy: 'always'
      });
      return {logs: ['overlay created']};
    }
  }
});
```

宿主仅负责在 App 启动时派发事件；是否创建、尺寸与数量均由插件决定。浮层授权未开启或系统权限缺失时，创建将失败并记录日志。

## 参考源码

浮层插件可参考 [`jieapi/aicode-plugin-desktop-pet`](https://github.com/jieapi/aicode-plugin-desktop-pet)。
