package com.aicode.feature.agent.domain.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunStateStoreTest {

    @Test
    fun startedAndFinishedUpdateSnapshot() {
        val store = AgentRunStateStore()

        store.started("session-1")
        val running = store.snapshot("session-1")
        assertNotNull(running)
        assertTrue(running!!.running)
        assertEquals("running", running.status)

        store.finished("session-1", "success", null)
        val idle = store.snapshot("session-1")
        assertFalse(idle!!.running)
        assertEquals("success", idle.status)
    }

    @Test
    fun awaitIdleReturnsFinishedSnapshot() = runTest {
        val store = AgentRunStateStore()
        store.started("session-1")
        store.finished("session-1", "error", "boom")

        val snapshot = store.awaitIdle("session-1", 1_000)

        assertEquals("error", snapshot?.status)
        assertEquals("boom", snapshot?.error)
    }

    @Test
    fun awaitIdleReturnsNullForUnknownSession() = runTest {
        assertNull(AgentRunStateStore().awaitIdle("missing", 1_000))
    }
}
