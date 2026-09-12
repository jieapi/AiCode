# AiCode 项目文档

本文档基于对代码仓库的递归分析生成，覆盖系统架构、模块职责、扩展点接口、领域概念与开发工作流，面向参与本项目的开发者与 AI 协作代理。

**快速链接**: [架构](./ARCHITECTURE.md) | [接口](./INTERFACES.md) | [开发者指南](./DEVELOPER_GUIDE.md)

---

## 核心文档

### [架构](./ARCHITECTURE.md)
系统设计、技术栈、组件结构、关键架构决策（本地/远程委托模式、targetSdk 28 锁定、数据库迁移双轨制、flavor 拆包）与 Mermaid 架构图。从这里开始了解系统如何运作。

### [接口](./INTERFACES.md)
AI 工具系统（19 个内置工具 + 扩展点）、LLM Provider 适配（OpenAI / Anthropic / Gemini）、MCP 协议、执行后端抽象接口、Room 数据契约与内部脚本。集成或扩展系统的参考。

### [开发者指南](./DEVELOPER_GUIDE.md)
环境搭建、构建变体、分支与提交规范、资产同步硬规则（prompts / docs-site / 双语 strings）、常见任务（加迁移、加工具、加设置、发版）。贡献者必读。

---

## 模块

| 模块 | 描述 | 文档 |
|------|------|------|
| `feature/agent/` | AI Agent 核心：workflow 状态机、工具系统、权限引擎、MCP、检查点、子代理 | [agent](./模块/agent.md) |
| `feature/terminal/` | 终端会话（本地 PRoot / 远程 SSH 统一抽象）+ 进程保活 | [terminal](./模块/terminal.md) |
| `feature/workspace/` | 工作区管理、文件访问后端、SAF Provider、SFTP/FTP 同步 | [workspace](./模块/workspace.md) |
| `feature/settings/` | Provider 管理、执行模式、20+ 分域 DataStore 设置 | [settings](./模块/settings.md) |
| `feature/editor/` | sora-editor 封装、TextMate 语法高亮、编码检测 | [editor](./模块/editor.md) |
| `feature/git/` | 容器内命令行 git 的可视化封装（状态/分支/历史/diff） | [git](./模块/git.md) |
| `feature/backup/` | tar.gz + AES-GCM 加密备份恢复（流式） | [backup](./模块/backup.md) |
| `feature/credentials/` | Git 凭据文件仓库与 helper 文件 IPC 桥 | [credentials](./模块/credentials.md) |
| `core/` | 数据库迁移加载、全局代理、主题、通用组件、日志 | [core](./模块/core.md) |
| `:terminal-emulator` / `:terminal-view` | Termux 派生终端模块（Java，Apache 2.0） | 见 [架构-项目结构](./ARCHITECTURE.md#项目结构) |

`feature/onboarding/`（首启 spotlight 引导）体量小、自包含：`domain/OnboardingStep.kt` 定义 10 步流程，`OnboardingCoordinator` 桥接状态机与 DataStore 持久化，UI 侧 `OnboardingOverlay` + `SpotlightOverlay` 实现挖孔高亮。

---

## 核心概念

理解这些领域概念有助于导航代码库：

| 概念 | 描述 |
|------|------|
| [执行模式](./专有概念/执行模式.md) | 本地 PRoot 容器 vs 远程 SSH，三个委托层的分发依据 |
| [Agent 权限模式](./专有概念/Agent权限模式.md) | BUILD / PLAN / AUTO 三档授权范围与判定顺序 |
| [PRoot 容器](./专有概念/PRoot容器.md) | 免 root 的 Alpine 用户态容器机制与构建打包约束 |
| [检查点](./专有概念/检查点.md) | AI 改码前的自动快照与三维回滚 |
| [子代理](./专有概念/子代理.md) | 独立上下文的后台并行代理，定义可全局/项目级定制 |

---

## 入门指南

### 项目新人？

按此路径学习：
1. **[架构](./ARCHITECTURE.md)** — 了解全局与关键架构决策
2. **[核心概念](#核心概念)** — 学习领域术语
3. **[开发者指南](./DEVELOPER_GUIDE.md)** — 搭建环境、跑通构建
4. **[模块文档](#模块)** — 深入负责的模块

### 需要扩展功能？

1. **[接口](./INTERFACES.md)** — 工具系统、provider 适配、MCP 等扩展点契约
2. **[开发者指南 - 常见任务](./DEVELOPER_GUIDE.md#常见任务)** — 加工具 / 加迁移 / 加设置的分步指南

### 定位问题？

1. **[架构 - 子系统](./ARCHITECTURE.md#子系统)** — 按功能定位模块
2. **[模块文档](#模块)** — 各模块的错误处理与代码模式约定

---

## 快速参考

### 命令

```bash
./gradlew :app:assembleUniversalDebug      # 冒烟编译（日常默认）
./gradlew :app:testUniversalDebugUnitTest  # 单元测试
python3 scripts/check_migrations.py        # 迁移对账（推送前必跑）
./gradlew checkMigrations                  # 同上（Gradle 包装）
./gradlew assembleRelease                  # 发版构建三 flavor APK
```

### 重要文件

| 文件 | 目的 |
|------|------|
| `app/build.gradle.kts` | 构建配置（版本号规则、flavor、proot 打包约束，注释信息量大） |
| `app/src/main/java/com/aicode/di/AgentModule.kt` | Hilt 核心装配（数据库、Retrofit、ToolRegistry、委托绑定） |
| `app/src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt` | Room 主库，SCHEMA_VERSION 当前 52 |
| `app/src/main/assets/migrations/` | 文件式 SQL 迁移脚本（编号必须连续） |
| `app/src/main/assets/prompts/` | AI 系统提示词分片（改动 agent 行为须同步） |
| `CLAUDE.md` | AI 协作规则（构建验证、资产同步、发版流程） |
