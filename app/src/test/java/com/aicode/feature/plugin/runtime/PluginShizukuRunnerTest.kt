package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.shizuku.ShizukuCommandResult
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginShizukuDefinition
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginShizukuRunnerTest {

    private val pluginId = "demo.shizuku"

    private fun runner(
        active: Boolean = true,
        granted: Boolean = true,
        state: ShizukuState = ShizukuState.READY,
        result: ShizukuCommandResult = ShizukuCommandResult("ok", 0)
    ): Triple<PluginShizukuRunner, ShizukuManager, PluginShizukuPermissionRepository> {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns pluginId
            every { isActive } returns active
            every { content } returns PluginContent(
                shizuku = PluginShizukuDefinition(pluginId, "切换免打扰")
            )
        }
        val pluginManager = mockk<PluginManager>(relaxed = true) {
            every { plugins } returns MutableStateFlow(listOf(plugin))
            every { ensureLoaded() } just Runs
        }
        val shizukuManager = mockk<ShizukuManager>(relaxed = true)
        every { shizukuManager.state } returns MutableStateFlow(state)
        coEvery { shizukuManager.runCommand(any(), any()) } returns result
        val permissions = mockk<PluginShizukuPermissionRepository>(relaxed = true) {
            coEvery { isGranted(any()) } returns granted
        }
        return Triple(PluginShizukuRunner(pluginManager, shizukuManager, permissions), shizukuManager, permissions)
    }

    @Test
    fun exec_runsAnyCommandAfterGrant() = runTest {
        val (runner, shizuku, permissions) = runner()

        val result = runner.exec(pluginId, "pm list packages", 30)

        assertEquals("ok", result.output)
        coVerify { permissions.isGranted(pluginId) }
        coVerify { shizuku.runCommand("pm list packages", 30_000L) }
    }

    @Test
    fun exec_rejectsWithoutUserGrant() = runTest {
        val (runner, shizuku, _) = runner(granted = false)

        val error = runCatching { runner.exec(pluginId, "settings get global zen_mode", 30) }.exceptionOrNull()

        assertTrue(error is PluginShizukuException)
        assertEquals("SHIZUKU_NOT_AUTHORIZED", (error as PluginShizukuException).code)
        coVerify(exactly = 0) { shizuku.runCommand(any(), any()) }
    }

    @Test
    fun exec_rejectsInactivePlugin() = runTest {
        val (runner, _, _) = runner(active = false)

        val error = runCatching { runner.exec(pluginId, "settings get global zen_mode", 30) }.exceptionOrNull()

        assertEquals("PLUGIN_INACTIVE", (error as PluginShizukuException).code)
    }

    @Test
    fun exec_reportsNotReadyState() = runTest {
        val (runner, _, _) = runner(state = ShizukuState.NOT_RUNNING)

        val error = runCatching { runner.exec(pluginId, "settings get global zen_mode", 30) }.exceptionOrNull()

        assertEquals("SHIZUKU_NOT_READY", (error as PluginShizukuException).code)
    }
}
