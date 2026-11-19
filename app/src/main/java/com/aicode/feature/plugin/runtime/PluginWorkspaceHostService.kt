package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 处理插件发来的 `host.workspaces.*` 请求，校验插件激活态及目标 `backendId` 的归属权后写入
 * [PluginWorkspaceRegistry]。
 */
@Singleton
class PluginWorkspaceHostService @Inject constructor(
    private val pluginManager: PluginManager,
    private val workspaceRegistry: PluginWorkspaceRegistry
) {

    fun upsert(pluginId: String, params: JsonObject): JsonElement {
        val backend = requireOwnedBackend(pluginId, params)
        val items = parseWorkspaces(params, backend)
        workspaceRegistry.upsert(backend.key, items)
        return buildJsonObject {
            put("ok", true)
            put("count", items.size)
        }
    }

    fun remove(pluginId: String, params: JsonObject): JsonElement {
        val backend = requireOwnedBackend(pluginId, params)
        val ids = parseIds(params)
        workspaceRegistry.remove(backend.key, ids)
        return buildJsonObject {
            put("ok", true)
            put("count", ids.size)
        }
    }

    fun set(pluginId: String, params: JsonObject): JsonElement {
        val backend = requireOwnedBackend(pluginId, params)
        val items = parseWorkspaces(params, backend)
        workspaceRegistry.set(backend.key, items)
        return buildJsonObject {
            put("ok", true)
            put("count", items.size)
        }
    }

    private fun requireOwnedBackend(pluginId: String, params: JsonObject): PluginExecutionBackendDefinition {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val backendId = PluginHostPaths.requireString(params, "backendId").trim()
        if (backendId.isEmpty()) {
            throw PluginHostException("PARAM_INVALID", "backendId must not be empty")
        }
        return plugin.content.executionBackends.firstOrNull { it.key == backendId }
            ?: throw PluginHostException(
                "FORBIDDEN",
                "backendId does not belong to plugin $pluginId: $backendId"
            )
    }

    private fun parseWorkspaces(
        params: JsonObject,
        backend: PluginExecutionBackendDefinition
    ): List<Workspace> {
        val rawArray = (params["items"] ?: params["workspaces"]) as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "items must be an array")
        if (rawArray.size > MAX_ITEMS) {
            throw PluginHostException("PARAM_INVALID", "too many workspace items (max $MAX_ITEMS)")
        }
        val defaultType = if (backend.capabilities.isRemoteEnvironment) {
            WorkspaceType.REMOTE
        } else {
            WorkspaceType.INTERNAL
        }
        return rawArray.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw PluginHostException("PARAM_INVALID", "items[$index] must be an object")
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw PluginHostException("PARAM_INVALID", "items[$index].name is required")
            val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw PluginHostException("PARAM_INVALID", "items[$index].path is required")
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw PluginHostException("WORKSPACE_INVALID", "items[$index].id is required")
            val available = (obj["available"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: true
            val group = (obj["group"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                .orEmpty()
            val caps = when (val raw = obj["capabilities"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw PluginHostException("PARAM_INVALID", "items[$index].capabilities must be an object")
            }
            fun capability(key: String): Boolean {
                val raw = caps[key] ?: return true
                return (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw PluginHostException("PARAM_INVALID", "items[$index].capabilities.$key must be a boolean")
            }
            val canCreateChild = capability("createChild")
            val canDelete = capability("delete")
            capability("git")
            val type = when ((obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.lowercase()) {
                null, "" -> defaultType
                "internal" -> WorkspaceType.INTERNAL
                "external_local" -> WorkspaceType.EXTERNAL_LOCAL
                "remote" -> WorkspaceType.REMOTE
                else -> throw PluginHostException("PARAM_INVALID", "items[$index].type is invalid")
            }
            Workspace(
                name = name,
                path = path,
                type = type,
                available = available,
                id = id,
                backendId = backend.key,
                group = group,
                canCreateChild = canCreateChild,
                canDelete = canDelete
            )
        }
    }

    private fun parseIds(params: JsonObject): List<String> {
        val rawArray = params["workspaceIds"] as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "workspaceIds must be an array")
        if (rawArray.size > MAX_ITEMS) {
            throw PluginHostException("PARAM_INVALID", "too many workspaceIds (max $MAX_ITEMS)")
        }
        return rawArray.mapIndexed { index, element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw PluginHostException("PARAM_INVALID", "workspaceIds[$index] must be a non-empty string")
        }
    }

    private companion object {
        const val MAX_ITEMS = 500
    }
}
