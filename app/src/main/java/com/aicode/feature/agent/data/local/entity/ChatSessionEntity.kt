package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.workspace.domain.model.WorkspaceRef

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["workspacePath"]), Index(value = ["backendInstanceId", "workspaceId"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = AgentMode.BUILD.name,
    /** 进入 PLAN 前的模式（如 AUTO）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: String? = null,
    /** 会话级持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** goal 生命周期状态；[goal] 为 null 时无意义。 */
    val goalStatus: String? = null,
    val goalTurnCount: Int = 0,
    val goalStartInputTokens: Int = 0,
    val goalStartOutputTokens: Int = 0,
    val reasoningEffort: String = ReasoningEffort.MEDIUM.name,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** 会话绑定的工作流 ID；null 表示使用默认内置工作流。 */
    val workflowId: String? = null,
    /** 会话绑定的工作流持久化状态；null 表示无状态。 */
    val workflowState: String? = null,
    val backendInstanceId: String? = null,
    val workspaceId: String? = null
) {
    @get:Ignore
    val workspaceRef: WorkspaceRef?
        get() = backendInstanceId?.let { instance -> workspaceId?.let { WorkspaceRef(instance, it) } }

    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        workspaceRef = workspaceRef,
        mode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.BUILD),
        modeBeforePlan = modeBeforePlan?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() },
        goal = goal,
        goalStatus = goalStatus?.let { runCatching { GoalStatus.valueOf(it) }.getOrNull() },
        goalTurnCount = goalTurnCount,
        goalStartInputTokens = goalStartInputTokens,
        goalStartOutputTokens = goalStartOutputTokens,
        reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(ReasoningEffort.MEDIUM),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        parentId = parentId,
        subagentType = subagentType,
        workflowId = workflowId,
        workflowState = workflowState
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            workspacePath = session.workspacePath,
            backendInstanceId = session.workspaceRef?.backendInstanceId,
            workspaceId = session.workspaceRef?.workspaceId,
            mode = session.mode.name,
            modeBeforePlan = session.modeBeforePlan?.name,
            goal = session.goal,
            goalStatus = session.goalStatus?.name,
            goalTurnCount = session.goalTurnCount,
            goalStartInputTokens = session.goalStartInputTokens,
            goalStartOutputTokens = session.goalStartOutputTokens,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens,
            isPinned = session.isPinned,
            parentId = session.parentId,
            subagentType = session.subagentType,
            workflowId = session.workflowId,
            workflowState = session.workflowState
        )
    }
}
