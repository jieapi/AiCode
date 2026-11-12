package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTabStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
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
}
