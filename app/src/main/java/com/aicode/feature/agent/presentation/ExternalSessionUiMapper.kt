package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.plugin.domain.session.ExternalMessageBlock
import com.aicode.feature.plugin.domain.session.ExternalSessionMessage
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 外部会话领域模型 ↔ 聊天 UI / 工作流上下文模型的无状态映射。
 */
object ExternalSessionUiMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun toUiMessages(message: ExternalSessionMessage): List<AgentUIMessage> {
        val text = buildString {
            for (block in message.blocks) {
                when (block) {
                    is ExternalMessageBlock.Text -> append(block.text)
                    is ExternalMessageBlock.Notice -> {
                        if (isNotEmpty()) append('\n')
                        append(block.text)
                    }
                    is ExternalMessageBlock.Attachment -> {
                        if (isNotEmpty()) append('\n')
                        append("[附件")
                        if (block.name.isNotBlank()) append("：").append(block.name)
                        append("]")
                    }
                    is ExternalMessageBlock.Reasoning, is ExternalMessageBlock.Tool -> Unit
                }
            }
        }.trim()

        val reasoning = message.blocks
            .filterIsInstance<ExternalMessageBlock.Reasoning>()
            .joinToString("\n") { it.text }
            .trim()
            .ifBlank { null }

        val role = when (message.role.lowercase()) {
            "user" -> MessageRole.USER
            "tool" -> MessageRole.TOOL
            else -> MessageRole.ASSISTANT
        }
        val timestamp = message.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
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
        message.blocks.filterIsInstance<ExternalMessageBlock.Tool>().forEachIndexed { index, tool ->
            result += AgentUIMessage(
                id = tool.id.takeIf { it.isNotBlank() }?.let { "ext-tool-$it" } ?: "${message.id}-tool-$index",
                role = MessageRole.TOOL,
                content = tool.result.orEmpty(),
                timestamp = timestamp,
                toolName = tool.name,
                toolArgs = tool.arguments,
                isError = tool.isError || tool.status.equals("error", ignoreCase = true)
            )
        }
        return result
    }

    fun toUiMessages(messages: List<ExternalSessionMessage>): List<AgentUIMessage> =
        messages.flatMap { toUiMessages(it) }

    fun toAgentHistory(messages: List<ExternalSessionMessage>): List<AgentMessage> {
        val out = mutableListOf<AgentMessage>()
        for (msg in messages) {
            val text = msg.blocks.filterIsInstance<ExternalMessageBlock.Text>()
                .joinToString("\n") { it.text }
                .trim()
            val reasoning = msg.blocks.filterIsInstance<ExternalMessageBlock.Reasoning>()
                .joinToString("\n") { it.text }
                .trim()
            val tools = msg.blocks.filterIsInstance<ExternalMessageBlock.Tool>()
            when (msg.role.lowercase()) {
                "user" -> out += AgentMessage.UserMessage(id = msg.id, content = text)
                "tool" -> {
                    val tool = tools.firstOrNull()
                    out += AgentMessage.ToolResultMessage(
                        id = tool?.id?.ifBlank { msg.id } ?: msg.id,
                        toolName = tool?.name ?: "tool",
                        result = tool?.result ?: text
                    )
                }
                else -> {
                    val toolCalls = tools.mapIndexed { idx, t ->
                        ToolCall(
                            id = t.id.ifBlank { "${msg.id}-tc-$idx" },
                            name = t.name,
                            arguments = parseJsonObjectOrEmpty(t.arguments)
                        )
                    }
                    out += AgentMessage.AssistantMessage(
                        id = msg.id,
                        content = text,
                        reasoning = reasoning,
                        toolCalls = toolCalls
                    )
                    tools.forEachIndexed { idx, t ->
                        if (t.result != null) {
                            out += AgentMessage.ToolResultMessage(
                                id = t.id.ifBlank { "${msg.id}-tc-$idx" },
                                toolName = t.name,
                                result = t.result
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    fun toChatSession(session: ExternalSessionSummary): ChatSession = ChatSession(
        id = session.id,
        title = session.title.ifBlank { "未命名会话" },
        createdAt = session.updatedAt,
        updatedAt = session.updatedAt,
        workspacePath = session.workspaceId.orEmpty(),
        providerId = session.providerId,
        model = session.model,
        isPinned = session.isPinned,
        workflowId = session.workflowId
    )

    private fun parseJsonObjectOrEmpty(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }
}
