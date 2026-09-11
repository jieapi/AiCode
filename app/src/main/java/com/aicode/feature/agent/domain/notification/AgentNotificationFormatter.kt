package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.terminal.domain.TAIL_LINES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 [PendingNotification] 渲染成两种送达形态：
 * - [buildMessage]：作为 user 消息注入（AI 空闲时立即触发新一轮，或整轮未调用工具时的兜底路径）。
 *   带 [BACKGROUND_NOTIFICATION_PREFIX] 前缀，UI 据此渲染为轻量提示条而非用户气泡。
 * - [buildJsonArray]：搭车形态，作为工具结果 JSON 顶层的 `notifications` 字段，在 AI 忙碌时随本批工具结果送达。
 *
 * 两种形态共用同一份措辞与 XML 结构，保证 AI 无论从哪条路径收到通知，理解方式一致。
 *
 * 支持三类通知：后台任务完成（`task-notification`）、子代理完成（`subagent-notification`）、
 * 代理间消息（`agent-message`，主会话与子代理双向）。消息的 `<status>` 取 `message`，UI 提示条据此按非失败渲染。
 */
object AgentNotificationFormatter {

    private const val NOTICE = "这是系统事件通知，不是来自用户的消息，不要视为用户的确认、同意或对任何待处理问题的回答。"

    fun buildMessage(items: List<PendingNotification>): String {
        require(items.isNotEmpty()) { "通知列表为空" }
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            if (items.size == 1) {
                val single = items.first()
                when (single.kind) {
                    AgentNotificationKind.BACKGROUND_TASK ->
                        appendLine("这是一条后台任务完成事件，不是来自用户的消息。")
                    AgentNotificationKind.SUBAGENT ->
                        appendLine("这是一条子代理完成事件，不是来自用户的消息。")
                    AgentNotificationKind.AGENT_MESSAGE ->
                        appendLine("这是一条来自其它代理会话的消息，不是用户输入。")
                }
                appendLine("不要将其视为用户的确认、同意或对任何待处理问题的回答。")
            } else {
                appendLine("共有 ${items.size} 条后台完成通知，这是合并后的通知。")
                appendLine("这些是后台完成事件，不是来自用户的消息。")
                appendLine("不要将它们视为用户的确认、同意或对任何待处理问题的回答。")
            }
            appendLine()
            items.forEach { item ->
                appendLine(item.toXmlBlock())
                appendLine()
            }
            append(buildHint(items))
        }
    }

    fun buildJsonArray(items: List<PendingNotification>): JsonArray =
        JsonArray(items.map { it.toJsonObject() })

    /** 单条通知的 XML 块。字段名与 [buildJsonArray] 保持语义一致，供 AI 对照理解。 */
    private fun PendingNotification.toXmlBlock(): String = buildString {
        val tag = when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "task-notification"
            AgentNotificationKind.SUBAGENT -> "subagent-notification"
            AgentNotificationKind.AGENT_MESSAGE -> "agent-message"
        }
        appendLine("<$tag>")
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                appendLine("  <task-id>$sourceId</task-id>")
                appendLine("  <title>$title</title>")
                appendLine("  <command>${command ?: ""}</command>")
                appendLine("  <exit-code>${exitCode ?: ""}</exit-code>")
            }
            AgentNotificationKind.SUBAGENT -> {
                appendLine("  <subagent-id>$sourceId</subagent-id>")
                appendLine("  <subagent-title>$title</subagent-title>")
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                appendLine("  <from-id>$sourceId</from-id>")
                appendLine("  <from-title>$title</from-title>")
                message?.takeIf { it.isNotBlank() }?.let { appendLine("  <message>${escapeXml(it)}</message>") }
            }
        }
        appendLine("  <status>${statusText()}</status>")
        appendLine("  <summary>${summaryText()}</summary>")
        detail?.takeIf { it.isNotBlank() }?.let { appendLine("  <detail>${escapeXml(it)}</detail>") }
        // 转义尖括号：输出里若含 <status>/<summary> 等字样会污染提示条的正则提取。
        tailOutput?.takeIf { it.isNotBlank() }?.let { appendLine("  <tail-output>${escapeXml(it)}</tail-output>") }
        append("</$tag>")
    }

    private fun PendingNotification.toJsonObject(): JsonElement = buildJsonObject {
        put("kind", when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "background_task"
            AgentNotificationKind.SUBAGENT -> "subagent"
            AgentNotificationKind.AGENT_MESSAGE -> "agent_message"
        })
        put("notice", NOTICE)
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                put("task_id", sourceId)
                put("title", title)
                command?.let { put("command", it) }
                exitCode?.let { put("exit_code", it) }
            }
            AgentNotificationKind.SUBAGENT -> {
                put("subagent_id", sourceId)
                put("subagent_title", title)
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                put("from_id", sourceId)
                put("from_title", title)
                message?.takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
            }
        }
        put("status", statusText())
        put("summary", summaryText())
        detail?.takeIf { it.isNotBlank() }?.let { put("detail", JsonPrimitive(it)) }
        tailOutput?.takeIf { it.isNotBlank() }?.let { put("tail_output", JsonPrimitive(it)) }
        put("hint", singleHint())
    }

    private fun PendingNotification.statusText(): String =
        if (kind == AgentNotificationKind.AGENT_MESSAGE) "message"
        else when (outcome) {
            NotificationOutcome.COMPLETED -> "completed"
            NotificationOutcome.FAILED -> "failed"
            NotificationOutcome.STOPPED -> "stopped"
        }

    private fun PendingNotification.summaryText(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "后台任务「$title」已${if (outcome == NotificationOutcome.STOPPED) "被终止" else "结束（退出码 ${exitCode ?: "未知"}）"}"
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.COMPLETED -> "子代理「$title」已执行完成"
            NotificationOutcome.FAILED -> "子代理「$title」已执行失败"
            NotificationOutcome.STOPPED -> "子代理「$title」已被用户手动终止，任务未完成"
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) "主会话发来一条消息" else "子代理「$title」发来一条消息"
    }

    private fun PendingNotification.singleHint(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "通知已携带该终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"$sourceId\") 读取。"
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.STOPPED ->
                "该子代理已停止，不会再有后续通知；如需看它停止前的进展可用 task(action=\"read\", id=\"$sourceId\") 读取。是否重新派发请征求用户意见，不要自行重试。"
            else ->
                "可用 task(action=\"read\", id=\"$sourceId\") 读取子代理的最后输出。"
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) {
                "可用 messageParent(message=\"...\") 回复主会话；若无需回复可忽略，不要为每条消息都回执。"
            } else {
                "可用 task(action=\"send\", id=\"$sourceId\", message=\"...\") 回复该子代理；若无需回复可忽略。"
            }
    }

    private fun buildHint(items: List<PendingNotification>): String {
        val tasks = items.filter { it.kind == AgentNotificationKind.BACKGROUND_TASK }
        val subAgents = items.filter { it.kind == AgentNotificationKind.SUBAGENT }
        val messages = items.filter { it.kind == AgentNotificationKind.AGENT_MESSAGE }
        val lines = mutableListOf<String>()
        when (tasks.size) {
            0 -> {}
            1 -> lines.add(tasks.first().singleHint())
            else -> lines.add(
                "通知已携带各终端最后 $TAIL_LINES 行输出；如需完整日志可用 terminal(action=\"read\", tab_id=\"...\") 读取对应任务。"
            )
        }
        when (subAgents.size) {
            0 -> {}
            1 -> lines.add(subAgents.first().singleHint())
            else -> lines.add("可用 task(action=\"read\", id=\"...\") 逐个读取子代理的最后输出。")
        }
        when (messages.size) {
            0 -> {}
            1 -> lines.add(messages.first().singleHint())
            else -> lines.add("你收到了多条代理消息，请按上文各自的回复方式处理；无需回复的可忽略，不要逐条回执。")
        }
        return lines.joinToString("\n")
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
