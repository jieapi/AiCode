package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.overlay.AppForegroundMonitor
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHookDispatcherTest {

    private val manager = mockk<PluginManager>()
    private val runtime = mockk<PluginRuntimeManager>()
    private val dispatcher = PluginHookDispatcher(
        pluginManager = manager,
        runtime = runtime,
        appForegroundMonitor = mockk<AppForegroundMonitor>(relaxed = true)
    )

    private val record = PluginRecord(
        id = PLUGIN_ID,
        version = "1.0.0",
        enabled = true,
        installedAt = 1_000L,
        origin = "local",
        archiveSha256 = "archive-sha256",
        manifestSha256 = "manifest-sha256"
    )
    private val runtimeRef = PluginRuntime(
        pluginId = PLUGIN_ID,
        type = "javascript",
        installDir = File("/tmp/$PLUGIN_ID"),
        entryFile = File("/tmp/$PLUGIN_ID/main.js"),
        command = listOf("node", "main.js")
    )
    private val hooks = listOf(
        hook("on-installed", PluginHookEvents.PLUGIN_INSTALLED),
        hook("on-uninstalled", PluginHookEvents.PLUGIN_UNINSTALLED),
        hook("on-enabled", PluginHookEvents.PLUGIN_ENABLED),
        hook("on-disabled", PluginHookEvents.PLUGIN_DISABLED)
    )

    private fun hook(localId: String, event: String) = PluginHook(
        pluginId = PLUGIN_ID,
        localId = localId,
        event = event,
        handler = "hooks.$localId",
        runtime = runtimeRef
    )

    private fun plugin(
        record: PluginRecord = this.record,
        hooks: List<PluginHook> = this.hooks
    ) = InstalledPlugin(
        record = record,
        status = PluginStatus.OK,
        manifest = mockk<PluginManifest>(relaxed = true),
        content = PluginContent(runtime = runtimeRef, hooks = hooks)
    )

    private fun stubPlugin(plugins: List<InstalledPlugin>, activeHooks: List<PluginHook>) {
        every { manager.ensureLoaded() } just Runs
        every { manager.plugins } returns MutableStateFlow(plugins)
        every { manager.activeHooks() } returns activeHooks
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) } returns JsonNull
    }

    @Test
    fun uninstallDispatchWaitsForTargetHook() = runBlocking {
        stubPlugin(listOf(plugin()), hooks)

        dispatcher.dispatchPluginUninstalled(PLUGIN_ID)

        coVerify {
            runtime.invoke(
                pluginId = PLUGIN_ID,
                version = "1.0.0",
                runtime = runtimeRef,
                handler = "hooks.on-uninstalled",
                args = match {
                    it["pluginId"]?.jsonPrimitive?.content == PLUGIN_ID &&
                        it["version"]?.jsonPrimitive?.content == "1.0.0"
                },
                method = "hook.invoke",
                timeoutMs = 10_000L
            )
        }
    }

    @Test
    fun disableDispatchWaitsForTargetHook() = runBlocking {
        stubPlugin(listOf(plugin()), hooks)

        dispatcher.dispatchPluginDisabled(PLUGIN_ID)

        coVerify {
            runtime.invoke(
                pluginId = PLUGIN_ID,
                version = "1.0.0",
                runtime = runtimeRef,
                handler = "hooks.on-disabled",
                args = any(),
                method = "hook.invoke",
                timeoutMs = 10_000L
            )
        }
    }

    @Test
    fun installDispatchIncludesPreviousVersion() = runBlocking {
        stubPlugin(listOf(plugin()), hooks)

        dispatcher.dispatchPluginInstalled(PLUGIN_ID, "0.9.0")

        coVerify(timeout = 2_000L) {
            runtime.invoke(
                pluginId = PLUGIN_ID,
                version = "1.0.0",
                runtime = runtimeRef,
                handler = "hooks.on-installed",
                args = match {
                    it["previousVersion"]?.jsonPrimitive?.content == "0.9.0" &&
                        it["installedAt"]?.jsonPrimitive?.content == "1000"
                },
                method = "hook.invoke",
                timeoutMs = 30_000L
            )
        }
    }

    @Test
    fun lifecycleDispatchSkipsOtherPlugins() = runBlocking {
        val otherHook = PluginHook(
            pluginId = "other.plugin",
            localId = "on-uninstalled",
            event = PluginHookEvents.PLUGIN_UNINSTALLED,
            handler = "hooks.on-uninstalled",
            runtime = runtimeRef.copy(pluginId = "other.plugin")
        )
        stubPlugin(
            plugins = listOf(
                plugin(),
                plugin(record = record.copy(id = "other.plugin"), hooks = listOf(otherHook))
            ),
            activeHooks = hooks + otherHook
        )

        dispatcher.dispatchPluginUninstalled(PLUGIN_ID)

        coVerify(exactly = 0) {
            runtime.invoke(
                pluginId = "other.plugin",
                version = any(),
                runtime = any(),
                handler = any(),
                args = any(),
                method = any(),
                timeoutMs = any()
            )
        }
    }

    @Test
    fun uninstallDispatchIgnoresHandlerFailure() = runBlocking {
        stubPlugin(listOf(plugin()), hooks)
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("boom")

        dispatcher.dispatchPluginUninstalled(PLUGIN_ID)
    }

    @Test
    fun transformHookOpensCircuitAfterThreeFailures() = runBlocking {
        val guard = hook("guard-bash", PluginHookEvents.AGENT_TOOL_BEFORE)
            .copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(guard))), listOf(guard))
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("boom")

        repeat(3) {
            dispatcher.beforeToolCall(
                sessionId = "session",
                runId = "run",
                callId = "call-$it",
                toolName = "bash",
                mode = "BUILD",
                scope = "main",
                agentName = null,
                arguments = JsonObject(emptyMap())
            )
        }

        coVerify(exactly = 3) {
            runtime.invoke(any(), any(), any(), any(), any(), any(), any())
        }
        val health = requireNotNull(dispatcher.hookHealth.value["$PLUGIN_ID/guard-bash"])
        assertEquals(3, health.consecutiveFailures)
        assertTrue(health.isOpen())
        assertEquals("IllegalStateException", health.lastError)

        dispatcher.beforeToolCall(
            sessionId = "session",
            runId = "run",
            callId = "call-open",
            toolName = "bash",
            mode = "BUILD",
            scope = "main",
            agentName = null,
            arguments = JsonObject(emptyMap())
        )

        coVerify(exactly = 3) {
            runtime.invoke(any(), any(), any(), any(), any(), any(), any())
        }
    }

    private companion object {
        const val PLUGIN_ID = "demo.hooks"
    }
}
