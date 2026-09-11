package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理 → 主会话的单向消息工具 `messageParent`。
 *
 * 异步不阻塞：调用即返回，消息由 ViewModel 按主会话状态分发（忙碌时搭车送达，空闲时触发新一轮）。
 * 只在子代理会话中可用——主会话的工具集剔除了它（见 [com.aicode.feature.agent.presentation.AIAgentViewModel]）。
 */
class MessageParentTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val eventBus: SubAgentEventBus
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "MessageParentTool"
        const val MESSAGE_MAX = 4000
    }

    override val name = "messageParent"

    override val description = "向主会话（父代理）发送一条消息：汇报进展、求助、或提交需要主会话协调/决策的事项。异步发送、不阻塞你继续执行；主会话的回复会作为后续消息送达，无需等待。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给主会话的消息正文（进度、问题、需要协调或决策的事项）",
            required = true
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSessionId = sessionUseCase.getSessionById(sessionId)?.parentId
            ?: return ToolResult.Error("当前会话不是子代理，无法向主会话发送消息", "NO_PARENT")
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        if (message.length > MESSAGE_MAX) {
            return ToolResult.Error("消息过长（上限 $MESSAGE_MAX 字）", "INVALID_ARGS")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = sessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_SUB,
                detail = message
            )
        )
        FileLogger.i(TAG, "子代理向主会话发消息: session=$sessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("state", "sent")
                put("message", "消息已发送给主会话，可继续手头工作；主会话的回复会稍后送达。")
            }
        )
    }
}
