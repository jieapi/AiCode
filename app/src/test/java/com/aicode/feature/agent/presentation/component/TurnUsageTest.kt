package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮任务的 token 合计口径（[computeTurnUsage]）：**1 轮 n 步求和**，只挂在轮末那条助手消息上。
 */
class TurnUsageTest {

    private fun user(id: String, ts: Long, marker: Boolean = false) = AgentUIMessage(
        id = id,
        role = MessageRole.USER,
        content = "hi",
        timestamp = ts,
        isCompactionMarker = marker
    )

    private fun assistant(
        id: String,
        ts: Long,
        input: Int = 0,
        output: Int = 0,
        cached: Int = 0,
        generationWindow: Int? = null,
        summary: Boolean = false
    ) = AgentUIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "ok",
        timestamp = ts,
        inputTokens = input,
        outputTokens = output,
        cachedInputTokens = cached,
        generationWindowMillis = generationWindow,
        isContextSummary = summary
    )

    private fun tool(id: String, ts: Long) = AgentUIMessage(
        id = id,
        role = MessageRole.TOOL,
        content = "result",
        timestamp = ts,
        toolName = "Bash"
    )

    @Test
    fun sums_every_step_of_the_turn() {
        // 一轮三步（assistant → tool → assistant → tool → assistant）：用量是三条之和
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, input = 1_000, output = 100, cached = 400),
            tool("t1", 3_000),
            assistant("a2", 4_000, input = 2_000, output = 200, cached = 900),
            tool("t2", 5_000),
            assistant("a3", 6_000, input = 3_000, output = 300, cached = 1_500)
        )
        val usage = computeTurnUsage(messages, lastTurnFinished = true)
        assertEquals(1, usage.size)
        // 中间步骤不单独挂统计
        assertNull(usage["a1"])
        assertNull(usage["a2"])
        val total = usage.getValue("a3")
        assertEquals(6_000, total.inputTokens)
        assertEquals(600, total.outputTokens)
        assertEquals(2_800, total.cachedInputTokens)
    }

    @Test
    fun outputRateUsesWeightedGenerationWindow() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, output = 100, generationWindow = 1_000),
            tool("t1", 3_000),
            assistant("a2", 4_000, output = 300, generationWindow = 6_000)
        )

        val total = computeTurnUsage(messages, lastTurnFinished = true).getValue("a2")

        assertEquals(400, total.outputTokens)
        assertEquals(57, total.outputTokensPerSecond)
    }

    @Test
    fun outputRateIsHiddenIfAnyOutputCallHasMissingTiming() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, output = 100, generationWindow = 1_000),
            tool("t1", 3_000),
            assistant("a2", 4_000, output = 300, generationWindow = null)
        )

        val total = computeTurnUsage(messages, lastTurnFinished = true).getValue("a2")

        assertEquals(400, total.outputTokens)
        assertNull(total.outputTokensPerSecond)
    }

    @Test
    fun outputRateIsUnavailableWhenNoCallHasPositiveOutputAndGenerationWindow() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, output = 50, generationWindow = 0)
        )

        assertNull(computeTurnUsage(messages, lastTurnFinished = true).getValue("a1").outputTokensPerSecond)
    }

    @Test
    fun each_turn_has_its_own_total() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, input = 100, output = 10),
            user("u2", 3_000),
            assistant("a2", 4_000, input = 500, output = 50),
            tool("t1", 5_000),
            assistant("a3", 6_000, input = 700, output = 70)
        )
        val usage = computeTurnUsage(messages, lastTurnFinished = true)
        assertEquals(100, usage.getValue("a1").inputTokens)
        assertEquals(1_200, usage.getValue("a3").inputTokens)
        assertEquals(120, usage.getValue("a3").outputTokens)
    }

    @Test
    fun unfinished_turn_has_no_total() {
        val messages = listOf(user("u1", 1_000), assistant("a1", 2_000, input = 100))
        assertTrue(computeTurnUsage(messages, lastTurnFinished = false).isEmpty())
    }

    @Test
    fun compaction_summary_does_not_count_into_the_turn() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, input = 100, output = 10),
            user("marker", 3_000, marker = true),
            assistant("summary", 3_001, input = 5_000, output = 500, summary = true),
            assistant("a2", 9_000, input = 200, output = 20)
        )
        val usage = computeTurnUsage(messages, lastTurnFinished = true)
        assertNull(usage["summary"])
        val total = usage.getValue("a2")
        assertEquals(300, total.inputTokens)
        assertEquals(30, total.outputTokens)
    }

    @Test
    fun assistant_without_a_preceding_user_message_has_no_total() {
        val messages = listOf(assistant("a1", 2_000, input = 100))
        assertTrue(computeTurnUsage(messages, lastTurnFinished = true).isEmpty())
    }
}
