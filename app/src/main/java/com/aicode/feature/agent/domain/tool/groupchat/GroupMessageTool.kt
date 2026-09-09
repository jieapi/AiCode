package com.aicode.feature.agent.domain.tool.groupchat

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.groupchat.GroupChatCoordinator
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

/**
 * 群聊私信工具 `group_message`：供群聊成员（子代理）之间私信，或向用户发言。
 *
 * 语义对齐 Hermes Bot Mode 的 bot-to-bot messaging：
 * - target=成员 key：消息写入目标成员的 Bot Chat 私信会话，对方下次轮次会看到；
 * - target=user：消息写入群聊房间（等价成员在群里对用户发言，房间内可见）。
 *
 * 仅群聊房间的成员子代理会话可调用：发送者由子会话的 subagentType 推导。
 * 自动放行（不弹授权）：成员 turn 内弹授权会卡住群聊轮转，且私信属成员间通信。
 */
class GroupMessageTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val coordinator: GroupChatCoordinator
) : AbstractContextualTool() {

    override val name = "group_message"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val description =
        "给群聊中的另一个成员发私信（写入对方 Bot Chat，对方下次轮次处理），或给用户发言（写入房间）。" +
            "只能由群聊成员调用。target 传成员 key（如 coder）或 user。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "target" to ToolParameter(
            name = "target",
            type = ParameterType.STRING,
            description = "接收者：成员 key（私信，如 \"coder\"）或 \"user\"（在房间里对用户发言）",
            required = true
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "消息内容",
            required = true
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val session = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")
        val roomId = session.parentId
            ?: return ToolResult.Error("group_message 只能由群聊成员调用", "NOT_GROUP_MEMBER")
        val room = chatSessionDao.getById(roomId)
            ?: return ToolResult.Error("群聊房间不存在", "ROOM_NOT_FOUND")
        if (!room.isGroupChat) {
            return ToolResult.Error("group_message 只能由群聊成员调用", "NOT_GROUP_MEMBER")
        }
        val senderKey = session.subagentType
            ?: return ToolResult.Error("无法确定发送者身份", "NOT_GROUP_MEMBER")
        val members = coordinator.decodeMembers(room)
        if (members.none { it.memberKey == senderKey }) {
            return ToolResult.Error("发送者不是该房间成员", "NOT_GROUP_MEMBER")
        }

        val target = (args["target"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolResult.Error("参数无效：text 不能为空", "INVALID_ARGS")
        }
        if (target.isEmpty()) {
            return ToolResult.Error("参数无效：target 不能为空", "INVALID_ARGS")
        }
        if (target.equals("user", ignoreCase = true)) {
            return postToRoom(roomId, senderKey, text)
        }
        val delivered = coordinator.appendDm(roomId, senderKey, target, text)
        if (!delivered) {
            val available = members.joinToString(", ") { it.memberKey }
            return ToolResult.Error("目标成员不存在: $target（房间成员：$available）", "TARGET_NOT_FOUND")
        }
        return ToolResult.Success(
            buildJsonObject {
                put("deliveredTo", target)
                put("mode", "dm")
                put("message", "已私信 $target，对方下次轮次会处理。")
            }
        )
    }

    private suspend fun postToRoom(roomId: String, senderKey: String, text: String): ToolResult {
        agentMessageDao.insert(
            AgentMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = roomId,
                role = MessageRole.USER.name,
                content = text,
                timestamp = System.currentTimeMillis(),
                senderName = senderKey
            )
        )
        chatSessionDao.touch(roomId, System.currentTimeMillis())
        return ToolResult.Success(
            buildJsonObject {
                put("deliveredTo", "user")
                put("mode", "room")
                put("message", "已发送到房间给用户。")
            }
        )
    }
}
