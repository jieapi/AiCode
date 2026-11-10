package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 AI 运行生命周期事件分发给生效插件声明的 hook。
 *
 * 调用方只报告运行元数据：`runId` / `sessionId` / `mode` / `status` / `toolCalls` / `errorCode`，
 * 绝不携带消息正文或工具参数。[dispatchRunStarted] 与 [dispatchRunFinished] 都是 fire-and-forget，
 * hook 的执行失败只写日志，不向主流程抛出，也不阻塞运行。
 */
@Singleton
class PluginHookDispatcher @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtime: PluginRuntimeManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dispatchRunStarted(sessionId: String, runId: String, mode: String) {
        dispatch(
            event = PluginHookEvents.AGENT_RUN_STARTED,
            payload = payload(runId, sessionId, mode, status = "started", toolCalls = 0, errorCode = null)
        )
    }

    /** [status] 取 `success` / `error` / `cancelled`；[toolCalls] 是本次运行的工具调用次数。 */
    fun dispatchRunFinished(
        sessionId: String,
        runId: String,
        mode: String,
        status: String,
        toolCalls: Int,
        errorCode: String?
    ) {
        dispatch(
            event = PluginHookEvents.AGENT_RUN_FINISHED,
            payload = payload(runId, sessionId, mode, status, toolCalls, errorCode)
        )
    }

    private fun dispatch(event: String, payload: JsonObject) {
        scope.launch {
            val targets = try {
                pluginManager.ensureLoaded()
                pluginManager.plugins.value
                    .filter { it.isActive && PluginPermission.AGENT_EVENTS in it.granted }
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
        try {
            runtime.invoke(
                pluginId = hook.pluginId,
                version = version,
                entryFile = hook.runtimeEntry,
                handler = hook.handler,
                args = payload
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件生命周期 hook 调用失败: ${hook.pluginId}/${hook.event} (${e.javaClass.simpleName})")
        }
    }

    private fun payload(
        runId: String,
        sessionId: String,
        mode: String,
        status: String,
        toolCalls: Int,
        errorCode: String?
    ): JsonObject = buildJsonObject {
        put("runId", runId)
        put("sessionId", sessionId)
        put("mode", mode)
        put("status", status)
        put("toolCalls", toolCalls)
        put("errorCode", if (errorCode == null) JsonNull else JsonPrimitive(errorCode))
    }

    private companion object {
        const val TAG = "PluginHook"
    }
}
