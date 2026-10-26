package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.plugin.domain.PluginAgentSource
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDefinitionRepositoryPluginTest {

    private val global = mockk<GlobalDirectoryAgentSource>().apply {
        every { listDefinitions() } returns emptyList()
    }
    private val project = mockk<ProjectDirectoryAgentSource>().apply {
        every { listDefinitions() } returns emptyList()
    }
    private val config = mockk<AgentDefinitionConfigRepository>().apply {
        every { disabledNames() } returns emptySet()
    }
    private val plugin = mockk<PluginAgentSource>().apply {
        every { listDefinitions() } returns emptyList()
        every { listAllDefinitions() } returns emptyList()
        every { revision } returns 0L
    }
    private val repository = AgentDefinitionRepository(global, project, config, TestFileAccessProvider(), plugin)

    private fun agent(name: String) = AgentDefinition(name = name, description = "d", prompt = "p")

    @Test
    fun listEnabled_appendsPluginAgentsWithPluginScope() {
        every { global.listDefinitions() } returns listOf(agent("zeta"))
        every { plugin.listDefinitions() } returns listOf(agent("b.kit:two"), agent("a.kit:one"))

        val entries = repository.listEnabled()
        assertEquals(listOf("zeta", "a.kit:one", "b.kit:two"), entries.map { it.definition.name })
        assertEquals(
            listOf(AgentDefinitionScope.GLOBAL, AgentDefinitionScope.PLUGIN, AgentDefinitionScope.PLUGIN),
            entries.map { it.scope }
        )
    }

    @Test
    fun listAll_excludesPluginAgents() {
        every { plugin.listDefinitions() } returns listOf(agent("a.kit:one"))

        assertTrue(repository.listAll().isEmpty())
    }

    @Test
    fun userDefinitionWinsOverSameNamedPluginAgent() {
        val own = agent("Same")
        every { global.listDefinitions() } returns listOf(own)
        every { plugin.listDefinitions() } returns listOf(agent("same"))

        assertEquals(listOf(own), repository.listEnabled().map { it.definition })
    }

    @Test
    fun disabledNames_filterPluginAgentsToo() {
        every { plugin.listDefinitions() } returns listOf(agent("a.kit:one"), agent("a.kit:two"))
        every { config.disabledNames() } returns setOf("a.kit:one")

        assertEquals(listOf("a.kit:two"), repository.listEnabled().map { it.definition.name })
        assertNull(repository.find("a.kit:one"))
        assertEquals("a.kit:two", repository.find("A.KIT:TWO")?.name)
    }

    @Test
    fun findIncludingDisabled_reachesInactivePluginAgents() {
        every { plugin.listAllDefinitions() } returns listOf(agent("a.kit:one"))

        assertNull(repository.find("a.kit:one"))
        assertEquals("a.kit:one", repository.findIncludingDisabled("a.kit:one")?.name)
    }

    @Test
    fun definitionsVersion_followsPluginRevision() {
        val before = repository.definitionsVersion
        every { plugin.revision } returns 7L

        assertEquals(before + 7L, repository.definitionsVersion)
    }
}
