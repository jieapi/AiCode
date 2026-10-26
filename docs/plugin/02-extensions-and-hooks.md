# 插件系统计划：扩展注册与 Agent 生命周期

状态：讨论稿。工具、MCP、Skill、子代理、提供商、结构化页面/配置和生命周期扩展是已确认需求；具体 SDK 和事件名称尚未确定。

## 1. 贡献的共同规则（建议）

- 安装时能预览插件贡献，运行时激活只能注册属于当前插件且符合已授权范围的内容。
- 宿主保存内部归属键：插件身份、版本、贡献类型和局部名称。展示名称不作为安全身份。
- 同名不得覆盖内置功能、使用者配置或其他插件；卸载只能移除自己的贡献。
- 给模型的工具名由宿主生成符合上游限制的稳定 ASCII 名称，不能直接拼接任意插件名。
- 注册成功不代表立即拥有执行权限；声明、启用、会话可见性和实际调用权限分别判断。
- 同一轮使用一致的能力快照；撤权或停用后，执行入口仍要检查，不能仅依赖旧清单是否可见。
- 动态注册能否超出安装时声明的清单待确认。建议不能静默扩大，新增受保护能力必须重新授权。

现有注册表的 `register` 直接覆盖同名对象，`unregister` 仅按名称删除，见 [ToolRegistry.kt:12](../../app/src/main/java/com/aicode/feature/agent/domain/tool/ToolRegistry.kt:12)。不能把它不加保护地暴露给插件。

## 2. 注册工具

### 建议的接入方式

1. 插件贡献工具名、用途、参数 schema 和 handler 引用；同一个业务 handler 可按声明与权限分别作为 UI 动作或 AI 工具入口，不因共享代码而合并两种调用的授权语义。
2. 宿主校验定义，创建可信适配器并分配归属，不让插件实现任意 `AgentTool` 对象。
3. AI 调用仍经过现有模式限制、权限评估和代审核/UI 流程。
4. 获准后进入隔离运行时，执行 handler；handler 调用宿主能力时还要检查插件授权。
5. 返回有界结构化结果，交现有工具结果链路，不把可执行对象传回 App。

插件不能通过声明 `AUTO_APPROVE`、省略 capability 等方式自行取得放行。现有默认值见 [AgentTool.kt:98](../../app/src/main/java/com/aicode/feature/agent/domain/tool/AgentTool.kt:98)。但也不能简单把所有插件工具硬编码成同一种风险，就宣称解决了 PLAN：哪些纯计算/只读工具可在 PLAN 运行、如何映射实际能力，需要设计宿主侧分类和运行时调用限制。

至少区分：AI 是否可以调用这个工具、插件是否可以调用某项宿主能力、当前执行上下文是否允许该副作用。AI 批准调用一个插件工具，不自动授予该插件通知、Shizuku 或其他工具的权限。

取消、结果大小、超时、停用时在途调用和是否支持流式结果需要写入工具接口验收。具体参数和错误码在接口定稿时确定。

## 3. 注册 MCP：复用现有功能

**已确认：复用现有 MCP。** 不另造传输协议，不自行删掉已有 HTTP、stdio、OAuth、工具启停等功能。

### 建议新增的适配边界

- 将“插件声明的服务器”作为有归属的配置来源，交现有 `McpManager` / `McpClient` / transport 管理。
- 不让插件直接改写全局或项目 `mcp.json`，避免把插件贡献与使用者手工配置混在一起。
- 插件声明、开始连接/启动进程、使用凭据、调用远程工具分别检查所需授权。
- 手工配置和插件配置冲突时显示来源并阻止静默覆盖；插件停用/卸载只断开自己持有的连接。
- 若未来允许共享一个已存在的服务连接，需要引用归属和引用计数，不能卸载一个插件就停掉使用者或其他插件的服务。
- OAuth 和密钥输入由宿主管理；插件能注册一个服务不等于能读取其他服务器的令牌或供应商 Key。

现有链路在 [McpManager.kt:130](../../app/src/main/java/com/aicode/feature/agent/domain/mcp/McpManager.kt:130) 选择 HTTP/stdio，连接后包装并注册工具；[McpTool.kt:36](../../app/src/main/java/com/aicode/feature/agent/domain/mcp/McpTool.kt:36) 已有工具名生成、schema 转换与默认审核策略，可复用这些机制。

### 必须显式说明的 stdio 边界

现有 MCP 会随配置和工作区变化自动连接，见 [McpManager.kt:65](../../app/src/main/java/com/aicode/feature/agent/domain/mcp/McpManager.kt:65)。插件来源必须在这之前完成授权判断，不能等到第一次 `tools/call` 才询问是否允许启动。

stdio 通过本机 PRoot 进程运行，见 [LinuxContainerEngine.kt:670](../../app/src/main/java/com/aicode/feature/agent/domain/container/LinuxContainerEngine.kt:670)。这条链路可访问所绑定的环境，不属于插件 JavaScript 沙箱。需向使用者明确展示启动程序、参数、工作区、环境变量来源和可能访问的资源。

插件外部服务的依赖已确认采用用户手动准备机制；不在注册/安装/预览时自动安装。MCP 随包 server 的文件声明及如何关联已准备环境仍需细化，不能直接把普通服务 environmentRef 套到现有 stdio 路由。也不能宣称现有 stdio 已满足不可信 server 强隔离；若要强隔离，仍需单独执行后端设计，依赖准备见 [12-service-dependencies.md](12-service-dependencies.md)。

## 4. 注册 Skill

### 建议

- 包中 Skill 由手机端插件来源提供，复用现有清单注入、按需加载和斜杠触发流程。
- 列表、完整正文加载、禁用状态和设置页来源展示必须一致，不能只追加名称但无法加载正文。
- 插件贡献元数据由宿主聚合，不写入远端或使用者的技能目录；显示作者、插件归属和版本。运行所需资源是否另行发布，属于单独授权的候选能力，不与贡献注册混同。
- 安装/启用含指令的插件会影响模型行为，授权说明需要呈现这一点，不能只列“读取一个 Markdown 文件”。

现有 [SkillSource.kt:6](../../app/src/main/java/com/aicode/feature/agent/domain/skill/SkillSource.kt:6) 已区分列表和正文读取；[SkillRepository.kt:24](../../app/src/main/java/com/aicode/feature/agent/domain/skill/SkillRepository.kt:24) 当前仍直接聚合两类目录，实施时要同时调整列表和加载路径。

### 脚本与远程模式待确认

现有技能常通过文本引导 AI 调用 Bash 执行同目录脚本，见 [LoadSkillTool.kt:16](../../app/src/main/java/com/aicode/feature/agent/domain/tool/skill/LoadSkillTool.kt:16)。插件包留在手机端后，不能假定远程工作区能直接读取该路径。

候选方式：插件 Skill 通过已注册插件工具调用手机端 handler；或在单独明确授权后向执行环境发布运行所需资源。已确认宿主命令可随工作区在远端执行，但额外文件发布机制、路径与授权仍需明确。发布资源不把贡献元数据写入远端技能目录，也不迁移插件运行时，不能自动等同于“注册 Skill”。资源方案选择待用户确认。

技能文本影响 AI 后续工具调用，不是运行时代码权限继承。若要求由插件权限持续限制这些后续行为，需要另设计受限执行上下文；不能仅靠提示词或插件来源标签保证。

## 5. 注册子代理

建议复用现有定义解析、清单、派发与模型配置，不创建另一套子会话系统。

- 新增插件定义来源，并为定义、派生会话保留插件身份和版本。
- 插件可引用自身已注册或宿主明确允许引用的提供商实例，不能把不存在的实例当作可用，也不能因此读取其他渠道凭据；模型绑定失败要明确提示。
- 继续遵守父会话模式约束、工具白/黑名单和不能嵌套派发等现有规则，见 [AgentDefinition.kt:83](../../app/src/main/java/com/aicode/feature/agent/domain/subagent/AgentDefinition.kt:83)。
- 注册子代理是否即授权启动它、是否允许调用所有父会话工具，需要独立确定；不能默认插件权限自动约束 LLM 的工具能力。
- 停用后禁止新派发；已运行的子代理是否取消、完成当前轮还是保留快照待确认，不直接改变既有行为。

现有定义仓库通过 `definitionsVersion` 更新提示词缓存，且为在途子代理提供包含禁用定义的读取路径，见 [AgentDefinitionRepository.kt:24](../../app/src/main/java/com/aicode/feature/agent/domain/subagent/AgentDefinitionRepository.kt:24)。插件更新要兼顾可见性和在途定义稳定性。

## 6. 生命周期必须区分的层级

| 层级 | 含义 | 原因 |
| --- | --- | --- |
| 一次 Agent 运行 | 一次输入或队列任务触发的完整执行，可能含多次模型调用 | “完成时”不能对每个工具循环都触发 |
| 一次逻辑模型请求 | 一次工具循环中的请求 | 可发生压缩后重建、截断续写 |
| 一次传输尝试 | 网络重试或换 Key 后的实际尝试 | 不能把计费通知或有副作用 hook 重复执行 |
| 一次工具调用 | 带唯一 callId 的完整参数调用 | 参数流式准备事件不等于已经执行 |
| 一批工具 | 同轮多个调用，先收集审批再并行执行 | 插件拒绝仅影响当前调用，结果顺序和工具消息配对仍需完整 |

建议所有事件携带宿主生成的关联 ID、序号、插件可读的数据快照和明确的结果状态。主会话与子代理范围要显式标注，不能因全局监听而自动读取所有会话。

## 7. 建议的事件目录与可修改性

以下是候选概念，不是已经定稿的 API 名称，也不表示每类事件都允许改写。

| 时机 | 建议用途 | 拦截/修改状态 |
| --- | --- | --- |
| 运行开始 | 初始化该次运行的插件状态 | 能否阻止启动待确认 |
| 请求准备完成、发送之前 | 检查消息、追加获授权的上下文或调整允许字段 | 用户已要求请求前扩展；字段白名单、是否持久化待确认 |
| 传输重试/换 Key | 提示或观测 | 建议仅脱敏观测，不给 Key 原文，不重复请求前副作用 |
| 一次模型响应结束 | 读取允许的结果、usage 和状态 | 能否替换结果或影响后续工具调用待确认 |
| 工具参数完整时的通用变换 hook | 工具参数处理的独立候选扩展，不属于代审核 | 是否开放、在宿主评估前是否允许执行及可改字段均未确认，不能据此让代审核插件先看/先改全部调用 |
| 宿主判定需要使用者确认 | 同意 / 拒绝 / 不处理 | 流程已确认，详见权限章节 |
| 工具真正开始、输出、结束 | 提示、诊断或后处理 | 不能把“已批准待执行”误记为已经产生副作用 |
| 运行结束 | 完成提示、通知、清理 | 区分成功、失败、取消，不把异常当成功 |

通用工具变换 hook 和代审核是不同入口、不同授权。当前已确认的代审核仍只能在宿主 ASK 分支返回同意、拒绝或不处理，不包含修改参数，也不处理宿主已放行或拒绝的调用。是否额外开放评估前的通用工具 hook，必须另行确认，不能由生命周期需求自动推导。

若另行确认支持可变 hook，建议插件返回结构化的受限变更，由宿主校验并应用，不直接修改内部状态引用；修改后的工具调用必须重新经过完整宿主权限判断。工具参数在审批后不得悄悄改写，否则原批准立即失效，必须重新评估并取得新决定。

修改模型消息还要考虑 token 预算、上下文压缩、工具调用与结果配对、Anthropic 签名和缓存前缀。不能提供“任意改原始请求 JSON”的入口就认为适配完成。

### 提示词缓存稳定性约束（建议）

服务端提示词缓存按请求前缀匹配，system、tools 或消息中段一变，后面全部失配。项目有实测教训：把模式提示从 system 中段挪到消息层之前，缓存命中率曾跌到 10%~38%，挪走后恢复到约 90%；`ToolRegistry` 用 `LinkedHashMap` 就是为了让工具顺序不漂移（[ToolRegistry.kt:8](../../app/src/main/java/com/aicode/feature/agent/domain/tool/ToolRegistry.kt:8)）。插件会同时在三处动这个前缀（工具、技能清单、子代理清单）和 `agent.request.before`，因此建议三条硬约束：

1. **hook 的注入点只允许消息层末尾**，不允许往 system 前缀或工具列表中段追加内容；
2. **插件贡献的启停与更新对已有会话在边界生效**（新会话，或明确的压缩 / 重建点），而不是下一轮就改变工具集；
3. **工具顺序固定为“内置、MCP、插件（按安装顺序）”**，插件之间的顺序不随启停抖动。

第一切片的做法：技能清单沿用既有会话级缓存（新会话才刷新）；子代理清单复用既有的版本号失效机制，插件集合变化会使它重建一次，与使用者在设置里新建子代理时的现有行为一致。

## 8. 现有生命周期接入点

- 请求主循环在 [StatefulAgentWorkflow.kt:555](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:555)，压缩调用在 [StatefulAgentWorkflow.kt:579](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:579)，实际 `completeStream` 在 [StatefulAgentWorkflow.kt:614](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:614)。请求前 hook 必须确定在压缩、图片适配和预算检查的哪个阶段运行，修改后还要重新校验预算。
- 权限请求在 [StatefulAgentWorkflow.kt:740](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:740)，最终审批分支在 [StatefulAgentWorkflow.kt:1369](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:1369)。
- 批次审批状态转换在 [StatefulAgentWorkflow.kt:389](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:389)，批次执行分支在 [StatefulAgentWorkflow.kt:771](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:771)，其中 `async` 并发入口在 [StatefulAgentWorkflow.kt:791](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:791)。
- UI 事件定义在 [AgentWorkflow.kt:14](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/AgentWorkflow.kt:14)。这是现有 UI/落库事件，不直接当稳定的插件 ABI。
- 现有工作流在记录错误后仍发送 `Completed`，见 [StatefulAgentWorkflow.kt:905](../../app/src/main/java/com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt:905)；它表示事件流结束，不等于运行成功。外层失败事件处理见 [AIAgentViewModel.kt:2132](../../app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt:2132)，结束事件见 [AIAgentViewModel.kt:2150](../../app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt:2150)，取消处理见 [AIAgentViewModel.kt:2191](../../app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt:2191)，finally 清理见 [AIAgentViewModel.kt:2226](../../app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt:2226)。插件终态通知需要覆盖外层失败和取消，不能只订阅 `Completed`。
- 技能和子代理提示词存在会话缓存，见 [SystemPromptProvider.kt:55](../../app/src/main/java/com/aicode/feature/agent/domain/prompt/SystemPromptProvider.kt:55)。插件清单更新需要受控失效，而非在每个 token 上重扫。

## 9. 调度与故障规则（建议，细节待确认）

- 同一次可变 hook 使用确定顺序，插件不能自行抢占最高优先级；是否采用使用者排序待确认。
- 只读观察事件可异步、有界合并；拦截点必须等待明确结果或超时策略，不能无限阻塞。
- 事件按运行保序，不能把多个并行会话混进一个无归属全局状态。
- 宿主不持有工作流/注册表/授权锁等待插件，避免插件回调时死锁。
- 插件导致的宿主调用带来源链，限制 hook 递归；审核回调不能再次审核自己的插件授权申请。
- 插件异常不应自动批准；观察事件失败是否跳过、拦截事件失败是否中止或回到 UI，需要按事件类型定义。
- 运行结束通知在 App 存活的可控执行路径中去重；进程被系统杀死时不承诺最后一个 hook 必然执行。

### 故障保险（建议）

`agent.request.before` 这类拦截点位于每次对话请求的关键路径上，一个坏插件能让所有对话变慢或失败。除上面的超时和恢复上限外，还需要使用者可见、可操作的保险：

- 设置里有**全局总开关**“停用所有插件”，不依赖逐个关闭；
- **连续 N 次异常或超时自动停用该插件**并明确提示，而不是无限重试；
- 若上一次运行崩溃且与某插件的执行相关，下次启动进入**安全模式**（本次不加载任何运行时插件），并告知原因。

阈值（N、等待时长）要用 PoC 数据定，这里不写没有依据的数值。声明式贡献（技能、子代理）不执行代码，不在此列。

## 10. 页面、配置与构建业务贡献

**已确认**：右抽屉提供功能入口；“设置 → 插件”展示名称、版本等信息并提供插件配置/权限等管理操作。业务页面和配置内容使用结构化声明，权限及安装确认由宿主可信 UI 提供。插件通过与 AI 一致的 Bash/terminal 能力实现构建、下载依赖并联动用户终端，App 不内置具体业务。

页面/配置应与工具等贡献一样有归属、兼容版本和启停语义。声明定义布局、绑定与事件引用，插件 handler 更新状态；布局渲染不能因重组、刷新或列出入口而自动执行构建。

插件逻辑在手机端，命令跟随当前工作区；UI 动作在插件已获授权范围内直接执行，不额外逐条确认。AI 调用相关工具仍保留宿主先评估和 ASK 代审核链，不将两者混在一起。

通用 UI 演进见 [06-structured-ui.md](06-structured-ui.md)，页面入口、任务与命令边界见 [07-build-plugins.md](07-build-plugins.md)。具体 schema 与任务生命周期尚未定稿，不自行扩展为宿主内置的构建配方或任务图。

## 11. 注册提供商

**已确认**：插件可以注册提供商，面向 App 使用现有支持的接口；实际上游的协议、请求/响应转换和鉴权由插件自己处理，不为每个插件增加 App 内置上游适配器。

提供商定义、用户实例、兼容服务入口和进程生命周期需要有插件归属。建议复用现有提供商设置、模型选择与调用链，兼容服务未就绪时不标为可用，也不静默将会话切到其他上游。

贡献清单、源码目录、安装包和索引结构规范需一起覆盖提供商；不能把结构规范定稿后再用改写内置预设的方式临时拼接。具体接入与凭据边界见 [09-provider-plugins.md](09-provider-plugins.md)。
