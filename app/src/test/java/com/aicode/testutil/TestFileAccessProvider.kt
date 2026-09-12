package com.aicode.testutil

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

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

    override fun writeFile(path: String, content: String, overwrite: Boolean) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun fileSize(path: String): Long = File(path).length()

    override fun lastModified(path: String): Long = File(path).lastModified()

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

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
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

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun parentPath(path: String): String? = File(path).parentFile?.path

    override fun toDisplayPath(path: String): String = path
}
