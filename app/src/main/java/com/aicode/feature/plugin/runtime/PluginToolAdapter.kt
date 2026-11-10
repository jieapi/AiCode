package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.domain.PluginTool
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 把一个插件声明的 AI 工具适配成 [AgentTool]。
 *
 * 对模型暴露的名字带 `plugin__` 命名空间并清洗成 function-calling 合法字符；实际调用时用
 * 清单里的 handler 交给 [PluginRuntimeManager]。工具统一按外部工具处理：默认 ASK，
 * PLAN 模式下由权限策略拦截，不信任插件自报的“只读”声明。
 */
class PluginToolAdapter(
    private val context: Context,
    private val pluginId: String,
    private val pluginName: String,
    private val pluginVersion: String,
    private val tool: PluginTool,
    private val runtime: PluginRuntimeManager,
    private val isStillAuthorized: () -> Boolean
) : AgentTool() {

    override val name: String = buildNamespacedName(pluginId, tool.localId)

    override val description: String = tool.description
        .resolve(Locale.getDefault().toLanguageTag())
        .ifBlank { tool.title.resolve(Locale.getDefault().toLanguageTag()) }

    override val parameters: Map<String, ToolParameter> = emptyMap()

    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)

    override val permissionPolicy = ToolPermissionPolicy.ASK

    override fun toJsonSchema(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return (jsonElementToAny(tool.inputSchema) as? Map<String, Any>)
            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        if (!isStillAuthorized()) {
            return ToolResult.Error(
                context.getString(R.string.plugin_tool_not_authorized),
                "PLUGIN_NOT_AUTHORIZED"
            )
        }
        return try {
            val result = runtime.invoke(
                pluginId = pluginId,
                version = pluginVersion,
                entryFile = tool.runtimeEntry,
                handler = tool.handler,
                args = JsonObject(args)
            )
            ToolResult.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginRuntimeException) {
            FileLogger.w(TAG, "插件工具执行失败: $pluginId/${tool.localId} (${e.code})")
            ToolResult.Error(e.message ?: context.getString(R.string.plugin_runtime_failed), e.code)
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件工具执行异常: $pluginId/${tool.localId}", e)
            ToolResult.Error(
                context.getString(R.string.plugin_runtime_failed),
                "PLUGIN_RUNTIME_FAILED"
            )
        }
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val title = tool.title.resolve(Locale.getDefault().toLanguageTag())
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = context.getString(R.string.plugin_tool_permission_title),
            summary = context.getString(R.string.plugin_tool_permission_summary, pluginName, title),
            details = context.getString(
                R.string.plugin_tool_permission_details,
                pluginId,
                title,
                argsPreview
            ),
            argsPreview = argsPreview
        )
    }

    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { (_, value) -> jsonElementToAny(value) }
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }

    private companion object {
        const val TAG = "PluginTool"

        fun buildNamespacedName(pluginId: String, localId: String): String {
            val raw = "plugin__${sanitize(pluginId)}__${sanitize(localId)}"
            if (raw.length <= 64) return raw
            val suffix = "__${sha1(raw).take(8)}"
            return raw.take(64 - suffix.length).trimEnd('_', '-') + suffix
        }

        fun sanitize(value: String): String = value.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_'
        }.joinToString("")

        fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
