package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.session.ExternalMessageBlock
import com.aicode.feature.plugin.domain.session.ExternalSessionMessage
import com.aicode.feature.plugin.domain.session.ExternalSessionPage
import com.aicode.feature.plugin.domain.session.ExternalSessionSource
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * 协调已激活插件声明的 `contributes.sessions` 外部会话源：
 * 同步已声明源、触发刷新、分页读取消息与会话管理（新建/重命名/置顶/删除），
 * 统一通过 `runtimeManager.invoke(..., method = "sessions.invoke")` 调用插件运行时。
 */
@Singleton
class ExternalSessionService @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager,
    private val store: ExternalSessionStore
) {

    fun syncDeclaredSources(): List<ExternalSessionSource> {
        pluginManager.ensureLoaded()
        val languageTag = Locale.getDefault().toLanguageTag()
        val sources = pluginManager.plugins.value
            .filter { it.isActive }
            .flatMap { plugin ->
                val pluginName = plugin.manifest?.name?.resolve(languageTag)
                    ?.takeIf { it.isNotBlank() } ?: plugin.id
                plugin.content.sessions.map { def ->
                    ExternalSessionSource(
                        key = def.key,
                        pluginId = def.pluginId,
                        pluginVersion = def.pluginVersion,
                        pluginName = pluginName,
                        title = def.title.resolve(languageTag).ifBlank { def.localId },
                        handlers = def.handlers,
                        runtime = def.runtime
                    )
                }
            }
        store.syncSources(sources)
        return sources
    }

    suspend fun requestRefresh(sourceKey: String? = null) {
        val sources = syncDeclaredSources().let { declared ->
            if (sourceKey == null) declared else declared.filter { it.key == sourceKey }
        }
        val refreshable = sources.filter { !it.handlers.refresh.isNullOrBlank() }
        if (refreshable.isEmpty()) return

        coroutineScope {
            refreshable.map { source ->
                async {
                    val handler = source.handlers.refresh ?: return@async
                    val sourceId = localIdOf(source)
                    store.setLoading(source.pluginId, sourceId, true)
                    store.setError(source.pluginId, sourceId, null)
                    try {
                        val result = runtimeManager.invoke(
                            pluginId = source.pluginId,
                            version = source.pluginVersion,
                            runtime = source.runtime,
                            handler = handler,
                            args = buildJsonObject {
                                put("sourceId", sourceId)
                                put("sourceKey", source.key)
                            },
                            method = SESSIONS_INVOKE_METHOD
                        )
                        val obj = result as? JsonObject
                        val rawSessions = (obj?.get("sessions") ?: obj?.get("items")) as? JsonArray
                        if (rawSessions != null) {
                            val parsed = rawSessions.mapIndexedNotNull { index, element ->
                                val itemObj = element as? JsonObject ?: return@mapIndexedNotNull null
                                ExternalSessionHostService.parseSummaryObject(
                                    obj = itemObj,
                                    index = index,
                                    sourceKey = source.key,
                                    sourceTitle = source.title,
                                    pluginId = source.pluginId,
                                    pluginName = source.pluginName,
                                    pluginVersion = source.pluginVersion
                                )
                            }
                            store.replaceAll(source.pluginId, sourceId, parsed)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "[${source.key}] refresh failed", e)
                        store.setError(source.pluginId, sourceId, e.message ?: "Refresh failed")
                    } finally {
                        store.setLoading(source.pluginId, sourceId, false)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun readMessages(
        sourceKey: String,
        sessionId: String,
        cursor: JsonElement? = null,
        maxMessages: Int = DEFAULT_MAX_MESSAGES
    ): ExternalSessionPage {
        val source = requireSource(sourceKey)
        val rawSessionId = stripCompositePrefix(source.key, sessionId)
        val result = runtimeManager.invoke(
            pluginId = source.pluginId,
            version = source.pluginVersion,
            runtime = source.runtime,
            handler = source.handlers.readMessages,
            args = buildJsonObject {
                put("sourceId", localIdOf(source))
                put("sourceKey", source.key)
                put("sessionId", rawSessionId)
                cursor?.let { put("cursor", it) }
                put("maxMessages", maxMessages)
            },
            method = SESSIONS_INVOKE_METHOD
        )
        return parsePage(result, source)
    }

    suspend fun createSession(
        sourceKey: String,
        title: String? = null
    ): ExternalSessionSummary {
        val source = requireSource(sourceKey)
        val handler = source.handlers.createSession
            ?: throw PluginHostException("UNSUPPORTED_OPERATION", "createSession is not supported: ${source.key}")
        val result = runtimeManager.invoke(
            pluginId = source.pluginId,
            version = source.pluginVersion,
            runtime = source.runtime,
            handler = handler,
            args = buildJsonObject {
                put("sourceId", localIdOf(source))
                put("sourceKey", source.key)
                title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            },
            method = SESSIONS_INVOKE_METHOD
        )
        val obj = result as? JsonObject
            ?: throw PluginHostException("SESSION_SOURCE_INVALID", "createSession must return an object")
        val sessionObj = (obj["session"] as? JsonObject) ?: obj
        val summary = ExternalSessionHostService.parseSummaryObject(
            obj = sessionObj,
            sourceKey = source.key,
            sourceTitle = source.title,
            pluginId = source.pluginId,
            pluginName = source.pluginName,
            pluginVersion = source.pluginVersion
        )
        store.upsert(source.pluginId, localIdOf(source), listOf(summary))
        return summary
    }

    suspend fun deleteSession(sourceKey: String, sessionId: String): Boolean {
        val source = requireSource(sourceKey)
        val handler = source.handlers.deleteSession
            ?: throw PluginHostException("UNSUPPORTED_OPERATION", "deleteSession is not supported: ${source.key}")
        val rawSessionId = stripCompositePrefix(source.key, sessionId)
        val result = runtimeManager.invoke(
            pluginId = source.pluginId,
            version = source.pluginVersion,
            runtime = source.runtime,
            handler = handler,
            args = buildJsonObject {
                put("sourceId", localIdOf(source))
                put("sourceKey", source.key)
                put("sessionId", rawSessionId)
            },
            method = SESSIONS_INVOKE_METHOD
        )
        val obj = result as? JsonObject
        val ok = obj?.get("ok")?.booleanVal()
            ?: obj?.get("deleted")?.booleanVal()
            ?: true
        if (ok) {
            store.remove(source.pluginId, localIdOf(source), listOf(rawSessionId))
        }
        return ok
    }

    suspend fun renameSession(
        sourceKey: String,
        sessionId: String,
        title: String
    ): ExternalSessionSummary? {
        val source = requireSource(sourceKey)
        val handler = source.handlers.renameSession
            ?: throw PluginHostException("UNSUPPORTED_OPERATION", "renameSession is not supported: ${source.key}")
        val rawSessionId = stripCompositePrefix(source.key, sessionId)
        val result = runtimeManager.invoke(
            pluginId = source.pluginId,
            version = source.pluginVersion,
            runtime = source.runtime,
            handler = handler,
            args = buildJsonObject {
                put("sourceId", localIdOf(source))
                put("sourceKey", source.key)
                put("sessionId", rawSessionId)
                put("title", title)
            },
            method = SESSIONS_INVOKE_METHOD
        )
        val obj = result as? JsonObject
        val sessionObj = obj?.get("session") as? JsonObject
        val resolvedTitle = sessionObj?.get("title")?.stringVal()
            ?: obj?.get("title")?.stringVal()
            ?: title
        val resolvedUpdatedAt = sessionObj?.get("updatedAt")?.longVal()
            ?: obj?.get("updatedAt")?.longVal()
        val compositeId = ExternalSessionSummary.compositeId(source.key, rawSessionId)
        if (store.findSession(compositeId) != null) {
            store.updateSummary(source.key, rawSessionId) { current ->
                current.copy(
                    title = resolvedTitle,
                    updatedAt = resolvedUpdatedAt ?: current.updatedAt
                )
            }
        } else if (sessionObj != null) {
            val summary = ExternalSessionHostService.parseSummaryObject(
                obj = sessionObj,
                sourceKey = source.key,
                sourceTitle = source.title,
                pluginId = source.pluginId,
                pluginName = source.pluginName,
                pluginVersion = source.pluginVersion
            )
            store.upsert(source.pluginId, localIdOf(source), listOf(summary))
        }
        return store.findSession(compositeId)
    }

    suspend fun pinSession(
        sourceKey: String,
        sessionId: String,
        pinned: Boolean
    ): ExternalSessionSummary? {
        val source = requireSource(sourceKey)
        val handler = source.handlers.pinSession
            ?: throw PluginHostException("UNSUPPORTED_OPERATION", "pinSession is not supported: ${source.key}")
        val rawSessionId = stripCompositePrefix(source.key, sessionId)
        val result = runtimeManager.invoke(
            pluginId = source.pluginId,
            version = source.pluginVersion,
            runtime = source.runtime,
            handler = handler,
            args = buildJsonObject {
                put("sourceId", localIdOf(source))
                put("sourceKey", source.key)
                put("sessionId", rawSessionId)
                put("pinned", pinned)
                put("isPinned", pinned)
            },
            method = SESSIONS_INVOKE_METHOD
        )
        val obj = result as? JsonObject
        val sessionObj = obj?.get("session") as? JsonObject
        val resolvedPinned = sessionObj?.get("isPinned")?.booleanVal()
            ?: sessionObj?.get("pinned")?.booleanVal()
            ?: obj?.get("isPinned")?.booleanVal()
            ?: obj?.get("pinned")?.booleanVal()
            ?: pinned
        val compositeId = ExternalSessionSummary.compositeId(source.key, rawSessionId)
        if (store.findSession(compositeId) != null) {
            store.updateSummary(source.key, rawSessionId) { current ->
                current.copy(isPinned = resolvedPinned)
            }
        } else if (sessionObj != null) {
            val summary = ExternalSessionHostService.parseSummaryObject(
                obj = sessionObj,
                sourceKey = source.key,
                sourceTitle = source.title,
                pluginId = source.pluginId,
                pluginName = source.pluginName,
                pluginVersion = source.pluginVersion
            )
            store.upsert(source.pluginId, localIdOf(source), listOf(summary))
        }
        return store.findSession(compositeId)
    }

    private fun requireSource(sourceKey: String): ExternalSessionSource {
        store.findSource(sourceKey)?.let { return it }
        syncDeclaredSources()
        return store.findSource(sourceKey)
            ?: throw PluginHostException("SESSION_SOURCE_NOT_FOUND", "external session source not found: $sourceKey")
    }

    private fun localIdOf(source: ExternalSessionSource): String {
        val prefix = "${source.pluginId}:"
        return if (source.key.startsWith(prefix)) source.key.removePrefix(prefix) else source.key
    }

    private fun stripCompositePrefix(sourceKey: String, sessionId: String): String {
        val prefix = "$sourceKey|"
        return if (sessionId.startsWith(prefix)) sessionId.removePrefix(prefix) else sessionId
    }

    private fun parsePage(element: JsonElement, source: ExternalSessionSource): ExternalSessionPage {
        val obj = element as? JsonObject
            ?: throw PluginHostException("SESSION_SOURCE_INVALID", "readMessages must return an object")
        val session = (obj["session"] as? JsonObject)?.let { sessionObj ->
            ExternalSessionHostService.parseSummaryObject(
                obj = sessionObj,
                sourceKey = source.key,
                sourceTitle = source.title,
                pluginId = source.pluginId,
                pluginName = source.pluginName,
                pluginVersion = source.pluginVersion
            )
        }
        val rawMessages = obj["messages"] as? JsonArray ?: JsonArray(emptyList())
        val messages = rawMessages.mapIndexedNotNull { index, msgElement ->
            val msgObj = msgElement as? JsonObject ?: return@mapIndexedNotNull null
            parseMessage(msgObj, index)
        }
        val nextCursor = obj["nextCursor"]
        val hasMore = obj["hasMore"]?.booleanVal() ?: (nextCursor != null && nextCursor != JsonNull)
        return ExternalSessionPage(
            session = session,
            messages = messages,
            nextCursor = nextCursor,
            hasMore = hasMore
        )
    }

    private fun parseMessage(obj: JsonObject, index: Int): ExternalSessionMessage {
        val id = obj["id"]?.stringVal() ?: "msg-$index"
        val role = obj["role"]?.stringVal() ?: "assistant"
        val timestamp = obj["createdAt"]?.longVal() ?: 0L
        val rawBlocks = obj["blocks"] as? JsonArray
        val blocks = if (rawBlocks != null) {
            rawBlocks.mapNotNull { blockElement ->
                val blockObj = blockElement as? JsonObject ?: return@mapNotNull null
                parseBlock(blockObj)
            }
        } else {
            buildList {
                obj["reasoning"]?.stringVal()?.takeIf { it.isNotEmpty() }?.let {
                    add(ExternalMessageBlock.Reasoning(text = it, signature = obj["signature"]?.stringVal()))
                }
                val text = obj["content"]?.stringVal() ?: obj["text"]?.stringVal()
                if (!text.isNullOrEmpty()) {
                    add(ExternalMessageBlock.Text(text = text))
                }
                val toolCalls = obj["toolCalls"] as? JsonArray
                toolCalls?.forEach { tc ->
                    val tcObj = tc as? JsonObject ?: return@forEach
                    add(
                        ExternalMessageBlock.Tool(
                            id = tcObj["id"]?.stringVal().orEmpty(),
                            name = tcObj["name"]?.stringVal() ?: "tool",
                            arguments = elementToText(tcObj["arguments"]),
                            result = tcObj["result"]?.let(::elementToText),
                            isError = tcObj["isError"]?.booleanVal() ?: false,
                            status = tcObj["status"]?.stringVal().orEmpty()
                        )
                    )
                }
            }
        }
        return ExternalSessionMessage(
            id = id,
            role = role,
            blocks = blocks,
            timestamp = timestamp
        )
    }

    private fun parseBlock(obj: JsonObject): ExternalMessageBlock? {
        return when (obj["type"]?.stringVal()?.lowercase(Locale.ROOT)) {
            "text" -> ExternalMessageBlock.Text(
                text = obj["text"]?.stringVal() ?: obj["content"]?.stringVal().orEmpty()
            )
            "reasoning", "thinking" -> ExternalMessageBlock.Reasoning(
                text = obj["text"]?.stringVal() ?: obj["content"]?.stringVal().orEmpty(),
                signature = obj["signature"]?.stringVal()
            )
            "tool", "tool_call", "toolcall" -> ExternalMessageBlock.Tool(
                id = obj["id"]?.stringVal() ?: obj["callId"]?.stringVal().orEmpty(),
                name = obj["name"]?.stringVal() ?: obj["toolName"]?.stringVal() ?: "tool",
                arguments = elementToText(obj["arguments"] ?: obj["args"]),
                result = (obj["result"] ?: obj["output"])?.takeUnless { it is JsonNull }?.let(::elementToText),
                isError = obj["isError"]?.booleanVal() ?: false,
                status = obj["status"]?.stringVal().orEmpty()
            )
            "attachment", "file", "image" -> ExternalMessageBlock.Attachment(
                name = obj["name"]?.stringVal() ?: obj["fileName"]?.stringVal().orEmpty(),
                mimeType = obj["mimeType"]?.stringVal() ?: obj["mime"]?.stringVal().orEmpty(),
                path = obj["path"]?.stringVal(),
                url = obj["url"]?.stringVal(),
                size = obj["size"]?.longVal()
            )
            "notice", "system", "info" -> ExternalMessageBlock.Notice(
                text = obj["text"]?.stringVal() ?: obj["content"]?.stringVal()
                    ?: obj["message"]?.stringVal().orEmpty(),
                level = obj["level"]?.stringVal() ?: "info"
            )
            else -> {
                val fallback = obj["text"]?.stringVal() ?: obj["content"]?.stringVal()
                fallback?.let { ExternalMessageBlock.Text(it) }
            }
        }
    }

    private companion object {
        const val TAG = "ExternalSessionService"
        const val SESSIONS_INVOKE_METHOD = "sessions.invoke"
        const val DEFAULT_MAX_MESSAGES = 50

        fun JsonElement.stringVal(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun JsonElement.booleanVal(): Boolean? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        fun JsonElement.longVal(): Long? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        fun elementToText(element: JsonElement?): String = when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            else -> element.toString()
        }
    }
}
