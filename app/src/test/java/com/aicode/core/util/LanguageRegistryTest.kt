package com.aicode.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LanguageRegistry] 支持语言清单与 tag 判定。 */
class LanguageRegistryTest {

    @Test
    fun zh_and_en_are_supported() {
        assertTrue(LanguageRegistry.isSupported("zh"))
        assertTrue(LanguageRegistry.isSupported("en"))
    }

    @Test
    fun follow_system_placeholder_is_not_supported() {
        assertFalse(LanguageRegistry.isSupported(LanguageRegistry.FOLLOW_SYSTEM))
        assertFalse(LanguageRegistry.isSupported(""))
    }

    @Test
    fun unsupported_tags_are_rejected() {
        assertFalse(LanguageRegistry.isSupported("fr"))
        assertFalse(LanguageRegistry.isSupported("ja"))
        assertFalse(LanguageRegistry.isSupported("ZH"))
        assertFalse(LanguageRegistry.isSupported("en-US"))
        assertFalse(LanguageRegistry.isSupported("zh-CN"))
    }

    @Test
    fun languages_contain_zh_and_en() {
        val tags = LanguageRegistry.languages.map { it.tag }
        assertTrue(tags.containsAll(listOf("zh", "en")))
    }

    @Test
    fun display_names_are_native() {
        assertTrue(LanguageRegistry.languages.any { it.tag == "zh" && it.displayName == "中文" })
        assertTrue(LanguageRegistry.languages.any { it.tag == "en" && it.displayName == "English" })
    }
}