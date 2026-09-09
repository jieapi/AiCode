package com.aicode.feature.agent.domain.model

/**
 * Agent 的工作模式。
 */
enum class AgentMode {
    BUILD, // 默认模式，允许所有授权操作
    PLAN,  // 计划模式，拦截修改类操作，只读/探索为主
    AUTO   // 自动模式，放行所有权限（不弹窗），仅用户手动可切换；AI 无法通过 switchMode 进入
}

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
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
    /** 子代理会话：创建时实际命中的预设名；null 表示未用预设（继承主会话）。 */
    val presetName: String? = null,
    /** 群聊房间标记：true 表示该会话是一个群聊房间（成员发言以 USER+senderName 落此会话）。 */
    val isGroupChat: Boolean = false,
    /** 群聊房间成员配置（JSON 编码的 List<GroupChatMember>）；非房间会话为 null。 */
    val groupMembersJson: String? = null
)
