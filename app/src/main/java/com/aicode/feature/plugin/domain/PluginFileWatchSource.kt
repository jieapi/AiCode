package com.aicode.feature.plugin.domain

import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.RemoteWatchException
import com.aicode.core.watch.WatchFilter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

@Singleton
class PluginFileWatchSource internal constructor(
    private val hub: FileChangeHub,
    private val dispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(hub: FileChangeHub) : this(hub, Dispatchers.IO)

    /** 已存在文件按精确路径订阅；本地尚不存在的路径按待创建目录处理。 */
    fun watch(target: PluginWatchTarget, recursive: Boolean): Flow<FileChangeBatch> = flow {
        when (target) {
            is PluginWatchTarget.Local -> emitAll(watchLocal(target.file, recursive))
            is PluginWatchTarget.Remote -> emitAll(
                hub.watchRemoteDir(target.path, recursive).mapRemoteErrors()
            )
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

    private fun Flow<FileChangeBatch>.mapRemoteErrors(): Flow<FileChangeBatch> = catch { error ->
        if (error is RemoteWatchException) {
            throw PluginHostException(error.code, error.message ?: "remote watch failed")
        }
        throw error
    }
}
