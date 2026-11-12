package com.aicode.feature.terminal.presentation.component

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.domain.font.TerminalFontManager
import com.aicode.feature.terminal.domain.model.TerminalThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 终端设置底部抽屉面板：配色主题、字号大小、字体与光标样式，提供即时预览。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalSettingsSheet(
    settings: TerminalSettings,
    onDismiss: () -> Unit,
    onSelectTheme: (String) -> Unit,
    onChangeFontSize: (Int) -> Unit,
    onChangeCursorStyle: (Int) -> Unit,
    onChangeFontPath: (String) -> Unit,
    onChangeShowPluginTabs: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 滑块用本地值实时预览，松手（onValueChangeFinished）才落盘并触发一次 PTY resize：
    // 若每格都调 onChangeFontSize，会连续写 DataStore 并给 shell 连发 SIGWINCH。
    var sliderFontSize by remember { mutableFloatStateOf(settings.fontSizeSp.toFloat()) }
    LaunchedEffect(settings.fontSizeSp) { sliderFontSize = settings.fontSizeSp.toFloat() }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.terminal_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(Spacing.md))

            // 实时预览卡片
            TerminalPreviewCard(settings = settings.copy(fontSizeSp = sliderFontSize.roundToInt()))

            Spacer(Modifier.height(Spacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.terminal_show_plugin_tabs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.terminal_show_plugin_tabs_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.showPluginTabs,
                    onCheckedChange = onChangeShowPluginTabs
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            // 配色主题选择
            Text(
                text = stringResource(R.string.terminal_theme_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TerminalThemePreset.ALL_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = preset.id == settings.themeId,
                        onClick = { onSelectTheme(preset.id) },
                        label = { Text(stringResource(preset.nameRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // 字号大小滑块
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.terminal_font_size_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${sliderFontSize.roundToInt()} sp",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = sliderFontSize,
                onValueChange = { sliderFontSize = it },
                onValueChangeFinished = { onChangeFontSize(sliderFontSize.roundToInt()) },
                valueRange = 5f..22f,
                steps = 16,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.md))

            // 字体选择
            TerminalFontSection(
                currentPath = settings.fontPath,
                onChangeFontPath = onChangeFontPath
            )

            Spacer(Modifier.height(Spacing.md))

            // 光标样式选择
            Text(
                text = stringResource(R.string.terminal_cursor_style_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                listOf(
                    0 to stringResource(R.string.terminal_cursor_block),
                    1 to stringResource(R.string.terminal_cursor_underline),
                    2 to stringResource(R.string.terminal_cursor_bar)
                ).forEach { (style, label) ->
                    FilterChip(
                        selected = settings.cursorStyle == style,
                        onClick = { onChangeCursorStyle(style) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

/** 自定义字体选择区：系统等宽 + 已导入字体，支持导入与删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalFontSection(
    currentPath: String,
    onChangeFontPath: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf(TerminalFontManager.listFonts(context)) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) { TerminalFontManager.importFont(context, uri) }
            if (imported == null) {
                Toast.makeText(context, R.string.terminal_font_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                fonts = TerminalFontManager.listFonts(context)
                onChangeFontPath(imported.path)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.terminal_font_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Icon(
                imageVector = Icons.Outlined.FileUpload,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.terminal_font_import))
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        FilterChip(
            selected = currentPath.isBlank(),
            onClick = { onChangeFontPath("") },
            label = { Text(stringResource(R.string.terminal_font_default)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        FilterChip(
            selected = currentPath == TerminalFontManager.BUILTIN_PATH,
            onClick = { onChangeFontPath(TerminalFontManager.BUILTIN_PATH) },
            label = { Text(stringResource(R.string.terminal_font_builtin)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        fonts.forEach { font ->
            FilterChip(
                selected = font.path == currentPath,
                onClick = { onChangeFontPath(font.path) },
                label = { Text(font.displayName) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.terminal_font_delete),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                TerminalFontManager.deleteFont(font.path)
                                fonts = TerminalFontManager.listFonts(context)
                                if (font.path == currentPath) onChangeFontPath(TerminalFontManager.BUILTIN_PATH)
                            }
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/** 把字体路径解析成预览用 FontFamily，路径无效时回落系统等宽字体。 */
@Composable
private fun rememberPreviewFontFamily(path: String): FontFamily = remember(path) {
    if (path.isBlank()) return@remember FontFamily.Monospace
    if (path == TerminalFontManager.BUILTIN_PATH) {
        return@remember FontFamily(Font(R.font.jetbrains_mono_nl))
    }
    val file = File(path)
    if (!file.isFile) return@remember FontFamily.Monospace
    runCatching { FontFamily(Font(file)) }.getOrDefault(FontFamily.Monospace)
}

/** 终端实时预览小窗。 */
@Composable
private fun TerminalPreviewCard(settings: TerminalSettings) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.luminance() < 0.5f
    val bgArgb = colorScheme.background.toArgb()
    val fgArgb = colorScheme.onBackground.toArgb()
    val cursorArgb = colorScheme.primary.toArgb()
    val theme = remember(settings.theme, isDark, bgArgb, fgArgb, cursorArgb) {
        TerminalThemePreset.resolve(
            preset = settings.theme,
            isDark = isDark,
            backgroundArgb = bgArgb,
            foregroundArgb = fgArgb,
            cursorArgb = cursorArgb
        )
    }
    val bgColor = Color(theme.background)
    val fgColor = Color(theme.foreground)
    val cursorColor = Color(theme.cursor)
    val greenColor = Color(theme.ansiColors.getOrElse(2) { theme.foreground })
    val blueColor = Color(theme.ansiColors.getOrElse(4) { theme.foreground })
    val yellowColor = Color(theme.ansiColors.getOrElse(3) { theme.foreground })
    val fontFamily = rememberPreviewFontFamily(settings.fontPath)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = bgColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "root@aicode",
                    color = greenColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = ":",
                    color = fgColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp
                )
                Text(
                    text = "~/workspace",
                    color = blueColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$ ./gradlew build",
                    color = fgColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "> Task :app:assembleDebug ",
                    color = yellowColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp
                )
                Text(
                    text = "SUCCESS",
                    color = greenColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$ git status",
                    color = fgColor,
                    fontFamily = fontFamily,
                    fontSize = settings.fontSizeSp.sp
                )
                Spacer(Modifier.width(2.dp))
                // 模拟光标
                Box(
                    modifier = Modifier
                        .size(
                            width = when (settings.cursorStyle) {
                                1 -> 8.dp
                                2 -> 2.dp
                                else -> 8.dp
                            },
                            height = when (settings.cursorStyle) {
                                1 -> 2.dp
                                2 -> (settings.fontSizeSp + 2).dp
                                else -> (settings.fontSizeSp + 2).dp
                            }
                        )
                        .background(cursorColor)
                )
            }
        }
    }
}
