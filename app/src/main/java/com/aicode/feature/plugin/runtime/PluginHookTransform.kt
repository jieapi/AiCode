package com.aicode.feature.plugin.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `agent.tool.before` 返回的受限变更；[arguments] 为 null 表示不改参数。 */
data class ToolBeforeHookResult(
    val denied: Boolean = false,
    val denyReason: String? = null,
    val arguments: JsonObject? = null
)

/** `agent.tool.after` 返回的受限变更；[data] 为 null 表示不改结果。 */
data class ToolAfterHookResult(val data: JsonElement? = null)

/**
 * 校验可写 hook 的返回值。只接受白名单字段，字段格式不对就整条丢弃，
 * 由调用方保持原值继续；插件不能借返回值改状态码、图片附件或工具名。
 */
object PluginHookTransform {
    const val MAX_JSON_CHARS = 256 * 1024
    const val MAX_REASON_CHARS = 500

    fun parseBefore(result: JsonElement): ToolBeforeHookResult? {
        val obj = result as? JsonObject ?: return null
        val decision = obj.stringField("decision") ?: run {
            if (obj.containsKey("decision")) return null
            null
        }
        if (decision != null && decision != "allow" && decision != "deny") return null
        val reason = obj.stringField("reason") ?: run {
            if (obj.containsKey("reason")) return null
            null
        }
        val arguments = if (obj.containsKey("arguments")) {
            val value = obj["arguments"] as? JsonObject ?: return null
            if (value.toString().length > MAX_JSON_CHARS) return null
            value
        } else {
            null
        }
        return ToolBeforeHookResult(
            denied = decision == "deny",
            denyReason = reason?.trim()?.take(MAX_REASON_CHARS)?.takeIf { it.isNotEmpty() },
            arguments = arguments
        )
    }

    fun parseAfter(result: JsonElement): ToolAfterHookResult? {
        val obj = result as? JsonObject ?: return null
        if (!obj.containsKey("data")) return ToolAfterHookResult()
        val data = obj["data"] ?: return null
        if (data.toString().length > MAX_JSON_CHARS) return null
        return ToolAfterHookResult(data)
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
