package com.aicode.feature.plugin.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginSettingsPage
import com.aicode.feature.plugin.runtime.PluginEventBus
import com.aicode.feature.plugin.runtime.PluginProcessException
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginShizukuException
import com.aicode.feature.plugin.runtime.PluginShizukuRunner
import com.aicode.feature.plugin.runtime.PluginTipException
import com.aicode.feature.plugin.runtime.PluginTipManager
import com.aicode.feature.plugin.runtime.PluginTerminalException
import com.aicode.feature.plugin.runtime.PluginTerminalRunner
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件 HTML 页面与宿主之间的桥。
 *
 * 页面通过 `__aicodeBridge.postMessage(json)` 发请求，宿主在 IO 线程处理，
 * 再用 `window.__aicodeResolve(id, payloadJson)` 回传。文件根解析与限制由
 * [PluginHostPaths] 统一提供，与插件进程的 host API 共用同一套规则。
 *
 * 桥只由宿主注入到已启用插件的页面；方法在 WebView 的 JavaBridge 线程调用，
 * 不能在这里直接操作 UI。
 */
class PluginWebBridge(
    private val context: Context,
    private val page: PluginSettingsPage,
    private val dataDir: File,
    private val pluginVersion: String,
    private val languageTag: String,
    private val workspaceName: String,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val commandEngine: CommandEngine,
    private val workspacePath: () -> String,
    private val eventBus: PluginEventBus,
    private val pluginManager: PluginManager,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val terminalRunner: PluginTerminalRunner,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    @Volatile private var theme: () -> String,
    @Volatile private var onClosePage: () -> Unit,
    @Volatile private var post: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostPaths = PluginHostPaths(dataDir, page.installDir, fileAccess, homeResolver)
    @Volatile private var overlayActive = false
    private val eventSubscription = eventBus.subscribe(page.pluginId) { event ->
        val script = "window.__aicodeEvent(${jsString(event.toString())})"
        mainHandler.post { runCatching { post(script) } }
    }

    @JavascriptInterface
    fun postMessage(payload: String) {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val reply = try {
                val data = dispatch(method, params)
                buildJsonObject {
                    put("ok", true)
                    put("data", data)
                }
            } catch (e: PluginHostException) {
                errorReply(e.code, e.message)
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件页面桥调用失败: ${page.pluginId} $method", e)
                errorReply("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            deliver(id, reply)
        }
    }

    fun close() {
        updateOverlay(false, null)
        eventSubscription()
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

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "context" -> buildJsonObject {
            put("pluginId", page.pluginId)
            put("pageId", page.localId)
            put("version", pluginVersion)
            put("languageTag", languageTag)
            put("theme", theme())
            put("workspaceName", workspaceName)
        }

        "toast" -> {
            val message = PluginHostPaths.requireString(params, "message")
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            buildJsonObject { put("ok", true) }
        }

        "ui.close" -> {
            mainHandler.post { onClosePage() }
            buildJsonObject { put("ok", true) }
        }

        "ui.overlay" -> {
            val visible = (params["visible"] as? JsonPrimitive)?.booleanOrNull
                ?: throw PluginHostException("PARAM_INVALID", "visible required")
            val rect = (params["rect"] as? JsonObject)?.let { readOverlayRect(it) }
            updateOverlay(visible, rect)
            buildJsonObject { put("ok", true) }
        }

        "fs.read" -> hostPaths.read(PluginHostPaths.requireString(params, "path"))
        "fs.write" -> hostPaths.write(
            PluginHostPaths.requireString(params, "path"),
            PluginHostPaths.requireString(params, "content")
        )
        "fs.list" -> hostPaths.list(PluginHostPaths.requireString(params, "path"))
        "fs.delete" -> hostPaths.delete(PluginHostPaths.requireString(params, "path"))
        "fs.stat" -> hostPaths.stat(PluginHostPaths.requireString(params, "path"))
        "bash" -> bash(params)
        "shizuku.exec" -> shizukuExec(params)
        "tip" -> tip(params)
        "plugin.invoke" -> invokePlugin(params)
        "terminal.run" -> terminalRun(params)
        "terminal.poll" -> terminalPoll(params)
        "terminal.stop" -> terminalStop(params)
        "terminal.close" -> terminalClose(params)
        else -> throw PluginHostException("METHOD_UNKNOWN", "unknown method: $method")
    }

    /** 页面调用自身插件的常驻进程；不允许跨插件。 */
    private suspend fun invokePlugin(params: JsonObject): JsonElement {
        val handler = PluginHostPaths.requireString(params, "handler").trim()
        if (!HANDLER_NAME.matches(handler)) {
            throw PluginHostException("PARAM_INVALID", "invalid handler")
        }
        val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())
        if (args.toString().length > MAX_INVOKE_ARGS_CHARS) {
            throw PluginHostException("TOO_LARGE", "invoke args too large")
        }
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: ${page.pluginId}")
        val runtime = plugin.content.runtime
            ?: throw PluginHostException("RUNTIME_UNAVAILABLE", "plugin has no runtime")
        return try {
            pluginRuntimeManager.invoke(
                pluginId = page.pluginId,
                version = plugin.record.version,
                runtime = runtime,
                handler = handler,
                args = args,
                method = "ui.invoke",
                timeoutMs = UI_INVOKE_TIMEOUT_MS
            )
        } catch (e: PluginProcessException) {
            throw PluginHostException(e.code, e.message)
        }
    }

    /** 页面请求一条对话内提示；动作按钮会回调页面所属插件的 handler。 */
    private fun tip(params: JsonObject): JsonElement = try {
        tipManager.show(page.pluginId, params)
    } catch (e: PluginTipException) {
        throw PluginHostException(e.code, e.message.orEmpty())
    }

    /** 通过 Shizuku 在宿主 Android 系统上执行命令；范围由插件的清单声明限制。 */    private suspend fun shizukuExec(params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: DEFAULT_SHIZUKU_TIMEOUT_SECONDS
        return try {
            val result = shizukuRunner.exec(page.pluginId, command, timeoutSeconds)
            buildJsonObject {
                put("output", result.output)
                put("exitCode", result.exitCode)
                put("truncated", result.truncated)
            }
        } catch (e: PluginShizukuException) {
            throw PluginHostException(e.code, e.message.orEmpty())
        }
    }

    /** 新建共享终端标签；后续通过 terminal.poll 读取输出。 */    private suspend fun terminalRun(params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command").trim()
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TERMINAL_TITLE_CHARS)
        return try {
            val tabId = terminalRunner.start(page.pluginId, command, title)
            buildJsonObject {
                put("tabId", tabId)
                put("state", "running")
            }
        } catch (e: PluginTerminalException) {
            throw PluginHostException(e.code, e.message)
        }
    }

    private suspend fun terminalPoll(params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val offset = (params["offset"] as? JsonPrimitive)?.longOrNull ?: 0L
        return try {
            val poll = terminalRunner.poll(page.pluginId, tabId, offset)
            buildJsonObject {
                put("output", poll.output)
                put("offset", poll.offset)
                put("state", poll.state)
                poll.exitCode?.let { put("exitCode", it) }
                put("truncated", poll.truncated)
            }
        } catch (e: PluginTerminalException) {
            throw PluginHostException(e.code, e.message)
        }
    }

    private suspend fun terminalStop(params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        withTerminalErrors { terminalRunner.stop(page.pluginId, tabId) }
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun terminalClose(params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        withTerminalErrors { terminalRunner.close(page.pluginId, tabId) }
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun <T> withTerminalErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: PluginTerminalException) {
        throw PluginHostException(e.code, e.message)
    }

    /** 一次性 Shell 命令：在插件页面所属工作区执行，输出与退出码原样返回。 */
    private suspend fun bash(params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        if (command.isBlank()) throw PluginHostException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginHostException("PARAM_INVALID", "command too long")
        }
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceIn(1L, PluginHostPaths.MAX_BASH_TIMEOUT_SECONDS) * 1000L
        val result = commandEngine.runCommandSyncWithExit(command, workspacePath(), timeoutMs)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.outputTruncated)
        }
    }

    /** 页面弹层开关与位置；多个页面同时打开时由控制器逐个挖洞。 */
    @Synchronized
    private fun updateOverlay(visible: Boolean, rect: PluginOverlayController.CssRect?) {
        if (!visible && !overlayActive) return
        overlayActive = visible
        PluginOverlayController.setOverlay(page.cacheKey, visible, rect)
    }

    private fun readOverlayRect(obj: JsonObject): PluginOverlayController.CssRect? {
        fun value(key: String): Float? = (obj[key] as? JsonPrimitive)?.floatOrNull
        val left = value("left") ?: return null
        val top = value("top") ?: return null
        val width = value("width") ?: return null
        val height = value("height") ?: return null
        if (width <= 0f || height <= 0f) return null
        return PluginOverlayController.CssRect(
            left = left,
            top = top,
            width = width,
            height = height,
            topRadius = value("topRadius") ?: 0f,
            bottomRadius = value("bottomRadius") ?: 0f,
            coversBottom = (obj["coversBottom"] as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    private fun errorReply(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun deliver(id: String, payload: JsonObject) {
        val script = "window.__aicodeResolve(${jsString(id)}, ${jsString(payload.toString())})"
        // 预热时 WebView 还没 attach，View.post 会一直排队；直接走主线程 Handler，不依赖窗口。
        mainHandler.post { post(script) }
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

    private companion object {
        const val TAG = "PluginWebBridge"
        const val UI_INVOKE_TIMEOUT_MS = 60_000L
        const val MAX_INVOKE_ARGS_CHARS = 256 * 1024
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
        val HANDLER_NAME = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    }
}
