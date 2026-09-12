# 实施任务清单：TARGET 目标驱动权限模式

依据 `design.md` 的实施步骤摘要拆解。每完成一项勾选 `[x]`。

## 任务列表

- [x] **T1** AgentMode 枚举加 `TARGET` + 新增 `GoalTerminationReason` 枚举 + `ChatSession` 加 4 字段（goalStatement / goalTerminationReason / goalStepCount / goalFailCount）
  - `app/src/main/java/com/aicode/feature/agent/domain/model/ChatSession.kt:6`
- [x] **T2** `ChatSessionEntity` 加 4 字段 + `toDomain`/`fromDomain` 映射
  - `app/src/main/java/com/aicode/feature/agent/data/local/entity/ChatSessionEntity.kt:14`
- [x] **T3** `AgentDatabase.SCHEMA_VERSION` 52 → 53 + 新建 `53_add_target_mode_fields.sql`（迁移对账通过：8..53 共 46 个，编号连续）
  - `app/src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt:37`
  - `app/src/main/assets/migrations/53_add_target_mode_fields.sql`
- [x] **T4** `ToolPermissionPolicyEngine` AUTO 分支扩展为含 TARGET（共用灾难防护）
  - `app/src/main/java/com/aicode/feature/agent/domain/permission/ToolPermissionPolicyEngine.kt:70`
- [x] **T5** `SwitchModeTool` 参数加 `goal`、enum 加 `TARGET`、`executeWithContext` 逻辑扩展（含从 TARGET 切出记录 INTERRUPTED）
  - `app/src/main/java/com/aicode/feature/agent/domain/tool/mode/SwitchModeTool.kt:31`
- [x] **T6** 新增 `CompleteGoalTool`（第 20 个内置工具）+ `di/AgentModule.kt` 注册
  - `app/src/main/java/com/aicode/feature/agent/domain/tool/mode/CompleteGoalTool.kt`（新建）
- [x] **T7** 新增 `TargetModeSettingsRepository`（DataStore 分域 `target_mode_prefs`，默认 50/5）
  - `app/src/main/java/com/aicode/feature/settings/data/repository/TargetModeSettingsRepository.kt`（新建）
- [x] **T8** `StatefulAgentWorkflow` 注入阈值仓库 + ChatSessionDao，工具循环加步数/失败计数与终止判定 + `buildModeReminder`/`buildModeSwitchNotice` 加 TARGET 分支
  - `app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt`
- [x] **T9** 新建 `prompts/82-target-mode.md` 提示词
  - `app/src/main/assets/prompts/82-target-mode.md`（新建）
- [~] **T10** UI：模式选择器加 TARGET + 目标输入对话框（已完成）；会话顶部状态条 + 设置页阈值入口（待后续增强）
- [x] **T11** 双语 `strings.xml` 新增文案（values/ + values-en/）
- [x] **T12** `docs-site/docs/guide/modes.md` 更新为四模式 + `overview.md` 索引更新
- [ ] **T13** 单测 + 冒烟编译 `./gradlew :app:assembleUniversalDebug` + 迁移对账（迁移对账已通过；编译/单测受阻于环境缺 JDK/Android SDK）

## 依赖与顺序

T1 → T2 → T3（模型与迁移先行，编译期 schema 校验依赖）
T4 / T5 / T6 相互独立，可并行
T7 → T8（workflow 依赖阈值仓库）
T9 / T10 / T11 资源层，依赖代码层完成
T12 文档，独立
T13 验证，全部代码完成后执行
