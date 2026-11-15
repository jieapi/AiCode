package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.lazy.LazyListState
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.StreamingAssistantMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAssistantHandoffTest {
    private val user = AgentUIMessage("u1", MessageRole.USER, "request")
    private val stream = StreamingAssistantMessage("s1", "a1", reasoning = "thinking")

    private fun render(messages: List<AgentUIMessage>) = flattenChatProcesses(buildChatItems(
        messages, emptyMap(), activeTurnKey = "turn:u1",
    ))

    @Test
    fun finalSnapshotRemainsUntilItsMessageIsInTheRenderedList() {
        val settled = stream.copy(content = "checking", settled = true)
        val pending = reconcileStreamingAssistants(listOf(stream), settled, listOf(user), "s1")
        assertEquals(listOf(settled), pending)

        val assistant = AgentUIMessage("a1", MessageRole.ASSISTANT, "checking", reasoning = "thinking")
        val tool = AgentUIMessage("t1", MessageRole.TOOL, "running")
        assertTrue(reconcileStreamingAssistants(pending, settled, listOf(user, assistant, tool), "s1").isEmpty())
    }

    @Test
    fun identicalContentInAnOlderMessageDoesNotConsumeTheStream() {
        val previous = AgentUIMessage("older", MessageRole.ASSISTANT, "", reasoning = "thinking")
        assertEquals(listOf(stream), reconcileStreamingAssistants(emptyList(), stream, listOf(previous, user), "s1"))
    }

    @Test
    fun nextResponseKeepsAnUncommittedPreviousResponseInOrder() {
        val next = stream.copy(messageId = "a2", reasoning = "next")
        val pending = reconcileStreamingAssistants(listOf(stream), next, listOf(user), "s1")
        assertEquals(listOf("a1", "a2"), pending.map { it.messageId })
        assertTrue(pending.first().settled)
        assertFalse(pending.last().settled)

        val assistant = AgentUIMessage("a1", MessageRole.ASSISTANT, "", reasoning = "thinking")
        assertEquals(listOf(next), reconcileStreamingAssistants(pending, next, listOf(user, assistant), "s1"))
    }

    @Test
    fun clearingOrSwitchingSessionDropsPendingResponses() {
        val pending = listOf(stream.copy(settled = true))
        assertTrue(reconcileStreamingAssistants(pending, null, listOf(user), "s1").isEmpty())
        assertTrue(reconcileStreamingAssistants(pending, stream, emptyList(), "s2").isEmpty())
    }

    @Test
    fun reasoningKeepsItsItemIdentityWhenToolStartsAndWhenTurnFinishes() {
        val assistant = AgentUIMessage("a1", MessageRole.ASSISTANT, "checking", reasoning = "thinking")
        val tool = AgentUIMessage("t1", MessageRole.TOOL, "running")
        val running = render(listOf(user, assistant, tool))
        val reasoning = running.single { it.key == "a1#reasoning" }
        assertEquals("reasoning", reasoning.contentType)
        assertFalse(running.single { it.key == "a1" }.reasoningVisible)
        assertTrue(running.indexOf(reasoning) < running.indexOfFirst { it.key == "t1" })

        val finished = flattenChatProcesses(buildChatItems(
            listOf(user, assistant), emptyMap(), turnOverrides = mapOf("turn:u1" to true),
        ))
        assertEquals(reasoning.key, finished.single { it.contentType == "reasoning" }.key)
        assertEquals(running.size, running.map { it.key }.toSet().size)
    }

    @Test
    fun longResponseKeepsTheStreamingTextItemKeyForItsFirstChunk() {
        val assistant = AgentUIMessage(
            "a1", MessageRole.ASSISTANT,
            (1..100).joinToString("\n\n") { "paragraph $it " + "content ".repeat(20) },
            reasoning = "thinking",
        )
        val rendered = render(listOf(user, assistant))
        val chunks = rendered.filter { it.contentType == "assistant-chunk" }
        assertTrue(chunks.size > 1)
        assertEquals(stream.messageId, chunks.first().key)
        assertEquals("a1#reasoning", rendered.single { it.contentType == "reasoning" }.key)
        assertTrue(chunks.none { it.reasoningVisible })
    }

    @Test
    fun reasoningScrollStateIsSharedAcrossStreamingAndPersistedIds() {
        val states = mutableMapOf<String, LazyListState>()
        val streamingState = states.getOrPut(stream.messageId) { LazyListState(7, 42) }
        val persisted = AgentUIMessage("a1", MessageRole.ASSISTANT, "", reasoning = "thinking")
        val persistedState = states.getOrPut(persisted.id) { LazyListState() }
        assertSame(streamingState, persistedState)
        assertEquals(7, persistedState.firstVisibleItemIndex)
        assertEquals(42, persistedState.firstVisibleItemScrollOffset)
    }
}
