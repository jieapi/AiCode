package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 插件访问宿主文件时的稳定错误码；页面桥与插件进程都按这里的 code 判断。 */
class PluginHostException(val code: String, override val message: String) : Exception(message)

/**
 * 插件可访问的四个文件根，页面桥与插件进程共用同一套解析与限制：
 * `data://`（插件数据目录）、`plugin://`（安装目录，只读）、`workspace://`（当前工作区）、
 * `aicode://`（全局配置，敏感目录受保护）。后两者经 [FileAccessProvider]，SSH 模式同样生效。
 */
class PluginHostPaths(
    private val dataDir: File,
    private val installDir: File,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver
) {

    fun read(path: String): JsonElement {
        val target = resolve(path)
        requireRead(target)
        return when (target) {
            is Target.Local -> {
                if (!target.file.isFile) throw PluginHostException("NOT_FOUND", "not a file: $path")
                val size = target.file.length()
                if (size > MAX_READ_BYTES) throw PluginHostException("TOO_LARGE", "file too large: $path")
                buildJsonObject {
                    put("content", target.file.readText(Charsets.UTF_8))
                    put("size", size)
                }
            }

            is Target.Remote -> {
                if (!fileAccess.exists(target.path) || fileAccess.isDirectory(target.path)) {
                    throw PluginHostException("NOT_FOUND", "not a file: $path")
                }
                val size = fileAccess.fileSize(target.path)
                if (size > MAX_READ_BYTES) throw PluginHostException("TOO_LARGE", "file too large: $path")
                buildJsonObject {
                    put("content", fileAccess.readFile(target.path))
                    put("size", size)
                }
            }
        }
    }

    fun write(path: String, content: String): JsonElement {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) throw PluginHostException("TOO_LARGE", "content too large: $path")
        val target = resolve(path)
        requireWrite(target)
        when (target) {
            is Target.Local -> {
                if (!target.writable) throw PluginHostException("READ_ONLY", "root is read-only: $path")
                atomicWrite(target.file, content)
            }

            is Target.Remote -> {
                if (!target.writable) throw PluginHostException("READ_ONLY", "root is read-only: $path")
                fileAccess.parentPath(target.path)?.let { fileAccess.mkdirs(it) }
                fileAccess.writeFile(target.path, content)
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    fun list(path: String): JsonElement {
        val target = resolve(path)
        requireRead(target)
        val entries = when (target) {
            is Target.Local -> {
                if (!target.file.isDirectory) throw PluginHostException("NOT_FOUND", "not a directory: $path")
                target.file.listFiles().orEmpty()
                    .sortedBy { it.name }
                    .take(MAX_LIST_ENTRIES)
                    .map { file ->
                        buildJsonObject {
                            put("name", file.name)
                            put("isDir", file.isDirectory)
                            put("size", if (file.isFile) file.length() else 0L)
                        }
                    }
            }

            is Target.Remote -> {
                if (!fileAccess.isDirectory(target.path)) {
                    throw PluginHostException("NOT_FOUND", "not a directory: $path")
                }
                fileAccess.listFiles(target.path)
                    .sortedBy { it.name }
                    .take(MAX_LIST_ENTRIES)
                    .map { entry ->
                        buildJsonObject {
                            put("name", entry.name)
                            put("isDir", entry.isDirectory)
                            put("size", entry.size)
                        }
                    }
            }
        }
        return buildJsonObject { put("entries", JsonArray(entries)) }
    }

    fun delete(path: String): JsonElement {
        val target = resolve(path)
        requireWrite(target)
        if (target.relative.isEmpty()) throw PluginHostException("PATH_DENIED", "cannot delete a root")
        when (target) {
            is Target.Local -> {
                if (!target.writable) throw PluginHostException("READ_ONLY", "root is read-only: $path")
                target.file.deleteRecursively()
            }

            is Target.Remote -> {
                if (!target.writable) throw PluginHostException("READ_ONLY", "root is read-only: $path")
                fileAccess.deleteRecursively(target.path)
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    fun stat(path: String): JsonElement {
        val target = resolve(path)
        requireRead(target)
        return when (target) {
            is Target.Local -> buildJsonObject {
                put("exists", target.file.exists())
                put("isDir", target.file.isDirectory)
                put("size", if (target.file.isFile) target.file.length() else 0L)
            }

            is Target.Remote -> buildJsonObject {
                put("exists", fileAccess.exists(target.path))
                put("isDir", fileAccess.isDirectory(target.path))
                put("size", if (fileAccess.isFile(target.path)) fileAccess.fileSize(target.path) else 0L)
            }
        }
    }

    private fun resolve(raw: String): Target {
        val index = raw.indexOf("://")
        if (index <= 0) throw PluginHostException("PATH_INVALID", "path must start with a root: $raw")
        val scheme = raw.substring(0, index)
        var relative = raw.substring(index + 3)
        if (relative.startsWith("/")) throw PluginHostException("PATH_INVALID", "absolute path")
        // 目录根常写作 `aicode://memory/`，末尾斜杠等价于不带；不能当作非法空段拒绝。
        relative = relative.trimEnd('/')
        validateRelative(relative)
        return when (scheme) {
            "data" -> Target.Local(inside(dataDir, relative), scheme, relative, writable = true)
            "plugin" -> Target.Local(inside(installDir, relative), scheme, relative, writable = false)
            "workspace" -> Target.Remote(containerPath(WORKSPACE_ROOT, relative), scheme, relative, writable = true)
            "aicode" -> Target.Remote(containerPath(homeResolver.aicodeRoot(), relative), scheme, relative, writable = true)
            else -> throw PluginHostException("PATH_INVALID", "unknown root: $scheme")
        }
    }

    private fun requireRead(target: Target) {
        if (target.scheme == "aicode") assertHostProtected(target.relative)
    }

    private fun requireWrite(target: Target) {
        when (target.scheme) {
            "data", "workspace" -> Unit
            "plugin" -> throw PluginHostException("READ_ONLY", "plugin root is read-only")
            "aicode" -> assertHostProtected(target.relative)
            else -> throw PluginHostException("PATH_INVALID", "unknown root: ${target.scheme}")
        }
    }

    /**
     * 宿主配置、安装记录与插件包不能经 `aicode://` 读写：插件可以给自己扩权或改写其他插件。
     * 读取插件自身资源走只读的 `plugin://`，写入走 `data://`。
     */
    private fun assertHostProtected(relative: String) {
        val first = relative.substringBefore('/')
        if (first in HOST_PROTECTED) {
            throw PluginHostException("READ_ONLY", "host path is protected: $relative")
        }
    }

    private fun inside(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, relative).canonicalFile
        val prefix = canonicalRoot.path + File.separator
        if (file != canonicalRoot && !file.path.startsWith(prefix)) {
            throw PluginHostException("PATH_DENIED", "path escapes root")
        }
        return file
    }

    private fun containerPath(root: String, relative: String): String {
        val trimmed = root.trimEnd('/')
        return if (relative.isEmpty()) trimmed else "$trimmed/$relative"
    }

    private fun validateRelative(relative: String) {
        if (relative.isEmpty()) return
        if (relative.length > MAX_PATH_LENGTH) throw PluginHostException("PATH_INVALID", "path too long")
        if (relative.contains('\\')) throw PluginHostException("PATH_INVALID", "backslash path")
        if (relative.any { it.isISOControl() }) throw PluginHostException("PATH_INVALID", "control character in path")
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw PluginHostException("PATH_INVALID", "invalid path segment")
        }
    }

    private fun atomicWrite(file: File, content: String) {
        val parent = file.parentFile ?: throw PluginHostException("PATH_INVALID", "file has no parent")
        parent.mkdirs()
        val tmp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private sealed interface Target {
        val scheme: String
        val relative: String
        val writable: Boolean

        data class Local(
            val file: File,
            override val scheme: String,
            override val relative: String,
            override val writable: Boolean
        ) : Target

        data class Remote(
            val path: String,
            override val scheme: String,
            override val relative: String,
            override val writable: Boolean
        ) : Target
    }

    companion object {
        const val WORKSPACE_ROOT = "~/workspace"
        const val MAX_PATH_LENGTH = 200
        const val MAX_READ_BYTES = 512L * 1024
        const val MAX_WRITE_BYTES = 1024L * 1024
        const val MAX_LIST_ENTRIES = 500
        const val MAX_COMMAND_CHARS = 4000
        const val DEFAULT_BASH_TIMEOUT_SECONDS = 30L
        const val MAX_BASH_TIMEOUT_SECONDS = 300L
        val HOST_PROTECTED = setOf("plugins", "permissions.json", "mcp.json", "skills", "agents", "prompts.custom", "docs")

        /** 参数缺失的稳定错误；桥与进程都直接用这个 code。 */
        fun requireString(params: JsonObject, key: String): String =
            (params[key] as? JsonPrimitive)?.contentOrNull
                ?: throw PluginHostException("PARAM_MISSING", "missing parameter: $key")
    }
}
