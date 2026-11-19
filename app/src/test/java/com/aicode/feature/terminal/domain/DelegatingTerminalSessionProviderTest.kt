package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.BackendExecutionContext
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.runtime.PluginTerminalRunner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegatingTerminalSessionProviderTest {
    private val a = provider("A")
    private val b = provider("B")
    private val backendA = backend("test:a", "instance-a", a)
    private val backendB = backend("test:b", "instance-b", b)
    private var active = backendA
    private val registered = mutableMapOf(backendA.id to backendA, backendB.id to backendB)

    @Test
    fun overlappingRemoteIdsKeepTheirOwnInputOutputAndCloseRoutes() = runTest {
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val handleA = router.startBackgroundCommand("command A")
        active = backendB
        val handleB = router.startBackgroundCommand("command B")

        assertNotEquals(RAW_ID, handleA)
        assertNotEquals(RAW_ID, handleB)
        assertNotEquals(handleA, handleB)
        assertEquals(listOf(handleB), router.listTabs().map { it.id })
        assertTrue(router.sendInput(handleA, "input A"))
        assertTrue(router.sendInput(handleB, "input B"))
        assertTrue(router.writeToTab(handleA, "raw A"))
        assertTrue(router.writeBytesToTab(handleB, 0x03))
        assertEquals(TerminalTabStatus(true, null), router.tabStatus(handleA))
        assertEquals("A", router.getTabOutput(handleA))
        assertEquals("B", router.tabOutput(handleB)?.first()?.decodeToString())
        assertTrue(router.closeTab(handleA))
        assertFalse(router.closeTab(handleA))
        assertTrue(router.closeTab(handleB))

        verify(exactly = 1) { a.sendInput(RAW_ID, "input A", true) }
        verify(exactly = 0) { a.sendInput(RAW_ID, "input B", any()) }
        verify(exactly = 1) { b.sendInput(RAW_ID, "input B", true) }
        verify(exactly = 0) { b.sendInput(RAW_ID, "input A", any()) }
        verify(exactly = 1) { a.closeTab(RAW_ID) }
        verify(exactly = 1) { b.closeTab(RAW_ID) }
    }

    @Test
    fun forwardsOldBackendCompletionWithItsHostHandleAndSourceSession() = runTest {
        val upstreamA = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 1)
        every { a.tabFinishedEvents } returns upstreamA
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val result = async { router.tabFinishedEvents.first() }
        val handleA = router.startBackgroundCommand("command A", notify = true, sourceSessionId = "agent-a")
        active = backendB
        router.startBackgroundCommand("command B")
        runCurrent()

        val event = TabFinishedEvent(RAW_ID, "A", "command A", 17, "agent-a", "tail")
        upstreamA.emit(event)

        assertEquals(event.copy(tabId = handleA), result.await())
    }

    @Test
    fun forwardsCompletionThatArrivesBeforeStartReturns() = runTest {
        val upstreamA = MutableSharedFlow<TabFinishedEvent>()
        every { a.tabFinishedEvents } returns upstreamA
        val event = TabFinishedEvent(RAW_ID, "A", "fast", 0, "agent-a")
        coEvery { a.startBackgroundCommand("fast", null, true, "agent-a") } coAnswers {
            upstreamA.emit(event)
            RAW_ID
        }
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val result = async { router.tabFinishedEvents.first() }
        val handle = router.startBackgroundCommand("fast", notify = true, sourceSessionId = "agent-a")

        assertEquals(event.copy(tabId = handle), result.await())
    }

    @Test
    fun rejectsUnknownRawIdsAndRetiredInstancesWithoutTouchingReplacement() = runTest {
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val old = router.startBackgroundCommand("old")
        active = backend(backendA.id, "replacement-a", a)
        registered[backendA.id] = active
        val fresh = router.startBackgroundCommand("fresh")

        for (id in listOf(RAW_ID, "unknown", old)) {
            assertNull(router.tabStatus(id))
            assertNull(router.getTabOutput(id))
            assertNull(router.tabOutput(id))
            assertFalse(router.sendInput(id, "ignored"))
            assertFalse(router.writeToTab(id, "ignored"))
            assertFalse(router.writeBytesToTab(id, 0x03))
            assertFalse(router.closeTab(id))
        }
        assertNotEquals(old, fresh)
        verify(exactly = 0) { a.sendInput(any(), any(), any()) }
        verify(exactly = 0) { a.closeTab(any()) }
    }

    @Test
    fun boundExecutionContextCannotStartOrListOnNewlySelectedBackend() = runTest {
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val handle = router.startBackgroundCommand("old")
        withContext(BackendExecutionContext.bind(backendA)) {
            active = backendB
            assertTrue(runCatching { router.startBackgroundCommand("wrong backend") }.isFailure)
            assertTrue(runCatching { router.listTabs() }.isFailure)
            assertTrue(router.sendInput(handle, "still belongs to A"))
        }
    }

    @Test
    fun pluginCanStopOldBackendRunAfterSwitchWithoutStoppingNewRun() = runTest {
        val router = DelegatingTerminalSessionProvider({ active }, backgroundScope, registered::get)
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.test"
            every { isActive } returns true
        }
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.plugins } returns MutableStateFlow(listOf(plugin))
        val runner = PluginTerminalRunner(router, manager)
        every { a.tabStatus(RAW_ID) } returns TerminalTabStatus(false, 130)
        val handleA = runner.start(plugin.id, "command A", null)
        active = backendB
        val handleB = runner.start(plugin.id, "command B", null)

        assertNotEquals(handleA, handleB)
        assertEquals(2, (runner.list(plugin.id) as JsonArray).size)
        assertTrue(runner.stop(plugin.id, handleA))
        assertTrue(runner.close(plugin.id, handleA))
        assertTrue(runner.close(plugin.id, handleB))
        verify(exactly = 1) { a.writeBytesToTab(RAW_ID, 0x03) }
        verify(exactly = 0) { b.writeBytesToTab(RAW_ID, 0x03) }
        verify(exactly = 1) { a.closeTab(RAW_ID) }
        verify(exactly = 1) { b.closeTab(RAW_ID) }
    }

    private fun backend(id: String, instance: String, provider: TerminalSessionProvider): ExecutionBackend = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { instanceId } returns instance
        every { terminalSessionProvider } returns provider
    }

    private fun provider(title: String): TerminalSessionProvider = mockk {
        every { tabFinishedEvents } returns MutableSharedFlow()
        coEvery { startBackgroundCommand(any(), any(), any(), any()) } returns RAW_ID
        coEvery { startPluginCommand(any(), any(), any()) } returns RAW_ID
        every { tabStatus(RAW_ID) } returns TerminalTabStatus(true, null)
        every { sendInput(RAW_ID, any(), any()) } returns true
        every { writeToTab(RAW_ID, any()) } returns true
        every { writeBytesToTab(RAW_ID, 0x03) } returns true
        every { getTabOutput(RAW_ID) } returns title
        every { tabOutput(RAW_ID) } returns flowOf(title.encodeToByteArray())
        every { listTabs() } returns listOf(TabInfo(RAW_ID, title, true, true, null))
        every { closeTab(RAW_ID) } returns true
    }

    private companion object {
        const val RAW_ID = "term-1"
    }
}
