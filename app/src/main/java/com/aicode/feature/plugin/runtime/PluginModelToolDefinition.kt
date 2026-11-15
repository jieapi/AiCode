package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 插件在 `host.model.start` 里声明的自定义工具。
 *
 * 宿主只把定义原样转给模型的 function-calling 接口，不执行任何调用：模型返回的 toolCalls
 * 由插件自己处理。因此 [execute] 只作为 [AgentTool] 的占位实现，永远不会被调用。
 */
class PluginModelToolDefinition(
    override val name: String,
    override val description: String,
    private val inputSchema: JsonObject
) : AgentTool() {

    override val parameters: Map<String, ToolParameter> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    override fun toJsonSchema(): Map<String, Any> = jsonToAny(inputSchema) as Map<String, Any>

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
        throw UnsupportedOperationException("plugin-defined tool is not executed by the host")

    private fun jsonToAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { (_, value) -> jsonToAny(value) }
        is JsonArray -> element.map { jsonToAny(it) }
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }
}
