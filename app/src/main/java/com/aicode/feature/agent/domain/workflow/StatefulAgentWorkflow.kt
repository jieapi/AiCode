package com.aicode.feature.agent.domain.workflow

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.plugin.runtime.PermissionReviewDecision
import com.aicode.feature.plugin.runtime.PluginHookDispatcher
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.plugin.runtime.PluginProviderIds
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.provider.clampedFrom
import com.aicode.feature.agent.domain.provider.reasoningPlanFor
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.provider.wireSummary
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider,
    private val pluginHookDispatcher: PluginHookDispatcher,
    @param:ApplicationContext private val appContext: Context
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val PLUGIN_HOOK_DENIED = "PLUGIN_HOOK_DENIED"
        const val PLUGIN_REVIEW_DENIED = "PLUGIN_REVIEW_DENIED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_AUTO_FILE = "agent/auto-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /**
     * 每会话「上次已注入模式提醒的模式」。模式提醒只在变化时注入，避免每轮把同一段文本
     * 重复拼到最新用户消息上——那样下一轮重建历史时该位置（Anthropic 缓存断点）内容不一致，
     * 会白白丢掉一段前缀缓存。进程内缓存，重启后首轮再注入一次（无害）。
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED"
    )

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = findProviderConfig(boundProviderId)
                if (config == null && PluginProviderIds.isPluginId(boundProviderId)) {
                    throw IllegalStateException("插件提供商已被删除，或提供它的插件已停用/卸载，请重新选择模型")
                }
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = findProviderConfig(defaultProviderId)
            if (config == null && PluginProviderIds.isPluginId(defaultProviderId)) {
                throw IllegalStateException("默认插件提供商已被删除，或提供它的插件已停用/卸载，请重新选择模型")
            }
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        // 摘要仍用专用压缩模型（尊重用户选择），但保留最近消息的窗口预算按主聊天模型算，
        // 与自动压缩一致——否则小窗口的压缩模型会让手动压缩保留的最近上下文偏少。
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, windowProvider = provider, onEvent = onEvent)
        return result.compacted
    }

    /** 插件提供商注册后就是普通提供商记录，直接查仓库即可。 */
    private suspend fun findProviderConfig(id: String): AIProviderConfig? =
        aiProviderRepository.getProviderById(id)

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> {
        val hookSessionId = context.sessionId
        var hookRunId = ""
        var hookStarted = false
        var hookCompleted = false
        var hookTerminalError = false
        var hookErrorCode: String? = null
        var hookToolCalls = 0
        var hookMode = context.mode.name
        val hookScope = if (context.agentDefinition != null) "subagent" else "main"
        val hookAgentName = context.agentDefinition?.name

        return channelFlow {
            if (hookSessionId != null) {
                hookRunId = UUID.randomUUID().toString()
                hookStarted = true
                pluginHookDispatcher.dispatchRunStarted(
                    sessionId = hookSessionId,
                    runId = hookRunId,
                    mode = hookMode,
                    scope = hookScope,
                    agentName = hookAgentName
                )
            }
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // 模式提醒仅在模式变化时随最新用户消息注入一次（不进 system，避免切换时 system 前缀变化打断缓存）。
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        // 提示词构建会读取技能/子代理/记忆等文件（远程模式经 SFTP），必须离开收集本工作流的线程（Main），
        // 否则远程模式下每次会话首次发消息都会在主线程做多次文件/网络往返。
        val promptStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "prompt.start", details = "operation=$promptStartedNs")
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        FileLogger.memoryCheckpoint(
            TAG,
            "prompt.ready",
            elapsedMs = (System.nanoTime() - promptStartedNs) / 1_000_000,
            details = "operation=$promptStartedNs promptChars=${systemPrompt.length}"
        )
        val providerStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "provider.start", details = "operation=$providerStartedNs")
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        FileLogger.memoryCheckpoint(
            TAG,
            "provider.ready",
            elapsedMs = (System.nanoTime() - providerStartedNs) / 1_000_000,
            details = "operation=$providerStartedNs"
        )
        // 压缩失败后本轮（本次用户请求内）不再重复尝试压缩，避免每次 LLM 调用都白试一次。
        var compactionAttemptFailed = false
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        // 思考能力随模型走：每次请求刷新，切模型后档位集合立即变化（resolver 用它判断能否关闭与降级目标）。
        aiProvider.reasoningCapability = metadata.toReasoningCapability()
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ContextTokenEstimator.estimate(systemPrompt,
                currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        }
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        var overflowRecoveryAttempted = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val estimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            }
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                state = state.copy(messages = compaction.messages)
                                baselineUsage = 0
                                baselineEstimate = 0
                                pluginHookDispatcher.dispatchCompaction(
                                    sessionId = hookSessionId,
                                    runId = hookRunId,
                                    mode = currentContext.mode.name,
                                    scope = hookScope,
                                    agentName = hookAgentName,
                                    estimatedInputTokens = predictedInput
                                )
                            }
                        }
                        val requestEstimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools)
                        }
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        val hookRequestId = UUID.randomUUID().toString()
                        pluginHookDispatcher.dispatchRequestStarted(
                            sessionId = hookSessionId,
                            runId = hookRunId,
                            requestId = hookRequestId,
                            mode = currentContext.mode.name,
                            scope = hookScope,
                            agentName = hookAgentName,
                            messageCount = compactedMessages.size,
                            toolCount = currentTools.size,
                            estimatedInputTokens = predictedInput
                        )

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var callError: String? = null
                        var callCompleted = false
                        val stream = StreamAccumulator(callStartElapsed) { send(it) }

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, supportsVision)
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort)
                                .collect { stream.onChunk(it) }
                            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
                            stream.flush()
                            val aiResponse = stream.finalResponse ?: AIResponse(content = stream.text.toString())
                            hookToolCalls += aiResponse.toolCalls.size
                            // 模型直出图片（Gemini 图像模型）不随流式增量到达，整块在 Final 里：
                            // 先把 base64 落盘到 ~/.aicode/generated-images/ 并构造 UI 附件（只存路径不存 base64），
                            // 再随 LlmResponse 把带 path 的 images 交 reduce 挂上 AssistantMessage 供下一回放。
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    continue
                                }
                            }
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            send(AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else requestEstimate, inputBudget, baselineUsage <= 0
                            ))
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (stream.reasoning.isNotEmpty()) {
                                aiResponse.copy(reasoning = stream.reasoning.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        stream.reasoning.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted
                                    )
                                )
                                persisted.await()
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!overflowRecoveryAttempted && stream.text.isEmpty() && stream.reasoning.isEmpty() && isContextOverflow(e)) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = stream.text.toString()
                            val reasoning = stream.reasoning.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
                            // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
                            val errorText = "LLM 调用失败: ${e.message}"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = stream.finalResponse
                            pluginHookDispatcher.dispatchRequestFinished(
                                sessionId = hookSessionId,
                                runId = hookRunId,
                                requestId = hookRequestId,
                                mode = currentContext.mode.name,
                                scope = hookScope,
                                agentName = hookAgentName,
                                status = when {
                                    callCompleted -> "success"
                                    callError != null -> "error"
                                    else -> "cancelled"
                                },
                                inputTokens = usage?.inputTokens ?: 0,
                                outputTokens = usage?.outputTokens ?: 0,
                                toolCalls = usage?.toolCalls?.size ?: 0,
                                durationMs = durationMillis,
                                stopReason = usage?.stopReason,
                                errorCode = callError
                            )
                            runCatching {
                                // 与适配器同源算一次「实际会发什么」：落库后能在统计页分辨
                                // 「选了但没生效」「被降级」这些静默问题。
                                val reasoningPlan = reasoningPlanFor(providerInUse, currentContext.reasoningEffort)
                                llmCallRecordDao.insert(
                                    LlmCallRecordEntity(
                                        sessionId = currentContext.sessionId,
                                        providerId = providerInUse.providerId.ifBlank { null },
                                        model = providerInUse.model,
                                        reasoningEffort = currentContext.reasoningEffort,
                                        reasoningWire = reasoningPlan.wireSummary(),
                                        reasoningClampedFrom = reasoningPlan.clampedFrom(currentContext.reasoningEffort),
                                        kind = callKind,
                                        inputTokens = usage?.inputTokens ?: 0,
                                        outputTokens = usage?.outputTokens ?: 0,
                                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                        ttfbMillis = stream.ttfbElapsed?.toInt(),
                                        durationMillis = durationMillis,
                                        status = when {
                                            callCompleted -> "success"
                                            callError != null -> "error"
                                            else -> "cancelled"
                                        },
                                        errorMessage = callError,
                                        stopReason = usage?.stopReason,
                                        retryCount = stream.retryAttempts,
                                        createdAt = callStartWall
                                    )
                                )
                            }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        var toolCall = effect.toolCall
                        val hookDecision = if (tool != null && hookSessionId != null) {
                            pluginHookDispatcher.beforeToolCall(
                                sessionId = hookSessionId,
                                runId = hookRunId,
                                callId = toolCall.id,
                                toolName = toolCall.name,
                                mode = currentContext.mode.name,
                                scope = hookScope,
                                agentName = hookAgentName,
                                arguments = JsonObject(toolCall.arguments)
                            )
                        } else {
                            null
                        }
                        if (hookDecision != null) {
                            toolCall = toolCall.copy(
                                arguments = hookDecision.arguments ?: JsonObject(toolCall.arguments)
                            )
                        }
                        val argsPreview = JsonObject(toolCall.arguments).toString().take(500)
                        // 参数可能已被插件改写：必须按最终参数重新走完整权限判断，原批准不沿用。
                        val checkResult = if (hookDecision?.denied == true) {
                            PermissionCheckResult(
                                approved = false,
                                denyReason = hookDecision.denyReason ?: "插件阻止了该工具调用",
                                errorCode = PLUGIN_HOOK_DENIED
                            )
                        } else {
                            requestPermissionIfNeeded(
                                tool = tool,
                                callId = toolCall.id,
                                arguments = toolCall.arguments,
                                argsPreview = argsPreview,
                                mode = currentContext.permissionMode,
                                sessionId = currentContext.sessionId,
                                runId = hookRunId,
                                scope = hookScope,
                                agentName = hookAgentName
                            )
                        }

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(toolCall.id, toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(toolCall.id, toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
                        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
                        val toolCalls = effect.toolCalls
                        val toolStartedAt = ConcurrentHashMap<String, Long>()
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                val pathLocks = ConcurrentHashMap<String, Mutex>()
                                toolCalls.map { toolCall ->
                                    async {
                                        toolStartedAt[toolCall.id] = SystemClock.elapsedRealtime()
                                        pluginHookDispatcher.dispatchToolStarted(
                                            sessionId = hookSessionId,
                                            runId = hookRunId,
                                            callId = toolCall.id,
                                            toolName = toolCall.name,
                                            mode = currentContext.mode.name,
                                            scope = hookScope,
                                            agentName = hookAgentName
                                        )
                                        val run: suspend () -> ToolRunResult = {
                                            val tool = toolRegistry.getTool(toolCall.name)
                                            if (tool is StreamingAgentTool) {
                                                runToolStream(tool, toolCall, currentContext) { send(it) }
                                            } else {
                                                runToolSync(tool, toolCall, currentContext)
                                            }
                                        }
                                        // 同一批里针对同一路径的调用必须串行：并行会让「读-改-写」互相覆盖，
                                        // 并让写入方的回读校验看到别人的内容而误报失败。不同路径仍并行。
                                        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull
                                        if (path.isNullOrBlank()) run()
                                        else pathLocks.computeIfAbsent(path) { Mutex() }.withLock { run() }
                                    }
                                }.awaitAll()
                            }
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // 退出 PLAN 时挂起 workflow，等待用户在计划审查面板批准后才继续
                                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system 与 mode 已解耦（SystemPromptProvider 不再注入模式提示词），
                                        // 切换不重建 systemPrompt，避免 system 前缀变化打断缓存；模式状态通过工具结果与下轮消息提醒告知。
                                        rawResult = appendPlanModeNotice(rawResult, buildModeSwitchNotice(newCtx.mode))
                                    } else {
                                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消并等待用户反馈
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error(
                                            appContext.getString(R.string.plan_mode_refinement_requested),
                                            "MODE_SWITCH_REJECTED"
                                        ).toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult = appendPlanModeNotice(rawResult, buildModeSwitchNotice(newCtx.mode))
                                }
                            }
                            if (!isError && hookSessionId != null) {
                                toolResultSuccessData(rawResult)?.let { data ->
                                    val transform = pluginHookDispatcher.afterToolCall(
                                        sessionId = hookSessionId,
                                        runId = hookRunId,
                                        callId = toolCall.id,
                                        toolName = toolCall.name,
                                        mode = currentContext.mode.name,
                                        scope = hookScope,
                                        agentName = hookAgentName,
                                        data = data
                                    )
                                    transform.data?.let { rawResult = replaceToolResultData(rawResult, it) }
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        batchResults.forEach { result ->
                            val startedAt = toolStartedAt.remove(result.id)
                            val duration = startedAt?.let { (SystemClock.elapsedRealtime() - it).toInt() } ?: 0
                            pluginHookDispatcher.dispatchToolFinished(
                                sessionId = hookSessionId,
                                runId = hookRunId,
                                callId = result.id,
                                toolName = result.toolName,
                                mode = currentContext.mode.name,
                                scope = hookScope,
                                agentName = hookAgentName,
                                status = if (result.isError) "error" else "success",
                                durationMs = duration
                            )
                        }

                        // 本轮内到达的后台任务/子代理完成通知：搭在本批最后一条工具结果上立即送达，
                        // AI 当轮即可感知，不必等本轮结束再起新一轮。ack 放到完成事件发出（结果已落库）之后：
                        // 中途被取消时通知仍留在队列里，由后续批次或本轮结束的兜底路径送达。
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // 用户在工作期间切换模式：本轮后续批次的权限判定立即改用新模式。
                                // 与 SessionUseCase.updateMode 保持同一套语义：进入 PLAN 记下进入前的模式，其余情况清空。
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                            }
                            val last = batchResults.last()
                            val outgoingNotifications = if (newMode != null) {
                                notifications.map { item ->
                                    if (item === modeChange) {
                                        item.copy(detail = listOfNotNull(
                                            item.detail?.takeIf { it.isNotBlank() },
                                            buildExternalModeSwitchNotice(newMode).trim()
                                        ).joinToString("\n\n"))
                                    } else item
                                }
                            } else notifications
                            val injected = eventInjector.inject(last.result, outgoingNotifications)
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                                attachments = br.attachments, persisted = persisted))
                            persisted.await()
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let {
            hookTerminalError = true
            hookErrorCode = state.errorCode
            send(AgentEvent.Failed(it, state.errorCode))
        }
        hookMode = currentContext.mode.name
        send(AgentEvent.Completed)
        hookCompleted = true
    }.onCompletion { cause ->
        if (!hookStarted || hookSessionId == null) return@onCompletion
        val status = when {
            hookCompleted -> if (hookTerminalError) "error" else "success"
            cause is CancellationException -> "cancelled"
            cause != null -> "error"
            hookTerminalError -> "error"
            else -> "success"
        }
        val code = if (status == "error" && hookErrorCode == null) {
            cause?.javaClass?.simpleName
        } else {
            hookErrorCode
        }
        pluginHookDispatcher.dispatchRunFinished(
            sessionId = hookSessionId,
            runId = hookRunId,
            mode = hookMode,
            scope = hookScope,
            agentName = hookAgentName,
            status = status,
            toolCalls = hookToolCalls,
            errorCode = code
        )
    }
    }

    /**
     * 流式响应累计器：把 [AIProvider.completeStream] 的增量按时间窗口节流后推送，并保留最终响应与调用统计。
     *
     * 独立成类是为了不让 [executeEvents] 的协程状态机为每个增量都插入挂起点——那个 `channelFlow` 主体
     * 是本工程最大的协程方法，规模过大会被 ART 长期交给解释器执行。
     *
     * 节流：上游每个 chunk 都携带完整累积文本，逐条推送会让 ViewModel 端每秒重建几十次状态、
     * UI 端反复重启打字机协程。按时间窗口合并，窗口内只保留最新累积文本，到窗口边界才发送，
     * 把下游事件频率压到 ~16/s；打字机渲染本身有 100ms 节流，少发中间态无感知。
     */
    private class StreamAccumulator(
        private val callStartElapsed: Long,
        private val emit: suspend (AgentEvent) -> Unit
    ) {
        private companion object {
            const val DELTA_THROTTLE_MS = 60L
        }

        /** 累积正文（节流推送与最终落库都用它）。 */
        val text = StringBuilder()

        /** 累积思考内容。 */
        val reasoning = StringBuilder()

        var finalResponse: AIResponse? = null
        var ttfbElapsed: Long? = null
        var retryAttempts = 0

        private var pendingText: String? = null
        private var pendingReasoning: String? = null
        private var lastTextSentAt = 0L
        private var lastReasoningSentAt = 0L

        suspend fun onChunk(chunk: AIStreamChunk) {
            when (chunk) {
                is AIStreamChunk.TextDelta -> {
                    markTtfb()
                    text.append(chunk.text)
                    pendingText = text.toString()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTextSentAt >= DELTA_THROTTLE_MS) {
                        flushText()
                        lastTextSentAt = now
                    }
                }
                is AIStreamChunk.ReasoningDelta -> {
                    // 思考内容也算首字（推理模型先吐思考再吐正文）
                    markTtfb()
                    reasoning.append(chunk.text)
                    pendingReasoning = reasoning.toString()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReasoningSentAt >= DELTA_THROTTLE_MS) {
                        flushReasoning()
                        lastReasoningSentAt = now
                    }
                }
                is AIStreamChunk.Retrying -> {
                    retryAttempts = maxOf(retryAttempts, chunk.attempt)
                    reset()
                    emit(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                }
                is AIStreamChunk.KeySwitched -> {
                    reset()
                    emit(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                }
                is AIStreamChunk.Final -> {
                    // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                    markTtfb()
                    finalResponse = chunk.response
                }
                is AIStreamChunk.ToolCallDeclared -> {
                    // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                    // 免得长参数流式期间一直停在「正在思考」。
                    markTtfb()
                    emit(AgentEvent.ToolCallPreparing(chunk.name))
                }
            }
        }

        /** 补发窗口内压着的最后一段文本（流结束时调用）。 */
        suspend fun flush() {
            flushText()
            flushReasoning()
        }

        private fun markTtfb() {
            if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
        }

        /** 重试/换 Key 后重放：丢弃已推送内容，避免新旧文本拼接。 */
        private fun reset() {
            text.setLength(0)
            reasoning.setLength(0)
            pendingText = null
            pendingReasoning = null
            lastTextSentAt = 0L
            lastReasoningSentAt = 0L
        }

        private suspend fun flushText() {
            val t = pendingText ?: return
            pendingText = null
            emit(AgentEvent.AssistantDelta(t))
        }

        private suspend fun flushReasoning() {
            val t = pendingReasoning ?: return
            pendingReasoning = null
            emit(AgentEvent.ReasoningDelta(t))
        }
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            // 工具实现在此被调用；工作流本身在主线程收集，故统一切到 IO——避免远程模式下工具里的
            // 同步文件/网络 IO 阻塞主线程。依赖主线程的工具（浏览器）在自身实现内已切回 Main。
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "title",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        return try {
            // 同 runToolSync：流式工具也可能在主线程收集上下文里被驱动，统一切 IO。
            withContext(Dispatchers.IO) {
                val live = StringBuilder()
                var lastEmitMs = 0L
                var finalResult: ToolResult? = null
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 把模型直出的图片（base64）落盘到 `~/.aicode/generated-images/`，返回带容器路径的 images
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // 进入 PLAN 时记住当前模式；退出时恢复到它（AUTO→PLAN 的规划往返结束后回到 AUTO 而非 BUILD）。
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.AUTO -> promptProvider.resolvePrompt(MODE_REMINDER_AUTO_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /**
     * 取本次应注入的模式提醒：仅当模式与上次注入时不同（含首次）才返回文本并记录，否则为 null。
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** 工具调用成功后写入 planMode 结果 data 的模式状态通知（当轮即可见）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "【模式切换】" + appContext.getString(R.string.plan_mode_approved_build)
        AgentMode.AUTO -> "【模式切换】" + appContext.getString(R.string.plan_mode_approved_auto)
    }

    /** 界面手动切换时写入模式通知 detail 的提示，区分计划审批与用户直接切换。 */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "【模式切换】" + appContext.getString(R.string.plan_mode_external_build)
        AgentMode.AUTO -> "【模式切换】" + appContext.getString(R.string.plan_mode_external_auto)
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun toolResultSuccessData(raw: String): JsonElement? {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if ((obj["status"] as? JsonPrimitive)?.contentOrNull != "success") return null
        return obj["data"]
    }

    private fun replaceToolResultData(raw: String, data: JsonElement): String {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return raw
        return JsonObject(obj.toMutableMap().apply { this["data"] = data }).toString()
    }

    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?,
        runId: String,
        scope: String,
        agentName: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // planMode 退出 PLAN 时，后续会有计划审查面板兜底用户决策，
        // 此处权限弹窗冗余，直接放行；进入 PLAN 的方向无后续审查面板，仍走权限弹窗。
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") {
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                // 模式切换仍由本人确认；高危 ASK（Shizuku、elevate、配置修改、
                // 删除子代理、危险 rm）同样进入代审核链。
                if (tool.name != "planMode") {
                    val review = pluginHookDispatcher.reviewPermission(
                        runId = runId,
                        sessionId = sessionId,
                        mode = mode.name,
                        scope = scope,
                        agentName = agentName,
                        callId = callId,
                        toolName = tool.name,
                        arguments = JsonObject(arguments)
                    )
                    when (review.decision) {
                        PermissionReviewDecision.ALLOW -> return PermissionCheckResult(true)
                        PermissionReviewDecision.DENY -> return PermissionCheckResult(
                            approved = false,
                            denyReason = review.reason ?: "插件代审核拒绝了该工具调用",
                            errorCode = PLUGIN_REVIEW_DENIED
                        )
                        PermissionReviewDecision.PASS -> Unit
                    }
                }
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty()
                )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }
}

internal fun appendPlanModeNotice(rawToolResult: String, notice: String): String {
    val result = Json.parseToJsonElement(rawToolResult).jsonObject
    val data = result.getValue("data").jsonPrimitive.content
    return JsonObject(result + ("data" to JsonPrimitive("$data\n\n${notice.trim()}"))).toString()
}
