# 插件结构规范草案：源码、发布包与 plugin.json

状态：格式草案 v1，供本轮审阅和继续修改，不是已实现 SDK。本文给出具体文件组织、字段和完整清单示例；JavaScript 是推荐的逻辑载体，具体引擎尚待验证。不得把示例当作已可安装运行的插件。

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
- `engines.pluginApi` / `engines.structuredUi`：插件 API 与 UI 协议版本范围，草案使用 SemVer 范围。没有 UI 的插件可省略后者。
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
| `permissions` | 是，可空数组 | 明确的能力申请、必要性、用途与范围 |
| `contributes` | 是 | 至少一个有效贡献；未使用的类型省略 |

清单不接受 `official` 作为可信身份，不接受 Key、密码、OAuth 令牌、授权结果或任意 `AUTO_APPROVE` 策略。未知顶层字段/贡献类型按不支持拒绝，不能把安全相关字段悄悄忽略。

### 本地化文本（建议，已在第一切片实现）

`name`、`description` 以及权限项的 `reason` 会显示在宿主可信界面里，而 App 是中英双语，因此这三处都允许写成两种形式：

- 单一字符串：对所有语言生效；
- 语言标签到文本的映射，可含 `default` 键：`{"default": "Demo Kit", "zh-CN": "演示套件"}`。

取值顺序：完整标签（`zh-CN`）→ 语言部分（`zh`）→ `default` → `en` → 第一项。标签统一为小写、`-` 分隔，`zh_CN` 与 `zh-cn` 视为同一个；标签必须是 `zh`、`zh-cn` 这类语言标签，非法标签、空文本、超长文本按字段无效拒绝。`name` 不允许换行，`description` 允许。

页面、配置页里由插件自己渲染的文案如何本地化属于 UI 协议范围，仍待 [06-structured-ui.md](06-structured-ui.md) 定稿，不在此处预设。

## 6. 权限项结构

每项包含 `id`、`required`、`reason`、`scope`：

- `id`：宿主认识的能力标识，不是插件自己定义的新权限。
- `required`：任一必要能力被拒绝时，插件保持已安装但未启用，宿主管理/授权/卸载仍可用；可选能力被拒绝时相关贡献不可用，不能静默降级成绕过检查。
- `reason`：向使用者解释用途的文字，不是授权依据。
- `scope`：该权限类型规定的结构化范围；宿主根据实际请求核验，插件自报范围不成为事实证明。

草案能力名与范围如下；实现时必须逐项实现校验，不提供任意通用方法调用：

| 能力 ID | scope 草案 | 边界 |
| --- | --- | --- |
| `tools.register` | `ids: [局部工具ID]` | 仅注册自身工具，不赋予执行副作用 |
| `mcp.register` / `mcp.connect` | `ids: [局部MCP ID]` | 注册和连接/启动分别申请；连接授权覆盖该 ID 已核验配置的实际端点/启动范围，不是任意改址许可，stdio 仍需实际进程权限 |
| `skills.register` / `agents.register` | `ids: [局部ID]` | 仅注册自身内容，不扩大派生 Agent 权限 |
| `providers.register` | `ids: [局部提供商ID]` | 仅注册兼容入口，启动服务与请求/凭据另校验 |
| `providers.requests` | `ids: [局部提供商ID]` | 处理实际选用这些提供商实例的请求/响应，不获得其他实例或任意会话历史 |
| `ui.pages` | `ids: [页面ID]` | 提供自身页面，不替换宿主权限/安装 UI |
| `storage.plugin` | `access: readWrite` | 仅自身配置/运行数据，具体全局/项目作用域仍待定 |
| `workspace.commands` | `target: currentWorkspace` | 路由到当前工作区后端，不承诺 shell 的文件/网络隔离 |
| `terminal.sessions` | `target: currentWorkspace, access: owned` | 新建共享标签并操作自有句柄，不接管用户已有标签 |
| `environment.prepare` | `ids: [环境ID]` | 仅用户手动准备的插件服务依赖；不自动安装解释器或系统工具 |
| `agent.events` | `events: [事件名], data: metadata/content` | metadata 只给状态/关联信息；content 还须按事件契约和作用域授权，不能因监听事件就读取完整会话 |
| `agent.transform` | `events: [事件名]` | 仅宿主明确支持的变更契约；字段未定的事件不能据此任意改写 |
| `agent.permissionReview` | `tools: [工具匹配项]` | 只处理宿主 ASK；同意/拒绝/不处理，不修改自身授权 |
| `notifications.post` / `shizuku.execute` | `target: device` | 均单独授权；Shizuku 具体操作粒度仍待定 |
| `ui.tips` | `target: app` | 在 App 内显示来源明确的提示；限频，不得伪装成系统或授权界面；不含 Android 系统通知（那是 `notifications.post`） |
| `credentials.use` | `access: owned` | 仅用户明确交给该插件/实例的凭据，不读取其他渠道 |
| `network.request` | `hosts: [主机]` | SDK 网络调用的目标；不能限制已授权任意 shell 内部的全部网络行为 |

注册描述与实际调用权限不混同；授予 `workspace.commands` 不自动授予通知、Shizuku、其他终端或渠道凭据。模型调用经插件工具进入时仍保留原 Agent 上下文和模式限制。

## 7. contributes 的固定位置

| 字段 | 结构 | 关键引用 |
| --- | --- | --- |
| `tools` | 数组 | `id/title/description/inputSchema/handler` |
| `mcp` | 数组 | `id/file`，文件采用现有 MCP 配置字段，宿主加归属 |
| `skills` / `agents` | 数组 | `id/file`，分别引用现有 Markdown 定义 |
| `pages` | 数组 | `id/title/document/onOpen`；`onOpen` 可省略 |
| `workspacePanel` | 单个对象，可省略 | `page` 引用页面，右抽屉按插件展示入口 |
| `configuration` | 单个对象，可省略 | `page/defaults/saveHandler`，从设置内配置操作打开 |
| `hooks` | 数组 | `id/event/mode/handler`，mode 为 `observe` 或获支持的 `transform` |
| `permissionReviewer` | 单个对象，可省略 | `handler`，必须已申请 `agent.permissionReview` |
| `environments` | 数组 | `id/target/manager/prerequisites/inputs/lockFiles/checkHandler/prepareHandler` |
| `services` | 数组 | `id/environment/environmentRef/startHandler/stopHandler/exports`，无依赖时可省略 environmentRef |
| `providers` | 数组 | `id/title/format/endpoint/models/authentication` |

这些都是静态声明。注册清单不执行 handler；启用后由宿主建立归属关系，真正触发事件/调用时再加载逻辑并检查权限。页面 onOpen 是一次受控初始化事件，不随重组重复发送；它不自动获得执行构建、安装依赖等副作用的权利，相关业务须由明确动作触发。

贡献可用 `requires` 数组声明额外的已申请权限依赖；注册本类型的权限仍按类型隐式校验，不能通过省略 requires 省掉。依赖未获授权则相关功能不可用；每次能力调用仍单独核验，不把 requires 当作已授权证明。服务不因出现在清单中就自动运行。

`providers.requests` 仅在宿主路由确实选用了该插件拥有的提供商实例时生效，并绑定实例、请求、插件版本与授权。它允许处理该请求已发送的内容，不允许枚举历史或主动使用其他渠道消费额度。注册和凭据授权均不能代替这项请求处理授权。

`mcp.connect` 对宿主 MCP 传输生效；`network.request` 单独约束插件 SDK 发起的请求，两者不隐式互相授予。下载后、真正授权连接之前，宿主展开并展示该 MCP 配置的实际 origin/入口路径或程序/参数，把包配置摘要、解析后的目标和凭据引用记录为有效范围。目标改变或跨 origin 重定向不沿用授权；无符合授权的明确目标则拒绝，不自动追加网络许可。

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

服务 `exports` 为名称到类型的映射。HTTP 导出声明 `type: httpEndpoint, audience: app, binding: managed, basePath: /v1/`；startHandler 通过宿主登记受管理的就绪出口句柄，不允许返回一个任意 URL 冒充该实例的服务。宿主把出口绑定到已授权服务实例、执行后端和固定 basePath；本地/SSH 的受控监听或转发如何实现仍待验证，不支持时拒绝导出，不能退回信任任意外部地址。

`startHandler` 仅在获授权、environmentRef 对应环境已 ready 且需要服务时运行；`stopHandler` 接收该实例的受控清理句柄，不因权限撤销或卸载就继续运行任意插件清理代码。App 消费的地址由宿主解析受控出口，不把远端 localhost 原样作为手机入口。

`authentication.source = serviceToken` 仅用于受管理服务出口，使用宿主为该实例生成并绑定受众的桥接认证，不把上游 Key 当作桥接口令。插件所需上游凭据另走 `credentials.use` 的自有授权。

固定外部入口可使用 `authentication.source = providerInstance`，复用用户明确配置给该实例的认证；宿主将其绑定到已授权 origin 与 basePath，目标改变重新确认，不向跨 origin 重定向转发认证。清单不内嵌口令，也不读取全量渠道。

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
    "pluginApi": "^1.0.0",
    "structuredUi": "^1.0.0"
  },
  "runtime": {
    "type": "javascript",
    "entry": "dist/main.js"
  },
  "permissions": [
    {"id": "tools.register", "required": true, "reason": "注册项目查询工具", "scope": {"ids": ["project_info"]}},
    {"id": "mcp.register", "required": false, "reason": "注册项目上下文服务", "scope": {"ids": ["project_context"]}},
    {"id": "mcp.connect", "required": false, "reason": "连接已配置的上下文服务", "scope": {"ids": ["project_context"]}},
    {"id": "skills.register", "required": false, "reason": "提供项目说明技能", "scope": {"ids": ["project-notes"]}},
    {"id": "agents.register", "required": false, "reason": "提供构建子代理定义", "scope": {"ids": ["builder"]}},
    {"id": "providers.register", "required": false, "reason": "提供上游转换后的兼容入口", "scope": {"ids": ["gateway"]}},
    {"id": "providers.requests", "required": false, "reason": "处理已选插件提供商的模型请求", "scope": {"ids": ["gateway"]}},
    {"id": "ui.pages", "required": true, "reason": "展示操作和配置页面", "scope": {"ids": ["build", "configuration"]}},
    {"id": "storage.plugin", "required": true, "reason": "保存本插件配置", "scope": {"access": "readWrite"}},
    {"id": "workspace.commands", "required": true, "reason": "执行用户要求的项目命令", "scope": {"target": "currentWorkspace"}},
    {"id": "terminal.sessions", "required": true, "reason": "在共享标签显示任务输出", "scope": {"target": "currentWorkspace", "access": "owned"}},
    {"id": "environment.prepare", "required": false, "reason": "按用户操作准备本插件服务依赖", "scope": {"ids": ["gateway_deps"]}},
    {"id": "agent.events", "required": false, "reason": "展示本轮运行状态", "scope": {"events": ["agent.request.before", "agent.run.finished"], "data": "metadata"}},
    {"id": "agent.permissionReview", "required": false, "reason": "代审用户授权范围内的命令", "scope": {"tools": ["Bash", "terminal"]}},
    {"id": "credentials.use", "required": false, "reason": "处理交给本插件的上游凭据", "scope": {"access": "owned"}}
  ],
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
      {"id": "before_request", "event": "agent.request.before", "mode": "observe", "handler": "hooks.beforeRequest"},
      {"id": "run_finished", "event": "agent.run.finished", "mode": "observe", "handler": "hooks.runFinished"}
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
- 权限预览与实际清单逐项一致；引用某能力的贡献未获得对应授权时不可用，依赖项缺失需可诊断，不是忽略错误继续启动。
- 宿主先核验包摘要/清单摘要，再读取并校验声明；读取声明不触发模块顶层执行、MCP 连接、服务启动或脚本安装。
- 更新保持 `id/version/repository/publisher` 与索引记录一致。服务地址的运行期导出绑定插件实例与授权版本，不因同名端口复用而信任另一进程。
- 资源上限、实际 runtime 支持、权限作用域、hook 可变字段与任务退出策略仍按宿主契约验证，不能因为清单可解析就声称这些实现完成。

## 11. 作者发布顺序

1. 在独立源码仓库实现 handler、声明与测试。
2. 发布前生成业务 bundle，按本节目录组织包，确认清单引用全部存在。
3. 计算最终 ZIP 与包内 `plugin.json` 的 SHA-256，关联源码 commit/tag；发布后不修改同版本内容。
4. 在索引仓库提交相应条目与版本记录，完整复制权限预览并绑定同一产物，见索引规范。
5. 使用者通过 App 在线流程安装；非官方每次安装/更新仍手输源码仓库地址，清单不提供免确认字段。

## 12. 当前实现范围（第一切片）

上面的格式是完整草案；宿主现在只实现了其中**声明式**的一层，其余一律在安装时明确拒绝，不静默忽略。

### 已实现

| 项 | 行为 |
| --- | --- |
| 顶层字段 | `manifestVersion`（必须为 1）、`id`、`name`、`version`、`publisher`、`repository`、`description`、`license`、`engines`、`permissions`、`contributes` |
| `engines` | `pluginApi` 必填，范围必须包含宿主实现的插件 API（当前 `1.0.0`）；`structuredUi` 只校验写法，目前没有对应功能 |
| 版本范围写法 | `^x.y.z`、`~x.y.z`、`>= > <= < =` 加完整版本、精确版本，空格分隔取交集；不支持 `1.x`、`*`、`\|\|`、`a - b` |
| 能力 | `skills.register`、`agents.register`，`scope` 只有 `ids` |
| 贡献 | `contributes.skills[]`、`contributes.agents[]`，每项 `{id, file}`，`file` 为包内 `.md` |
| `repository` | 必须是不带账号、查询、片段的 `https` 地址；只作为插件自述展示，**不核验** |
| 安装路径 | 设置 → 插件 → 「＋」选择本地 `.zip`。这是目前唯一的安装路径，没有在线索引 |

### 校验规则（实现里的硬规则）

- 未知顶层字段、未实现的字段（含 `runtime`）、未实现的贡献类型与能力：整体拒绝。
- 权限范围与贡献逐项对应：每个贡献 ID 必须被对应注册权限的 `scope.ids` 覆盖，权限里也不能列出不存在的 ID；同类 ID 与同一权限不得重复。
- `publisher` 必须等于 `id` 第一段；`id` 形如 `发布者.插件名`，两段都是小写字母开头、仅含小写字母 / 数字 / 连字符。
- 包内路径禁止绝对路径、`.` / `..` 段、空段、反斜杠、控制字符，长度 ≤ 200、深度 ≤ 8；贡献文件必须存在、位于包内、不超过 512 KB。
- ZIP：压缩包 ≤ 16 MiB、≤ 512 个条目、单条目 ≤ 4 MiB、解压总量 ≤ 32 MiB；重复条目（含仅大小写不同）拒绝；清单 ≤ 256 KiB 且必须在根目录。限额在流式读取时执行，不信任条目自报的大小。所有条目都按普通文件写出，ZIP 里的符号链接与权限位被忽略。
- 技能 / 子代理的清单名由宿主命名空间化为 `<插件ID>:<局部ID>`，frontmatter 里的 `name` 被忽略，因此不会覆盖内置功能、使用者自建内容或其他插件。用户自建技能名不允许含 `:`，二者不会撞名；万一撞名，使用者自建的优先。
- 描述会进入系统提示词的技能 / 子代理清单，因此压成单行，避免插件借换行伪造清单条目。
- 子代理定义不允许带 `provider` / `model`：它们会动用使用者的付费渠道，插件子代理一律继承父会话。`mode` 仍只能收紧不能放大（既有规则）。

### 授权与状态

- 能力授权由安装确认界面逐项决定：必要能力固定勾选，**可选能力默认不开启**。
- 同 ID 再次导入视为替换安装，授权按本次确认重新设定，**不继承**旧授权。
- 必要能力被撤销后，插件整体不生效（“待授权”）；可选能力被撤销只让对应贡献失效。
- 安装记录（启停、授权、包摘要）由宿主独占，保存在 `filesDir/plugins/registry.json`，不在插件包目录里，也没有绑定进 PRoot 容器。
- 每次读取已安装插件都会用记录里的清单摘要校验 `plugin.json`；摘要不符、文件缺失或内容无法解析时标记“已损坏”并让其贡献失效。清单不再通过校验（例如宿主插件 API 升级后不兼容）标记“不可用”。
- 已派出去的子会话仍能还原自己的子代理定义（停用只拦新的派发）。
- 安装记录目前**不进入备份**；备份域是固定的，插件相关数据不会被还原，换机后需重新导入。

### 暂未实现

`tools`、`mcp`、`pages`、`workspacePanel`、`configuration`、`hooks`、`permissionReviewer`、`environments`、`services`、`providers` 等贡献，`runtime` 与全部依赖 JS 运行时的能力，在线索引安装与更新，官方标识，Skill 随包脚本。技能设置页与子代理设置页目前不列出插件内容（它们是可编辑文件列表），插件提供的内容在“设置 → 插件 → 详情”里查看。
