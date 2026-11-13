# 快速开始：创建第一个插件

本篇创建两个可导入的插件：一个仅含技能的声明式插件，和一个带 AI 工具的运行时插件。完成后可掌握目录结构、清单编写、打包、导入与调试流程。

## 1. 创建目录

创建一个空目录，并将 `plugin.json` 直接放在目录根部：

```text
hello-plugin/
└── plugin.json
```

请勿在外层再嵌套一层 `hello-plugin/`。导入时，宿主仅识别压缩包根目录中的 `plugin.json`；嵌套目录会导致提示「压缩包根目录里没有 plugin.json」。

## 2. 最小声明式插件：仅添加一个技能

技能是一个 Markdown 文件，AI 会在适当的时机按需加载。首先编写 `skills/hello/SKILL.md`：

```markdown
---
description: 当用户要求向插件示例问好，或需要验证插件技能是否生效时使用
---

用一句话向用户问好，并说明这条回复来自插件的技能文件。
```

然后编写 `plugin.json`：

```json
{
  "manifestVersion": 1,
  "id": "demo.hello",
  "name": {"default": "Hello Plugin", "zh-CN": "问好插件"},
  "version": "1.0.0",
  "publisher": "demo",
  "repository": "https://github.com/example/hello-plugin",
  "description": {"default": "A minimal skill-only plugin.", "zh-CN": "仅包含一个技能的最小插件。"},
  "license": "MIT",
  "engines": {"pluginApi": "^1.0.0"},
  "contributes": {
    "skills": [{"id": "hello", "file": "skills/hello/SKILL.md"}]
  }
}
```

规则摘要：

- `id` 是 `发布者.插件名`，两段均以小写字母开头，仅含小写字母、数字和连字符，各不超过 32 个字符；
- `publisher` 必须等于 `id` 的第一段；
- `repository` 必须是 `https://` 地址，不能包含账号、查询参数或片段；
- `name`、`description` 可写成字符串，也可按语言写成对象；
- `contributes` 至少包含一项。

技能在 AI 工具清单中显示为 `demo.hello:hello`。`description` 决定 AI 何时加载该技能，应明确说明「何时使用」；文件中的 `name` 会被忽略，正文不得为空。

## 3. 添加 AI 工具：需要 runtime

AI 工具的业务逻辑运行在容器内的插件进程中。需要增加以下三项：

```text
hello-plugin/
├── plugin.json
├── dist/
│   └── main.js
├── schemas/
│   └── echo.input.json
└── skills/
    └── hello/
        └── SKILL.md
```

工具入参使用标准 JSON Schema，`schemas/echo.input.json`：

```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string", "description": "要回显的文本"}
  },
  "required": ["text"]
}
```

清单中增加 `runtime` 和 `contributes.tools`：

```json
{
  "runtime": {"type": "javascript", "entry": "dist/main.js"},
  "contributes": {
    "skills": [{"id": "hello", "file": "skills/hello/SKILL.md"}],
    "tools": [
      {
        "id": "echo",
        "title": {"default": "Echo", "zh-CN": "回显"},
        "description": {"default": "Echo the given text.", "zh-CN": "将传入的文本原样回显。"},
        "inputSchema": "schemas/echo.input.json",
        "handler": "tools.echo"
      }
    ]
  }
}
```

`handler` 是入口脚本导出的 handler 表中的键，而非文件路径。`dist/main.js` 实现宿主与插件之间的按行 JSON-RPC：

```js
'use strict';

const readline = require('readline');

let hostSeq = 0;
const hostPending = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve, reject });
    send({ jsonrpc: '2.0', id, method, params: params || {} });
  });
}

const handlers = {
  'tools.echo': (args) => ({ text: String(args.text ?? '') })
};

function handleRequest(id, method, params) {
  if (method !== 'tool.invoke' && method !== 'hook.invoke' && method !== 'ui.invoke') {
    send({ jsonrpc: '2.0', id, error: { code: 'METHOD_UNKNOWN', message: method } });
    return;
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    send({ jsonrpc: '2.0', id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
    return;
  }
  Promise.resolve()
    .then(() => handler(params.args || {}))
    .then((result) => send({ jsonrpc: '2.0', id, result: result === undefined ? null : result }))
    .catch((error) => send({
      jsonrpc: '2.0',
      id,
      error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) }
    }));
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (e) {
    return;
  }
  if (message.method && message.id !== undefined) {
    handleRequest(message.id, message.method, message.params || {});
  } else if (message.method) {
    return;
  } else if (message.id !== undefined) {
    const pending = hostPending.get(String(message.id));
    if (!pending) return;
    hostPending.delete(String(message.id));
    if (message.error) pending.reject(new Error(message.error.message || 'host error'));
    else pending.resolve(message.result === undefined ? null : message.result);
  }
});
```

工具对 AI 暴露的名称形如 `plugin__demo_hello__echo`。首次调用会显示工具授权提示，确认后在容器中启动插件进程执行。

`runtime` 支持 `javascript`（使用 `node` 启动）和 `python`（使用 `python3` 启动）；也可通过 `command` 覆盖启动命令。容器缺少 node 或 python3 时，请先在终端完成「环境安装」。

## 4. 打包

在插件目录中执行，注意命令末尾的 `.`：

```bash
cd hello-plugin
zip -r ../hello-plugin-1.0.0.zip .
```

容器中未安装 `zip` 时，可使用 Python：

```bash
python3 -c "import shutil; shutil.make_archive('../hello-plugin-1.0.0', 'zip', '.')"
```

打包前请检查压缩包根目录：打开 zip 后应直接看到 `plugin.json`，而非 `hello-plugin/plugin.json`。

## 5. 导入

将 `.zip` 文件传输到手机，打开「设置 → 插件」，点击右上角导入并选择文件。安装确认页将列出插件提供的能力，确认后启用。

导入限制（任意一项超出都会拒绝整个包）：

| 项 | 限制 |
| --- | --- |
| 压缩包大小 | 16 MiB |
| 文件数量 | 512 个 |
| 单个文件 | 4 MiB |
| 解压后总大小 | 32 MiB |
| 路径 | 不能是绝对路径，不能含 `..`、`.`、空段或反斜杠，长度不超过 200，层级不超过 8 |

技能与子代理的 Markdown、`plugin.json`、工具 schema、MCP 配置另有更小的单文件上限，详见[打包、导入与排错](/advanced/plugin/packaging)。

## 6. 开发者侧载

反复打包并导入效率较低。开发期间可将插件目录直接放入容器：

1. 在「设置 → 插件」中点击右上角设置按钮，在底部「插件设置」中开启「开发者侧载」；
2. 将项目目录放入容器的 `~/.aicode/plugin-dev/<项目名>/`，每个目录均须包含 `plugin.json`；
3. 在弹层中点击「安装」或「重载」。开启期间保存文件将自动重载。

侧载不参与在线更新，普通 `.zip` 导入仍可使用。打包会跳过 `node_modules`、`.git` 等目录；依赖应通过 `runtime.dependencies` 准备，或在发布前自行 bundle。

本地模式下，`~/.aicode/plugin-dev` 即容器内的路径；远程 SSH 模式下，该路径位于远端服务器的用户 home 目录下。

## 7. 查看日志

插件进程的启动、调用失败和 hook 超时会写入应用日志。请打开「设置 → 日志」并搜索插件 ID 或 `Plugin` 关键字。handler 也可主动返回调试输出：

```js
const handlers = {
  'tools.echo': (args) => ({
    text: String(args.text ?? ''),
    logs: ['echo called', 'length=' + String(args.text ?? '').length]
  })
};
```

`logs` 最多 50 行、单行 500 字符，将写入应用日志，不会展示给 AI。

## 8. 后续步骤

- 查阅字段：参见 [plugin.json 清单参考](/advanced/plugin/manifest)；
- 添加技能、子代理或 MCP：参见[技能、子代理与 MCP](/advanced/plugin/skills-agents-mcp)；
- 编写更多工具或调用宿主能力：参见[AI 工具与插件运行时](/advanced/plugin/tools-runtime)；
- 构建界面：参见[插件页面与宿主接口](/advanced/plugin/ui-pages)；
- 拦截工具调用或实现代审核：参见[生命周期 Hooks](/advanced/plugin/hooks)；
- 打包失败或导入报错：参见[打包、导入与排错](/advanced/plugin/packaging)。
