package com.aicode.feature.agent.domain.workflow

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorkflowNotFoundException(workflowId: String) :
    IllegalArgumentException("WORKFLOW_NOT_FOUND: $workflowId")

data class WorkflowCapabilities(
    val streaming: Boolean = true,
    val tools: Boolean = true,
    val compaction: Boolean = false,
    val titleGeneration: Boolean = false
)

data class WorkflowDescriptor(
    val id: String,
    val displayName: String,
    val pluginId: String? = null,
    val capabilities: WorkflowCapabilities = WorkflowCapabilities()
)

@Singleton
class AgentWorkflowRegistry @Inject constructor(
    private val defaultWorkflow: AgentWorkflow
) {
    private val lock = Any()
    private val builtinDescriptor = WorkflowDescriptor(BUILTIN_WORKFLOW_ID, "内置 Agent 工作流")
    private val pluginEntries = LinkedHashMap<String, Pair<WorkflowDescriptor, AgentWorkflow>>()

    private val _workflows = MutableStateFlow(listOf(builtinDescriptor))
    val workflows: StateFlow<List<WorkflowDescriptor>> = _workflows.asStateFlow()

    fun resolve(workflowId: String?): AgentWorkflow {
        if (workflowId.isNullOrBlank() || workflowId == BUILTIN_WORKFLOW_ID) {
            return defaultWorkflow
        }
        return synchronized(lock) {
            pluginEntries[workflowId]?.second ?: throw WorkflowNotFoundException(workflowId)
        }
    }

    fun findDescriptor(workflowId: String?): WorkflowDescriptor? {
        if (workflowId.isNullOrBlank()) return null
        if (workflowId == BUILTIN_WORKFLOW_ID) return builtinDescriptor
        return synchronized(lock) {
            pluginEntries[workflowId]?.first
        }
    }

    fun syncPluginWorkflows(pluginId: String, entries: List<Pair<WorkflowDescriptor, AgentWorkflow>>) {
        if (pluginId.isBlank()) return
        synchronized(lock) {
            val prefix = "$pluginId:"
            val iterator = pluginEntries.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.first.pluginId == pluginId) {
                    iterator.remove()
                }
            }
            for ((descriptor, workflow) in entries) {
                val id = descriptor.id
                if (id == BUILTIN_WORKFLOW_ID) continue
                require(id.startsWith(prefix)) { "Workflow id must use plugin prefix: $id" }
                val existingOwner = pluginEntries[id]?.first?.pluginId
                if (existingOwner != null && existingOwner != pluginId) continue
                val normalized = if (descriptor.pluginId == pluginId) {
                    descriptor
                } else {
                    descriptor.copy(pluginId = pluginId)
                }
                pluginEntries[id] = normalized to workflow
            }
            publishLocked()
        }
    }

    fun unregisterPlugin(pluginId: String) {
        if (pluginId.isBlank()) return
        synchronized(lock) {
            var changed = false
            val iterator = pluginEntries.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.first.pluginId == pluginId) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) publishLocked()
        }
    }

    private fun publishLocked() {
        _workflows.value = listOf(builtinDescriptor) + pluginEntries.values.map { it.first }
    }

    companion object {
        const val BUILTIN_WORKFLOW_ID = "builtin:stateful-agent"
    }
}
