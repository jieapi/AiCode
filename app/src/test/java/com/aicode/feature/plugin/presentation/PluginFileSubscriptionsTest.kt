package com.aicode.feature.plugin.presentation

import com.aicode.feature.plugin.domain.PluginHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginFileSubscriptionsTest {

    @Test
    fun add_whileInactiveValidatesPathsWithoutCollecting() = runTest {
        val probe = WatchProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch) { events += it }

        subscriptions.add("settings", "data://settings/layout.json", false)
        subscriptions.add("sources", "workspace://src/components", true)
        runCurrent()

        assertEquals(
            listOf("data://settings/layout.json" to false, "workspace://src/components" to true),
            probe.calls
        )
        assertTrue(probe.started.isEmpty())
        assertTrue(probe.finished.isEmpty())
        assertTrue(events.isEmpty())

        subscriptions.close()
        runCurrent()
        assertTrue(probe.started.isEmpty())
    }

    @Test
    fun add_rejectsInvalidPathsBeforeRetainingTheId() = runTest {
        val probe = WatchProbe()
        val subscriptions = PluginFileSubscriptions(backgroundScope, { path, recursive ->
            if (path == "data://blocked") throw PluginHostException("PATH_DENIED", "blocked path")
            probe.watch(path, recursive)
        }, {})

        assertCode("PATH_DENIED") { subscriptions.add("notes", "data://blocked", false) }
        subscriptions.add("notes", "data://notes", false)
        subscriptions.setActive(true)
        runCurrent()

        assertEquals(1, probe.started["data://notes"])
        subscriptions.close()
        runCurrent()
        assertEquals(probe.started, probe.finished)
    }

    @Test
    fun setActive_collectsExistingAndNewRegistrationsAndAddsTrustedMetadata() = runTest {
        val probe = WatchProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch) { events += it }
        subscriptions.add("first", "data://preferences.json", false)
        subscriptions.setActive(true)
        runCurrent()
        subscriptions.add("second", "workspace://docs", true)
        runCurrent()

        val payload = buildJsonObject {
            put("marker", "updated")
            put("subscriptionId", "untrusted-id")
            put("path", "untrusted-path")
            put("contextKey", "workspace-a")
        }
        probe.send("data://preferences.json", payload)
        probe.send("workspace://docs", change("docs-updated"))
        runCurrent()

        assertEquals(1, probe.started["data://preferences.json"])
        assertEquals(1, probe.started["workspace://docs"])
        assertEquals(listOf("first", "second"), events.map { it["subscriptionId"]!!.jsonPrimitive.content })
        assertEquals(listOf("data://preferences.json", "workspace://docs"), events.map { it["path"]!!.jsonPrimitive.content })
        assertEquals("updated", events.first()["marker"]!!.jsonPrimitive.content)
        assertEquals("workspace-a", events.first()["contextKey"]!!.jsonPrimitive.content)
        assertEquals("untrusted-id", payload["subscriptionId"]!!.jsonPrimitive.content)

        subscriptions.close()
        runCurrent()
        assertEquals(probe.started, probe.finished)
    }

    @Test
    fun hide_releasesCollectorsAndShowRecreatesThemWithoutRepeatingActiveState() = runTest {
        val probe = WatchProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch) { events += it }
        val path = "workspace://notes/project.md"
        subscriptions.add("notes", path, false)
        subscriptions.setActive(true)
        runCurrent()
        subscriptions.setActive(true)
        runCurrent()
        assertEquals(1, probe.started[path])

        subscriptions.setActive(false)
        runCurrent()
        assertEquals(1, probe.finished[path])
        probe.send(path, change("hidden"))
        runCurrent()
        assertTrue(events.isEmpty())

        subscriptions.setActive(false)
        runCurrent()
        assertEquals(1, probe.finished[path])
        subscriptions.setActive(true)
        runCurrent()
        probe.send(path, change("shown"))
        runCurrent()
        assertEquals(2, probe.started[path])
        assertEquals(listOf(change("shown", "notes", path)), events)

        subscriptions.close()
        runCurrent()
        assertEquals(probe.started, probe.finished)
    }

    @Test
    fun hide_cancelsDelayedWorkInsteadOfSendingItAfterResume() = runTest {
        var started = 0
        var finished = 0
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, { _, _ ->
            flow {
                started++
                try {
                    delay(1_000)
                    emit(change("ready"))
                    awaitCancellation()
                } finally {
                    finished++
                }
            }
        }) { events += it }
        val path = "data://exports"
        subscriptions.add("export", path, true)
        subscriptions.setActive(true)
        runCurrent()
        advanceTimeBy(400)
        subscriptions.setActive(false)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(1, finished)

        subscriptions.setActive(true)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(change("ready", "export", path)), events)
        assertEquals(2, started)

        subscriptions.close()
        runCurrent()
        assertEquals(2, finished)
    }

    @Test
    fun remove_isIdempotentAndOnlyCancelsTheSelectedId() = runTest {
        val probe = WatchProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch) { events += it }
        val path = "data://shared.json"
        subscriptions.add("left", path, false)
        subscriptions.add("right", path, false)
        subscriptions.setActive(true)
        runCurrent()
        assertEquals(2, probe.started[path])

        probe.send(path, change("both"))
        runCurrent()
        assertEquals(setOf("left", "right"), events.map { it["subscriptionId"]!!.jsonPrimitive.content }.toSet())
        events.clear()
        subscriptions.remove("left")
        subscriptions.remove("left")
        subscriptions.remove("unknown")
        runCurrent()
        assertEquals(1, probe.finished[path])
        probe.send(path, change("right-only"))
        runCurrent()
        assertEquals(listOf(change("right-only", "right", path)), events)

        subscriptions.close()
        runCurrent()
        assertEquals(2, probe.finished[path])
    }

    @Test
    fun remove_whileInactivePreventsCollectionOnShow() = runTest {
        val probe = WatchProbe()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch, {})
        subscriptions.add("temporary", "data://temporary", false)
        subscriptions.remove("temporary")
        subscriptions.setActive(true)
        runCurrent()

        assertTrue(probe.started.isEmpty())
        subscriptions.close()
    }

    @Test
    fun restart_releasesCurrentCollectorsAndRejectsCallbacksFromOlderGenerations() = runTest {
        val probe = LateCallbackProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, { _, _ -> probe.watch() }) { events += it }
        val path = "workspace://src"
        subscriptions.add("sources", path, true)
        subscriptions.setActive(true)
        runCurrent()
        val first = probe.callbacks.single()
        first(change("first"))

        subscriptions.setActive(true, restart = true)
        runCurrent()
        assertEquals(1, probe.finished)
        assertEquals(2, probe.callbacks.size)
        val second = probe.callbacks.last()
        first(change("stale-after-restart"))
        second(change("second"))

        subscriptions.setActive(false)
        runCurrent()
        second(change("hidden"))
        subscriptions.setActive(true)
        runCurrent()
        first(change("stale-after-show"))
        second(change("stale-after-show"))
        probe.callbacks.last()(change("third"))
        assertEquals(
            listOf(change("first", "sources", path), change("second", "sources", path), change("third", "sources", path)),
            events
        )

        subscriptions.close()
        runCurrent()
        probe.callbacks.forEach { it(change("closed")) }
        assertEquals(3, events.size)
        assertEquals(3, probe.finished)
    }

    @Test
    fun removeAndReadd_rejectsCallbacksFromThePreviousEntryWithTheSameId() = runTest {
        val probe = LateCallbackProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, { _, _ -> probe.watch() }) { events += it }
        subscriptions.setActive(true)
        subscriptions.add("same", "data://old.json", false)
        runCurrent()
        val previous = probe.callbacks.single()

        subscriptions.remove("same")
        subscriptions.add("same", "workspace://new.json", false)
        runCurrent()
        previous(change("stale"))
        probe.callbacks.last()(change("current"))

        assertEquals(listOf(change("current", "same", "workspace://new.json")), events)
        assertEquals(1, probe.finished)
        subscriptions.close()
        runCurrent()
        assertEquals(2, probe.finished)
    }

    @Test
    fun close_releasesAllCollectorsAndPermanentlyRejectsNewRegistrations() = runTest {
        val probe = WatchProbe()
        val events = mutableListOf<JsonObject>()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch) { events += it }
        subscriptions.add("one", "data://one", false)
        subscriptions.add("two", "workspace://two", true)
        subscriptions.setActive(true)
        runCurrent()
        val callsBeforeClose = probe.calls.size

        subscriptions.close()
        subscriptions.close()
        runCurrent()
        subscriptions.setActive(true)
        subscriptions.setActive(true, restart = true)
        subscriptions.remove("one")
        assertCode("PAGE_CLOSED") { subscriptions.add("new", "data://new", false) }
        runCurrent()
        probe.send("data://one", change("closed"))
        runCurrent()

        assertEquals(probe.started, probe.finished)
        assertEquals(callsBeforeClose, probe.calls.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun collectorFailures_emitStructuredErrorsWithoutStoppingOtherSubscriptions() = runTest {
        val events = mutableListOf<JsonObject>()
        var finished = 0
        val subscriptions = PluginFileSubscriptions(backgroundScope, { path, _ ->
            flow {
                try {
                    when (path) {
                        "data://denied" -> throw PluginHostException("PATH_DENIED", "access denied")
                        "data://failed" -> throw IllegalStateException("transport unavailable")
                        else -> {
                            emit(change("healthy"))
                            awaitCancellation()
                        }
                    }
                } finally {
                    finished++
                }
            }
        }) { events += it }
        subscriptions.add("denied", "data://denied", false)
        subscriptions.add("failed", "data://failed", false)
        subscriptions.add("healthy", "workspace://src", true)
        subscriptions.setActive(true)
        runCurrent()

        val byId = events.associateBy { it["subscriptionId"]!!.jsonPrimitive.content }
        assertEquals(3, byId.size)
        val denied = byId.getValue("denied")
        assertEquals("data://denied", denied["path"]!!.jsonPrimitive.content)
        assertEquals("PATH_DENIED", denied["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("access denied", denied["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        val failed = byId.getValue("failed")
        assertEquals("WATCH_FAILED", failed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("transport unavailable", failed["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(change("healthy", "healthy", "workspace://src"), byId.getValue("healthy"))
        assertEquals(2, finished)

        subscriptions.close()
        runCurrent()
        assertEquals(3, finished)
    }

    @Test
    fun cancellation_isNotReportedAsAWatchFailure() = runTest {
        val events = mutableListOf<JsonObject>()
        val jobs = mutableListOf<Job>()
        var finished = 0
        val subscriptions = PluginFileSubscriptions(backgroundScope, { _, _ ->
            flow<JsonObject> {
                jobs += currentCoroutineContext().job
                try {
                    throw CancellationException("watch cancelled")
                } finally {
                    finished++
                }
            }
        }) { events += it }
        subscriptions.add("cancelled", "data://cache", false)
        subscriptions.setActive(true)
        runCurrent()

        assertEquals(1, finished)
        assertTrue(jobs.single().isCancelled)
        assertTrue(events.isEmpty())
        subscriptions.setActive(true, restart = true)
        runCurrent()
        assertEquals(2, finished)
        assertTrue(jobs.all { it.isCancelled })
        assertTrue(events.isEmpty())
        subscriptions.close()
    }

    @Test
    fun add_acceptsValidIdsAndRejectsInvalidOrDuplicateIdsBeforeWatching() = runTest {
        val probe = WatchProbe()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch, {})
        val valid = listOf("a", "Z", "0", "_", "-", "Ab09_-", "a".repeat(64))
        valid.forEach { subscriptions.add(it, "data://ids", false) }
        assertEquals(valid.size, probe.calls.size)

        for (id in listOf("", "a".repeat(65), "a b", "a.b", "a/b", "a:b", "id\n", "订阅")) {
            assertCode("PARAM_INVALID") { subscriptions.add(id, "data://invalid", false) }
        }
        assertCode("PARAM_INVALID") { subscriptions.add("a", "workspace://duplicate", true) }
        assertEquals(valid.size, probe.calls.size)
        assertTrue(probe.started.isEmpty())
        subscriptions.close()
    }

    @Test
    fun add_limitsRegistrationsToSixteenAndRemovalReleasesCapacity() = runTest {
        val probe = WatchProbe()
        val subscriptions = PluginFileSubscriptions(backgroundScope, probe::watch, {})
        repeat(16) { subscriptions.add("watch-$it", "data://entry-$it", false) }
        assertCode("TOO_MANY_WATCHES") { subscriptions.add("overflow", "data://overflow", false) }
        assertEquals(16, probe.calls.size)
        assertTrue(probe.started.isEmpty())

        subscriptions.remove("watch-7")
        subscriptions.add("replacement", "workspace://replacement", true)
        assertEquals(17, probe.calls.size)
        subscriptions.setActive(true)
        runCurrent()
        assertEquals(16, probe.started.values.sum())
        assertTrue("data://entry-7" !in probe.started)
        subscriptions.close()
        runCurrent()
        assertEquals(probe.started, probe.finished)
    }

    private fun assertCode(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(PluginHostException::class.java) { block() }.code)
    }

    private fun change(marker: String, id: String? = null, path: String? = null): JsonObject = buildJsonObject {
        put("marker", marker)
        if (id != null) put("subscriptionId", id)
        if (path != null) put("path", path)
    }

    private class WatchProbe {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val started = mutableMapOf<String, Int>()
        val finished = mutableMapOf<String, Int>()
        private val updates = mutableMapOf<String, MutableSharedFlow<JsonObject>>()

        fun watch(path: String, recursive: Boolean): Flow<JsonObject> {
            calls += path to recursive
            return flow {
                started[path] = started.getOrDefault(path, 0) + 1
                try {
                    updates.getOrPut(path) { MutableSharedFlow() }.collect { emit(it) }
                } finally {
                    finished[path] = finished.getOrDefault(path, 0) + 1
                }
            }
        }

        suspend fun send(path: String, payload: JsonObject) {
            updates.getValue(path).emit(payload)
        }
    }

    private class LateCallbackProbe {
        val callbacks = mutableListOf<suspend (JsonObject) -> Unit>()
        var finished = 0

        @OptIn(InternalCoroutinesApi::class)
        fun watch(): Flow<JsonObject> = object : Flow<JsonObject> {
            override suspend fun collect(collector: FlowCollector<JsonObject>) {
                callbacks += { payload -> collector.emit(payload) }
                try {
                    awaitCancellation()
                } finally {
                    finished++
                }
            }
        }
    }
}
