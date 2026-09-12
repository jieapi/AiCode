# agent — AI Agent 核心模块

驱动 LLM 对话循环的最大业务模块：提示词组装 → 多协议 provider 调用 → 工具调用与权限管控 → 回填结果，直至产出最终回复。同时拥有 MCP 扩展、检查点回滚、子代理、上下文压缩等全部 Agent 能力。

## 结构

```
feature/agent/
├── data/
│   ├── local/
│   │   ├── database/AgentDatabase.kt   # Room 主库，SCHEMA_VERSION = 52
│   │   ├── entity/                     # 6 个实体：会话/消息/待办/检查点×2/调用记录
│   │   └── dao/                        # 5 个 DAO
│   └── remote/
│       ├── anthropic/                  # AnthropicApi + DTO（Messages API）
│       ├── openai/                     # OpenAIApi（Chat Completions + Responses + Images）
│       ├── gemini/                     # GeminiApi（generateContent + Interactions）
│       └── RemoteSshConnection.kt      # 共享 sshj client（远程后端的总连接）
├── domain/
│   ├── workflow/StatefulAgentWorkflow.kt  # 状态机主循环（AgentEvent 事件流）
│   ├── provider/                       # AIProvider 接口 + 三适配器 + RetryPolicy/Key 轮换
│   ├── tool/                           # AgentTool 基类 + 19 个内置工具 + ToolRegistry
│   ├── permission/ToolPermissionPolicyEngine.kt  # 授权策略引擎
│   ├── mcp/McpManager.kt               # MCP stdio/HTTP 客户端与动态工具注册
│   ├── checkpoint/CheckpointManager.kt # 检查点创建/快照/回滚
│   ├── subagent/                       # AgentDefinition + 事件总线 + 定义源
│   ├── session/                        # SessionUseCase / MessagePersistenceUseCase
│   ├── model/                          # ChatSession / AgentMessage / AgentContext
│   ├── command/                        # 斜杠命令（multibinding 注册）
│   └── ...（memory / skill / todo / container / prompt）
└── presentation/
    ├── AIAgentViewModel.kt             # 约 1900 行，agent 功能唯一 ViewModel
    └── component/                      # AIChatPanel、消息气泡、Markdown 渲染链、回滚面板等
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/workflow/StatefulAgentWorkflow.kt` | 核心状态机：LLM 请求 → 工具循环 → 权限弹窗 → checkpoint，20+ 依赖注入 |
| `domain/tool/AgentTool.kt` | 工具公共接口（见 [接口文档](../INTERFACES.md)） |
| `domain/tool/ToolRegistry.kt` | @Singleton 保序注册表；PLAN 模式拦截交给策略引擎 |
| `domain/permission/ToolPermissionPolicyEngine.kt` | 弹窗前 ALLOW/DENY/ASK 判定 + PLAN 物理拦截 + AUTO 灾难命令防护 |
| `domain/mcp/McpManager.kt` | 订阅 4 信号源自动 reload；stdio 跑在 PRoot 容器内，HTTP 走 Streamable + SSE |
| `domain/checkpoint/CheckpointManager.kt` | 每条消息建节点、改前抓快照、三维回滚 |
| `domain/provider/AIProvider.kt` | 统一 provider 抽象（complete / completeStream） |
| `presentation/AIAgentViewModel.kt` | 多会话并行 UI 状态、权限弹窗回传、子代理事件、唤醒锁与保活 |

## 依赖

**本模块依赖**:
- `feature/terminal` — `CommandEngine` 执行 Shell
- `feature/workspace` — `FileAccessProvider` 读写文件
- `feature/settings` — `AIProviderRepository`（模型配置）、`ExecutionModeHolder`（模式）
- `feature/credentials` — 远程 SSH 凭据
- `terminal-emulator` — 后台命令的输出解析

**依赖本模块的**:
- `MainActivity` / `WorkbenchPane` — 聊天面板挂载
- `feature/editor` — 复用 `MarkdownContent` 渲染链

## 规范

### 代码模式

**新工具**：继承 `AgentTool`（流式输出实现 `StreamingAgentTool`），在 `di/AgentModule.kt` 注册，同步更新 `assets/prompts/`。详见[开发者指南](../DEVELOPER_GUIDE.md)。

**授权**：工具调用统一走 `ToolPermissionPolicyEngine`，判定顺序 DENY → 静态不可判定 ASK → 内置白名单 → 已记忆规则 → ASK。

**错误处理**：工具失败返回 `ToolResult.Error` 回传给 LLM（AI 自行重试/改道），进程级异常经 workflow 事件流上报 UI；LLM 网络错误由 `RetryPolicy` 阶梯重试，Key 失效由 `KeyFailureClassifier` 触发轮换。

### 测试

纯 JVM 单测 + MockK 打桩 + `kotlinx-coroutines-test`（`runTest` 虚拟时间）；数据库迁移测试用 Robolectric + `MigrationTestHelper`（仅 universalDebug 变体）。
