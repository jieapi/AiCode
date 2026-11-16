# Shizuku 与插件提供商

Shizuku 用于执行 Android 系统命令，需要插件级独立授权；插件提供商用于接入 OpenAI 兼容服务，在插件启用后注册到模型设置中。两项能力可按需分别使用。

## Shizuku

### 声明与授权

在 `contributes.shizuku` 中填写用途 `purpose`，字段限制见[清单参考](/advanced/plugin/manifest#shizuku)。声明不会自动获得权限，实际执行需满足：

- Shizuku 服务正在运行。
- App 已获得 Shizuku 的系统授权。
- 使用者在插件详情中开启了该插件的 Shizuku 权限。

授权后，该插件可直接执行 Shizuku 命令，无需逐次确认。用途说明不限制具体命令，仅应为可信插件授权。

### 执行命令

页面调用：

```ts
const result = await aicode.shizuku.exec('settings get global airplane_mode_on', {timeout: 30});
```

进程调用：

```ts
import { hostCall } from '@jieapi/plugin-sdk';

const result = await hostCall('host.shizuku.exec', {
  command: 'settings get global airplane_mode_on',
  timeout: 30
});
```

`timeout` 以秒计，默认为 120。命令在 Android 系统上执行，输出与退出码按宿主返回值处理；权限缺失或服务未运行时会失败并记录日志。浮层页面的调用限制见[系统浮层](/advanced/plugin/overlay#浮层页面)。

## 插件提供商

在 `contributes.providers` 中登记 OpenAI 兼容服务，字段与地址限制见[清单参考](/advanced/plugin/manifest#providers)。宿主复用现有 OpenAI 适配器，插件负责保证服务可访问。

`baseUrl` 可指向固定外部服务，也可指向容器内 `http://127.0.0.1:<port>`。

### 生效方式

插件启用后，宿主在「设置 → 提供商」中创建带「插件」标记的普通条目：

- 使用者可改名、修改地址和凭据、启用或停用。
- 可从兼容服务的 `/v1/models` 拉取并选择模型。
- 服务未就绪时请求直接失败，不会回退到其他渠道。
- 插件停用或卸载时，其注册的条目一并删除。

清单中不要内嵌上游密钥。凭据可由使用者在提供商条目中填写，或由插件通过加密存储维护。

### 由插件维护模型与凭据

页面与进程只能维护本插件声明、且已注册的提供商，无需声明 `contributes.modelAccess`。

| 页面调用 | 进程调用 | 作用 |
| --- | --- | --- |
| `aicode.providers.listModels(providerId)` | `host.providers.listModels({providerId})` | 读取配置，返回 `{providerId, models, selectedModel}`，不访问上游 |
| `aicode.providers.addModel(providerId, model, options?)` | `host.providers.addModel({providerId, model, contextTokens?})` | 追加模型；重复添加视为成功，尚无选中模型时自动选中它 |
| `aicode.providers.deleteModel(providerId, model)` | `host.providers.deleteModel({providerId, model})` | 返回 `{providerId, model, models, selectedModel, removed}`；不存在时 `removed: false`，删除选中模型时回退到剩余模型 |
| `aicode.providers.setModelContext(providerId, model, contextTokens)` | `host.providers.setModelContext({providerId, model, contextTokens})` | 写入模型上下文窗口 |
| `aicode.providers.setApiKey(providerId, apiKey)` | `host.providers.setApiKey({providerId, apiKey})` | 设置单 Key；空串清除，值不回显 |

服务启动后可在页面同步配置：

```ts
await aicode.providers.addModel('gateway', 'local-model', {contextTokens: 32768});
const token = (await aicode.secrets.get('gateway-token')).value;
await aicode.providers.setApiKey('gateway', token);
const {models, selectedModel} = await aicode.providers.listModels('gateway');
```

进程中通过 SDK 调用对应的 `host.providers.*`，参数统一放入对象：

```ts
await hostCall('host.providers.addModel', {
  providerId: 'gateway',
  model: 'local-model',
  contextTokens: 32768
});
```

限制与行为：

- `providerId` 可用清单局部 ID 或 `model.listProviders()` 返回的完整 ID；其他插件或使用者自建条目返回 `PROVIDER_NOT_FOUND`。
- `contextTokens` 为 1–10000000 的正整数，设置后立即影响上下文预算；自动识别不到窗口时应给出准确值。
- 每个提供商最多 500 个模型，模型名最长 200 字符。
- `setApiKey` 由宿主加密落盘；若使用者启用了多 Key 模式，多 Key 列表仍优先于单 Key，应由使用者选择关闭多 Key 或自行维护列表。

### 启动服务

本地兼容服务由插件自行启动，宿主不代为拉起。需要随 App 启动时，使用 `runtime.resident: true` 配合 `app.started` Hook，声明方式见[常驻与自启动](/advanced/plugin/tools-runtime#常驻与自启动)。

Hook 在初始化完成后应尽快返回，服务在进程内继续运行。安装后需要立即使用时，也可提供手动启动动作；服务地址须与 `baseUrl` 一致，并可从 App 访问。
