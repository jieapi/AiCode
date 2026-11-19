package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginExternalSessionDefinition
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 处理插件发来的 `host.sessions.*` 请求，校验插件激活态及外部会话源归属权后写入 [ExternalSessionStore]。
 */
@Singleton
class ExternalSessionHostService @Inject constructor(
    private val pluginManager: PluginManager,
    private val store: ExternalSessionStore
) {

    fun upsert(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val items = parseSummaries(params, plugin, source)
        store.upsert(pluginId, source.localId, items)
        return buildJsonObject {
            put("ok", true)
            put("count", items.size)
        }
    }

    fun remove(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val ids = parseSessionIds(params, source.key)
        store.remove(pluginId, source.localId, ids)
        return buildJsonObject {
            put("ok", true)
            put("count", ids.size)
        }
    }

    fun replaceAll(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val items = parseSummaries(params, plugin, source)
        store.replaceAll(pluginId, source.localId, items)
        return buildJsonObject {
            put("ok", true)
            put("count", items.size)
        }
    }

    fun setLoading(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val loading = (params["loading"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw PluginHostException("PARAM_INVALID", "loading must be a boolean")
        store.setLoading(pluginId, source.localId, loading)
        return buildJsonObject { put("ok", true) }
    }

    fun setError(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val error = when (val raw = params["error"]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (raw.isString) raw.content.trim().takeIf { it.isNotEmpty() }
            else throw PluginHostException("PARAM_INVALID", "error must be a string or null")
            else -> throw PluginHostException("PARAM_INVALID", "error must be a string or null")
        }
        store.setError(pluginId, source.localId, error)
        return buildJsonObject { put("ok", true) }
    }

    fun setThinking(pluginId: String, params: JsonObject): JsonElement {
        val (plugin, source) = requireOwnedSource(pluginId, params)
        ensureSourcePresent(plugin, source)
        val rawSessionId = (params["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw PluginHostException("PARAM_INVALID", "sessionId is required")
        val prefix = "${source.key}|"
        val sessionId = if (rawSessionId.startsWith(prefix)) rawSessionId.removePrefix(prefix) else rawSessionId
        val visible = (params["visible"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw PluginHostException("PARAM_INVALID", "visible must be a boolean")
        val rawText = params["text"]
        val text = when (rawText) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (rawText.isString) rawText.content
            else throw PluginHostException("PARAM_INVALID", "text must be a string or null")
            else -> throw PluginHostException("PARAM_INVALID", "text must be a string or null")
        }
        if (!visible) {
            store.clearThinking(ExternalSessionSummary.compositeId(source.key, sessionId))
        } else {
            store.setThinking(source.key, sessionId, text.ifBlank { "Thinking…" })
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun requireOwnedSource(
        pluginId: String,
        params: JsonObject
    ): Pair<InstalledPlugin, PluginExternalSessionDefinition> {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val rawSource = (params["sourceId"] ?: params["sourceKey"])?.let { elem ->
            (elem as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        }?.takeIf { it.isNotEmpty() }
            ?: throw PluginHostException("PARAM_MISSING", "missing parameter: sourceId")
        val source = plugin.content.sessions.firstOrNull { it.localId == rawSource || it.key == rawSource }
            ?: throw PluginHostException(
                "SESSION_SOURCE_NOT_FOUND",
                "session source does not belong to plugin $pluginId: $rawSource"
            )
        return plugin to source
    }

    private fun ensureSourcePresent(
        plugin: InstalledPlugin,
        source: PluginExternalSessionDefinition
    ) {
        val languageTag = Locale.getDefault().toLanguageTag()
        val pluginName = plugin.manifest?.name?.resolve(languageTag)?.takeIf { it.isNotBlank() }
            ?: source.pluginId
        val sourceTitle = source.title.resolve(languageTag).ifBlank { source.localId }
        store.ensureSource(
            pluginId = source.pluginId,
            pluginName = pluginName,
            sourceKey = source.key,
            title = sourceTitle
        )
    }

    private fun parseSummaries(
        params: JsonObject,
        plugin: InstalledPlugin,
        source: PluginExternalSessionDefinition
    ): List<ExternalSessionSummary> {
        val rawArray = (params["sessions"] ?: params["items"]) as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "sessions must be an array")
        if (rawArray.size > MAX_ITEMS) {
            throw PluginHostException("PARAM_INVALID", "too many session items (max $MAX_ITEMS)")
        }
        val languageTag = Locale.getDefault().toLanguageTag()
        val pluginName = plugin.manifest?.name?.resolve(languageTag)?.takeIf { it.isNotBlank() }
            ?: source.pluginId
        val sourceTitle = source.title.resolve(languageTag).ifBlank { source.localId }
        return rawArray.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw PluginHostException("PARAM_INVALID", "sessions[$index] must be an object")
            parseSummaryObject(
                obj = obj,
                index = index,
                sourceKey = source.key,
                sourceTitle = sourceTitle,
                pluginId = source.pluginId,
                pluginName = pluginName,
                pluginVersion = source.pluginVersion
            )
        }
    }

    private fun parseSessionIds(params: JsonObject, sourceKey: String): List<String> {
        val rawArray = (params["sessionIds"] ?: params["ids"]) as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "sessionIds must be an array")
        if (rawArray.size > MAX_ITEMS) {
            throw PluginHostException("PARAM_INVALID", "too many sessionIds (max $MAX_ITEMS)")
        }
        val prefix = "$sourceKey|"
        return rawArray.mapIndexed { index, element ->
            val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw PluginHostException("PARAM_INVALID", "sessionIds[$index] must be a non-empty string")
            if (raw.startsWith(prefix)) raw.removePrefix(prefix) else raw
        }
    }

    internal companion object {
        const val MAX_ITEMS = 500

        fun parseSummaryObject(
            obj: JsonObject,
            index: Int = 0,
            sourceKey: String,
            sourceTitle: String,
            pluginId: String,
            pluginName: String,
            pluginVersion: String
        ): ExternalSessionSummary {
            val prefix = "$sourceKey|"
            val rawSessionId = obj.string("sessionId")
                ?: obj.string("id")?.let { if (it.startsWith(prefix)) it.removePrefix(prefix) else it }
                ?: throw PluginHostException("PARAM_INVALID", "sessions[$index].sessionId is required")
            val sessionId = if (rawSessionId.startsWith(prefix)) rawSessionId.removePrefix(prefix) else rawSessionId
            if (sessionId.isEmpty()) {
                throw PluginHostException("PARAM_INVALID", "sessions[$index].sessionId is required")
            }
            val title = obj.string("title") ?: sessionId
            val status = obj.string("status").orEmpty()
            val running = obj.bool("running") ?: false
            val updatedAt = obj.long("updatedAt") ?: 0L
            val group = obj.string("group").orEmpty()
            val isPinned = obj.bool("isPinned") ?: obj.bool("pinned") ?: false
            val workflowId = obj.string("workflowId")
            val backendId = obj.string("backendId")
            val workspaceId = obj.string("workspaceId")
            val providerId = obj.string("providerId")
            val model = obj.string("model")
            return ExternalSessionSummary(
                id = ExternalSessionSummary.compositeId(sourceKey, sessionId),
                sessionId = sessionId,
                sourceKey = sourceKey,
                sourceTitle = sourceTitle,
                pluginId = pluginId,
                pluginName = pluginName,
                pluginVersion = pluginVersion,
                title = title,
                status = status,
                running = running,
                updatedAt = updatedAt,
                group = group,
                isPinned = isPinned,
                workflowId = workflowId,
                backendId = backendId,
                workspaceId = workspaceId,
                providerId = providerId,
                model = model
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
