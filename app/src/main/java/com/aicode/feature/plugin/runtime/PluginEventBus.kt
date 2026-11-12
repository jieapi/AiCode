package com.aicode.feature.plugin.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * 插件主动事件的宿主侧总线。插件进程通过 `plugin.event` 通知发事件，
 * 页面桥按键订阅后把事件推给该插件已加载的页面。事件不跨插件转发，也不注入会话。
 */
@Singleton
class PluginEventBus @Inject constructor() {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<(JsonObject) -> Unit>>()

    fun subscribe(pluginId: String, listener: (JsonObject) -> Unit): () -> Unit {
        listeners.getOrPut(pluginId) { CopyOnWriteArraySet() }.add(listener)
        return {
            listeners[pluginId]?.let { set ->
                set.remove(listener)
                if (set.isEmpty()) listeners.remove(pluginId, set)
            }
        }
    }

    fun publish(pluginId: String, event: JsonObject) {
        listeners[pluginId]?.forEach { listener -> runCatching { listener(event) } }
    }
}
