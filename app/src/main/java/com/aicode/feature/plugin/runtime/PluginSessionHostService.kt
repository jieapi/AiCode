package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginSessionSourceDefinition
import com.aicode.feature.plugin.domain.pluginContributionName
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 插件进程侧的会话 UI 宿主 API。
 *
 * 插件通过 `host.sessionSource.*` 把异步拿到的会话列表、加载态和“正在思考”文案推给宿主；
 * 宿主只校验来源插件与 sessionSource 声明，不替插件主动拉取数据。
 */
@Singleton
class PluginSessionHostService @Inject constructor(
    private val pluginManager: PluginManager,
    private val registry: PluginSessionRegistry
) {

    fun setSessions(pluginId: String, params: JsonObject): JsonElement {
        val (pluginName, source) = requireSource(pluginId, params)
        val rawSessions = params["sessions"] as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "sessions is required")
        val sourceKey = pluginContributionName(pluginId, source.localId)
        val sourceTitle = source.title.resolve(Locale.getDefault().toLanguageTag())
        val sessions = rawSessions.mapNotNull { raw ->
            parseSession(raw as? JsonObject, pluginId, pluginName, source, sourceKey, sourceTitle)
        }.take(MAX_SESSIONS)
        registry.setSessions(pluginId, source.localId, sessions)
        return ok()
    }

    fun setLoading(pluginId: String, params: JsonObject): JsonElement {
        val (_, source) = requireSource(pluginId, params)
        val loading = (params["loading"] as? JsonPrimitive)?.booleanOrNull
            ?: throw PluginHostException("PARAM_INVALID", "loading is required")
        registry.setLoading(pluginId, source.localId, loading)
        return ok()
    }

    fun setError(pluginId: String, params: JsonObject): JsonElement {
        val (_, source) = requireSource(pluginId, params)
        val error = when (val raw = params["error"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }?.take(MAX_ERROR_CHARS)
        registry.setError(pluginId, source.localId, error)
        return ok()
    }

    fun setThinking(pluginId: String, params: JsonObject): JsonElement {
        val (_, source) = requireSource(pluginId, params)
        val sessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val visible = (params["visible"] as? JsonPrimitive)?.booleanOrNull
            ?: throw PluginHostException("PARAM_INVALID", "visible is required")
        val text = if (visible) {
            (params["text"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }?.take(MAX_THINKING_CHARS)
                ?: DEFAULT_THINKING_TEXT
        } else {
            null
        }
        val sourceKey = pluginContributionName(pluginId, source.localId)
        registry.setThinking(sourceKey, sessionId, text)
        return ok()
    }

    private fun requireSource(
        pluginId: String,
        params: JsonObject
    ): Pair<String, PluginSessionSourceDefinition> {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val sourceId = PluginHostPaths.requireString(params, "sourceId").trim()
        val source = plugin.content.sessionSources.firstOrNull { it.localId == sourceId }
            ?: throw PluginHostException("SESSION_SOURCE_NOT_FOUND", "session source not declared: $sourceId")
        val pluginName = runCatching {
            plugin.manifest?.name?.resolve(Locale.getDefault().toLanguageTag())
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: plugin.id
        registry.ensureSource(
            pluginId = pluginId,
            pluginName = pluginName,
            sourceKey = pluginContributionName(pluginId, source.localId),
            title = source.title.resolve(Locale.getDefault().toLanguageTag())
        )
        return pluginName to source
    }

    private fun parseSession(
        row: JsonObject?,
        pluginId: String,
        pluginName: String,
        source: PluginSessionSourceDefinition,
        sourceKey: String,
        sourceTitle: String
    ): PluginSessionSummary? {
        if (row == null) return null
        val sessionId = row.string("sessionId")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hostName = row.string("hostName").orEmpty()
        val workspaceTitle = row.string("workspaceTitle").orEmpty()
        val group = row.string("group").orEmpty().ifBlank {
            listOf(hostName, workspaceTitle).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { sourceTitle }
        }
        val status = row.string("status").orEmpty().ifBlank {
            if (row.bool("running") == true) "running" else "idle"
        }
        return PluginSessionSummary(
            id = "$sourceKey|$sessionId",
            sessionId = sessionId,
            sourceKey = sourceKey,
            sourceTitle = sourceTitle,
            pluginId = pluginId,
            pluginName = pluginName,
            pluginVersion = source.pluginVersion,
            title = row.string("title").orEmpty().ifBlank { "未命名会话" }.take(MAX_TITLE_CHARS),
            status = status,
            running = row.bool("running") == true || status == "running",
            updatedAt = row.long("updatedAt"),
            group = group.take(MAX_GROUP_CHARS),
            hostName = hostName.take(MAX_GROUP_CHARS),
            workspaceTitle = workspaceTitle.take(MAX_GROUP_CHARS),
            capabilities = (row["capabilities"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun ok(): JsonElement = buildJsonObject { put("ok", true) }

    companion object {
        private const val MAX_SESSIONS = 1000
        private const val MAX_TITLE_CHARS = 200
        private const val MAX_GROUP_CHARS = 200
        private const val MAX_ERROR_CHARS = 1000
        private const val MAX_THINKING_CHARS = 200
        const val DEFAULT_THINKING_TEXT = "正在思考中…"
    }
}
