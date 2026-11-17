package com.aicode.feature.plugin.domain.session

import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginSessionSourceHandlersRef
import kotlinx.serialization.json.JsonElement

/** 已解析且可调用的插件会话源；runtime 仅服务层使用，不进入 UI 状态。 */
data class PluginSessionSource(
    val key: String,
    val pluginId: String,
    val pluginVersion: String,
    val pluginName: String,
    val title: String,
    val handlers: PluginSessionSourceHandlersRef,
    val runtime: PluginRuntime
)

/** 外部会话列表的一行。sessionId 是插件返回的 opaque 值，App 只保存与回传。 */
data class PluginSessionSummary(
    val id: String,
    val sessionId: String,
    val sourceKey: String,
    val sourceTitle: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val title: String,
    val status: String,
    val running: Boolean,
    val updatedAt: Long?,
    val group: String,
    val hostName: String,
    val workspaceTitle: String,
    val capabilities: List<String> = emptyList(),
    val providerId: String? = null,
    val model: String? = null,
    val executionMode: String? = null
)

sealed interface PluginMessageBlock {
    data class Text(val text: String) : PluginMessageBlock
    data class Reasoning(val text: String) : PluginMessageBlock
    data class Tool(
        val callId: String?,
        val name: String,
        val status: String,
        val input: String?,
        val output: String?
    ) : PluginMessageBlock

    data class Attachment(
        val kind: String?,
        val name: String?,
        val mimeType: String?,
        val path: String?
    ) : PluginMessageBlock

    data class Notice(val text: String) : PluginMessageBlock
}

data class PluginSessionMessage(
    val id: String,
    val role: String,
    val createdAt: Long?,
    val status: String,
    val blocks: List<PluginMessageBlock>
)

data class PluginSessionPage(
    val sessionId: String,
    val title: String,
    val status: String,
    val messages: List<PluginSessionMessage>,
    val hasMore: Boolean,
    val nextCursor: JsonElement?
)

data class PluginSessionListResult(
    val sessions: List<PluginSessionSummary>,
    val errors: List<String> = emptyList()
)

data class PluginSessionGroup(
    val pluginId: String,
    val pluginName: String,
    val sessions: List<PluginSessionSummary>,
    /** 插件正在异步加载会话列表；侧栏数量位置显示转圈。 */
    val loading: Boolean = false,
    /** 最近一次会话列表加载错误；仅作诊断，不阻塞分组展示。 */
    val error: String? = null
)
