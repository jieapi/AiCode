package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalTerminationReason
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 在 TARGET 模式下声明目标已达成。调用后切回 BUILD 并记录终止原因 ACHIEVED，
 * summary 展示给用户确认。仅在 TARGET 模式可用。
 */
class CompleteGoalTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "completeGoal"
    override val description = "声明当前 TARGET 模式的目标已达成。调用后 AiCode 会提示用户确认，确认即退出 TARGET 回到 BUILD。仅在 TARGET 模式可用，调用时必须提供 summary 描述完成的工作与最终状态。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "summary" to ToolParameter(
            name = "summary",
            type = ParameterType.STRING,
            description = "目标达成总结，说明完成的工作与最终状态，展示给用户确认",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        if (context.mode != AgentMode.TARGET) {
            return ToolResult.Error("当前不在 TARGET 模式，无法声明目标达成", "NOT_IN_TARGET_MODE")
        }

        val summary = args["summary"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: summary", "MISSING_SUMMARY")
        if (summary.isEmpty()) {
            return ToolResult.Error("summary 不能为空", "EMPTY_SUMMARY")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话 ID，无法完成目标", "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        // 标记目标达成并切回 BUILD，终止原因记 ACHIEVED
        chatSessionDao.upsert(
            sessionEntity.copy(
                mode = AgentMode.BUILD.name,
                goalTerminationReason = GoalTerminationReason.ACHIEVED.name
            )
        )

        return ToolResult.Success(JsonPrimitive("目标已达成，会话已切回 BUILD 模式。达成总结：$summary"))
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val summary = args["summary"]?.jsonPrimitive?.contentOrNull ?: "无总结"

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "目标达成确认",
            summary = "AI 声明目标已达成，申请退出 TARGET 模式",
            details = "达成总结：$summary",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}
