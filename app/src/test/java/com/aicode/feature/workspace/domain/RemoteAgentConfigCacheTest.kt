package com.aicode.feature.workspace.domain

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteAgentConfigCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = RemoteAgentConfigKey("host", 22, "user", "/home/user", "/srv/project", AgentConfigKind.SKILLS, "~/workspace/.aicode/skills", "/srv/project/.aicode/skills")
    private var now = 100L
    private val transport = FakeTransport()
    private fun engine(maxMemoryEntries: Int = 24, maxMemoryBytes: Long = 64L * 1024 * 1024) = RemoteAgentConfigCacheEngine(RemoteAgentConfigStore(temporary.root), transport, { now }, maxMemoryEntries = maxMemoryEntries, maxMemoryBytes = maxMemoryBytes)

    private class FakeTransport : RemoteAgentConfigTransport {
        var files = mapOf("demo/SKILL.md" to "first")
        var manifestFailure: IOException? = null
        var bodyFailure: IOException? = null
        var wrongBody = false
        var identityValid = true
        var beforeManifest: (() -> Unit)? = null
        var beforeBodies: (() -> Unit)? = null
        val manifests = AtomicInteger()
        val batches = AtomicInteger()
        val requested = ArrayList<Set<String>>()
        override fun checkIdentity(key: RemoteAgentConfigKey) {
            if (!identityValid) throw RemoteAgentConfigIdentityChangedException()
        }
        override fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint> {
            manifests.incrementAndGet()
            beforeManifest?.invoke()
            manifestFailure?.let { throw it }
            return files.mapValues { (_, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                RemoteAgentConfigFingerprint(RemoteAgentConfigProtocol.sha256(bytes), bytes.size)
            }
        }
        override fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String> {
            batches.incrementAndGet()
            requested.add(expected.keys.toSet())
            beforeBodies?.invoke()
            bodyFailure?.let { throw it }
            return expected.keys.associateWith { if (wrongBody) "incorrect" else files.getValue(it) }
        }
    }

    @Test
    fun ttlForceAndOfflineLookupReuseStableSnapshot() {
        val cache = engine()
        assertNull(cache.cachedDirectory(key))
        assertEquals(0, transport.manifests.get())
        val first = cache.directory(key)
        assertEquals(mapOf("~/workspace/.aicode/skills/demo/SKILL.md" to "first"), first.files)
        assertSame(first, cache.cachedDirectory(key))
        assertSame(first, cache.directory(key))
        assertEquals(1, transport.manifests.get())
        assertSame(first, cache.directory(key, true))
        assertEquals(2, transport.manifests.get())
        assertEquals(1, transport.batches.get())
        now += 2000
        assertSame(first, cache.directory(key))
        assertEquals(3, transport.manifests.get())
        assertEquals(1, transport.batches.get())
    }

    @Test
    fun restartRevalidatesMetadataWithoutRetransmittingUnchangedBodies() {
        val first = engine().directory(key)
        val restarted = engine()
        assertEquals(first, restarted.cachedDirectory(key))
        assertEquals(1, transport.manifests.get())
        val second = restarted.directory(key)
        assertEquals(first, second)
        assertEquals(2, transport.manifests.get())
        assertEquals(1, transport.batches.get())
    }

    @Test
    fun changesAreOneBatchAndDeletesInvalidateEntries() {
        val cache = engine()
        val first = cache.directory(key)
        transport.files = mapOf("demo/SKILL.md" to "second", "new/SKILL.md" to "new")
        val second = cache.directory(key, true)
        assertNotEquals(first.revision, second.revision)
        assertEquals(setOf("demo/SKILL.md", "new/SKILL.md"), transport.requested.last())
        assertEquals(2, transport.batches.get())
        transport.files = mapOf("new/SKILL.md" to "new")
        val deleted = cache.directory(key, true)
        assertEquals(mapOf("~/workspace/.aicode/skills/new/SKILL.md" to "new"), deleted.files)
        assertNotEquals(second.revision, deleted.revision)
        assertEquals(2, transport.batches.get())
        assertEquals(deleted, engine().cachedDirectory(key))
    }

    @Test
    fun failedManifestAndPartialBatchPreserveSnapshotAndRemainStale() {
        val cache = engine()
        val first = cache.directory(key)
        transport.manifestFailure = IOException("offline")
        assertSame(first, cache.directory(key, true))
        assertSame(first, cache.directory(key))
        assertEquals(3, transport.manifests.get())
        transport.manifestFailure = null
        transport.files = mapOf("demo/SKILL.md" to "second")
        transport.bodyFailure = IOException("batch interrupted")
        assertSame(first, cache.directory(key))
        assertEquals(first, engine().cachedDirectory(key))
        transport.bodyFailure = null
        val recovered = cache.directory(key)
        assertNotEquals(first.revision, recovered.revision)
        assertEquals(3, transport.batches.get())
    }

    @Test
    fun firstFailureThrowsButExplicitEmptyManifestCanBeCached() {
        transport.manifestFailure = IOException("offline")
        val cache = engine()
        assertThrows(IOException::class.java) { cache.directory(key) }
        assertNull(cache.cachedDirectory(key))
        transport.manifestFailure = null
        transport.files = emptyMap()
        val empty = cache.directory(key)
        assertTrue(empty.files.isEmpty())
        assertEquals(0, transport.batches.get())
        assertEquals(empty, engine().cachedDirectory(key))
    }

    @Test
    fun wrongBodyHashDoesNotReplaceMemoryOrDisk() {
        val cache = engine()
        val first = cache.directory(key)
        transport.files = mapOf("demo/SKILL.md" to "updated")
        transport.wrongBody = true
        assertSame(first, cache.directory(key, true))
        assertSame(first, cache.directory(key))
        assertEquals(first, engine().cachedDirectory(key))
        assertEquals(3, transport.manifests.get())
    }

    @Test
    fun identityChangeNeverReturnsFallbackEvenWhenNetworkFails() {
        val cache = engine()
        cache.directory(key)
        transport.beforeManifest = { transport.identityValid = false }
        transport.manifestFailure = IOException("disconnected")
        assertThrows(RemoteAgentConfigIdentityChangedException::class.java) { cache.directory(key, true) }
        transport.identityValid = true
        transport.beforeManifest = null
        transport.manifestFailure = null
        transport.beforeBodies = { transport.identityValid = false }
        transport.files = mapOf("demo/SKILL.md" to "updated")
        assertThrows(RemoteAgentConfigIdentityChangedException::class.java) { cache.directory(key, true) }
        transport.identityValid = true
        assertEquals("first", engine().cachedDirectory(key)!!.files.values.single())
    }

    @Test
    fun invalidateOnlyAffectedDirectoriesAndKeepsBodyCache() {
        val cache = engine()
        val agents = key.copy(kind = AgentConfigKind.AGENTS, root = "~/workspace/.aicode/agents", remoteRoot = "/srv/project/.aicode/agents")
        cache.directory(key)
        cache.directory(agents)
        cache.invalidate(listOf("/srv/project/.aicode/skills/demo"))
        cache.directory(agents)
        assertEquals(2, transport.manifests.get())
        cache.directory(key)
        assertEquals(3, transport.manifests.get())
        assertEquals(2, transport.batches.get())
        cache.invalidate(listOf("~/workspace/.aicode/agents.json"))
        cache.directory(key)
        assertEquals(3, transport.manifests.get())
        cache.directory(agents)
        assertEquals(4, transport.manifests.get())
        cache.invalidate(listOf("/srv/project/.aicode"))
        cache.directory(key)
        cache.directory(agents)
        assertEquals(6, transport.manifests.get())
        cache.invalidate()
        cache.directory(key)
        cache.directory(agents)
        assertEquals(8, transport.manifests.get())
        assertEquals(2, transport.batches.get())
    }

    @Test
    fun identitySeparatesServersUsersHomesWorkspacesKindsAndRootsButNotReconnects() {
        val variants = listOf(key, key.copy(host = "other"), key.copy(port = 2200), key.copy(username = "other"), key.copy(home = "/other"), key.copy(workspace = "/other"), key.copy(kind = AgentConfigKind.AGENTS), key.copy(root = "~/other"), key.copy(remoteRoot = "/other"))
        assertEquals(variants.size, variants.map { it.identity }.toSet().size)
        val cache = engine()
        variants.forEachIndexed { index, variant ->
            transport.files = mapOf("demo/SKILL.md" to "value-$index")
            cache.directory(variant)
        }
        variants.forEachIndexed { index, variant ->
            assertEquals("value-$index", engine().cachedDirectory(variant)!!.files.values.single())
            assertTrue(File(temporary.root, variant.identity).isDirectory)
        }
        assertEquals(key.identity, key.copy(connectionGeneration = 7).identity)
        assertEquals(variants.size, transport.manifests.get())
    }

    @Test
    fun corruptDiskIsIgnoredAndRepairedOnSuccessfulRefresh() {
        val first = engine().directory(key)
        val blob = File(File(temporary.root, key.identity), RemoteAgentConfigProtocol.sha256("first".toByteArray()) + ".body")
        blob.writeText("wrong")
        val restarted = engine()
        assertNull(restarted.cachedDirectory(key))
        assertEquals(first, restarted.directory(key))
        assertEquals(first, engine().cachedDirectory(key))
        assertEquals(2, transport.batches.get())
    }

    @Test
    fun idleMemoryEntriesAreEvictedWhileDiskRemainsAvailable() {
        val cache = engine(maxMemoryEntries = 2)
        val first = cache.directory(key)
        cache.directory(key.copy(root = "~/two", remoteRoot = "/two"))
        cache.directory(key.copy(root = "~/three", remoteRoot = "/three"))
        val reloaded = cache.cachedDirectory(key)
        assertNotSame(first, reloaded)
        assertEquals(first, reloaded)
        assertEquals(3, transport.manifests.get())
    }

    @Test
    fun explicitInvalidationDisablesOfflineLookupUntilSuccessfulValidationEvenAfterEviction() {
        val cache = engine(maxMemoryEntries = 1)
        val first = cache.directory(key)
        now += 2000
        assertSame(first, cache.cachedDirectory(key))
        cache.invalidate(listOf("/srv/project/.aicode/skills/demo/SKILL.md"))
        assertNull(cache.cachedDirectory(key))
        transport.manifestFailure = IOException("offline")
        assertSame(first, cache.directory(key))
        assertNull(cache.cachedDirectory(key))
        cache.cachedDirectory(key.copy(root = "~/other", remoteRoot = "/other"))
        assertNull(cache.cachedDirectory(key))
        transport.manifestFailure = null
        assertEquals(first, cache.directory(key))
        assertEquals(first, cache.cachedDirectory(key))
        assertEquals(1, transport.batches.get())
    }

    @Test
    fun memoryByteBudgetEvictsIdleContentBeforeEntryCountLimit() {
        val cache = engine(maxMemoryBytes = 12)
        val first = cache.directory(key)
        cache.directory(key.copy(root = "~/other", remoteRoot = "/other"))
        assertNotSame(first, cache.cachedDirectory(key))
        assertEquals(first, cache.cachedDirectory(key))
        assertEquals(2, transport.manifests.get())
    }

    @Test
    fun contentAddressedBodiesAreNotRewrittenWhenOtherFilesChange() {
        val cache = engine()
        cache.directory(key)
        val blob = File(File(temporary.root, key.identity), RemoteAgentConfigProtocol.sha256("first".toByteArray()) + ".body")
        assertTrue(blob.setLastModified(1_000_000))
        transport.files = transport.files + ("new/SKILL.md" to "new")
        cache.directory(key, true)
        assertEquals(1_000_000L, blob.lastModified())
        assertEquals(setOf("new/SKILL.md"), transport.requested.last())
    }

    @Test
    fun concurrentDirectoryRefreshIsOneManifestAndOneBodyBatch() {
        val cache = engine()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(8)
        transport.beforeManifest = { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..8).map { pool.submit<AgentConfigSnapshot> { ready.countDown(); cache.directory(key) } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            val results = calls.map { it.get(5, TimeUnit.SECONDS) }
            results.forEach { assertSame(results.first(), it) }
            assertEquals(1, transport.manifests.get())
            assertEquals(1, transport.batches.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun invalidationDuringRefreshRemainsStaleAndFlightIsNotEvicted() {
        val cache = engine(maxMemoryEntries = 1)
        cache.directory(key)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.beforeManifest = { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val refresh = pool.submit<AgentConfigSnapshot> { cache.directory(key, true) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.cachedDirectory(key.copy(root = "~/other", remoteRoot = "/other"))
            cache.invalidate(listOf("/srv/project/.aicode/skills/demo/SKILL.md"))
            release.countDown()
            val result = refresh.get(5, TimeUnit.SECONDS)
            assertNull(cache.cachedDirectory(key))
            cache.directory(key)
            assertSame(result, cache.cachedDirectory(key))
            assertEquals(3, transport.manifests.get())
            assertEquals(1, transport.batches.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
