package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.overlay.AppForegroundMonitor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 单个 hook 的熔断状态；[openUntilMs] 为 0 表示电路关闭。 */
data class HookHealth(
    val consecutiveFailures: Int = 0,
    val openUntilMs: Long = 0L,
    val lastError: String? = null
) {
    fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean = openUntilMs > nowMs
}

/**
 * 把 AI 运行与插件生命周期事件分发给生效插件声明的 hook。
 *
 * 只读事件异步派发，只报告运行元数据，不携带消息正文、工具参数或工具结果；
 * 插件停用/卸载事件在状态变更前等待该插件自己的 hook，超时后继续。
 * 可写事件（`agent.tool.before` / `agent.tool.after`）同步等待插件结果，
 * 携带对应工具的参数或成功结果，由宿主校验后应用。两类 hook 的执行失败都只写日志，
 * 不向主流程抛出；可写 hook 失败时保持原值继续。
 */
@Singleton
class PluginHookDispatcher @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager,
    private val appForegroundMonitor: AppForegroundMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 热路径共享的 hook 并发额度：观察型事件再多，同时执行的运行时 RPC 也不超过这个数。 */
    private val invokeSlots = Semaphore(MAX_CONCURRENT_HOOKS)

    @Volatile
    private var hookSnapshot: HookSnapshot? = null

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    private val _hookHealth = MutableStateFlow<Map<String, HookHealth>>(emptyMap())

    /** 各 hook 的连续失败与熔断状态；`agent.permission.review` 的 allow/deny 不计入失败。 */
    val hookHealth: StateFlow<Map<String, HookHealth>> = _hookHealth.asStateFlow()

    /**
     * 按 [PluginManager.plugins] 的列表实例身份缓存 hook 索引，插件集合不变时不再重扫。
     * 列表每次发布都是新实例，因此身份变化即代表需要重建。
     */
    private fun snapshot(): HookSnapshot {
        pluginManager.ensureLoaded()
        val plugins = pluginManager.plugins.value
        val cached = hookSnapshot
        if (cached != null && cached.plugins === plugins) return cached
        return buildSnapshot(plugins).also { hookSnapshot = it }
    }

    private fun buildSnapshot(plugins: List<InstalledPlugin>): HookSnapshot {
        val byEventMode = HashMap<EventMode, MutableList<Pair<String, PluginHook>>>()
        val activeKeys = HashSet<HookKey>()
        plugins.filter { it.isActive }.forEach { plugin ->
            plugin.content.hooks.forEach { hook ->
                activeKeys += HookKey(hook.key, hook.mode)
                byEventMode.getOrPut(EventMode(hook.event, hook.mode)) { mutableListOf() }
                    .add(plugin.record.version to hook)
            }
        }
        return HookSnapshot(
            plugins = plugins,
            byEventMode = byEventMode.mapValues { (_, hooks) -> hooks.toList() },
            activeKeys = activeKeys
        )
    }

    private data class EventMode(val event: String, val mode: String)

    private data class HookKey(val key: String, val mode: String)

    private class HookSnapshot(
        val plugins: List<InstalledPlugin>,
        val byEventMode: Map<EventMode, List<Pair<String, PluginHook>>>,
        val activeKeys: Set<HookKey>
    )

    /**
     * 是否存在生效的 `agent.http.before` transform hook。拦截器据此在读取请求体之前决定
     * 是否走 hook；索引随 [PluginManager.plugins] 的列表实例更新。
     */
    fun hasHttpHooks(): Boolean = try {
        snapshot().byEventMode[HTTP_BEFORE_TRANSFORM]?.isNotEmpty() == true
    } catch (e: Exception) {
        FileLogger.w(TAG, "读取插件失败: ${e.javaClass.simpleName}")
        false
    }

    /** App 进程启动完成；声明 `app.started` 的插件会在此拉起进程，resident 运行时不受空闲回收。 */
    fun dispatchAppStarted(appVersion: String) {
        dispatch(
            event = PluginHookEvents.APP_STARTED,
            payload = buildJsonObject {
                put("launchId", UUID.randomUUID().toString())
                put("startedAt", System.currentTimeMillis())
                put("appVersion", appVersion)
                put("process", "main")
            }
        )
    }

    /** 插件安装或版本替换完成；只通知该插件自身，[previousVersion] 非空表示更新。 */
    fun dispatchPluginInstalled(pluginId: String, previousVersion: String?) {
        scope.launch {
            val plugin = findPlugin(pluginId) ?: return@launch
            dispatchToPlugin(
                event = PluginHookEvents.PLUGIN_INSTALLED,
                plugin = plugin,
                payload = buildJsonObject {
                    put("pluginId", pluginId)
                    put("version", plugin.record.version)
                    put("installedAt", plugin.record.installedAt)
                    put(
                        "previousVersion",
                        if (previousVersion == null) JsonNull else JsonPrimitive(previousVersion)
                    )
                }
            )
        }
    }

    /** 插件启用生效后；只通知该插件自身。 */
    fun dispatchPluginEnabled(pluginId: String) {
        scope.launch {
            val plugin = findPlugin(pluginId) ?: return@launch
            dispatchToPlugin(
                event = PluginHookEvents.PLUGIN_ENABLED,
                plugin = plugin,
                payload = pluginLifecyclePayload(plugin)
            )
        }
    }

    /**
     * 插件停用生效前；此时插件仍可运行。等待该插件自己的 hook 返回，
     * 超时或失败只记日志，调用方随后继续停用。
     */
    suspend fun dispatchPluginDisabled(pluginId: String) {
        val plugin = findPlugin(pluginId) ?: return
        dispatchToPluginAndAwait(
            event = PluginHookEvents.PLUGIN_DISABLED,
            plugin = plugin,
            payload = pluginLifecyclePayload(plugin)
        )
    }

    /** 插件卸载生效前；安装目录与数据目录尚未删除，等待最多 10 秒后继续卸载。 */
    suspend fun dispatchPluginUninstalled(pluginId: String) {
        val plugin = findPlugin(pluginId) ?: return
        dispatchToPluginAndAwait(
            event = PluginHookEvents.PLUGIN_UNINSTALLED,
            plugin = plugin,
            payload = pluginLifecyclePayload(plugin)
        )
    }

    fun dispatchRunStarted(
        sessionId: String,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?
    ) {
        dispatch(
            event = PluginHookEvents.AGENT_RUN_STARTED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("status", "started")
                put("toolCalls", 0)
                put("errorCode", JsonNull)
            }
        )
    }

    /** [status] 取 `success` / `error` / `cancelled`；[toolCalls] 是本次运行的工具调用次数。 */
    fun dispatchRunFinished(
        sessionId: String,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        toolCalls: Int,
        errorCode: String?
    ) {
        dispatch(
            event = PluginHookEvents.AGENT_RUN_FINISHED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("status", status)
                put("toolCalls", toolCalls)
                put("errorCode", if (errorCode == null) JsonNull else JsonPrimitive(errorCode))
            }
        )
    }

    /** 一次模型请求即将发出。 */
    fun dispatchRequestStarted(
        sessionId: String?,
        runId: String,
        requestId: String,
        mode: String,
        scope: String,
        agentName: String?,
        messageCount: Int,
        toolCount: Int,
        estimatedInputTokens: Int
    ) {
        if (sessionId == null) return
        dispatch(
            event = PluginHookEvents.AGENT_REQUEST_STARTED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("requestId", requestId)
                put("messageCount", messageCount)
                put("toolCount", toolCount)
                put("estimatedInputTokens", estimatedInputTokens)
            }
        )
    }

    /** 一次模型响应结束（成功、失败或取消）。 */
    fun dispatchRequestFinished(
        sessionId: String?,
        runId: String,
        requestId: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        inputTokens: Int,
        outputTokens: Int,
        toolCalls: Int,
        durationMs: Int,
        stopReason: String?,
        errorCode: String?
    ) {
        if (sessionId == null) return
        dispatch(
            event = PluginHookEvents.AGENT_REQUEST_FINISHED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("requestId", requestId)
                put("status", status)
                put("inputTokens", inputTokens)
                put("outputTokens", outputTokens)
                put("toolCalls", toolCalls)
                put("durationMs", durationMs)
                put("stopReason", if (stopReason == null) JsonNull else JsonPrimitive(stopReason))
                put("errorCode", if (errorCode == null) JsonNull else JsonPrimitive(errorCode))
            }
        )
    }

    /** 一次工具调用真正开始执行。 */
    fun dispatchToolStarted(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?
    ) {
        if (sessionId == null) return
        dispatch(
            event = PluginHookEvents.AGENT_TOOL_STARTED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("callId", callId)
                put("toolName", toolName)
            }
        )
    }

    /** 一次工具调用结束；[status] 取 `success` / `error`。 */
    fun dispatchToolFinished(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        status: String,
        durationMs: Int
    ) {
        if (sessionId == null) return
        dispatch(
            event = PluginHookEvents.AGENT_TOOL_FINISHED,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("callId", callId)
                put("toolName", toolName)
                put("status", status)
                put("durationMs", durationMs)
            }
        )
    }

    /** 上下文压缩实际发生。 */
    fun dispatchCompaction(
        sessionId: String?,
        runId: String,
        mode: String,
        scope: String,
        agentName: String?,
        estimatedInputTokens: Int
    ) {
        if (sessionId == null) return
        dispatch(
            event = PluginHookEvents.AGENT_COMPACTION,
            payload = payload(runId, sessionId, mode, scope, agentName) {
                put("compacted", true)
                put("estimatedInputTokens", estimatedInputTokens)
            }
        )
    }

    /**
     * 工具执行前的可写 hook。按插件安装顺序依次等待结果：可拒绝调用或改写参数，
     * 改写后的参数由调用方重新走完整权限判断；超时、异常与格式错误都按“未改动”跳过。
     */
    suspend fun beforeToolCall(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        arguments: JsonObject
    ): ToolBeforeHookResult {
        if (sessionId == null) return ToolBeforeHookResult(arguments = arguments)
        val targets = hooksFor(PluginHookEvents.AGENT_TOOL_BEFORE, PluginHookModes.TRANSFORM)
        if (targets.isEmpty()) return ToolBeforeHookResult(arguments = arguments)
        val deadline = System.currentTimeMillis() + TRANSFORM_TOTAL_TIMEOUT_MS
        var current = arguments
        for ((version, hook) in targets) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                FileLogger.w(TAG, "可写 hook 总预算用尽: ${hook.event}")
                break
            }
            if (!isActiveHook(hook, PluginHookModes.TRANSFORM)) continue
            val payload = payload(runId, sessionId, mode, scope, agentName) {
                put("callId", callId)
                put("toolName", toolName)
                put("arguments", current)
            }
            val result = invokeTransform(version, hook, payload, remaining) ?: continue
            val mutation = PluginHookTransform.parseBefore(result)
            if (mutation == null) {
                FileLogger.w(TAG, "忽略格式错误的可写 hook 返回值: ${hook.pluginId}/${hook.localId}")
                continue
            }
            if (mutation.denied) {
                return ToolBeforeHookResult(
                    denied = true,
                    denyReason = mutation.denyReason ?: DEFAULT_DENY_REASON,
                    arguments = current
                )
            }
            mutation.arguments?.let { current = it }
        }
        return ToolBeforeHookResult(arguments = current)
    }

    /**
     * 工具成功结束后的可写 hook。只允许改写结果的 `data`；每个 hook 拿到上一个 hook 改写后的值，
     * 失败或超时保持当前值。只应传入 `status=success` 的结果。
     */
    suspend fun afterToolCall(
        sessionId: String?,
        runId: String,
        callId: String,
        toolName: String,
        mode: String,
        scope: String,
        agentName: String?,
        data: JsonElement
    ): ToolAfterHookResult {
        if (sessionId == null) return ToolAfterHookResult()
        val targets = hooksFor(PluginHookEvents.AGENT_TOOL_AFTER, PluginHookModes.TRANSFORM)
        if (targets.isEmpty()) return ToolAfterHookResult()
        val deadline = System.currentTimeMillis() + TRANSFORM_TOTAL_TIMEOUT_MS
        var current = data
        var changed = false
        for ((version, hook) in targets) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                FileLogger.w(TAG, "可写 hook 总预算用尽: ${hook.event}")
                break
            }
            if (!isActiveHook(hook, PluginHookModes.TRANSFORM)) continue
            val payload = payload(runId, sessionId, mode, scope, agentName) {
                put("callId", callId)
                put("toolName", toolName)
                put("status", "success")
                put("data", current)
            }
            val result = invokeTransform(version, hook, payload, remaining) ?: continue
            val mutation = PluginHookTransform.parseAfter(result)
            if (mutation == null) {
                FileLogger.w(TAG, "忽略格式错误的可写 hook 返回值: ${hook.pluginId}/${hook.localId}")
                continue
            }
            mutation.data?.let {
                current = it
                changed = true
            }
        }
        return ToolAfterHookResult(data = if (changed) current else null)
    }

    /**
     * provider HTTP 请求发出前的可写 hook。每次实际发送都会执行，重试或换 Key 可能重复触发；
     * 插件应保持幂等。返回的 URL/headers/body 由调用方校验后再重建请求。
     */
    suspend fun beforeHttpRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String
    ): HttpRequestHookResult {
        val targets = hooksFor(PluginHookEvents.AGENT_HTTP_BEFORE, PluginHookModes.TRANSFORM)
        if (targets.isEmpty()) return HttpRequestHookResult()
        val deadline = System.currentTimeMillis() + HTTP_TRANSFORM_TOTAL_TIMEOUT_MS
        var currentUrl = url
        var currentHeaders = headers
        var currentBody = body
        var urlChanged = false
        var headersChanged = false
        var bodyChanged = false
        for ((version, hook) in targets) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                FileLogger.w(TAG, "可写 hook 总预算用尽: ${hook.event}")
                break
            }
            if (!isActiveHook(hook, PluginHookModes.TRANSFORM)) continue
            val payload = buildJsonObject {
                put("url", currentUrl)
                put("method", method)
                put(
                    "headers",
                    JsonObject(PluginHttpHeaders.redact(currentHeaders).mapValues { JsonPrimitive(it.value) })
                )
                put("body", currentBody)
            }
            val result = invokeTransform(version, hook, payload, remaining, HTTP_TRANSFORM_HOOK_TIMEOUT_MS)
                ?: continue
            val mutation = PluginHookTransform.parseHttp(result)
            if (mutation == null) {
                FileLogger.w(TAG, "忽略格式错误的可写 hook 返回值: ${hook.pluginId}/${hook.localId}")
                continue
            }
            mutation.url?.let {
                currentUrl = it
                urlChanged = true
            }
            mutation.headers?.let {
                currentHeaders = PluginHttpHeaders.merge(
                    originalUrl = url,
                    originalHeaders = currentHeaders,
                    returnedHeaders = it,
                    finalUrl = currentUrl
                )
                headersChanged = true
            }
            mutation.body?.let {
                currentBody = it
                bodyChanged = true
            }
        }
        return HttpRequestHookResult(
            url = if (urlChanged) currentUrl else null,
            headers = if (headersChanged) currentHeaders else null,
            body = if (bodyChanged) currentBody else null
        )
    }

    /**
     * 宿主判定为 ASK 后、弹出用户确认前的代审核。按插件 ID 顺序逐个询问：
     * allow/deny 立即生效，pass 继续下一个；全部不处理则回到 UI。超时、异常与格式错误都按 pass。
     * 插件不能审核自己提供的工具，避免自批。
     */
    suspend fun reviewPermission(
        runId: String,
        sessionId: String?,
        mode: String,
        scope: String,
        agentName: String?,
        callId: String,
        toolName: String,
        arguments: JsonObject
    ): PermissionReviewHookResult {
        if (sessionId == null) return PermissionReviewHookResult(PermissionReviewDecision.PASS)
        val targets = hooksFor(PluginHookEvents.AGENT_PERMISSION_REVIEW, PluginHookModes.REVIEW)
        if (targets.isEmpty()) return PermissionReviewHookResult(PermissionReviewDecision.PASS)
        val deadline = System.currentTimeMillis() + REVIEW_TOTAL_TIMEOUT_MS
        for ((version, hook) in targets) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                FileLogger.w(TAG, "代审核总预算用尽，回退用户确认")
                break
            }
            if (!isActiveHook(hook, PluginHookModes.REVIEW)) continue
            if (isOwnPluginTool(hook.pluginId, toolName)) {
                FileLogger.d(TAG, "跳过自身工具代审核: ${hook.pluginId}/$toolName")
                continue
            }
            val payload = payload(runId, sessionId, mode, scope, agentName) {
                put("callId", callId)
                put("toolName", toolName)
                put("arguments", arguments)
                put("appForeground", appForegroundMonitor.current())
            }
            val result = invokeTransform(version, hook, payload, remaining, REVIEW_HOOK_TIMEOUT_MS) ?: continue
            val review = PluginHookTransform.parseReview(result)
            if (review == null) {
                FileLogger.w(TAG, "忽略格式错误的代审核返回值: ${hook.pluginId}/${hook.localId}")
                continue
            }
            if (review.decision == PermissionReviewDecision.PASS) continue
            FileLogger.i(TAG, "代审核 ${review.decision}: ${hook.pluginId}/${hook.localId} tool=$toolName")
            return review
        }
        return PermissionReviewHookResult(PermissionReviewDecision.PASS)
    }

    private fun isOwnPluginTool(pluginId: String, toolName: String): Boolean {
        val sanitized = pluginId.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        return toolName.startsWith("plugin__${sanitized}__")
    }

    private suspend fun hooksFor(event: String, mode: String): List<Pair<String, PluginHook>> =
        withContext(Dispatchers.IO) {
            try {
                snapshot().byEventMode[EventMode(event, mode)].orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "hook 调度失败: $event (${e.javaClass.simpleName})")
                emptyList()
            }
        }

    private fun isActiveHook(hook: PluginHook, mode: String): Boolean = try {
        snapshot().activeKeys.contains(HookKey(hook.key, mode))
    } catch (e: Exception) {
        FileLogger.w(TAG, "hook 状态读取失败: ${hook.pluginId}/${hook.localId} (${e.javaClass.simpleName})")
        false
    }

    private suspend fun invokeTransform(
        version: String,
        hook: PluginHook,
        payload: JsonObject,
        timeoutMs: Long,
        maxTimeoutMs: Long = TRANSFORM_HOOK_TIMEOUT_MS
    ): JsonElement? {
        return try {
            val result = performHookCall(
                hook = hook,
                version = version,
                payload = payload,
                timeoutMs = timeoutMs.coerceAtMost(maxTimeoutMs)
            ) ?: return null
            writeHookLogs(hook, result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "可写 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * 受并发额度与熔断保护地调用一次 hook。调用失败向上抛出，由调用方按各自语义兜底；
     * 熔断打开或等待并发额度超时时返回 null，这两种情况都不算 hook 调用失败。
     */
    private suspend fun performHookCall(
        hook: PluginHook,
        version: String,
        payload: JsonObject,
        timeoutMs: Long
    ): JsonElement? {
        if (timeoutMs <= 0L) return null
        if (!allowHookCall(hook)) {
            FileLogger.d(TAG, "hook 熔断中，跳过: ${hook.pluginId}/${hook.localId}")
            return null
        }
        var remaining = timeoutMs
        if (!invokeSlots.tryAcquire()) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val acquired = try {
                withTimeoutOrNull(timeoutMs) {
                    invokeSlots.acquire()
                    true
                } ?: false
            } catch (e: CancellationException) {
                releaseProbe(hook)
                throw e
            }
            if (!acquired) {
                releaseProbe(hook)
                FileLogger.w(TAG, "hook 并发额度已满，跳过: ${hook.pluginId}/${hook.localId}")
                return null
            }
            remaining = deadline - System.currentTimeMillis()
        }
        try {
            if (remaining <= 0L) {
                releaseProbe(hook)
                return null
            }
            val result = runtime.invoke(
                pluginId = hook.pluginId,
                version = version,
                runtime = hook.runtime,
                handler = hook.handler,
                args = payload,
                method = "hook.invoke",
                timeoutMs = remaining
            )
            recordHookSuccess(hook)
            return result
        } catch (e: CancellationException) {
            releaseProbe(hook)
            throw e
        } catch (e: Exception) {
            recordHookFailure(hook, e)
            throw e
        } finally {
            invokeSlots.release()
        }
    }

    private fun allowHookCall(hook: PluginHook): Boolean {
        val state = circuitFor(hook)
        return synchronized(state) {
            val now = System.currentTimeMillis()
            when {
                state.openUntilMs == 0L -> true
                now < state.openUntilMs -> false
                state.probeInFlight -> false
                else -> {
                    state.probeInFlight = true
                    true
                }
            }
        }
    }

    private fun recordHookSuccess(hook: PluginHook) {
        val state = circuitFor(hook)
        var recovered = false
        var changed = false
        synchronized(state) {
            recovered = state.openUntilMs != 0L
            changed = recovered || state.consecutiveFailures > 0 || state.lastError != null
            state.consecutiveFailures = 0
            state.openUntilMs = 0L
            state.lastError = null
            state.probeInFlight = false
        }
        if (changed) publishHealth()
        if (recovered) {
            FileLogger.i(TAG, "hook 熔断恢复: ${hook.pluginId}/${hook.localId}")
        }
    }

    private fun recordHookFailure(hook: PluginHook, error: Exception) {
        val state = circuitFor(hook)
        val category = errorCategory(error)
        var failures = 0
        var opened = false
        synchronized(state) {
            state.probeInFlight = false
            state.consecutiveFailures += 1
            state.lastError = category
            failures = state.consecutiveFailures
            if (failures >= FAILURE_THRESHOLD) {
                state.openUntilMs = System.currentTimeMillis() + OPEN_DURATION_MS
                opened = true
            }
        }
        publishHealth()
        if (opened) {
            FileLogger.w(
                TAG,
                "hook 熔断打开 ${OPEN_DURATION_MS / 1000}s: ${hook.pluginId}/${hook.localId} " +
                    "连续失败 $failures 次，最近错误 $category"
            )
        }
    }

    private fun releaseProbe(hook: PluginHook) {
        val state = circuits[healthKey(hook)] ?: return
        synchronized(state) { state.probeInFlight = false }
    }

    private fun circuitFor(hook: PluginHook): CircuitState =
        circuits.computeIfAbsent(healthKey(hook)) { CircuitState() }

    private fun healthKey(hook: PluginHook): String = "${hook.pluginId}/${hook.localId}"

    private fun publishHealth() {
        _hookHealth.value = circuits.mapValues { (_, state) ->
            synchronized(state) {
                HookHealth(
                    consecutiveFailures = state.consecutiveFailures,
                    openUntilMs = state.openUntilMs,
                    lastError = state.lastError
                )
            }
        }
    }

    private fun errorCategory(error: Exception): String = when {
        error is PluginProcessException && error.code == "PLUGIN_TIMEOUT" -> "timeout"
        error is PluginProcessException -> error.code
        else -> error.javaClass.simpleName
    }

    private class CircuitState {
        var consecutiveFailures = 0
        var openUntilMs = 0L
        var lastError: String? = null
        var probeInFlight = false
    }

    private fun dispatch(event: String, payload: JsonObject) {
        scope.launch {
            val targets = hooksFor(event, PluginHookModes.OBSERVE)
            if (targets.isEmpty()) return@launch
            coroutineScope {
                targets.map { (version, hook) ->
                    async { invoke(version, hook, payload) }
                }.awaitAll()
            }
        }
    }

    private suspend fun invoke(
        version: String,
        hook: PluginHook,
        payload: JsonObject,
        timeoutMs: Long = HOOK_TIMEOUT_MS
    ) {
        // 派发是异步的：执行前再确认 hook 仍生效且未撤权，避免停用后旧任务继续跑。
        if (!isActiveHook(hook, PluginHookModes.OBSERVE)) return
        try {
            val result = performHookCall(hook, version, payload, timeoutMs) ?: return
            writeHookLogs(hook, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件生命周期 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
        }
    }

    private fun dispatchToPlugin(event: String, plugin: InstalledPlugin, payload: JsonObject) {
        val hooks = lifecycleHooks(plugin, event)
        if (hooks.isEmpty()) return
        scope.launch {
            coroutineScope {
                hooks.map { hook ->
                    async { invoke(plugin.record.version, hook, payload) }
                }.awaitAll()
            }
        }
    }

    private suspend fun dispatchToPluginAndAwait(
        event: String,
        plugin: InstalledPlugin,
        payload: JsonObject
    ) {
        val hooks = lifecycleHooks(plugin, event)
        if (hooks.isEmpty()) return
        val completed = withTimeoutOrNull(LIFECYCLE_TOTAL_TIMEOUT_MS) {
            coroutineScope {
                hooks.map { hook ->
                    async {
                        invoke(plugin.record.version, hook, payload, LIFECYCLE_HOOK_TIMEOUT_MS)
                    }
                }.awaitAll()
            }
            true
        }
        if (completed == null) {
            FileLogger.w(TAG, "插件生命周期 hook 等待超时: ${plugin.id}/$event")
        }
    }

    private fun lifecycleHooks(plugin: InstalledPlugin, event: String): List<PluginHook> =
        plugin.content.hooks.filter { it.event == event && it.mode == PluginHookModes.OBSERVE }

    private fun pluginLifecyclePayload(plugin: InstalledPlugin): JsonObject = buildJsonObject {
        put("pluginId", plugin.id)
        put("version", plugin.record.version)
    }

    private suspend fun findPlugin(pluginId: String): InstalledPlugin? = withContext(Dispatchers.IO) {
        try {
            pluginManager.ensureLoaded()
            pluginManager.plugins.value.firstOrNull { it.id == pluginId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取插件失败: $pluginId (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * hook 可以通过返回 `{ logs: ["..."] }` 把调试信息写进宿主日志。
     * 行数与单条长度都有上限，避免插件日志刷屏。
     */
    private fun writeHookLogs(hook: PluginHook, result: JsonElement) {
        val logs = (result as? JsonObject)?.get("logs") as? JsonArray ?: return
        logs.take(MAX_LOG_LINES).forEach { item ->
            val text = (item as? JsonPrimitive)?.contentOrNull?.take(MAX_LOG_CHARS) ?: return@forEach
            FileLogger.i(TAG, "[${hook.pluginId}/${hook.localId}] $text")
        }
    }

    private fun payload(
        runId: String,
        sessionId: String,
        mode: String,
        scope: String,
        agentName: String?,
        extra: JsonObjectBuilder.() -> Unit
    ): JsonObject = buildJsonObject {
        put("runId", runId)
        put("sessionId", sessionId)
        put("mode", mode)
        put("scope", scope)
        put("agentName", if (agentName == null) JsonNull else JsonPrimitive(agentName))
        extra()
    }

    private companion object {
        const val TAG = "PluginHook"
        const val HOOK_TIMEOUT_MS = 30_000L
        const val TRANSFORM_HOOK_TIMEOUT_MS = 10_000L
        const val TRANSFORM_TOTAL_TIMEOUT_MS = 20_000L

        /** 同时执行的 hook 运行时 RPC 上限，避免观察型事件把插件进程打满。 */
        const val MAX_CONCURRENT_HOOKS = 8
        const val FAILURE_THRESHOLD = 3
        const val OPEN_DURATION_MS = 60_000L

        /** HTTP hook 挂在 OkHttp 线程上，预算必须比工具 hook 更紧。 */
        const val HTTP_TRANSFORM_HOOK_TIMEOUT_MS = 3_000L
        const val HTTP_TRANSFORM_TOTAL_TIMEOUT_MS = 5_000L

        val HTTP_BEFORE_TRANSFORM = EventMode(
            PluginHookEvents.AGENT_HTTP_BEFORE,
            PluginHookModes.TRANSFORM
        )

        /** 代审核等的是人：给用户离开手机后回来的时间，但仍保留有界等待。 */
        const val REVIEW_HOOK_TIMEOUT_MS = 5 * 60_000L
        const val REVIEW_TOTAL_TIMEOUT_MS = 5 * 60_000L

        /** 停用/卸载前的清理最多等 10 秒，插件不能借此阻止操作。 */
        const val LIFECYCLE_HOOK_TIMEOUT_MS = 10_000L
        const val LIFECYCLE_TOTAL_TIMEOUT_MS = 10_000L
        const val MAX_LOG_LINES = 50
        const val MAX_LOG_CHARS = 500
        const val DEFAULT_DENY_REASON = "插件阻止了该工具调用"
    }
}
