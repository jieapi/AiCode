package com.aicode.testutil

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.readBytesBounded
import com.aicode.feature.workspace.domain.sha256Fingerprint
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.util.ArrayDeque

/**
 * 测试用的 [FileAccessProvider]：把传入路径直接当本地文件系统路径处理。
 * 覆盖技能/子代理目录扫描与写回、附件写入所需的能力。
 */
class TestFileAccessProvider : FileAccessProvider {

    override fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readText()
    }

    override fun readLines(path: String): Sequence<String> {
        val file = File(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.bufferedReader().useLines { it.toList() }.asSequence()
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        file.writeText(content, encoding)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun fileSize(path: String): Long = File(path).length()

    override fun lastModified(path: String): Long = File(path).lastModified()

    /** 与生产实现同语义：非普通文件返回 null，否则按原始字节算 SHA-256。 */
    override fun fingerprint(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        return sha256Fingerprint(file.readBytes())
    }

    override fun permissions(path: String): String = "rwx"

    override fun listFiles(path: String): List<FileEntry> =
        File(path).listFiles().orEmpty().map { child ->
            FileEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.length(),
                lastModified = child.lastModified(),
                localFile = child
            )
        }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(maxDepth)
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val file = File(path)
        if (file.length() > maxBytes) throw FileReadTooLargeException(maxBytes)
        return file.inputStream().use { it.readBytesBounded(maxBytes) }
    }

    override fun resolvePath(path: String, followFinalLink: Boolean): String {
        val pending = ArrayDeque(File(path).absolutePath.split('/').filter { it.isNotEmpty() })
        var current = File("/")
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "." -> continue
                ".." -> {
                    current = current.parentFile ?: current
                    continue
                }
            }
            val candidate = File(current, part)
            if ((followFinalLink || pending.isNotEmpty()) && Files.isSymbolicLink(candidate.toPath())) {
                if (++links > 40) throw FileSystemLoopException(path)
                val target = Files.readSymbolicLink(candidate.toPath()).toString()
                if (target.startsWith('/')) current = File("/")
                target.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
            } else {
                current = candidate
            }
        }
        return current.path
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        return FileOutputStream(file).use { out -> input.copyTo(out) }
    }

    override fun copyToLocal(path: String): File = File(path)

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    override fun rename(path: String, newPath: String) {
        File(path).renameTo(File(newPath))
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = File(path)
        if (!src.exists()) throw NoSuchFileException(src)
        val dst = File(newPath)
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst)
        if (src.isDirectory) {
            src.copyRecursively(dst, overwrite = true)
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val src = File(path)
        if (!src.exists()) throw NoSuchFileException(src)
        val dst = File(newPath)
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            copy(path, newPath, overwrite = true)
            src.deleteRecursively()
        }
    }

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun parentPath(path: String): String? = File(path).parentFile?.path

    override fun toDisplayPath(path: String): String = path
}
