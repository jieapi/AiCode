package com.aicode.feature.workspace.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteAgentConfigCache @Inject constructor(
    @ApplicationContext context: Context,
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository
) {
    private val engine = RemoteAgentConfigCacheEngine(
        RemoteAgentConfigStore(File(context.cacheDir, "remote-agent-config")),
        SshAgentConfigTransport(connection, workspaceRepository::currentPath)
    )

    fun directory(root: String, kind: AgentConfigKind, forceRefresh: Boolean = false): AgentConfigSnapshot =
        engine.directory(key(root, kind), forceRefresh)

    fun cachedDirectory(root: String, kind: AgentConfigKind): AgentConfigSnapshot? =
        engine.cachedDirectory(key(root, kind))

    fun invalidate(path: String? = null) {
        if (path == null) {
            engine.invalidate()
            return
        }
        val cfg = connection.config
        val home = connection.remoteHome
        val workspace = cfg?.let { selectedAgentWorkspace(workspaceRepository.currentPath(), it.remoteWorkspacePath, home ?: "~") }
        val mapped = workspace?.let { mapPath(path, it, home) }
        engine.invalidate(listOfNotNull(normalizePath(path), mapped?.let(::normalizePath)))
    }

    private fun key(root: String, kind: AgentConfigKind): RemoteAgentConfigKey {
        val generation = connection.connectionGeneration
        val cfg = connection.config ?: throw IOException("SSH configuration unavailable")
        val home = connection.remoteHome ?: throw IOException("SSH home unavailable")
        val workspace = selectedAgentWorkspace(workspaceRepository.currentPath(), cfg.remoteWorkspacePath, home)
        val displayRoot = root.trimEnd('/').ifEmpty { "/" }
        val remoteRoot = mapPath(displayRoot, workspace, home)
        return RemoteAgentConfigKey(cfg.host, cfg.port, cfg.username, home, workspace, kind, displayRoot, remoteRoot, generation).also {
            if (connection.connectionGeneration != generation) throw RemoteAgentConfigIdentityChangedException()
        }
    }

    private fun mapPath(path: String, workspace: String, home: String?): String {
        // The suffix protects significant trailing whitespace from remotePathFor's input trimming.
        return remotePathFor(path.trimEnd('/') + "/.", workspace, home).removeSuffix("/.").ifEmpty { "/" }
    }

    private fun normalizePath(path: String): String {
        val absolute = path.startsWith('/')
        val parts = ArrayList<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != ".." && parts.last() != "~") {
                    parts.removeAt(parts.lastIndex)
                } else if (!absolute) parts.add(part)
                else -> parts.add(part)
            }
        }
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }
}

private fun selectedAgentWorkspace(current: String, fallback: String, home: String): String {
    val workspace = (current.takeIf { it.isNotBlank() && it != "/" } ?: fallback).trimEnd('/')
    return if (workspace == "~") home else if (workspace.startsWith("~/")) home.trimEnd('/') + "/" + workspace.removePrefix("~/") else workspace
}

internal class SshAgentConfigTransport(
    private val connection: RemoteSshConnection,
    private val workspacePath: () -> String,
    private val timeoutMillis: Long = 30_000
) : RemoteAgentConfigTransport {
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "remote-agent-config-io").apply { isDaemon = true } }

    override fun checkIdentity(key: RemoteAgentConfigKey) {
        val cfg = connection.config ?: throw RemoteAgentConfigIdentityChangedException()
        val home = connection.remoteHome ?: throw RemoteAgentConfigIdentityChangedException()
        if (connection.connectionGeneration != key.connectionGeneration || cfg.host != key.host || cfg.port != key.port ||
            cfg.username != key.username || home != key.home || selectedAgentWorkspace(workspacePath(), cfg.remoteWorkspacePath, home) != key.workspace
        ) throw RemoteAgentConfigIdentityChangedException()
    }

    override fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint> = RemoteAgentConfigProtocol.manifest(
        execute(key, RemoteAgentConfigProtocol.manifestCommand(key.remoteRoot, key.kind), ByteArray(0), RemoteAgentConfigProtocol.MAX_MANIFEST_BYTES),
        key.kind
    )

    override fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String> =
        RemoteAgentConfigProtocol.bodies(
            execute(
                key,
                RemoteAgentConfigProtocol.bodiesCommand(key.remoteRoot),
                RemoteAgentConfigProtocol.request(expected.keys),
                RemoteAgentConfigProtocol.MAX_WIRE_BYTES
            ),
            expected
        )

    private fun execute(key: RemoteAgentConfigKey, command: String, stdin: ByteArray, limit: Int): ByteArray {
        checkIdentity(key)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val abandoned = AtomicBoolean(false)
        val active = AtomicReference<Session.Command?>()
        val tasks = ArrayList<Future<*>>()
        fun <T> start(block: () -> T): Future<T> = workers.submit(Callable(block)).also { tasks.add(it) }
        fun <T> await(task: Future<T>): T {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Remote configuration exec timed out")
            return try {
                task.get(remaining, TimeUnit.NANOSECONDS)
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        }
        try {
            val session = await(start {
                checkIdentity(key)
                connection.startExecSessionWithStdin(command).also {
                    active.set(it)
                    if (abandoned.get()) {
                        runCatching { it.close() }
                        throw IOException("Remote configuration exec cancelled")
                    }
                    checkIdentity(key)
                }
            })
            val output = start { session.inputStream.use { RemoteAgentConfigProtocol.readLimited(it, limit) } }
            val error = start { session.errorStream.use { RemoteAgentConfigProtocol.readLimited(it, 64 * 1024) }; Unit }
            val input = start { session.outputStream.use { it.write(stdin) } }
            await(input)
            val bytes = await(output)
            await(error)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Remote configuration exec timed out")
            session.join(remaining, TimeUnit.NANOSECONDS)
            val status = session.exitStatus
            if (status == null || status != 0 || session.exitSignal != null) {
                throw IOException("Remote configuration exec failed (exit=${status ?: "missing"})")
            }
            checkIdentity(key)
            return bytes
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Remote configuration exec interrupted").apply { initCause(e) }
        } catch (e: TimeoutException) {
            throw IOException("Remote configuration exec timed out", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Remote configuration exec failed", e)
        } finally {
            abandoned.set(true)
            runCatching { active.get()?.close() }
            tasks.forEach { it.cancel(true) }
        }
    }
}
