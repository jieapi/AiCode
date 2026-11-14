package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIntrospectionServiceTest {

    private val pluginId = "demo.introspection"

    private class FakeTool(
        override val name: String,
        override val description: String,
        override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE,
        override val capabilities: Set<ToolCapability> = emptySet()
    ) : AgentTool() {
        override val parameters: Map<String, ToolParameter> = emptyMap()

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    private fun service(
        active: Boolean = true,
        tools: List<AgentTool> = emptyList(),
        servers: List<McpServerStatus> = emptyList()
    ): PluginIntrospectionService {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns pluginId
            every { isActive } returns active
        }
        val pluginManager = mockk<PluginManager>(relaxed = true) {
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
        val toolRegistry = mockk<ToolRegistry> {
            every { getAvailableTools() } returns tools
        }
        val mcpManager = mockk<McpManager>(relaxed = true) {
            every { statuses } returns MutableStateFlow(servers)
        }
        return PluginIntrospectionService(
            pluginManager = pluginManager,
            toolRegistry = toolRegistry,
            mcpManager = mcpManager
        )
    }

    @Test
    fun listTools_rejectsInactivePlugin() {
        val error = runCatching { service(active = false).listTools(pluginId) }.exceptionOrNull()

        assertTrue(error is PluginHostException)
        assertEquals("PLUGIN_INACTIVE", (error as PluginHostException).code)
    }

    @Test
    fun mcpStatus_rejectsInactivePlugin() {
        val error = runCatching { service(active = false).mcpStatus(pluginId) }.exceptionOrNull()

        assertTrue(error is PluginHostException)
        assertEquals("PLUGIN_INACTIVE", (error as PluginHostException).code)
    }

    @Test
    fun listTools_classifiesByPrefixAndMapsPolicy() {
        val result = service(
            tools = listOf(
                FakeTool(
                    name = "plugin__demo__hello",
                    description = "say hi",
                    permissionPolicy = ToolPermissionPolicy.ASK,
                    capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
                ),
                FakeTool(
                    name = "mcp__files__read",
                    description = "read a file",
                    permissionPolicy = ToolPermissionPolicy.ASK,
                    capabilities = setOf(ToolCapability.READ_WORKSPACE)
                ),
                FakeTool(
                    name = "readFile",
                    description = "read a workspace file",
                    capabilities = setOf(ToolCapability.READ_WORKSPACE)
                )
            )
        ).listTools(pluginId) as JsonObject

        val tools = result["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, tools.size)
        assertEquals(listOf("plugin", "mcp", "builtin"), tools.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(
            setOf("name", "description", "type", "permissionPolicy", "capabilities"),
            tools.first().keys
        )
        assertEquals("ask", tools[0]["permissionPolicy"]!!.jsonPrimitive.content)
        assertEquals("ask", tools[1]["permissionPolicy"]!!.jsonPrimitive.content)
        assertEquals("auto_approve", tools[2]["permissionPolicy"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("external_tool"),
            tools[0]["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("read_workspace"),
            tools[1]["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun listTools_truncatesDescriptionTo500Chars() {
        val result = service(
            tools = listOf(FakeTool(name = "readFile", description = "x".repeat(600)))
        ).listTools(pluginId) as JsonObject

        val description = result["tools"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content
        assertEquals(500, description.length)
    }

    @Test
    fun listTools_capsAt200Tools() {
        val tools = (0 until 205).map { index -> FakeTool(name = "tool$index", description = "d$index") }

        val result = service(tools = tools).listTools(pluginId) as JsonObject

        val listed = result["tools"]!!.jsonArray
        assertEquals(200, listed.size)
        assertEquals("tool0", listed.first().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("tool199", listed.last().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun mcpStatus_mapsServerStates() {
        val result = service(
            servers = listOf(
                McpServerStatus("playwright", McpServerStatus.State.CONNECTED, toolCount = 7),
                McpServerStatus("files", McpServerStatus.State.FAILED, error = "boom"),
                McpServerStatus("pending", McpServerStatus.State.NEEDS_AUTH)
            )
        ).mcpStatus(pluginId) as JsonObject

        val servers = result["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, servers.size)
        assertEquals(
            listOf("connected", "failed", "needs_auth"),
            servers.map { it["state"]!!.jsonPrimitive.content }
        )
        assertEquals(7, servers[0]["toolCount"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, servers[0]["error"])
        assertEquals("boom", servers[1]["error"]!!.jsonPrimitive.content)
        assertEquals(0, servers[2]["toolCount"]!!.jsonPrimitive.int)
    }
}
