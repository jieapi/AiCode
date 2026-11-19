package com.aicode.feature.plugin.domain.session

import com.aicode.feature.plugin.domain.PluginExternalSessionHandlersRef
import com.aicode.feature.plugin.domain.PluginRuntime

/** 已注册的插件外部会话源元信息与运行时绑定。 */
data class ExternalSessionSource(
    val key: String,
    val pluginId: String,
    val pluginVersion: String,
    val pluginName: String,
    val title: String,
    val handlers: PluginExternalSessionHandlersRef,
    val runtime: PluginRuntime
)

/** 外部会话摘要；[id] 统一使用 `"$sourceKey|$sessionId"` 格式。 */
data class ExternalSessionSummary(
    val id: String,
    val sessionId: String,
    val sourceKey: String,
    val sourceTitle: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val title: String,
    val status: String = "",
    val running: Boolean = false,
    val updatedAt: Long = 0L,
    val group: String = "",
    val isPinned: Boolean = false,
    val workflowId: String? = null,
    val backendId: String? = null,
    val workspaceId: String? = null,
    val providerId: String? = null,
    val model: String? = null
) {
    companion object {
        fun compositeId(sourceKey: String, sessionId: String): String = "$sourceKey|$sessionId"
    }
}

/** 外部会话消息内容块。 */
sealed interface ExternalMessageBlock {
    data class Text(val text: String) : ExternalMessageBlock
    data class Reasoning(val text: String, val signature: String? = null) : ExternalMessageBlock
    data class Tool(
        val id: String = "",
        val name: String,
        val arguments: String = "",
        val result: String? = null,
        val isError: Boolean = false,
        val status: String = ""
    ) : ExternalMessageBlock

    data class Attachment(
        val name: String,
        val mimeType: String = "",
        val path: String? = null,
        val url: String? = null,
        val size: Long? = null
    ) : ExternalMessageBlock

    data class Notice(val text: String, val level: String = "info") : ExternalMessageBlock
}

/** 外部会话单条消息。 */
data class ExternalSessionMessage(
    val id: String,
    val role: String,
    val blocks: List<ExternalMessageBlock>,
    val timestamp: Long = 0L
)

/** 分页读取外部会话消息的结果。 */
data class ExternalSessionPage(
    val session: ExternalSessionSummary? = null,
    val messages: List<ExternalSessionMessage>,
    val nextCursor: kotlinx.serialization.json.JsonElement? = null,
    val hasMore: Boolean = false
)

/** 按插件聚合的外部会话分组视图。 */
data class ExternalSessionGroup(
    val pluginId: String,
    val pluginName: String,
    val sessions: List<ExternalSessionSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)
