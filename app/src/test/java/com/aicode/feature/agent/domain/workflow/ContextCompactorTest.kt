package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextCompactorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val metadata = mockk<ModelMetadataService>()
    private val prompts = mockk<SystemPromptProvider>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    private val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence)

    private fun prepare(context: Int = 16_000) {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } returns 8_000
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "summary", contextTokens = context)
        coEvery { settings.compactionThresholdPercent() } returns 90
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
    }

    private fun history(size: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue the task")
    )

    @Test
    fun projectionUsesModelResultAndPreservesBothEnds() {
        val message = AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END")
        val text = CompactionText.project(message)
        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        assertFalse(text.contains("not sent to model"))
        assertEquals(3_008, message.modelResult!!.length)
    }

    @Test
    fun projectionRemovesMediaAndProtocolThinkingForEveryRole() {
        val image = AgentImage("image/png", "secret-media")
        val messages = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(content = "answer", reasoning = "private-thinking", signature = "signature",
                thinkingBlocksJson = "snapshot", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "image", result = "{\"images\":[{\"base64Data\":\"secret-media\"}],\"value\":\"kept\"}", images = listOf(image))
        )
        val text = messages.joinToString { CompactionText.project(it) }
        assertFalse(text.contains("secret-media"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("private-thinking"))
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("snapshot"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun splitKeepsAllToolResultsWithAssistant() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("one", "read", emptyMap()), ToolCall("two", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one"),
            AgentMessage.ToolResultMessage(id = "two", toolName = "read", result = "two")
        )
        assertEquals(1, CompactionText.adjustSplitIndex(messages, 3))
        assertEquals(2, CompactionText.units(messages).size)
    }

    @Test
    fun splitDoesNotSeparatePreviousSummaryPair() {
        val messages = listOf(
            AgentMessage.UserMessage(content = com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(content = "summary"),
            AgentMessage.UserMessage(content = "continue")
        )
        assertEquals(0, CompactionText.adjustSplitIndex(messages, 1))
    }

    @Test
    fun cursorNeverDiscardsOversizedOrLeadingAssistantMaterial() {
        val source = "汉".repeat(1_000)
        val cursor = CompactionText.Cursor(listOf(source, "LAST"))
        val chunks = mutableListOf<String>()
        repeat(32) { if (!cursor.finished) chunks.add(cursor.next(100)) }
        assertTrue(cursor.finished)
        assertEquals(1_000, chunks.sumOf { chunk -> chunk.count { it == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
        assertTrue(CompactionText.units(listOf(AgentMessage.AssistantMessage(content = "leading"))).single().contains("leading"))
    }

    @Test
    fun cursorIncrementalBudgetMatchesWholeTextCounting() {
        val units = List(120) { index -> if (index % 2 == 0) "ascii-$index" else "混合-$index" }
        val cursor = CompactionText.Cursor(units)
        val chunks = mutableListOf<String>()
        while (!cursor.finished) chunks.add(cursor.next(160))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 160 })
        assertEquals(units.size, chunks.sumOf { chunk -> Regex("\\[history-unit ").findAll(chunk).count() })
        units.forEach { unit -> assertTrue(chunks.any { it.contains("\n$unit\n") }) }
    }

    @Test
    fun multipleBlocksFusePreviousSummaryAndRetainLatestGoal() = runTest {
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        coEvery { provider.complete(any(), capture(requests), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(requests.size > 1)
        assertTrue((requests[1].single() as AgentMessage.UserMessage).content.contains("<previous-summary>\nConcise handoff"))
        assertTrue(requests.flatten().all { it is AgentMessage.UserMessage })
        assertSame(original.last(), result.messages.last())
        coVerify { provider.maxOutputTokens = 8_000 }
        coVerify(exactly = requests.size) { records.insert(any()) }
    }

    @Test
    fun currentUsageTriggersBeforeSending() = runTest {
        prepare()
        val original = history(100)
        assertFalse(compactor.compactIfNeeded(original, provider).compacted)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        val events = mutableListOf<AgentEvent>()
        compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999, onEvent = { events.add(it) })
        assertTrue(events.any { it is AgentEvent.CompactionStarted })
    }

    @Test
    fun invalidResponsesDoNotCommitAndCountFailedCalls() = runTest {
        prepare()
        for (response in listOf(AIResponse(""), AIResponse("partial", stopReason = "length"), AIResponse("blocked", stopReason = "refusal"))) {
            coEvery { provider.complete(any(), any(), any(), any()) } returns response
            val original = history()
            val result = compactor.compactIfNeeded(original, provider, force = true)
            assertFalse(result.compacted)
            assertSame(original, result.messages)
        }
        coVerify(exactly = 3) { records.insert(match { it.status == "error" }) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun blockLimitReturnsOriginalWithoutCommitting() = runTest {
        prepare(2_000)
        val original = history(100_000)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(atMost = 32) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun missingPersistedAnchorFailsBeforeSummaryCall() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns emptyList()
        val original = history()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true).messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun commitUsesHeadIdsAndCompactionTriggerTimestamp() = runTest {
        prepare()
        every { persistence.nextTimestamp() } returnsMany listOf(300L, 301L)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 200),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        val result = compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true)
        assertTrue(result.compacted)
        coVerify(exactly = 1) {
            dao.commitCompaction("session", listOf("old"), match { it.map { row -> row.timestamp } == listOf(300L, 301L) }, any())
        }
        coVerify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test
    fun adjustedEmptyHeadFailsWithoutCallingModel() = runTest {
        prepare()
        val original = listOf(
            AgentMessage.AssistantMessage(id = "assistant", content = "", toolCalls = listOf(ToolCall("call", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "result")
        )
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun mainSystemBudgetIsIncludedInFinalValidation() = runTest {
        prepare()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true, systemPrompt = "汉".repeat(20_000))
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }

    @Test
    fun transactionFailureReturnsOriginal() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun cancellationPropagatesAndRecordsFailure() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            compactor.compactIfNeeded(history(), provider, force = true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
            coVerify { provider.maxOutputTokens = 8_000 }
        }
    }

    @Test
    fun oversizedTailFailsWithoutReplacingHistory() = runTest {
        prepare()
        val original = history() + AgentMessage.UserMessage(id = "huge-tail", content = "汉".repeat(30_000))
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }
}
