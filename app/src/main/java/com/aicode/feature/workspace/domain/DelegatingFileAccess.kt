package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 *
 * 文件树添加的本机文件夹（[WorkspacePathMapper.LOCAL_FS_ROOT] 下的虚拟路径）在任何执行模式下
 * 都走本地实现：它们就是当前设备上的目录，与远程服务器无关。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess
) : FileAccessProvider {

    private fun delegate(path: String): FileAccessProvider =
        if (WorkspacePathMapper.isLocalFolderContainerPath(path) ||
            modeHolder.currentMode() != ExecutionMode.REMOTE_SSH
        ) {
            localFileAccess
        } else {
            remoteSftpFileAccess
        }

    override fun backendFor(path: String): FileAccessProvider = delegate(path)

    override fun agentConfigSnapshot(path: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        delegate(path).agentConfigSnapshot(path, kind, forceRefresh)

    override fun cachedAgentConfigSnapshot(path: String, kind: AgentConfigKind): AgentConfigSnapshot? =
        delegate(path).cachedAgentConfigSnapshot(path, kind)

    override fun invalidateAgentConfigCache(path: String?) {
        if (path == null) remoteSftpFileAccess.invalidateAgentConfigCache()
        else delegate(path).invalidateAgentConfigCache(path)
    }

    override fun readFile(path: String): String = delegate(path).readFile(path)

    override fun readLines(path: String): Sequence<String> = delegate(path).readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        delegate(path).writeFile(path, content, overwrite, encoding)

    override fun exists(path: String): Boolean = delegate(path).exists(path)

    override fun isDirectory(path: String): Boolean = delegate(path).isDirectory(path)

    override fun isFile(path: String): Boolean = delegate(path).isFile(path)

    override fun fileSize(path: String): Long = delegate(path).fileSize(path)

    override fun lastModified(path: String): Long = delegate(path).lastModified(path)

    override fun fingerprint(path: String): String? = delegate(path).fingerprint(path)

    override fun permissions(path: String): String = delegate(path).permissions(path)

    override fun listFiles(path: String): List<FileEntry> = delegate(path).listFiles(path)

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        delegate(path).listFilesRecursive(path, maxDepth)

    override fun readBytes(path: String): ByteArray = delegate(path).readBytes(path)

    override fun readBytes(path: String, maxBytes: Long): ByteArray = delegate(path).readBytes(path, maxBytes)

    override fun resolvePath(path: String, followFinalLink: Boolean): String =
        delegate(path).resolvePath(path, followFinalLink)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate(path).writeBytes(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        delegate(path).writeStream(path, input, overwrite)

    override fun copyToLocal(path: String): File = delegate(path).copyToLocal(path)

    override fun delete(path: String) = delegate(path).delete(path)

    override fun deleteRecursively(path: String) = delegate(path).deleteRecursively(path)

    override fun rename(path: String, newPath: String) {
        ensureSameBackend(path, newPath)
        delegate(path).rename(path, newPath)
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        ensureSameBackend(path, newPath)
        delegate(path).copy(path, newPath, overwrite)
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        ensureSameBackend(path, newPath)
        delegate(path).move(path, newPath, overwrite)
    }

    /** 单后端操作才能直接交给实现；跨后端复制/移动由调用方分成读、写两步。 */
    private fun ensureSameBackend(path: String, newPath: String) {
        require(delegate(path) === delegate(newPath)) {
            "cross-backend operation: $path -> $newPath"
        }
    }

    override fun mkdirs(path: String) = delegate(path).mkdirs(path)

    override fun parentPath(path: String): String? = delegate(path).parentPath(path)

    override fun toDisplayPath(path: String): String = delegate(path).toDisplayPath(path)
}
