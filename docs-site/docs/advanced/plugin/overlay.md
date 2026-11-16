# 系统浮层

系统浮层是宿主托管的 Android 悬浮窗，页面仍为插件包内的 HTML。适用于桌宠、状态 HUD、后台审批面板等需要显示在 App 之外的组件。

## 声明

```json
{
  "contributes": {
    "overlay": {
      "purpose": "悬浮小组件与状态 HUD",
      "surfaces": [
        {"id": "pet", "page": "pages/pet.html", "width": 240, "height": 320, "draggable": true, "snap": "x"},
        {"id": "menu", "page": "pages/menu.html", "width": 150, "height": 130, "draggable": false}
      ]
    },
    "configPage": "pages/config.html"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `purpose` | 用途说明，最多 200 字符，展示在授权界面 |
| `surfaces` | 1–3 个浮层页面 |
| `surface.id` | 局部 ID，同一插件内唯一 |
| `surface.page` | 包内 `.html`/`.htm` 页面 |
| `surface.width` | 默认宽度，单位 dp，默认 240，范围 48–720 |
| `surface.height` | 默认高度，单位 dp，默认 320，范围 48–1200 |
| `surface.draggable` | 是否允许拖动，默认 `true` |
| `surface.snap` | 松手吸附轴：`none`、`x`、`y`、`both`，默认 `none` |

全局最多同时存在 8 个插件浮层窗口，单个插件最多 3 个。窗口不可全屏，也不保证覆盖安全界面。

## 授权

声明不会自动创建窗口，需同时满足以下条件：

1. 使用者在插件详情中单独开启该插件的浮层授权；
2. App 已获得系统的「显示在其他应用上层」权限。

任一条件不满足时，`overlay.create` 将返回 `OVERLAY_NOT_AUTHORIZED` 或 `OVERLAY_SYSTEM_PERMISSION_MISSING`。插件停用、卸载或撤销授权时，宿主将关闭其创建的窗口。

## 创建与更新

普通页面或插件进程均可创建清单中声明的 surface：

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

## 浮层页面

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="/__aicode/theme.css">
  <script src="/__aicode/aicode.js"></script>
  <style>
    html, body {
      margin: 0;
      width: 100%;
      height: 100%;
      overflow: hidden;
      background: transparent;
    }
    .ball {
      width: 100%;
      height: 100%;
      border-radius: 50%;
      background: var(--aicode-primary);
      display: grid;
      place-items: center;
      color: var(--aicode-on-surface);
      font-size: 14px;
    }
  </style>
</head>
<body>
  <div class="ball" id="ball">AI</div>
  <script>
    const ball = document.getElementById('ball');

    aicode.overlay.self().then((self) => {
      aicode.overlay.setDragRegion({left: 0, top: 0, width: self.width, height: self.height});
    });

    ball.addEventListener('click', () => {
      aicode.overlay.postMessage({type: 'click'});
    });

    aicode.overlay.onMessage((message) => {
      if (message.type === 'state') {
        ball.textContent = message.busy ? '忙' : 'AI';
      }
    });
  </script>
</body>
</html>
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

自动启动不由宿主内置。插件声明 `app.started` hook，在 handler 中读取自身 `data://` 设置，随后调用 `host.overlay.create`（以下 `hostCall` 沿用[快速开始](/advanced/plugin/quick-start)）：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js", "resident": true},
  "contributes": {
    "overlay": {
      "purpose": "状态悬浮球",
      "surfaces": [{"id": "ball", "page": "pages/ball.html", "width": 48, "height": 48}]
    },
    "hooks": [{"id": "boot", "event": "app.started", "handler": "hooks.boot"}]
  }
}
```

```js
const handlers = {
  'hooks.boot': async () => {
    const enabled = await hostCall('host.fs.read', {path: 'data://enabled'})
      .then((result) => result.content.trim() === 'true')
      .catch(() => false);
    if (!enabled) return {logs: ['overlay disabled']};
    await hostCall('host.overlay.create', {
      surface: 'ball',
      visibilityPolicy: 'always'
    });
    return {logs: ['overlay created']};
  }
};
```

宿主仅负责在 App 启动时派发事件；是否创建、尺寸与数量均由插件决定。浮层授权未开启或系统权限缺失时，创建将失败并记录日志。

## 示例

独立仓库 [`jieapi/aicode-plugin-desktop-pet`](https://github.com/jieapi/aicode-plugin-desktop-pet) 演示单 surface：宠物会眨眼、摆耳、摇尾，拖拽松手有落地回弹，靠近屏幕边缘后以翻身动画贴边。长按宠物会在下方展开菜单，点喂食、休息或关闭按钮后收起；菜单展开和收起时保持宠物宽度与大小，动画完成后再执行喂食或休息，并恢复展开前的位置。喂食时会低头咀嚼，休息时闭眼并放慢呼吸，睡眠标志仅在休息时出现。连续空闲 3 分钟会自动休息，抚摸、拖动和菜单操作会重计空闲时间；思考、工作和进食期间不计时。睡眠时每次抚摸有 30% 概率醒来，未醒来则继续睡；喂食或新任务会切换到相应状态。该示例声明了 `app.started`、`plugin.enabled`、`agent.run.started`、`agent.tool.started`、`agent.tool.finished`、`agent.run.finished` 六个只读 hook：启动或启用插件时按“随 App 启动”设置创建浮层，并以 AI 运行状态驱动宠物表情。

`plugins/samples/background-approval` 演示后台审批：`app.started` 时创建悬浮球，`agent.permission.review` 收到审批请求后将悬浮球展开为面板；App 在前台时直接返回 `pass`，交回应用内确认框。

## 限制

- 单个插件最多 3 个 surface，全局最多 8 个窗口；
- 宽度 48–720 dp，高度 48–1200 dp；
- 窗口不可全屏，也不保证覆盖安全界面；
- 浮层页面不得调用 Shizuku、Shell 或终端；
- 授权撤销、插件停用或卸载时，窗口将被关闭；
- 位置按插件与 surface 保存，不按页面实例保存。
