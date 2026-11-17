package com.aicode.feature.plugin.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject

data class PluginSessionEvent(val sessionId: String, val payload: JsonObject)

@Singleton
class PluginSessionEventBus @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<PluginSessionEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val events = mutableEvents.asSharedFlow()

    suspend fun publish(sessionId: String, payload: JsonObject) {
        mutableEvents.emit(PluginSessionEvent(sessionId, payload))
    }
}
