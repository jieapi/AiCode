package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginRuntime
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * 插件运行时依赖的独立缓存。
 *
 * 插件代码目录会随版本替换，依赖目录不属于安装包生命周期；缓存目录只在完整依赖环境提交后才可见。
 */
class PluginDependencyStore(private val rootDir: File) {
    private val dependenciesDir = File(rootDir, "dependencies")
    private val stagingDir = File(dependenciesDir, ".staging")

    fun fingerprint(runtime: PluginRuntime): String {
        val input = buildString {
            append(runtime.type).append('\n')
            append(runtime.dependencies?.tool.orEmpty()).append('\n')
            append(runtime.dependencies?.file?.name.orEmpty()).append('\n')
            val files = runtime.dependencies?.file?.let { dependencyFiles(it) }.orEmpty()
            files.sortedBy { it.name }.forEach { file ->
                append(file.name).append('\n')
                append(PluginPackageReader.sha256Hex(file.readBytes())).append('\n')
            }
        }
        return PluginPackageReader.sha256Hex(input.toByteArray(Charsets.UTF_8))
    }

    fun directory(pluginId: String, fingerprint: String): File =
        File(File(dependenciesDir, pluginId), fingerprint)

    fun isReady(pluginId: String, fingerprint: String): Boolean {
        val dir = directory(pluginId, fingerprint)
        val marker = File(dir, COMPLETE_MARKER)
        return marker.isFile && dependencyOutput(dir).exists()
    }

    /** 创建独立准备目录，并复制依赖输入；调用方必须在成功后调用 [commit]。 */
    fun createStaging(runtime: PluginRuntime): File {
        val fingerprint = fingerprint(runtime)
        val dir = File(stagingDir, "${runtime.pluginId}-${UUID.randomUUID()}")
        if (!dir.mkdirs()) throw IOException("无法创建依赖暂存目录")
        dependencyFiles(runtime.dependencies?.file).forEach { file ->
            file.copyTo(File(dir, file.name), overwrite = true)
        }
        return dir
    }

    fun capture(runtime: PluginRuntime, fingerprint: String): File {
        val source = dependencyOutput(runtime.installDir)
        if (!source.exists()) throw IOException("依赖安装结果不存在")
        val staging = File(stagingDir, "${runtime.pluginId}-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("无法创建依赖暂存目录")
        val targetOutput = File(staging, source.name)
        source.copyRecursively(targetOutput, overwrite = true)
        return commit(runtime.pluginId, fingerprint, staging)
    }

    fun link(pluginDir: File, fingerprint: String, pluginId: String) {
        val cached = directory(pluginId, fingerprint)
        if (!isReady(pluginId, fingerprint)) throw IOException("依赖缓存未准备完成")
        val output = dependencyOutput(cached)
        val link = File(pluginDir, output.name)
        deleteTree(link)
        val relative = pluginDir.toPath().relativize(output.toPath())
        Files.createSymbolicLink(link.toPath(), relative)
    }

    fun commit(pluginId: String, fingerprint: String, staging: File): File {
        val target = directory(pluginId, fingerprint)
        target.parentFile?.mkdirs()
        if (isReady(pluginId, fingerprint)) {
            deleteTree(staging)
            return target
        }
        deleteTree(target)
        if (!staging.renameTo(target)) throw IOException("无法提交插件依赖缓存")
        File(target, COMPLETE_MARKER).writeText("$fingerprint\n")
        return target
    }

    fun cleanupStaging() {
        stagingDir.listFiles().orEmpty().forEach(::deleteTree)
    }

    fun dependencyOutput(root: File): File = File(root, "node_modules").takeIf { it.exists() }
        ?: File(root, ".venv").takeIf { it.exists() }
        ?: File(root, "site-packages")

    fun deleteForPlugin(pluginId: String) {
        deleteTree(File(dependenciesDir, pluginId))
    }

    fun deleteUnreferenced(referenced: Set<Pair<String, String>>) {
        dependenciesDir.listFiles().orEmpty()
            .filter { it.name != ".staging" }
            .forEach { pluginDir ->
                pluginDir.listFiles().orEmpty().forEach { cached ->
                    if (pluginDir.name to cached.name !in referenced) deleteTree(cached)
                }
                if (pluginDir.listFiles().isNullOrEmpty()) pluginDir.delete()
            }
    }

    private fun dependencyFiles(file: File?): List<File> {
        if (file == null) return emptyList()
        val parent = file.parentFile ?: return listOf(file)
        return listOf(file) + listOf(
            "package-lock.json",
            "npm-shrinkwrap.json",
            "requirements.lock"
        ).map { File(parent, it) }.filter { it.isFile && it != file }
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        const val COMPLETE_MARKER = ".complete"
    }
}
