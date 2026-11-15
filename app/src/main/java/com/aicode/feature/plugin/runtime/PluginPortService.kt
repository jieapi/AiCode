package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.PortForwardException
import com.aicode.feature.agent.domain.container.PortForwardInfo
import com.aicode.feature.agent.domain.container.PortForwardManager
import com.aicode.feature.plugin.domain.PluginHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 插件侧的端口转发入口：把 `host.ports.*` 转成 [PortForwardManager] 调用。
 *
 * 归属用插件 ID 表示——插件只能关闭自己建立的映射，配额也按插件计算。
 * SSH 凭据与连接始终由宿主持有，插件只能描述要映射的端口。
 */
@Singleton
class PluginPortService @Inject constructor(
    private val portForwardManager: PortForwardManager
) {

    suspend fun forward(pluginId: String, params: JsonObject): JsonElement {
        val port = readPort(params["port"]) ?: throw PluginHostException("PARAM_INVALID", "port is required")
        val localPort = readPort(params["localPort"])
        val host = (params["host"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { "127.0.0.1" }
        return portCall { portForwardManager.forward(pluginId, host, port, localPort) }.toJson()
    }

    suspend fun close(pluginId: String, params: JsonObject): JsonElement {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) throw PluginHostException("PARAM_INVALID", "id is required")
        return buildJsonObject { put("closed", portCall { portForwardManager.close(pluginId, id) }) }
    }

    suspend fun list(pluginId: String): JsonElement = buildJsonArray {
        portCall { portForwardManager.list(pluginId) }.forEach { add(it.toJson()) }
    }

    private suspend fun <T> portCall(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PortForwardException) {
        throw PluginHostException(e.code, e.message.orEmpty())
    }

    private fun readPort(element: JsonElement?): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it.checked() }
        val text = primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return (text.toIntOrNull() ?: throw PluginHostException("PARAM_INVALID", "invalid port: $text")).checked()
    }

    private fun Int.checked(): Int {
        if (this !in 1..65535) throw PluginHostException("PARAM_INVALID", "port out of range: $this")
        return this
    }

    private fun PortForwardInfo.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("localPort", localPort)
        put("remoteHost", remoteHost)
        put("remotePort", remotePort)
        put("url", url)
        put("direct", direct)
    }
}
