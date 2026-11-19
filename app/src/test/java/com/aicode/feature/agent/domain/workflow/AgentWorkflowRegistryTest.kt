package com.aicode.feature.agent.domain.workflow

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentWorkflowRegistryTest {

    private val defaultWorkflow = mockk<AgentWorkflow>(relaxed = true)
    private val registry = AgentWorkflowRegistry(defaultWorkflow)

    @Test
    fun initialState_containsOnlyBuiltinWorkflow() {
        val list = registry.workflows.value
        assertEquals(1, list.size)
        assertEquals(
            WorkflowDescriptor(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "内置 Agent 工作流"),
            list.first()
        )
        assertSame(defaultWorkflow, registry.resolve(null))
        assertSame(defaultWorkflow, registry.resolve(""))
        assertSame(defaultWorkflow, registry.resolve("   "))
        assertSame(defaultWorkflow, registry.resolve(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID))
        assertThrows(WorkflowNotFoundException::class.java) { registry.resolve("unknown:workflow") }
        assertNull(registry.findDescriptor(null))
        assertNull(registry.findDescriptor(""))
        assertNull(registry.findDescriptor("unknown:workflow"))
        assertEquals(
            WorkflowDescriptor(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "内置 Agent 工作流"),
            registry.findDescriptor(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID)
        )
    }

    @Test
    fun syncPluginWorkflows_registersReplacesAndPreservesBuiltinFirst() {
        val wf1 = mockk<AgentWorkflow>(relaxed = true)
        val wf2 = mockk<AgentWorkflow>(relaxed = true)
        val wf3 = mockk<AgentWorkflow>(relaxed = true)

        registry.syncPluginWorkflows(
            "plugin-a",
            listOf(
                WorkflowDescriptor(
                    id = "plugin-a:wf1",
                    displayName = "WF 1",
                    capabilities = WorkflowCapabilities(streaming = true, tools = false, compaction = true)
                ) to wf1,
                WorkflowDescriptor(
                    id = "plugin-a:wf2",
                    displayName = "WF 2",
                    pluginId = "plugin-a"
                ) to wf2
            )
        )

        assertSame(wf1, registry.resolve("plugin-a:wf1"))
        assertSame(wf2, registry.resolve("plugin-a:wf2"))
        assertEquals("plugin-a", registry.findDescriptor("plugin-a:wf1")?.pluginId)
        assertEquals(
            WorkflowCapabilities(streaming = true, tools = false, compaction = true, titleGeneration = false),
            registry.findDescriptor("plugin-a:wf1")?.capabilities
        )
        assertEquals(
            listOf(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "plugin-a:wf1", "plugin-a:wf2"),
            registry.workflows.value.map { it.id }
        )

        // 再次 sync 会替换该插件之前的条目
        registry.syncPluginWorkflows(
            "plugin-a",
            listOf(WorkflowDescriptor(id = "plugin-a:wf3", displayName = "WF 3") to wf3)
        )

        assertThrows(WorkflowNotFoundException::class.java) { registry.resolve("plugin-a:wf1") }
        assertThrows(WorkflowNotFoundException::class.java) { registry.resolve("plugin-a:wf2") }
        assertSame(wf3, registry.resolve("plugin-a:wf3"))
        assertNull(registry.findDescriptor("plugin-a:wf1"))
        assertEquals(
            listOf(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "plugin-a:wf3"),
            registry.workflows.value.map { it.id }
        )
    }

    @Test
    fun syncPluginWorkflows_cannotOverrideBuiltinOrOtherPluginWorkflow() {
        val maliciousWf = mockk<AgentWorkflow>(relaxed = true)
        val wfA = mockk<AgentWorkflow>(relaxed = true)
        val wfB = mockk<AgentWorkflow>(relaxed = true)

        registry.syncPluginWorkflows(
            "plugin-a",
            listOf(
                WorkflowDescriptor(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "Fake Builtin") to maliciousWf,
                WorkflowDescriptor("plugin-a:shared", "Plugin A WF") to wfA
            )
        )
        registry.syncPluginWorkflows(
            "plugin-b",
            listOf(WorkflowDescriptor("plugin-b:shared", "Plugin B WF") to wfB)
        )

        assertSame(defaultWorkflow, registry.resolve(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID))
        assertEquals("内置 Agent 工作流", registry.findDescriptor(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID)?.displayName)
        assertSame(wfA, registry.resolve("plugin-a:shared"))
        assertEquals("plugin-a", registry.findDescriptor("plugin-a:shared")?.pluginId)
    }

    @Test
    fun unregisterPlugin_removesOnlyTargetPluginWorkflows() {
        val wfA = mockk<AgentWorkflow>(relaxed = true)
        val wfB = mockk<AgentWorkflow>(relaxed = true)

        registry.syncPluginWorkflows("plugin-a", listOf(WorkflowDescriptor("plugin-a:wf", "A") to wfA))
        registry.syncPluginWorkflows("plugin-b", listOf(WorkflowDescriptor("plugin-b:wf", "B") to wfB))

        registry.unregisterPlugin("plugin-a")

        assertThrows(WorkflowNotFoundException::class.java) { registry.resolve("plugin-a:wf") }
        assertNull(registry.findDescriptor("plugin-a:wf"))
        assertSame(wfB, registry.resolve("plugin-b:wf"))
        assertEquals(
            listOf(AgentWorkflowRegistry.BUILTIN_WORKFLOW_ID, "plugin-b:wf"),
            registry.workflows.value.map { it.id }
        )
    }
}
