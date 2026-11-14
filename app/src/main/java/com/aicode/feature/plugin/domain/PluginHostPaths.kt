package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.io.FileAlreadyExistsException as KotlinFileAlreadyExistsException
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

sealed interface PluginWatchTarget {
    data class Local(val file: File) : PluginWatchTarget
    data class Remote(val path: String) : PluginWatchTarget
}

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

    /** 创建目录（含父目录）；已存在的目录视为成功。 */
    fun mkdir(path: String): JsonElement {
        val target = resolve(path)
        requireWrite(target)
        when (target) {
            is Target.Local -> {
                if (target.file.exists() && !target.file.isDirectory) {
                    throw PluginHostException("NOT_FOUND", "not a directory: $path")
                }
                if (!target.file.isDirectory && !target.file.mkdirs()) {
                    throw PluginHostException("IO_ERROR", "cannot create directory: $path")
                }
            }

            is Target.Remote -> fileAccess.mkdirs(target.path)
        }
        return buildJsonObject { put("ok", true) }
    }

    /** 在现有文本后追加；现有内容与新内容合计不超过 [MAX_WRITE_BYTES]。 */
    fun append(path: String, content: String): JsonElement {
        val tail = content.toByteArray(Charsets.UTF_8)
        if (tail.size > MAX_WRITE_BYTES) throw PluginHostException("TOO_LARGE", "content too large: $path")
        val target = resolve(path)
        requireWrite(target)
        val head = when (target) {
            is Target.Local -> {
                val file = target.file
                when {
                    !file.exists() -> ""
                    !file.isFile -> throw PluginHostException("NOT_FOUND", "not a file: $path")
                    file.length() + tail.size > MAX_WRITE_BYTES ->
                        throw PluginHostException("TOO_LARGE", "file too large: $path")
                    else -> file.readText(Charsets.UTF_8)
                }
            }

            is Target.Remote -> when {
                !fileAccess.exists(target.path) -> ""
                !fileAccess.isFile(target.path) -> throw PluginHostException("NOT_FOUND", "not a file: $path")
                fileAccess.fileSize(target.path) + tail.size > MAX_WRITE_BYTES ->
                    throw PluginHostException("TOO_LARGE", "file too large: $path")
                else -> fileAccess.readFile(target.path)
            }
        }
        return write(path, head + content)
    }

    /** 读取原始字节并以 Base64 返回；单文件受 [MAX_READ_BYTES] 限制。 */
    fun readBinary(path: String): JsonElement {
        val target = resolve(path)
        requireRead(target)
        val bytes = when (target) {
            is Target.Local -> {
                if (!target.file.isFile) throw PluginHostException("NOT_FOUND", "not a file: $path")
                if (target.file.length() > MAX_READ_BYTES) {
                    throw PluginHostException("TOO_LARGE", "file too large: $path")
                }
                target.file.readBytes()
            }

            is Target.Remote -> {
                if (!fileAccess.exists(target.path) || fileAccess.isDirectory(target.path)) {
                    throw PluginHostException("NOT_FOUND", "not a file: $path")
                }
                if (fileAccess.fileSize(target.path) > MAX_READ_BYTES) {
                    throw PluginHostException("TOO_LARGE", "file too large: $path")
                }
                fileAccess.readBytes(target.path)
            }
        }
        return buildJsonObject {
            put("base64", Base64.getEncoder().encodeToString(bytes))
            put("size", bytes.size)
        }
    }

    /** 写入 Base64 解码后的字节；解码后受 [MAX_WRITE_BYTES] 限制。 */
    fun writeBinary(path: String, base64: String): JsonElement {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw PluginHostException("PARAM_INVALID", "invalid base64: $path")
        }
        if (bytes.size > MAX_WRITE_BYTES) throw PluginHostException("TOO_LARGE", "content too large: $path")
        val target = resolve(path)
        requireWrite(target)
        when (target) {
            is Target.Local -> atomicWrite(target.file, bytes)

            is Target.Remote -> {
                fileAccess.parentPath(target.path)?.let { fileAccess.mkdirs(it) }
                fileAccess.writeBytes(target.path, bytes)
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    /** 移动/重命名；源与目标必须属于同类后端（都是本地根或都是远程根）。 */
    fun rename(from: String, to: String): JsonElement {
        val source = resolve(from)
        val destination = resolve(to)
        requireWrite(source)
        requireWrite(destination)
        if (source.relative.isEmpty()) throw PluginHostException("PATH_DENIED", "cannot rename a root")
        if (destination.relative.isEmpty()) throw PluginHostException("PATH_DENIED", "cannot overwrite a root")
        assertOutsideSource(source, destination, to)
        when (source) {
            is Target.Local -> {
                val target = destination as? Target.Local
                    ?: throw PluginHostException("UNSUPPORTED", "cannot move across local and remote roots")
                renameLocal(source.file, target.file, from, to)
            }

            is Target.Remote -> {
                val target = destination as? Target.Remote
                    ?: throw PluginHostException("UNSUPPORTED", "cannot move across local and remote roots")
                mapIoError(to) { fileAccess.rename(source.path, target.path) }
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    /** 复制文件或目录；源与目标必须属于同类后端。 */
    fun copy(from: String, to: String, overwrite: Boolean): JsonElement {
        val source = resolve(from)
        val destination = resolve(to)
        requireRead(source)
        requireWrite(destination)
        if (source.relative.isEmpty()) throw PluginHostException("PATH_DENIED", "cannot copy a root")
        if (destination.relative.isEmpty()) throw PluginHostException("PATH_DENIED", "cannot overwrite a root")
        assertOutsideSource(source, destination, to)
        when (source) {
            is Target.Local -> {
                val target = destination as? Target.Local
                    ?: throw PluginHostException("UNSUPPORTED", "cannot copy across local and remote roots")
                if (!source.file.exists()) throw PluginHostException("NOT_FOUND", "not found: $from")
                if (!overwrite && target.file.exists()) {
                    throw PluginHostException("FILE_EXISTS", "target exists: $to")
                }
                target.file.parentFile?.mkdirs()
                mapIoError(to) {
                    if (source.file.isDirectory) {
                        source.file.copyRecursively(target.file, overwrite)
                    } else {
                        source.file.copyTo(target.file, overwrite)
                    }
                }
            }

            is Target.Remote -> {
                val target = destination as? Target.Remote
                    ?: throw PluginHostException("UNSUPPORTED", "cannot copy across local and remote roots")
                if (!fileAccess.exists(source.path)) throw PluginHostException("NOT_FOUND", "not found: $from")
                fileAccess.parentPath(target.path)?.let { fileAccess.mkdirs(it) }
                mapIoError(to) { fileAccess.copy(source.path, target.path, overwrite) }
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

    fun watchTarget(
        path: String,
        localFile: (String) -> File?,
        recursive: Boolean = false
    ): PluginWatchTarget {
        val target = resolve(path)
        requireRead(target)
        if (target.scheme == "aicode" && target.relative.isEmpty()) {
            throw PluginHostException("PATH_DENIED", "cannot watch the host configuration root")
        }
        val file = when (target) {
            is Target.Local -> target.file
            is Target.Remote -> localFile(target.path)
        }
        if (file != null) {
            val canonical = file.canonicalFile
            if (target is Target.Remote) {
                val root = if (target.scheme == "workspace") WORKSPACE_ROOT else homeResolver.aicodeRoot()
                localFile(root)?.let { mappedRoot ->
                    val expected = inside(mappedRoot, target.relative)
                    if (canonical != expected) {
                        throw PluginHostException("PATH_DENIED", "watch path escapes root")
                    }
                }
            }
            assertLocalWatchRange(canonical, target.scheme, recursive, localFile)
            return PluginWatchTarget.Local(canonical)
        }
        target as Target.Remote
        if (target.scheme == "workspace") {
            val hostRoot = fileAccess.toDisplayPath(homeResolver.aicodeRoot()).trimEnd('/')
            assertWatchAncestor(target.path, hostRoot, recursive)
            if (target.path.startsWith("$hostRoot/")) {
                assertHostProtected(target.path.removePrefix("$hostRoot/"))
            }
        }
        return PluginWatchTarget.Remote(target.path)
    }

    private fun assertLocalWatchRange(
        file: File,
        scheme: String,
        recursive: Boolean,
        localFile: (String) -> File?
    ) {
        val hostRoot = localFile(homeResolver.aicodeRoot())?.canonicalFile ?: return
        val watched = file.toPath()
        assertWatchAncestor(file.path, hostRoot.path, recursive)
        for (name in HOST_PROTECTED) {
            val protected = File(hostRoot, name).canonicalFile.toPath()
            if (((scheme == "aicode" || scheme == "workspace") && watched.startsWith(protected)) ||
                (recursive && protected.startsWith(watched))
            ) {
                throw PluginHostException("READ_ONLY", "watch range includes a protected host path")
            }
        }
    }

    private fun assertWatchAncestor(path: String, hostRoot: String, recursive: Boolean) {
        if (path == hostRoot || (recursive && hostRoot.startsWith(path.trimEnd('/') + "/"))) {
            throw PluginHostException("PATH_DENIED", "watch range includes the host configuration root")
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

    /** 复制/移动不能自我嵌套：目标在源内部会无限递归，源在目标内部时覆盖式复制会先删掉源。 */
    private fun assertOutsideSource(source: Target, destination: Target, to: String) {
        if (source.scheme != destination.scheme) return
        if (source.relative == destination.relative) {
            throw PluginHostException("PATH_DENIED", "destination is the source: $to")
        }
        if (source.relative.startsWith("${destination.relative}/")) {
            throw PluginHostException("PATH_DENIED", "destination contains the source: $to")
        }
        if (source.relative.isEmpty() || !destination.relative.startsWith("${source.relative}/")) return
        val isDirectory = when (source) {
            is Target.Local -> source.file.isDirectory
            is Target.Remote -> fileAccess.isDirectory(source.path)
        }
        if (isDirectory) throw PluginHostException("PATH_DENIED", "destination is inside the source: $to")
    }

    private fun renameLocal(from: File, to: File, fromPath: String, toPath: String) {
        if (!from.exists()) throw PluginHostException("NOT_FOUND", "not found: $fromPath")
        if (to.exists()) throw PluginHostException("FILE_EXISTS", "target exists: $toPath")
        to.parentFile?.mkdirs()
        mapIoError(toPath) {
            if (from.renameTo(to)) return@mapIoError
            // renameTo 跨文件系统会失败，退回复制后删除源。
            if (from.isDirectory) {
                from.copyRecursively(to, overwrite = false)
            } else {
                from.copyTo(to, overwrite = false)
            }
            if (!from.deleteRecursively()) throw IOException("cannot delete source: $fromPath")
        }
    }

    private fun mapIoError(path: String, block: () -> Unit) {
        try {
            block()
        } catch (e: NoSuchFileException) {
            throw PluginHostException("NOT_FOUND", "not found: $path")
        } catch (e: FileAlreadyExistsException) {
            throw PluginHostException("FILE_EXISTS", "target exists: $path")
        } catch (e: KotlinFileAlreadyExistsException) {
            throw PluginHostException("FILE_EXISTS", "target exists: $path")
        } catch (e: IOException) {
            if (e.javaClass.simpleName == "FileAlreadyExistsException") {
                throw PluginHostException("FILE_EXISTS", "target exists: $path")
            }
            throw PluginHostException("IO_ERROR", e.message ?: "io error: $path")
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

    private fun atomicWrite(file: File, content: String) = atomicWrite(file, content.toByteArray(Charsets.UTF_8))

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw PluginHostException("PATH_INVALID", "file has no parent")
        parent.mkdirs()
        val tmp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        tmp.writeBytes(bytes)
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
