# 接口文档

AiCode 是 Android 应用，没有对外 HTTP API。本文档覆盖四类「接口」：核心扩展点接口（工具系统、执行后端抽象）、LLM/MCP 协议适配、数据契约（Room 实体）与内部脚本/Gradle task。

## AI 工具系统（核心扩展点）

所有 AI 可调用的工具实现 `AgentTool` 接口（`app/src/main/java/com/aicode/feature/agent/domain/tool/AgentTool.kt`）：

```kotlin
interface AgentTool {
    val name: String                      // 注册名，function-calling 用
    val description: String               // 给 LLM 看的工具说明
    val parameters: Map<String, ToolParameter>  // 参数定义 → toJsonSchema() 生成 schema
    val permissionPolicy: ToolPermission  // 授权策略
    val capabilities: Set<ToolCapability>
    suspend fun execute(args: JSONObject): ToolResult
    suspend fun executeWithContext(args: JSONObject, context: AgentContext): ToolResult
}
```

- `ToolResult` 为 sealed：`Success` / `Error` / `Partial`
- 需要流式过程输出的工具实现 `StreamingAgentTool`（返回 `Flow<ToolStreamEvent>`）
- 强制依赖会话上下文的工具继承 `AbstractContextualTool`
- 工具经 `ToolRegistry`（`domain/tool/ToolRegistry.kt`）注册，LinkedHashMap 保序；MCP 工具运行时由 `McpManager` 动态追加/反注册

### 内置工具清单

注册于 `app/src/main/java/com/aicode/di/AgentModule.kt`：

| 注册名 | 类 | 位置 `feature/agent/domain/tool/` | 职责 |
|--------|-----|------|------|
| `readFile` | ReadFileTool | file/FileTools.kt | 读文件 |
| `writeFile` | WriteFileTool | file/FileTools.kt | 写文件（触发检查点快照） |
| `editFile` | EditFileTool | editor/EditFileTool.kt | 精确编辑文件片段 |
| `sendFile` | SendFileTool | file/SendFileTool.kt | 发送文件给用户 |
| `viewImage` | ViewImageTool | file/ImageTools.kt | 读取图片进上下文 |
| `generateImage` | GenerateImageTool | file/GenerateImageTool.kt | 文生图 |
| `Bash` | ExecuteCommandTool | container/ExecuteCommandTool.kt | 容器/远程执行 Shell 命令 |
| `terminal` | TerminalSessionTool | container/BackgroundTerminalTools.kt | 后台终端会话（创建/输入/读输出） |
| `list` | ListFilesTool | explorer/ | 列目录 |
| `search` | SearchCodeTool | explorer/ | 代码搜索 |
| `websearch` | WebSearchTool | search/ | 网页搜索 |
| `webfetch` | WebFetchTool | search/ | 抓取网页（jsoup 清洗） |
| `loadSkill` | LoadSkillTool | skill/LoadSkillTool.kt | 加载技能（Skills） |
| `memory` | MemoryTool | memory/MemoryTool.kt | 读写长期记忆 |
| `todo` | TodoTool | todo/TodoTool.kt | 任务清单管理 |
| `switchMode` | SwitchModeTool | mode/SwitchModeTool.kt | BUILD/PLAN/AUTO 模式切换 |
| `askUserQuestion` | AskUserQuestionTool | question/AskUserQuestionTool.kt | 向用户提问（挂起等待） |
| `manageMcp` | ManageMcpTool | mcp/ManageMcpTool.kt | 管理 MCP 服务器连接 |
| `task` | TaskTool | subagent/TaskTool.kt | 派生子代理并行工作 |

## LLM Provider 适配

统一抽象 `AIProvider`（`feature/agent/domain/provider/AIProvider.kt`）：

```kotlin
interface AIProvider {
    suspend fun complete(request: AIRequest): AIResponse
    fun completeStream(request: AIRequest): Flow<AIStreamChunk>
}
```

- `AIStreamChunk` 为 sealed：`TextDelta` / `ReasoningDelta` / `Final` / `Retrying`
- 三个适配器位于 `feature/agent/domain/provider/`：

| 适配器 | 协议 | 特性 |
|--------|------|------|
| `AnthropicAdapter` | Messages API（`AnthropicApi`，Retrofit + 手动 SSE 解析） | thinking/signature 块回传 |
| `OpenAIAdapter` | Chat Completions；`useResponseApi=true` 时走 Responses API | 图片生成、Responses 事件聚合器 `ResponsesStreamAccumulator` |
| `GeminiAdapter` | generateContent + Interactions API 双协议 | Interactions 事件聚合器 `GeminiInteractionsStreamAccumulator` |

- 重试与容错：`RetryPolicy`（阶梯重试）、`KeyFailureClassifier`（API Key 失效分类 → 配合 `ProviderKeyRotator` 多 Key 轮换）、`HttpErrorEnricher`
- Retrofit 接口层位于 `feature/agent/data/remote/{anthropic,openai,gemini}/`，由 `di/AgentModule.kt` 提供 `@Named` 区分的三个实例

## MCP 协议（动态工具扩展）

`McpManager`（`feature/agent/domain/mcp/McpManager.kt`）连接 MCP 服务器并把远端工具桥接为 `AgentTool`：

| 传输方式 | 实现类 | 说明 |
|---------|--------|------|
| 本地 stdio | `StdioTransport` | 在 PRoot 容器内以子进程拉起 server（`command`/`args`/`env`） |
| 远程 HTTP | `StreamableHttpTransport` | OkHttp Streamable HTTP + SSE（手动解析 `data:` 行） |

- 协议实现：`McpClient`（JSON-RPC 握手 + `tools/list` + `tools/call`）、`McpJsonRpc`（消息模型）、`McpTransport`（可插拔传输接口）
- 工具桥接：远端工具注册名命名空间化为 `mcp__<server>__<tool>`，断开时反注册
- 配置：`McpConfigRepository` + `.mcp.json` 风格配置文件；订阅 4 个信号源（工作区切换、配置文件外部编辑、容器 profile 切换、远程默认容器变化）自动 reload

## 执行后端抽象接口

三个委托接口实现本地/远程双后端（详见 [架构文档 - 本地/远程委托模式](./ARCHITECTURE.md#本地远程委托模式)）：

| 接口 | 位置 | 方法概要 | 本地实现 | 远程实现 |
|------|------|---------|---------|---------|
| `CommandEngine` | `feature/agent/domain/container/` | `runCommand` / `runCommandSyncUnbounded` | `LinuxContainerEngine`（PRoot 拉起容器内进程） | `RemoteSshEngine`（sshj exec channel） |
| `FileAccessProvider` | `feature/workspace/domain/` | `read` / `write` / `list` / `delete` / `copy` / `rename` / `mkdirs` | `LocalFileAccess` | `RemoteSftpFileAccess`（SSH exec channel，规避 sshj SFTP 缓冲区溢出 bug） |
| `TerminalSessionProvider` | `feature/terminal/domain/` | `startBackgroundCommand` / `sendInput` / `getTabOutput` / `listTabs` / `closeTab` / `tabFinishedEvents` | `TerminalSessionManager`（本地 PTY） | `RemoteTerminalSessionManager`（sshj shell channel → `SshShellBackend`） |

分发机制：`DelegatingCommandEngine` / `DelegatingFileAccess` / `DelegatingTerminalSessionProvider` 每次调用读 `ExecutionModeHolder.currentMode()` 转发目标实现。

## 数据契约（Room 实体）

主数据库 `aicode_agent_db`，`AgentDatabase`（`feature/agent/data/local/database/AgentDatabase.kt`），`SCHEMA_VERSION = 52`：

| 实体（表名） | 路径 `feature/agent/data/local/entity/` | 关键字段 |
|-------------|------|--------|
| `ChatSessionEntity`（chat_sessions） | ChatSessionEntity.kt | 标题、模式（BUILD/PLAN/AUTO）、provider/model、token 统计、parentId（子代理会话） |
| `AgentMessageEntity`（agent_messages） | AgentMessageEntity.kt | 角色、内容、工具调用 JSON、附件、reasoning 回传字段 |
| `TodoItemEntity`（todo_items） | TodoItemEntity.kt | AI 任务清单项 |
| `CheckpointEntity`（session_checkpoints） | CheckpointEntity.kt | 检查点节点（归属会话与用户消息） |
| `CheckpointFileSnapshotEntity`（checkpoint_file_snapshots） | CheckpointFileSnapshotEntity.kt | 单文件快照记录（内容副本存 `filesDir/checkpoints/`） |
| `LlmCallRecordEntity`（llm_call_records） | LlmCallRecordEntity.kt | 每次 LLM 调用的 token/耗时审计 |
| `AIProviderEntity`（ai_providers） | `feature/settings/data/local/entity/AIProviderEntity.kt` | 多 Key 轮换、每提供商代理、自定义请求头 |
| `RemoteConnectionEntity` / `RemoteMountEntity` | `feature/workspace/data/local/` | 远程 SSH 连接与挂载配置 |

对应 DAO：`AgentMessageDao`、`ChatSessionDao`、`TodoItemDao`、`CheckpointDao`、`LlmCallRecordDao`、`AIProviderDao`、`RemoteConnectionDao`。

迁移脚本：`app/src/main/assets/migrations/{VERSION}_{description}.sql`，由 `core/db/MigrationLoader.kt` 加载执行并记入 `migration_history` 表；schema 导出在 `app/schemas/`。

## 内部脚本与 Gradle Task

### scripts/（仓库根）

| 脚本 | 用途 | 调用时机 |
|------|------|---------|
| `check_migrations.py` | 迁移对账：编号连续、`SCHEMA_VERSION` 一致、已发布 Tag 迁移未篡改/复用 | push 前 + CI 构建 |
| `update-models-dev-assets.py` | 从 models.dev 刷新 `app/src/main/assets/api.official.json`（仅内置 provider） | 仅打 Tag 发版前手动；失败非零退出且不改文件 |
| `build-vscode-themes.py` | 合并 VSCode Dark+/Light+ 主题为 tm4e 单文件 JSON → `assets/textmate/` | 编辑器主题变更时 |
| `sync-gitcode-releases.py` | 同步 GitHub Release APK 到 GitCode 镜像 | 发布后 |

### assets/aicode/（容器内脚本）

| 脚本 | 用途 |
|------|------|
| `provision.sh` | 首次进终端的依赖安装菜单（基础工具 + Node/Python/Java/Go 运行时，多镜像换源；`PROVISION_VERSION` 变更触发存量设备重跑） |
| `git-credential-aicode` | 自定义 git credential helper（busybox ash 兼容，仅 HTTPS），经文件 IPC 与 App 凭据弹窗联动 |

### Gradle Task

| Task | 用途 |
|------|------|
| `:app:assemble<Flavor><BuildType>` | 构建 APK（`universal` / `armsolo` / `x86solo` × `debug` / `release`） |
| `:app:testUniversalDebugUnitTest` | 单元测试（迁移测试只跑 universalDebug 变体） |
| `:app:checkMigrations` | 调 `scripts/check_migrations.py` 迁移对账 |
| `:app:syncAiDocs` | 把 `docs-site/docs/*.md` 复制进 `assets/docs/`（`preBuild` 自动依赖） |

## 权限与授权接口

- `ToolPermissionPolicyEngine`（`feature/agent/domain/permission/`）：弹窗前判定 ALLOW / DENY / ASK。判定顺序：DENY 规则 → 不可静态判定则 ASK → 内置安全白名单（`BuiltInSafeCommands`）→ 已记忆 ALLOW 规则 → ASK
- `ToolPermissionManager`（`feature/agent/domain/tool/`）：授权中台，`awaitApproval()` 挂起（CompletableDeferred）等用户在弹窗选择，多会话并行互不阻塞
- 三种运行模式：BUILD（正常授权）、PLAN（工具层拦截全部写操作）、AUTO（全部放行，保留灾难性 `rm` 防护）
