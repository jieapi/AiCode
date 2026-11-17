package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.domain.session.PluginSessionGroup
import com.aicode.feature.plugin.domain.session.PluginSessionMessage
import com.aicode.feature.plugin.domain.session.PluginSessionSummary

/**
 * 插件会话模型 → 聊天 UI 模型的无状态映射。
 *
 * 插件消息不写入 Room，AIChatPanel 也不应认识 `PluginSessionMessage`：统一在这里转成
 * [AgentUIMessage]，让插件会话与本地会话共用同一套消息渲染。映射层不发起任何 IO。
 */
object PluginSessionUiMapper {

    /**
     * 一条插件消息可展开成多条 UI 消息：正文/思考合成一条气泡，工具 block 各自成条。
     * 保持与插件会话旧展示逻辑一致，接入 AIChatPanel 后观感不变。
     */
    fun toUiMessages(message: PluginSessionMessage): List<AgentUIMessage> {
        val text = buildString {
            for (block in message.blocks) {
                when (block) {
                    is PluginMessageBlock.Text -> append(block.text)
                    is PluginMessageBlock.Notice -> append(block.text)
                    is PluginMessageBlock.Attachment -> append(
                        "\n[附件${block.name?.let { "：$it" } ?: ""}]"
                    )
                    is PluginMessageBlock.Reasoning, is PluginMessageBlock.Tool -> Unit
                }
            }
        }.trim()

        val reasoning = message.blocks
            .filterIsInstance<PluginMessageBlock.Reasoning>()
            .joinToString("\n") { it.text }
            .trim()
            .ifBlank { null }

        val role = when (message.role) {
            "user" -> MessageRole.USER
            "tool" -> MessageRole.TOOL
            else -> MessageRole.ASSISTANT
        }
        val timestamp = message.createdAt ?: System.currentTimeMillis()
        val result = mutableListOf<AgentUIMessage>()
        if (text.isNotEmpty() || reasoning != null || message.blocks.isEmpty()) {
            result += AgentUIMessage(
                id = message.id,
                role = role,
                content = text,
                timestamp = timestamp,
                reasoning = reasoning
            )
        }
        message.blocks.filterIsInstance<PluginMessageBlock.Tool>().forEachIndexed { index, tool ->
            result += AgentUIMessage(
                id = tool.callId?.let { "remote-tool-$it" } ?: "${message.id}-tool-$index",
                role = MessageRole.TOOL,
                content = tool.output.orEmpty(),
                timestamp = timestamp,
                toolName = tool.name,
                toolArgs = tool.input.orEmpty(),
                isError = tool.status == "error"
            )
        }
        return result
    }

    /** 批量转换；对话流按返回顺序拼接（翻页时由调用方决定 prepend / append）。 */
    fun toUiMessages(messages: List<PluginSessionMessage>): List<AgentUIMessage> =
        messages.flatMap { toUiMessages(it) }

    /**
     * 按 pluginId 分组。`groupBy` 使用 LinkedHashMap，组顺序与组内会话顺序都保持
     * [PluginSessionSourceService.listSessions] 的返回顺序。
     */
    fun toGroups(sessions: List<PluginSessionSummary>): List<PluginSessionGroup> =
        sessions.groupBy { it.pluginId }.map { (pluginId, groupSessions) ->
            PluginSessionGroup(
                pluginId = pluginId,
                pluginName = groupSessions.firstOrNull()?.pluginName.orEmpty(),
                sessions = groupSessions
            )
        }

    /**
     * 插件会话的合成 [ChatSession]：只供 AIChatPanel 顶栏标题/时间等只读展示，
     * 不对应任何 Room 行，因此 workspacePath 留空、其它字段取默认值。
     */
    fun toChatSession(session: PluginSessionSummary): ChatSession {
        val updatedAt = session.updatedAt ?: 0L
        return ChatSession(
            id = session.id,
            title = session.title,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            workspacePath = ""
        )
    }
}
