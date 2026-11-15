package com.aicode.feature.plugin.presentation

import androidx.compose.ui.graphics.Color
import com.aicode.core.theme.DarkColorScheme
import com.aicode.core.theme.DarkSemanticColors
import com.aicode.core.theme.LightColorScheme
import com.aicode.core.theme.LightSemanticColors
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginThemeTokensTest {

    @Test
    fun lightTextFields_useAppOutlineAndFocusedSurface() {
        val variables = PluginThemeTokens.variables(LightColorScheme, LightSemanticColors, 1f)

        assertEquals("#E5E5EA", variables["--aicode-text-field-border"])
        assertEquals("#FFFFFF", variables["--aicode-text-field-focused-bg"])
        assertEquals("rgba(229,229,234,0.502)", variables["--aicode-text-field-disabled-border"])
        assertEquals("rgba(15,23,42,0.380)", variables["--aicode-text-field-disabled-text"])
    }

    @Test
    fun darkTextFields_useAppOutlineAlphaAndFocusedSurface() {
        val variables = PluginThemeTokens.variables(DarkColorScheme, DarkSemanticColors, 1f)

        assertEquals("rgba(51,65,85,0.600)", variables["--aicode-text-field-border"])
        assertEquals("rgba(19,39,63,0.800)", variables["--aicode-text-field-focused-bg"])
        assertEquals("rgba(51,65,85,0.302)", variables["--aicode-text-field-disabled-border"])
        assertEquals("rgba(234,242,255,0.380)", variables["--aicode-text-field-disabled-text"])
    }

    @Test
    fun dialogs_followHighSurfaceInBothThemes() {
        val light = PluginThemeTokens.variables(LightColorScheme, LightSemanticColors, 1f)
        val dark = PluginThemeTokens.variables(DarkColorScheme, DarkSemanticColors, 1f)

        assertEquals("#EAF4FF", light["--aicode-dialog-surface"])
        assertEquals("#13273F", dark["--aicode-dialog-surface"])
    }

    @Test
    fun dialogSurface_followsCurrentColorScheme() {
        val scheme = LightColorScheme.copy(surfaceContainerHigh = Color(0xFFABCDEF))
        val variables = PluginThemeTokens.variables(scheme, LightSemanticColors, 1f)

        assertEquals("#ABCDEF", variables["--aicode-dialog-surface"])
    }

    @Test
    fun textFieldTokens_followCurrentColorSchemeAndCardSurface() {
        val scheme = LightColorScheme.copy(
            outlineVariant = Color(0xFF123456),
            onSurface = Color(0xFF654321)
        )
        val semantic = LightSemanticColors.copy(cardSurface = Color(0xFFF1F2F3))
        val variables = PluginThemeTokens.variables(scheme, semantic, 1.5f)

        assertEquals("#123456", variables["--aicode-text-field-border"])
        assertEquals("#F1F2F3", variables["--aicode-text-field-focused-bg"])
        assertEquals("rgba(18,52,86,0.502)", variables["--aicode-text-field-disabled-border"])
        assertEquals("rgba(101,67,33,0.380)", variables["--aicode-text-field-disabled-text"])
        assertEquals("1.50", variables["--aicode-font-scale"])
    }
}
