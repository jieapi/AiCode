package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import com.aicode.feature.plugin.domain.PluginPage

/** 插件页面宿主：设置页、侧栏、顶栏与大屏右栏共用同一套 WebView 与桥接逻辑。 */
@Composable
fun PluginPageHost(
    page: PluginPage,
    languageTag: String,
    pluginsViewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    applySystemBarInsets: Boolean = true,
    context: () -> PluginEntryContext = { PluginEntryContext() }
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        PluginWebPanelScreen(
            page = page,
            onClosePage = onClose,
            bridgeFactory = { post, close ->
                pluginsViewModel.createWebBridge(
                    page = page,
                    pluginVersion = page.pluginVersion,
                    languageTag = languageTag,
                    theme = { if (isDark) "dark" else "light" },
                    onClosePage = close,
                    post = post,
                    pageContext = context
                )
            },
            modifier = Modifier.fillMaxSize(),
            applySystemBarInsets = applySystemBarInsets,
            entryContext = context
        )
    }
}
