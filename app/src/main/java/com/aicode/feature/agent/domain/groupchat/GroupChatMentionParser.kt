package com.aicode.feature.agent.domain.groupchat

/**
 * 群聊协调的纯函数：@mention 解析、轮次发言人选择、轮转、hold 指令、pass 判定。
 * 语义照搬 Hermes Bot Mode group-rounds.ts，全部无副作用，可单测。
 */
object GroupChatMentionParser {

    const val MAX_ROUNDS = 3
    const val MAX_MESSAGES = 10
    const val MAX_CONTINUATIONS = 2
    const val MAX_MEMBERS = 6
    const val HISTORY_LIMIT = 24

    /** @mention 解析结果。 */
    data class MentionResult(
        val everyone: Boolean,
        /** 命中的成员 memberKey 集合。 */
        val mentioned: Set<String>
    )

    /** 单个成员可被 @ 到的名字形式（大小写不敏感，含折叠空格/连字符/下划线形式）。 */
    fun mentionForms(member: GroupChatMember): Set<String> {
        val title = member.title.trim()
        val handle = member.handle.trim().ifEmpty { member.memberKey }
        return buildSet {
            add(member.memberKey.lowercase())
            add(member.memberKey.lowercase().replace(Regex("[\\s_-]+"), ""))
            add(handle.lowercase())
            add(handle.lowercase().replace(Regex("[\\s_-]+"), ""))
            if (title.isNotEmpty()) {
                add(title.lowercase())
                add(title.lowercase().replace(Regex("[\\s_-]+"), ""))
                add(title.split(Regex("\\s+")).first().lowercase())
            }
        }
    }

    /** 文本是否 @ 了用户（@user，大小写不敏感；请求人类介入）。 */
    fun mentionsUser(text: String): Boolean =
        Regex("@(?:user|human|you)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * 解析文本中的 @mention。支持 @name、@handle、@title（多词）、@everyone/@all。
     * 匹配大小写不敏感；@user 跳过（@user 是请求人类介入，不是成员）。
     */
    fun parseMentions(text: String, members: List<GroupChatMember>): MentionResult {
        val forms = mutableMapOf<String, String>()
        for (member in members) {
            for (form in mentionForms(member)) {
                forms[form] = member.memberKey
            }
        }
        val mentioned = mutableSetOf<String>()
        var everyone = false
        for (match in Regex("@([a-z0-9][a-z0-9._-]*)", RegexOption.IGNORE_CASE).findAll(text)) {
            val handle = match.groupValues[1].lowercase()
            when (handle) {
                "everyone", "all" -> everyone = true
                "user" -> Unit
                else -> {
                    forms[handle]?.let { mentioned.add(it) }
                        ?: forms[handle.replace(Regex("[._-]+"), "")]?.let { mentioned.add(it) }
                }
            }
        }
        return MentionResult(everyone, mentioned)
    }

    /**
     * 本轮应该发言的成员：最近一条用户消息之后被 @ 的成员（@everyone 或无人被 @ 时全员）。
     * 每次轮次重算，中途被拉进来的成员下一轮加入。
     */
    fun resolveResponders(
        log: List<GroupChatMessage>,
        members: List<GroupChatMember>
    ): List<GroupChatMember> {
        var sinceLastUser = emptyList<GroupChatMessage>()
        for (i in log.indices.reversed()) {
            if (log[i].kind == "user" && log[i].sender == "You") {
                sinceLastUser = log.subList(i, log.size)
                break
            }
        }
        val mentioned = mutableSetOf<String>()
        var everyone = false
        for (entry in sinceLastUser) {
            val parsed = parseMentions(entry.text, members)
            if (parsed.everyone) everyone = true
            mentioned.addAll(parsed.mentioned)
        }
        return if (everyone || mentioned.isEmpty()) members
        else members.filter { it.memberKey in mentioned }
    }

    /** 轮转：每轮从不同成员开始，避免固定顺序偏袒。 */
    fun rotateSpeakers(members: List<GroupChatMember>, round: Int): List<GroupChatMember> {
        if (members.size < 2) return members
        val shift = round % members.size
        return members.subList(shift, members.size) + members.subList(0, shift)
    }

    /** "(pass)"（宽松：pass / (pass) / pass.）或空 = 该成员保持沉默。 */
    fun isPassText(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return Regex("^\\(?\\s*pass\\s*\\)?\\.?$", RegexOption.IGNORE_CASE).matches(trimmed)
    }

    /** hold 指令分类：只有用户消息会触发（成员回复文本不会改 holds）。 */
    data class HoldDirective(
        val hold: Set<String> = emptySet(),
        val holdAll: Boolean = false,
        val release: Set<String> = emptySet(),
        val releaseAll: Boolean = false
    )

    /**
     * 解析用户消息对成员 hold 的影响：
     * - stop/halt/pause 命中 → @ 到的成员被 hold（@all stop → 全员）
     * - resume/continue/go/proceed 命中 → @ 到的成员被释放（@all resume → 全员）
     * - 其它直接 @ → 释放被 @ 的成员（用户点名即解除暂停）
     */
    fun classifyHoldDirective(text: String, mentioned: Set<String>, everyone: Boolean): HoldDirective {
        val stop = Regex("\\b(stop|halt|pause)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val resume = Regex("\\b(resume|continue|go|proceed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        return when {
            stop -> HoldDirective(hold = mentioned, holdAll = everyone)
            resume -> HoldDirective(release = mentioned, releaseAll = everyone)
            else -> HoldDirective(release = mentioned)
        }
    }

    /**
     * 应用 hold 指令后的新 holds 映射。hold 以 memberKey 为键（房间级，非线程级）。
     * 返回与原对象相同时（无变化）返回原引用，便于调用方判断。
     */
    fun applyHoldDirective(
        holds: Map<String, Long>,
        directive: HoldDirective,
        allMemberKeys: List<String>,
        at: Long = System.currentTimeMillis()
    ): Map<String, Long> {
        if (directive.releaseAll) return if (holds.isEmpty()) holds else emptyMap()
        var next: Map<String, Long> = holds
        val toHold = if (directive.holdAll) allMemberKeys else directive.hold.toList()
        for (key in toHold) {
            if (next === holds) next = holds.toMutableMap()
            next = next + (key to at)
        }
        for (key in directive.release) {
            if (next.containsKey(key)) {
                if (next === holds) next = holds.toMutableMap()
                next = next - key
            }
        }
        return next
    }

    /** 被 @ 但尚未发言的成员（未解决 handoff 检测，供 continuation 轮使用）。 */
    fun unaddressedMentions(
        log: List<GroupChatMessage>,
        members: List<GroupChatMember>
    ): Set<String> {
        val citedAt = mutableMapOf<String, Int>()
        val lastPostAt = mutableMapOf<String, Int>()
        for ((index, entry) in log.withIndex()) {
            if (entry.kind != "member") continue
            val speaker = entry.sender
            lastPostAt[speaker] = index
            val parsed = parseMentions(entry.text, members)
            for (key in parsed.mentioned) {
                if (key != speaker) citedAt[key] = index
            }
        }
        return citedAt.filter { (key, citedIdx) ->
            val answered = lastPostAt[key]
            answered == null || answered <= citedIdx
        }.keys
    }
}
