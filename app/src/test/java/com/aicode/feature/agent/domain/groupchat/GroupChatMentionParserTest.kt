package com.aicode.feature.agent.domain.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatMentionParserTest {

    private val members = listOf(
        GroupChatMember(memberKey = "coder", title = "Builder", handle = "builder"),
        GroupChatMember(memberKey = "reviewer", title = "", handle = ""),
        GroupChatMember(memberKey = "publisher", title = "Release Manager", handle = "rel-mgr")
    )

    // ── parseMentions ─────────────────────────────────────────────────────────

    @Test
    fun `mention by profile name case-insensitive`() {
        val r = GroupChatMentionParser.parseMentions("@Coder 看看这个", members)
        assertFalse(r.everyone)
        assertEquals(setOf("coder"), r.mentioned)
    }

    @Test
    fun `mention by handle and title forms`() {
        val r = GroupChatMentionParser.parseMentions("交给 @builder 和 @Release 处理", members)
        assertEquals(setOf("coder", "publisher"), r.mentioned)
    }

    @Test
    fun `mention everyone`() {
        val r = GroupChatMentionParser.parseMentions("@everyone 开会", members)
        assertTrue(r.everyone)
        assertTrue(r.mentioned.isEmpty())
    }

    @Test
    fun `at user is skipped`() {
        val r = GroupChatMentionParser.parseMentions("需要你判断 @user", members)
        assertFalse(r.everyone)
        assertTrue(r.mentioned.isEmpty())
    }

    @Test
    fun `collapsed no-space form matches`() {
        val r = GroupChatMentionParser.parseMentions("@relmgr 发版", members)
        assertEquals(setOf("publisher"), r.mentioned)
    }

    @Test
    fun `unknown mention ignored`() {
        val r = GroupChatMentionParser.parseMentions("@nobody 你好", members)
        assertTrue(r.mentioned.isEmpty())
    }

    // ── resolveResponders ─────────────────────────────────────────────────────

    private fun log(vararg texts: String): List<GroupChatMessage> =
        texts.mapIndexed { i, t ->
            GroupChatMessage(id = "m$i", kind = "user", sender = "You", text = t, at = i.toLong())
        }

    @Test
    fun `responders all when no mention`() {
        val l = log("大家好")
        assertEquals(members, GroupChatMentionParser.resolveResponders(l, members))
    }

    @Test
    fun `responders only mentioned`() {
        val l = log("@reviewer 你来审")
        assertEquals(listOf(members[1]), GroupChatMentionParser.resolveResponders(l, members))
    }

    @Test
    fun `responders recompute after member reply mentions someone`() {
        val l = log(
            "@reviewer 审这个",
            "reviewer: 我看看，@publisher 你确认下发布",
            "publisher: 收到"
        ).mapIndexed { i, m ->
            when (i) {
                0 -> m
                1 -> m.copy(kind = "member", sender = "reviewer")
                else -> m.copy(kind = "member", sender = "publisher")
            }
        }
        // 最近用户消息之后的所有 mention（含成员回复里的）都计入
        assertEquals(
            setOf("reviewer", "publisher"),
            GroupChatMentionParser.resolveResponders(l, members).map { it.memberKey }.toSet()
        )
    }

    // ── rotateSpeakers ────────────────────────────────────────────────────────

    @Test
    fun `rotate shifts start each round`() {
        val r0 = GroupChatMentionParser.rotateSpeakers(members, 0).map { it.memberKey }
        val r1 = GroupChatMentionParser.rotateSpeakers(members, 1).map { it.memberKey }
        assertEquals(listOf("coder", "reviewer", "publisher"), r0)
        assertEquals(listOf("reviewer", "publisher", "coder"), r1)
    }

    @Test
    fun `rotate single member no-op`() {
        val one = listOf(members[0])
        assertEquals(one, GroupChatMentionParser.rotateSpeakers(one, 5))
    }

    // ── isPassText ────────────────────────────────────────────────────────────

    @Test
    fun `pass variants`() {
        assertTrue(GroupChatMentionParser.isPassText(null))
        assertTrue(GroupChatMentionParser.isPassText(""))
        assertTrue(GroupChatMentionParser.isPassText("pass"))
        assertTrue(GroupChatMentionParser.isPassText("(pass)"))
        assertTrue(GroupChatMentionParser.isPassText("Pass."))
    }

    @Test
    fun `not pass`() {
        assertFalse(GroupChatMentionParser.isPassText("让我看看"))
        assertFalse(GroupChatMentionParser.isPassText("passed the test"))
    }

    // ── hold 指令 ─────────────────────────────────────────────────────────────

    @Test
    fun `stop holds mentioned`() {
        val d = GroupChatMentionParser.classifyHoldDirective(
            "@coder stop", setOf("coder"), everyone = false
        )
        assertEquals(setOf("coder"), d.hold)
        assertFalse(d.holdAll)
    }

    @Test
    fun `all stop holds everyone`() {
        val d = GroupChatMentionParser.classifyHoldDirective(
            "@all stop", emptySet(), everyone = true
        )
        assertTrue(d.holdAll)
    }

    @Test
    fun `resume releases`() {
        val d = GroupChatMentionParser.classifyHoldDirective(
            "@coder resume", setOf("coder"), everyone = false
        )
        assertEquals(setOf("coder"), d.release)
    }

    @Test
    fun `plain mention releases without stop keyword`() {
        val d = GroupChatMentionParser.classifyHoldDirective(
            "@coder 继续分析", setOf("coder"), everyone = false
        )
        assertEquals(setOf("coder"), d.release)
        assertTrue(d.hold.isEmpty())
    }

    @Test
    fun `apply hold directive`() {
        val holds = emptyMap<String, Long>()
        val next = GroupChatMentionParser.applyHoldDirective(
            holds,
            GroupChatMentionParser.HoldDirective(hold = setOf("coder")),
            members.map { it.memberKey },
            at = 100L
        )
        assertEquals(100L, next["coder"])
    }

    @Test
    fun `apply release clears hold`() {
        val holds = mapOf("coder" to 100L, "reviewer" to 200L)
        val next = GroupChatMentionParser.applyHoldDirective(
            holds,
            GroupChatMentionParser.HoldDirective(release = setOf("coder")),
            members.map { it.memberKey }
        )
        assertFalse(next.containsKey("coder"))
        assertTrue(next.containsKey("reviewer"))
    }

    @Test
    fun `apply release all clears everything`() {
        val holds = mapOf("coder" to 100L, "reviewer" to 200L)
        val next = GroupChatMentionParser.applyHoldDirective(
            holds,
            GroupChatMentionParser.HoldDirective(releaseAll = true),
            members.map { it.memberKey }
        )
        assertTrue(next.isEmpty())
    }

    // ── unaddressedMentions ───────────────────────────────────────────────────

    @Test
    fun `cited member with no post is pending`() {
        val l = listOf(
            GroupChatMessage("m1", "user", "You", "@reviewer 你来"),
            GroupChatMessage("m2", "member", "reviewer", "好，@publisher 交给你")
        )
        val pending = GroupChatMentionParser.unaddressedMentions(l, members)
        assertEquals(setOf("publisher"), pending)
    }

    @Test
    fun `cited member who answered is not pending`() {
        val l = listOf(
            GroupChatMessage("m1", "user", "You", "@reviewer 你来"),
            GroupChatMessage("m2", "member", "reviewer", "好，我来"),
            GroupChatMessage("m3", "member", "reviewer", "完成")
        )
        assertTrue(GroupChatMentionParser.unaddressedMentions(l, members).isEmpty())
    }

    @Test
    fun `self citation not pending`() {
        val l = listOf(
            GroupChatMessage("m1", "member", "coder", "@coder 我自己处理")
        )
        assertTrue(GroupChatMentionParser.unaddressedMentions(l, members).isEmpty())
    }

    // ── mentionsUser ─────────────────────────────────────────────────────────

    @Test
    fun `detects user mention case-insensitive`() {
        assertTrue(GroupChatMentionParser.mentionsUser("@user 需要你介入"))
        assertTrue(GroupChatMentionParser.mentionsUser("请看 @USER"))
        assertTrue(GroupChatMentionParser.mentionsUser("请 @human 决定"))
        assertTrue(GroupChatMentionParser.mentionsUser("@you 你来"))
    }
}