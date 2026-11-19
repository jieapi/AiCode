package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.workflow.AgentWorkflowRegistry
import com.aicode.feature.agent.domain.workflow.WorkflowCapabilities
import com.aicode.feature.agent.domain.workflow.WorkflowDescriptor
import com.aicode.feature.plugin.domain.PluginManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步已激活插件声明的 `contributes.workflows` 到 [AgentWorkflowRegistry]，
 * 并在插件停用或卸载时移除其注册的工作流。
 */
@Singleton
class PluginWorkflowRegistrar @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager,
    private val workflowEventBus: PluginWorkflowEventBus,
    private val workflowRegistry: AgentWorkflowRegistry
) {

    /** 将指定插件当前激活状态下的工作流同步到注册表；停用或不再声明时移除。 */
    fun sync(pluginId: String) {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId }
        val definitions = plugin?.takeIf { it.isActive }?.content?.workflows.orEmpty()
        if (definitions.isEmpty()) {
            removeAll(pluginId)
            return
        }
        val languageTag = Locale.getDefault().toLanguageTag()
        val entries: List<Pair<WorkflowDescriptor, AgentWorkflow>> = definitions.map { def ->
            val descriptor = WorkflowDescriptor(
                id = def.key,
                displayName = def.title.resolve(languageTag),
                pluginId = pluginId,
                capabilities = WorkflowCapabilities(
                    streaming = def.capabilities.streaming,
                    tools = def.capabilities.tools,
                    compaction = def.capabilities.compaction,
                    titleGeneration = def.capabilities.titleGeneration
                )
            )
            val adapter: AgentWorkflow = PluginAgentWorkflowAdapter(
                definition = def,
                runtimeManager = runtimeManager,
                workflowEventBus = workflowEventBus
            )
            descriptor to adapter
        }
        workflowRegistry.syncPluginWorkflows(pluginId, entries)
    }

    /** 全量对齐全部已安装插件声明的工作流。 */
    fun syncAll() {
        pluginManager.ensureLoaded()
        val activePlugins = pluginManager.plugins.value.filter { it.isActive }
        val activeIds = activePlugins.map { it.id }.toSet()
        workflowRegistry.workflows.value
            .mapNotNull { it.pluginId }
            .distinct()
            .filterNot { it in activeIds }
            .forEach { removeAll(it) }
        activePlugins.forEach { plugin ->
            sync(plugin.id)
        }
    }

    /** 插件停用或卸载：移除它注册的全部工作流。 */
    fun removeAll(pluginId: String) {
        workflowRegistry.unregisterPlugin(pluginId)
    }
}
