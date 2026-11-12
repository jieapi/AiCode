# 插件结构规范草案：源码、发布包与 plugin.json

状态：格式草案 v1，供本轮审阅和继续修改，不是已实现 SDK。本文给出具体文件组织、字段和完整清单示例；运行时是容器内 node/python 进程，不再有 structuredUi。不得把示例当作已可安装运行的插件。

## 1. 三层结构

| 层次 | 内容 | 谁消费 |
| --- | --- | --- |
| 插件源码仓库 | 源码、声明、资源、测试、作者构建配置 | 插件作者及其发布流程 |
| 发布包 | 校验后的静态清单、已打包逻辑和贡献资源 | App 安装器与运行时 |
| 索引仓库 | 插件/版本记录、包地址、摘要和权限预览 | App 在线发现与更新流程 |

一个插件一个源码仓库；索引不放插件源码和安装包，见 [11-index-format.md](11-index-format.md)。三者通过插件 ID、版本、源码仓库、发布者和包摘要关联，不按展示名称互相覆盖。

下文 `${...}` 是需作者/发布流程填写的占位符，不是 URL、凭据或运行时模板语法。示例中的外部地址和摘要没有真实值，因此只是结构完整的样本，不是可下载产物。

## 2. 推荐源码目录

```text
workbench-kit/
├── plugin.json
├── package.json
├── package-lock.json
├── tsconfig.json
├── src/
│   ├── main.ts
│   ├── actions.ts
│   ├── hooks.ts
│   └── provider-gateway.ts
├── ui/
│   ├── build.json
│   └── configuration.json
├── configuration/
│   └── defaults.json
├── schemas/
│   └── project-info.input.json
├── skills/
│   └── project-notes/
│       └── SKILL.md
├── agents/
│   └── builder.md
├── mcp/
│   └── project-context.json
├── resources/
│   └── gateway/
│       ├── gateway.js
│       ├── package.json
│       └── package-lock.json
├── tests/
└── LICENSE
```

- 这是 TypeScript 作者项目的推荐组织，不要求 App 认识 `src/`、npm 或具体构建工具。使用 JavaScript 的作者可以没有 TypeScript 配置。
- `plugin.json` 引用发布包里的路径；源码构建把业务入口生成到 `dist/main.js`。
- 一份清单可以同时提供多类贡献，工具插件、构建插件、提供商插件不是互斥的安装包类型。
- `resources/gateway/` 是插件自己的服务资源和锁定依赖输入，不是 App 内置提供商适配器；执行仍需对应能力和已准备环境。

## 3. 发布包目录

格式建议为 ZIP，根目录直接放 `plugin.json`，不再套仓库名这一层。后缀先使用 `.zip`，不另发明自定义压缩格式。

```text
community.workbench-kit-1.0.0.zip
├── plugin.json
├── dist/
│   └── main.js
├── ui/
│   ├── build.json
│   └── configuration.json
├── configuration/
│   └── defaults.json
├── schemas/
│   └── project-info.input.json
├── skills/
│   └── project-notes/
│       └── SKILL.md
├── agents/
│   └── builder.md
├── mcp/
│   └── project-context.json
├── resources/
│   └── gateway/
│       ├── gateway.js
│       ├── package.json
│       └── package-lock.json
└── LICENSE
```

不打包开发依赖目录、Git 数据、缓存或任何用户凭据。App 不运行包管理器/作者构建脚本来得到插件主入口，主逻辑依赖在作者发布前打包。外部服务依赖可在用户点击准备环境后安装到受管理目录，不能在安装预览/解压自动执行。配置、授权和运行数据由宿主另存，不写回已验证发布包，见 [12-service-dependencies.md](12-service-dependencies.md)。

## 4. 身份与版本规则

- `manifestVersion`：清单结构版本，草案为整数 `1`。未知版本拒绝，不猜测解析。
- `id`：稳定插件标识，草案采用 `发布者.插件名`，如 `community.workbench-kit`。两段均以小写字母开头，仅包含小写字母、数字和连字符，中间用一个点分隔；它不是发布者可信证明。
- `publisher`：与插件 ID 第一段一致的稳定发布者标识；改显示名称不改变 ID，改来源/发布身份不能静默继承授权。局部贡献 ID 使用 ASCII 字母/数字/下划线/连字符，以字母开头，不包含路径或命名空间分隔符。
- `version`：SemVer，例如 `1.0.0`；同一版本号不得重新发布不同包内容。
- `engines.pluginApi`：插件 API 版本范围，使用 SemVer 范围，必须包含宿主实现的 `1.0.0`。不再接受 `structuredUi`。
- 安装身份由宿主结合配置的索引来源、源码仓库与核验结果建立，不能只信任清单中的 `id/publisher` 文本。
- 局部贡献 ID 在各自类型内唯一；宿主按插件归属生成内部键与模型工具名，插件不能覆盖内置功能。

## 5. 顶层字段

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `manifestVersion` | 是 | 清单结构版本 |
| `id` / `name` / `version` | 是 | 稳定 ID、展示名称、发布版本 |
| `publisher` / `repository` | 是 | 发布者标识、插件独立源码仓库地址 |
| `description` / `license` | 是 | 用途说明与许可证标识/说明 |
| `engines` | 是 | 所需插件 API，及使用到的 UI 协议版本 |
| `runtime` | 有业务 handler 时 | 逻辑类型和发布包入口；纯静态贡献可省略 |
| `contributes` | 是 | 至少一个有效贡献；未使用的类型省略 |

清单不接受 `official` 作为可信身份，不接受 Key、密码、OAuth 令牌、授权结果或任意 `AUTO_APPROVE` 策略。未知顶层字段/贡献类型按不支持拒绝，不能把安全相关字段悄悄忽略。

### 本地化文本（建议，已在第一切片实现）

`name`、`description` 以及权限项的 `reason` 会显示在宿主可信界面里，而 App 是中英双语，因此这三处都允许写成两种形式：

- 单一字符串：对所有语言生效；
- 语言标签到文本的映射，可含 `default` 键：`{"default": "Demo Kit", "zh-CN": "演示套件"}`。

取值顺序：完整标签（`zh-CN`）→ 语言部分（`zh`）→ `default` → `en` → 第一项。标签统一为小写、`-` 分隔，`zh_CN` 与 `zh-cn` 视为同一个；标签必须是 `zh`、`zh-cn` 这类语言标签，非法标签、空文本、超长文本按字段无效拒绝。`name` 不允许换行，`description` 允许。

页面、配置页里由插件自己渲染的文案如何本地化属于 UI 协议范围，仍待 [06-structured-ui.md](06-structured-ui.md) 定稿，不在此处预设。

## 6. 能力与信任边界

当前清单没有插件权限字段。技能、子代理、工具、页面、hook 与 MCP 随插件启用；插件页面通过宿主桥可以读写工作区与宿主配置目录，并执行命令。

- `permissions.json`、`mcp.json`、`skills/`、`agents/`、`prompts.custom/`、`docs/` 始终禁止插件读写。
- 命令与 MCP 进程内部不受宿主路径限制，不能承诺细粒度沙箱。
- 插件业务进程（AI 工具、hook）在本地容器的插件安装目录中运行，与 Bash 同级；host API 只限制宿主接口的路径，不限制进程自身。
- Shizuku 这类宿主能真正拦截的系统能力，接入时再以独立的高危确认项加入清单。

## 7. contributes 的固定位置

| 字段 | 结构 | 关键引用 |
| --- | --- | --- |
| `tools` | 数组 | `id/title/description/inputSchema/handler` |
| `mcp` | 数组 | `id/file`，文件采用现有 MCP 配置字段，宿主加归属 |
| `skills` / `agents` | 数组 | `id/file`，分别引用现有 Markdown 定义 |
| `pages` | 数组 | `id/title/document/onOpen`；`onOpen` 可省略 |
| `workspacePanel` | 单个对象，可省略 | `page` 引用页面，右抽屉按插件展示入口 |
| `configuration` | 单个对象，可省略 | `page/defaults/saveHandler`，从设置内配置操作打开 |
| `hooks` | 数组 | `id/event/handler/mode`；缺省 `observe`，transform/review 事件必须显式声明对应 mode |
| `permissionReviewer` | 单个对象，可省略 | `handler`，必须已申请 `agent.permissionReview` |
| `environments` | 数组 | `id/target/manager/prerequisites/inputs/lockFiles/checkHandler/prepareHandler` |
| `services` | 数组 | `id/environment/environmentRef/startHandler/stopHandler/exports`，无依赖时可省略 environmentRef |
| `providers` | 数组 | `id/title/format/endpoint/models/authentication` |

这些都是静态声明。注册清单不执行 handler；启用后由宿主建立归属关系，真正触发事件/调用时再加载逻辑并检查权限。页面 onOpen 是一次受控初始化事件，不随重组重复发送；它不自动获得执行构建、安装依赖等副作用的权利，相关业务须由明确动作触发。

贡献可用 `requires` 数组声明依赖的服务或环境；依赖未准备则相关功能不可用；每次调用仍检查插件启用状态，不把 requires 当作已准备证明。服务不因出现在清单中就自动运行。

`providers.requests` 仅在宿主路由确实选用了该插件拥有的提供商实例时生效，并绑定实例、请求与插件版本。它允许处理该请求已发送的内容，不允许枚举历史或主动使用其他渠道消费额度。注册和凭据配置均不能代替启用状态检查。

`mcp.connect` 对宿主 MCP 传输生效；`network.request` 单独约束插件 SDK 发起的请求，两者不隐式互相授予。下载后、真正连接之前，宿主展开并展示该 MCP 配置的实际 origin/入口路径或程序/参数，把包配置摘要、解析后的目标和凭据引用记录为有效范围。目标改变或跨 origin 重定向不沿用原确认；无明确目标则拒绝，不自动追加网络许可。

### Handler 与引用约定

- Handler 是 `runtime.entry` 模块导出的 `handlers` 表里的稳定名称，如 `actions.build`，不是文件路径、脚本片段或任意反射表达式。
- UI 事件、工具、hook、审核和服务均引用同一张表；宿主赋予不同调用上下文，不能因共享代码而共享权限。
- 每种入口有类型化输入/结果，具体 SDK ABI 待后续专题细化；本格式不允许返回可执行对象或直接操作宿主管理器。
- 安装阶段只静态校验 handler 标识与 runtime 文件引用；导出表在隔离运行时实际加载时核对。模块顶层初始化不获得业务副作用调用上下文，不能为验证导出而在安装预览或宿主进程执行代码。
- 审核 handler 的结果固定为 `allow/deny/pass` 加可选原因；只对当前调用生效，`deny` 仅拒绝当前工具。多插件顺序由使用者决定，不设插件自报优先级。
- 工具不能在清单中指定自动批准或凭自报“只读”绕过 PLAN；宿主结合实际授权与调用上下文裁定。

### 提供商与服务引用

`format` 草案为 `openai/anthropic/gemini`，映射 App 当前已有格式；现有请求模式可通过受校验的 `options` 表达，不引入未知上游类型。

`endpoint` 二选一：`{"url":"${COMPATIBLE_API_URL}"}`，或 `{"service":"gateway_service","export":"api"}`。后者引用服务声明的导出项，不把临时端口写死进清单。

服务 `exports` 为名称到类型的映射。HTTP 导出声明 `type: httpEndpoint, audience: app, binding: managed, basePath: /v1/`；startHandler 通过宿主登记受管理的就绪出口句柄，不允许返回一个任意 URL 冒充该实例的服务。宿主把出口绑定到已启用服务实例、执行后端和固定 basePath；本地/SSH 的受控监听或转发如何实现仍待验证，不支持时拒绝导出，不能退回信任任意外部地址。

`startHandler` 仅在插件启用、environmentRef 对应环境已 ready 且需要服务时运行；`stopHandler` 接收该实例的受控清理句柄，不因停用或卸载就继续运行任意插件清理代码。App 消费的地址由宿主解析受控出口，不把远端 localhost 原样作为手机入口。

`authentication.source = serviceToken` 仅用于受管理服务出口，使用宿主为该实例生成并绑定受众的桥接认证，不把上游 Key 当作桥接口令。插件所需上游凭据另走 `credentials.use` 的自有声明。

固定外部入口可使用 `authentication.source = providerInstance`，复用用户明确配置给该实例的认证；宿主将其绑定到已声明 origin 与 basePath，目标改变重新确认，不向跨 origin 重定向转发认证。清单不内嵌口令，也不读取全量渠道。

`models.mode = remote` 表示走当前格式的模型发现流程；静态模型可用 `mode = static, ids = [...]`。这些选项要与实际兼容服务能力匹配，模型发现与请求测试使用相同的受众/目标校验。

**模型元数据（草案补充）。** 只给模型 ID 不够：App 的上下文压缩阈值、思考档位选项、识图/工具能力判断和费用估算都取自 `ModelMetadata`（[ModelMetadata.kt:6](../../app/src/main/java/com/aicode/feature/settings/domain/model/ModelMetadata.kt:6)），而新来源的提供商没有元数据自注册入口，`ProviderType` 又是三值闭集（[AIProviderConfig.kt:138](../../app/src/main/java/com/aicode/feature/settings/domain/model/AIProviderConfig.kt:138)）。模型 ID 撞不上内置目录时只会落到 128k / 64k 的推断默认值，压缩时机和费用都会失真。因此静态模型建议允许携带元数据：

```json
"models": {
  "mode": "static",
  "items": [
    {
      "id": "gateway-pro",
      "contextTokens": 200000,
      "outputTokens": 16000,
      "supportsTools": true,
      "supportsVision": false,
      "supportsReasoning": true,
      "reasoning": {"levels": ["low", "medium", "high"], "canDisable": true},
      "cost": {"inputPerM": 1.0, "outputPerM": 4.0, "cacheReadPerM": 0.1, "cacheWritePerM": 1.25}
    }
  ]
}
```

字段与 `ModelMetadata` 一一对应，单价单位是美元 / 百万 token，缺省项按现有规则回落（缓存读缺省取输入价的 10%，缓存写缺省取 1.25 倍）。元数据按 `providerId:model` 落键，与自定义模型元数据同一键格式，避免跨渠道串价（项目已踩过这个坑）。`remote` 模式下由服务返回元数据或由使用者在现有自定义元数据里补。这里只是格式草案，宿主侧入口尚未实现。

### 服务依赖环境

`environments` 是受管理的服务依赖声明，不是另一个插件运行时。`manager` 草案为 npm/pip；`inputs` 记录包根相对路径与原始字节 SHA-256，`lockFiles` 必须指向 inputs 中的锁定输入。服务 environmentRef 引用同插件环境，服务 environment 必须与 target 一致。

check/prepare 的 JavaScript handler 仍在手机，只有实际检查/安装命令走绑定的工作区后端。prepare 必须有 `environment.prepare` 和其实际使用的执行权限，并来自使用者明确的准备/重试动作；check 不得偷偷安装。缺少 prerequisites 只报告，不自动装系统工具。服务不自动补装依赖再启动，完整状态与严格安装规则见依赖专题。

## 8. 完整 plugin.json 示例

这是一个同时展示各类贡献的组合样本，不表示每个插件都必须具备所有能力。源码仓库/地址等占位符需替换，所有 handler 均由插件作者实现。

```json
{
  "manifestVersion": 1,
  "id": "community.workbench-kit",
  "name": "Workbench Kit",
  "version": "1.0.0",
  "publisher": "community",
  "description": "提供项目操作页面、上下文工具与兼容提供商",
  "license": "MIT",
  "repository": "${PLUGIN_REPOSITORY_URL}",
  "engines": {
    "pluginApi": "^1.0.0"
  },
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js"
  },
  "contributes": {
    "tools": [
      {"id": "project_info", "title": "项目信息", "description": "查询当前项目状态", "inputSchema": "schemas/project-info.input.json", "handler": "tools.projectInfo"}
    ],
    "mcp": [
      {"id": "project_context", "file": "mcp/project-context.json"}
    ],
    "skills": [
      {"id": "project-notes", "file": "skills/project-notes/SKILL.md"}
    ],
    "agents": [
      {"id": "builder", "file": "agents/builder.md"}
    ],
    "pages": [
      {"id": "build", "title": "项目操作", "document": "ui/build.json", "onOpen": "pages.buildOpen"},
      {"id": "configuration", "title": "插件配置", "document": "ui/configuration.json", "onOpen": "pages.configurationOpen"}
    ],
    "workspacePanel": {"page": "build"},
    "configuration": {"page": "configuration", "defaults": "configuration/defaults.json", "saveHandler": "configuration.save"},
    "hooks": [
      {"id": "before_request", "event": "agent.request.started", "handler": "hooks.beforeRequest"},
      {"id": "run_finished", "event": "agent.run.finished", "handler": "hooks.runFinished"}
    ],
    "permissionReviewer": {"handler": "permissions.review"},
    "environments": [
      {"id": "gateway_deps", "target": "currentWorkspace", "manager": "npm", "prerequisites": ["node", "npm"], "requires": ["workspace.commands", "terminal.sessions"], "inputs": [{"path": "resources/gateway/gateway.js", "sha256": "${GATEWAY_SCRIPT_SHA256}"}, {"path": "resources/gateway/package.json", "sha256": "${SERVICE_PACKAGE_SHA256}"}, {"path": "resources/gateway/package-lock.json", "sha256": "${SERVICE_LOCK_SHA256}"}], "lockFiles": ["resources/gateway/package-lock.json"], "checkHandler": "environments.gatewayCheck", "prepareHandler": "environments.gatewayPrepare"}
    ],
    "services": [
      {"id": "gateway_service", "environment": "currentWorkspace", "environmentRef": "gateway_deps", "requires": ["workspace.commands", "terminal.sessions"], "startHandler": "services.gatewayStart", "stopHandler": "services.gatewayStop", "exports": {"api": {"type": "httpEndpoint", "audience": "app", "binding": "managed", "basePath": "/v1/"}}}
    ],
    "providers": [
      {"id": "gateway", "title": "Plugin Gateway", "requires": ["providers.requests", "credentials.use"], "format": "openai", "endpoint": {"service": "gateway_service", "export": "api"}, "options": {"useResponseApi": false}, "models": {"mode": "remote"}, "authentication": {"source": "serviceToken"}}
    ]
  }
}
```

示例的可选提供商依赖 `providers.register`、`providers.requests`、已手动准备的 gateway_deps 环境、其服务实际使用的命令/终端能力和需要时的自有凭据授权；未授权时不能仅因声明存在就启动服务。MCP 文件里的真实地址、上游转换和依赖检查由插件提供，宿主不包含这些业务。

示例里的 hooks 已按当前实现改成 `{id, event, handler}`；`agent.hooks` 的 `data` 等字段仍是后续草案。当前版本接受八个只读事件（App 启动、运行开始/结束、请求开始/结束、工具开始/结束、上下文压缩）与三个等待型事件，以第 12 节为准。

## 9. 页面文件的结构连接示例

下面仅固定页面外壳、状态引用和事件关联的草案，用于把清单与 UI 连起来；完整布局/组件属性与变更协议仍需在 UI 规范中细化，不代表当前 DIY 已支持。

`ui/build.json`：

```json
{
  "uiVersion": 1,
  "id": "build",
  "initialState": {"form": {"task": "build"}, "status": "idle", "canRun": true},
  "root": {
    "id": "root",
    "type": "Column",
    "children": [
      {"id": "task", "type": "TextInput", "label": "任务", "value": {"bind": "form.task"}, "onChange": {"set": "form.task"}},
      {"id": "run", "type": "Button", "text": "运行", "enabled": {"bind": "canRun"}, "onClick": {"emit": "run"}},
      {"id": "status", "type": "Text", "text": {"bind": "status"}}
    ]
  },
  "events": {
    "run": {"handler": "actions.build", "arguments": {"task": {"bind": "form.task"}}}
  }
}
```

事件参数在真实事件触发时读取一致状态快照。`set` 仅改该页面状态，不授予权限；`emit` 只调用已声明的 handler，不执行声明中的命令字符串。`actions.build` 由插件决定做什么并调用受控 Bash/terminal；重组、绑定求值与刷新不执行它。

## 10. 包与引用校验

- 清单、UI、schema、Markdown 与资源引用统一相对于发布包根目录；禁止绝对路径、`..`、反斜杠混淆、符号/硬链接和重复 ZIP 路径。
- 贡献 ID、页面引用、服务引用和 export 必须存在且类型匹配；同名局部 ID 不重复。
- 包含 handler 的贡献必须有 runtime；只静态注册内容的包不需要业务入口。MCP 配置等外部资源的端点、启动参数和凭据引用还需在下载后展示实际授权范围；不能仅凭索引里的局部 ID 掩盖目标变化。
- input schema 仅使用允许的本地引用，不在安装预览阶段追踪远端引用或执行代码。
- 安装预览展示的贡献与实际清单一致；引用缺失文件或 handler 的诊断不能忽略错误继续启动。
- 宿主先核验包摘要/清单摘要，再读取并校验声明；读取声明不触发模块顶层执行、MCP 连接、服务启动或脚本安装。
- 更新保持 `id/version/repository/publisher` 与索引记录一致。服务地址的运行期导出绑定插件实例与启用版本，不因同名端口复用而信任另一进程。
- 资源上限、实际 runtime 支持、权限作用域、hook 可变字段与任务退出策略仍按宿主契约验证，不能因为清单可解析就声称这些实现完成。

## 11. 作者发布顺序

1. 在独立源码仓库实现 handler、声明与测试。
2. 发布前生成业务 bundle，按本节目录组织包，确认清单引用全部存在。
3. 计算最终 ZIP 与包内 `plugin.json` 的 SHA-256，关联源码 commit/tag；发布后不修改同版本内容。
4. 在索引仓库提交相应条目与版本记录，完整复制权限预览并绑定同一产物，见索引规范。
5. 使用者通过 App 在线流程安装；非官方每次安装/更新仍手输源码仓库地址，清单不提供免确认字段。

## 12. 当前实现范围

上面的格式是完整草案；宿主现在只实现了声明式贡献、AI 工具运行时、MCP 注册、只读 hooks 与工具前后的可写 hooks，其余一律在安装时明确拒绝，不静默忽略。第一、第二切片已提交；第三切片（AI 工具）已真机验证；第四切片（MCP）已实现，冒烟编译与插件单测通过，待真机验证；第五切片（只读 hooks）已实现，冒烟编译、插件单测与真机验证均通过（2026-11-10）；可写 hooks 第一版（工具前后）已实现，验证状态见 00 第 8 节。

### 第一切片（已提交）：技能与子代理

| 项 | 行为 |
| --- | --- |
| 顶层字段 | `manifestVersion`（必须为 1）、`id`、`name`、`version`、`publisher`、`repository`、`description`、`license`、`engines`、`contributes` |
| `engines` | `pluginApi` 必填，范围必须包含宿主实现的插件 API（当前 `1.0.0`）；不再接受 `structuredUi` |
| 版本范围写法 | `^x.y.z`、`~x.y.z`、`>= > <= < =` 加完整版本、精确版本，空格分隔取交集；不支持 `1.x`、`*`、`\|\|`、`a - b` |
| 能力 | 技能与子代理随插件启用生效；逐项能力字段已移除 |
| 贡献 | `contributes.skills[]`、`contributes.agents[]`，每项 `{id, file}`，`file` 为包内 `.md` |
| `repository` | 必须是不带账号、查询、片段的 `https` 地址；只作为插件自述展示，**不核验** |
| 安装路径 | 设置 → 插件 → 「＋」选择本地 `.zip`。这是目前唯一的安装路径，没有在线索引 |

### 第二切片（已提交，`c0223c8e`）：工作区工具

| 项 | 行为 |
| --- | --- |
| 能力 | 工作区工具随插件启用生效；逐项能力字段已移除 |
| 贡献 | `contributes.workspaceTools[]`，每项 `{id, title, pageFile}`，`icon` / `category` 可选；`pageFile` 为包内 `.html` |
| 页面 | `pageFile` 由 WebView 加载，缺失或越出包目录时报错；页面通过宿主桥读写文件、执行同步 `bash`，或用 `terminal.*` 创建共享终端标签 |
| 数据 | 当前项目已启用的工具与参数保存在 `<workspace>/.aicode/tools.json`；插件启停、安装、卸载时重新读取该文件，不会自动移除或刷新已添加条目 |
| UI | 右侧项目工具抽屉、全部工具页、工具详情页；提交只把命令模板发到终端标签，没有输出流与运行状态 |

### 第三切片（历史，JavaScriptSandbox 已弃用）：AI 工具与运行时

| 项 | 行为 |
| --- | --- |
| 顶层字段 | 新增可选 `runtime`，只接受 `{"type": "javascript", "entry": "<包内 .js>"}` |
| 能力 | 工具随插件启用生效；逐项能力字段已移除 |
| 贡献 | `contributes.tools[]`，每项 `{id, title, description, inputSchema, handler}`；`inputSchema` 为包内 `.json`，`handler` 形如 `tools.echo` |
| 运行时 | AndroidX JavaScriptSandbox 1.1.1；每个插件一个 isolate，共用 App 的单个 sandbox 进程 |
| handler 协议 | 入口设置 `globalThis.aicode.handlers`，宿主注入 `__aicodeInvoke` 调用；参数用转义后的 JSON 字符串传入 |
| 工具名 | 宿主生成 `plugin__<插件ID>__<工具ID>`（清洗并限长 64），不会与内置工具/MCP 重名 |
| 授权 | 工具默认 ASK；PLAN 模式按 `EXTERNAL_TOOL` 拦截；执行前复核插件已启用 |
| 结果 | handler 返回的 JSON 作为工具结果；异常、超时、沙箱终止作为工具错误返回 |
| 已验证 | `agent.run.finished` 元数据与 Hook → isolate 状态共享（第一版两个事件）；扩到七个事件后仅编译/单测通过 |
| 未验证 | started/finished 顺序、取消路径、设备 WebView 支持矩阵、Promise 返回、真正终止死循环、崩溃恢复 |

### 第五切片（已实现，编译/单测通过，待真机验证）：容器内进程运行时

| 项 | 行为 |
| --- | --- |
| 顶层字段 | `runtime` 接受 `{type, entry, command?, dependencies?, resident?}`；`type` 为 `javascript`（node）或 `python`（python3）；`dependencies` 为 `{tool: npm|pip, file}`；`resident: true` 不参与空闲回收 |
| 运行位置 | 本地容器内子进程，cwd 为插件安装目录；SSH 模式下仍在手机端 |
| 协议 | stdin/stdout NDJSON JSON-RPC；宿主发 `tool.invoke`/`hook.invoke`/`ui.invoke`；插件可发 `host.fs.*`、`host.bash`、`host.terminal.*`、`host.notify` 与 `plugin.event` |
| 依赖 | 详情页手动「准备依赖」：npm `npm ci`/`npm install`，pip 创建 `.venv`；缺少运行时只提示，不自动装系统包 |
| 生命周期 | 按插件版本惰性启动，空闲 10 分钟回收（`runtime.resident: true` 跳过），停用/卸载/版本替换关闭；单次调用 120s，观察 hook 30s，等待型 hook 10s/合计 20s |
| 事件 | `plugin.event` 推送到同插件已打开的页面（`aicode.events.on`），不跨插件转发；页面用 `aicode.invoke` 调同插件进程的 `ui.invoke`，单次 60 秒 |
| 安全 | 启用即信任；进程拥有容器内文件、网络与命令能力；host API 不构成安全边界 |
| 旧实现 | 本文第三切片的 JavaScriptSandbox/isolate 记录为历史，不再使用 |

### 第四切片（已实现，编译/单测通过，待真机验证）：MCP 服务器

| 项 | 行为 |
| --- | --- |
| 能力 | MCP 随插件启用生效；逐项能力字段已移除 |
| 贡献 | `contributes.mcp[]`，每项 `{id, file}`，`file` 为包内 `.json` |
| 文件格式 | 沿用现有 `{"mcpServers": {...}}`，支持 HTTP（`url`）与 stdio（`command`/`args`/`env`），由 `McpConfigParser` 解析 |
| 命名空间 | server 名生成 `plugin__<插件ID>__<贡献ID>__<原server名>`，用户同名配置优先 |
| 连接 | 插件生效时加入 `McpManager`；停用、卸载会重连并移除工具 |
| 工具名 | 沿用 `mcp__<server名>__<工具名>`，server 名已带插件命名空间 |
| 未验证 | stdio 在本地容器/SSH 的启动、OAuth、插件更新时的连接切换 |

### 第五切片（已实现）：生命周期 hooks

| 项 | 行为 |
| --- | --- |
| 生效 | 插件启用即生效，没有逐项权限系统 |
| 贡献 | `contributes.hooks[]`，每项 `{id, event, handler}`；同类 id 唯一；`handler` 是插件进程 handler 表里的键，由 `hook.invoke` 调用 |
| 运行时 | hooks 需要 `runtime`；没有运行时的包不能声明 hooks |
| 事件 | `app.started`、`agent.run.started` / `agent.run.finished`、`agent.request.started` / `agent.request.finished`、`agent.tool.started` / `agent.tool.finished`、`agent.compaction` |
| payload | `app.started` 为 `launchId`/`startedAt`/`appVersion`/`process`，无 run/session 字段；其余事件公共字段 `runId`、`sessionId`、`mode`、`scope`（`main`/`subagent`）、`agentName`；run 事件带 `status`/`toolCalls`/`errorCode`；请求事件带 `requestId`/`usage`/`durationMs`/`stopReason`/`estimatedInputTokens`；工具事件带 `callId`/`toolName`/`status`/`durationMs`；压缩事件带 `compacted`/`estimatedInputTokens` |
| 派发 | 宿主异步派发；handler 可返回 `{logs:[...]}` 写应用日志（最多 50 行、单行 500 字符），其余返回值被忽略；失败只记日志，不阻塞 Agent，不改变本轮结果 |
| 边界 | 只读：不能修改请求、消息、工具参数或响应，不能自动批准任何工具，不能影响工具授权与模式限制 |
| 未验证 | run 事件曾真机验证；扩展的请求/工具/压缩事件尚未真机验证 |

### 第五切片补充（已实现）：可写与代审核 hooks

| 项 | 行为 |
| --- | --- |
| 贡献 | `contributes.hooks[]` 增加可选 `mode`；`observe` 为缺省，`transform` 必须显式声明 |
| 事件 | `agent.tool.before`、`agent.tool.after`、`agent.http.before` 只接受 `mode: "transform"`；`agent.permission.review` 只接受 `review`；八个只读事件只接受 `observe` |
| `agent.tool.before` | 在工具权限判定前同步等待；payload 含 `callId`、`toolName`、`arguments`；返回 `{decision:"allow"|"deny", reason?, arguments?}`。拒绝时工具错误码 `PLUGIN_HOOK_DENIED`；参数改写后重新走完整权限判断 |
| `agent.tool.after` | 仅成功执行后同步等待；payload 含 `callId`、`toolName`、`status=success`、`data`；返回 `{data}`，只能改 `data`，不能改状态、图片、附件或错误结果 |
| `agent.http.before` | 挂在 AgentModule 共享 OkHttpClient 上，每次实际发出 provider 请求前同步等待；payload 含 `url`、`method`、`headers`、`body`；返回 `{url?, headers?, body?}`。body 是该 provider 的完整请求 JSON，系统提示词/消息/工具定义/模型参数均可改，但宿主不校验 provider 语义、工具注册一致性、签名或预算。覆盖对话、标题、压缩、模型列表/测试与图片请求；headers 含明文鉴权头 |
| `agent.permission.review` | `mode: "review"`，只在宿主判定为 ASK 后、弹窗前同步等待；payload 含 `callId`、`toolName`、`arguments`；返回 `{decision:"allow"|"deny"|"pass", reason?}`。首个 allow/deny 生效，pass 继续；allow 只批准当前调用、不写记忆规则；deny 错误码 `PLUGIN_REVIEW_DENIED`。已开放全部 ASK（含 Shizuku、elevate、配置修改、删除子代理、危险 rm）；`planMode` 与自身工具不代审；超时/异常/格式错误按 pass，最终回退 UI |
| 顺序与预算 | 按插件 ID 升序、清单内顺序执行；单 hook 10 秒、合计 20 秒；超时/异常/格式错误保持原值继续，不自动批准 |
| 校验 | 只接受白名单字段；`arguments` 必须是 JSON 对象，`data` 可为任意 JSON；变更 JSON 上限 256 KiB；拒绝理由截断到 500 字符。HTTP：URL 限 http/https 且 ≤4096 字符，headers ≤64 项且禁止 Host/Content-Length/Transfer-Encoding/Connection/Expect/Upgrade，body 必须是 JSON 对象且 ≤4 MiB；方法固定 POST |
| 边界 | 不能改工具名、不能批准工具、不能把宿主 DENY 改成 ALLOW、不能绕过 PLAN 与权限策略；审批后不悄悄改写参数。HTTP hook 不能改方法与非 JSON body，改写后的响应仍按原 provider 格式解析；重试/换 Key 会重复执行 |
| 未验证 | 冒烟编译与插件单测通过后需补真机验证 |

### 校验规则（实现里的硬规则）

- 未知顶层字段、未实现的字段、未实现的贡献类型与能力：整体拒绝。已实现 `runtime`、`contributes.tools`、`contributes.mcp`、`contributes.hooks`（`observe` / `transform`）。
- hooks 声明的事件必须来自宿主支持的事件集合；事件与 mode 一一对应：`agent.tool.before` / `agent.tool.after` / `agent.http.before` 必须 `transform`，`agent.permission.review` 必须 `review`，其余只读事件必须 `observe`；同类贡献 ID 不得重复。
- `publisher` 必须等于 `id` 第一段；`id` 形如 `发布者.插件名`，两段都是小写字母开头、仅含小写字母 / 数字 / 连字符。
- 包内路径禁止绝对路径、`.` / `..` 段、空段、反斜杠、控制字符，长度 ≤ 200、深度 ≤ 8；贡献文件必须存在、位于包内、不超过 512 KB。
- ZIP：压缩包 ≤ 16 MiB、≤ 512 个条目、单条目 ≤ 4 MiB、解压总量 ≤ 32 MiB；重复条目（含仅大小写不同）拒绝；清单 ≤ 256 KiB 且必须在根目录。限额在流式读取时执行，不信任条目自报的大小。所有条目都按普通文件写出，ZIP 里的符号链接与权限位被忽略。
- 技能 / 子代理的清单名由宿主命名空间化为 `<插件ID>:<局部ID>`，frontmatter 里的 `name` 被忽略，因此不会覆盖内置功能、使用者自建内容或其他插件。用户自建技能名不允许含 `:`，二者不会撞名；万一撞名，使用者自建的优先。
- 描述会进入系统提示词的技能 / 子代理清单，因此压成单行，避免插件借换行伪造清单条目。
- 子代理定义不允许带 `provider` / `model`：它们会动用使用者的付费渠道，插件子代理一律继承父会话。`mode` 仍只能收紧不能放大（既有规则）。
- 工作区工具项必须有合法 `id`、非空 `title` 与安全的包内 `.html` `pageFile`；同类 ID 不得重复。
- `workspaceTools` 项内的未知键目前不拒绝，与 `skills` / `agents` 的严格键过滤不一致；`icon` / `category` 不校验长度与控制字符，`category` 默认硬编码中文「通用」且不可本地化。
- 页面是包内 HTML；WebView 只加载包内资源，相对路径不能越出安装目录。
- 工作区工具添加到项目时会把标题、说明、图标、分类写入 `tools.json`；之后插件更新不会自动刷新这些字段，需要从项目移除后重新添加。

### 启用与状态

- 启用即信任：安装确认展示插件提供的贡献与高危能力，安装后全部生效，没有逐项能力授权。
- 同 ID 再次导入视为替换安装，按新包重新确认，不继承旧状态。
- 停用插件后所有贡献与页面入口失效；卸载删除包、数据与安装记录。
- 安装记录（启停、包摘要）由宿主独占，保存在 `filesDir/plugin-registry.json`，不在插件包目录里，也不进入容器可写范围；插件包与插件数据位于 `~/.aicode/plugins`（本地容器内可直接访问）。
- 每次读取已安装插件都会用记录里的清单摘要校验 `plugin.json`；摘要不符、文件缺失或内容无法解析时标记“已损坏”并让其贡献失效。清单不再通过校验（例如宿主插件 API 升级后不兼容）标记“不可用”。
- 已派出去的子会话仍能还原自己的子代理定义（停用只拦新的派发）。
- 安装记录与插件数据**不进入备份**；换机后需重新导入。

### 暂未实现

`environments`、`services`、`providers` 等贡献；hooks 的响应 transform 与更多可变字段；tips；Shizuku；在线索引安装与更新、官方标识；Skill 随包脚本。工作区工具页面可用 `bash` 同步执行，或用 `terminal.*` 创建共享标签；宿主不提供统一运行面板，运行状态由插件页面轮询展示。AI 工具与 hooks 的运行时设备兼容、强终止和崩溃恢复仍待验证，归入 [00](00-overview.md) 第 8.4 节。技能设置页与子代理设置页目前不列出插件内容（它们是可编辑文件列表），插件提供的内容在“设置 → 插件 → 详情”里查看。
