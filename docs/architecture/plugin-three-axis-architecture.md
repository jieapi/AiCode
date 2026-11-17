# 插件三轴解耦架构与传输/清理设计

> 本文档定义 AiCode 插件系统的下一代扩展架构：将 **执行后端（Execution Backend）**、**Agent 工作流（Agent Workflow）** 与 **会话源（Session Source）** 彻底拆分为三条正交能力轴，支持任意组合，并配套 **工作区双模式发现** 与 **二进制高速传输通道**，同时明确需要移除的旧版耦合 API。

---

## 1. 现状问题与设计目标

结合当前仓库实现，现有远程会话与插件适配存在以下结构性限制：

1. **执行后端被写死为全局二元枚举**
   - `ExecutionMode`（`app/src/main/java/com/aicode/feature/settings/data/repository/ExecutionModeRepository.kt`）仅有 `LOCAL_PROOT` 与 `REMOTE_SSH`。
   - `DelegatingCommandEngine`、`DelegatingFileAccess`、`DelegatingTerminalSessionProvider` 仅在内置实现之间二选一；`ProjectAicodeRoot`、`SystemPromptProvider`、`FileChangeHub`、`GitRepository`、`PortForwardManager`、`McpManager` 等多处直接判断 `ExecutionMode.REMOTE_SSH`，插件无法作为第三种执行后端接入。
2. **工作区发现假定单一父目录**
   - `WorkspaceRepository`（`app/src/main/java/com/aicode/feature/workspace/data/repository/WorkspaceRepository.kt`）固定扫描本地 `filesDir/projects` 或远程 `remoteWorkspacePath` 子目录，无法原生承接分散在不同目录/主机上的外部工作区。
3. **远程 Agent 靠 OpenAI Gateway 与分叉运行器绕行**
   - `PluginRemoteRunManager` 通过伪装成 OpenAI Provider 加自定义头（`X-AiCode-Session-Id` / `X-AiCode-Subscribe-Only` / `X-AiCode-Operation-Id`）驱动远端执行，遇工具调用直接报错 `REMOTE_TOOL_CALLS_NOT_SUPPORTED`，工具进度再另走 `PluginSessionEventBus` 拼补丁。
   - `AIAgentViewModel` 中存在 `session.executionMode == "remote"` 分叉与硬编码事件名 `ds-harness.session`，并绕开了 `AgentRunManager`（丢失进程级托管、排队、通知、Goal 循环、Room 持久化等通用能力）。
4. **会话注册仅支持全量覆盖，且生命周期缺失**
   - `host.sessionSource.setSessions`（`PluginSessionHostService` / `PluginSessionRegistry`）每次只能全量替换列表，不支持增量新增/更新/删除，也缺乏删除、重命名、置顶等生命周期 handler。
5. **插件进程通信仅走单管道 NDJSON，高时效场景偏慢**
   - `PluginProcessTransport` 复用单一 `stdin/stdout` 管道传输 NDJSON，`BoundedLineReader` 逐字节 `input.read()` 拼接、单行上限 `4 MiB`、二进制数据依赖 Base64、`writeMutex` 串行写入易队头阻塞。

### 核心设计原则

- **三轴正交**：执行后端、Agent 工作流、会话源各自独立声明、各自注册，互不捆绑。
- **全局后端 + 统一调度**：执行后端与工作区在全局作用域切换（与现行 `ContainerProfile` 体验一致）；Agent 工作流统一收口到 `AgentRunManager` 之下。
- **控制面与数据面分离**：低频控制信令保留简单协议，大文件与高频流式数据走 Unix Domain Socket 二进制多路复用通道。

---

## 2. 总体架构与组合矩阵

插件可在 `plugin.json` 的 `contributes` 中按需声明以下独立贡献点：

| 能力轴 | 清单字段 | 职责边界 | 内置实现 |
| --- | --- | --- | --- |
| **执行后端** | `executionBackends` | 文件读写、命令执行、可选终端 PTY、工作区提供 | `builtin:local-proot`、`builtin:remote-ssh` |
| **Agent 工作流** | `workflows` | 接收本轮输入与上下文，运行推理与工具循环，产出 `AgentEvent` | `builtin:stateful-agent` |
| **会话源** | `sessions` | 外部会话列表的增量同步、消息历史分页、会话操作 | `builtin:room` |

### 典型组合示例

| 场景 | 执行后端 | Agent 工作流 | 会话源 | 效果说明 |
| --- | --- | --- | --- | --- |
| **默认本地 / SSH** | 内置 PRoot / SSH | 内置 `StatefulAgentWorkflow` | 内置 Room | 现有默认行为 |
| **插件提供执行环境（自定义远端/容器/沙箱）** | **插件后端** | 内置 `StatefulAgentWorkflow` | 内置 Room | 文件树、编辑器、Git、终端、内置工具全部走插件通道；模型、Prompt、技能、记忆、会话历史完全由 App 管理 |
| **外部 Agent 引擎接入本地/远程工作区** | 内置或插件后端 | **插件工作流** | 内置 Room | 会话列表与消息落 App Room 持久化，但单轮 Agent 执行交给插件引擎（如外部 Agent Daemon） |
| **远端已有完整运行体（只把 App 当客户端）** | **插件后端**（可选） | **插件工作流** | **插件会话源** | 远端维护会话和历史并增量推送给 App；发消息直接走 `AgentRunManager -> PluginWorkflowAdapter` |

---

## 3. 轴一：执行后端（`executionBackends`）与工作区双模式

### 3.1 后端作用域与能力模型

执行后端保持**全局级**（切换当前激活后端时，工作区、文件树、编辑器、Git、终端、聊天执行工具统一切换），取代现有的全局二元 `ExecutionMode` 枚举。

每个后端（包括内置与插件）向上层提供统一的 `ExecutionBackend` 与能力声明 `BackendCapabilities`：

```kotlin
data class BackendCapabilities(
    val filesystem: Boolean = true,
    val command: Boolean = true,
    val terminal: Boolean = false,
    val fileWatch: Boolean = false,
    val portForward: Boolean = false,
    val workspaceDiscovery: WorkspaceDiscoveryMode, // SCAN | REGISTER | HYBRID
    val projectConfigLocation: ProjectConfigLocation // WORKSPACE_DIR | APP_PRIVATE_DIR
)
```

各模块不再判断 `executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH`，改为读取当前激活后端的 `capabilities`：
- `ProjectAicodeRoot`：按 `projectConfigLocation` 决定项目级 `.aicode` 放在工作区目录还是 `filesDir/aicode/projects/<key>`。
- `SystemPromptProvider`：向后端查询环境描述文案与缓存隔离键 `environmentKey`（不再写死 `"local"` / `"remote:..."`）。
- `FileChangeHub` / `GitRepository` / `PortForwardManager`：按 `fileWatch` / `command` / `portForward` 能力决定是否启用，不按枚举类型分支。

### 3.2 后端接口与插件 Handler

宿主 `DelegatingCommandEngine` / `DelegatingFileAccess` / `DelegatingTerminalSessionProvider` 改为从 `ExecutionBackendRegistry` 按当前激活的 `backendId` 路由到内置后端或 `PluginExecutionBackendAdapter`：

- **文件能力（映射 `FileAccessProvider`）**：
  - 元数据：`fs.stat`、`fs.list`、`fs.listRecursive`、`fs.resolvePath`、`fs.fingerprint`
  - 文本/字节读写：`fs.read`、`fs.write`、`fs.readStream`、`fs.writeStream`、`fs.delete`、`fs.rename`、`fs.copy`、`fs.move`、`fs.mkdirs`
  - 其中字节流与大文件优先走第 6 节的二进制数据通道。
- **命令能力（映射 `CommandEngine`）**：
  - `exec.sync`（返回 `output / exitCode / truncated`）
  - `exec.stream`（流式推送 `line` / `exit`）
  - `exec.health`（返回就绪状态与未就绪提示文案）
- **终端能力（可选，映射 `TerminalSessionProvider`）**：
  - `pty.open` / `pty.write` / `pty.resize` / `pty.close` + 输出流事件。不支持 `terminal` 能力的后端在终端页给出明确降级提示。

### 3.3 工作区发现双模式：App 主动扫描 vs 插件注册

重构 `WorkspaceRepository`，移除 `isLocal()` 分叉，工作区唯一键由 `(backendId, workspaceId)` 决定（不再以文件夹 `name` 作为全局唯一标识）：

1. **模式 A：App 主动扫描（`SCAN`）**
   - 适用于存在统一父目录的后端（内置本地、内置 SSH 或目录式后端插件）。
   - App 调用后端的 `workspace.list` / `workspace.create` / `workspace.delete`，主动刷新列表。
2. **模式 B：插件主动注册（`REGISTER`）**
   - 适用于项目分散在远端不同路径、不同机器或动态发现的场景。
   - 新增宿主 API，允许插件推送并增量维护工作区列表：
     - `host.workspaces.upsert`：`{ backendId, workspaces: WorkspaceItem[] }`
     - `host.workspaces.remove`：`{ backendId, workspaceIds: string[] }`
     - `host.workspaces.set`：`{ backendId, workspaces: WorkspaceItem[] }`（全量对齐可选）
3. **混合模式（`HYBRID`）**：支持插件先注册固定入口，同时也响应用户主动刷新 `workspace.refresh`。

`WorkspaceItem` 核心字段：

```typescript
export interface WorkspaceItem {
  id: string;             // 后端内稳定唯一 ID
  name: string;           // 展示名称
  path: string;           // 后端视角路径（供文件树、Bash、AI 展示）
  group?: string;         // 可选分组（如 Host 名 / 标签）
  available: boolean;     // 当前是否可达
  capabilities?: {
    createChild?: boolean;
    delete?: boolean;
    git?: boolean;
  };
}
```

---

## 4. 轴二：Agent 工作流（`workflows`）

### 4.1 统一收口到 `AgentRunManager`

废除 `AIAgentViewModel` 中独立于 `AgentRunManager` 之外的 `PluginRemoteRunManager` 与流式状态机。`AgentRunManager` 根据会话/全局配置解析 `workflowId`：

- `builtin:stateful-agent` → 现有 `StatefulAgentWorkflow`
- `<pluginId>:<workflowId>` → `PluginAgentWorkflowAdapter`（同样实现 `AgentWorkflow` 接口）

由此所有工作流自动共享：
- 进程级生命周期与保活（Activity 重建不丢运行态）
- 并发控制、排队请求、取消语义（`stop`）
- `StreamingAssistantStore`、思考计时、`runningTools`、`contextUsages`
- `ToolPermissionManager`、`AskUserQuestionManager`、`PlanApprovalManager`
- 完成通知与提示音

### 4.2 消息存储边界（配合 App 会话使用时）

当组合为 **「App 管会话 + 插件管 Agent 工作流」** 时，**消息历史由 App 的 Room 持久化作为唯一事实源**：

1. 插件工作流不另存消息副本，只在单轮执行期间通过事件流向 `AgentRunManager` 输出，由 `AgentRunManager` 复用既有 `messagePersistenceUseCase.persist(...)` 落库。
2. `ChatSession` 增加轻量不透明字段：
   - `workflowId: String?`（null 表示内置默认工作流）
   - `workflowState: String?`（插件可选写入的外部上下文游标/threadId，由宿主保存并在下次 `startTurn` 原样回传）
3. 宿主向插件 `workflow.startTurn` 传入：
   - `sessionId`、`turnId`、`userMessage`、`attachments`
   - `workflowState`（上次保存的游标）与可选裁剪后的 `recentMessages`（当远端状态失效时可供插件重建上下文）
   - 当前 `mode`（`BUILD / PLAN / AUTO`）、`provider/model` 选择、`workspace` 上下文

### 4.3 工作流 Handler 与标准化事件

插件工作流只需实现：
- `workflow.startTurn`：启动一轮执行并立即返回受理回执 `{ turnId }`（或通过流通道持续推送）。
- `workflow.cancelTurn`：中断指定 `turnId`。
- `workflow.compact`（可选）：触发手动压缩 `/compress`。
- `workflow.generateTitle`（可选）：生成会话标题；不实现时可回退宿主默认标题生成。

运行中插件向宿主推送的事件直接映射到 `AgentEvent`（不再定义两套事件格式）：
- `assistant.delta` / `reasoning.delta` → `AgentEvent.AssistantDelta` / `ReasoningDelta`
- `assistant.message` → `AgentEvent.AssistantText`（支持携带 `toolCalls` 与 `usage`）
- `tool.preparing` / `tool.started` / `tool.progress` / `tool.finished` → 对应 `AgentEvent.ToolCall*`
- `retrying` / `keySwitched` / `contextUsage` / `compaction.*` / `modeChanged`
- `turn.completed`（可附带更新后的 `workflowState`）/ `turn.failed`

交互能力直接复用宿主现有能力，不重复造轮子：
- 审批确认：复用 `host.permissions.request` / `host.permissions.cancel`
- 向用户提问 / 计划审批：复用宿主交互桥（可从现有 `AskUserQuestionManager` / `PlanApprovalManager` 开放对应 `host.ui.*` 请求方法）
- 若插件工作流需要调用 App 内置工具（如 `browser`、`shizuku`、`mcp`）或用户配置的模型，直接复用现有 `host.tools.execute` 与 `host.model.*`

---

## 5. 轴三：外部会话源（`sessions`）与增量同步

当**会话历史本身归属于外部系统**（不落本地 Room）时，插件声明 `contributes.sessions`。本轴只负责「会话元数据列表 + 历史消息分页 + 会话管理」，**不再混入执行通道或 `executionMode: "remote"` 字段**。

### 5.1 增量推送 API（替代全量 `setSessions`）

在宿主 `PluginSessionRegistry`（或更名后的 `ExternalSessionStore`）上全面支持增量操作：

- `host.sessions.upsert`：`{ sourceId, sessions: ExternalSessionSummary[] }`
  按 `sessionId` 合并插入或更新（标题、运行态、更新时间、关联的 `workflowId` / `backendId` / `workspaceId`），列表仅局部 Diff 更新。
- `host.sessions.remove`：`{ sourceId, sessionIds: string[] }`
  精确移除指定会话。
- `host.sessions.replaceAll`：`{ sourceId, sessions: ExternalSessionSummary[] }`
  仅在首次全量拉取或断线全量重同步时使用。
- `host.sessions.setLoading` / `host.sessions.setError` / `host.sessions.setThinking`
  维持分组加载态、错误提示与会话级思考气泡控制。

### 5.2 清单 Handlers 补齐完整生命周期

```json
{
  "contributes": {
    "sessions": [
      {
        "id": "my-remote",
        "title": { "default": "Remote Sessions" },
        "handlers": {
          "refresh": "sessions.refresh",
          "readMessages": "sessions.readMessages",
          "createSession": "sessions.create",
          "deleteSession": "sessions.delete",
          "renameSession": "sessions.rename",
          "pinSession": "sessions.pin"
        }
      }
    ]
  }
}
```

- 发消息与中断不再绑在 `sessions` 内部：
  - 若该外部会话同时指定了 `workflowId`（可为插件工作流，也可为内置工作流），发消息统一经过 `AgentRunManager` 调度；
  - 读取历史走 `sessions.readMessages`（返回分页快照），实时增量由绑定工作流推送并更新内存会话投影。

---

## 6. 高速传输层设计（控制面 + 二进制数据面）

### 6.1 瓶颈定位

当前 `PluginProcessTransport` 存在四处性能限制：
1. 全部载荷经 `stdin/stdout` 单通道 NDJSON 传输；
2. `BoundedLineReader`（`PluginProcessTransport.kt:287`）采用逐字节 `input.read()` 循环读取并拷贝字节数组，单行上限 `4 MiB`；
3. 文件二进制与大输出需要 Base64 编码和 JSON 转义，体积膨胀约 33% 且加重 GC/序列化开销；
4. 所有写操作共用同一把 `writeMutex`，大块输出会阻塞紧急控制信令（如取消、审批）。

### 6.2 分层演进方案

保持对轻量插件（只用普通 tools / hooks / uiEntries）的**完全零成本兼容**，为高时效性插件引入独立的二进制高速通道：

#### 1) 传输介质：Unix Domain Socket（`AF_UNIX`）
- 宿主在 App 私有目录创建 `LocalServerSocket`（或按插件会话创建 socket 文件），路径通过环境变量（如 `AICODE_IPC_SOCKET`）注入进程。
- PRoot 容器默认已绑定挂载 `.aicode` 相关私有路径，容器内 Node.js（`net.connect({ path })`）与 Python（`socket.AF_UNIX`）均可零依赖直连，无需 TCP 端口协商，也不受网络防火墙影响。

#### 2) 紧凑二进制多路复用帧格式
所有高吞吐请求（`fs.*`、`exec.stream`、`pty.*`、`workflow` 流事件）走统一帧格式，**元信息与原始二进制载荷分离**：

```text
+--------+------+----------+---------+------------+----------------------+-------------------+
| Magic  | Type | StreamId | MetaLen | PayloadLen | Meta (UTF-8 JSON)    | Payload (Raw)     |
| 2B     | 1B   | 4B (u32) | 4B(u32) | 4B (u32)   | MetaLen bytes        | PayloadLen bytes  |
+--------+------+----------+---------+------------+----------------------+-------------------+
```

- **Type**：`REQ (1)`、`RESP (2)`、`ERR (3)`、`STREAM_DATA (4)`、`STREAM_END (5)`、`EVENT (6)`、`PING (7)`。
- **核心收益**：
  - `fs.read` / `fs.write` / `pty` 输出 / `exec.stream` 的原始文本或二进制直接写入 `Payload`，**零 Base64、零 JSON 转义、无 4 MiB 单行限制**（大文件按 `64 KiB` 分片发送，通过 `StreamId` 多路复用）。
  - `Meta` 仅包含几十字节的调度信息（`id` / `method` / `status` / `offset` / `eof`），JSON 解析开销几乎归零。
  - 按 `StreamId` 交错分片与独立队列发送，彻底消除大文件传输对控制帧（取消、权限确认）的队头阻塞。

#### 3) SDK 透明封装与高频事件合批
- 在 `plugins/sdk` 中封装二进制通道客户端与分帧读写器，插件业务代码直接使用异步生成器 / `Buffer` / `Uint8Array`，不感知底层字节拼接。
- 对高频小颗粒输出（Token Delta、`exec.stream` 单行日志、PTY 回显）在发送侧增加 `16~30ms` 微批合并（coalescing），减少跨进程唤醒与 UI 重组次数。
- **立即可做的底层修补**：在 `PluginProcessTransport.BoundedLineReader` 中将逐字节 `input.read()` 改为固定块缓冲区（如 `8 KiB`）批量扫描 `\n`，同时降低兼容层的 CPU 开销。

---

## 7. 旧 API 移除与清理清单

当前插件系统处于预览阶段，以下为配合 `sessionSources` 与 `PluginRemoteRunManager` 引入的过渡性接口与分叉逻辑，在新架构切换后应整体移除（若分期推进，清理阶段需同步更新脚本校验并移除对应测试）：

### 7.1 宿主 Kotlin 代码（`app/`）

| 文件路径 | 移除/重构内容 |
| --- | --- |
| `feature/plugin/runtime/PluginRemoteRunManager.kt` | **整文件移除**（OpenAI 伪装流与自定义头逻辑收归 `PluginAgentWorkflowAdapter` + `AgentRunManager`） |
| `feature/plugin/runtime/PluginSessionSourceService.kt` | **整文件移除**（由新的 `ExternalSessionService` 取代） |
| `feature/plugin/runtime/PluginSessionHostService.kt` | **整文件移除**（由支持增量的会话宿主服务取代） |
| `feature/plugin/runtime/PluginSessionRegistry.kt` | **整文件移除**（由支持 `upsert/remove` 的新注册表取代） |
| `feature/plugin/runtime/PluginSessionEventBus.kt` | **整文件移除**（工作流事件改走 `AgentWorkflow.executeEvents` 标准流） |
| `feature/plugin/domain/session/PluginSessionModels.kt` | **移除旧模型**（`PluginSessionSummary.executionMode` 等过渡字段清理，按新契约重建） |
| `feature/agent/presentation/PluginSessionUiMapper.kt` | **移除或重构**（剥离 `executionMode` / 旧块结构映射） |
| `feature/agent/presentation/AIAgentViewModel.kt` | **移除分叉逻辑**：`openPluginSession`、`sendPluginMessage`、`handleGenericPluginEvent`、`handlePluginStreamEvent`（含 `ds-harness.session` 硬编码）、`refreshPluginSessionAfterSend`、`pluginRemoteRunManager` 注入及 `session.executionMode == "remote"` 判断 |
| `feature/plugin/runtime/PluginRuntimeManager.kt` | **移除分发分支**：`host.sessionSource.setSessions`、`setLoading`、`setError`、`setThinking`、`setMessages`、`pushEvent` |
| `feature/plugin/presentation/PluginWebBridge.kt` & `overlay/PluginOverlayBridge.kt` | **移除分支**：`sessionSource.setMessages`、`sessionSource.pushEvent` |
| `feature/plugin/domain/PluginManifest.kt`、`PluginManifestParser.kt`、`PluginContent.kt`、`PluginsSection.kt` | **移除旧贡献点**：`contributes.sessionSources`、`PluginSessionSourceRef`、`PluginSessionSourceHandlersRef`、`PluginSessionSourceDefinition` |
| `feature/settings/data/repository/ExecutionModeRepository.kt` & `ExecutionModeHolder.kt` | **重构成后端注册表**：移除固定二元枚举 `ExecutionMode { LOCAL_PROOT, REMOTE_SSH }` 及全仓直接引用 |

### 7.2 清单 Schema、SDK 与页面桥（`plugins/` 与 `assets/`）

| 文件路径 | 移除内容 |
| --- | --- |
| `plugins/schema/plugin.schema.json` | 移除 `contributes.sessionSources` 定义 |
| `plugins/sdk/types/host.d.ts` | 移除 `SessionSourceSession`（含 `executionMode?: 'remote'`）、`SessionSourceMessage`、`SessionSourcePage`、`SessionSourceEvent` 以及 `HostMethodMap` 中 6 个 `host.sessionSource.*` 方法与 `SESSION_SOURCE_*` 错误码 |
| `plugins/sdk/src/index.ts` | 移除 `export const sessionSource` |
| `plugins/sdk/types/aicode.d.ts` | 移除 `sessionSource` 接口声明 |
| `app/src/main/assets/plugin-ui/aicode.js` | 移除 `sessionSource` 方法代理 |

> 清理上述 SDK 与桥接后，按仓库规范运行对账脚本验证一致性：
> `npm --prefix plugins run check:host-methods`、`check:page-bridge`、`check:manifests`、`typecheck:plugins` 以及 `node scripts/test-plugin-sdk.cjs`。

---

## 8. 建议实施顺序

为控制每一步的验证面与回归风险，建议按以下顺序推进（每步可独立编译验证）：

1. **Phase 1：高速传输基础（低风险、独立可测）**
   - 优化 `PluginProcessTransport.BoundedLineReader` 块读取。
   - 新增 Unix Domain Socket 数据通道服务与二进制分帧编解码（Kotlin 侧 + `plugins/sdk`）。
2. **Phase 2：执行后端轴与工作区双模式（收益最高）**
   - 建立 `ExecutionBackendRegistry`、`BackendCapabilities` 与 `PluginExecutionBackendAdapter`。
   - 将 `DelegatingCommandEngine`、`DelegatingFileAccess`、`DelegatingTerminalSessionProvider` 迁移到后端注册表，消除业务层对 `ExecutionMode.REMOTE_SSH` 的直接依赖。
   - 改造 `WorkspaceRepository`，支持扫描（`SCAN`）与插件增量注册（`host.workspaces.upsert/remove`）。
3. **Phase 3：工作流轴（替换 `PluginRemoteRunManager`）**
   - `ChatSession` 递增迁移添加 `workflowId`、`workflowState`。
   - 实现 `PluginAgentWorkflowAdapter`，在 `AgentRunManager` 中按 `workflowId` 路由，打通事件映射与交互桥。
4. **Phase 4：增量会话轴与旧 API 彻底清理**
   - 上线 `contributes.sessions`（`host.sessions.upsert/remove` + `delete/rename/pin` handler）。
   - 移除第 7 节列出的全部旧 `sessionSources` / `PluginRemoteRunManager` 代码与 SDK 定义。
   - 同步更新 `docs-site/docs/advanced/plugin/` 用户开发者文档与 `plugins/samples/` 示例。
