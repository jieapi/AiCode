package com.aicode.feature.agent.domain.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatRequestBusTest {

    @Test
    fun submitWithoutConsumerIsRejected() {
        val bus = AgentChatRequestBus()

        assertFalse(bus.submit(AgentChatRequest.Stop("session-1")))
    }

    @Test
    fun submitWithConsumerIsReceived() = runTest {
        val bus = AgentChatRequestBus().apply { attach("view-model") }
        val request = AgentChatRequest.Stop("session-1")

        assertTrue(bus.submit(request))
        assertEquals(request, bus.receive())
    }

    @Test
    fun detachRemovesConsumerAndRejectsNewRequests() {
        val bus = AgentChatRequestBus()
        bus.attach("view-model")
        bus.submit(AgentChatRequest.Stop("session-1"))

        bus.detach("view-model")

        assertFalse(bus.isAttached)
        assertFalse(bus.submit(AgentChatRequest.Stop("session-2")))
    }
}
