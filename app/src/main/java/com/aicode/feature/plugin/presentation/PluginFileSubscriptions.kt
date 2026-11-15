package com.aicode.feature.plugin.presentation

import com.aicode.feature.plugin.domain.PluginHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PluginFileSubscriptions(
    private val scope: CoroutineScope,
    private val watch: (String, Boolean) -> Flow<JsonObject>,
    private val emit: (JsonObject) -> Unit
) {
    private data class Entry(
        val id: String,
        val path: String,
        val recursive: Boolean,
        var job: Job? = null
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var active = false
    private var generation = 0L
    private var closed = false

    fun add(id: String, path: String, recursive: Boolean) {
        if (!ID_PATTERN.matches(id)) throw PluginHostException("PARAM_INVALID", "invalid subscription id")
        synchronized(lock) {
            if (closed) throw PluginHostException("PAGE_CLOSED", "page bridge is closed")
            if (id in entries) throw PluginHostException("PARAM_INVALID", "subscription already exists")
            if (entries.size >= MAX_SUBSCRIPTIONS) {
                throw PluginHostException("TOO_MANY_WATCHES", "too many file subscriptions")
            }
        }
        watch(path, recursive)
        synchronized(lock) {
            if (closed) throw PluginHostException("PAGE_CLOSED", "page bridge is closed")
            if (id in entries || entries.size >= MAX_SUBSCRIPTIONS) {
                throw PluginHostException("TOO_MANY_WATCHES", "file subscription limit reached")
            }
            val entry = Entry(id, path, recursive)
            entries[id] = entry
            if (active) start(entry, generation)
        }
    }

    fun remove(id: String) {
        synchronized(lock) { entries.remove(id)?.job?.cancel() }
    }

    fun setActive(value: Boolean, restart: Boolean = false) {
        synchronized(lock) {
            if (closed || (active == value && !restart)) return
            active = value
            generation++
            entries.values.forEach { it.job?.cancel(); it.job = null }
            if (value) entries.values.forEach { start(it, generation) }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            active = false
            generation++
            entries.values.forEach { it.job?.cancel() }
            entries.clear()
        }
    }

    private fun start(entry: Entry, version: Long) {
        entry.job = scope.launch {
            try {
                watch(entry.path, entry.recursive).collect { payload ->
                    sendIfCurrent(entry, version, payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendIfCurrent(entry, version, buildJsonObject {
                    put("error", buildJsonObject {
                        put("code", (e as? PluginHostException)?.code ?: "WATCH_FAILED")
                        put("message", e.message ?: "file subscription failed")
                    })
                })
            }
        }
    }

    private fun sendIfCurrent(entry: Entry, version: Long, payload: JsonObject) {
        synchronized(lock) {
            if (!active || closed || generation != version || entries[entry.id] !== entry) return
            emit(buildJsonObject {
                payload.forEach { (key, value) -> put(key, value) }
                put("subscriptionId", entry.id)
                put("path", entry.path)
            })
        }
    }

    private companion object {
        const val MAX_SUBSCRIPTIONS = 16
        val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
