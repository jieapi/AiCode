package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的本地实现：包一层 [WorkspacePathMapper]，走 java.io.File 直读。
 *
 * 本地模式下文件工具的原行为零变化——仅把原先散落在各工具里的 pathMapper.toHostFile + File 操作
 * 集中到此处，统一经由接口调用。
 */
@Singleton
class LocalFileAccess @Inject constructor(
    private val pathMapper: WorkspacePathMapper
) : FileAccessProvider {

    private fun resolve(path: String): File = pathMapper.toHostFile(path)

    override fun readFile(path: String): String {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readText()
    }

    override fun readLines(path: String): Sequence<String> {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        // 惰性 Sequence：每次迭代才读下一行，大文件不整份载入；迭代结束后随 use 关闭 reader。
        // 用 for 循环（而非 forEach 非挂起 lambda）内联，yield 才能合法出现在 builder 的挂起作用域里。
        return sequence {
            file.bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    yield(line)
                }
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        val bytes = content.toByteArray(encoding)
        // fsync + 回读长度校验：writeText 只写入 page cache，延迟分配下的写回错误
        // （磁盘已满 ENOSPC、存储异常）不会报给调用方，会变成“工具返回 success 但文件没变”。
        // sync 把错误提前成 IOException，让工具报真实失败，而不是静默丢掉改动。
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val actual = file.length()
        if (actual != bytes.size.toLong()) {
            throw IOException("write verification failed: ${file.absolutePath} expected ${bytes.size} bytes, found $actual")
        }
        FileLogger.i(TAG, "写入: '$path' -> ${file.absolutePath} (${bytes.size} 字节)")
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun isFile(path: String): Boolean = resolve(path).isFile

    override fun fileSize(path: String): Long = resolve(path).length()

    override fun lastModified(path: String): Long = resolve(path).lastModified()

    override fun permissions(path: String): String {
        val file = resolve(path)
        val read = if (file.canRead()) "r" else "-"
        val write = if (file.canWrite()) "w" else "-"
        val execute = if (file.canExecute() || file.isDirectory) "x" else "-"
        return read + write + execute
    }

    override fun listFiles(path: String): List<FileEntry> {
        val dir = resolve(path)
        return dir.listFiles().orEmpty().map { child ->
            FileEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.length(),
                lastModified = child.lastModified(),
                localFile = child,
                permissions = run {
                    val r = if (child.canRead()) "r" else "-"
                    val w = if (child.canWrite()) "w" else "-"
                    val x = if (child.canExecute() || child.isDirectory) "x" else "-"
                    r + w + x
                }
            )
        }
    }

    override fun readBytes(path: String): ByteArray {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readBytes()
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(maxDepth)
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        // 与 writeFile 相同的 fsync + 回读长度校验，避免“报告成功但文件没写全”。
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        val actual = file.length()
        if (actual != bytes.size.toLong()) {
            throw IOException("write verification failed: ${file.absolutePath} expected ${bytes.size} bytes, found $actual")
        }
        FileLogger.i(TAG, "写入: '$path' -> ${file.absolutePath} (${bytes.size} 字节)")
    }

    override fun copyToLocal(path: String): File = resolve(path)

    override fun delete(path: String) {
        resolve(path).delete()
    }

    override fun deleteRecursively(path: String) {
        val file = resolve(path)
        if (!file.exists()) return
        if (!file.deleteRecursively()) throw IOException("delete failed: ${file.absolutePath}")
    }

    override fun rename(path: String, newPath: String) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists()) throw FileAlreadyExistsException(target)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            throw IOException("rename failed: ${source.absolutePath} -> ${target.absolutePath}")
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists()) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path)
        val target = resolve(newPath)
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists()) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
        val moved = try {
            source.renameTo(target)
        } catch (e: SecurityException) {
            false
        }
        if (moved) return
        // 回退：复制后删除源（复制中断时源保留，下次重试安全）。
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
        if (!source.deleteRecursively()) throw IOException("delete-after-copy failed: ${source.absolutePath}")
    }

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    override fun parentPath(path: String): String? {
        val parent = resolve(path).parentFile ?: return null
        return pathMapper.toContainerPath(parent.absolutePath)
    }

    override fun toDisplayPath(path: String): String {
        return pathMapper.toContainerPath(resolve(path).absolutePath)
    }

    private companion object {
        const val TAG = "LocalFileAccess"
    }
}
