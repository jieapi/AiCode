package com.aicode.feature.agent.presentation.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAssistantStoreTest {
    private fun StreamingAssistantStore.put(
        sessionId: String,
        messageId: String = "message-$sessionId",
        content: String = "text",
        reasoning: String = "",
        settled: Boolean = true,
        persisted: Boolean = true
    ) {
        update(sessionId, messageId) {
            it.copy(content = content, reasoning = reasoning, settled = settled, persisted = false)
        }
        if (persisted) markPersisted(sessionId, messageId, content, reasoning)
    }

    @Test
    fun acknowledgementOnlyRemovesMatchingSettledMessage() {
        val store = StreamingAssistantStore()
        store.put("s", "old")
        store.put("s", "new", settled = false, persisted = false)

        store.acknowledgeRenderedAssistant("s", "old")
        assertEquals("new", store.messages.value["s"]?.messageId)
        store.acknowledgeRenderedAssistant("s", "new")
        assertEquals("new", store.messages.value["s"]?.messageId)

        store.update("s", "new") { it.copy(settled = true) }
        store.acknowledgeRenderedAssistant("s", "old")
        assertEquals("new", store.messages.value["s"]?.messageId)
        store.acknowledgeRenderedAssistant("s", "new")
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun latePersistAndAcknowledgementCannotRecreateAClearedMessage() {
        val store = StreamingAssistantStore()
        store.put("s", persisted = false)
        store.acknowledgeRenderedAssistant("s", "message-s")
        store.markPersisted("s", "message-s", "text", "")
        store.updateIfPresent("s", "message-s") { it.copy(reasoningEndMs = 100) }
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun persistenceIsTrackedByMessageIdentityInsteadOfSettled() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0, maxRetainedChars = 0)
        store.put("s", "old", persisted = false)
        assertFalse(store.messages.value.getValue("s").persisted)
        store.put("s", "new", persisted = false)
        store.markPersisted("s", "old", "text", "")
        assertFalse(store.messages.value.getValue("s").persisted)
        store.markPersisted("s", "new", "text", "")
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun unpersistedAndUnsettledMessagesDoNotConsumeOrObeyTheBudget() {
        val store = StreamingAssistantStore(maxRetainedSessions = 1, maxRetainedChars = 4)
        store.put("unpersisted", content = "x".repeat(100), persisted = false)
        store.put("streaming", content = "x".repeat(100), settled = false)
        store.put("retained")
        assertEquals(setOf("unpersisted", "streaming", "retained"), store.messages.value.keys)
    }

    @Test
    fun sessionBudgetEvictsOldestSuccessfullyPersistedMessage() {
        val store = StreamingAssistantStore(maxRetainedSessions = 2)
        store.put("first")
        store.put("second")
        store.put("first", "latest-first")
        store.put("third")
        assertEquals(setOf("first", "third"), store.messages.value.keys)
    }

    @Test
    fun persistenceCompletionDefinesOrderRatherThanArrivalOfSettledSnapshot() {
        val store = StreamingAssistantStore(maxRetainedSessions = 1)
        store.put("slow", persisted = false)
        store.put("fast")
        store.markPersisted("slow", "message-slow", "text", "")
        assertEquals(setOf("slow"), store.messages.value.keys)
    }

    @Test
    fun characterBudgetCountsTextAndReasoningAndAcceptsExactLimit() {
        val store = StreamingAssistantStore(maxRetainedSessions = 8, maxRetainedChars = 10)
        store.put("first", content = "123", reasoning = "45")
        store.put("second", content = "123", reasoning = "45")
        assertEquals(setOf("first", "second"), store.messages.value.keys)
        store.put("third", content = "1")
        assertEquals(setOf("second", "third"), store.messages.value.keys)
        store.put("oversized", content = "123456", reasoning = "12345")
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun runningSessionIsProtectedUntilItsOwnRunFinishes() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0, maxRetainedChars = 0)
        val oldRun = Any()
        val newRun = Any()
        store.startRun("s", oldRun)
        store.put("s", content = "x".repeat(100))
        store.startRun("s", newRun)
        store.finishRun("s", oldRun)
        assertTrue(store.messages.value.getValue("s").persisted)
        store.finishRun("s", newRun)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun onlyMatchingRunCanCloseReasoningTiming() {
        val store = StreamingAssistantStore()
        val oldRun = Any()
        val newRun = Any()
        store.startRun("s", oldRun)
        store.startRun("s", newRun)
        store.update("s", "new") {
            it.copy(reasoning = "thinking", settled = true, reasoningStartMs = 100)
        }
        store.finishRun("s", oldRun, finishedAt = 200)
        assertNull(store.messages.value["s"]?.reasoningEndMs)
        store.finishRun("s", newRun, finishedAt = 300)
        assertEquals(300L, store.messages.value["s"]?.reasoningEndMs)
    }

    @Test
    fun commandSessionIsProtectedUntilCommandFinishes() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        store.setCommandRunning("s", true)
        store.put("s")
        assertTrue(store.messages.value.containsKey("s"))
        store.setCommandRunning("s", false)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun everyOwnerMustReleaseASelectedSessionBeforeItCanBeEvicted() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0, maxRetainedChars = 0)
        val firstOwner = Any()
        val secondOwner = Any()
        store.setSelectedSession(firstOwner, "s")
        store.setSelectedSession(secondOwner, "s")
        store.put("s", content = "x".repeat(100))
        store.setSelectedSession(firstOwner, "other")
        assertTrue(store.messages.value.containsKey("s"))
        store.setSelectedSession(secondOwner, null)
        assertTrue(store.messages.value.isEmpty())
        store.put("other")
        assertTrue(store.messages.value.containsKey("other"))
        store.setSelectedSession(firstOwner, null)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun finishingRunDoesNotOverrideOwnerProtection() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        val owner = Any()
        val run = Any()
        store.startRun("s", run)
        store.setSelectedSession(owner, "s")
        store.put("s")
        store.finishRun("s", run)
        assertTrue(store.messages.value.containsKey("s"))
        store.setSelectedSession(owner, null)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun finishDropsStreamingButRetainsFailedPersistence() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        val run = Any()
        store.startRun("streaming", run)
        store.put("streaming", settled = false, persisted = false)
        store.startRun("failed", run)
        store.put("failed", persisted = false)
        store.finishRun("streaming", run)
        store.finishRun("failed", run)
        assertEquals(setOf("failed"), store.messages.value.keys)
    }

    @Test
    fun cancelledPartialRemainsUntilPersistenceSucceedsEvenAfterFinish() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        val run = Any()
        store.startRun("s", run)
        store.update("s", "partial") {
            it.copy(content = "text  ", reasoning = "thinking", reasoningStartMs = 100)
        }
        val stopped = store.settlePartial("s", "stopped", 250, hasRunningTools = false)!!
        assertEquals("text\n\nstopped", stopped.content)
        assertEquals("thinking", stopped.reasoning)
        assertEquals(250L, stopped.reasoningEndMs)
        assertTrue(stopped.settled)
        assertFalse(stopped.persisted)
        store.finishRun("s", run)
        assertEquals(stopped, store.messages.value["s"])
        store.markPersisted("s", stopped.messageId, stopped.content, stopped.reasoning)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun persistenceOfOriginalTextCannotMarkCancelledPartialWithSameIdentity() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        store.put("s", "partial", content = "text", settled = false, persisted = false)
        val stopped = store.settlePartial("s", "stopped", 250, hasRunningTools = false)!!
        store.markPersisted("s", "partial", "text", "")
        assertEquals(stopped, store.messages.value["s"])
        assertFalse(store.messages.value.getValue("s").persisted)
        store.markPersisted("s", stopped.messageId, stopped.content, stopped.reasoning)
        assertTrue(store.messages.value.isEmpty())
    }

    @Test
    fun cancelledPartialPersistenceCannotMarkTheNextResponsePersisted() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        store.put("s", "partial", settled = false, persisted = false)
        val stopped = store.settlePartial("s", "stopped", 250, hasRunningTools = false)!!
        store.put("s", "next", persisted = false)
        store.markPersisted("s", stopped.messageId, stopped.content, stopped.reasoning)
        store.acknowledgeRenderedAssistant("s", stopped.messageId)
        assertFalse(store.messages.value.getValue("s").persisted)
        assertEquals("next", store.messages.value.getValue("s").messageId)
    }

    @Test
    fun cancellationPreservesReasoningOnlyButDropsEmptyOrToolStreaming() {
        val store = StreamingAssistantStore()
        store.put("reasoning", content = "", reasoning = "thinking", settled = false, persisted = false)
        val stopped = store.settlePartial("reasoning", "stopped", 250, hasRunningTools = false)!!
        assertEquals("stopped", stopped.content)
        assertEquals("thinking", stopped.reasoning)
        store.put("empty", content = "", settled = false, persisted = false)
        assertNull(store.settlePartial("empty", "stopped", 250, hasRunningTools = false))
        store.put("tool", settled = false, persisted = false)
        assertNull(store.settlePartial("tool", "stopped", 250, hasRunningTools = true))
        assertEquals(setOf("reasoning"), store.messages.value.keys)
        assertNull(store.settlePartial("reasoning", "stopped", 300, hasRunningTools = false))
        assertEquals(stopped, store.messages.value["reasoning"])
    }

    @Test
    fun clearingSessionInvalidatesItsRunWithoutDroppingOwnerRegistration() {
        val store = StreamingAssistantStore(maxRetainedSessions = 0)
        val owner = Any()
        val oldRun = Any()
        val newRun = Any()
        store.setSelectedSession(owner, "s")
        store.startRun("s", oldRun)
        store.put("s")
        store.clearSession("s")
        store.startRun("s", newRun)
        store.put("s", "new")
        store.finishRun("s", oldRun)
        store.finishRun("s", newRun)
        assertEquals("new", store.messages.value["s"]?.messageId)
        store.setSelectedSession(owner, null)
        assertTrue(store.messages.value.isEmpty())
    }
}
