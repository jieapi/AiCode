package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginExternalSessionDefinition
import com.aicode.feature.plugin.domain.PluginExternalSessionHandlersRef
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.session.ExternalMessageBlock
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSessionServiceTest {

    private val runtime = PluginRuntime(
        pluginId = "demo.remote",
        type = "javascript",
        installDir = File("/tmp/demo"),
        entryFile = File("/tmp/demo/index.js"),
        command = listOf("node", "index.js")
    )

    private val definition = PluginExternalSessionDefinition(
        pluginId = "demo.remote",
        localId = "harness",
        title = LocalizedText.single("Remote Harness"),
        description = LocalizedText.single("Remote sessions"),
        handlers = PluginExternalSessionHandlersRef(
            refresh = "sessions.refresh",
            readMessages = "sessions.readMessages",
            createSession = "sessions.create",
            deleteSession = "sessions.delete",
            renameSession = "sessions.rename",
            pinSession = "sessions.pin"
        ),
        runtime = runtime,
        pluginVersion = "1.0.0"
    )

    private val manifest = mockk<PluginManifest> {
        every { name } returns LocalizedText.single("Demo Remote")
    }

    private fun buildManager(active: Boolean = true): PluginManager {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.remote"
            every { isActive } returns active
            every { this@mockk.manifest } returns this@ExternalSessionServiceTest.manifest
            every { content } returns PluginContent(sessions = listOf(definition))
        }
        return mockk {
            every { ensureLoaded() } just Runs
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
    }

    @Test
    fun syncAndRefresh_invokesSessionsInvokeAndPopulatesStore() = runTest {
        val store = ExternalSessionStore()
        val pluginManager = buildManager()
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invoke(
                pluginId = "demo.remote",
                version = "1.0.0",
                runtime = runtime,
                handler = "sessions.refresh",
                args = any(),
                method = "sessions.invoke",
                timeoutMs = any()
            )
        } returns buildJsonObject {
            put("sessions", buildJsonArray {
                add(buildJsonObject {
                    put("sessionId", "s1")
                    put("title", "Remote Task")
                    put("status", "running")
                    put("running", true)
                    put("updatedAt", 1234L)
                    put("isPinned", true)
                })
            })
        }

        val service = ExternalSessionService(pluginManager, runtimeManager, store)
        service.requestRefresh()

        val group = store.groups.value.single()
        assertEquals("demo.remote", group.pluginId)
        assertFalse(group.loading)
        assertNull(group.error)
        assertEquals(1, group.sessions.size)
        val summary = group.sessions.single()
        assertEquals("demo.remote:harness|s1", summary.id)
        assertEquals("Remote Task", summary.title)
        assertTrue(summary.running)
        assertTrue(summary.isPinned)

        coVerify(exactly = 1) {
            runtimeManager.invoke(
                pluginId = "demo.remote",
                version = "1.0.0",
                runtime = runtime,
                handler = "sessions.refresh",
                args = match { it["sourceId"]?.toString()?.contains("harness") == true },
                method = "sessions.invoke",
                timeoutMs = any()
            )
        }
    }

    @Test
    fun refreshFailure_recordsErrorAndClearsLoading() = runTest {
        val store = ExternalSessionStore()
        val pluginManager = buildManager()
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invoke(any(), any(), any(), "sessions.refresh", any(), "sessions.invoke", any())
        } throws PluginProcessException("PLUGIN_ERROR", "remote unreachable")

        val service = ExternalSessionService(pluginManager, runtimeManager, store)
        service.requestRefresh()

        val group = store.groups.value.single()
        assertFalse(group.loading)
        assertEquals("remote unreachable", group.error)
    }

    @Test
    fun readMessages_parsesBlocksAndCursorViaSessionsInvoke() = runTest {
        val store = ExternalSessionStore()
        val pluginManager = buildManager()
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invoke(
                pluginId = "demo.remote",
                version = "1.0.0",
                runtime = runtime,
                handler = "sessions.readMessages",
                args = any(),
                method = "sessions.invoke",
                timeoutMs = any()
            )
        } returns buildJsonObject {
            put("nextCursor", "c-2")
            put("hasMore", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("id", "m1")
                    put("role", "assistant")
                    put("createdAt", 1000L)
                    put("timestamp", 9999L)
                    put("blocks", buildJsonArray {
                        add(buildJsonObject { put("type", "reasoning"); put("text", "plan step") })
                        add(buildJsonObject { put("type", "text"); put("text", "Hello") })
                        add(buildJsonObject {
                            put("type", "tool")
                            put("id", "tc1")
                            put("name", "bash")
                            put("arguments", "{\"cmd\":\"ls\"}")
                            put("result", "ok")
                            put("isError", false)
                        })
                        add(buildJsonObject {
                            put("type", "attachment")
                            put("name", "log.txt")
                            put("mimeType", "text/plain")
                            put("size", 42L)
                        })
                        add(buildJsonObject { put("type", "notice"); put("text", "done"); put("level", "info") })
                    })
                })
            })
        }

        val service = ExternalSessionService(pluginManager, runtimeManager, store)
        val page = service.readMessages("demo.remote:harness", "s1", cursor = JsonPrimitive("c-1"), maxMessages = 20)

        assertEquals(JsonPrimitive("c-2"), page.nextCursor)
        coVerify {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.readMessages",
                match { it["cursor"] == JsonPrimitive("c-1") && it["maxMessages"] == JsonPrimitive(20) },
                "sessions.invoke", any())
        }
        assertTrue(page.hasMore)
        val msg = page.messages.single()
        assertEquals("m1", msg.id)
        assertEquals(1000L, msg.timestamp)
        assertEquals(5, msg.blocks.size)
        assertTrue(msg.blocks[0] is ExternalMessageBlock.Reasoning)
        assertTrue(msg.blocks[1] is ExternalMessageBlock.Text)
        assertTrue(msg.blocks[2] is ExternalMessageBlock.Tool)
        assertTrue(msg.blocks[3] is ExternalMessageBlock.Attachment)
        assertTrue(msg.blocks[4] is ExternalMessageBlock.Notice)
    }

    @Test
    fun readMessages_preservesEveryJsonCursorType() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val service = ExternalSessionService(buildManager(), runtimeManager, ExternalSessionStore())
        val cursors = listOf(
            buildJsonObject { put("offset", 20); put("token", "opaque") },
            buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive("page")) },
            JsonPrimitive(0),
            JsonPrimitive(false),
            JsonPrimitive(""),
            JsonNull
        )
        cursors.forEach { cursor ->
            coEvery {
                runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.readMessages", any(), "sessions.invoke", any())
            } returns buildJsonObject {
                put("messages", buildJsonArray {})
                put("nextCursor", cursor)
            }
            val page = service.readMessages("demo.remote:harness", "s1", cursor)
            assertEquals(cursor, page.nextCursor)
            assertEquals(cursor != JsonNull, page.hasMore)
            coVerify {
                runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.readMessages",
                    match { it["cursor"] == cursor }, "sessions.invoke", any())
            }
        }
    }

    @Test
    fun readMessages_missingCursorAndCreatedAtDoNotUseLegacyFields() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.readMessages", any(), "sessions.invoke", any())
        } returns buildJsonObject {
            put("cursor", "legacy")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("timestamp", 123L) })
            })
        }
        val service = ExternalSessionService(buildManager(), runtimeManager, ExternalSessionStore())
        val page = service.readMessages("demo.remote:harness", "s1")
        assertNull(page.nextCursor)
        assertFalse(page.hasMore)
        assertEquals(0L, page.messages.single().timestamp)
        coVerify {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.readMessages",
                match { "cursor" !in it }, "sessions.invoke", any())
        }
    }

    @Test
    fun sourceFailures_useStableErrorCodes() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val service = ExternalSessionService(buildManager(), runtimeManager, ExternalSessionStore())
        try {
            service.readMessages("demo.remote:missing", "s1")
            fail("expected missing source")
        } catch (e: PluginHostException) {
            assertEquals("SESSION_SOURCE_NOT_FOUND", e.code)
        }
        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, any(), any(), "sessions.invoke", any())
        } returns JsonArray(emptyList())
        try {
            service.readMessages("demo.remote:harness", "s1")
            fail("expected invalid page")
        } catch (e: PluginHostException) {
            assertEquals("SESSION_SOURCE_INVALID", e.code)
        }
        try {
            service.createSession("demo.remote:harness")
            fail("expected invalid session")
        } catch (e: PluginHostException) {
            assertEquals("SESSION_SOURCE_INVALID", e.code)
        }
    }

    @Test
    fun thinking_requiresVisibleBooleanAndUsesStableDefault() {
        val store = ExternalSessionStore()
        val host = ExternalSessionHostService(buildManager(), store)
        val params = buildJsonObject { put("sourceId", "harness"); put("sessionId", "s1") }
        listOf(null, JsonNull, JsonPrimitive("true"), JsonPrimitive(1)).forEach { invalid ->
            try {
                host.setThinking("demo.remote", buildJsonObject {
                    params.forEach { (key, value) -> put(key, value) }
                    if (invalid != null) put("visible", invalid)
                    put("text", "text alone is insufficient")
                })
                fail("expected invalid visible: $invalid")
            } catch (e: PluginHostException) {
                assertEquals("PARAM_INVALID", e.code)
            }
        }
        host.setThinking("demo.remote", buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("visible", true)
        })
        assertEquals("Thinking…", store.thinking.value["demo.remote:harness|s1"])
        host.setThinking("demo.remote", buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("visible", false)
            put("text", "ignored")
        })
        assertFalse(store.thinking.value.containsKey("demo.remote:harness|s1"))
    }

    @Test
    fun sessionLifecycle_createRenamePinDeleteUpdatesStore() = runTest {
        val store = ExternalSessionStore()
        val pluginManager = buildManager()
        val runtimeManager = mockk<PluginRuntimeManager>()
        val service = ExternalSessionService(pluginManager, runtimeManager, store)

        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.create", any(), "sessions.invoke", any())
        } returns buildJsonObject {
            put("session", buildJsonObject {
                put("sessionId", "new-1")
                put("title", "Created")
                put("updatedAt", 100L)
            })
        }
        val created = service.createSession("demo.remote:harness", "Created")
        assertEquals("demo.remote:harness|new-1", created.id)
        assertNotNull(store.findSession("demo.remote:harness|new-1"))

        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.rename", any(), "sessions.invoke", any())
        } returns buildJsonObject {
            put("ok", true)
            put("title", "Renamed")
        }
        val renamed = service.renameSession("demo.remote:harness", "new-1", "Renamed")
        assertEquals("Renamed", renamed?.title)
        assertEquals("Renamed", store.findSession("demo.remote:harness|new-1")?.title)

        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.pin", any(), "sessions.invoke", any())
        } returns buildJsonObject { put("ok", true); put("isPinned", true) }
        val pinned = service.pinSession("demo.remote:harness", "new-1", true)
        assertTrue(pinned?.isPinned == true)
        assertTrue(store.findSession("demo.remote:harness|new-1")?.isPinned == true)

        coEvery {
            runtimeManager.invoke("demo.remote", "1.0.0", runtime, "sessions.delete", any(), "sessions.invoke", any())
        } returns buildJsonObject { put("ok", true) }
        assertTrue(service.deleteSession("demo.remote:harness", "new-1"))
        assertNull(store.findSession("demo.remote:harness|new-1"))
    }

    @Test
    fun hostService_validatesOwnershipAndUpdatesStore() {
        val store = ExternalSessionStore()
        val pluginManager = buildManager(active = true)
        val hostService = ExternalSessionHostService(pluginManager, store)

        hostService.upsert(
            "demo.remote",
            buildJsonObject {
                put("sourceId", "harness")
                put("sessions", buildJsonArray {
                    add(buildJsonObject {
                        put("sessionId", "s1")
                        put("title", "Host Pushed")
                        put("updatedAt", 500L)
                    })
                })
            }
        )
        assertEquals("Host Pushed", store.findSession("demo.remote:harness|s1")?.title)

        hostService.setThinking(
            "demo.remote",
            buildJsonObject {
                put("sourceId", "harness")
                put("sessionId", "s1")
                put("visible", true)
                put("text", "Analyzing...")
            }
        )
        assertEquals("Analyzing...", store.thinking.value["demo.remote:harness|s1"])

        hostService.remove(
            "demo.remote",
            buildJsonObject {
                put("sourceId", "harness")
                put("sessionIds", buildJsonArray { add(JsonPrimitive("s1")) })
            }
        )
        assertNull(store.findSession("demo.remote:harness|s1"))
        assertFalse(store.thinking.value.containsKey("demo.remote:harness|s1"))

        try {
            hostService.upsert(
                "demo.remote",
                buildJsonObject {
                    put("sourceId", "other-source")
                    put("sessions", buildJsonArray {})
                }
            )
            fail("expected SESSION_SOURCE_NOT_FOUND")
        } catch (e: PluginHostException) {
            assertEquals("SESSION_SOURCE_NOT_FOUND", e.code)
        }
    }
}
