# Shizuku 与插件提供商

这两项能力均涉及宿主之外的环境：Shizuku 将命令发送至 Android 系统，提供商将插件自身的 OpenAI 兼容服务接入模型列表。二者均需使用者额外确认，不会因启用插件而自动生效。

## Shizuku

插件可声明通过 Shizuku 执行 Android 系统命令的范围。Shizuku 是插件级独立授权：安装或启用插件不会自动获得，必须在插件详情中单独开启。

### 声明

```json
{
  "contributes": {
    "shizuku": {"purpose": "切换免打扰与查询系统设置"}
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `purpose` | 是 | 用途说明，最多 200 字符，展示在授权界面 |

### 授权与执行

授权后，该插件可直接执行任意 Shizuku 命令，无需逐次确认。实际执行还需满足以下条件：

- Shizuku 服务正在运行；
- App 已获得 Shizuku 的系统授权；
- 使用者在插件详情中开启了该插件的 Shizuku 权限。

页面调用：

```js
const result = await aicode.shizuku.exec('settings get global airplane_mode_on', {timeout: 30});
```

进程调用：

```js
const result = await hostCall('host.shizuku.exec', {
  command: 'settings get global airplane_mode_on',
  timeout: 30
});
```

`timeout` 以秒计，默认为 120。命令在 Android 系统上执行，输出与退出码按宿主返回值处理。

### 示例

`plugin-samples/capability-demo` 声明了 `contributes.shizuku`，提供两个 AI 工具：

- `show-tip` 在对话中显示可关闭的提示条；
- `shizuku-command` 通过 Shizuku 执行一条只读 `settings` 命令。

也可在配置页直接触发，以验证授权是否生效。

### 边界

- 声明仅保留用途说明，不限制具体命令；授权后插件可执行任意 Shizuku 命令；
- 授权为插件级开关，并非逐次确认；
- 系统权限缺失或 Shizuku 未运行时，调用将失败并记录日志；
- 仅应为可信插件开启 Shizuku 权限。

## 插件提供商

插件可登记一个 OpenAI 兼容服务，宿主复用现有 OpenAI 适配器，不内置新的上游协议。插件负责启动服务并保证宿主可访问。

### 声明

```json
{
  "contributes": {
    "providers": [
      {
        "id": "gateway",
        "name": {"zh-CN": "本地网关", "en": "Local Gateway"},
        "description": {"zh-CN": "本机 OpenAI 兼容服务"},
        "format": "openai",
        "baseUrl": "http://127.0.0.1:18080/v1"
      }
    ]
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 局部 ID，同一类型内唯一 |
| `name` | 是 | 本地化名称，最多 80 字符 |
| `description` | 否 | 本地化说明，最多 600 字符 |
| `format` | 是 | 第一版仅支持 `openai` |
| `baseUrl` | 是 | `http`/`https` 绝对地址，不能包含账号、查询参数或片段 |

`baseUrl` 可指向固定外部服务，也可指向容器内 `http://127.0.0.1:<port>`。

### 生效方式

插件启用或安装后，宿主将在「设置 → 提供商」中创建带「插件」标记的普通条目。该条目与自建渠道权限相同：

- 可改名、修改地址、修改凭据、启用或停用；
- 可在模型页从兼容服务的 `/v1/models` 拉取并选择模型；
- 服务未就绪时请求将直接失败，宿主不会回退到其他付费渠道；
- 插件停用或卸载时，该插件注册的条目将被删除。

插件不内嵌上游密钥。使用者可在提供商条目中填写凭据，或由插件在本地服务中处理。

### 启动服务

提供商服务需由插件自行启动。常见做法是设置 `resident: true` 并声明 `app.started` hook：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js", "resident": true},
  "contributes": {
    "providers": [
      {
        "id": "echo",
        "name": {"zh-CN": "演示回显服务"},
        "format": "openai",
        "baseUrl": "http://127.0.0.1:18080/v1"
      }
    ],
    "hooks": [{"id": "app-start", "event": "app.started", "handler": "provider.started"}],
    "tools": [
      {
        "id": "start",
        "title": "启动演示提供商",
        "description": "启动插件内置的 OpenAI 兼容回声服务",
        "inputSchema": "schemas/start.input.json",
        "handler": "provider.start"
      }
    ],
    "configPage": "pages/config.html"
  }
}
```

handler 在进程内启动 HTTP 服务并监听 `127.0.0.1:18080`（`schemas/start.input.json` 省略，内容为 `{"type":"object","properties":{}}`）：

```js
const http = require('http');

let server = null;

function startServer() {
  if (server) return;
  server = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/v1/models') {
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({data: [{id: 'echo', object: 'model'}]}));
      return;
    }
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      let payload = {};
      try {
        payload = body ? JSON.parse(body) : {};
      } catch (e) {
        res.writeHead(400, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: {message: 'invalid json'}}));
        return;
      }
      const messages = Array.isArray(payload.messages) ? payload.messages : [];
      const last = [...messages].reverse().find((item) => item.role === 'user');
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({
        id: 'chatcmpl-echo',
        object: 'chat.completion',
        choices: [{
          index: 0,
          message: {role: 'assistant', content: String(last?.content ?? '')},
          finish_reason: 'stop'
        }]
      }));
    });
  });
  server.listen(18080, '127.0.0.1');
}

const handlers = {
  'provider.started': () => {
    startServer();
    return {logs: ['provider listening on 18080']};
  },
  'provider.start': () => {
    startServer();
    return {ok: true};
  }
};
```

`provider-demo` 示例采用相同结构：`app.started` 自动启动；另有一个 AI 工具用于安装后不重启 App 时手动启动；配置页显示服务状态。

### 边界

- 插件负责保证服务运行在宿主可访问的地址；宿主不代为启动服务；
- `format` 目前仅支持 `openai`；
- `baseUrl` 不能包含查询参数或片段，以避免将凭据写入清单；
- 服务未就绪时请求将失败，不会回退到其他渠道；
- 插件停用或卸载将删除已注册的提供商条目；
- 仅应安装可信来源的插件；本地服务同样拥有进程与网络能力。

## 相关

- 进程启动、常驻与依赖准备：[AI 工具与插件运行时](/advanced/plugin/tools-runtime)
- 使用 `app.started` 初始化：[生命周期 Hooks](/advanced/plugin/hooks)
- 浮层页面不得调用 Shizuku：[系统浮层](/advanced/plugin/overlay)
