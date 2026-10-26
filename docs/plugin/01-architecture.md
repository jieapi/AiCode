# 插件系统计划：架构与运行时

状态：讨论稿。需求状态以 [总览](00-overview.md) 为准；本文件的架构选择均为建议，尚未实现。

## 1. 建议的整体结构

```text
第三方插件包（手机端保存）
  ├─ 通用安装元数据与权限声明
  ├─ 工具 / MCP / Skill / 子代理 / 提供商 / 页面与配置贡献
  └─ 业务逻辑入口、UI 声明与静态资源
             ↓ 校验、授权、启用
插件管理器（宿主可信代码）
  ├─ 版本、来源、启停、归属和授权记录
  ├─ 隔离运行时 ←→ 有界消息协议 ←→ 宿主能力接口
  └─ 贡献适配层
       ├─ 现有工具注册与调用链
       ├─ 现有 MCP 管理器与传输
       ├─ Skill / 子代理来源聚合
       ├─ 提供商注册与兼容服务入口
       ├─ Agent 生命周期与代审核
       ├─ 通用结构化 UI：声明 + 状态绑定 / 页面与配置呈现
       └─ 受控能力：Bash / terminal / tips / 通知 / Shizuku
```

通用安装元数据解决身份、兼容性、权限、贡献和入口。构建插件通过 UI 声明、状态绑定、业务 handler 和命令能力组成，不要求宿主内置构建配方；UI 及命令专题见 [06-structured-ui.md](06-structured-ui.md) 与 [07-build-plugins.md](07-build-plugins.md)。提供商插件通过 App 已有接口注册入口，上游适配留在插件，见 [09-provider-plugins.md](09-provider-plugins.md)。

### 职责划分

| 部分 | 负责 | 不负责 |
| --- | --- | --- |
| 插件管理器 | 安装状态、启停、版本切换、归属与清理 | 不把插件代码放进 App 主进程运行 |
| 插件运行时 | 执行插件逻辑、传递结果、取消与资源约束 | 不直接持有宿主文件、网络、数据库和系统服务 |
| 宿主能力接口 | 按调用者身份和授权执行操作 | 不相信插件自行提交的身份、权限或“已经批准”标志 |
| 贡献适配层 | 把插件贡献接入现有功能与页面入口 | 不绕开既定的插件能力授权与 AI 调用限制 |
| 通用结构化 UI 引擎 | 校验声明、渲染、状态绑定和事件分发 | 不内置构建命令、依赖源，不在重组时执行业务副作用 |
| 生命周期分发器 | 事件快照、顺序、超时、取消与结果校验 | 不将 reducer、ViewModel 等可变对象直接暴露给插件 |

规划上可新增 `feature/plugin/`，内部沿用 domain / data / presentation 分层。Agent 侧保留窄接口接入 hooks 和代审核，避免让工作流依赖安装器、下载器或具体 JavaScript 引擎。具体接口与 Gradle 模块拆分待选型后决定，不先做大范围重构。

## 2. 运行时与开发语言比较

### 2.1 建议：JavaScript 逻辑，TypeScript 发布前编译

理由：工具 handler、事件处理、异步交互和结构化贡献适合 JavaScript；作者可以用 TypeScript 获得 SDK 类型提示。手机只执行发布包中的 JavaScript，不需要在使用者设备安装 Node、npm 或 TypeScript 编译器。

需要明确：JavaScript 不等于 Node.js。除非后续单独实现并授权，插件不能直接使用 Node 的文件系统、子进程、网络模块，也不能调用 Android 类或 JVM 反射。第三方依赖由作者在发布前打包，不能以运行时下载额外代码的方式绕过包校验和更新授权。

| 候选 | 优点 | 限制与风险 | 当前结论 |
| --- | --- | --- | --- |
| AndroidX JavaScriptEngine / JavaScriptSandbox | 官方检索摘要描述为独立沙箱进程中的逻辑执行；隔离保证与是否无需可见 WebView 均待正文和设备验证 | WebView 依赖、并发实例数量与多个 isolate 的故障域均待核实 | 优先核实并做可行性验证，不直接定为正式依赖 |
| 自带 JS 引擎 + Android 隔离服务 | 对引擎版本、终止机制和消息协议控制更强 | 增加 JNI、ABI、包体、引擎安全更新和隔离服务维护成本；不能把普通 `:plugin` 同 UID 进程称为隔离 | 备选方案，未经比较与确认不作为自动回退 |
| 普通 WebView + JavaScript bridge | UI 表现能力强、工程内已有 WebView | DOM、导航、网络和 bridge 增加攻击面；不能仅凭“用了 WebView”保证按插件授权或故障隔离 | 不建议作为仅逻辑插件的默认后端 |
| App 内动态 DEX / Kotlin / Java | 易访问现有 Android 功能 | 不可信代码与宿主同权限，无法靠 SDK 约定限制反射、文件或系统访问 | 不作为不可信插件的默认方案 |
| 现有 PRoot 中 Python / Shell / Node | 易复用脚本生态 | 现有执行链不是按插件隔离的安全边界，能访问已挂载目录 | 可作为显式授权的外部执行能力讨论，不当作插件沙箱 |

语言与引擎分别决策。若优先候选不满足兼容性或终止要求，需要回到方案选择，不静默换成主进程执行、普通 WebView 或 PRoot。

### 2.2 选型前必须完成的验证

1. 覆盖项目 `minSdk = 26`、arm64-v8a / x86_64、不同系统和 WebView 版本；不支持时给出明确禁用原因。
2. 验证消息往返、异步宿主调用、异常、取消、运行时死亡和重建。
3. 对无限循环、Promise 永不结束、内存耗尽、超大返回值做测试，确认宿主不被拖死。
4. 验证能否真正终止执行；“Kotlin 超时停止等待”不等于底层 JavaScript 已停止。
5. 区分每插件隔离单元和进程级隔离单元。关于“每 App 同时只能持有一个 sandbox 连接”的官方检索摘要尚待核实；在正文与设备验证前，不以此冻结架构，也不承诺每插件一个独立进程。
6. 验证关闭一个 isolate、崩溃一个 sandbox 对其他插件的实际影响，并确定恢复上限。
7. 核实是否存在绕过宿主接口的文件、网络、原生导入或动态加载通道。
8. 核实所需最低库版本、许可证、F-Droid 构建和可复现构建影响，再确定依赖。

资源上限、超时数值和允许并发数由验证结果与实际使用场景确定，本轮不写没有依据的固定数值。

### 2.3 已核实的官方事实（2026-10）

来源为 Android Developers 的 JavaScriptSandbox 类参考与 JavaScriptEngine 使用指南（页面标注最后更新 2026-09-23），库最新稳定版 1.1.1（2026-09-23）。以下只陈述页面原文能支持的内容，设备行为仍需 PoC。

| 之前待核实的点 | 核实结果 |
| --- | --- |
| 每个 App 同时只能持有一个 sandbox | 属实：`JavaScriptSandbox` 对应一个**该 App 独占的隔离进程**，同一时刻只能存在一个，重复创建抛 `IllegalStateException` |
| 隔离粒度 | 同一 sandbox 内可建多个 `JavaScriptIsolate`；官方指南明确称其为“**weak security boundaries**”（弱安全边界）。因此**所有插件的 isolate 共享一个进程**，“每插件一个进程”不成立 |
| 崩溃的影响范围 | sandbox 进程崩溃会终止其中**全部** isolate，所有求值以 `IsolateTerminatedException` 失败。内存耗尽在支持时（`JS_FEATURE_ISOLATE_MAX_HEAP_SIZE`）被限制在出问题的那个 isolate，该 isolate 之后不可再用 |
| 超时能否真正终止 | 仅在设备实现支持 `JS_FEATURE_ISOLATE_TERMINATION` 时，`close` 才能终止正在运行的求值；否则无法终止运行中 / 排队中的求值，isolate 会继续占用资源。印证了“Kotlin 超时停止等待不等于 JS 已停止” |
| 是否需要可见 WebView | 不需要创建 WebView 实例，但**实现由设备上安装的 WebView 提供**，须先查 `isSupported()` 与各 `isFeatureSupported()`；API 26+ 且 WebView 支持时可用。这直接决定“你的设备不支持”必须在安装前就能告知，见 [11](11-index-format.md) 的 `engines.runtimeFeatures` 建议 |
| 宿主与 JS 通信 | `evaluateJavaScriptAsync` 的结果是字符串；Promise 返回需 `JS_FEATURE_PROMISE_RETURN`；双向消息端口（message ports）自 1.1.x 起提供（`JS_FEATURE_MESSAGE_PORTS`）。**在已读取的页面里没有找到“JS 主动调用宿主函数”的接口**，宿主能力调用大概率要建在 message ports 之上，延迟与取消语义是 PoC 的首要验证项 |

未核实：JS 环境是否具备 `setTimeout`、`TextEncoder`、`URL` 等常见全局，以及 F-Droid 对该库的构建影响。

对设计的含义：需要“崩溃恢复上限 + 疑似肇事插件自动停用”；不能把某个插件打崩进程当成只影响它自己。

## 3. 宿主能力接口与调用身份

建议使用类型化 SDK，底层通过可序列化消息交互，而不是跨边界传递宿主对象。

每次调用由宿主绑定：插件安装身份、包版本、运行实例、授权版本、当前项目/会话和调用关联 ID。插件传入的 `pluginId`、`sessionId` 或“授权凭据”不能自行扩大作用域；宿主校验关联关系和有效期。

- 每个入口和回调均有请求 ID，回包校验大小、类型、关联实例和是否已取消。
- 更新、停用、撤权后旧实例的回包失效，不能重新批准新请求。
- 不暴露全量 `AgentContext`、供应商 Key、数据库 DAO、任意 Android Intent、Binder 或管理器实例。
- Bash/terminal 已确认作为可申请的通用能力；插件 UI 在授权范围内直接执行命令。文件、网络、会话内容等额外接口仍需分别定义范围和授权，不因安装插件而默认获得。
- 来自受限 Agent 调用的运行上下文必须贯穿宿主接口检查，例如 PLAN 中的插件工具不能经 handler 间接执行被禁止的写入/命令；也不能把同一操作改投后台回调来解除限制。独立生命周期事件允许哪些副作用需按事件另行定义。
- 宿主不得提供可以任意调用内部方法的通用反射/RPC 接口，否则前面的权限划分没有意义。

## 4. 手机端保存与远程模式

**已确认：插件运行时始终在手机端。** 建议插件包、版本记录和授权状态也由手机端宿主持有，不上传到 SSH 服务器。

### 4.1 建议的数据分区

- 插件安装包与已校验版本：宿主管理的只读发布内容。
- 插件运行数据：按插件隔离的可写数据，通过宿主接口访问。
- 授权与身份记录：宿主独占，不放进可被插件写入的目录或现有 `~/.aicode` 配置挂载中。
- 下载缓存与日志：有大小与保留上限，日志不能复制凭据或完整会话内容。

具体用文件、DataStore 还是 Room，需要结合原子更新和备份需求决定。仅将文件放到 App 私有目录，不能证明它不受同 UID 代码影响；有任意本地进程执行能力时还需重新评估威胁边界。

### 4.2 与现有资源共存

现有 Skill 和子代理通过 `FileAccessProvider` 跟随执行环境。建议新增“插件贡献来源”，让手机端包中的贡献元数据通过宿主聚合到会话清单，不写入远端技能/子代理目录，也不改变使用者已有文件的行为。

Skill 运行所需资源是否允许另行发布到远端，是单独授权的候选能力，不等于把插件贡献元数据写进远端目录，更不代表插件运行时迁到远端。额外脚本文件的发布方式与授权仍待明确。

**已确认：插件调用 Bash/terminal 时，命令跟随当前工作区的本地容器或 SSH 后端执行。** 远端执行的是宿主委托的命令，插件的 UI 与业务 handler 仍在手机；每次任务启动要绑定目标服务器/容器、工作区和授权范围，已有任务后续操作不能因界面切换而误路由。

含本地脚本引用的 Skill 如何在远程模式工作仍待确认，不能仅改变其文本路径就宣称兼容。参见扩展章节。

## 5. 现有代码接入依据

| 现有位置 | 已核实事实 | 对规划的影响 |
| --- | --- | --- |
| [ToolRegistry.kt:6](../../app/src/main/java/com/aicode/feature/agent/domain/tool/ToolRegistry.kt:6) | 全局可变注册表，同名直接覆盖 | 需要命名空间、归属和调用时可用性检查 |
| [AgentTool.kt:94](../../app/src/main/java/com/aicode/feature/agent/domain/tool/AgentTool.kt:94) | 权限策略和 capability 由工具实现声明 | 不能让不可信插件自行选择宿主放行策略 |
| [McpManager.kt:65](../../app/src/main/java/com/aicode/feature/agent/domain/mcp/McpManager.kt:65) | 工作区、配置及容器变化可触发重连 | 插件来源必须在连接前核验授权和启用状态 |
| [McpManager.kt:130](../../app/src/main/java/com/aicode/feature/agent/domain/mcp/McpManager.kt:130) | 现有 HTTP / stdio 连接与动态工具注册 | 可复用协议与传输，不需要另造 MCP 实现 |
| [SkillRepository.kt:18](../../app/src/main/java/com/aicode/feature/agent/domain/skill/SkillRepository.kt:18) | 直接聚合全局和项目目录来源 | 增加插件来源时要同时修改列表、加载和管理路径 |
| [AgentDefinitionRepository.kt:17](../../app/src/main/java/com/aicode/feature/agent/domain/subagent/AgentDefinitionRepository.kt:17) | 定义聚合、启停、派发与版本管理集中在仓库 | 插件来源需要保留归属及缓存失效语义 |
| [WorkspacePathMapper.kt:94](../../app/src/main/java/com/aicode/feature/workspace/domain/WorkspacePathMapper.kt:94) | `/root/.aicode` 映射到 App 的配置目录 | 不把新插件授权记录放进同一可写映射中 |
| [LinuxContainerEngine.kt:721](../../app/src/main/java/com/aicode/feature/agent/domain/container/LinuxContainerEngine.kt:721) | PRoot 绑定系统目录、工作区和 `.aicode` | stdio 进程不继承 JS 沙箱的安全承诺 |
| [app/build.gradle.kts:104](../../app/build.gradle.kts:104) | minSdk 26、targetSdk 28，按 ABI 区分 flavor | 插件计划不调整 SDK 或 ABI 规则 |

## 6. 明确的安全承诺范围

建议承诺的是：插件逻辑不能未经授权直接获得宿主能力；授权后的宿主调用可归属、可撤销后续访问、可审计。

不能承诺的是：使用者授权 Bash/terminal、任意 Shizuku 命令或现有 stdio 进程后，命令内部仍受 JS 沙箱限制；提示词内容不会影响 AI 后续行为；撤权能撤销已经发生的文件、网络和系统副作用。

若产品要求“包括第三方 MCP server 和任意脚本在内的所有代码都必须保持强隔离”，需要为这些执行路径另做架构，不能只复用现有启动流程。这个目标尚未确认。

## 7. 外部参考与核验状态

- Android Developers：[Executing JavaScript and WebAssembly](https://developer.android.com/develop/ui/views/layout/webapps/jsengine)
- Android Developers：[JavaScriptSandbox](https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox)

官方两页正文已在后续一轮读取并核对，结论见 2.3 节。正文只说明 API 与官方承诺，不替代设备验证：隔离保证的实际强度、各设备 WebView 的特性支持矩阵、真实终止行为仍需在目标设备上用 PoC 验证。
