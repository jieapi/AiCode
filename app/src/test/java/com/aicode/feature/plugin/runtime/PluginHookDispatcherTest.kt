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
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun firstDelegateWinsWithoutExecutingAndLaterArgumentsApply() = runBlocking {
        val first = hook("first", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        val second = hook("second", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(first, second))), listOf(first, second))
        coEvery { runtime.invoke(any(), any(), any(), "hooks.first", any(), any(), any()) } returns
            JsonObject(mapOf("delegate" to JsonObject(mapOf("handler" to JsonPrimitive("remote.first")))))
        val finalArgs = JsonObject(mapOf("path" to JsonPrimitive("final")))
        coEvery { runtime.invoke(any(), any(), any(), "hooks.second", any(), any(), any()) } returns
            JsonObject(mapOf("delegate" to JsonObject(mapOf("handler" to JsonPrimitive("remote.second"))), "arguments" to finalArgs))
        val result = dispatcher.beforeToolCall("session", "run", "call", "readFile", "BUILD", "main", null, JsonObject(emptyMap()))
        assertEquals("remote.first", result.delegation!!.handler)
        assertEquals(PLUGIN_ID, result.delegation!!.hook.pluginId)
        assertEquals(finalArgs, result.arguments)
        coVerify(exactly = 0) { runtime.invoke(any(), any(), any(), any(), any(), "tool.invoke", any()) }
    }

    @Test
    fun laterDenyDiscardsSelectedDelegationWithoutStartingIt() = runBlocking {
        val first = hook("first", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        val second = hook("second", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(first, second))), listOf(first, second))
        coEvery { runtime.invoke(any(), any(), any(), "hooks.first", any(), any(), any()) } returns
            JsonObject(mapOf("delegate" to JsonObject(mapOf("handler" to JsonPrimitive("remote")))))
        coEvery { runtime.invoke(any(), any(), any(), "hooks.second", any(), any(), any()) } returns
            JsonObject(mapOf("decision" to JsonPrimitive("deny")))
        val result = dispatcher.beforeToolCall("session", "run", "call", "writeFile", "PLAN", "main", null, JsonObject(emptyMap()))
        assertTrue(result.denied)
        assertEquals(null, result.delegation)
        coVerify(exactly = 0) { runtime.invoke(any(), any(), any(), any(), any(), "tool.invoke", any()) }
    }

    @Test
    fun delegatedStreamUsesFinalArgumentsAndBoundIdentity() = runBlocking {
        val hook = hook("route", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(hook))), listOf(hook))
        val delegation = ToolDelegation(hook, "1.0.0", "remote", "run", "call", "readFile", "execution")
        val args = JsonObject(mapOf("path" to JsonPrimitive("final")))
        coEvery { runtime.invoke(any(), any(), any(), "remote", any(), "tool.invoke", any()) } answers {
            val request = arg<JsonObject>(4)
            assertEquals(JsonPrimitive("execution"), request["executionId"])
            val start = request["operation"] == JsonPrimitive("start")
            if (start) assertEquals(args, request["arguments"])
            JsonObject(mapOf("executionId" to JsonPrimitive("execution"),
                "type" to JsonPrimitive(if (start) "progress" else "completed"),
                "chunk" to JsonPrimitive("reading"), "data" to JsonPrimitive("done")))
        }
        val progress = mutableListOf<String>()
        val result = dispatcher.executeDelegatedTool(delegation, "run", "call", "readFile", args) { progress += it }
        assertEquals(listOf("reading"), progress)
        assertTrue(result is com.aicode.feature.agent.domain.tool.ToolResult.Success)
        coVerify(exactly = 2) { runtime.invoke(any(), any(), any(), "remote", any(), "tool.invoke", any()) }
    }

    @Test
    fun delegatedCancellationPropagatesAndSendsBoundCancel() = runBlocking {
        val hook = hook("route", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(hook))), listOf(hook))
        val delegation = ToolDelegation(hook, "1.0.0", "remote", "run", "call", "readFile", "execution")
        coEvery { runtime.invoke(any(), any(), any(), "remote", match { it["operation"] == JsonPrimitive("start") }, any(), any()) } throws
            kotlinx.coroutines.CancellationException("cancelled")
        try {
            dispatcher.executeDelegatedTool(delegation, "run", "call", "readFile", JsonObject(emptyMap())) { }
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        coVerify(exactly = 1) {
            runtime.invoke(PLUGIN_ID, "1.0.0", runtimeRef, "remote", match {
                it["operation"] == JsonPrimitive("cancel") && it["executionId"] == JsonPrimitive("execution")
            }, "tool.invoke", 2_000L)
        }
    }

    @Test
    fun delegatePluginCannotReviewItsOwnNativeToolExecution() = runBlocking {
        val reviewer = hook("review", PluginHookEvents.AGENT_PERMISSION_REVIEW).copy(mode = PluginHookModes.REVIEW)
        stubPlugin(listOf(plugin(hooks = listOf(reviewer))), listOf(reviewer))
        val result = dispatcher.reviewPermission(
            "run", "session", "BUILD", "main", null, "call", "writeFile", JsonObject(emptyMap()), PLUGIN_ID
        )
        assertEquals(PermissionReviewDecision.PASS, result.decision)
        coVerify(exactly = 0) { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun delegatedFailureCancelsAndNeverReturnsLocalRoute() = runBlocking {
        val hook = hook("route", PluginHookEvents.AGENT_TOOL_BEFORE).copy(mode = PluginHookModes.TRANSFORM)
        stubPlugin(listOf(plugin(hooks = listOf(hook))), listOf(hook))
        val delegation = ToolDelegation(hook, "1.0.0", "remote", "run", "call", "readFile", "execution")
        coEvery { runtime.invoke(any(), any(), any(), "remote", any(), "tool.invoke", any()) } throws
            PluginProcessException("PLUGIN_TIMEOUT", "timeout")
        val result = dispatcher.executeDelegatedTool(delegation, "run", "call", "readFile", JsonObject(emptyMap())) { }
        assertTrue(result is com.aicode.feature.agent.domain.tool.ToolResult.Error)
        coVerify(exactly = 1) { runtime.invoke(any(), any(), any(), "remote", match { it["operation"] == JsonPrimitive("cancel") }, "tool.invoke", 2_000L) }
    }

    private companion object {
        const val PLUGIN_ID = "demo.hooks"
    }
}
