package com.aicode.feature.plugin.runtime

import kotlinx.serialization.json.Json
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

/** `agent.http.before` 返回的受限变更；字段为 null 表示不修改。 */
data class HttpRequestHookResult(
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null
)

enum class PermissionReviewDecision { ALLOW, DENY, PASS }

/** `agent.permission.review` 的三态决定；[reason] 只用于展示与日志。 */
data class PermissionReviewHookResult(
    val decision: PermissionReviewDecision,
    val reason: String? = null
)

/**
 * 校验可写 hook 的返回值。只接受白名单字段，字段格式不对就整条丢弃，
 * 由调用方保持原值继续；插件不能借返回值改状态码、图片附件或工具名。
 */
object PluginHookTransform {
    const val MAX_JSON_CHARS = 256 * 1024
    const val MAX_REASON_CHARS = 500
    const val MAX_HTTP_URL_CHARS = 4096
    const val MAX_HTTP_HEADERS = 64
    const val MAX_HTTP_HEADER_VALUE_CHARS = 8192
    const val MAX_HTTP_BODY_BYTES = 4 * 1024 * 1024

    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val FORBIDDEN_HEADERS = setOf(
        "host", "content-length", "transfer-encoding", "connection", "expect", "upgrade"
    )

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

    fun parseHttp(result: JsonElement): HttpRequestHookResult? {
        val obj = result as? JsonObject ?: return null
        val url = obj.stringField("url") ?: run {
            if (obj.containsKey("url")) return null
            null
        }
        if (url != null && url.length > MAX_HTTP_URL_CHARS) return null

        val headers = if (obj.containsKey("headers")) {
            val raw = obj["headers"] as? JsonObject ?: return null
            if (raw.size > MAX_HTTP_HEADERS) return null
            val resultHeaders = LinkedHashMap<String, String>()
            for ((name, value) in raw) {
                if (!HEADER_NAME.matches(name) || name.lowercase() in FORBIDDEN_HEADERS) return null
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (text.length > MAX_HTTP_HEADER_VALUE_CHARS) return null
                if (text.any { it == '\r' || it == '\n' || it.isISOControl() }) return null
                resultHeaders[name] = text
            }
            resultHeaders
        } else {
            null
        }

        val body = obj.stringField("body") ?: run {
            if (obj.containsKey("body")) return null
            null
        }
        if (body != null) {
            if (body.toByteArray(Charsets.UTF_8).size > MAX_HTTP_BODY_BYTES) return null
            if (runCatching { Json.parseToJsonElement(body) }.getOrNull() !is JsonObject) return null
        }

        return HttpRequestHookResult(url = url, headers = headers, body = body)
    }

    fun parseReview(result: JsonElement): PermissionReviewHookResult? {
        val obj = result as? JsonObject ?: return null
        val decisionText = obj.stringField("decision") ?: run {
            if (obj.containsKey("decision")) return null
            null
        }
        val decision = when (decisionText) {
            null, "pass" -> PermissionReviewDecision.PASS
            "allow" -> PermissionReviewDecision.ALLOW
            "deny" -> PermissionReviewDecision.DENY
            else -> return null
        }
        val reason = obj.stringField("reason") ?: run {
            if (obj.containsKey("reason")) return null
            null
        }
        return PermissionReviewHookResult(
            decision = decision,
            reason = reason?.trim()?.take(MAX_REASON_CHARS)?.takeIf { it.isNotEmpty() }
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
