package com.aicode.feature.plugin.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.WatchFilter
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive

@Singleton
class PluginFileWatchSource internal constructor(
    private val hub: FileChangeHub,
    private val fileAccess: FileAccessProvider,
    private val dispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(hub: FileChangeHub, fileAccess: FileAccessProvider) : this(hub, fileAccess, Dispatchers.IO)

    /** 已存在文件按精确路径订阅；本地尚不存在的路径按待创建目录处理。 */
    fun watch(target: PluginWatchTarget, recursive: Boolean): Flow<FileChangeBatch> = flow {
        when (target) {
            is PluginWatchTarget.Local -> emitAll(watchLocal(target.file, recursive))
            is PluginWatchTarget.Remote -> {
                var previous: Map<String, String>? = null
                while (currentCoroutineContext().isActive) {
                    val snapshot = try {
                        remoteSnapshot(target.path, recursive)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PluginHostException) {
                        throw e
                    } catch (_: Exception) {
                        // 不完整扫描不能替换基线，否则短暂断网会被误报为批量删除。
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    val before = previous
                    if (before == null) {
                        emit(FileChangeBatch(emptyList()))
                    } else {
                        val batch = changesBetween(before, snapshot)
                        if (batch.changes.isNotEmpty()) emit(batch)
                    }
                    previous = snapshot
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }.flowOn(dispatcher)

    private fun watchLocal(file: File, recursive: Boolean): Flow<FileChangeBatch> {
        val watchingFile = file.isFile
        val directory = if (watchingFile) file.parentFile else file
        if (directory == null) throw PluginHostException("PATH_INVALID", "watch path has no parent")
        val rootPath = file.toPath()
        return hub.watchHostDir(
            hostDir = directory,
            recursive = recursive && !watchingFile,
            filter = WatchFilter(followGitignore = false)
        ).mapNotNull { batch ->
            if (file.canonicalFile.toPath() != rootPath) {
                throw PluginHostException("PATH_DENIED", "watch path escapes root")
            }
            val changes = batch.changes.filter { change ->
                val eventFile = File(change.hostPath)
                val eventPath = eventFile.absoluteFile.toPath().normalize()
                val canonical = eventFile.canonicalFile.toPath()
                if (watchingFile) {
                    eventPath == rootPath && canonical == rootPath
                } else {
                    eventPath.startsWith(rootPath) && canonical.startsWith(rootPath) &&
                        (recursive || eventPath == rootPath || eventPath.parent == rootPath)
                }
            }
            if (changes.isNotEmpty() || batch.truncated) {
                FileChangeBatch(changes, batch.truncated)
            } else {
                null
            }
        }
    }

    private suspend fun remoteSnapshot(root: String, recursive: Boolean): Map<String, String> {
        currentCoroutineContext().ensureActive()
        if (!fileAccess.exists(root)) return emptyMap()
        if (!fileAccess.isDirectory(root)) {
            return mapOf(root to "f:${fileAccess.lastModified(root)}:${fileAccess.fileSize(root)}")
        }
        val snapshot = linkedMapOf(root to "d")
        suspend fun walk(directory: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            for (entry in fileAccess.listFiles(directory).sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                val name = entry.name
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/') ||
                    name.contains('\\') || name.any { it.isISOControl() }
                ) continue
                if (snapshot.size >= MAX_SNAPSHOT_ENTRIES) {
                    throw PluginHostException("TOO_LARGE", "watch scope exceeds the entry limit")
                }
                val path = "${directory.trimEnd('/')}/$name"
                snapshot[path] = if (entry.isDirectory) "d" else "f:${entry.lastModified}:${entry.size}"
                if (recursive && entry.isDirectory) {
                    if (depth >= MAX_RECURSIVE_DEPTH) {
                        throw PluginHostException("TOO_LARGE", "watch scope exceeds the depth limit")
                    }
                    walk(path, depth + 1)
                }
            }
        }
        walk(root, 1)
        return snapshot
    }

    private fun changesBetween(before: Map<String, String>, after: Map<String, String>): FileChangeBatch {
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
    }
}
