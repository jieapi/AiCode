# 开发者指南

## 项目目的

AiCode 是 Android 端 AI 编程工具：内置 Alpine Linux 容器（PRoot）与终端模拟器，让 AI 直接读写文件、执行 Shell 命令、运行构建工具；同时支持远程 SSH 服务器作为执行后端。App 内含文件树与代码编辑器、可视化 Git 面板、可在后台并行干活的子代理。

**核心职责**:
- AI Agent 对话循环：提示词组装、多协议 LLM 调用、工具调用与权限管控、上下文压缩、检查点回滚
- 开发环境：本地 PRoot 容器 / 远程 SSH 双后端、多标签终端、代码编辑器、Git 可视化
- 数据管理：Room 持久化、20+ 分域 DataStore 设置、加密备份恢复

**相关系统**:
- Termux（`terminal-emulator` / `terminal-view` 模块）— 终端仿真核心，Apache 2.0
- OpenCode — 终端 AI 编码工具，核心灵感来源
- models.dev — 内置模型快照数据源

## 环境搭建

### 前置条件

- JDK 17（Temurin）
- Android SDK（compileSdk 36，buildTools 35.0.0）
- Python 3（运行 `scripts/check_migrations.py` 迁移对账）
- Git 完整历史（versionCode 由 `git rev-list --count HEAD` 生成，浅克隆会得到 1）

### 克隆与构建

```bash
git clone <repo-url>
cd aicode

# 冒烟编译（日常默认，单 flavor 最快）
./gradlew :app:assembleUniversalDebug

# 完整单测
./gradlew :app:testUniversalDebugUnitTest

# 迁移对账（编号连续、已发布迁移未被篡改）
python3 scripts/check_migrations.py
# 或
./gradlew checkMigrations
```

### 签名配置（release 构建）

release 签名凭据读 `app/keystore.properties`（已 gitignore）：

```properties
storeFile=aicode.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<密码>
```

本地通常不存放签名文件；CI 从 GitHub secret 还原 `app/aicode.jks`。无此文件时 release 产出 unsigned 包。

### 构建变体

| Flavor | ABI | 容器镜像 | 用途 |
|--------|-----|---------|------|
| `universal` | arm64-v8a + x86_64 | arm + x86 两套 | 通用包，体积最大 |
| `armsolo` | arm64-v8a | 仅 arm | 真机首选，体积约为一半 |
| `x86solo` | x86_64 | 仅 x86 | 模拟器/Chromebook |

debug 构建带包名后缀 `.debug`（`com.aicode.debug`），可与 release 同机共存；两者的私有目录互相隔离，已解压的容器 rootfs 与工作区项目在 debug 下不可见（需重新初始化），属预期行为。

### 日常验证命令

**别用聚合任务做日常验证**：`assembleDebug` / `assembleRelease` / `test` / `build` 都会跨三个 flavor 全跑，耗时极长。

| 用途 | 命令 |
|------|------|
| 冒烟编译 | `./gradlew :app:assembleUniversalDebug` |
| 推送前单测 | `./gradlew :app:testUniversalDebugUnitTest` |
| 推送前迁移对账 | `python3 scripts/check_migrations.py` |
| 发版构建 | `./gradlew assembleRelease` / `./gradlew bundleRelease` |

产物路径：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`。

## 开发工作流

### 分支策略

- **`main`** — 日常 bug 修复、补单测、CI/构建配置、纯文档直接提交
- **`feat/*` / `refactor/*`** — 新功能、复杂多文件改动、架构重构；验证通过后合回 `main`
- **`hotfix/*`** — 从已发布的 RC Tag 拉出（勿从最新 main 拉），修复合回 `main` 后删除
- **发版** — Tag 驱动：在 `main` 上打 `v*` Tag 触发 `android-release.yml` 自动构建签名 APK 并发 Release；严禁在 feature 分支上打 Tag

### 提交规范

Conventional Commits（`.githooks/commit-msg` 本地校验），格式 `<type>(<scope>): <subject>`：

- **type**：`feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope**：`agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- 示例：`fix(settings): 修复 provider 保存时校验失败`

### 提交前检查

改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 必跑冒烟编译；`git push` 前必跑单元测试 + 迁移对账。CI（`ci.yml`）会在 push/PR 时自动构建 + 测试 + 对账兜底。

### 资产同步硬规则

改代码时以下内容必须同步，否则功能/文档/文案会出现缺口：

| 改动 | 同步目标 |
|------|---------|
| AI 工具增删改名、参数签名变化、agent 行为调整 | `app/src/main/assets/prompts/` 下对应提示词分片 |
| UI 变化（新页面、交互、布局、文案） | `docs-site/docs/` 用户文档；新文档页同步加入 `docs-site/.vitepress/config.ts` 侧栏 |
| 新增用户可见中文文案 | 双语 strings.xml：`values/strings.xml`（中文）+ `values-en/strings.xml`（英文）；代码用 `stringResource(R.string.xxx)`，禁止在 `.kt` 硬编码中文 UI 文案 |

`docs-site/docs/` 是文档唯一事实源，构建时由 `syncAiDocs` task 复制到 `assets/docs/` 打进 APK——AI 在容器内看到的是 `~/.aicode/docs/{guide,advanced}/*.md`，与在线文档站永远一致。

### 版本号规则

版本号唯一事实源是 Git Tag / Commit，无需手写：

- `versionName` = `gitVersionName()`：Tag `v1.7.0` → `1.7.0`；Tag 后第 N 个提交 → `1.7.0-dev.N+<hash>`
- `versionCode` = `gitCommitCount()`：提交数单调递增

## 常见任务

### 添加数据库迁移

Room 数据库当前 `SCHEMA_VERSION = 52`（见 `app/src/main/java/com/aicode/feature/agent/data/local/database/AgentDatabase.kt`）。一个版本号二选一：

**文件式（默认，含数据清理/重命名/改约束的版本必须走这条）**：

1. 递增 `AgentDatabase.kt` 的 `SCHEMA_VERSION`
2. 在 `app/src/main/assets/migrations/` 新建 `{VERSION}_{description}.sql`（如 `53_add_xxx.sql`），**编号必须连续**
3. 写 DDL/SQL（字符串里可放心写 `;`，`SqlScriptSplitter` 会识别字符串字面量）

**AutoMigration（纯 schema 变更：加列/建表/索引）**：

1. 改 entity，加 `@AutoMigration(from = N-1, to = N)` 注解（忘写会直接编译失败）
2. 保证 `to == SCHEMA_VERSION` 且 `from` 衔接文件式最大版本

验证：`python3 scripts/check_migrations.py`（校验编号连续、与已发布 Tag 无冲突）。

**跨分支硬规则**：已打 `v*` Tag 的迁移文件内容与编号冻结不可改；hotfix/RC 的迁移号合回 `main` 时，`main` 上编号更小的未发布迁移必须整体重编号到该上限之后。

### 添加新 AI 工具

**需修改的文件**：
1. `app/src/main/java/com/aicode/feature/agent/domain/tool/<分类>/<XxxTool>.kt` — 继承 `AgentTool`（需要流式过程输出则实现 `StreamingAgentTool`，强制走上下文则继承 `AbstractContextualTool`）
2. `app/src/main/java/com/aicode/di/AgentModule.kt` — 注入并注册进 `ToolRegistry`
3. `app/src/main/assets/prompts/` — 在 `60-tools-and-paths.md` 等分片中同步工具说明

**步骤**：
1. 实现 `name` / `description` / `parameters` / `permissionPolicy` / `capabilities` 属性与 `execute(args)` 方法
2. 在 `AgentModule` 的 `ToolRegistry` 装配处注册
3. 如需授权弹窗/白名单，确认 `ToolPermissionPolicyEngine` 的内置安全清单是否需要更新
4. 更新提示词分片

### 添加设置项

各设置域独立一个 Preferences DataStore（`feature/settings/data/repository/` 下已有 20+ 个，如 `ThemeSettingsRepository`、`KeepaliveSettingsRepository`）。新设置项：

1. 新建 `XxxRepository`（`@Singleton`，注入 `@ApplicationContext`，定义私有 `preferencesDataStore`）
2. UI 放到 `feature/settings/presentation/component/SettingsScreen.kt` 对应分组
3. 用户可见文案进双语 strings.xml

### 添加新页面/交互

1. Compose Screen + ViewModel 放对应 feature 的 `presentation/`
2. 路由加进 `MainActivity.kt` 的 NavHost；大屏右栏容器能力在 `WorkbenchPane.kt`
3. 更新 `docs-site/docs/`（新文档页同步加侧栏与 `guide/overview.md` 索引）
4. 所有中文文案进双语 strings.xml

### 修复 Bug

1. 定位模块（对照 [架构文档](./ARCHITECTURE.md) 的子系统地图）
2. 最小改动修复；涉及共享组件签名变化时 `rg` 找出全部调用点确认都改到
3. 冒烟编译 + 相关单测
4. 提交信息用 `fix(<scope>): <描述>`

### 发版流程概要

1. （打 Tag 前）`python3 scripts/update-models-dev-assets.py` 刷新内置模型快照；失败则跳过此步直接发版，不要重试或手改文件
2. 在 `main` 最新提交打 Tag 并推送：`git tag v1.7.0 && git push origin v1.7.0`
3. CI 自动推导版本、构建三个签名 APK、发布 GitHub Release
4. 真机装 RC 包验证 AI 对话 + 终端 + 容器启动三条主线，有问题从该 RC Tag 拉 `hotfix/` 修复

## 编码规范

### 文件组织

- feature-based 分层 + DDD：每个 feature 内 `data/`（实现与持久化）/ `domain/`（业务逻辑与接口）/ `presentation/`（ViewModel + Compose）
- 依赖注入：可构造注入的类用 `@Inject constructor`；跨实现的接口绑定集中在 `di/` 下的 Module 或 feature 内自有 Module
- 协程用 Kotlin Coroutines / Flow，UI 层用 `StateFlow` 暴露状态

### 命名

| 类型 | 约定 | 示例 |
|------|------|------|
| 类 | PascalCase | `ToolPermissionPolicyEngine` |
| 函数 | camelCase | `resolveApproval()` |
| 常量 | SCREAMING_SNAKE | `SCHEMA_VERSION` |
| 数据库迁移文件 | `{版本}_{下划线描述}.sql` | `52_add_provider_custom_headers.sql` |
| strings.xml 资源 | 语义化英文小写下划线，复用加 `common_` 前缀 | `chat_send_message` |

### 关键约定

- **targetSdk 锁定 28**（PRoot 依赖可写目录 execve），勿升级
- **迁移编号连续**且已发布 Tag 的迁移冻结
- **`SqlScriptSplitter` 已支持字符串内分号**，SQL 字符串字面量无需再用 `char(59)` 绕行
- **单测环境不 mock android.jar**（`unitTests.isReturnDefaultValues = true`）；迁移测试用 Robolectric + `MigrationTestHelper`，只跑 universalDebug 变体
- 测试栈：JUnit 4 + MockK（`coEvery` 协程打桩）+ kotlinx-coroutines-test（`runTest` 虚拟时间）

### 日志

- `core/util/FileLogger.kt` — 落盘日志：按天分文件、等级阈值、7 天/5MB 自动清理
- `core/util/AILogger.kt` — AI 请求/响应逐会话完整日志
- 用户反馈崩溃由 `AIEditorApp` 全局 handler 捕获并拉起 `CrashActivity`（展示详情 + 复制 + 重启）
