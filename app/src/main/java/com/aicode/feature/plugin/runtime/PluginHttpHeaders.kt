package com.aicode.feature.plugin.runtime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * provider 请求头的脱敏与合并。
 *
 * `agent.http.before` 只看到敏感头的占位符；插件原样回传占位符、且请求仍发往原 origin
 * （scheme + host + port 完全一致）时才恢复真实值。URL 一旦跨 origin，原始敏感头必须剥离，
 * 避免真实密钥被转发给第三方端点。
 */
object PluginHttpHeaders {
    /** payload 中的敏感头占位符；插件原样回传表示“沿用真实值”。 */
    const val PLACEHOLDER = "{{Token}}"

    private val EXACT_SENSITIVE = setOf(
        "authorization",
        "x-api-key",
        "x-goog-api-key"
    )

    fun isSensitive(name: String): Boolean = name.lowercase() in EXACT_SENSITIVE

    /** 生成 hook payload 用的 headers：敏感值换成 [PLACEHOLDER]，其余保持原值。 */
    fun redact(headers: Map<String, String>): Map<String, String> {
        val secrets = secretsOf(headers)
        return headers.mapValues { (name, value) ->
            if (isSensitive(name) || containsSecret(value, secrets)) PLACEHOLDER else value
        }
    }

    /**
     * 以 [originalHeaders] 为基底增量应用 [returnedHeaders]，得到最终请求头。
     *
     * - 非敏感头：同名（忽略大小写）覆盖原值，新头追加，未返回的头保持不变。
     * - 敏感头：只有 [finalUrl] 与 [originalUrl] 同 scheme/host/port 且回传值恰为
     *   [PLACEHOLDER] 时才沿用基底中的真实值；其他回传值一律忽略。
     * - 跨 origin 时基底中的敏感头全部剥离。
     */
    fun merge(
        originalUrl: String,
        originalHeaders: Map<String, String>,
        returnedHeaders: Map<String, String>,
        finalUrl: String
    ): Map<String, String> {
        val sameOrigin = isSameOrigin(originalUrl, finalUrl)
        val secrets = secretsOf(originalHeaders)
        val merged = LinkedHashMap<String, String>(originalHeaders.size + returnedHeaders.size)
        originalHeaders.forEach { (name, value) ->
            if (sameOrigin || (!isSensitive(name) && !containsSecret(value, secrets))) {
                merged[name] = value
            }
        }
        returnedHeaders.forEach { (name, value) ->
            if (isSensitive(name)) return@forEach
            val existing = merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            when {
                value == PLACEHOLDER -> Unit
                existing != null -> merged[existing] = value
                else -> merged[name] = value
            }
        }
        return merged
    }

    /** 从真实敏感头中提取值（含 Bearer/Basic 前缀后的裸值），用于保护自定义头里的同一份密钥。 */
    private fun secretsOf(headers: Map<String, String>): Set<String> =
        headers
            .filterKeys { isSensitive(it) }
            .values
            .flatMap { value ->
                val trimmed = value.trim()
                listOf(trimmed, trimmed.substringAfter(' ', "").trim())
            }
            .filter { it.length >= 4 && it != PLACEHOLDER }
            .toSet()

    private fun containsSecret(value: String, secrets: Set<String>): Boolean =
        secrets.any { value.contains(it) }

    private fun isSameOrigin(originalUrl: String, finalUrl: String): Boolean {
        if (originalUrl == finalUrl) return true
        val original = originalUrl.toHttpUrlOrNull() ?: return false
        val final = finalUrl.toHttpUrlOrNull() ?: return false
        return original.scheme == final.scheme &&
            original.host == final.host &&
            original.port == final.port
    }
}
