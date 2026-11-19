package com.aicode.feature.plugin.runtime

import com.aicode.feature.workspace.domain.model.Workspace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 插件动态注册工作区的内存注册表，按 `backendId`（`<pluginId>:<localId>`）隔离存储。
 */
@Singleton
class PluginWorkspaceRegistry @Inject constructor() {

    private val lock = Any()
    private val _workspacesByBackend = MutableStateFlow<Map<String, List<Workspace>>>(emptyMap())
    val workspacesByBackend: StateFlow<Map<String, List<Workspace>>> = _workspacesByBackend.asStateFlow()

    fun workspacesFor(backendId: String): List<Workspace> =
        _workspacesByBackend.value[backendId].orEmpty()

    /** 按 `id` 增量插入或更新工作区；同 `id` 覆盖原项，其余保留顺序。 */
    fun upsert(backendId: String, items: List<Workspace>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val current = _workspacesByBackend.value[backendId].orEmpty()
            val merged = LinkedHashMap<String, Workspace>(current.size + items.size)
            current.forEach { ws ->
                merged[ws.id] = ws
            }
            items.forEach { item ->
                val normalized = normalize(backendId, item)
                merged[normalized.id] = normalized
            }
            val next = _workspacesByBackend.value.toMutableMap()
            next[backendId] = merged.values.toList()
            _workspacesByBackend.value = next
        }
    }

    /** 从指定后端移除匹配的 `ids` 工作区；列表为空时移除该后端键。 */
    fun remove(backendId: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        synchronized(lock) {
            val current = _workspacesByBackend.value[backendId] ?: return
            val filtered = current.filterNot { it.id in idSet }
            val next = _workspacesByBackend.value.toMutableMap()
            if (filtered.isEmpty()) {
                next.remove(backendId)
            } else {
                next[backendId] = filtered
            }
            _workspacesByBackend.value = next
        }
    }

    /** 全量替换指定后端下的工作区列表；空列表将清空并移除该后端键。 */
    fun set(backendId: String, items: List<Workspace>) {
        synchronized(lock) {
            val deduped = LinkedHashMap<String, Workspace>(items.size)
            items.forEach { item ->
                val normalized = normalize(backendId, item)
                deduped[normalized.id] = normalized
            }
            val next = _workspacesByBackend.value.toMutableMap()
            if (deduped.isEmpty()) {
                next.remove(backendId)
            } else {
                next[backendId] = deduped.values.toList()
            }
            _workspacesByBackend.value = next
        }
    }

    /** 清理指定插件下全部执行后端注册的工作区。 */
    fun clearPlugin(pluginId: String) {
        val prefix = "$pluginId:"
        synchronized(lock) {
            val next = _workspacesByBackend.value.filterKeys { !it.startsWith(prefix) }
            if (next.size != _workspacesByBackend.value.size) {
                _workspacesByBackend.value = next
            }
        }
    }

    private fun normalize(backendId: String, item: Workspace): Workspace {
        require(item.id.isNotBlank()) { "workspace.id is required" }
        return item.copy(
            backendId = backendId,
            instanceId = backendId
        )
    }
}
