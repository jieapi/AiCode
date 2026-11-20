package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.agent.domain.container.ContainerNotInitializedException
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTabStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginTerminalRunnerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun activePlugin(pluginId: String): InstalledPlugin = mockk {
        every { id } returns pluginId
        every { isActive } returns true
    }

    private fun runner(provider: TerminalSessionProvider, vararg plugins: InstalledPlugin): PluginTerminalRunner {
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.plugins } returns MutableStateFlow(plugins.toList())
        return PluginTerminalRunner(provider, manager)
    }

    @Test
    fun pollRejectsAnotherPluginsTab() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), any()) } returns "term-1"
        every { provider.tabOutput("term-1") } returns null
        every { provider.tabStatus("term-1") } returns TerminalTabStatus(running = true, exitCode = null)
        val runner = runner(provider, activePlugin("demo.test"))

        runner.start("demo.test", "echo hi", null)

        var rejected = false
        try {
            runner.poll("other.plugin", "term-1", 0)
        } catch (e: PluginTerminalException) {
            rejected = e.code == "TAB_FORBIDDEN"
        }
        assertTrue(rejected)
    }

    @Test
    fun stopAndCloseOnlyOwnedTab() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), any()) } returns "term-2"
        every { provider.tabOutput("term-2") } returns null
        every { provider.tabStatus("term-2") } returns TerminalTabStatus(running = false, exitCode = 0)
        every { provider.closeTab("term-2") } returns true
        val runner = runner(provider, activePlugin("demo.test"))

        runner.start("demo.test", "echo hi", null)

        assertTrue(runner.stop("demo.test", "term-2"))
        assertTrue(runner.close("demo.test", "term-2"))
    }

    @Test
    fun startReportsUninitializedContainer() = runTest {
        val provider = mockk<TerminalSessionProvider>()
        coEvery { provider.startPluginCommand(any(), any(), any()) } throws ContainerNotInitializedException()
        val runner = runner(provider, activePlugin("demo.test"))

        var errorCode: String? = null
        try {
            runner.start("demo.test", "echo hi", null)
        } catch (e: PluginTerminalException) {
            errorCode = e.code
        }
        assertEquals("CONTAINER_NOT_INITIALIZED", errorCode)
    }

    @Test
    fun startRejectsInactivePlugin() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        val runner = runner(provider, activePlugin("demo.test"))

        var rejected = false
        try {
            runner.start("other.plugin", "echo hi", null)
        } catch (e: PluginTerminalException) {
            rejected = e.code == "PLUGIN_INACTIVE"
        }
        assertTrue(rejected)
    }

    @Test
    fun writeRejectsAnotherPluginsTab() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), any()) } returns "term-1"
        every { provider.tabOutput("term-1") } returns null
        val runner = runner(provider, activePlugin("demo.test"))

        runner.start("demo.test", "bash", null)

        var rejected = false
        try {
            runner.write("other.plugin", "term-1", "ls")
        } catch (e: PluginTerminalException) {
            rejected = e.code == "TAB_FORBIDDEN"
        }
        assertTrue(rejected)
        verify(exactly = 0) { provider.sendInput(any(), any(), any()) }
    }

    @Test
    fun writeValidatesTextAndSendsToOwnedTab() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), any()) } returns "term-2"
        every { provider.tabOutput("term-2") } returns null
        every { provider.sendInput("term-2", "ls -la", false) } returns true
        val runner = runner(provider, activePlugin("demo.test"))

        runner.start("demo.test", "bash", null)

        assertTrue(runner.write("demo.test", "term-2", "ls -la", appendNewline = false))
        verify { provider.sendInput("term-2", "ls -la", false) }

        for (text in listOf("", "x".repeat(4_001))) {
            var rejected = false
            try {
                runner.write("demo.test", "term-2", text)
            } catch (e: PluginTerminalException) {
                rejected = e.code == "PARAM_INVALID"
            }
            assertTrue(rejected)
        }
    }

    @Test
    fun listReturnsOnlyOwnTabsWithState() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), "demo.one") } returns "term-1"
        coEvery { provider.startPluginCommand(any(), any(), "demo.two") } returns "term-2"
        every { provider.tabOutput(any()) } returns null
        every { provider.tabStatus("term-1") } returns TerminalTabStatus(running = true, exitCode = null)
        every { provider.tabStatus("term-2") } returns TerminalTabStatus(running = false, exitCode = 0)
        val runner = runner(provider, activePlugin("demo.one"), activePlugin("demo.two"))

        runner.start("demo.one", "sleep 5", "watch")
        runner.start("demo.two", "echo hi", null)

        assertEquals(
            """[{"tabId":"term-1","title":"watch","command":"sleep 5","state":"running"}]""",
            runner.list("demo.one").toString()
        )
        assertEquals(
            """[{"tabId":"term-2","title":"term-2","command":"echo hi","state":"finished"}]""",
            runner.list("demo.two").toString()
        )
        assertEquals("[]", runner.list("demo.three").toString())
    }

    @Test
    fun listReportsCancelledTabs() = runTest {
        val provider = mockk<TerminalSessionProvider>(relaxed = true)
        coEvery { provider.startPluginCommand(any(), any(), any()) } returns "term-3"
        every { provider.tabOutput("term-3") } returns null
        every { provider.tabStatus("term-3") } returns TerminalTabStatus(running = false, exitCode = 130)
        val runner = runner(provider, activePlugin("demo.test"))

        runner.start("demo.test", "tail -f app.log", "tail")
        runner.stop("demo.test", "term-3")

        assertEquals(
            """[{"tabId":"term-3","title":"tail","command":"tail -f app.log","state":"cancelled"}]""",
            runner.list("demo.test").toString()
        )
    }
}
