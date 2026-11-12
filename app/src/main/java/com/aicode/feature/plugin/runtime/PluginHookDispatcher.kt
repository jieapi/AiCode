package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 把 AI 运行生命周期事件分发给生效插件声明的 hook。
 *
 * 只报告运行元数据：运行标识、会话、模式、会话范围、请求/工具标识与计数，
 * 绝不携带消息正文、工具参数或工具结果。所有方法都是 fire-and-forget：
 * hook 的执行失败只写日志，不向主流程抛出，也不阻塞运行。
 */
@Singleton
class PluginHookDispatcher @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private fun dispatch(event: String, payload: JsonObject) {
        scope.launch {
            if (!runtime.isSupported()) return@launch
            val targets = try {
                pluginManager.ensureLoaded()
                pluginManager.plugins.value
                    .filter { it.isActive }
                    .flatMap { plugin ->
                        plugin.content.hooks
                            .filter { it.event == event }
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
                entryFile = hook.runtimeEntry,
                handler = hook.handler,
                args = payload
            )
            writeHookLogs(hook, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件生命周期 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
        }
    }

    /**
     * hook 可以通过返回 `{ logs: ["..."] }` 把调试信息写进宿主日志；其余返回值仍被忽略。
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
        const val MAX_LOG_LINES = 50
        const val MAX_LOG_CHARS = 500
    }
}
