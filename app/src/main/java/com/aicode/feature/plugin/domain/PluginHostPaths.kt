package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.Base64
import kotlin.io.FileAlreadyExistsException as KotlinFileAlreadyExistsException
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
 * 插件可访问的文件路径是当前执行环境的真实绝对路径：本地模式为容器内路径（与终端里看到的一致），
 * SSH 模式为远端服务器路径。唯一的虚拟根是 `data://`，指向插件自己的数据目录，
 * 与插件 ID、执行环境解耦；改写插件 ID 不需要改路径写法。
 *
 * `~` 展开到当前环境的 home，裸相对路径挂到工作区根。本地模式下真实路径经
 * [com.aicode.feature.workspace.domain.WorkspacePathMapper] 映射，只会落到容器 rootfs、工作区与
 * `~/.aicode`，宿主 App 的其它私有目录没有对应的容器路径，因此天然不可达。
 */
class PluginHostPaths(
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val workspaceRoot: () -> String,
    private val pluginId: String
) {

    fun read(path: String): JsonElement {
        val target = resolve(path)
        if (!fileAccess.exists(target) || fileAccess.isDirectory(target)) {
            throw PluginHostException("NOT_FOUND", "not a file: $path")
        }
        val size = fileAccess.fileSize(target)
        if (size > MAX_READ_BYTES) throw PluginHostException("TOO_LARGE", "file too large: $path")
        return buildJsonObject {
            put("content", mapIoError(path) { fileAccess.readFile(target) })
            put("size", size)
        }
    }

    fun write(path: String, content: String): JsonElement {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) throw PluginHostException("TOO_LARGE", "content too large: $path")
        val target = resolveWritable(path)
        fileAccess.parentPath(target)?.let { fileAccess.mkdirs(it) }
        mapIoError(path) { fileAccess.writeFile(target, content) }
        return ok()
    }

    /** 创建目录（含父目录）；已存在的目录视为成功。 */
    fun mkdir(path: String): JsonElement {
        val target = resolveWritable(path)
        mapIoError(path) { fileAccess.mkdirs(target) }
        return ok()
    }

    /** 在现有文本后追加；现有内容与新内容合计不超过 [MAX_WRITE_BYTES]。 */
    fun append(path: String, content: String): JsonElement {
        val tail = content.toByteArray(Charsets.UTF_8)
        if (tail.size > MAX_WRITE_BYTES) throw PluginHostException("TOO_LARGE", "content too large: $path")
        val target = resolveWritable(path)
        val head = when {
            !fileAccess.exists(target) -> ""
            !fileAccess.isFile(target) -> throw PluginHostException("NOT_FOUND", "not a file: $path")
            fileAccess.fileSize(target) + tail.size > MAX_WRITE_BYTES ->
                throw PluginHostException("TOO_LARGE", "file too large: $path")
            else -> mapIoError(path) { fileAccess.readFile(target) }
        }
        return write(path, head + content)
    }

    /** 读取原始字节并以 Base64 返回；单文件受 [MAX_READ_BYTES] 限制。 */
    fun readBinary(path: String): JsonElement = readBinary(path, MAX_READ_BYTES)

    /** 读取原始字节并以 Base64 返回；单文件大小上限由调用方按场景给出（如模型图片输入）。 */
    fun readBinary(path: String, maxBytes: Long): JsonElement {
        val target = resolve(path)
        if (!fileAccess.exists(target) || fileAccess.isDirectory(target)) {
            throw PluginHostException("NOT_FOUND", "not a file: $path")
        }
        val size = fileAccess.fileSize(target)
        if (size > maxBytes) throw PluginHostException("TOO_LARGE", "file too large: $path")
        val bytes = mapIoError(path) { fileAccess.readBytes(target) }
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
        val target = resolveWritable(path)
        fileAccess.parentPath(target)?.let { fileAccess.mkdirs(it) }
        mapIoError(path) { fileAccess.writeBytes(target, bytes) }
        return ok()
    }

    /** 移动/重命名；源与目标在同一后端，不能是根，也不能把目录移进自身。 */
    fun rename(from: String, to: String): JsonElement {
        val source = resolveWritable(from, followFinalLink = false)
        val destination = resolveWritable(to, followFinalLink = false)
        assertOutsideSource(source, destination, to, followSourceLink = false)
        mapIoError(to) { fileAccess.rename(source, destination) }
        return ok()
    }

    /** 复制文件或目录；不能是根，也不能把目录复制进自身。 */
    fun copy(from: String, to: String, overwrite: Boolean): JsonElement {
        val source = resolve(from)
        val destination = resolveWritable(to, followFinalLink = false)
        assertOutsideSource(source, destination, to, followSourceLink = true)
        mapIoError(to) { fileAccess.copy(source, destination, overwrite) }
        return ok()
    }

    fun list(path: String): JsonElement {
        val target = resolve(path)
        if (!fileAccess.isDirectory(target)) throw PluginHostException("NOT_FOUND", "not a directory: $path")
        val entries = mapIoError(path) { fileAccess.listFiles(target) }
            .sortedBy { it.name }
            .take(MAX_LIST_ENTRIES)
            .map { entry ->
                buildJsonObject {
                    put("name", entry.name)
                    put("isDir", entry.isDirectory)
                    put("size", entry.size)
                }
            }
        return buildJsonObject { put("entries", JsonArray(entries)) }
    }

    fun delete(path: String): JsonElement {
        val target = resolveWritable(path, followFinalLink = false)
        mapIoError(path) { fileAccess.deleteRecursively(target) }
        return ok()
    }

    fun stat(path: String): JsonElement {
        val target = resolve(path)
        val exists = fileAccess.exists(target)
        return buildJsonObject {
            put("exists", exists)
            put("isDir", exists && fileAccess.isDirectory(target))
            put("size", if (exists && fileAccess.isFile(target)) fileAccess.fileSize(target) else 0L)
        }
    }

    /**
     * 解析订阅目标：本地执行环境返回映射后的宿主文件，远程返回远端路径。
     * 拒绝整个文件系统、home 与 AI 配置根作为订阅范围——递归扫描整棵树会耗尽内存。
     */
    fun watchTarget(
        path: String,
        localFile: (String) -> File?,
        recursive: Boolean = false
    ): PluginWatchTarget {
        val target = resolve(path)
        assertWatchScope(target, recursive)
        val file = localFile(target)
        if (file != null) return PluginWatchTarget.Local(file.canonicalFile)
        return PluginWatchTarget.Remote(target)
    }

    private fun resolveWritable(path: String, followFinalLink: Boolean = true): String {
        val target = resolve(path)
        if (isProtectedRoot(target, followFinalLink)) {
            throw PluginHostException("PATH_DENIED", "cannot modify a root: $path")
        }
        return target
    }

    private fun identity(path: String, followFinalLink: Boolean = true): String =
        mapIoError(path) { fileAccess.resolvePath(path, followFinalLink) }.trimEnd('/').ifEmpty { "/" }

    /** `~` 展开到当前环境的 home；`data://` 映射到插件数据目录；裸相对路径挂到工作区根。 */
    private fun resolve(path: String): String {
        val raw = path.trim()
        if (raw.isEmpty()) throw PluginHostException("PATH_INVALID", "empty path")
        if (raw.any { it.isISOControl() }) {
            throw PluginHostException("PATH_INVALID", "control character in path")
        }
        if (raw.contains('\\')) throw PluginHostException("PATH_INVALID", "backslash path")
        if (raw.startsWith(DATA_SCHEME)) {
            val suffix = raw.removePrefix(DATA_SCHEME).trim('/')
            validateDataSuffix(suffix)
            val root = dataRoot()
            return if (suffix.isEmpty()) root else "$root/$suffix"
        }
        val expanded = homeResolver.expandHome(raw)
        val absolute = if (expanded.startsWith("/")) {
            expanded
        } else {
            workspaceRoot().trimEnd('/') + "/" + expanded
        }
        if (absolute.length > MAX_PATH_LENGTH) throw PluginHostException("PATH_INVALID", "path too long")
        return absolute
    }

    /** `data://` 是插件自己的根，只允许其下的普通段，防止用 `..` 逃到数据目录之外。 */
    private fun validateDataSuffix(suffix: String) {
        if (suffix.isEmpty()) return
        if (suffix.length > MAX_PATH_LENGTH) throw PluginHostException("PATH_INVALID", "path too long")
        if (suffix.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw PluginHostException("PATH_INVALID", "invalid path segment")
        }
    }

    private fun dataRoot(): String = dataRoot(homeResolver, pluginId)

    /**
     * 这些根目录一旦被递归删除或整体覆盖，会丢掉工作区、AI 配置或全部插件，
     * 因此所有写操作（写入、移动、复制目标、删除）都必须拦住根本身，子路径不受影响。
     */
    private fun isProtectedRoot(path: String, followFinalLink: Boolean): Boolean {
        val target = identity(path, followFinalLink)
        val aicode = homeResolver.aicodeRoot().trimEnd('/')
        val protected = listOf(
            "/",
            homeResolver.home(),
            aicode,
            workspaceRoot(),
            dataRoot(),
            "$aicode/plugins",
            "$aicode/plugins/installed",
            "$aicode/plugins/staging",
            "$aicode/plugins/data",
            "$aicode/plugin-dev"
        )
        return protected.any { identity(it) == target }
    }

    /**
     * 复制/移动不能自我嵌套：目标在源内部会无限递归，源在目标内部时覆盖式复制会先删掉源。
     * 只在源确实是目录时判定，源不存在交给后端报 NOT_FOUND。
     */
    private fun assertOutsideSource(source: String, destination: String, to: String, followSourceLink: Boolean) {
        val sourceDir = identity(source, followSourceLink)
        val destinationDir = identity(destination, followFinalLink = false)
        val root = identity("/")
        if (sourceDir == root || destinationDir == root) {
            throw PluginHostException("PATH_DENIED", "cannot use a root: $to")
        }
        if (sourceDir == destinationDir) {
            throw PluginHostException("PATH_DENIED", "destination is the source: $to")
        }
        if (fileAccess.isDirectory(source) && (followSourceLink || sourceDir == identity(source)) &&
            (destinationDir.startsWith("$sourceDir/") || sourceDir.startsWith("$destinationDir/"))
        ) {
            throw PluginHostException("PATH_DENIED", "source and destination are nested: $to")
        }
    }

    private fun assertWatchScope(path: String, recursive: Boolean) {
        val trimmed = identity(path)
        val home = identity(homeResolver.home())
        val aicode = identity(homeResolver.aicodeRoot())
        if (trimmed == identity("/") || trimmed == home || trimmed == aicode) {
            throw PluginHostException("PATH_DENIED", "watch range is too broad: $path")
        }
        if (!recursive) return
        if (home.startsWith("$trimmed/") || aicode.startsWith("$trimmed/")) {
            throw PluginHostException("PATH_DENIED", "watch range includes $home: $path")
        }
    }

    private fun <T> mapIoError(path: String, block: () -> T): T = try {
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

    private fun ok(): JsonElement = buildJsonObject { put("ok", true) }

    companion object {
        const val DATA_SCHEME = "data://"
        const val MAX_PATH_LENGTH = 1024
        const val MAX_READ_BYTES = 512L * 1024
        const val MAX_WRITE_BYTES = 1024L * 1024
        const val MAX_LIST_ENTRIES = 500
        const val MAX_COMMAND_CHARS = 4000
        const val DEFAULT_BASH_TIMEOUT_SECONDS = 30L
        const val MAX_BASH_TIMEOUT_SECONDS = 300L

        /** 参数缺失的稳定错误；桥与进程都直接用这个 code。 */
        fun requireString(params: JsonObject, key: String): String =
            (params[key] as? JsonPrimitive)?.contentOrNull
                ?: throw PluginHostException("PARAM_MISSING", "missing parameter: $key")

        /** 插件数据目录在当前执行环境中的真实路径；`data://` 映射到这里。 */
        fun dataRoot(homeResolver: PathHomeResolver, pluginId: String): String =
            homeResolver.aicodeRoot().trimEnd('/') + "/plugins/data/" + pluginId
    }
}
