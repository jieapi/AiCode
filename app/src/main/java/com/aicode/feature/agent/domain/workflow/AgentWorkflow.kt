package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Agent 执行过程中向 UI 推送的分步事件。
 * 每个事件对应聊天里的一条（或对一条的更新），由 ViewModel 落库并渲染。
 */
sealed class AgentEvent {
    /** 模型产出的一段文字回复（可能同时伴随工具调用）。[reasoning] 为本轮思考过程，落库供历史展示，空串表示无。 */
    data class AssistantText(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名，随 reasoning 落库，供工具循环回传。其他 provider 为空串。 */
        val signature: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），随 reasoning 落库供后续轮原样回传。 */
        val thinkingBlocksJson: String = "",
        /** 模型直出图片（Gemini 图像模型）落盘后构造的文件卡片，随消息落库供 UI 渲染。 */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList()
    ) : AgentEvent()

    /** 流式过程中模型逐字吐出的文字（[accumulated] 为本轮已累积的完整文本，用于 UI 实时渲染，不落库）。 */
    data class AssistantDelta(val accumulated: String) : AgentEvent()

    /** 流式过程中模型逐字吐出的思考过程（[accumulated] 为本轮已累积的完整思考，用于 UI 实时渲染，不落库）。 */
    data class ReasoningDelta(val accumulated: String) : AgentEvent()

    /** 模型决定调用某工具（执行前）。 */
    data class ToolCallStarted(val id: String, val toolName: String, val argsPreview: String) : AgentEvent()

    /**
     * 模型刚开始产出某次工具调用、工具名已知（参数还在流式传输中）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间既没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。这条事件让 UI 提前把状态说具体，不落库、不参与执行判定。
     */
    data class ToolCallPreparing(val toolName: String) : AgentEvent()

    /** 工具执行过程中的实时累积输出（仅流式工具产生，用于 UI 实时渲染，不落库）。 */
    data class ToolCallProgress(val id: String, val toolName: String, val accumulated: String) : AgentEvent()

    /** 工具执行完成的结果（成功、失败或被用户拒绝）。 */
    data class ToolCallFinished(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        val argsPreview: String? = null,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，落库供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList()
    ) : AgentEvent()

    /** 网络请求正在重试（首字节前失败触发自动重试）。仅用于 UI 实时展示，不落库。[error] 为触发重试的错误摘要。 */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AgentEvent()

    /** 多 Key 自动切换：当前 Key 不可用，已改用第 [newIndex]/[total] 个 Key 重发本次请求。仅用于 UI 实时展示，不落库。 */
    data class KeySwitched(val newIndex: Int, val total: Int) : AgentEvent()

    /** 正在进行上下文压缩。仅用于 UI 实时展示，不落库。 */
    data class CompactionStarted(val estimatedTokens: Int) : AgentEvent()

    /** 上下文压缩流程已结束（成功或失败）。仅用于 UI 实时展示，不落库。 */
    object CompactionFinished : AgentEvent()

    /** 上下文压缩失败（如压缩模型不可用）。携带失败原因，UI 展示为可展开的失败卡片；不落库。 */
    data class CompactionFailed(val reason: String) : AgentEvent()

    /**
     * 整个流程因错误终止（如流式请求被网络中断、达到迭代上限）。与 [Completed] 区别：携带错误，UI 应展示错误而非成功。
     * [reasonCode] 为服务端给出的类型码（如 Anthropic 的 refusal / model_context_window_exceeded），
     * 供 UI 换成本地化说明；null 时直接展示 [error]。
     */
    data class Failed(val error: String, val reasonCode: String? = null) : AgentEvent()

    /** 整个流程结束。 */
    object Completed : AgentEvent()

    /** 模式已切换（由 AI 调用 planMode 触发），UI 据此展示计划审查面板等。 */
    data class ModeChanged(val newMode: AgentMode, val reason: String) : AgentEvent()
}

interface AgentWorkflow {
    /**
     * 运行 Agent 循环并以事件流的形式分步推送进度：
     * 模型回复 → （可能的）工具调用 → 工具结果 → 再回复 …… 直至模型不再调用工具。
     */
    fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent>

    /**
     * 手动触发指定会话的上下文压缩（供 /compress 命令调用）。
     * 复用自动压缩逻辑：解析当前生效 provider，对历史消息调用 ContextCompactor。
     * 压缩结果由 ContextCompactor 内部持久化，通过 [onEvent] 回调推送压缩进度事件。
     * @return 是否实际触发了压缩（历史未超阈值时返回 false，不改动）。
     */
    suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit = {}): Boolean

    /**
     * 为新建会话生成标题（供首条用户消息后异步调用）。
     * 默认跟随当前聊天模型；若配置了标题总结专用模型且可用则用之。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    suspend fun generateTitle(sessionId: String, request: String): String?

    /**
     * 根据 Git 差异文本生成符合 Conventional Commits 规范的提交信息。
     * 默认使用全局生效的 AI 供应商及默认模型。
     * @param diff 变更内容差异文本（`git diff --cached` 或工作区差异）
     * @return 建议的提交说明，失败返回 null。
     */
    suspend fun generateCommitMessage(diff: String): String?

    /**
     * 会话轮次结束后的记忆兑现（引擎级兑底）：用轻量模型从本轮对话文本中抽取值得长期记住的事实，
     * 直接写入记忆存储。静默失败，不抛异常。
     * @param transcript 本轮对话文本（"用户: …/助手: …" 行），由调用方截取最近内容拼出。
     * @return 本次实际写入的记忆条数。
     */
    suspend fun curateMemory(sessionId: String, projectRoot: String?, transcript: String): Int
}
