package com.aicode.feature.plugin.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 插件工作流事件的宿主侧总线。
 *
 * 当二进制 IPC 通道未就绪、工作流通过 NDJSON 回退执行时，插件进程经 `plugin.event`
 * （`params.name == "workflow.event"`）或直接以 `workflow.event` 通知推送轮次事件；
 * 总线按 `(pluginId, turnId)` 将事件精准转发给对应订阅流。
 */
@Singleton
class PluginWorkflowEventBus @Inject constructor() {

    private data class Key(val pluginId: String, val turnId: String)

    private val listeners = ConcurrentHashMap<Key, CopyOnWriteArraySet<(JsonObject) -> Unit>>()

    fun subscribe(pluginId: String, turnId: String, listener: (JsonObject) -> Unit): () -> Unit {
        val key = Key(pluginId, turnId)
        listeners.getOrPut(key) { CopyOnWriteArraySet() }.add(listener)
        return {
            listeners[key]?.let { set ->
                set.remove(listener)
                if (set.isEmpty()) listeners.remove(key, set)
            }
        }
    }

    fun eventsFor(pluginId: String, turnId: String): Flow<JsonObject> = callbackFlow {
        val unsubscribe = subscribe(pluginId, turnId) { event ->
            trySend(event)
        }
        awaitClose { unsubscribe() }
    }.buffer(Channel.UNLIMITED)

    fun publish(pluginId: String, turnId: String, event: JsonObject) {
        if (pluginId.isBlank() || turnId.isBlank()) return
        listeners[Key(pluginId, turnId)]?.forEach { listener ->
            runCatching { listener(event) }
        }
    }

    /**
     * 消费插件进程通知并路由到对应 `(pluginId, turnId)` 的工作流事件订阅者。
     * 返回 true 表示该通知属于工作流事件（不论当前是否有订阅者）。
     */
    fun publishNotification(pluginId: String, method: String, params: JsonObject): Boolean {
        val eventObj: JsonObject = when (method) {
            METHOD_PLUGIN_EVENT -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                if (name != EVENT_NAME) return false
                (params["payload"] as? JsonObject) ?: return true
            }
            EVENT_NAME -> {
                (params["payload"] as? JsonObject) ?: params
            }
            else -> return false
        }
        val turnId = (eventObj["turnId"] as? JsonPrimitive)?.contentOrNull
            ?: (params["turnId"] as? JsonPrimitive)?.contentOrNull
            ?: return true
        publish(pluginId, turnId, eventObj)
        return true
    }

    companion object {
        const val METHOD_PLUGIN_EVENT = "plugin.event"
        const val EVENT_NAME = "workflow.event"
    }
}
