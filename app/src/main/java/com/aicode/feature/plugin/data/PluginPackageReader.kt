package com.aicode.feature.plugin.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManifestParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 解压并校验过的插件包：[dir] 内为包内容，[manifestText] 是 `plugin.json` 原文。 */
data class ExtractedPackage(
    val dir: File,
    val manifestText: String,
    val archiveSha256: String,
    val manifestSha256: String
)

sealed interface PackageReadResult {
    data class Ok(val pkg: ExtractedPackage) : PackageReadResult
    data class Failed(val error: PluginError) : PackageReadResult
}

/**
 * 把用户提供的插件 ZIP 安全地解压到暂存目录。
 *
 * 包是不可信输入：大小、条目数、单条目与总解压量都在流式读取时执行限额（不信任条目自报的大小），
 * 路径逐条校验并要求落在目标目录内，重复条目（含仅大小写不同）一律拒绝。
 * 所有条目都以普通文件写出，ZIP 里的符号链接 / 权限位被忽略，不会在磁盘上生成链接或可执行文件。
 * 本类不执行包内任何内容。
 */
class PluginPackageReader(private val limits: Limits = Limits()) {

    data class Limits(
        val maxArchiveBytes: Long = 16L * 1024 * 1024,
        val maxEntries: Int = 512,
        val maxEntryBytes: Long = 4L * 1024 * 1024,
        val maxTotalBytes: Long = 32L * 1024 * 1024
    )

    private class Rejected(val error: PluginError) : Exception(null, null, false, false)

    /** [targetDir] 必须不存在；失败时已创建的目录与临时文件都会被清理。 */
    fun read(input: InputStream, targetDir: File): PackageReadResult {
        val archive = File(targetDir.parentFile, "${targetDir.name}.zip")
        return try {
            targetDir.parentFile?.mkdirs()
            val archiveSha = copyArchive(input, archive)
            extract(archive, targetDir)
            val manifestBytes = readManifest(targetDir)
            PackageReadResult.Ok(
                ExtractedPackage(
                    dir = targetDir,
                    manifestText = String(manifestBytes, Charsets.UTF_8),
                    archiveSha256 = archiveSha,
                    manifestSha256 = sha256Hex(manifestBytes)
                )
            )
        } catch (e: Rejected) {
            targetDir.deleteRecursively()
            PackageReadResult.Failed(e.error)
        } catch (e: IOException) {
            FileLogger.w(TAG, "读取插件包失败", e)
            targetDir.deleteRecursively()
            PackageReadResult.Failed(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE, e.javaClass.simpleName))
        } catch (e: RuntimeException) {
            // ZipException 之外，部分设备的 ZipFile 会对畸形包抛出运行时异常
            FileLogger.w(TAG, "读取插件包失败", e)
            targetDir.deleteRecursively()
            PackageReadResult.Failed(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE, e.javaClass.simpleName))
        } finally {
            archive.delete()
        }
    }

    private fun copyArchive(input: InputStream, archive: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        archive.outputStream().use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limits.maxArchiveBytes) throw Rejected(PluginError(PluginErrorCode.ARCHIVE_TOO_LARGE))
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        if (total == 0L) throw Rejected(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE, "empty"))
        return digest.digest().toHex()
    }

    private fun extract(archive: File, targetDir: File) {
        if (!targetDir.mkdirs()) throw IOException("无法创建暂存目录")
        val root = targetDir.canonicalFile.toPath()
        val seen = HashSet<String>()
        var count = 0
        var total = 0L

        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (++count > limits.maxEntries) throw Rejected(PluginError(PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES))
                val isDirectory = entry.isDirectory
                val name = entry.name.removeSuffix("/")
                if (!PluginManifestParser.isSafeRelativePath(name)) {
                    throw Rejected(PluginError(PluginErrorCode.ARCHIVE_UNSAFE_PATH, name.take(MAX_DETAIL)))
                }
                if (!seen.add(name.lowercase())) {
                    throw Rejected(PluginError(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, name.take(MAX_DETAIL)))
                }
                if (isDirectory) continue

                if (entry.size > limits.maxEntryBytes) {
                    throw Rejected(PluginError(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE, name.take(MAX_DETAIL)))
                }
                val out = File(targetDir, name)
                if (!out.canonicalFile.toPath().startsWith(root)) {
                    throw Rejected(PluginError(PluginErrorCode.ARCHIVE_UNSAFE_PATH, name.take(MAX_DETAIL)))
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { src ->
                    out.outputStream().use { dst ->
                        val written = copyBounded(src, dst, limits.maxEntryBytes, name)
                        total += written
                        if (total > limits.maxTotalBytes) throw Rejected(PluginError(PluginErrorCode.ARCHIVE_TOO_LARGE))
                    }
                }
            }
        }
    }

    private fun copyBounded(src: InputStream, dst: OutputStream, limit: Long, name: String): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        while (true) {
            val n = src.read(buffer)
            if (n < 0) break
            written += n
            if (written > limit) throw Rejected(PluginError(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE, name.take(MAX_DETAIL)))
            dst.write(buffer, 0, n)
        }
        return written
    }

    private fun readManifest(targetDir: File): ByteArray {
        val manifest = File(targetDir, MANIFEST_FILE)
        if (!manifest.isFile) throw Rejected(PluginError(PluginErrorCode.MANIFEST_MISSING))
        if (manifest.length() > PluginManifestParser.MAX_MANIFEST_BYTES) {
            throw Rejected(PluginError(PluginErrorCode.MANIFEST_TOO_LARGE))
        }
        return manifest.readBytes()
    }

    companion object {
        private const val TAG = "PluginPackageReader"
        const val MANIFEST_FILE = "plugin.json"
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_DETAIL = 120

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
