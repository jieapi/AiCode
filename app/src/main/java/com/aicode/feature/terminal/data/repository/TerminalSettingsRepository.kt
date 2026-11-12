package com.aicode.feature.terminal.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.terminal.domain.font.TerminalFontManager
import com.aicode.feature.terminal.domain.model.TerminalThemePreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalDataStore by preferencesDataStore(
    name = "terminal_settings_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 终端配置模型。 */
data class TerminalSettings(
    val themeId: String = TerminalThemePreset.FOLLOW_APP.id,
    val fontSizeSp: Int = 12,
    val cursorStyle: Int = 0, // 0=Block, 1=Underline, 2=Bar
    /** 字体标识：空=系统等宽，TerminalFontManager.BUILTIN_PATH=内置 JetBrains Mono NL，其余为导入字体的绝对路径。 */
    val fontPath: String = TerminalFontManager.BUILTIN_PATH,
    /** 是否在终端标签栏显示插件创建的命令标签；默认隐藏。 */
    val showPluginTabs: Boolean = false
) {
    val theme: TerminalThemePreset
        get() = TerminalThemePreset.findById(themeId)
}

/** 终端个性化配置持久化仓库（DataStore）。 */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_ID_KEY = stringPreferencesKey("terminal_theme_id")
        val FONT_SIZE_SP_KEY = intPreferencesKey("terminal_font_size_sp")
        val CURSOR_STYLE_KEY = intPreferencesKey("terminal_cursor_style")
        val FONT_PATH_KEY = stringPreferencesKey("terminal_font_path")
        val SHOW_PLUGIN_TABS_KEY = booleanPreferencesKey("terminal_show_plugin_tabs")
    }

    val settingsFlow: Flow<TerminalSettings> = context.terminalDataStore.data.map { prefs ->
        TerminalSettings(
            themeId = prefs[THEME_ID_KEY] ?: TerminalThemePreset.FOLLOW_APP.id,
            fontSizeSp = prefs[FONT_SIZE_SP_KEY]?.coerceIn(5, 22) ?: 12,
            cursorStyle = prefs[CURSOR_STYLE_KEY] ?: 0,
            // 未设过字体的用户给内置字体；显式选过“系统等宽”（空串）的保持原样；导入字体被删时回落内置
            fontPath = when (val saved = prefs[FONT_PATH_KEY]) {
                null -> TerminalFontManager.BUILTIN_PATH
                "", TerminalFontManager.BUILTIN_PATH -> saved
                else -> if (File(saved).isFile) saved else TerminalFontManager.BUILTIN_PATH
            },
            showPluginTabs = prefs[SHOW_PLUGIN_TABS_KEY] ?: false
        )
    }

    suspend fun setThemeId(themeId: String) {
        context.terminalDataStore.edit { it[THEME_ID_KEY] = themeId }
    }

    suspend fun setFontSizeSp(sizeSp: Int) {
        context.terminalDataStore.edit { it[FONT_SIZE_SP_KEY] = sizeSp.coerceIn(5, 22) }
    }

    suspend fun setCursorStyle(style: Int) {
        context.terminalDataStore.edit { it[CURSOR_STYLE_KEY] = style.coerceIn(0, 2) }
    }

    suspend fun setFontPath(path: String) {
        context.terminalDataStore.edit { it[FONT_PATH_KEY] = path }
    }

    suspend fun setShowPluginTabs(enabled: Boolean) {
        context.terminalDataStore.edit { it[SHOW_PLUGIN_TABS_KEY] = enabled }
    }
}
