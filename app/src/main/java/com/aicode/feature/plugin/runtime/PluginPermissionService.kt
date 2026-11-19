package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class PluginPermissionService @Inject constructor(
    private val pluginManager: PluginManager,
    private val workspaceRepository: WorkspaceRepository,
    private val sessions: ChatSessionDao,
    private val permissions: ToolPermissionManager,
    private val externalSessionStore: ExternalSessionStore
) {
    private data class Entry(val id: String, val cancelled: CompletableDeferred<Unit>)
    private val pending = ConcurrentHashMap<Pair<String, String>, Entry>()

    suspend fun request(pluginId: String, params: JsonObject): JsonElement {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val sessionId = required(params, "sessionId", 1024)
        val requestId = required(params, "requestId", 256)
        val title = required(params, "title", 200)
        val description = required(params, "description", 4000)
        val operation = params["operation"] as? JsonObject
            ?: throw PluginHostException("PARAM_INVALID", "operation must be an object")
        required(operation, "kind", 100)
        if (operation.toString().length > 8000) throw PluginHostException("PARAM_INVALID", "operation is too large")
        val timeout = params["timeoutMs"]?.let {
            (it as? JsonPrimitive)?.longOrNull
                ?: throw PluginHostException("PARAM_INVALID", "timeoutMs must be a number")
        }?.coerceIn(1000L, 600000L) ?: 120000L
        val isExternalSession = sessionId.contains('|')
        val workspace = if (isExternalSession) {
            if (externalSessionStore.findSession(sessionId)?.pluginId != pluginId) forbidden()
            null
        } else {
            val activeWorkspace = workspaceRepository.current.value?.path
                ?.takeIf { it.isNotBlank() }
                ?: throw PluginHostException("WORKSPACE_UNAVAILABLE", "no active workspace")
            if (sessions.getById(sessionId)?.workspacePath != activeWorkspace) forbidden()
            activeWorkspace
        }
        val key = pluginId to requestId
        val entry = Entry("plugin-permission-${UUID.randomUUID()}", CompletableDeferred())
        if (pending.putIfAbsent(key, entry) != null) {
            throw PluginHostException("REQUEST_EXISTS", "permission request already exists: $requestId")
        }
        val name = plugin.manifest?.name?.resolve(Locale.getDefault().toLanguageTag())
            ?.takeIf { it.isNotBlank() } ?: pluginId
        try {
            val result = withTimeoutOrNull(timeout) {
                supervisorScope {
                    val approval = async(start = CoroutineStart.UNDISPATCHED) {
                        permissions.awaitApproval(PendingToolPermission(
                            id = entry.id,
                            toolName = operation["kind"].toString().trim('"'),
                            title = title,
                            summary = description,
                            details = operation.toString(),
                            argsPreview = "",
                            sessionId = sessionId,
                            origin = PendingToolPermission.ORIGIN_PLUGIN_PERMISSION,
                            pluginId = pluginId,
                            pluginName = name
                        ))
                    }
                    val inactive = async { pluginManager.plugins.first { rows -> rows.none { it.id == pluginId && it.isActive } } }
                    val moved = if (workspace != null) {
                        async { workspaceRepository.current.first { it?.path != workspace } }
                    } else {
                        null
                    }
                    val removed = async {
                        if (isExternalSession) {
                            externalSessionStore.groups.first {
                                externalSessionStore.findSession(sessionId)?.pluginId != pluginId
                            }
                        } else {
                            sessions.getByIdFlow(sessionId).first { it?.workspacePath != workspace }
                        }
                    }
                    try {
                        select<String> {
                            approval.onAwait { if (it == PermissionChoice.ONCE) "allow" else "deny" }
                            entry.cancelled.onAwait { "cancel" }
                            inactive.onAwait { "cancel" }
                            moved?.onAwait { "cancel" }
                            removed.onAwait { "cancel" }
                        }
                    } finally {
                        approval.cancel()
                        inactive.cancel()
                        moved?.cancel()
                        removed.cancel()
                    }
                }
            }
            val stillBound = pluginManager.plugins.value.any { it.id == pluginId && it.isActive } &&
                if (isExternalSession) {
                    externalSessionStore.findSession(sessionId)?.pluginId == pluginId
                } else {
                    workspaceRepository.current.value?.path == workspace &&
                        sessions.getById(sessionId)?.workspacePath == workspace
                }
            return buildJsonObject {
                put("decision", if (stillBound) result ?: "cancel" else "cancel")
                if (result == null) put("reason", "timeout")
            }
        } finally {
            pending.remove(key, entry)
        }
    }

    fun cancel(pluginId: String, params: JsonObject): JsonElement {
        val requestId = required(params, "requestId", 256)
        val cancelled = pending[pluginId to requestId]?.cancelled?.complete(Unit) ?: false
        return buildJsonObject { put("cancelled", cancelled) }
    }

    private fun required(params: JsonObject, key: String, max: Int): String {
        val raw = params[key] as? JsonPrimitive
        if (raw?.isString != true) throw PluginHostException("PARAM_INVALID", "$key must be a string")
        val value = PluginHostPaths.requireString(params, key).trim()
        if (value.isEmpty() || value.length > max) throw PluginHostException("PARAM_INVALID", "$key has invalid length")
        return value
    }

    private fun forbidden(): Nothing = throw PluginHostException("SESSION_FORBIDDEN", "session is not available to this plugin")
}
