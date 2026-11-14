package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 插件查看宿主 AI 工具与 MCP server 状态的只读入口。
 *
 * 只给出名字、描述、来源类型、授权策略与能力，不暴露参数 schema 与执行结果；插件未启用或内容
 * 损坏时所有方法返回 `PLUGIN_INACTIVE`。`type` 取 `plugin` / `mcp` / `builtin`，
 * `permissionPolicy` 与 `capabilities` 取枚举名的小写形式（如 `ask`、`execute_commands`）。
 */
@Singleton
class PluginIntrospectionService @Inject constructor(
    private val pluginManager: PluginManager,
    private val toolRegistry: ToolRegistry,
    private val mcpManager: McpManager
) {

    /** 已注册工具，按注册顺序最多 [MAX_TOOLS] 项；description 截断到 [MAX_DESCRIPTION_CHARS] 字符。 */
    fun listTools(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val tools = toolRegistry.getAvailableTools()
            .take(MAX_TOOLS)
            .map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description.take(MAX_DESCRIPTION_CHARS))
                    put("type", toolType(tool.name))
                    put("permissionPolicy", tool.permissionPolicy.name.lowercase(Locale.ROOT))
                    put("capabilities", JsonArray(tool.capabilities.map { capabilityName(it) }))
                }
            }
        return buildJsonObject { put("tools", JsonArray(tools)) }
    }

    /** MCP server 连接状态；来自宿主全局视图，未失败的 server 的 error 为 null。 */
    fun mcpStatus(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val servers = mcpManager.statuses.value.map { status ->
            buildJsonObject {
                put("name", status.name)
                put("state", status.state.name.lowercase(Locale.ROOT))
                put("toolCount", status.toolCount)
                put("error", status.error)
            }
        }
        return buildJsonObject { put("servers", JsonArray(servers)) }
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun toolType(name: String): String = when {
        name.startsWith(PLUGIN_TOOL_PREFIX) -> "plugin"
        name.startsWith(MCP_TOOL_PREFIX) -> "mcp"
        else -> "builtin"
    }

    private fun capabilityName(capability: ToolCapability): JsonPrimitive =
        JsonPrimitive(capability.name.lowercase(Locale.ROOT))

    private companion object {
        const val MAX_TOOLS = 200
        const val MAX_DESCRIPTION_CHARS = 500
        const val PLUGIN_TOOL_PREFIX = "plugin__"
        const val MCP_TOOL_PREFIX = "mcp__"
    }
}
