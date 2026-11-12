package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * 把 AI 运行生命周期事件分发给生效插件声明的 hook。
 *
 * 只读事件异步派发，只报告运行元数据，不携带消息正文、工具参数或工具结果；
 * 可写事件（`agent.tool.before` / `agent.tool.after`）同步等待插件结果，
 * 携带对应工具的参数或成功结果，由宿主校验后应用。两类 hook 的执行失败都只写日志，
 * 不向主流程抛出；可写 hook 失败时保持原值继续。
 */
@Singleton
class PluginHookDispatcher @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val deadline = System.currentTimeMillis() + TRANSFORM_TOTAL_TIMEOUT_MS
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
                put("headers", JsonObject(currentHeaders.mapValues { JsonPrimitive(it.value) }))
                put("body", currentBody)
            }
            val result = invokeTransform(version, hook, payload, remaining) ?: continue
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
                currentHeaders = it
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
        val deadline = System.currentTimeMillis() + TRANSFORM_TOTAL_TIMEOUT_MS
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
            }
            val result = invokeTransform(version, hook, payload, remaining) ?: continue
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

    private suspend fun hooksFor(event: String, mode: String): List<Pair<String, PluginHook>> = withContext(Dispatchers.IO) {
        try {
            pluginManager.ensureLoaded()
            pluginManager.plugins.value
                .filter { it.isActive }
                .flatMap { plugin ->
                    plugin.content.hooks
                        .filter { it.event == event && it.mode == mode }
                        .map { hook -> plugin.record.version to hook }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "hook 调度失败: $event (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private suspend fun isActiveHook(hook: PluginHook, mode: String): Boolean = withContext(Dispatchers.IO) {
        pluginManager.activeHooks().any { it.key == hook.key && it.mode == mode }
    }

    private suspend fun invokeTransform(
        version: String,
        hook: PluginHook,
        payload: JsonObject,
        timeoutMs: Long
    ): JsonElement? = try {
        val result = runtime.invoke(
            pluginId = hook.pluginId,
            version = version,
            runtime = hook.runtime,
            handler = hook.handler,
            args = payload,
            method = "hook.invoke",
            timeoutMs = timeoutMs.coerceAtMost(TRANSFORM_HOOK_TIMEOUT_MS)
        )
        writeHookLogs(hook, result)
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "可写 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
        null
    }

    private fun dispatch(event: String, payload: JsonObject) {
        scope.launch {
            val targets = try {
                pluginManager.ensureLoaded()
                pluginManager.plugins.value
                    .filter { it.isActive }
                    .flatMap { plugin ->
                        plugin.content.hooks
                            .filter { it.event == event && it.mode == PluginHookModes.OBSERVE }
                            .map { hook -> plugin.record.version to hook }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件生命周期 hook 调度失败: $event (${e.javaClass.simpleName})")
                return@launch
            }
            targets.forEach { (version, hook) ->
                scope.launch { invoke(version, hook, payload) }
            }
        }
    }

    private suspend fun invoke(version: String, hook: PluginHook, payload: JsonObject) {
        // 派发是异步的：执行前再确认 hook 仍生效且未撤权，避免停用后旧任务继续跑。
        if (pluginManager.activeHooks().none { it.key == hook.key }) return
        try {
            val result = runtime.invoke(
                pluginId = hook.pluginId,
                version = version,
                runtime = hook.runtime,
                handler = hook.handler,
                args = payload,
                method = "hook.invoke",
                timeoutMs = HOOK_TIMEOUT_MS
            )
            writeHookLogs(hook, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件生命周期 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
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
        const val MAX_LOG_LINES = 50
        const val MAX_LOG_CHARS = 500
        const val DEFAULT_DENY_REASON = "插件阻止了该工具调用"
    }
}
