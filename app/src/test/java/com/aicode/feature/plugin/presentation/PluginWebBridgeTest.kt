package com.aicode.feature.plugin.presentation

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.session.AgentRunEvent
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.runtime.PluginEventBus
import com.aicode.feature.plugin.runtime.PluginModelService
import com.aicode.feature.plugin.runtime.PluginProviderService
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginWebBridgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bridges = mutableListOf<PluginWebBridge>()
    private val pluginId = "test.web"

    private data class Env(
        val bridge: PluginWebBridge,
        val plugins: MutableStateFlow<List<InstalledPlugin>>,
        val commandEngine: CommandEngine,
        val fileAccess: FileAccessProvider,
        val modelService: PluginModelService,
        val providerService: PluginProviderService,
        val hostApiDispatcher: com.aicode.feature.plugin.runtime.PluginHostApiDispatcher,
        val eventBus: PluginEventBus,
        val environment: PluginPageEnvironment,
        val scripts: MutableList<String>
    )

    @After
    fun tearDown() {
        bridges.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
        PluginWebViewCache.clear()
    }

    private fun env(enabled: Boolean = true): Env {
        val page = PluginPage(
            pluginId, "settings", LocalizedText.single("Settings"), null,
            "settings.html", "settings", tmp.newFolder(), "1.0.0"
        )
        val plugin = InstalledPlugin(
            record = PluginRecord(pluginId, "1.0.0", enabled, 1L, "local", "archive-1", "manifest"),
            status = PluginStatus.OK,
            manifest = mockk(),
            content = PluginContent(configPage = page)
        )
        val plugins = MutableStateFlow(listOf(plugin))
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.plugins } returns plugins
        val engine = mockk<CommandEngine>(relaxed = true)
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)
        val fileAccess = mockk<FileAccessProvider>(relaxed = true)
        val home = mockk<PathHomeResolver>(relaxed = true)
        every { home.home() } returns "/root"
        every { home.aicodeRoot() } returns "/root/.aicode"
        val environment = mockk<PluginPageEnvironment>(relaxed = true)
        every { environment.contextKey() } returns "workspace-a"
        every { environment.changes } returns MutableSharedFlow()
        every { environment.workspaceRoot() } returns "/root/workspace"
        val runs = mockk<AgentRunStateStore>(relaxed = true)
        every { runs.events } returns MutableSharedFlow<AgentRunEvent>()
        val modelService = mockk<PluginModelService>(relaxed = true)
        val providerService = mockk<PluginProviderService>(relaxed = true)
        val eventBus = PluginEventBus()
        val hostApiDispatcher = mockk<com.aicode.feature.plugin.runtime.PluginHostApiDispatcher>()
        coEvery {
            hostApiDispatcher.dispatch(any(), "bash", any(), any(), any(), any())
        } returns buildJsonObject {
            put("output", "ok")
            put("exitCode", 0)
            put("truncated", false)
        }
        coEvery {
            hostApiDispatcher.dispatch(any(), "providers.setApiKey", any(), any(), any(), any())
        } coAnswers {
            providerService.setApiKey(pluginId, thirdArg())
        }
        val scripts = mutableListOf<String>()
        val bridge = PluginWebBridge(
            context = ApplicationProvider.getApplicationContext(),
            page = page,
            pluginVersion = "1.0.0",
            languageTag = "zh-CN",
            fileAccess = fileAccess,
            homeResolver = home,
            workspacePath = { "/projects/a" },
            eventBus = eventBus,
            pluginManager = manager,
            pluginRuntimeManager = mockk(relaxed = true),
            modelService = modelService,
            agentRunStateStore = runs,
            hostApiDispatcher = hostApiDispatcher,
            environment = environment,
            theme = { "light" },
            onClosePage = {},
            post = { scripts += it }
        )
        bridges += bridge
        return Env(bridge, plugins, engine, fileAccess, modelService, providerService, hostApiDispatcher, eventBus, environment, scripts)
    }

    private suspend fun assertInactive(bridge: PluginWebBridge, method: String, params: JsonObject) {
        val failure = runCatching { bridge.dispatch(method, params) }.exceptionOrNull()
        assertTrue(failure is PluginHostException)
        assertEquals("PLUGIN_INACTIVE", (failure as PluginHostException).code)
    }

    @Test
    fun activeInstallationCanExecuteCommands() = runBlocking {
        val env = env()
        val result = env.bridge.dispatch("bash", buildJsonObject { put("command", "pwd") }) as JsonObject
        assertEquals("ok", result.getValue("output").jsonPrimitive.content)
        coVerify(exactly = 1) {
            env.hostApiDispatcher.dispatch(
                pluginId,
                "bash",
                any(),
                com.aicode.feature.plugin.runtime.PluginHostApiOrigin.PAGE,
                any(),
                "page:${env.bridge.javaClass.getDeclaredField("page").let { it.isAccessible = true; (it.get(env.bridge) as com.aicode.feature.plugin.domain.PluginPage).cacheKey } }"
            )
        }
    }

    @Test
    fun providerManagementIsForwardedToProviderService() = runBlocking {
        val env = env()
        coEvery { env.providerService.setApiKey(any(), any()) } returns buildJsonObject { put("ok", true) }

        env.bridge.dispatch(
            "providers.setApiKey",
            buildJsonObject {
                put("providerId", "echo")
                put("apiKey", "local-token")
            }
        )

        coVerify(exactly = 1) {
            env.hostApiDispatcher.dispatch(
                pluginId,
                "providers.setApiKey",
                any(),
                com.aicode.feature.plugin.runtime.PluginHostApiOrigin.PAGE,
                any(),
                any()
            )
        }
    }

    @Test
    fun disabledInstallationCannotExecuteCommandsOrWriteFiles() = runBlocking {
        val env = env(enabled = false)
        assertInactive(env.bridge, "bash", buildJsonObject { put("command", "pwd") })
        assertInactive(env.bridge, "fs.write", buildJsonObject {
            put("path", "/root/workspace/file.txt")
            put("content", "content")
        })
        coVerify(exactly = 0) { env.commandEngine.runCommandSyncWithExit(any(), any(), any()) }
        verify(exactly = 0) { env.fileAccess.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun disablingAndUninstallingInvalidateExistingBridgeImmediately() = runBlocking {
        val env = env()
        val plugin = env.plugins.value.single()
        env.plugins.value = listOf(plugin.copy(record = plugin.record.copy(enabled = false)))
        assertInactive(env.bridge, "bash", buildJsonObject { put("command", "pwd") })
        verify(timeout = 5_000) { env.modelService.closeOrigin(any()) }
        env.plugins.value = listOf(plugin)
        assertInactive(env.bridge, "context", JsonObject(emptyMap()))

        val another = env()
        another.plugins.value = emptyList()
        assertInactive(another.bridge, "fs.delete", buildJsonObject { put("path", "/root/workspace/file.txt") })
        coVerify(exactly = 0) { env.commandEngine.runCommandSyncWithExit(any(), any(), any()) }
        verify(exactly = 0) { another.fileAccess.deleteRecursively(any()) }
    }

    @Test
    fun rapidDisableAndEnableCannotRestoreTheOldBridge() = runBlocking {
        val env = env()
        val plugin = env.plugins.value.single()
        env.plugins.value = listOf(plugin.copy(record = plugin.record.copy(enabled = false)))
        env.plugins.value = listOf(plugin)
        assertInactive(env.bridge, "bash", buildJsonObject { put("command", "pwd") })
        coVerify(exactly = 0) { env.commandEngine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun sameVersionReplacementCannotReuseOldBridge() = runBlocking {
        val env = env()
        val plugin = env.plugins.value.single()
        env.plugins.value = listOf(plugin.copy(record = plugin.record.copy(installedAt = 2L, archiveSha256 = "archive-2")))
        assertInactive(env.bridge, "bash", buildJsonObject { put("command", "pwd") })
        coVerify(exactly = 0) { env.commandEngine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun installationRevisionChangeInvalidatesTheBridgeWithoutTimestampOrArchiveChanges() = runBlocking {
        val env = env()
        val plugin = env.plugins.value.single()
        env.plugins.value = listOf(plugin.copy(record = plugin.record.copy(installRevision = "replacement")))
        assertInactive(env.bridge, "bash", buildJsonObject { put("command", "pwd") })
        coVerify(exactly = 0) { env.commandEngine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test
    fun dependencyStateUpdateKeepsTheCurrentBridgeAuthorized() = runBlocking {
        val env = env()
        val plugin = env.plugins.value.single()
        env.plugins.value = listOf(plugin.copy(record = plugin.record.copy(dependenciesReady = true, dependenciesHash = "deps")))
        assertTrue(env.bridge.isActive)
        env.bridge.dispatch("bash", buildJsonObject { put("command", "pwd") })
        coVerify(exactly = 1) {
            env.hostApiDispatcher.dispatch(
                pluginId,
                "bash",
                any(),
                com.aicode.feature.plugin.runtime.PluginHostApiOrigin.PAGE,
                any(),
                any()
            )
        }
    }

    @Test
    fun burstEventsShareJavascriptCallsAndKeepTheirOrder() {
        val env = env()
        repeat(65) { index ->
            env.eventBus.publish(pluginId, buildJsonObject { put("name", "event-$index") })
        }
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertTrue(env.scripts.isEmpty())

        looper.idleFor(Duration.ofMillis(16))

        assertEquals(3, env.scripts.size)
        assertEquals(32, env.scripts.first().split("window.__aicodeEvent(").size - 1)
        val eventNames = Regex("event-\\d+").findAll(env.scripts.joinToString("\n")).map { it.value }.toList()
        assertEquals((0 until 65).map { "event-$it" }, eventNames)
    }

    @Test
    fun largeEventsAreNotCombinedIntoOversizedJavascriptBatches() {
        val env = env()
        repeat(3) { index ->
            env.eventBus.publish(pluginId, buildJsonObject {
                put("name", "large-$index")
                put("payload", "x".repeat(40_000))
            })
        }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))

        assertEquals(3, env.scripts.size)
        assertTrue(env.scripts.all { it.length < 64 * 1024 })
    }

    @Test
    fun repliesKeepTheirPositionAndRemainSeparateFromEventBatches() {
        val env = env()
        env.eventBus.publish(pluginId, buildJsonObject { put("name", "before-reply") })
        val deliver = PluginWebBridge::class.java.getDeclaredMethod("deliver", String::class.java, JsonObject::class.java)
        deliver.isAccessible = true
        deliver.invoke(env.bridge, "request-1", buildJsonObject { put("ok", true) })
        env.eventBus.publish(pluginId, buildJsonObject { put("name", "after-reply") })

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, env.scripts.size)
        assertTrue(env.scripts[0].contains("before-reply"))
        assertTrue(env.scripts[1].contains("__aicodeResolve"))
        assertFalse(env.scripts[1].contains("__aicodeEvent"))
        assertTrue(env.scripts[2].contains("after-reply"))
    }

    @Test
    fun postMessageParsesRequestsOffTheCallingThreadAndDeliversAReply() {
        val env = env()
        val responseReady = CountDownLatch(1)
        val pendingScripts = mutableListOf<String>()
        val handler = mockk<android.os.Handler>()
        val originalHandler = PluginWebBridge::class.java.getDeclaredField("mainHandler").apply {
            isAccessible = true
        }.get(env.bridge)
        val callerThread = Thread.currentThread()
        var processingThread: Thread? = null
        every { handler.removeCallbacks(any()) } returns Unit
        every { handler.post(any()) } answers {
            responseReady.countDown()
            true
        }
        val handlerField = PluginWebBridge::class.java.getDeclaredField("mainHandler")
        handlerField.isAccessible = true
        handlerField.set(env.bridge, handler)
        every { env.environment.workspaceName() } answers {
            processingThread = Thread.currentThread()
            "workspace"
        }
        env.bridge.updateCallbacks({ "light" }, {}, { pendingScripts += it })

        env.bridge.postMessage("""{"id":"request-1","method":"context","params":{}}""")
        assertTrue(responseReady.await(5, TimeUnit.SECONDS))
        val flush = PluginWebBridge::class.java.getDeclaredMethod("flushEvents")
        flush.isAccessible = true
        flush.invoke(env.bridge)

        assertTrue(processingThread !== callerThread)
        assertTrue(pendingScripts.single().contains("__aicodeResolve"))
        assertTrue(pendingScripts.single().contains("request-1"))
        handlerField.set(env.bridge, originalHandler)
    }

    @Test
    fun cachedPageStillReceivesEventsWithoutBeingShown() {
        val env = env()
        env.eventBus.publish(pluginId, buildJsonObject { put("name", "background-finished") })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))

        assertTrue(env.scripts.single().contains("background-finished"))
    }

    @Test
    fun queuedContextEventsAreDiscardedAfterWorkspaceChange() {
        val env = env()
        env.eventBus.publish(pluginId, buildJsonObject {
            put("name", "fs.changed")
            put("payload", buildJsonObject { put("contextKey", "workspace-a") })
        })
        env.eventBus.publish(pluginId, buildJsonObject { put("name", "custom-event") })
        every { env.environment.contextKey() } returns "workspace-b"

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))

        assertFalse(env.scripts.single().contains("fs.changed"))
        assertTrue(env.scripts.single().contains("custom-event"))
    }

    @Test
    fun closingBridgeCancelsPendingEventBatches() {
        val env = env()
        env.eventBus.publish(pluginId, buildJsonObject { put("name", "pending-event") })
        env.bridge.close()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertTrue(env.scripts.isEmpty())
    }

    @Test
    fun cacheInvalidationDoesNotEvictAReplacementBridgeOrAcceptOldLoadCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = WebView(context)
        val second = WebView(context)
        val firstBridge = mockk<PluginWebBridge>(relaxed = true)
        val secondBridge = mockk<PluginWebBridge>(relaxed = true)
        every { firstBridge.isActive } returns true
        every { secondBridge.isActive } returns true
        PluginWebViewCache.put("page", first)
        PluginWebViewCache.putBridge("page", firstBridge)
        PluginWebViewCache.put("page", second)
        PluginWebViewCache.putBridge("page", secondBridge)
        PluginWebViewCache.invalidateBridge(firstBridge)
        PluginWebViewCache.markLoaded("page", "old-url", first)
        assertFalse(PluginWebViewCache.isLoaded("page", "old-url"))
        assertSame(second, PluginWebViewCache.take("page"))
        PluginWebViewCache.markLoaded("page", "new-url", second)
        assertTrue(PluginWebViewCache.isLoaded("page", "new-url"))
        PluginWebViewCache.invalidateBridge(secondBridge)
        assertNull(PluginWebViewCache.take("page"))
        verify { secondBridge.close() }
    }
}
