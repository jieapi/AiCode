package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.domain.session.PluginSessionListResult
import com.aicode.feature.plugin.domain.session.PluginSessionMessage
import com.aicode.feature.plugin.domain.session.PluginSessionPage
import com.aicode.feature.plugin.domain.session.PluginSessionSource
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 把插件声明的 `sessionSources` 变成宿主可消费的外部会话/消息。
 *
 * 只负责调用插件 handler、校验并归一化 JSON；不把任何外部消息写入 Room。插件停用后
 * [listSessions] 自然返回空列表，不会留下孤儿会话。
 */
@Singleton
class PluginSessionSourceService @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager,
    private val registry: PluginSessionRegistry
) {
    /** 侧栏直接消费的插件分组；插件清单同步后立即出现占位，会话列表由插件异步推送。 */
    val sessionGroups: kotlinx.coroutines.flow.StateFlow<List<com.aicode.feature.plugin.domain.session.PluginSessionGroup>>
        get() = registry.groups

    /** 当前启用且声明了会话源的插件数量；侧栏入口据此决定是否显示。 */
    fun sourceCount(): Int = activeSources().size

    /** 把当前生效的插件会话源同步成侧栏占位分组（不等待插件返回任何会话数据）。 */
    suspend fun syncDeclaredSources() {
        pluginManager.ensureLoaded()
        registry.syncSources(activeSources())
    }

    /**
     * 通知插件刷新会话列表。插件应立即返回，随后通过 `host.sessionSource.setSessions`
     * 异步推送结果；未声明 refresh handler 的插件标记为加载失败，避免侧栏一直转圈。
     */
    suspend fun requestRefresh() {
        pluginManager.ensureLoaded()
        for (source in activeSources()) {
            val handler = source.handlers.refresh ?: run {
                registry.setError(
                    pluginId = source.pluginId,
                    sourceId = source.key.substringAfterLast(':'),
                    error = "插件未声明 sessionSource.refresh"
                )
                continue
            }
            runCatching {
                invoke(
                    source = source,
                    handler = handler,
                    args = buildJsonObject { put("sourceId", source.key.substringAfterLast(':')) },
                    timeoutMs = REFRESH_TIMEOUT_MS
                )
            }.onFailure {
                FileLogger.w(TAG, "sessionSource.refresh failed: ${source.key}", it)
                registry.setError(
                    pluginId = source.pluginId,
                    sourceId = source.key.substringAfterLast(':'),
                    error = "插件会话刷新失败：${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    suspend fun listSessions(): PluginSessionListResult {
        pluginManager.ensureLoaded()
        val sources = activeSources()
        if (sources.isEmpty()) return PluginSessionListResult(emptyList())

        val sessions = mutableListOf<PluginSessionSummary>()
        val errors = mutableListOf<String>()
        for (source in sources) {
            try {
                val response = invoke(
                    source = source,
                    handler = source.handlers.listSessions,
                    args = buildJsonObject {
                        put("offset", 0)
                        put("limit", 200)
                    }
                )
                sessions += parseSessions(source, response)
                errors += parseErrors(response)
            } catch (error: Exception) {
                FileLogger.w(TAG, "listSessions failed: ${source.key}", error)
                errors += "${source.title}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        sessions.sortWith(compareByDescending<PluginSessionSummary> { it.updatedAt ?: 0L }
            .thenBy { it.title })
        return PluginSessionListResult(sessions, errors)
    }

    suspend fun readSession(
        sourceKey: String,
        sessionId: String,
        cursor: JsonElement? = null,
        maxMessages: Int = 50
    ): PluginSessionPage {
        val source = activeSources().firstOrNull { it.key == sourceKey }
            ?: throw IllegalStateException("PLUGIN_SESSION_SOURCE_NOT_FOUND")
        val response = invoke(
            source = source,
            handler = source.handlers.readSession,
            args = buildJsonObject {
                put("sessionId", sessionId)
                cursor?.let { put("cursor", it) }
                put("maxMessages", maxMessages.coerceIn(1, 100))
            }
        )
        return parsePage(response, sessionId)
    }

    internal fun parsePage(response: JsonElement, sessionId: String): PluginSessionPage {
        val root = response as? JsonObject ?: throw IllegalStateException("PLUGIN_SESSION_INVALID_RESPONSE")
        val messages = (root["messages"] as? JsonArray).orEmpty()
            .mapNotNull { parseMessage(it as? JsonObject) }
        return PluginSessionPage(
            sessionId = root.string("sessionId") ?: sessionId,
            title = root.string("title").orEmpty(),
            status = root.string("status").orEmpty(),
            messages = messages,
            hasMore = root.bool("hasMore") == true,
            nextCursor = root["nextCursor"]?.takeUnless { it is JsonNull }
        )
    }

    /**
     * 向外部会话发送一条用户消息。
     *
     * 宿主生成 operationId（幂等键）随请求透传；返回 JSON 只表示插件已接受/入队，
     * 真正的消息与运行状态仍需调用方继续 [readSession] 拉取。插件未声明 sendMessage
     * handler 时抛出 `PLUGIN_SESSION_SEND_UNSUPPORTED`，由上层转成可展示错误。
     */
    suspend fun sendMessage(
        sourceKey: String,
        sessionId: String,
        message: String
    ): JsonElement {
        require(message.isNotBlank()) { "PLUGIN_SESSION_MESSAGE_EMPTY" }
        require(message.length <= MAX_MESSAGE_CHARS) { "PLUGIN_SESSION_MESSAGE_TOO_LONG" }
        val source = activeSources().firstOrNull { it.key == sourceKey }
            ?: throw IllegalStateException("PLUGIN_SESSION_SOURCE_NOT_FOUND")
        val handler = source.handlers.sendMessage
            ?: throw IllegalStateException("PLUGIN_SESSION_SEND_UNSUPPORTED")
        return invoke(
            source = source,
            handler = handler,
            args = buildJsonObject {
                put("sessionId", sessionId)
                put("message", message)
                put("operationId", UUID.randomUUID().toString().replace("-", "").take(32))
            }
        )
    }

    suspend fun interruptSession(sourceKey: String, sessionId: String) {
        val source = activeSources().firstOrNull { it.key == sourceKey }
            ?: error("PLUGIN_SESSION_SOURCE_NOT_FOUND")
        val handler = source.handlers.interruptSession ?: error("PLUGIN_SESSION_INTERRUPT_UNSUPPORTED")
        invoke(source, handler, buildJsonObject { put("sessionId", sessionId) })
    }

    /**
     * 开始实时跟随一个外部会话：插件会把运行中的消息增量以 `plugin.event` 推给宿主。
     * 不是所有插件都实现，失败静默忽略并退化为轮询刷新。
     */
    suspend fun followSession(sourceKey: String, sessionId: String) {
        val source = activeSources().firstOrNull { it.key == sourceKey } ?: return
        runCatching {
            invoke(
                source = source,
                handler = "sessionSource.follow",
                args = buildJsonObject { put("sessionId", sessionId) }
            )
        }.onFailure { FileLogger.w(TAG, "followSession failed: $sourceKey", it) }
    }

    /** 停止实时跟随；宿主离开会话时调用。 */
    suspend fun unfollowSession(sourceKey: String, sessionId: String) {
        val source = activeSources().firstOrNull { it.key == sourceKey } ?: return
        runCatching {
            invoke(
                source = source,
                handler = "sessionSource.unfollow",
                args = buildJsonObject { put("sessionId", sessionId) }
            )
        }.onFailure { FileLogger.w(TAG, "unfollowSession failed: $sourceKey", it) }
    }

    private fun activeSources(): List<PluginSessionSource> {        pluginManager.ensureLoaded()
        val languageTag = Locale.getDefault().toLanguageTag()
        return pluginManager.plugins.value
            .filter { it.isActive }
            .flatMap { plugin ->
                val runtime = plugin.content.runtime ?: return@flatMap emptyList()
                plugin.content.sessionSources.map { definition ->
                    PluginSessionSource(
                        key = definition.key,
                        pluginId = plugin.id,
                        pluginVersion = definition.pluginVersion,
                        pluginName = runCatching {
                            plugin.manifest?.name?.resolve(languageTag)
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: plugin.id,
                        title = definition.title.resolve(languageTag),
                        handlers = definition.handlers,
                        runtime = runtime
                    )
                }
            }
    }

    private suspend fun invoke(
        source: PluginSessionSource,
        handler: String,
        args: JsonObject,
        timeoutMs: Long = SESSION_SOURCE_TIMEOUT_MS
    ): JsonElement = runtimeManager.invoke(
        pluginId = source.pluginId,
        version = source.pluginVersion,
        runtime = source.runtime,
        handler = handler,
        args = args,
        method = "ui.invoke",
        timeoutMs = timeoutMs
    )

    private fun parseSessions(source: PluginSessionSource, response: JsonElement): List<PluginSessionSummary> {
        val root = response as? JsonObject ?: return emptyList()
        val array = root["sessions"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val sessionId = row.string("sessionId") ?: return@mapNotNull null
            val hostName = row.string("hostName").orEmpty()
            val workspaceTitle = row.string("workspaceTitle").orEmpty()
            val group = row.string("group").orEmpty().ifBlank {
                listOf(hostName, workspaceTitle).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { source.title }
            }
            val status = row.string("status").orEmpty().ifBlank {
                if (row.bool("running") == true) "running" else "idle"
            }
            PluginSessionSummary(
                id = "${source.key}|$sessionId",
                sessionId = sessionId,
                sourceKey = source.key,
                sourceTitle = source.title,
                pluginId = source.pluginId,
                pluginName = source.pluginName,
                pluginVersion = source.pluginVersion,
                title = row.string("title").orEmpty().ifBlank { "未命名会话" },
                status = status,
                running = row.bool("running") == true || status == "running",
                updatedAt = row.long("updatedAt"),
                group = group,
                hostName = hostName,
                workspaceTitle = workspaceTitle,
                capabilities = (row["capabilities"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                providerId = row.string("providerId"),
                model = row.string("model"),
                executionMode = row.string("executionMode")
            )
        }
    }

    private fun parseErrors(response: JsonElement): List<String> {
        val root = response as? JsonObject ?: return emptyList()
        return (root["errors"] as? JsonArray).orEmpty().mapNotNull { raw ->
            when (raw) {
                is JsonObject -> raw.string("message")
                is JsonPrimitive -> raw.contentOrNull
                else -> null
            }
        }
    }

    internal fun parseMessage(row: JsonObject?): PluginSessionMessage? {
        if (row == null) return null
        val id = row.string("id") ?: return null
        val role = row.string("role") ?: "assistant"
        val blocks = (row["blocks"] as? JsonArray).orEmpty().mapNotNull { parseBlock(it as? JsonObject) }
        if (blocks.isEmpty()) return null
        return PluginSessionMessage(
            id = id,
            role = role,
            createdAt = row.long("createdAt"),
            status = row.string("status") ?: "done",
            blocks = blocks
        )
    }

    private fun parseBlock(row: JsonObject?): PluginMessageBlock? {
        if (row == null) return null
        return when (row.string("type")) {
            "text" -> row.string("text")?.takeIf { it.isNotBlank() }?.let(PluginMessageBlock::Text)
            "reasoning" -> row.string("text")?.takeIf { it.isNotBlank() }?.let(PluginMessageBlock::Reasoning)
            "tool" -> {
                val name = row.string("name") ?: "tool"
                PluginMessageBlock.Tool(
                    callId = row.string("callId"),
                    name = name,
                    status = row.string("status") ?: "success",
                    input = jsonText(row["input"]),
                    output = blocksText(row["output"])
                )
            }
            "attachment" -> PluginMessageBlock.Attachment(
                kind = row.string("kind"),
                name = row.string("name"),
                mimeType = row.string("mimeType"),
                path = row.string("path")
            )
            "notice" -> row.string("text")?.takeIf { it.isNotBlank() }?.let(PluginMessageBlock::Notice)
            else -> row.string("text")?.takeIf { it.isNotBlank() }?.let(PluginMessageBlock::Text)
        }
    }

    private fun blocksText(element: JsonElement?): String? {
        val array = element as? JsonArray ?: return jsonText(element)
        val parts = array.mapNotNull { raw ->
            val block = raw as? JsonObject ?: return@mapNotNull jsonText(raw)
            block.string("text") ?: jsonText(block)
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
    }

    private fun jsonText(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        if (element is JsonPrimitive) return element.contentOrNull
        return element.toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        private const val TAG = "PluginSessionSource"
        private const val SESSION_SOURCE_TIMEOUT_MS = 60_000L
        private const val REFRESH_TIMEOUT_MS = 20_000L

        /** 与插件 sessionSource.sendMessage 的入参上限保持一致。 */
        private const val MAX_MESSAGE_CHARS = 32_000
    }
}
