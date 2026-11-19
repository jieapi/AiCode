package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginExternalSessionHandlersRef
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.session.ExternalSessionSource
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSessionStoreTest {

    private fun runtime(pluginId: String = "demo.remote") = PluginRuntime(
        pluginId = pluginId,
        type = "javascript",
        installDir = File("/tmp/$pluginId"),
        entryFile = File("/tmp/$pluginId/index.js"),
        command = listOf("node", "index.js")
    )

    private fun source(
        pluginId: String = "demo.remote",
        localId: String = "harness",
        pluginName: String = "Remote",
        title: String = "Harness"
    ) = ExternalSessionSource(
        key = "$pluginId:$localId",
        pluginId = pluginId,
        pluginVersion = "1.0.0",
        pluginName = pluginName,
        title = title,
        handlers = PluginExternalSessionHandlersRef(readMessages = "sessions.readMessages"),
        runtime = runtime(pluginId)
    )

    private fun summary(
        sessionId: String,
        title: String = sessionId,
        updatedAt: Long = 100L,
        isPinned: Boolean = false,
        sourceKey: String = "demo.remote:harness",
        pluginId: String = "demo.remote"
    ) = ExternalSessionSummary(
        id = ExternalSessionSummary.compositeId(sourceKey, sessionId),
        sessionId = sessionId,
        sourceKey = sourceKey,
        sourceTitle = "Harness",
        pluginId = pluginId,
        pluginName = "Remote",
        pluginVersion = "1.0.0",
        title = title,
        status = "idle",
        running = false,
        updatedAt = updatedAt,
        group = "default",
        isPinned = isPinned
    )

    @Test
    fun upsert_insertsUpdatesAndMaintainsPinnedUpdatedAtTitleOrder() {
        val store = ExternalSessionStore()
        store.ensureSource("demo.remote", "Remote", "demo.remote:harness", "Harness")

        store.upsert(
            "demo.remote",
            "harness",
            listOf(
                summary("s1", title = "Beta", updatedAt = 100L, isPinned = false),
                summary("s2", title = "Alpha", updatedAt = 200L, isPinned = false),
                summary("s3", title = "Pinned Old", updatedAt = 50L, isPinned = true),
                summary("s4", title = "Alpha Tie", updatedAt = 100L, isPinned = false)
            )
        )

        val firstOrder = store.groups.value.single().sessions.map { it.sessionId }
        assertEquals(listOf("s3", "s2", "s4", "s1"), firstOrder)

        // 增量更新 s1 为置顶且时间最新
        store.upsert(
            "demo.remote",
            "harness",
            listOf(summary("s1", title = "Beta Pinned", updatedAt = 300L, isPinned = true))
        )

        val secondOrder = store.groups.value.single().sessions.map { it.sessionId }
        assertEquals(listOf("s1", "s3", "s2", "s4"), secondOrder)
        assertEquals("Beta Pinned", store.findSession("demo.remote:harness|s1")?.title)
    }

    @Test
    fun remove_deletesExactSessionAndClearsThinking() {
        val store = ExternalSessionStore()
        store.syncSources(listOf(source()))
        store.upsert(
            "demo.remote",
            "harness",
            listOf(summary("s1"), summary("s2"))
        )
        store.setThinking("demo.remote:harness", "s1", "thinking about s1")
        store.setThinking("demo.remote:harness", "s2", "thinking about s2")

        assertEquals("thinking about s1", store.thinking.value["demo.remote:harness|s1"])

        store.remove("demo.remote", "harness", listOf("s1"))

        assertNull(store.findSession("demo.remote:harness|s1"))
        assertNotNull(store.findSession("demo.remote:harness|s2"))
        assertFalse(store.thinking.value.containsKey("demo.remote:harness|s1"))
        assertEquals("thinking about s2", store.thinking.value["demo.remote:harness|s2"])
    }

    @Test
    fun replaceAll_replacesSessionsAndCleansStaleThinking() {
        val store = ExternalSessionStore()
        store.syncSources(listOf(source()))
        store.upsert(
            "demo.remote",
            "harness",
            listOf(summary("old-1", updatedAt = 100L), summary("keep", updatedAt = 200L))
        )
        store.setThinking("demo.remote:harness", "old-1", "stale")
        store.setThinking("demo.remote:harness", "keep", "active")

        store.replaceAll(
            "demo.remote",
            "harness",
            listOf(
                summary("keep", title = "Kept", updatedAt = 250L),
                summary("new-1", title = "New", updatedAt = 300L, isPinned = true)
            )
        )

        val sessions = store.groups.value.single().sessions
        assertEquals(listOf("new-1", "keep"), sessions.map { it.sessionId })
        assertNull(store.findSession("demo.remote:harness|old-1"))
        assertFalse(store.thinking.value.containsKey("demo.remote:harness|old-1"))
        assertEquals("active", store.thinking.value["demo.remote:harness|keep"])
    }

    @Test
    fun updateSummary_loadingErrorThinkingAndUnregisterPlugin() {
        val store = ExternalSessionStore()
        store.syncSources(
            listOf(
                source(pluginId = "demo.remote", localId = "harness", pluginName = "Remote"),
                source(pluginId = "other.plugin", localId = "chat", pluginName = "Other", title = "Chat")
            )
        )
        store.upsert("demo.remote", "harness", listOf(summary("s1", title = "Old", updatedAt = 100L)))
        store.upsert(
            "other.plugin",
            "chat",
            listOf(summary("o1", sourceKey = "other.plugin:chat", pluginId = "other.plugin"))
        )

        store.updateSummary("demo.remote:harness", "s1") { it.copy(title = "Renamed", isPinned = true) }
        val updated = store.findSession("demo.remote:harness|s1")
        assertEquals("Renamed", updated?.title)
        assertTrue(updated?.isPinned == true)

        store.setLoading("demo.remote", "harness", true)
        store.setError("demo.remote", "harness", "network timeout")
        val group = store.groups.value.first { it.pluginId == "demo.remote" }
        assertTrue(group.loading)
        assertEquals("network timeout", group.error)

        store.setThinking("demo.remote:harness", "s1", "working")
        store.clearThinking("s1")
        assertTrue(store.thinking.value.isEmpty())

        store.setThinking("demo.remote:harness", "s1", "working again")
        store.setThinking("other.plugin:chat", "o1", "other thinking")
        store.unregisterPlugin("demo.remote")

        assertEquals(listOf("other.plugin"), store.groups.value.map { it.pluginId })
        assertNull(store.findSession("demo.remote:harness|s1"))
        assertFalse(store.thinking.value.containsKey("demo.remote:harness|s1"))
        assertEquals("other thinking", store.thinking.value["other.plugin:chat|o1"])
    }
}
