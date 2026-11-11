package com.aicode.feature.plugin.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginSettingsPage
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 桥调用错误；[code] 是页面侧可判断的稳定错误码。 */
private class BridgeException(val code: String, override val message: String) : Exception(message)

/**
 * 插件 HTML 页面与宿主之间的桥。
 *
 * 页面通过 `__aicodeBridge.postMessage(json)` 发请求，宿主在 IO 线程处理，
 * 再用 `window.__aicodeResolve(id, payloadJson)` 回传。四个文件根：
 * `data://`（插件数据目录）、`plugin://`（安装目录，只读）、
 * `workspace://`（当前工作区）、`aicode://`（全局 `.aicode`），后两者经 [FileAccessProvider]，
 * 远程模式同样生效。
 *
 * 桥只由宿主注入到已授权插件的页面；方法在 WebView 的 JavaBridge 线程调用，
 * 不能在这里直接操作 UI。
 */
class PluginWebBridge(
    private val context: Context,
    private val page: PluginSettingsPage,
    private val pluginVersion: String,
    private val languageTag: String,
    private val workspaceName: String,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    @Volatile private var theme: () -> String,
    @Volatile private var onClosePage: () -> Unit,
    @Volatile private var post: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dataDir: File = page.dataDir ?: File(context.filesDir, "plugins/data/${page.pluginId}")

    @JavascriptInterface
    fun postMessage(payload: String) {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val reply = try {
                buildJsonObject {
                    put("ok", true)
                    put("data", dispatch(method, params))
                }
            } catch (e: BridgeException) {
                errorReply(e.code, e.message ?: method)
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件页面桥调用失败: ${page.pluginId} $method", e)
                errorReply("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            deliver(id, reply)
        }
    }

    fun close() {
        scope.cancel()
    }

    /**
     * WebView 缓存复用时页面持有的是同一个桥实例（注入对象只能在页面加载时出现），
     * 重新进入页面要把回调换成当前组合的。
     */
    fun updateCallbacks(theme: () -> String, onClosePage: () -> Unit, post: (String) -> Unit) {
        this.theme = theme
        this.onClosePage = onClosePage
        this.post = post
    }

    private fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "context" -> buildJsonObject {
            put("pluginId", page.pluginId)
            put("pageId", page.localId)
            put("version", pluginVersion)
            put("languageTag", languageTag)
            put("theme", theme())
            put("workspaceName", workspaceName)
        }

        "toast" -> {
            val message = params.str("message")
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            buildJsonObject { put("ok", true) }
        }

        "ui.close" -> {
            mainHandler.post { onClosePage() }
            buildJsonObject { put("ok", true) }
        }

        "fs.read" -> read(params.str("path"))
        "fs.write" -> write(params.str("path"), params.str("content"))
        "fs.list" -> list(params.str("path"))
        "fs.delete" -> delete(params.str("path"))
        "fs.stat" -> stat(params.str("path"))
        else -> throw BridgeException("METHOD_UNKNOWN", "unknown method: $method")
    }

    private fun read(path: String): JsonElement {
        return when (val target = resolve(path)) {
            is Target.Local -> {
                if (!target.file.isFile) throw BridgeException("NOT_FOUND", "not a file: $path")
                val size = target.file.length()
                if (size > MAX_READ_BYTES) throw BridgeException("TOO_LARGE", "file too large: $path")
                buildJsonObject {
                    put("content", target.file.readText(Charsets.UTF_8))
                    put("size", size)
                }
            }

            is Target.Remote -> {
                if (!fileAccess.exists(target.path) || fileAccess.isDirectory(target.path)) {
                    throw BridgeException("NOT_FOUND", "not a file: $path")
                }
                val size = fileAccess.fileSize(target.path)
                if (size > MAX_READ_BYTES) throw BridgeException("TOO_LARGE", "file too large: $path")
                buildJsonObject {
                    put("content", fileAccess.readFile(target.path))
                    put("size", size)
                }
            }
        }
    }

    private fun write(path: String, content: String): JsonElement {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) throw BridgeException("TOO_LARGE", "content too large: $path")
        when (val target = resolve(path)) {
            is Target.Local -> {
                if (!target.writable) throw BridgeException("READ_ONLY", "root is read-only: $path")
                atomicWrite(target.file, content)
            }

            is Target.Remote -> {
                if (!target.writable) throw BridgeException("READ_ONLY", "root is read-only: $path")
                fileAccess.parentPath(target.path)?.let { fileAccess.mkdirs(it) }
                fileAccess.writeFile(target.path, content)
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun list(path: String): JsonElement {
        val entries = when (val target = resolve(path)) {
            is Target.Local -> {
                if (!target.file.isDirectory) throw BridgeException("NOT_FOUND", "not a directory: $path")
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
                if (!fileAccess.isDirectory(target.path)) throw BridgeException("NOT_FOUND", "not a directory: $path")
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
        return buildJsonObject {
            put("entries", JsonArray(entries))
        }
    }

    private fun delete(path: String): JsonElement {
        when (val target = resolve(path)) {
            is Target.Local -> {
                if (!target.writable) throw BridgeException("READ_ONLY", "root is read-only: $path")
                target.file.deleteRecursively()
            }

            is Target.Remote -> {
                if (!target.writable) throw BridgeException("READ_ONLY", "root is read-only: $path")
                fileAccess.deleteRecursively(target.path)
            }
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun stat(path: String): JsonElement = when (val target = resolve(path)) {
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

    private fun resolve(raw: String): Target {
        val index = raw.indexOf("://")
        if (index <= 0) throw BridgeException("PATH_INVALID", "path must start with a root: $raw")
        val scheme = raw.substring(0, index)
        var relative = raw.substring(index + 3)
        if (relative.startsWith("/")) throw BridgeException("PATH_INVALID", "absolute path")
        // 目录根常写作 `aicode://memory/`，末尾斜杠等价于不带；不能当作非法空段拒绝。
        relative = relative.trimEnd('/')
        validateRelative(relative)
        return when (scheme) {
            "data" -> Target.Local(inside(dataDir, relative), writable = true)
            "plugin" -> Target.Local(inside(page.installDir, relative), writable = false)
            "workspace" -> Target.Remote(containerPath(WORKSPACE_ROOT, relative), writable = true)
            "aicode" -> Target.Remote(containerPath(homeResolver.aicodeRoot(), relative), writable = true)
            else -> throw BridgeException("PATH_INVALID", "unknown root: $scheme")
        }
    }

    private fun inside(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, relative).canonicalFile
        val prefix = canonicalRoot.path + File.separator
        if (file != canonicalRoot && !file.path.startsWith(prefix)) {
            throw BridgeException("PATH_DENIED", "path escapes root")
        }
        return file
    }

    private fun containerPath(root: String, relative: String): String {
        val trimmed = root.trimEnd('/')
        return if (relative.isEmpty()) trimmed else "$trimmed/$relative"
    }

    private fun validateRelative(relative: String) {
        if (relative.isEmpty()) return
        if (relative.length > MAX_PATH_LENGTH) throw BridgeException("PATH_INVALID", "path too long")
        if (relative.contains('\\')) throw BridgeException("PATH_INVALID", "backslash path")
        if (relative.any { it.isISOControl() }) throw BridgeException("PATH_INVALID", "control character in path")
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw BridgeException("PATH_INVALID", "invalid path segment")
        }
    }

    private fun atomicWrite(file: File, content: String) {
        val parent = file.parentFile ?: throw BridgeException("PATH_INVALID", "file has no parent")
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

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: throw BridgeException("PARAM_MISSING", "missing parameter: $key")

    private fun errorReply(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun deliver(id: String, payload: JsonObject) {
        post("window.__aicodeResolve(${jsString(id)}, ${jsString(payload.toString())})")
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private sealed interface Target {
        data class Local(val file: File, val writable: Boolean) : Target
        data class Remote(val path: String, val writable: Boolean) : Target
    }

    private companion object {
        const val TAG = "PluginWebBridge"
        const val WORKSPACE_ROOT = "~/workspace"
        const val MAX_PATH_LENGTH = 200
        const val MAX_READ_BYTES = 512L * 1024
        const val MAX_WRITE_BYTES = 1024L * 1024
        const val MAX_LIST_ENTRIES = 500
    }
}
