package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步已激活插件声明的 `contributes.executionBackends` 到 [ExecutionBackendRegistry]，
 * 并在插件停用或卸载时清理其执行后端与动态工作区。
 */
@Singleton
class PluginExecutionBackendRegistrar @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager,
    private val backendRegistry: ExecutionBackendRegistry,
    private val workspaceRegistry: PluginWorkspaceRegistry
) {

    /** 将指定插件当前激活状态下的执行后端同步到注册表；停用或不再声明时移除。 */
    suspend fun sync(
        pluginId: String,
        beforeActiveBackendChange: suspend (ExecutionBackend) -> Unit = {}
    ) {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId }
        val definitions = plugin?.takeIf { it.isActive }?.content?.executionBackends.orEmpty()
        val active = backendRegistry.currentBackend()
        val nextDefinition = definitions.firstOrNull { it.key == active.id }
        val currentDefinition = (backendRegistry.find(active.id) as? PluginExecutionBackendAdapter)?.definition
        if (active.id.startsWith("$pluginId:") && nextDefinition != currentDefinition) {
            beforeActiveBackendChange(active)
        }
        if (definitions.isEmpty()) {
            removeAll(pluginId)
            return
        }
        val adapters = definitions.map { definition ->
            (backendRegistry.find(definition.key) as? PluginExecutionBackendAdapter)
                ?.takeIf { it.definition == definition }
                ?: PluginExecutionBackendAdapter(definition, runtimeManager)
        }
        backendRegistry.syncPluginBackends(pluginId, adapters)
    }

    /** 全量对齐全部已安装插件的执行后端。 */
    suspend fun syncAll(
        beforeActiveBackendChange: suspend (ExecutionBackend) -> Unit = {}
    ) {
        pluginManager.ensureLoaded()
        val activePlugins = pluginManager.plugins.value.filter { it.isActive }
        val activeIds = activePlugins.map { it.id }.toSet()
        backendRegistry.registeredPluginIds()
            .filterNot { it in activeIds }
            .forEach { pluginId ->
                val active = backendRegistry.currentBackend()
                if (active.id.startsWith("$pluginId:")) beforeActiveBackendChange(active)
                removeAll(pluginId)
            }
        activePlugins.forEach { plugin ->
            sync(plugin.id, beforeActiveBackendChange)
        }
    }

    /** 插件停用或卸载：移除它注册的全部执行后端与工作区。 */
    fun removeAll(pluginId: String) {
        backendRegistry.unregisterPlugin(pluginId)
        workspaceRegistry.clearPlugin(pluginId)
    }
}
