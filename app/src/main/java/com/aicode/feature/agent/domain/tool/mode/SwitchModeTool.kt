package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 可以主动申请切换当前会话的模式（PLAN / BUILD）。
 */
class SwitchModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "switchMode"
    override val description = "切换当前会话的模式。支持 PLAN / BUILD / TARGET 三种切换。BUILD 切换到 PLAN 可构思复杂逻辑，PLAN 计划完成后切 BUILD 修改代码。切换到 TARGET 模式需提供 goal 参数设定目标声明，进入后 AI 依据目标自主执行直到达成或失败。切换前需要用户授权。注意：AUTO（自动）模式只能由用户在界面上手动切换进入，本工具无法切换到 AUTO；但处于 AUTO 模式时，可通过本工具切换到 PLAN 模式退出自动模式（这是 AI 退出 AUTO 的唯一路径）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "mode" to ToolParameter(
            name = "mode",
            type = ParameterType.STRING,
            description = "目标模式，必须是 'PLAN'、'BUILD' 或 'TARGET'",
            required = true,
            enum = listOf("PLAN", "BUILD", "TARGET")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "切换模式的理由，将展示给用户",
            required = true
        ),
        "goal" to ToolParameter(
            name = "goal",
            type = ParameterType.STRING,
            description = "切换到 TARGET 模式时的目标声明，描述 AI 需自主达成的目标。仅 mode=TARGET 时必需",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val targetModeStr = args["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
            ?: return ToolResult.Error("缺少必需参数: mode", "MISSING_MODE")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: reason", "MISSING_REASON")

        val targetMode = try {
            AgentMode.valueOf(targetModeStr)
        } catch (e: Exception) {
            return ToolResult.Error("无效的模式: $targetModeStr，只能是 PLAN、BUILD 或 TARGET", "INVALID_MODE")
        }

        if (targetMode == AgentMode.AUTO) {
            return ToolResult.Error("AUTO 模式只能由用户在界面上手动切换，AI 无法通过工具切换", "AUTO_MODE_MANUAL_ONLY")
        }

        // AUTO 模式下 AI 只能切到 PLAN（唯一退出路径），不能直接切到 BUILD
        if (context.mode == AgentMode.AUTO && targetMode != AgentMode.PLAN) {
            return ToolResult.Error("当前处于 AUTO 模式，只能切换到 PLAN 模式退出自动模式", "AUTO_EXIT_PLAN_ONLY")
        }

        // TARGET 模式必须提供 goal 参数
        if (targetMode == AgentMode.TARGET) {
            val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
            if (goal.isNullOrEmpty()) {
                return ToolResult.Error("切换到 TARGET 模式需要提供 goal 参数描述目标声明", "MISSING_GOAL")
            }
        }

        if (context.mode == targetMode) {
            return ToolResult.Success(JsonPrimitive("当前已处于 ${targetMode.name} 模式，无需切换。"))
        }

        val sessionId = context.sessionId
        if (sessionId == null) {
            return ToolResult.Error("未关联会话 ID，无法切换模式", "NO_SESSION")
        }

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        // 从 TARGET 切出视为中断目标执行，记录终止原因（保留检查点供回滚）
        val terminationReason = if (context.mode == AgentMode.TARGET) {
            com.aicode.feature.agent.domain.model.GoalTerminationReason.INTERRUPTED.name
        } else null

        val goalStatement = if (targetMode == AgentMode.TARGET) {
            args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
        } else null

        // 切换模式并保存到数据库。进入 TARGET 重置步数与失败计数并落 goal；从 TARGET 切出记录中断。
        val updated = sessionEntity.copy(
            mode = targetMode.name,
            goalStatement = goalStatement ?: if (targetMode == AgentMode.TARGET) sessionEntity.goalStatement else sessionEntity.goalStatement,
            goalStepCount = if (targetMode == AgentMode.TARGET) 0 else sessionEntity.goalStepCount,
            goalFailCount = if (targetMode == AgentMode.TARGET) 0 else sessionEntity.goalFailCount,
            goalTerminationReason = terminationReason ?: sessionEntity.goalTerminationReason
        )
        chatSessionDao.upsert(updated)

        val extra = if (targetMode == AgentMode.TARGET) "，目标：${goalStatement}" else ""
        return ToolResult.Success(JsonPrimitive("成功切换至 ${targetMode.name} 模式$extra。"))
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "无理由"
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull
        val details = if (goal != null) {
            "目标模式：$mode\n\n目标声明：$goal\n\n申请理由：$reason"
        } else {
            "目标模式：$mode\n\n申请理由：$reason"
        }

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "模式切换申请",
            summary = "AI 申请切换为 $mode 模式",
            details = details,
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}
