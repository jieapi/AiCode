package com.aicode.feature.plugin.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.session.AgentRunEvent
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.overlay.PluginOverlayException
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import com.aicode.feature.plugin.runtime.PluginEventBus
import com.aicode.feature.plugin.runtime.PluginChatService
import com.aicode.feature.plugin.runtime.PluginModelService
import com.aicode.feature.plugin.runtime.PluginHttpService
import com.aicode.feature.plugin.runtime.PluginIntrospectionService
import com.aicode.feature.plugin.runtime.PluginProcessException
import com.aicode.feature.plugin.runtime.PluginProviderService
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginSecretStore
import com.aicode.feature.plugin.runtime.PluginShizukuException
import com.aicode.feature.plugin.runtime.PluginShizukuRunner
import com.aicode.feature.plugin.runtime.PluginSystemService
import com.aicode.feature.plugin.runtime.PluginTipException
import com.aicode.feature.plugin.runtime.PluginTipManager
import com.aicode.feature.plugin.runtime.PluginTerminalException
import com.aicode.feature.plugin.runtime.PluginTerminalRunner
import com.aicode.feature.plugin.runtime.PluginToolService
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * 再用 `window.__aicodeResolve(id, payloadJson)` 回传。路径解析与限制由
 * [PluginHostPaths] 统一提供，与插件进程的 host API 共用同一套规则。
 *
 * 桥只由宿主注入到已启用插件的页面；消息可能来自 WebMessageListener 回调或兼容回退的
 * JavaBridge 线程，处理统一切到 IO，不能在这里直接操作 UI。
 */
class PluginWebBridge(
    private val context: Context,
    private val page: PluginPage,
    private val pluginVersion: String,
    private val languageTag: String,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val workspacePath: () -> String,
    private val eventBus: PluginEventBus,
    private val pluginManager: PluginManager,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val modelService: PluginModelService,
    private val agentRunStateStore: AgentRunStateStore,
    private val hostApiDispatcher: com.aicode.feature.plugin.runtime.PluginHostApiDispatcher,
    private val environment: PluginPageEnvironment,
    @Volatile private var theme: () -> String,
    @Volatile private var onClosePage: () -> Unit,
    @Volatile private var post: (String) -> Unit,
    /** 页面绑定的入口上下文；缓存复用时由页面宿主刷新。 */
    @Volatile private var pageContext: () -> PluginEntryContext = { PluginEntryContext() },
    @Volatile private var lastPageContext: PluginEntryContext? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installation = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId }?.record
    internal val isActive: Boolean get() = isAuthorized()
    private val hostPaths = PluginHostPaths(
        fileAccess,
        homeResolver,
        environment::workspaceRoot,
        page.pluginId
    )
    @Volatile private var overlayActive = false
    @Volatile private var visible = false
    @Volatile private var closed = false
    @Volatile private var contextKey = environment.contextKey()
    private data class QueuedEvent(val script: String, val contextKey: String?, val reply: Boolean)
    private val eventLock = Any()
    private val queuedEvents = ArrayDeque<QueuedEvent>()
    private var eventFlushScheduled = false
    private val eventFlush = Runnable { flushEvents() }
    private val fileSubscriptions = PluginFileSubscriptions(
        scope = scope,
        watch = { path, recursive -> environment.watch(hostPaths, path, recursive) },
        emit = { payload -> postEvent("fs.changed", payload) }
    )
    private val eventSubscription = eventBus.subscribe(page.pluginId) { event ->
        postEvent(event)
    }
    private val runSubscription = scope.launch {
        agentRunStateStore.events.collect { event ->
            when (event) {
                is AgentRunEvent.Started -> postEvent("chat.run.started", buildJsonObject {
                    put("sessionId", event.sessionId)
                    put("startedAt", event.startedAt)
                })
                is AgentRunEvent.Finished -> postEvent("chat.run.finished", buildJsonObject {
                    put("sessionId", event.sessionId)
                    put("status", event.status)
                    event.error?.let { put("error", it) }
                    put("finishedAt", event.finishedAt)
                })
            }
        }
    }

    init {
        // 状态发布时立即关闭旧桥，避免快速停用再启用被 StateFlow 合并后恢复旧授权。
        scope.launch(Dispatchers.Unconfined) {
            pluginManager.plugins.collect {
                if (!isAuthorized()) {
                    val wasVisible = visible
                    close()
                    mainHandler.post {
                        PluginWebViewCache.invalidateBridge(this@PluginWebBridge)
                        if (wasVisible) onClosePage()
                    }
                }
            }
        }
        scope.launch {
            environment.changes.collect {
                val key = environment.contextKey()
                if (contextKey != key) {
                    contextKey = key
                    fileSubscriptions.setActive(visible, restart = true)
                    postLifecycle("contextChanged")
                }
            }
        }
    }

    @JavascriptInterface
    fun postMessage(payload: String) {
        val requestContext = environment.contextKey()
        scope.launch {
            val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return@launch
            val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return@launch
            val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return@launch
            val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
            val reply = try {
                if (method.startsWith("fs.") && method != "fs.unwatch") {
                    val expected = (params["contextKey"] as? JsonPrimitive)?.contentOrNull
                    if (requestContext != environment.contextKey() ||
                        (expected != null && expected != requestContext)
                    ) {
                        throw PluginHostException("STALE_CONTEXT", "page context has changed")
                    }
                }
                val data = dispatch(method, params)
                if (method in setOf("fs.read", "fs.list", "fs.stat") &&
                    requestContext != environment.contextKey()
                ) {
                    throw PluginHostException("STALE_CONTEXT", "page context has changed")
                }
                buildJsonObject {
                    put("ok", true)
                    put("data", data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginHostException) {
                errorReply(e.code, e.message)
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件页面桥调用失败: ${page.pluginId} $method", e)
                errorReply("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            deliver(id, reply)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        visible = false
        synchronized(eventLock) {
            queuedEvents.clear()
            eventFlushScheduled = false
            mainHandler.removeCallbacks(eventFlush)
        }
        modelService.closeOrigin("page:${page.cacheKey}")
        fileSubscriptions.close()
        updateOverlay(false, null)
        eventSubscription()
        scope.cancel()
    }

    fun setVisible(value: Boolean, reason: String) {
        if (closed || (!value && !visible)) return
        visible = value
        val key = environment.contextKey()
        val changed = contextKey != key
        contextKey = key
        fileSubscriptions.setActive(value, restart = changed)
        if (changed) postLifecycle("contextChanged")
        postLifecycle(reason)
    }

    /**
     * 插件数据目录在当前执行环境中的真实路径。进程侧同一路径通过 AICODE_DATA_DIR 提供；
     * 本地为容器内 `/…/.aicode/plugins/data/<id>`，远程落在服务器同名目录。
     */
    private fun dataDirPath(): String = PluginHostPaths.dataRoot(homeResolver, page.pluginId)

    private fun postLifecycle(reason: String) {
        postEvent("ui.lifecycle", buildJsonObject {
            put("visible", visible)
            put("reason", reason)
            put("contextKey", contextKey)
        })
    }

    private fun postEvent(name: String, payload: JsonObject) {
        postEvent(buildJsonObject {
            put("name", name)
            put("payload", if (name == "fs.changed" && "contextKey" !in payload) {
                buildJsonObject {
                    payload.forEach { (key, value) -> put(key, value) }
                    put("contextKey", environment.contextKey())
                }
            } else payload)
        })
    }

    private fun postEvent(event: JsonObject) {
        if (closed) return
        val payload = event["payload"] as? JsonObject
        val contextual = (event["name"] as? JsonPrimitive)?.contentOrNull in setOf("fs.changed", "ui.lifecycle")
        val key = if (contextual) (payload?.get("contextKey") as? JsonPrimitive)?.contentOrNull else null
        enqueueScript("try{window.__aicodeEvent(${jsString(event.toString())})}catch(e){}", key)
    }

    private fun enqueueScript(script: String, contextKey: String? = null, immediate: Boolean = false) {
        synchronized(eventLock) {
            if (closed) return
            queuedEvents.addLast(QueuedEvent(script, contextKey, reply = immediate))
            if (immediate) {
                mainHandler.removeCallbacks(eventFlush)
                eventFlushScheduled = true
                mainHandler.post(eventFlush)
            } else if (!eventFlushScheduled) {
                eventFlushScheduled = true
                mainHandler.postDelayed(eventFlush, EVENT_BATCH_WINDOW_MS)
            }
        }
    }

    private fun flushEvents() {
        val batch = synchronized(eventLock) {
            val events = ArrayList<QueuedEvent>()
            var chars = 0
            while (queuedEvents.isNotEmpty() && events.size < MAX_EVENT_BATCH_SIZE) {
                val next = queuedEvents.first()
                // Promise 回复单独执行，保留其微任务先于后续事件处理的顺序。
                if (events.isNotEmpty() && (events.first().reply || next.reply ||
                        chars + next.script.length > MAX_EVENT_BATCH_CHARS)) break
                events += queuedEvents.removeFirst()
                chars += next.script.length
            }
            if (queuedEvents.isEmpty()) {
                eventFlushScheduled = false
            } else {
                mainHandler.post(eventFlush)
            }
            events
        }
        if (batch.isEmpty() || !isAuthorized()) return
        val key = environment.contextKey()
        val script = batch.asSequence()
            .filter { it.contextKey == null || it.contextKey == key }
            .joinToString(";\n") { it.script }
        if (script.isNotEmpty()) runCatching { post(script) }
    }

    /**
     * WebView 缓存复用时页面持有的是同一个桥实例（注入对象只能在页面加载时出现），
     * 重新进入页面要把回调换成当前组合的。
     */
    fun updateCallbacks(
        theme: () -> String,
        onClosePage: () -> Unit,
        post: (String) -> Unit,
        pageContext: () -> PluginEntryContext = { PluginEntryContext() }
    ) {
        this.theme = theme
        this.onClosePage = onClosePage
        this.post = post
        this.pageContext = pageContext
        val next = pageContext()
        if (lastPageContext != null && lastPageContext != next) postLifecycle("contextChanged")
        lastPageContext = next
    }

    private fun isAuthorized(): Boolean {
        if (closed) return false
        val expected = installation ?: return false
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == page.pluginId && it.isActive }
            ?: return false
        return plugin.record.version == pluginVersion &&
            plugin.record.version == expected.version &&
            plugin.record.installedAt == expected.installedAt &&
            plugin.record.archiveSha256 == expected.archiveSha256 &&
            plugin.record.installRevision == expected.installRevision
    }

    internal suspend fun dispatch(method: String, params: JsonObject): JsonElement {
        if (!isAuthorized()) {
            throw PluginHostException("PLUGIN_INACTIVE", "plugin page is no longer active: ${page.pluginId}")
        }
        return when (method) {
            "context" -> buildJsonObject {
                put("pluginId", page.pluginId)
                put("pageId", page.localId)
                page.slot?.let { put("slot", it) }
                val ctx = pageContext()
                ctx.sessionId?.let { put("sessionId", it) }
                ctx.path?.let { put("path", it) }
                ctx.isDirectory?.let { put("isDirectory", it) }
                ctx.selection?.let { put("selection", it) }
                ctx.language?.let { put("language", it) }
                ctx.lineStart?.let { put("lineStart", it) }
                ctx.lineEnd?.let { put("lineEnd", it) }
                put("version", pluginVersion)
                put("languageTag", languageTag)
                put("theme", theme())
                put("workspaceName", environment.workspaceName())
                put("contextKey", environment.contextKey())
                put("visible", visible)
                put("home", homeResolver.home())
                put("workspace", environment.workspaceRoot())
                put("dataDir", dataDirPath())
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
            "fs.watch" -> {
                val id = PluginHostPaths.requireString(params, "subscriptionId")
                val path = PluginHostPaths.requireString(params, "path")
                val recursive = params["recursive"]?.let {
                    (it as? JsonPrimitive)?.booleanOrNull
                        ?: throw PluginHostException("PARAM_INVALID", "recursive must be a boolean")
                } ?: false
                fileSubscriptions.add(id, path, recursive)
                buildJsonObject { put("subscriptionId", id) }
            }
            "fs.unwatch" -> {
                fileSubscriptions.remove(PluginHostPaths.requireString(params, "subscriptionId"))
                buildJsonObject { put("ok", true) }
            }
            "plugin.invoke" -> invokePlugin(params)
            else -> hostApiDispatcher.dispatch(
                pluginId = page.pluginId,
                method = method,
                params = params,
                origin = com.aicode.feature.plugin.runtime.PluginHostApiOrigin.PAGE,
                workspaceRoot = workspacePath,
                modelOrigin = "page:${page.cacheKey}"
            )
        }
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
        val script = "try{window.__aicodeResolve(${jsString(id)}, ${jsString(payload.toString())})}catch(e){}"
        // 回复不能越过已经排队的事件；不依赖 View.post，以兼容尚未 attach 的缓存实例。
        enqueueScript(script, immediate = true)
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
        const val EVENT_BATCH_WINDOW_MS = 16L
        const val MAX_EVENT_BATCH_SIZE = 32
        const val MAX_EVENT_BATCH_CHARS = 64 * 1024
        const val UI_INVOKE_TIMEOUT_MS = 60_000L
        const val MAX_INVOKE_ARGS_CHARS = 256 * 1024
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
        val HANDLER_NAME = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    }
}
