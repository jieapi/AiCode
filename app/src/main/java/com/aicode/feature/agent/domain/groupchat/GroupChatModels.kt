package com.aicode.feature.agent.domain.groupchat

import kotlinx.serialization.Serializable

/**
 * 群聊房间成员描述。序列化为房间会话 groupMembersJson。
 */
@Serializable
data class GroupChatMember(
    /** 成员标识：AgentDefinition 名或子代理预设名（唯一）。 */
    val memberKey: String,
    /** 群聊显示名（AgentDefinition.title 或预设名）。 */
    val title: String = "",
    /** @mention 用的 handle（缺省 = memberKey）。 */
    val handle: String = "",
    val avatarColor: String? = null,
    val avatarShape: String? = null,
    /** 来自子代理预设（设置→子代理模型）时记录预设 id，用于解析模型；null 表示来自 agents 目录下 .md。 */
    val presetId: String? = null,
    /** 加入时间。 */
    val joinedAt: Long = System.currentTimeMillis()
)

/** 房间 log 中的一条消息（复用 agent_messages：user 与成员发言同表）。 */
data class GroupChatMessage(
    val id: String,
    /** 'user'（用户或系统）| 'member'（成员发言）。 */
    val kind: String,
    /** 发送者：用户为 "You"，成员为其 memberKey。 */
    val sender: String,
    val text: String,
    val thread: String = "legacy",
    val at: Long = System.currentTimeMillis(),
    /** 成员发言的思考过程（成员 turn 从子会话带回，复用普通聊天 reasoning 气泡）。 */
    val reasoning: String? = null
)

/** 房间运行时状态（不落库，重启归零）。 */
data class GroupChatRoomState(
    /** 用户每发一次消息 epoch+1：旧 drive 在下个成员边界放弃提交。 */
    val epoch: Int = 0,
    /** 是否有 drive 正在轮转。 */
    val running: Boolean = false,
    /** 当前发言成员（UI 显示「X 正在思考…」）。 */
    val turn: String? = null,
    /** 每成员已读位置：memberKey -> 房间消息数。 */
    val watermarks: Map<String, Int> = emptyMap(),
    /** 被用户暂停的成员：memberKey -> hold 时间戳。 */
    val holds: Map<String, Long> = emptyMap(),
    /** 超时后等待回收的成员：memberKey -> {before, thread}。 */
    val strands: Map<String, GroupStrand> = emptyMap()
)

data class GroupStrand(val before: Int, val thread: String)

/** 一次群聊驱动如何结束。 */
enum class GroupDriveExit { SETTLED, CAPPED, CANCELLED }

/** 成员一次 turn 的产出：正文 + 思考过程（可空，与普通聊天 reasoning 同构）。 */
data class MemberTurnReply(val text: String, val reasoning: String?)
