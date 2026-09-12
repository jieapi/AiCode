# 需求文档：TARGET 目标驱动模式

## 简介

在现有三档权限模式（BUILD / PLAN / AUTO）之外，新增第四档 **TARGET（目标驱动）** 模式。用户进入该模式时设定一个明确目标，AI 自主规划并执行工具调用直到目标达成或失败，期间免逐步弹窗授权，但保留灾难性命令防护与检查点回滚。TARGET 与现有三档完全互切。

**动机**：处理多步骤复杂任务时，BUILD 逐步授权打断执行流，AUTO 无终止条件易失控。TARGET 填补「自主执行 + 明确终止」的中间地带，并由专用 `completeGoal` 工具声明达成、DataStore 可配置阈值约束终止。

## 术语表

- **TARGET 模式**：目标驱动权限模式。AI 依据目标声明自主执行，达成或失败即终止。
- **目标声明（Goal Statement）**：进入 TARGET 模式时由用户输入或 AI 提炼、用户确认的明确目标描述，持久化到会话。
- **终止条件（Termination Condition）**：触发 TARGET 模式退出的判定，包括目标达成、连续失败、最大步数上限、用户手动切换。
- **最大步数上限（Max Step Budget）**：单次 TARGET 执行允许的工具调用次数上限，超出即判定失败终止。
- **检查点（Checkpoint）**：文件改动前的自动快照，见 [检查点概念](../../docs/专有概念/检查点.md)。
- **灾难命令防护**：对递归删除根目录类 `rm` 等高危命令的物理拦截，AUTO 与 TARGET 共用。
- **AgentMode**：领域模型，`ChatSession` 携带的权限模式枚举（BUILD / PLAN / AUTO / TARGET）。

## 现状参考

| 方面 | 现有实现 | 位置 |
|------|---------|------|
| 模式定义 | `AgentMode` 枚举（BUILD/PLAN/AUTO） | `feature/agent/domain/model/` |
| 策略引擎 | `ToolPermissionPolicyEngine.resolve()` 判定 ALLOW/DENY/ASK | `feature/agent/domain/permission/ToolPermissionPolicyEngine.kt` |
| 模式切换工具 | `switchMode`（PLAN/BUILD，AUTO 仅手动） | `feature/agent/domain/tool/mode/SwitchModeTool.kt` |
| 内置工具数 | 19 个（TARGET 需新增 `completeGoal` 为第 20 个） | `feature/agent/domain/tool/` + `di/AgentModule.kt` |
| 计划批准流 | `PlanApprovalManager` | `feature/agent/domain/` |
| 持久化 | `ChatSessionEntity` 模式字段 | `feature/agent/data/local/entity/ChatSessionEntity.kt` |
| 提示词 | `80-plan-mode.md`、`81-auto-mode.md` | `app/src/main/assets/prompts/` |
| 灾难防护 | AUTO 模式 rm 防护 | `ToolPermissionPolicyEngine.kt` |
| 检查点 | 每条消息建节点、改前抓快照 | `feature/agent/domain/checkpoint/CheckpointManager.kt` |

## 需求

### 需求 1：进入 TARGET 模式并设定目标

**用户故事**：作为开发者，我希望设定一个明确目标让 AI 自主达成，以便处理多步骤复杂任务时不必逐步授权。

#### 验收标准

1. WHEN 用户选择 TARGET 模式，AiCode SHALL 提示用户输入目标声明或确认 AI 提炼的目标声明。
2. IF 目标声明为空，AiCode SHALL 拒绝进入 TARGET 模式并提示用户填写目标。
3. WHILE 处于 TARGET 模式，AiCode SHALL 将目标声明持久化到所属会话。
4. WHEN 用户从 BUILD 或 PLAN 或 AUTO 切换到 TARGET，AiCode SHALL 要求设定目标声明后激活 TARGET。
5. WHEN AI 通过 `switchMode` 工具请求切换到 TARGET，AiCode SHALL 经授权策略校验后提示用户提供目标声明。

### 需求 2：自主执行与终止条件

**用户故事**：作为开发者，我希望 TARGET 模式在达成或失败时自动终止，以便资源不被无限占用且结果可控。

#### 验收标准

1. WHILE 处于 TARGET 模式，AiCode SHALL 免逐步弹窗授权执行工具调用（授权策略等同 AUTO）。
2. WHILE 处于 TARGET 模式，AiCode SHALL 保留灾难性命令防护（递归删除根目录类 `rm` 物理拦截）。
3. WHEN AI 声明目标已达成并经用户确认，AiCode SHALL 退出 TARGET 模式回到 BUILD。
4. IF 连续工具调用失败次数达到连续失败阈值，AiCode SHALL 停止执行并将失败原因通知用户。
5. IF 单次 TARGET 执行的工具调用次数达到最大步数上限，AiCode SHALL 停止执行并提示用户目标未在预算内达成。
6. WHEN TARGET 模式因终止条件退出，AiCode SHALL 记录终止原因（达成 / 失败 / 步数超限 / 用户中断）到会话。

### 需求 3：模式互切与中断

**用户故事**：作为开发者，我希望在任意模式间切换到 TARGET，并能随时中断目标执行切回其他模式。

#### 验收标准

1. WHEN 用户从 TARGET 切换到 BUILD 或 PLAN 或 AUTO，AiCode SHALL 中止当前目标执行并记录中断点。
2. WHEN 用户从 BUILD 切换到 TARGET，AiCode SHALL 进入目标设定流程。
3. WHEN 用户从 PLAN 切换到 TARGET，AiCode SHALL 将已批准计划作为候选目标声明。
4. WHEN 用户从 AUTO 切换到 TARGET，AiCode SHALL 要求设定目标声明以约束后续执行。
5. WHILE 处于 TARGET 模式且用户中断执行，AiCode SHALL 保留已产生的检查点供回滚。

### 需求 4：检查点与回滚

**用户故事**：作为开发者，我希望目标模式执行期间保留检查点，以便自主改动可整体回滚。

#### 验收标准

1. WHILE 处于 TARGET 模式，AiCode SHALL 对每个文件改动创建检查点快照。
2. WHEN 用户在 TARGET 会话结束后选择回滚，AiCode SHALL 提供按目标会话维度的整体回滚。
3. WHILE 处于 TARGET 模式，AiCode SHALL 在目标达成时创建聚合检查点节点标记整体改动范围。

### 需求 5：数据持久化与迁移

**用户故事**：作为开发者，我希望目标模式的目标声明与执行状态持久化，以便重启后可恢复与审计。

#### 验收标准

1. WHILE 会话处于 TARGET 模式，AiCode SHALL 持久化目标声明、终止原因与步数计数到 `ChatSessionEntity`。
2. WHEN 数据库升级到目标 schema 版本，AiCode SHALL 通过文件式迁移脚本为 `chat_sessions` 表扩展目标相关字段。
3. WHEN 迁移执行，AiCode SHALL 保证现有 BUILD/PLAN/AUTO 会话的模式字段值不被篡改。
4. IF 迁移脚本编号不连续，AiCode SHALL 经 `check_migrations.py` 拦截并报错。

### 需求 6：提示词与资产同步

**用户故事**：作为开发者，我希望 TARGET 模式有专属提示词指导 AI 行为，并随代码同步。

#### 验收标准

1. WHEN TARGET 模式启用，AiCode SHALL 加载 `app/src/main/assets/prompts/82-target-mode.md` 提示词分片。
2. WHILE 处于 TARGET 模式，AiCode SHALL 向 LLM 注入目标声明与终止条件约束。
3. WHEN TARGET 模式行为调整，AiCode SHALL 同步更新 `82-target-mode.md` 与 `prompts/` 下相关索引。
4. WHEN TARGET 模式上线，AiCode SHALL 在 `docs-site/docs/` 补充用户使用文档并加入侧栏索引。

### 需求 7：UI 入口与双语文案

**用户故事**：作为用户，我希望在模式切换入口看到 TARGET 选项，并以我的语言呈现文案。

#### 验收标准

1. WHEN 用户打开模式切换入口，AiCode SHALL 展示 BUILD / PLAN / AUTO / TARGET 四个选项。
2. WHEN TARGET 选项被选中，AiCode SHALL 展示目标声明输入界面。
3. WHILE 处于 TARGET 模式，AiCode SHALL 在会话顶部持续展示当前目标声明与步数计数。
4. WHEN TARGET 相关用户可见文案新增，AiCode SHALL 写入 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文）并以 `stringResource(R.string.xxx)` 引用。
5. IF `.kt` 文件中出现硬编码中文 UI 文案，AiCode SHALL 视为缺陷。

### 需求 8：SwitchModeTool 扩展（TARGET 进入）

**用户故事**：作为 AI，我希望通过 `switchMode` 工具切换到 TARGET 并设定目标，以便在对话中自主进入目标驱动执行。

#### 验收标准

1. WHEN `switchMode` 工具收到 `mode=target` 参数，AiCode SHALL 经授权策略校验后激活 TARGET。
2. WHEN `switchMode` 工具收到 `goal` 参数，AiCode SHALL 将其作为目标声明持久化。
3. IF `switchMode` 工具收到 `mode=target` 但缺少 `goal` 参数，AiCode SHALL 返回工具错误提示要求提供目标。
4. WHEN 用户从 TARGET 通过 `switchMode` 切换到其他模式，AiCode SHALL 记录中断并保留检查点。

### 需求 9：completeGoal 工具（目标达成声明）

**用户故事**：作为 AI，我希望通过专用工具声明目标已达成，以便语义明确地结束 TARGET 执行并提示用户确认。

#### 验收标准

1. WHEN AI 在 TARGET 模式调用 `completeGoal` 工具，AiCode SHALL 展示达成总结并提示用户确认。
2. WHEN 用户确认达成，AiCode SHALL 退出 TARGET 模式回到 BUILD 并记录 `goalTerminationReason = ACHIEVED`。
3. IF `completeGoal` 工具在非 TARGET 模式被调用，AiCode SHALL 返回工具错误提示当前不在目标模式。
4. WHEN `completeGoal` 工具被调用，AiCode SHALL 要求提供 `summary` 参数描述完成的工作与最终状态。
5. WHEN `completeGoal` 工具注册，AiCode SHALL 在内置工具清单中登记为第 20 个工具并同步 `INTERFACES.md`。

### 需求 10：阈值 DataStore 可配置

**用户故事**：作为用户，我希望调整最大步数与连续失败阈值，以便适配不同复杂度的目标任务。

#### 验收标准

1. WHEN 用户打开设置页 TARGET 配置入口，AiCode SHALL 展示最大步数与连续失败阈值当前值（默认 50 / 5）。
2. WHEN 用户修改阈值，AiCode SHALL 经 DataStore 持久化并即时生效于后续 TARGET 执行。
3. WHILE 处于 TARGET 执行中，AiCode SHALL 读取当前阈值用于终止判定。

## 范围边界

### 包含

- `AgentMode` 枚举新增 TARGET + `GoalTerminationReason` 枚举
- `ToolPermissionPolicyEngine` 扩展 TARGET 分支（授权策略等同 AUTO）
- `SwitchModeTool` 扩展 `goal` 参数
- 新增 `completeGoal` 工具（第 20 个内置工具）声明目标达成
- `ChatSessionEntity` 新增目标相关字段 + 迁移脚本
- `TargetModeSettingsRepository`（DataStore，阈值默认 50/5，可配置）
- `prompts/82-target-mode.md` 新增
- UI 模式切换入口新增 TARGET 选项 + 目标输入界面 + 会话顶部状态条 + 设置页阈值入口
- 双语 `strings.xml` 文案
- `docs-site` 用户文档

### 不包含

- 目标声明的自然语言解析与拆解（AI 自行在对话中理解，本需求只做存储与注入）
- 跨会话的目标复用（每个 TARGET 会话独立）
- 目标达成的自动判定算法（依赖 AI 声明 + 用户确认）
- 子代理在 TARGET 模式下的特殊调度（沿用现有子代理机制）

## 约束

- `targetSdk` 锁定 28（项目硬约束，本需求不触碰）。
- 迁移脚本编号必须连续，接续当前 `SCHEMA_VERSION` 最大编号。
- `prompts/` 与 `docs-site/` 同步是硬规则（见 CLAUDE.md 资产同步）。
- 用户可见中文文案必须进双语 `strings.xml`。

## 验收方式

- 纯 JVM 单测 + MockK 打桩覆盖 `ToolPermissionPolicyEngine` 的 TARGET 分支判定。
- `SwitchModeTool` 与 `completeGoal` 工具参数校验与模式守卫单测。
- `TargetModeSettingsRepository` DataStore 读写单测。
- 数据库迁移测试用 Robolectric + `MigrationTestHelper`（universalDebug 变体）。
- `check_migrations.py` 对账通过。
- 冒烟编译 `./gradlew :app:assembleUniversalDebug` 通过。
