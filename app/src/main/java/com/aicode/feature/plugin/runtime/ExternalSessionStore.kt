package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.pluginContributionName
import com.aicode.feature.plugin.domain.session.ExternalSessionGroup
import com.aicode.feature.plugin.domain.session.ExternalSessionSource
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内存中的外部会话增量状态仓库，按 `sourceKey` 维护 `LinkedHashMap<String, ExternalSessionSummary>`，
 * 对外按插件聚合输出有序分组视图与实时思考状态。
 */
@Singleton
class ExternalSessionStore @Inject constructor() {

    private class SourceState(
        var pluginId: String,
        var pluginName: String,
        val sourceKey: String,
        var title: String,
        var source: ExternalSessionSource? = null,
        val sessions: LinkedHashMap<String, ExternalSessionSummary> = LinkedHashMap(),
        var loading: Boolean = false,
        var error: String? = null
    )

    private val lock = Any()
    private val sourcesByKey = LinkedHashMap<String, SourceState>()
    private val thinkingByCompositeId = LinkedHashMap<String, String>()

    private val _groups = MutableStateFlow<List<ExternalSessionGroup>>(emptyList())
    val groups: StateFlow<List<ExternalSessionGroup>> = _groups.asStateFlow()

    private val _thinking = MutableStateFlow<Map<String, String>>(emptyMap())
    val thinking: StateFlow<Map<String, String>> = _thinking.asStateFlow()

    fun syncSources(sources: List<ExternalSessionSource>) {
        synchronized(lock) {
            val activeKeys = sources.mapTo(HashSet(sources.size)) { it.key }
            val removedIterator = sourcesByKey.entries.iterator()
            var thinkingChanged = false
            while (removedIterator.hasNext()) {
                val (key, bucket) = removedIterator.next()
                if (key !in activeKeys) {
                    bucket.sessions.keys.forEach { sessionId ->
                        if (thinkingByCompositeId.remove(ExternalSessionSummary.compositeId(key, sessionId)) != null) {
                            thinkingChanged = true
                        }
                    }
                    removedIterator.remove()
                }
            }
            sources.forEach { source ->
                val existing = sourcesByKey[source.key]
                if (existing == null) {
                    sourcesByKey[source.key] = SourceState(
                        pluginId = source.pluginId,
                        pluginName = source.pluginName,
                        sourceKey = source.key,
                        title = source.title,
                        source = source
                    )
                } else {
                    existing.pluginId = source.pluginId
                    existing.pluginName = source.pluginName
                    existing.title = source.title
                    existing.source = source
                }
            }
            if (thinkingChanged) {
                publishThinkingLocked()
            }
            publishGroupsLocked()
        }
    }

    fun ensureSource(pluginId: String, pluginName: String, sourceKey: String, title: String) {
        synchronized(lock) {
            val resolvedKey = resolveSourceKey(pluginId, sourceKey)
            val existing = sourcesByKey[resolvedKey]
            if (existing == null) {
                sourcesByKey[resolvedKey] = SourceState(
                    pluginId = pluginId,
                    pluginName = pluginName.ifBlank { pluginId },
                    sourceKey = resolvedKey,
                    title = title
                )
            } else {
                if (pluginName.isNotBlank()) existing.pluginName = pluginName
                if (title.isNotBlank()) existing.title = title
            }
            publishGroupsLocked()
        }
    }

    fun upsert(pluginId: String, sourceId: String, sessions: List<ExternalSessionSummary>) {
        if (sessions.isEmpty()) return
        synchronized(lock) {
            val bucket = getOrCreateBucketLocked(pluginId, sourceId)
            sessions.forEach { raw ->
                val normalized = normalizeSummary(bucket, raw)
                bucket.sessions[normalized.sessionId] = normalized
            }
            publishGroupsLocked()
        }
    }

    fun remove(pluginId: String, sourceId: String, sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        synchronized(lock) {
            val sourceKey = resolveSourceKey(pluginId, sourceId)
            val bucket = sourcesByKey[sourceKey] ?: return
            var removedAny = false
            var thinkingChanged = false
            val prefix = "$sourceKey|"
            sessionIds.forEach { rawId ->
                val normalizedId = if (rawId.startsWith(prefix)) rawId.removePrefix(prefix) else rawId
                if (bucket.sessions.remove(normalizedId) != null) {
                    removedAny = true
                }
                val compositeId = ExternalSessionSummary.compositeId(sourceKey, normalizedId)
                if (thinkingByCompositeId.remove(compositeId) != null) {
                    thinkingChanged = true
                }
            }
            if (thinkingChanged) {
                publishThinkingLocked()
            }
            if (removedAny) {
                publishGroupsLocked()
            }
        }
    }

    fun replaceAll(pluginId: String, sourceId: String, sessions: List<ExternalSessionSummary>) {
        synchronized(lock) {
            val bucket = getOrCreateBucketLocked(pluginId, sourceId)
            val nextIds = HashSet<String>(sessions.size)
            val oldIds = bucket.sessions.keys.toList()
            bucket.sessions.clear()
            sessions.forEach { raw ->
                val normalized = normalizeSummary(bucket, raw)
                nextIds += normalized.sessionId
                bucket.sessions[normalized.sessionId] = normalized
            }
            var thinkingChanged = false
            oldIds.forEach { oldId ->
                if (oldId !in nextIds) {
                    val compositeId = ExternalSessionSummary.compositeId(bucket.sourceKey, oldId)
                    if (thinkingByCompositeId.remove(compositeId) != null) {
                        thinkingChanged = true
                    }
                }
            }
            if (thinkingChanged) {
                publishThinkingLocked()
            }
            publishGroupsLocked()
        }
    }

    fun updateSummary(
        sourceKey: String,
        sessionId: String,
        transform: (ExternalSessionSummary) -> ExternalSessionSummary
    ) {
        synchronized(lock) {
            val bucket = sourcesByKey[sourceKey] ?: return
            val prefix = "$sourceKey|"
            val normalizedId = if (sessionId.startsWith(prefix)) sessionId.removePrefix(prefix) else sessionId
            val current = bucket.sessions[normalizedId] ?: return
            val transformed = transform(current)
            val normalized = normalizeSummary(bucket, transformed).copy(
                sessionId = current.sessionId,
                id = current.id,
                sourceKey = current.sourceKey,
                pluginId = current.pluginId
            )
            bucket.sessions[normalizedId] = normalized
            publishGroupsLocked()
        }
    }

    fun setLoading(pluginId: String, sourceId: String, loading: Boolean) {
        synchronized(lock) {
            val bucket = getOrCreateBucketLocked(pluginId, sourceId)
            if (bucket.loading != loading) {
                bucket.loading = loading
                publishGroupsLocked()
            }
        }
    }

    fun setError(pluginId: String, sourceId: String, error: String?) {
        synchronized(lock) {
            val bucket = getOrCreateBucketLocked(pluginId, sourceId)
            val normalized = error?.takeIf { it.isNotBlank() }
            if (bucket.error != normalized) {
                bucket.error = normalized
                publishGroupsLocked()
            }
        }
    }

    fun setThinking(sourceKey: String, sessionId: String, text: String) {
        synchronized(lock) {
            val prefix = "$sourceKey|"
            val normalizedId = if (sessionId.startsWith(prefix)) sessionId.removePrefix(prefix) else sessionId
            val compositeId = ExternalSessionSummary.compositeId(sourceKey, normalizedId)
            if (text.isBlank()) {
                if (thinkingByCompositeId.remove(compositeId) != null) {
                    publishThinkingLocked()
                }
            } else {
                thinkingByCompositeId[compositeId] = text
                publishThinkingLocked()
            }
        }
    }

    fun clearThinking(sessionId: String) {
        synchronized(lock) {
            var changed = false
            if (thinkingByCompositeId.remove(sessionId) != null) {
                changed = true
            }
            val suffix = "|$sessionId"
            val iterator = thinkingByCompositeId.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key == sessionId || key.endsWith(suffix)) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                publishThinkingLocked()
            }
        }
    }

    fun unregisterPlugin(pluginId: String) {
        synchronized(lock) {
            val prefix = "$pluginId:"
            var sourcesChanged = false
            var thinkingChanged = false
            val sourceIterator = sourcesByKey.entries.iterator()
            while (sourceIterator.hasNext()) {
                val (key, bucket) = sourceIterator.next()
                if (bucket.pluginId == pluginId || key.startsWith(prefix)) {
                    bucket.sessions.keys.forEach { sessionId ->
                        if (thinkingByCompositeId.remove(ExternalSessionSummary.compositeId(key, sessionId)) != null) {
                            thinkingChanged = true
                        }
                    }
                    sourceIterator.remove()
                    sourcesChanged = true
                }
            }
            val thinkingIterator = thinkingByCompositeId.keys.iterator()
            while (thinkingIterator.hasNext()) {
                if (thinkingIterator.next().startsWith(prefix)) {
                    thinkingIterator.remove()
                    thinkingChanged = true
                }
            }
            if (thinkingChanged) {
                publishThinkingLocked()
            }
            if (sourcesChanged) {
                publishGroupsLocked()
            }
        }
    }

    fun findSession(compositeId: String): ExternalSessionSummary? {
        synchronized(lock) {
            val separator = compositeId.indexOf('|')
            if (separator > 0) {
                val sourceKey = compositeId.substring(0, separator)
                val sessionId = compositeId.substring(separator + 1)
                sourcesByKey[sourceKey]?.sessions?.get(sessionId)?.let { return it }
            }
            sourcesByKey.values.forEach { bucket ->
                bucket.sessions.values.firstOrNull { it.id == compositeId }?.let { return it }
            }
            return null
        }
    }

    fun findSource(sourceKey: String): ExternalSessionSource? {
        synchronized(lock) {
            return sourcesByKey[sourceKey]?.source
        }
    }

    fun allSources(): List<ExternalSessionSource> {
        synchronized(lock) {
            return sourcesByKey.values.mapNotNull { it.source }
        }
    }

    private fun getOrCreateBucketLocked(pluginId: String, sourceId: String): SourceState {
        val sourceKey = resolveSourceKey(pluginId, sourceId)
        return sourcesByKey.getOrPut(sourceKey) {
            SourceState(
                pluginId = pluginId,
                pluginName = pluginId,
                sourceKey = sourceKey,
                title = sourceId
            )
        }
    }

    private fun resolveSourceKey(pluginId: String, sourceIdOrKey: String): String =
        if (sourceIdOrKey.startsWith("$pluginId:")) sourceIdOrKey
        else pluginContributionName(pluginId, sourceIdOrKey)

    private fun normalizeSummary(bucket: SourceState, raw: ExternalSessionSummary): ExternalSessionSummary {
        val prefix = "${bucket.sourceKey}|"
        val resolvedSessionId = when {
            raw.sessionId.startsWith(prefix) -> raw.sessionId.removePrefix(prefix)
            raw.sessionId.isNotBlank() -> raw.sessionId
            raw.id.startsWith(prefix) -> raw.id.removePrefix(prefix)
            else -> raw.id
        }
        val resolvedSourceTitle = raw.sourceTitle.ifBlank { bucket.title }
        val resolvedPluginName = raw.pluginName.ifBlank { bucket.pluginName }
        val resolvedPluginVersion = raw.pluginVersion.ifBlank { bucket.source?.pluginVersion.orEmpty() }
        return raw.copy(
            id = ExternalSessionSummary.compositeId(bucket.sourceKey, resolvedSessionId),
            sessionId = resolvedSessionId,
            sourceKey = bucket.sourceKey,
            sourceTitle = resolvedSourceTitle,
            pluginId = bucket.pluginId,
            pluginName = resolvedPluginName,
            pluginVersion = resolvedPluginVersion
        )
    }

    private fun publishGroupsLocked() {
        val grouped = LinkedHashMap<String, MutableList<SourceState>>()
        sourcesByKey.values.forEach { bucket ->
            grouped.getOrPut(bucket.pluginId) { mutableListOf() }.add(bucket)
        }
        _groups.value = grouped.entries.map { (pluginId, buckets) ->
            val pluginName = buckets.firstOrNull { it.pluginName.isNotBlank() }?.pluginName ?: pluginId
            val sessions = buckets
                .flatMap { it.sessions.values }
                .sortedWith(SUMMARY_COMPARATOR)
            val loading = buckets.any { it.loading }
            val error = buckets.firstNotNullOfOrNull { it.error }
            ExternalSessionGroup(
                pluginId = pluginId,
                pluginName = pluginName,
                sessions = sessions,
                loading = loading,
                error = error
            )
        }
    }

    private fun publishThinkingLocked() {
        _thinking.value = LinkedHashMap(thinkingByCompositeId)
    }

    private companion object {
        val SUMMARY_COMPARATOR = compareByDescending<ExternalSessionSummary> { it.isPinned }
            .thenByDescending { it.updatedAt }
            .thenBy { it.title }
    }
}
