package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginSessionSourceHandlersRef
import com.aicode.feature.plugin.domain.session.PluginSessionSource
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSessionRegistryTest {

    private fun source(key: String = "test.remote:ds-harness") = PluginSessionSource(
        key = key,
        pluginId = "test.remote",
        pluginVersion = "1.0.0",
        pluginName = "Test Remote",
        title = "DS Harness",
        handlers = PluginSessionSourceHandlersRef(
            listSessions = "sessionSource.listSessions",
            readSession = "sessionSource.readSession"
        ),
        runtime = PluginRuntime(
            pluginId = "test.remote",
            type = "javascript",
            installDir = File("."),
            entryFile = File("main.js"),
            command = listOf("node", "main.js")
        )
    )

    @Test
    fun placeholderGroup_isVisibleBeforeSessionsArrive() {
        val registry = PluginSessionRegistry()
        registry.syncSources(listOf(source()))

        val group = registry.groups.value.single()
        assertEquals("Test Remote", group.pluginName)
        assertTrue(group.loading)
        assertTrue(group.sessions.isEmpty())

        registry.setLoading("test.remote", "ds-harness", false)
        assertEquals(false, registry.groups.value.single().loading)

        val session = PluginSessionSummary(
            id = "test.remote:ds-harness|session-1",
            sessionId = "session-1",
            sourceKey = "test.remote:ds-harness",
            sourceTitle = "DS Harness",
            pluginId = "test.remote",
            pluginName = "Test Remote",
            pluginVersion = "1.0.0",
            title = "Session 1",
            status = "idle",
            running = false,
            updatedAt = 1L,
            group = "Host · Workspace",
            hostName = "Host",
            workspaceTitle = "Workspace"
        )
        registry.setSessions("test.remote", "ds-harness", listOf(session))
        val loaded = registry.groups.value.single()
        assertEquals(1, loaded.sessions.size)
        assertEquals(false, loaded.loading)
    }

    @Test
    fun thinking_isScopedToSummaryId() {
        val registry = PluginSessionRegistry()
        registry.setThinking("test.remote:ds-harness", "session-1", "正在读取文件…")
        assertEquals(
            "正在读取文件…",
            registry.thinking.value["test.remote:ds-harness|session-1"]
        )
        registry.clearThinking("session-1")
        assertNull(registry.thinking.value["test.remote:ds-harness|session-1"])
    }
}
