package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess
) : FileAccessProvider {

    private fun delegate(): FileAccessProvider =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remoteSftpFileAccess
        else localFileAccess

    override fun readFile(path: String): String = delegate().readFile(path)

    override fun readLines(path: String): Sequence<String> = delegate().readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean) =
        delegate().writeFile(path, content, overwrite)

    override fun exists(path: String): Boolean = delegate().exists(path)

    override fun isDirectory(path: String): Boolean = delegate().isDirectory(path)

    override fun isFile(path: String): Boolean = delegate().isFile(path)

    override fun fileSize(path: String): Long = delegate().fileSize(path)

    override fun lastModified(path: String): Long = delegate().lastModified(path)

    override fun permissions(path: String): String = delegate().permissions(path)

    override fun listFiles(path: String): List<FileEntry> = delegate().listFiles(path)

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        delegate().listFilesRecursive(path, maxDepth)

    override fun readBytes(path: String): ByteArray = delegate().readBytes(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate().writeBytes(path, bytes, overwrite)

    override fun copyToLocal(path: String): File = delegate().copyToLocal(path)

    override fun delete(path: String) = delegate().delete(path)

    override fun deleteRecursively(path: String) = delegate().deleteRecursively(path)

    override fun rename(path: String, newPath: String) = delegate().rename(path, newPath)

    override fun mkdirs(path: String) = delegate().mkdirs(path)

    override fun parentPath(path: String): String? = delegate().parentPath(path)

    override fun toDisplayPath(path: String): String = delegate().toDisplayPath(path)
}
