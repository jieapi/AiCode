package com.aicode.feature.workspace.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

internal data class RemoteAgentConfigKey(
    val host: String,
    val port: Int,
    val username: String,
    val home: String,
    val workspace: String,
    val kind: AgentConfigKind,
    val root: String,
    val remoteRoot: String,
    val connectionGeneration: Long = 0
) {
    val identity: String = RemoteAgentConfigProtocol.sha256(
        listOf("remote-agent-config-v1", host, port.toString(), username, home, workspace, kind.name, root, remoteRoot)
            .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
    )

    fun displayPath(relative: String): String = root.trimEnd('/') + "/" + relative

    fun affectedBy(path: String): Boolean {
        fun overlaps(left: String, right: String): Boolean = left == right ||
            left.startsWith(right.trimEnd('/') + "/") || right.startsWith(left.trimEnd('/') + "/")
        if (overlaps(path, root) || overlaps(path, remoteRoot)) return true
        val settingsName = if (kind == AgentConfigKind.SKILLS) "skills.json" else "agents.json"
        if (!path.substringAfterLast('/').equals(settingsName, true)) return false
        val parent = path.substringBeforeLast('/', "")
        return parent == root.substringBeforeLast('/', "") || parent == remoteRoot.substringBeforeLast('/', "")
    }
}

internal class RemoteAgentConfigIdentityChangedException : IOException("Remote configuration connection identity changed")

internal interface RemoteAgentConfigTransport {
    fun checkIdentity(key: RemoteAgentConfigKey) = Unit
    fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint>
    fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String>
}

internal data class RemoteAgentConfigState(
    val snapshot: AgentConfigSnapshot,
    val fingerprints: Map<String, RemoteAgentConfigFingerprint>,
    val contents: Map<String, String>
)

internal class RemoteAgentConfigStore(private val cacheRoot: File) {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(key: RemoteAgentConfigKey): RemoteAgentConfigState? = try {
        val directory = File(cacheRoot, key.identity)
        val manifest = File(directory, "snapshot.json")
        if (!manifest.isFile) null else {
            val record = json.decodeFromString<PersistedSnapshot>(
                manifest.inputStream().use { RemoteAgentConfigProtocol.strictText(RemoteAgentConfigProtocol.readLimited(it, RemoteAgentConfigProtocol.MAX_MANIFEST_BYTES)) }
            )
            if (record.version != 1 || record.identity != key.identity || record.files.size > RemoteAgentConfigProtocol.MAX_FILES) {
                throw IOException("Invalid persisted remote configuration identity")
            }
            val fingerprints = linkedMapOf<String, RemoteAgentConfigFingerprint>()
            val contents = linkedMapOf<String, String>()
            var total = 0L
            record.files.forEach { file ->
                RemoteAgentConfigProtocol.request(listOf(file.path))
                if (!Regex("[0-9a-f]{64}").matches(file.sha256) || file.size !in 0..RemoteAgentConfigProtocol.MAX_FILE_BYTES ||
                    fingerprints.containsKey(file.path)
                ) throw IOException("Invalid persisted remote configuration entry")
                total += file.size
                if (total > RemoteAgentConfigProtocol.MAX_TOTAL_BYTES) throw IOException("Persisted remote configuration exceeds limit")
                val bytes = File(directory, "${file.sha256}.body").inputStream().use {
                    RemoteAgentConfigProtocol.readLimited(it, RemoteAgentConfigProtocol.MAX_FILE_BYTES)
                }
                if (bytes.size != file.size || RemoteAgentConfigProtocol.sha256(bytes) != file.sha256) {
                    throw IOException("Corrupt persisted remote configuration body")
                }
                fingerprints[file.path] = RemoteAgentConfigFingerprint(file.sha256, file.size)
                contents[file.path] = RemoteAgentConfigProtocol.strictText(bytes)
            }
            state(key, fingerprints, contents).also {
                if (it.snapshot.revision != record.revision) throw IOException("Corrupt persisted remote configuration revision")
            }
        }
    } catch (e: IOException) {
        if (e is java.io.InterruptedIOException) throw e
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun save(key: RemoteAgentConfigKey, state: RemoteAgentConfigState) {
        val directory = File(cacheRoot, key.identity)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create remote configuration cache")
        state.fingerprints.forEach { (path, fingerprint) ->
            val target = File(directory, "${fingerprint.sha256}.body")
            val valid = try {
                target.isFile && target.length() == fingerprint.size.toLong() && target.inputStream().use {
                    RemoteAgentConfigProtocol.sha256(RemoteAgentConfigProtocol.readLimited(it, RemoteAgentConfigProtocol.MAX_FILE_BYTES)) == fingerprint.sha256
                }
            } catch (e: IOException) {
                if (e is java.io.InterruptedIOException) throw e
                false
            }
            if (!valid) atomicWrite(directory, target.name, state.contents.getValue(path).toByteArray(Charsets.UTF_8))
        }
        val record = PersistedSnapshot(
            identity = key.identity,
            revision = state.snapshot.revision,
            files = state.fingerprints.map { (path, fingerprint) -> PersistedFile(path, fingerprint.sha256, fingerprint.size) }
        )
        atomicWrite(directory, "snapshot.json", json.encodeToString(record).toByteArray(Charsets.UTF_8))
        val liveBodies = state.fingerprints.values.mapTo(hashSetOf()) { "${it.sha256}.body" }
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".body") && file.name !in liveBodies) file.delete()
        }
    }

    private fun atomicWrite(directory: File, name: String, bytes: ByteArray) {
        val temporary = File.createTempFile("config-", ".tmp", directory)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        fun state(
            key: RemoteAgentConfigKey,
            fingerprints: Map<String, RemoteAgentConfigFingerprint>,
            contents: Map<String, String>
        ): RemoteAgentConfigState {
            if (fingerprints.keys != contents.keys) throw IOException("Incomplete remote configuration snapshot")
            val sorted = fingerprints.toSortedMap()
            val revision = RemoteAgentConfigProtocol.sha256(
                buildString {
                    append(key.identity)
                    sorted.forEach { (path, fingerprint) ->
                        append(path.toByteArray(Charsets.UTF_8).size).append(':').append(path)
                        append(':').append(fingerprint.sha256).append(':').append(fingerprint.size).append(';')
                    }
                }.toByteArray(Charsets.UTF_8)
            )
            val files = Collections.unmodifiableMap(sorted.keys.associate { key.displayPath(it) to contents.getValue(it) })
            return RemoteAgentConfigState(
                AgentConfigSnapshot(key.identity, revision, files),
                Collections.unmodifiableMap(sorted),
                Collections.unmodifiableMap(contents.toMap())
            )
        }
    }

    @Serializable
    private data class PersistedSnapshot(
        val version: Int = 1,
        val identity: String,
        val revision: String,
        val files: List<PersistedFile>
    )

    @Serializable
    private data class PersistedFile(val path: String, val sha256: String, val size: Int)
}

internal class RemoteAgentConfigCacheEngine(
    private val store: RemoteAgentConfigStore,
    private val transport: RemoteAgentConfigTransport,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val freshnessMillis: Long = 2000,
    private val maxMemoryEntries: Int = 24,
    private val maxMemoryBytes: Long = 64L * 1024 * 1024
) {
    private class Entry {
        var loaded = false
        @Volatile var state: RemoteAgentConfigState? = null
        var fresh = false
        var invalidated = false
        var checkedAt = 0L
        var generation = 0L
        var users = 0
        var lastAccess = 0L
        var flight: CompletableFuture<AgentConfigSnapshot>? = null
    }

    private val entries = ConcurrentHashMap<RemoteAgentConfigKey, Entry>()
    private val invalidatedIdentities = ConcurrentHashMap.newKeySet<String>()
    private var accessSequence = 0L

    private fun <T> withEntry(key: RemoteAgentConfigKey, action: (Entry) -> T): T {
        val entry = synchronized(entries) {
            entries.computeIfAbsent(key) { Entry() }.also {
                it.users++
                it.lastAccess = ++accessSequence
            }
        }
        try {
            return action(entry)
        } finally {
            synchronized(entries) {
                entry.users--
                fun bytes(value: Entry): Long = value.state?.contents?.values?.sumOf { it.length.toLong() * 2 } ?: 0L
                var totalBytes = entries.values.sumOf(::bytes)
                for ((idleKey, idle) in entries.entries.filter { it.value.users == 0 }.sortedBy { it.value.lastAccess }) {
                    if (entries.size <= maxMemoryEntries && totalBytes <= maxMemoryBytes) break
                    if (entries.remove(idleKey, idle)) totalBytes -= bytes(idle)
                }
            }
        }
    }

    fun cachedDirectory(key: RemoteAgentConfigKey): AgentConfigSnapshot? = withEntry(key) { entry ->
        transport.checkIdentity(key)
        synchronized(entry) {
            if (!entry.loaded) {
                entry.state = store.load(key)
                entry.loaded = true
            }
            entry.state?.snapshot?.takeUnless { entry.invalidated || key.identity in invalidatedIdentities }.also { transport.checkIdentity(key) }
        }
    }

    fun directory(key: RemoteAgentConfigKey, forceRefresh: Boolean = false): AgentConfigSnapshot =
        withEntry(key) { entry ->
            transport.checkIdentity(key)
            directory(entry, key, forceRefresh).also { transport.checkIdentity(key) }
        }

    private fun directory(entry: Entry, key: RemoteAgentConfigKey, forceRefresh: Boolean): AgentConfigSnapshot {
        var owner = false
        val generation: Long
        val previous: RemoteAgentConfigState?
        val flight: CompletableFuture<AgentConfigSnapshot>
        synchronized(entry) {
            if (!entry.loaded) {
                entry.state = store.load(key)
                entry.loaded = true
            }
            val age = clockMillis() - entry.checkedAt
            if (!forceRefresh && entry.fresh && key.identity !in invalidatedIdentities && age in 0 until freshnessMillis) return entry.state!!.snapshot
            previous = entry.state
            generation = entry.generation
            flight = entry.flight ?: CompletableFuture<AgentConfigSnapshot>().also {
                entry.flight = it
                owner = true
            }
        }
        if (!owner) return await(flight)
        try {
            transport.checkIdentity(key)
            val fingerprints = transport.manifest(key)
            val changed = fingerprints.filter { (path, fingerprint) -> previous?.fingerprints?.get(path) != fingerprint }
            val bodies = if (changed.isEmpty()) emptyMap() else transport.bodies(key, changed)
            if (bodies.keys != changed.keys) throw IOException("Incomplete remote configuration batch")
            bodies.forEach { (path, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val fingerprint = changed.getValue(path)
                if (bytes.size != fingerprint.size || RemoteAgentConfigProtocol.sha256(bytes) != fingerprint.sha256) {
                    throw IOException("Remote configuration changed during batch read")
                }
            }
            transport.checkIdentity(key)
            val state = if (previous != null && previous.fingerprints == fingerprints) previous else {
                val contents = fingerprints.keys.associateWith { path ->
                    bodies[path] ?: previous?.contents?.get(path) ?: throw IOException("Missing remote configuration body")
                }
                RemoteAgentConfigStore.state(key, fingerprints, contents)
            }
            transport.checkIdentity(key)
            if (state !== previous) store.save(key, state)
            transport.checkIdentity(key)
            synchronized(entry) {
                entry.state = state
                entry.checkedAt = clockMillis()
                entry.fresh = entry.generation == generation
                if (entry.fresh) {
                    entry.invalidated = false
                    invalidatedIdentities.remove(key.identity)
                }
                flight.complete(state.snapshot)
                entry.flight = null
            }
            return state.snapshot
        } catch (e: Exception) {
            val interrupted = e is InterruptedException || e is java.io.InterruptedIOException ||
                e is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted
            val actual = if (interrupted) e else try {
                transport.checkIdentity(key)
                e
            } catch (changed: RemoteAgentConfigIdentityChangedException) {
                changed
            }
            val failure = if (actual is IOException || interrupted) actual else IOException("Remote configuration refresh failed", actual)
            val fallback = previous?.snapshot?.takeUnless { interrupted || actual is RemoteAgentConfigIdentityChangedException }
            synchronized(entry) {
                entry.fresh = false
                if (fallback != null) flight.complete(fallback) else flight.completeExceptionally(failure)
                entry.flight = null
            }
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return fallback ?: throw failure
        }
    }

    fun invalidate(paths: Collection<String>? = null) {
        entries.forEach { (key, entry) ->
            if (paths == null || paths.any(key::affectedBy)) synchronized(entry) {
                entry.fresh = false
                entry.invalidated = true
                invalidatedIdentities.add(key.identity)
                entry.generation++
            }
        }
    }

    private fun await(flight: CompletableFuture<AgentConfigSnapshot>): AgentConfigSnapshot = try {
        flight.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.io.InterruptedIOException("Remote configuration wait interrupted").apply { initCause(e) }
    } catch (e: ExecutionException) {
        throw (e.cause ?: e)
    }
}
