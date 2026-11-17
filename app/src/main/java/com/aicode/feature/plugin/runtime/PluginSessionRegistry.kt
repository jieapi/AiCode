package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.pluginContributionName
import com.aicode.feature.plugin.domain.session.PluginSessionGroup
import com.aicode.feature.plugin.domain.session.PluginSessionSource
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 插件会话源的 UI 状态缓存。
 *
 * 会话列表由插件通过 `host.sessionSource.setSessions` 主动推送，宿主不再在打开侧栏时
 * 同步调用 `sessionSource.listSessions` 阻塞分组出现。插件启用后先把 [PluginSessionSource]
 * 注册为占位分组（默认 loading），后续推送再填充会话与加载状态。
 */
@Singleton
class PluginSessionRegistry @Inject constructor() {

    private data class SourceState(
        val key: String,
        val pluginId: String,
        val pluginName: String,
        val title: String,
        val sessions: List<PluginSessionSummary> = emptyList(),
        val loading: Boolean = true,
        val loaded: Boolean = false,
        val error: String? = null
    )

    private val _sources = MutableStateFlow<Map<String, SourceState>>(emptyMap())
    private val _groups = MutableStateFlow<List<PluginSessionGroup>>(emptyList())
    private val _thinking = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 按插件聚合的侧栏分组；插件清单变化或收到推送时更新。 */
    val groups: StateFlow<List<PluginSessionGroup>> = _groups.asStateFlow()

    /** 会话级别的“正在思考”文案，key 为 `sourceKey|sessionId`（与 PluginSessionSummary.id 一致）。 */
    val thinking: StateFlow<Map<String, String>> = _thinking.asStateFlow()

    /** 用当前生效的插件会话源同步占位分组，保留已有会话与加载状态。 */
    fun syncSources(sources: List<PluginSessionSource>) {
        val previous = _sources.value
        val next = LinkedHashMap<String, SourceState>()
        for (source in sources) {
            val old = previous[source.key]
            next[source.key] = SourceState(
                key = source.key,
                pluginId = source.pluginId,
                pluginName = source.pluginName,
                title = source.title,
                sessions = old?.sessions.orEmpty(),
                loading = old?.loading ?: true,
                loaded = old?.loaded ?: false,
                error = old?.error
            )
        }
        _sources.value = next
        publishGroups()
    }

    /** 插件先于宿主同步清单推送时，补一个占位 source，避免首批数据被丢掉。 */
    fun ensureSource(pluginId: String, pluginName: String, sourceKey: String, title: String) {
        if (_sources.value.containsKey(sourceKey)) return
        _sources.update { current ->
            current + (sourceKey to SourceState(
                key = sourceKey,
                pluginId = pluginId,
                pluginName = pluginName,
                title = title
            ))
        }
        publishGroups()
    }

    fun setSessions(pluginId: String, sourceId: String, sessions: List<PluginSessionSummary>) {
        updateSource(pluginId, sourceId) {
            it.copy(sessions = sessions, loading = false, loaded = true, error = null)
        }
    }

    fun setLoading(pluginId: String, sourceId: String, loading: Boolean) {
        updateSource(pluginId, sourceId) { it.copy(loading = loading) }
    }

    fun setError(pluginId: String, sourceId: String, error: String?) {
        updateSource(pluginId, sourceId) {
            // 只有真正加载完成才隐藏转圈；错误也要给用户一个结束态，避免一直转。
            it.copy(error = error, loading = false, loaded = true)
        }
    }

    fun setThinking(sourceKey: String, sessionId: String, text: String?) {
        val key = thinkingKey(sourceKey, sessionId)
        _thinking.update { current ->
            if (text.isNullOrBlank()) current - key else current + (key to text)
        }
    }

    fun clearThinking(sessionId: String) {
        _thinking.update { current ->
            current.filterKeys { key -> !key.endsWith("|$sessionId") }
        }
    }

    private fun updateSource(pluginId: String, sourceId: String, transform: (SourceState) -> SourceState) {
        val key = pluginContributionName(pluginId, sourceId)
        _sources.update { current ->
            val state = current[key] ?: return@update current
            current + (key to transform(state))
        }
        publishGroups()
    }

    private fun publishGroups() {
        val grouped = _sources.value.values.groupBy { it.pluginId }
        _groups.value = grouped.map { (pluginId, sources) ->
            PluginSessionGroup(
                pluginId = pluginId,
                pluginName = sources.first().pluginName,
                sessions = sources.flatMap { it.sessions }
                    .sortedWith(compareByDescending<PluginSessionSummary> { it.updatedAt ?: 0L }
                        .thenBy { it.title }),
                loading = sources.any { it.loading },
                error = sources.mapNotNull { it.error }.firstOrNull()
            )
        }
    }

    private fun thinkingKey(sourceKey: String, sessionId: String): String = "$sourceKey|$sessionId"
}
