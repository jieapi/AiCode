package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["workspacePath"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = AgentMode.BUILD.name,
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
    /** 子代理会话：创建时实际命中的预设名（task 显式 preset 或按序自动分配）；null 表示未用预设（继承主会话）。 */
    val presetName: String? = null,
    /** 群聊房间标记：1 表示该会话是一个群聊房间（成员发言以 USER+senderName 落此会话）。 */
    val isGroupChat: Boolean = false,
    /** 群聊房间成员配置（JSON 编码的 List<GroupChatMember>）；非房间会话为 null。 */
    val groupMembersJson: String? = null
) {
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        mode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.BUILD),
        reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(ReasoningEffort.MEDIUM),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        parentId = parentId,
        subagentType = subagentType,
        presetName = presetName,
        isGroupChat = isGroupChat,
        groupMembersJson = groupMembersJson
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            workspacePath = session.workspacePath,
            mode = session.mode.name,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens,
            isPinned = session.isPinned,
            parentId = session.parentId,
            subagentType = session.subagentType,
            presetName = session.presetName,
            isGroupChat = session.isGroupChat,
            groupMembersJson = session.groupMembersJson
        )
    }
}
