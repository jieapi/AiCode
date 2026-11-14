# 页面样式、组件与事件

插件页面可使用宿主主题、Vue 组件库和文件订阅，使界面与 App 保持一致，并在数据变化时刷新。

## 主题

引用宿主动态生成的 `/__aicode/theme.css`：

```html
<link rel="stylesheet" href="/__aicode/theme.css">
<link rel="stylesheet" href="/__aicode/ui.css">
<script src="/__aicode/vue.global.prod.js"></script>
<script src="/__aicode/aicode-ui-vue.js"></script>
<script src="/__aicode/aicode.js"></script>
```

可用 CSS 变量：

| 变量 | 用途 |
| --- | --- |
| `--aicode-bg` | 页面背景 |
| `--aicode-surface` | 卡片、输入框表面 |
| `--aicode-on-surface` | 正文文字 |
| `--aicode-primary` | 主色 |
| `--aicode-error` | 错误色 |
| `--aicode-success` | 成功色 |
| `--aicode-warning` | 警告色 |
| `--aicode-radius-*` | 圆角 |
| `--aicode-space-*` | 间距 |
| `--aicode-font-scale` | 字号缩放 |

深浅色切换时，宿主直接更新变量，页面不会重新加载。`aicode.applyTheme(variables)` 可在页面内临时覆盖变量，并派发 `aicode:theme` 事件。

### 布局

内容区需要居中并限制最大宽度时，应同时设置 `box-sizing: border-box; width: 100%; max-width: 860px; margin: 0 auto;`，使其从首次显示起即占满可用宽度。若仅设置最大宽度和自动外边距，在纵向 Flex 布局中会按内容收窄，待异步数据返回后才撑开。

```css
.page {
  box-sizing: border-box;
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: 16px;
}
```

## Vue 组件库

在 Vue 应用挂载前注册组件：

```html
<div id="app">
  <aicode-field label="名称">
    <aicode-input v-model="name" placeholder="输入名称"></aicode-input>
  </aicode-field>
  <aicode-field label="描述">
    <aicode-textarea v-model="description" placeholder="输入描述"></aicode-textarea>
  </aicode-field>
</div>

<script>
  const {createApp, ref} = Vue;
  const app = createApp({
    setup() {
      return {name: ref(''), description: ref('')};
    }
  });
  app.use(AicodeUI);
  app.mount('#app');
</script>
```

### 输入框

`aicode-input`、`aicode-textarea` 的外观与 App 输入框一致，包括圆角、描边、文字间距、深浅色、聚焦和禁用状态。

- 每个 `aicode-field` 放置一个输入组件；
- 空值且未聚焦时，标签显示在框内；聚焦或有内容时标签浮到边框处，提示文字在标签浮起后显示；
- 点击标签也可聚焦输入框；
- `disabled` 时底色保持不变，文字与边框变淡；
- `type="password"` 时默认隐藏内容，右侧自动显示明文/隐藏切换按钮；切换仅改变显示方式，保留原内容和光标位置；禁用时切换按钮同样禁用。

### 对话框

`aicode-dialog` 对齐 App 的居中对话框：标题、内容和底部文字按钮分区排列，取消在左、确认在右。

文字确认：

```html
<aicode-dialog v-model="confirmOpen" title="确认操作"
  message="确定继续执行吗？" @confirm="runAction"></aicode-dialog>
```

输入表单：

```html
<aicode-dialog v-model="editOpen" title="修改名称" confirm-text="保存"
  :confirm-disabled="!name.trim()" @confirm="saveName">
  <aicode-field label="名称">
    <aicode-input v-model="name" placeholder="输入名称"></aicode-input>
  </aicode-field>
</aicode-dialog>
```

- 文字确认通过 `message` 显示说明；
- 输入弹窗将输入组件放入默认插槽，输入框底色透出弹窗颜色；
- 内容过长时仅滚动内容区，底部按钮保持可见；
- `confirm-disabled` 为 `true` 时不可确认；
- 确认将触发 `confirm` 并关闭；取消按钮、遮罩、返回键或 Escape 将触发 `cancel` 并关闭；
- 输入值由页面的 `v-model` 管理，取消时是否恢复原值由页面决定。

`aicode-bottom-sheet` 用于底部弹窗内容，窄屏从底部展开，宽屏居中。简单输入和文字确认应直接使用 `aicode-dialog`。

### 自定义全屏弹层

页面自行绘制的全屏弹层，应在打开和关闭时成对调用 `aicode.ui.setOverlay`，由宿主在全局背景图上按矩形挖孔：

```js
const rect = element.getBoundingClientRect();
await aicode.ui.setOverlay(true, {
  left: rect.left,
  top: rect.top,
  width: rect.width,
  height: rect.height,
  topRadius: 16,
  bottomRadius: 16,
  coversBottom: false
});

// 关闭时
await aicode.ui.setOverlay(false);
```

`AicodeBottomSheet`、`AicodeDialog` 打开时会自动上报弹层矩形并调用 `setOverlay(true, rect)`，关闭后恢复。页面自行绘制的弹层不应依赖这两个组件的行为。

## 插件事件

插件进程可主动向页面推送事件：

```js
process.stdout.write(JSON.stringify({
  jsonrpc: '2.0',
  method: 'plugin.event',
  params: {name: 'records.changed', payload: {count: 4}}
}) + '\n');
```

页面侧：

```js
const off = aicode.events.on((event) => {
  if (event.name === 'records.changed') {
    console.log(event.payload);
    refreshRecords();
  }
});
```

- 事件仅发送给同一插件、当前已加载的页面；
- 无页面时丢弃，不排队；
- 不跨插件转发，也不会注入会话或触发新的 AI 推理；
- 页面关闭或宿主销毁时应调用 `off()`。

适用于事件推送的场景：进程后台任务完成、定时器状态、浮层交互结果。

## 文件订阅

所有插件 HTML 页面均可订阅其读取的文件来源，无需增加运行时或声明 hook。订阅仅通知「数据可能发生变化」，由页面决定重读哪些内容；宿主不会重载 WebView。

```js
const stopWatching = await aicode.fs.watch('data://records', {recursive: false}, (change) => {
  if (change.error) {
    console.error(change.error.code, change.error.message);
    return;
  }
  refreshRecords();
});

const stopLifecycle = aicode.events.on((event) => {
  if (event.name === 'ui.lifecycle' && event.payload.visible) {
    refreshRecords();
  }
});

await refreshRecords();

// 页面应用卸载时应清理；暂时退出页面时由宿主自动暂停监听。
await stopWatching();
stopLifecycle();
```

规则：

- `watch(path, options?, handler)` 返回 Promise，解析为取消订阅函数；取消函数返回 Promise，可重复调用；
- 也支持 `watch(path, handler)`；
- 路径沿用 `data://`、`plugin://`、`workspace://`、`aicode://` 四个根，可订阅目录或已存在的文件；
- `recursive` 默认为 `false`；未创建的路径按目录等待；如需监听尚未创建的文件，应订阅其父目录；
- 回调收到 `{subscriptionId, path, contextKey, changes: [{path, kind}], truncated}`，`kind` 为 `created`、`modified` 或 `deleted`；
- 路径始终为插件使用的逻辑 URI，不包含手机私有目录；
- 首次远端扫描成功时也会收到一次 `changes: []` 的校准通知，不应仅在明细非空时刷新；
- `truncated` 为 `true` 时应重新读取整个订阅范围；`error` 存在时表示订阅出现错误，不应据此清空已有数据；
- 本地复用文件系统通知，默认约 300ms 合并一批，另有约 2 秒的文件快照检查兜底；
- SSH 的工作区和配置目录使用约 2 秒的按需远端快照检查，以修改时间和大小判断变化；同一时间精度内、大小也相同的连续改写可能无法区分，重新显示页面时应再次校准；
- 页面可见时监听，离开页面或 App 退到后台时暂停，回到页面时恢复；工作区、执行模式或 SSH 服务器变化时重建；
- 每个页面最多 16 个订阅，应选择必要的小范围，避免递归监听整个工作区；
- 文件访问保护同样适用于订阅；不可监听受保护配置路径，也不得通过订阅整个 `aicode://` 根获取这些路径的变更。

文件订阅仅覆盖指定文件来源，不会自动推断数据库、HTTP API 或其他插件数据的变化。这类数据仍由插件进程通过 `plugin.event` 主动推送，或在页面显示时自行校准。

## 页面生命周期

`ui.lifecycle` 的 payload 为 `{visible, reason, contextKey}`，`reason` 为：

| 值 | 含义 |
| --- | --- |
| `shown` | 页面首次显示 |
| `resumed` | 从后台或隐藏状态回到前台 |
| `hidden` | 页面被隐藏或 App 退到后台 |
| `contextChanged` | 工作区、执行模式或 SSH 服务器变化 |

首次加载仍需主动获取数据；重新显示、回到前台和上下文切换用于校准缓存。不应仅依赖一次 Vue `mounted`。

## 运行事件

页面启动 AI 轮次后，会额外收到宿主的 `chat.run.started` 与 `chat.run.finished` 事件：

```js
aicode.events.on((event) => {
  if (event.name === 'chat.run.finished') {
    console.log(event.payload.sessionId, event.payload.status, event.payload.error);
  }
});
```

`status` 为 `running` / `success` / `error` / `cancelled`；这些事件只说明轮次状态，正文仍通过 `aicode.chat.listMessages` 读取。

`aicode.context()` 返回当前 `contextKey` 和 `visible`。文件方法可在最后一个 `options` 参数中传入 `{contextKey}`；上下文已经变化时请求返回 `STALE_CONTEXT`，用于拦截仍在排队的旧项目操作。异步刷新结果仍应在页面侧核对上下文，正在编辑的输入不得被刷新直接覆盖。

```js
let loading = false;

async function refreshRecords() {
  if (loading) return;
  loading = true;
  try {
    const ctx = await aicode.context();
    const result = await aicode.fs.read('data://records.json', {contextKey: ctx.contextKey});
    if (!ctx.visible) return;
    render(JSON.parse(result.content));
  } catch (error) {
    if (error.code === 'STALE_CONTEXT') return;
    console.error(error.code, error.message);
  } finally {
    loading = false;
  }
}
```

## 限制

- 页面资源仅能从插件包经虚拟域加载，外部链接会被宿主拦截；
- 页面文件最大 512 KiB；
- 每个页面最多 16 个文件订阅；
- 单次读取 512 KiB、写入 1 MiB、目录最多 500 条；
- 页面不可绕过宿主桥直接读取容器文件；
- 主题切换不会重新加载页面；页面应仅依赖 CSS 变量，请勿在脚本中缓存具体颜色值。
