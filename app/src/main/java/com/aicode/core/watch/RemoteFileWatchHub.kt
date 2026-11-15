package com.aicode.core.watch

import com.aicode.feature.workspace.domain.FileAccessProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 远端文件快照的稳定错误码；由插件层映射为宿主错误码。 */
class RemoteWatchException(val code: String, message: String) : Exception(message)

/**
 * 远端文件变化的全局订阅中心。
 *
 * 同一个远端根（路径 + 是否递归）只保留一个轮询任务，多个页面/订阅共享快照与批量结果；
 * 最后一个订阅离开后停止轮询，下次订阅重新建立基线。扫描失败（断网、部分目录读失败）
 * 不会替换基线，只等待下一轮；条目数/深度超限抛 [RemoteWatchException]。
 */
@Singleton
class RemoteFileWatchHub internal constructor(
    private val fileAccess: FileAccessProvider,
    private val dispatcher: CoroutineDispatcher,
    private val pollIntervalMs: Long
) {
    @Inject
    constructor(fileAccess: FileAccessProvider) : this(fileAccess, Dispatchers.IO, POLL_INTERVAL_MS)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val watches = ConcurrentHashMap<Key, Watch>()

    /** 订阅一个远端根；首次订阅触发一次扫描，返回的流只包含变化与截断信号。 */
    fun watch(rootPath: String, recursive: Boolean): Flow<FileChangeBatch> = flow {
        val key = Key(rootPath.trimEnd('/'), recursive)
        val subscriber = Subscriber()
        var watch: Watch
        while (true) {
            watch = watches.computeIfAbsent(key) { Watch(key) }
            if (watch.subscribe(subscriber)) break
        }
        try {
            for (batch in subscriber.channel) emit(batch)
        } finally {
            watch.unsubscribe(subscriber)
        }
    }

    private data class Key(val rootPath: String, val recursive: Boolean)

    private inner class Subscriber {
        val channel = Channel<FileChangeBatch>(
            capacity = SUBSCRIBER_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        private var calibrated = false

        fun onScan(batch: FileChangeBatch) {
            if (!calibrated) {
                calibrated = true
                channel.trySend(FileChangeBatch(emptyList()))
            }
            if (batch.changes.isNotEmpty() || batch.truncated) {
                channel.trySend(batch)
            }
        }

        fun fail(error: Throwable) {
            channel.close(error)
        }
    }

    private inner class Watch(private val key: Key) {
        private val lock = Any()
        private val subscribers = CopyOnWriteArraySet<Subscriber>()
        private val trigger = Channel<Unit>(Channel.CONFLATED)
        private var job: Job? = null
        private var previous: Map<String, String>? = null

        @Volatile
        private var closed = false

        fun subscribe(subscriber: Subscriber): Boolean = synchronized(lock) {
            if (closed) return false
            subscribers.add(subscriber)
            if (job?.isActive != true) {
                previous = null
                job = scope.launch { poll() }
            }
            trigger.trySend(Unit)
            true
        }

        fun unsubscribe(subscriber: Subscriber) {
            val closeSubscriber = synchronized(lock) {
                if (!subscribers.remove(subscriber)) return
                if (subscribers.isNotEmpty()) return
                closed = true
                job?.cancel()
                job = null
                previous = null
                watches.remove(key, this)
                true
            }
            if (closeSubscriber) subscriber.channel.close()
        }

        private suspend fun poll() {
            while (currentCoroutineContext().isActive) {
                // 合并订阅时补的触发信号；本轮扫描已经覆盖它。
                trigger.tryReceive()

                val snapshot = try {
                    snapshotOf(key.rootPath, key.recursive)
                } catch (e: CancellationException) {
                    fail(e)
                    return
                } catch (e: RemoteWatchException) {
                    fail(e)
                    return
                } catch (_: Exception) {
                    // 断网或部分目录读取失败：保留旧基线，下一轮继续比对。
                    null
                }

                if (snapshot != null) {
                    val before = previous
                    previous = snapshot
                    val batch = if (before == null) {
                        FileChangeBatch(emptyList())
                    } else {
                        changesBetween(before, snapshot)
                    }
                    synchronized(lock) {
                        if (!closed) subscribers.forEach { it.onScan(batch) }
                    }
                }

                withTimeoutOrNull(pollIntervalMs) { trigger.receive() }
            }
        }

        private fun fail(error: Throwable) {
            val targets = synchronized(lock) {
                if (closed) return
                closed = true
                job = null
                previous = null
                watches.remove(key, this)
                subscribers.toList()
            }
            targets.forEach { it.fail(error) }
        }
    }

    private suspend fun snapshotOf(root: String, recursive: Boolean): Map<String, String> {
        currentCoroutineContext().ensureActive()
        if (!fileAccess.exists(root)) return emptyMap()
        if (!fileAccess.isDirectory(root)) {
            return mapOf(root to fileStamp(root))
        }

        val snapshot = linkedMapOf(root to "d")
        suspend fun walk(directory: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            for (entry in fileAccess.listFiles(directory).sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                val name = entry.name
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/') ||
                    name.contains('\\') || name.any { it.isISOControl() }
                ) {
                    continue
                }
                if (snapshot.size >= MAX_SNAPSHOT_ENTRIES) {
                    throw RemoteWatchException("TOO_LARGE", "remote watch scope exceeds the entry limit")
                }
                val path = "${directory.trimEnd('/')}/$name"
                snapshot[path] = if (entry.isDirectory) "d" else "f:${entry.lastModified}:${entry.size}"
                if (recursive && entry.isDirectory) {
                    if (depth >= MAX_RECURSIVE_DEPTH) {
                        throw RemoteWatchException("TOO_LARGE", "remote watch scope exceeds the depth limit")
                    }
                    walk(path, depth + 1)
                }
            }
        }
        walk(root, 1)
        return snapshot
    }

    private fun fileStamp(path: String): String =
        "f:${fileAccess.lastModified(path)}:${fileAccess.fileSize(path)}"

    private fun changesBetween(
        before: Map<String, String>,
        after: Map<String, String>
    ): FileChangeBatch {
        val changes = mutableListOf<FileChange>()
        var truncated = false

        fun add(path: String, kind: ChangeKind) {
            if (changes.size >= MAX_BATCH_ENTRIES) {
                truncated = true
            } else {
                changes += FileChange(ChangeRoot.OTHER, path, path, kind)
            }
        }

        for ((path, stamp) in after) {
            val old = before[path]
            when {
                old == null -> add(path, ChangeKind.CREATED)
                old != stamp -> add(path, ChangeKind.MODIFIED)
            }
        }
        for (path in before.keys) {
            if (path !in after) add(path, ChangeKind.DELETED)
        }
        return FileChangeBatch(changes, truncated)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2000L
        const val MAX_SNAPSHOT_ENTRIES = 4096
        const val MAX_RECURSIVE_DEPTH = 8
        const val MAX_BATCH_ENTRIES = 512
        const val SUBSCRIBER_BUFFER = 16
    }
}
