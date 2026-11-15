package com.aicode.feature.plugin.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把开发者目录打成插件包，复用现有 zip 校验与安装管线。
 *
 * 只接受普通文件，跳过符号链接与常见构建/依赖目录；包大小与文件数上限和安装器保持一致。
 */
object PluginDevPackageBuilder {

    private const val MAX_FILES = 2_000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private val SKIPPED_DIRS = setOf(".git", "node_modules", ".venv", "__pycache__", ".idea")

    fun zip(sourceDir: File, target: File) {
        require(sourceDir.isDirectory) { "not a directory: ${sourceDir.path}" }
        target.parentFile?.mkdirs()
        var files = 0
        var bytes = 0L
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            sourceDir.walkTopDown()
                .filter { file ->
                    file.isFile && file.relativeTo(sourceDir).invariantSeparatorsPath
                        .split('/').none { it in SKIPPED_DIRS }
                }
                .sortedBy { it.relativeTo(sourceDir).invariantSeparatorsPath }
                .forEach { file ->
                    if (file.canonicalFile.toPath().startsWith(sourceDir.canonicalFile.toPath()).not()) return@forEach
                    files++
                    bytes += file.length()
                    if (files > MAX_FILES) throw IllegalArgumentException("too many files")
                    if (bytes > MAX_TOTAL_BYTES) throw IllegalArgumentException("package too large")
                    val name = file.relativeTo(sourceDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
