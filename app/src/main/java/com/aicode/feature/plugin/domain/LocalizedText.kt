package com.aicode.feature.plugin.domain

/**
 * 面向使用者展示的插件文本：清单里写成单一字符串（对所有语言生效），
 * 或语言标签到文本的映射（可含 `default` 键）。
 *
 * 取值顺序：完整标签（`zh-CN`）→ 语言部分（`zh`）→ `default` → `en` → 第一项。
 */
data class LocalizedText(val values: Map<String, String>) {

    init {
        require(values.isNotEmpty()) { "LocalizedText 至少需要一项文本" }
    }

    fun resolve(languageTag: String): String {
        val tag = normalize(languageTag)
        val language = tag.substringBefore('-')
        return values[tag]
            ?: values[language]
            ?: values[DEFAULT_KEY]
            ?: values["en"]
            ?: values.values.first()
    }

    companion object {
        const val DEFAULT_KEY = "default"

        fun single(text: String) = LocalizedText(mapOf(DEFAULT_KEY to text))

        /** 标签统一为小写、`-` 分隔，`zh_CN` 与 `zh-cn` 视为同一个。 */
        fun normalize(tag: String): String = tag.trim().lowercase().replace('_', '-')
    }
}
