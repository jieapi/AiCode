# 插件索引仓库结构与在线版本格式草案

状态：格式草案 v1，与 [10-plugin-format.md](10-plugin-format.md) 配套。多索引、独立源码仓库、非官方逐次手输要求已确认；目录名和字段是具体设计建议，不是已实现接口。官方认定与签名机制仍待决定。

## 1. 索引仓库目录

```text
plugin-index/
├── index.json
└── plugins/
    └── community.workbench-kit/
        ├── entry.json
        └── versions/
            └── 1.0.0.json
```

- `index.json` 是单一发现入口，指向各插件条目。
- `entry.json` 是插件展示与版本目录，指向不可变版本记录。
- `versions/<version>.json` 描述一个确定版本的包、校验、兼容性和权限预览。
- 源码、业务脚本、UI 实现和 ZIP 不放在索引仓库；一个插件自己的源码仓库负责发布对应安装包。
- 同一布局适用于官方或社区索引，不通过不同目录格式区分信任等级。

## 2. App 中的索引配置

这是宿主配置，不随插件包下发；一个 App 可以保存多项：

```json
{
  "name": "Community Plugins",
  "repository": "${INDEX_REPOSITORY_URL}",
  "ref": "main",
  "entryPath": "index.json",
  "enabled": true
}
```

宿主另生成不可由索引自报覆盖的来源 ID。来源比较与缓存至少关联配置的仓库身份、ref、入口路径；同名索引的显示名称或 `index.id` 不能合并授权/缓存。

`ref` 示例为 main，不代表所有来源默认扫描 main。认证索引、非 Git 仓库式来源和自动分支发现不在本草案默认加入。

**索引根固定为 `entryPath` 指定的 index.json 所在目录。** 例如配置 `entryPath = catalog/index.json`，根内的 `plugins/community.workbench-kit/entry.json` 解析为仓库中的 `catalog/plugins/community.workbench-kit/entry.json`，其版本路径则为 `catalog/plugins/community.workbench-kit/versions/1.0.0.json`。不尝试仓库根作为回退位置；不允许内部引用越出索引根。

所有 `${...}` 为待填写占位符，不是真实地址、摘要或源码提交，不能据此实际安装。其作用是给出完整字段关系，不臆造插件仓库与下载链接。

## 3. index.json

```json
{
  "indexVersion": 1,
  "id": "community.plugins",
  "name": "Community Plugins",
  "updatedAt": "2026-10-25T00:00:00Z",
  "plugins": [
    {
      "id": "community.workbench-kit",
      "entry": "plugins/community.workbench-kit/entry.json",
      "sha256": "${ENTRY_SHA256}"
    }
  ]
}
```

| 字段 | 语义 |
| --- | --- |
| `indexVersion` | 格式版本；未知版本拒绝 |
| `id` / `name` | 目录自报 ID 与展示名称，不构成官方身份 |
| `updatedAt` | 目录快照时间，用于展示，不作为唯一版本或防回滚证明 |
| `plugins[].id` | 条目标识；同一索引内不重复 |
| `entry` | 相对索引根的条目路径 |
| `sha256` | 条目文件原始字节的 SHA-256，用于快照一致性 |

取得根文件后，按根记录的摘要读条目；不扫描任意仓库文件、不执行索引脚本。无法取得匹配快照时不混用其他版本的文件继续安装。

## 4. 插件 entry.json

```json
{
  "entryVersion": 1,
  "id": "community.workbench-kit",
  "name": "Workbench Kit",
  "description": "提供项目操作页面、上下文工具与兼容提供商",
  "publisher": "community",
  "repository": "${PLUGIN_REPOSITORY_URL}",
  "officialClaim": false,
  "maintenance": {"state": "active"},
  "releases": [
    {
      "version": "1.0.0",
      "metadata": "versions/1.0.0.json",
      "sha256": "${RELEASE_METADATA_SHA256}",
      "state": "available"
    }
  ]
}
```

- `repository` 必须是插件独立源码仓库，不是索引或 ZIP 地址；非官方手输确认针对它。
- `officialClaim` 是目录维护者的声明，不是客户端最终 `isOfficial`，不能自行免除手输步骤。
- `metadata` 相对于当前 `entry.json` 所在目录，不能与根级 `entry` 的基准混用。
- `releases` 列出可发现版本，App 按兼容性与 SemVer 选择候选，不把数组第一个元素当权威最新版。
- 每个版本只指向一个不可变版本记录；不允许同一条目中版本号重复、版本与文件内容不一致。
- 预发布版本的推荐策略尚未确定；本草案不添加多个分发频道。使用者选择与推荐候选是不同概念。

这里的条目描述可以随索引更新；真正安装时采用选定版本的预览和包，不用新条目描述替换旧版本的权限/来源确认。

### 维护状态与撤回（建议）

[04-distribution.md](04-distribution.md) 第 5 节要求索引承载“维护状态、风险提示、撤回与可用替代版本”。版本记录 `versions/*.json` 发布即冻结，而状态会随时间变化，所以**状态只放在可变的 `entry.json` 里**，不写进版本记录：

| 字段 | 取值 | 语义 |
| --- | --- | --- |
| `maintenance.state` | `active` / `deprecated` / `archived` | 插件整体的维护状态；可选 `replacedBy`（插件 ID）与 `note`（本地化文本） |
| `releases[].state` | `available` / `yanked` / `revoked` | `yanked`：不再向新安装推荐，已安装的继续可用并提示；`revoked`：存在已知风险，已安装的在下次检查时被提醒并建议停用 |
| `releases[].advisory` | 本地化文本（可选） | 风险说明，展示在被标记版本旁 |
| `releases[].fixedIn` | SemVer（可选） | 可用的替代版本 |

`revoked` 是否自动停用已安装插件仍待决策；在确定前只提醒、不自动停用，因为一个未经核验的索引字段不应能远程关掉使用者的插件。状态字段没有签名保护时只是索引维护者的声明，与 `officialClaim` 同理不构成可信证据。

### 兼容要求（建议）

分发语义里要求“最低 App / 运行时要求”，现有 `engines` 只有 `pluginApi` 与 `structuredUi`。建议增加：

- `engines.app`：AiCode `versionName` 的 SemVer 范围，如 `>=1.14.0`；
- `engines.runtimeFeatures`：插件依赖的 JS 运行时能力名列表。JS 引擎来自设备 WebView（`isFeatureSupported` 的结果因设备而异），没有这一项就无法在安装前告诉使用者“你的设备不支持”。

两项都要同时出现在 `plugin.json` 与版本记录里并保持一致。第一切片的清单解析器目前会把它们当作未支持字段拒绝。

## 5. 版本记录 versions/1.0.0.json

```json
{
  "releaseVersion": 1,
  "id": "community.workbench-kit",
  "version": "1.0.0",
  "publisher": "community",
  "repository": "${PLUGIN_REPOSITORY_URL}",
  "source": {
    "ref": "v1.0.0",
    "commit": "${SOURCE_COMMIT}"
  },
  "engines": {
    "pluginApi": "^1.0.0",
    "structuredUi": "^1.0.0"
  },
  "artifact": {
    "format": "zip",
    "url": "${PACKAGE_DOWNLOAD_URL}",
    "size": 16384,
    "sha256": "${PACKAGE_SHA256}",
    "manifestSha256": "${MANIFEST_SHA256}"
  },
  "preview": {
    "name": "Workbench Kit",
    "description": "提供项目操作页面、上下文工具与兼容提供商",
    "license": "MIT",
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
    "contributions": {
      "tools": ["project_info"],
      "mcp": ["project_context"],
      "skills": ["project-notes"],
      "agents": ["builder"],
      "providers": ["gateway"],
      "pages": ["build", "configuration"],
      "hooks": ["before_request", "run_finished"],
      "services": ["gateway_service"],
      "environments": ["gateway_deps"],
      "permissionReviewer": true,
      "workspacePanel": "build",
      "configuration": "configuration"
    },
    "environments": [
      {"id": "gateway_deps", "target": "currentWorkspace", "manager": "npm", "prerequisites": ["node", "npm"], "requires": ["workspace.commands", "terminal.sessions"], "inputs": [{"path": "resources/gateway/gateway.js", "sha256": "${GATEWAY_SCRIPT_SHA256}"}, {"path": "resources/gateway/package.json", "sha256": "${SERVICE_PACKAGE_SHA256}"}, {"path": "resources/gateway/package-lock.json", "sha256": "${SERVICE_LOCK_SHA256}"}], "lockFiles": ["resources/gateway/package-lock.json"], "checkHandler": "environments.gatewayCheck", "prepareHandler": "environments.gatewayPrepare"}
    ]
  }
}
```

`size` 仅为样本数字，真实值由发布流程计算，不表示本示例存在一个可用包。`source.commit` 固定源码内容，ref 只便于人工定位，不能仅凭一个可移动 tag 证明构建来源。

### 必须与包内清单核对的字段

- 必须满足 `index.plugins[].id = entry.id = release.id = plugin.json.id`；`entry.publisher/repository` 与 release、清单一致，条目引用版本与 release/清单 version 一致。每层摘要正确不能代替这些身份恒等检查。
- `release.engines` 与 `plugin.json.engines` 一致。
- `preview.name/description/license` 与实际清单一致，不能预览一个名称却安装另一个。
- `preview.permissions` 与清单完整集合一致：包括 `required/reason/scope`，不只比较权限 ID；顺序无关，但重复/未知项拒绝。
- `preview.contributions` 是清单各类局部 ID 和页面入口的精确摘要，不隐藏提供商、审核、服务、依赖环境或其他贡献。
- `preview.environments` 与清单 environments 全量一致，包含准备入口、manager、目标、前置命令、inputs/锁文件路径与原始字节摘要；不只是列环境名称。下载后核验包内实际 inputs，不执行包管理器生成预览。
- 无 environments 的插件应省略该预览字段，不能拿空列表掩盖包内新增的准备逻辑。
- `artifact.sha256` 校验 ZIP 原始字节，`manifestSha256` 校验 ZIP 中 `plugin.json` 的原始字节；摘要不依据重新格式化后的 JSON 计算。

校验顺序：版本元数据摘要 → ZIP 字节数/摘要 → 安全读取根清单 → 清单字节摘要 → 字段及权限/贡献摘要核对。任何不一致都不能沿用原确认继续启用。

## 6. 来源与版本的一致性

安装身份不是一个 `id` 字符串，宿主记录至少关联：配置来源 ID、索引仓库/ref/入口、插件 ID、源码仓库、发布身份、版本、版本记录摘要、ZIP 与清单摘要、已授予权限版本。

- 同一索引内 ID 唯一；不同索引出现相同 ID 时保留来源，只有身份与内容一致才可合并展示。
- 已安装插件沿绑定来源查更新，不从另一个索引找一个更大版本号就静默换源。
- 索引变更了相同版本记录或包摘要视为内容替换/冲突，不当作正常更新。
- 地址、发布身份、权限、所选版本、官方核验或相关信任状态变化时，重新执行适用的确认/授权流程。
- 更新应用失败保留已安装版本；读取过期缓存只能明确展示状态，不伪称已经发现最新版本。

## 7. 在线安装与更新如何读取

```text
读取配置来源的 index.json
  → 校验并读取选定 entry.json
  → 读取候选版本元数据并核对引用摘要
  → 展示版本、来源、完整权限与贡献摘要
  → 非官方：本次输入插件源码仓库地址并确认
  → 下载匹配的 ZIP，检查真实清单
  → 校验预览一致并完成能力授权
  → 原子应用并记录来源与版本
```

非官方每次安装、每次更新都重新执行输入确认；同源正常更新也不免除。输入对象是版本记录和条目一致的插件源码仓库地址，不是索引地址或下载链接。

安装预览纯静态，不能为取得元数据运行插件入口、启动兼容提供商/MCP 服务或执行 shell。下载后的实际清单与预览不一致时拒绝该目标或重新取得正确元数据并重新确认，不做“先启用后补弹窗”。

官方插件是否跳过普通确认、认证索引、后台自动检查等未决策略不在格式中自行赋权。插件权限授权始终独立存在；官方身份本身不等于同意新能力。

## 8. 路径、下载与快照约束

- 根 `plugins[].entry` 相对 index.json 所在的索引根目录；条目 `releases[].metadata` 相对条目目录；包内引用相对包根。三种基准固定，不自动尝试不同位置。
- 索引内部引用只允许安全相对路径，禁止绝对路径、`..`、跨来源跳转或脚本表达式。
- ZIP 下载地址是显式的外部传输位置；同一包可经 CDN 传输，但最终摘要、字节数、清单和来源关系必须核对。
- 页面显示需要的字段来自有限大小的 JSON 元数据，不能为展示图标/标签无限抓取外部资源或执行插件代码。
- `index → entry → release` 各层摘要使混合 CDN 快照可被检测；发布建议先提供不可变版本记录/包，再更新条目，最后更新根。
- **安装与更新前必须绕过缓存读取最新索引。** 现有 `RepoDataFetcher` 的本地缓存默认 12 小时（[RepoDataFetcher.kt:192](../../app/src/main/java/com/aicode/core/net/RepoDataFetcher.kt:192)），另有 CDN 各节点刷新不同步的已知现象；若撤回状态也走这条链路，恶意版本被撤回后仍会在几个小时内继续可被安装。因此点击安装/更新时必须实时取最新的 `index → entry → release`；取不到（离线）就不允许安装未经最新状态确认的版本，只显示缓存时间。缓存键还要带上来源身份（现有键只按路径，见 04 第 3 节）。
- 摘要只能检测与当前记录是否一致，不能证明目录维护者可信。缓存按配置来源隔离，失败不清空其他来源或已安装状态。

## 9. 官方声明与未来签名位置

本草案保留 `officialClaim` 作为声明，不把签名算法、信任根或“官方=被收录”冻结为产品规则。客户端计算的官方核验结果属于宿主状态，不由插件或索引填一个布尔值决定。

推荐将官方维护与官方收录分开；如采用签名发布记录，可在后续版本增加明确的旁证引用与签名格式，并规定覆盖的原始字节或规范化内容、密钥轮换和过期语义。不能因为字段暂未定义就将索引所有包当作官方。

未知或无法核验的官方声明不用于跳过非官方手输要求；正式启用官方免除策略前必须先确定完整核验规则。

## 10. 发布与验证责任

作者负责：插件源码 commit、打包、包与清单摘要、handler/资源引用和插件自身测试。

索引维护者负责：元数据、权限预览与最终包一致；不在有发布凭据的任务中执行不可信插件代码。索引本身仍只放索引内容，校验实现可由宿主仓库或隔离发布工具提供。

App 负责：静态解析、来源/版本/摘要核对、逐次确认、能力授权、手动依赖准备、运行时隔离和失败恢复。本文的具体目录与 JSON 例子完成了结构草案，但不代表宿主、SDK、完整 UI 协议或签名验证已经实现。
