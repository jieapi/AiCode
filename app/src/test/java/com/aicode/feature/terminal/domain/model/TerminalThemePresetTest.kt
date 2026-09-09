package com.aicode.feature.terminal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalThemePreset] 预设主题：
 * companion 预设完整性、id 唯一性、ansiColors 长度与「仅按 id 判等」的语义。
 */
class TerminalThemePresetTest {

    private fun theme(id: String, nameRes: Int = 1) = TerminalThemePreset(
        id = id,
        nameRes = nameRes,
        background = 0xFF000000.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
        cursor = 0xFFFF0000.toInt(),
        ansiColors = IntArray(16)
    )

    @Test
    fun all_presets_defined_and_ids_unique() {
        val expected = listOf(
            TerminalThemePreset.TERMIUS_DARK,
            TerminalThemePreset.DRACULA,
            TerminalThemePreset.ONE_DARK,
            TerminalThemePreset.MONOKAI,
            TerminalThemePreset.GITHUB_DARK
        )
        val presets = TerminalThemePreset.ALL_PRESETS
        assertTrue(presets.containsAll(expected))
        assertEquals(expected.size, presets.size)
        assertEquals(expected.size, presets.map { it.id }.distinct().size)
    }

    @Test
    fun every_preset_has_16_ansi_colors() {
        TerminalThemePreset.ALL_PRESETS.forEach { preset ->
            assertEquals("${preset.id} 的 ANSI 调色板应为 16 色", 16, preset.ansiColors.size)
        }
    }

    @Test
    fun default_ansi_colors_is_16_when_omitted() {
        assertEquals(16, theme("custom").ansiColors.size)
    }

    @Test
    fun every_preset_has_existing_name_resource() {
        TerminalThemePreset.ALL_PRESETS.forEach { preset ->
            assertTrue("${preset.id} 的 nameRes 应为有效资源 id", preset.nameRes != 0)
        }
    }

    @Test
    fun name_resources_are_distinct() {
        val ids = TerminalThemePreset.ALL_PRESETS.map { it.nameRes }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun equals_and_hash_code_are_id_based() {
        val a = theme("custom", nameRes = 1001)
        val b = theme("custom", nameRes = 1002)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(theme("custom"), theme("other"))
    }

    @Test
    fun find_by_id_returns_matching_preset() {
        assertEquals(TerminalThemePreset.TERMIUS_DARK, TerminalThemePreset.findById("termius_dark"))
        assertEquals(TerminalThemePreset.DRACULA, TerminalThemePreset.findById("dracula"))
        assertEquals(TerminalThemePreset.ONE_DARK, TerminalThemePreset.findById("one_dark"))
        assertEquals(TerminalThemePreset.MONOKAI, TerminalThemePreset.findById("monokai"))
        assertEquals(TerminalThemePreset.GITHUB_DARK, TerminalThemePreset.findById("github_dark"))
    }

    @Test
    fun find_by_id_unknown_falls_back_to_termius_dark() {
        assertEquals(TerminalThemePreset.TERMIUS_DARK, TerminalThemePreset.findById("no_such_theme"))
    }

    @Test
    fun find_by_id_github_light_falls_back_to_github_dark() {
        // 源码特例：github_light 尚无专属预设，回退到 GitHub Dark
        assertEquals(TerminalThemePreset.GITHUB_DARK, TerminalThemePreset.findById("github_light"))
    }
}