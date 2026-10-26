package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(text: String) = requireNotNull(SemVer.parse(text)) { "解析失败: $text" }

    @Test
    fun parse_acceptsReleaseAndPreRelease() {
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 0, 0, listOf("beta", "1")), v("1.0.0-beta.1"))
        assertEquals("1.0.0", v("1.0.0+build.5").toString())
    }

    @Test
    fun parse_rejectsMalformed() {
        listOf("1", "1.2", "01.2.3", "1.2.3.4", "v1.2.3", "1.2.x", "", "1.0.0-", "1.0.0-01").forEach {
            assertNull("应拒绝: $it", SemVer.parse(it))
        }
    }

    @Test
    fun compare_followsSemverPrecedence() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0"
        ).map(::v)
        for (i in 0 until ordered.size - 1) {
            assertTrue("${ordered[i]} < ${ordered[i + 1]}", ordered[i] < ordered[i + 1])
            assertTrue("${ordered[i + 1]} > ${ordered[i]}", ordered[i + 1] > ordered[i])
        }
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3+meta")))
    }

    private fun range(text: String) = requireNotNull(SemVerRange.parse(text)) { "范围解析失败: $text" }

    @Test
    fun range_caret() {
        val r = range("^1.2.3")
        assertTrue(r.matches(v("1.2.3")))
        assertTrue(r.matches(v("1.9.0")))
        assertFalse(r.matches(v("1.2.2")))
        assertFalse(r.matches(v("2.0.0")))
        assertTrue(range("^0.2.3").matches(v("0.2.9")))
        assertFalse(range("^0.2.3").matches(v("0.3.0")))
        assertTrue(range("^0.0.3").matches(v("0.0.3")))
        assertFalse(range("^0.0.3").matches(v("0.0.4")))
    }

    @Test
    fun range_tildeExactAndComparators() {
        assertTrue(range("~1.2.3").matches(v("1.2.9")))
        assertFalse(range("~1.2.3").matches(v("1.3.0")))
        assertTrue(range("1.0.0").matches(v("1.0.0")))
        assertFalse(range("=1.0.0").matches(v("1.0.1")))
        val between = range(">=1.0.0 <2.0.0")
        assertTrue(between.matches(v("1.5.0")))
        assertFalse(between.matches(v("2.0.0")))
        assertFalse(range(">1.0.0").matches(v("1.0.0")))
        assertTrue(range("<=1.0.0").matches(v("1.0.0")))
    }

    @Test
    fun range_rejectsUnsupportedSyntax() {
        listOf("", "1.x", "*", "^1", "~1.2", "1.0.0 || 2.0.0", "1.0.0 - 2.0.0", ">=", "latest").forEach {
            assertNull("应拒绝: $it", SemVerRange.parse(it))
        }
    }

    @Test
    fun localizedText_resolveOrder() {
        val text = LocalizedText(
            mapOf("default" to "fallback", "en" to "English", "zh" to "中文", "zh-tw" to "繁體")
        )
        assertEquals("繁體", text.resolve("zh_TW"))
        assertEquals("中文", text.resolve("zh-CN"))
        assertEquals("English", text.resolve("en-US"))
        assertEquals("fallback", text.resolve("fr"))
        assertEquals("only", LocalizedText(mapOf("ja" to "only")).resolve("fr"))
        assertNotNull(LocalizedText.single("x").resolve("zh"))
    }
}
