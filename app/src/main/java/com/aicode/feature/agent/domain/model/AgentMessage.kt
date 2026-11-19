package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.WorkflowTurnContext

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val images: List<AgentImage> = emptyList()
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** 本轮模型的思考过程（对应 OpenAI/DeepSeek 的 reasoning_content）。回传上下文时需要原样发回，否则 DeepSeek 思考模式会报 400 错误。 */
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名。与 [reasoning] 一起原样回传（工具循环必须），否则 400。其他 provider 为空串。 */
        val signature: String = "",
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），回传时需保持原样与原序。其他 provider 为空串。 */
        val thinkingBlocksJson: String = "",
        /**
         * 本轮模型直接生成的图片（Gemini 图像模型）。内存态下 base64Data 可为空、path 指向容器文件，
         * 回放时按 path 重建 base64 喂模型；落库只存附件路径不存 base64（见 [MessagePersistenceUseCase]）。
         */
        val images: List<AgentImage> = emptyList(),
        val inputTokens: Int = 0
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** 仅喂模型的精简结果文本；null 时回退用 [result]。UI 与持久化仍用 result。 */
        val modelResult: String? = null,
        val messageId: String = "tool_$id"
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "What did we do so far?"
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> messageId
    }

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    val inputImages: List<AgentImage> = emptyList(),
    /** 当前会话 id：用于把本轮所有 AI 请求/响应落到该会话的日志文件（[com.aicode.core.util.AILogger]）。 */
    val sessionId: String? = null,
    val inputMessageId: String = java.util.UUID.randomUUID().toString(),
    val lastInputTokens: Int = 0,
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（如 AUTO）：退出 PLAN 时恢复到它，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    /** 当前会话的持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** 当前会话的 goal 生命周期状态；ACTIVE 表示自主循环进行中。 */
    val goalStatus: GoalStatus? = null,
    /** 思考强度（"low"/"medium"/"high"），随每次 LLM 请求传给支持的 provider。 */
    val reasoningEffort: String? = null,
    /**
     * 本会话绑定的自定义子代理定义；非空表示这是一个按定义运行的子代理会话，
     * 系统提示词与工具集都按其配置组装（见 [com.aicode.feature.agent.domain.prompt.SystemPromptProvider]）。
     */
    val agentDefinition: AgentDefinition? = null,
    val turn: WorkflowTurnContext? = null
) {
    /**
     * 工具授权判定用的有效模式：goal 自主循环激活期间按 AUTO 放行（保留灾难性安全防护），
     * 与用户可见的 [mode] 解耦——goal 不改变会话显示的模式，只改变授权强度。
     */
    val permissionMode: AgentMode
        get() = turn?.permissionMode ?: if (goalStatus == GoalStatus.ACTIVE) AgentMode.AUTO else mode
}
