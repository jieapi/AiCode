package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import com.aicode.feature.plugin.domain.PluginSessionSourceHandlersRef
import com.aicode.feature.plugin.domain.PluginStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSessionSourceServiceTest {

    @Test
    fun listAndRead_parsePluginPayload() = runBlocking {
        val pluginManager = mockk<PluginManager>(relaxed = true)
        val runtimeManager = mockk<PluginRuntimeManager>(relaxed = true)
        val runtime = PluginRuntime(
            pluginId = "test.remote",
            type = "javascript",
            installDir = File("."),
            entryFile = File("main.js"),
            command = listOf("node", "main.js")
        )
        val handlers = PluginSessionSourceHandlersRef(
            listSessions = "sessionSource.listSessions",
            readSession = "sessionSource.readSession",
            sendMessage = "sessionSource.sendMessage",
            interruptSession = "sessionSource.interruptSession",
            createSession = "sessionSource.createSession"
        )
        val content = PluginContent(
            runtime = runtime,
            sessionSources = listOf(
                PluginSessionSourceDefinition(
                    pluginId = "test.remote",
                    localId = "remote",
                    title = LocalizedText.single("Remote"),
                    handlers = handlers,
                    pluginVersion = "1.0.0"
                )
            )
        )
        val record = PluginRecord(
            id = "test.remote",
            version = "1.0.0",
            enabled = true,
            installedAt = 0L,
            origin = "local",
            archiveSha256 = "",
            manifestSha256 = ""
        )
        val installed = InstalledPlugin(
            record = record,
            status = PluginStatus.OK,
            manifest = mockk<PluginManifest>(relaxed = true),
            content = content
        )
        every { pluginManager.plugins } returns MutableStateFlow(listOf(installed))
        every { pluginManager.ensureLoaded() } returns Unit

        val listJson = buildJsonObject {
            put("sessions", buildJsonArray {
                add(buildJsonObject {
                    put("sessionId", "session-1")
                    put("title", "Session 1")
                    put("status", "idle")
                    put("updatedAt", 123L)
                    put("hostName", "Host")
                    put("workspaceTitle", "Workspace")
                    put("group", "Host · Workspace")
                    put("capabilities", buildJsonArray { add(JsonPrimitive("read")); add(JsonPrimitive("send")) })
                })
            })
        }
        val readJson = buildJsonObject {
            put("sessionId", "session-1")
            put("title", "Session 1")
            put("status", "running")
            put("hasMore", false)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("id", "message-1")
                    put("role", "assistant")
                    put("createdAt", 456L)
                    put("status", "done")
                    put("blocks", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "hello")
                        })
                    })
                })
            })
        }
        coEvery {
            runtimeManager.invoke(any(), any(), any(), any(), any(), any(), any())
        } answers {
            if (arg<String>(3) == handlers.listSessions) listJson else readJson
        }

        val service = PluginSessionSourceService(pluginManager, runtimeManager, PluginSessionRegistry())
        val listed = service.listSessions()
        assertEquals(1, listed.sessions.size)
        assertEquals("test.remote:remote", listed.sessions.single().sourceKey)
        assertEquals("Session 1", listed.sessions.single().title)

        val page = service.readSession("test.remote:remote", "session-1")
        assertEquals(1, page.messages.size)
        assertEquals("hello", (page.messages.single().blocks.single() as com.aicode.feature.plugin.domain.session.PluginMessageBlock.Text).text)
        assertTrue(page.messages.single().id == "message-1")
    }
}
