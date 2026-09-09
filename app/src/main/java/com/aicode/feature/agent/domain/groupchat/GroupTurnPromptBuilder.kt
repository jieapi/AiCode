package com.aicode.feature.agent.domain.groupchat

import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.HISTORY_LIMIT

/**
 * 构造群聊成员的 turn prompt：参与规则 + 自该成员上次发言以来的新增消息（delta）。
 * 语义照搬 Hermes Bot Mode buildGroupChatTurnPrompt。
 */
object GroupTurnPromptBuilder {

    /** 房间 log 行，按成员视角格式化（自己说的标 (you)）。 */
    fun formatLine(entry: GroupChatMessage, viewerKey: String): String {
        val sender = entry.sender
        val suffix = if (entry.kind == "member" && entry.sender == viewerKey) " (you)" else ""
        return if (entry.kind == "user") {
            "$sender (user): ${entry.text}"
        } else {
            "${entry.sender}$suffix: ${entry.text}"
        }
    }

    /**
     * 完整成员 turn prompt。
     *
     * @param groupName 房间名
     * @param members 全部成员
     * @param viewer 当前要发言的成员
     * @param delta 该成员 watermark 之后的新增消息（限定 thread）
     * @param privateDms 该成员 Bot Chat 私信会话中待处理的消息（成员间 DM，最旧在前）
     */
    fun buildMemberTurnPrompt(
        groupName: String,
        members: List<GroupChatMember>,
        viewer: GroupChatMember,
        delta: List<GroupChatMessage>,
        privateDms: List<GroupChatMessage> = emptyList()
    ): String {
        val viewerKey = viewer.memberKey
        val peers = members.filter { it.memberKey != viewerKey }
        val peerNames = peers.joinToString(", ") { peer ->
            val handle = if (peer.title.isNotEmpty()) "${peer.title} (@${peer.handle.ifEmpty { peer.memberKey }})"
            else "@${peer.handle.ifEmpty { peer.memberKey }}"
            handle
        }
        val deltaLines = delta
            .takeLast(HISTORY_LIMIT)
            .joinToString("\n") { "  ${formatLine(it, viewerKey)}" }

        return buildString {
            appendLine("[Group chat: \"$groupName\"] You are @${viewer.handle.ifEmpty { viewer.memberKey }}, one participant in a group chat with ${peerNames.ifEmpty { "no one else yet" }} and the user.")
            appendLine()
            appendLine("New messages in the room since your last turn (oldest first):")
            appendLine(deltaLines.ifEmpty { "  (no new messages)" })
            appendLine()
            appendLine("Rules for this room:")
            appendLine("- Reply with ONE conversational message ONLY if you have something new worth adding: build on what was just said, claim or hand off work, answer a question aimed at you, or report a real result. Keep chatter short (1-3 sentences) — but when you are delivering a result, an answer the user asked for, or substantive work, give it at full quality and length.")
            appendLine("- If you have nothing new to add, reply with exactly \"(pass)\". Passing is good — it lets the conversation settle.")
            appendLine("- Mention a teammate as @name to pull them in; mention @user only for a judgment call or a result the user needs. Do not repeat points already made.")
            appendLine("- Never reveal content from your private 1:1 chats. Your reply text goes to the room verbatim — no preamble, no meta-commentary.")
            if (privateDms.isNotEmpty()) {
                appendLine()
                appendLine("Private messages for you (1:1 chat with a teammate, oldest first):")
                privateDms.forEach { line ->
                    appendLine("  ${formatLine(line, viewerKey)}")
                }
                appendLine("Handle anything addressed to you: answer, act, or report back in the room. Ignore what is not yours.")
            }
        }
    }
}
