package com.aicode.feature.plugin.data.index

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val PluginIndexJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

@Serializable
data class PluginIndexDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    val plugins: List<PluginIndexPlugin> = emptyList()
)

@Serializable
data class PluginIndexPlugin(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val repository: String,
    val logo: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val tags: List<String> = emptyList(),
    val version: String? = null,
    @SerialName("min_app_version") val minAppVersion: String? = null
)

object PluginTags {
    const val PROVIDER = "provider"
    const val TOOL = "tool"
    const val SKILL = "skill"
    const val AGENT = "agent"
    const val MCP = "mcp"
    const val UI = "ui"
    const val INTEGRATION = "integration"
    const val OTHER = "other"

    val ALL = setOf(PROVIDER, TOOL, SKILL, AGENT, MCP, UI, INTEGRATION, OTHER)
}
