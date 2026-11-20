package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.DEFAULT_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.ProviderKey

/**
 * 多 Key 列表与持久化存储串互转。
 *
 * 每行一条，新格式 `值|权重|覆盖地址`；兼容旧格式 `值|权重` 与裸值（早期版本），
 * 因此不能简单按 `|` 切三段——值本身可能含 `|`。识别规则：
 * - 覆盖地址：最后一段以 `http://` 或 `https://` 开头（且至少三段）；
 * - 权重：剥掉地址后的最后一段能解析为整数，且不是唯一一段。
 */
internal fun decodeProviderKeys(raw: String): List<ProviderKey> =
    raw.split("\n").mapNotNull { decodeProviderKeyLine(it.trim()) }

/** 单条 Key 的持久化形式：覆盖地址留空时不写第三段，保持与旧版存储串一致。 */
internal fun encodeProviderKeys(keys: List<ProviderKey>): String =
    keys.joinToString("\n") { key ->
        buildString {
            append(key.value).append('|').append(key.weight)
            if (key.baseUrl.isNotBlank()) append('|').append(key.baseUrl.trim())
        }
    }

private fun decodeProviderKeyLine(line: String): ProviderKey? {
    if (line.isEmpty()) return null
    val parts = line.split('|').toMutableList()
    var override = ""
    val tail = parts.last()
    if (parts.size >= 3 && (tail.startsWith("http://", true) || tail.startsWith("https://", true))) {
        override = parts.removeAt(parts.lastIndex).trim()
    }
    val weight = parts.lastOrNull()?.trim()?.toIntOrNull()
    if (weight != null && parts.size > 1) parts.removeAt(parts.lastIndex)
    val value = parts.joinToString("|").trim()
    if (value.isEmpty()) return null
    return ProviderKey(value, (weight ?: DEFAULT_KEY_WEIGHT).coerceIn(0, MAX_KEY_WEIGHT), override)
}
