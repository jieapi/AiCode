package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 纯 MCP 配置解析器：从 `{"mcpServers": {...}}` 文本解析出 server 列表。
 * 用户配置与插件包内的 MCP 文件共用同一套解析规则，避免两套行为漂移。
 */
object McpConfigParser {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, logTag: String = "McpConfig"): List<McpServerConfig> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(logTag, "MCP 配置 JSON 解析失败: ${it.message}")
            return emptyList()
        }
        val servers = (root["mcpServers"] as? JsonObject) ?: return emptyList()

        return servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

            val command = (obj["command"] as? JsonPrimitive)?.contentOrNull
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull

            val disabledTools = (obj["disabledTools"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.toSet() ?: emptySet()

            when {
                !command.isNullOrBlank() -> {
                    val args = (obj["args"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: emptyList()
                    val env = (obj["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        command = command,
                        args = args,
                        env = env,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                !url.isNullOrBlank() -> {
                    val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    val auth = (obj["auth"] as? JsonObject)?.let { authObj ->
                        McpAuthConfig(
                            type = (authObj["type"] as? JsonPrimitive)?.contentOrNull ?: McpAuthConfig.TYPE_OAUTH,
                            clientId = (authObj["clientId"] as? JsonPrimitive)?.contentOrNull,
                            authServer = (authObj["authServer"] as? JsonPrimitive)?.contentOrNull,
                            scopes = (authObj["scopes"] as? JsonArray)?.mapNotNull {
                                (it as? JsonPrimitive)?.contentOrNull
                            }
                        )
                    }
                    McpServerConfig(
                        name = name,
                        url = url,
                        headers = headers,
                        enabled = enabled,
                        disabledTools = disabledTools,
                        auth = auth
                    )
                }
                else -> {
                    FileLogger.i(logTag, "跳过无法识别的 MCP server（缺 url/command）: $name")
                    null
                }
            }
        }
    }
}
