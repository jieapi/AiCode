package com.aicode.feature.plugin.presentation.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.domain.PluginSettingsPage
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.presentation.PluginWebBridge
import com.aicode.feature.plugin.presentation.PluginWebPanelScreen
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Trash2

/**
 * 界面 ②：工具详情（全屏页面）。
 *
 * 页面统一由插件自己的 HTML 实现，宿主只提供标题栏与返回/移除操作，
 * WebView 容器与设置页、插件配置页共用 [PluginWebPanelScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    tool: PluginWorkspaceTool,
    bridgeFactory: (
        page: PluginSettingsPage,
        post: (String) -> Unit,
        onClosePage: () -> Unit
    ) -> PluginWebBridge,
    languageTag: String = "zh-CN",
    onRemove: () -> Unit = {},
    onBack: () -> Unit
) {
    val page = remember(tool.key, tool.installDir) {
        tool.installDir?.let { dir ->
            PluginSettingsPage(
                pluginId = tool.pluginId,
                localId = tool.localId,
                title = tool.title,
                anchor = "",
                pageFile = tool.pageFile,
                icon = tool.icon,
                installDir = dir,
                pluginVersion = tool.pluginVersion
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = tool.title.resolve(languageTag),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = FeatherIcons.Trash2,
                            contentDescription = stringResource(R.string.plugin_tool_remove),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (page != null) {
            PluginWebPanelScreen(
                page = page,
                onClosePage = onBack,
                bridgeFactory = { post, onClose -> bridgeFactory(page, post, onClose) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                applySystemBarInsets = false
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.plugin_tool_not_found_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 工具不存在（插件停用、卸载或更新后不再提供）时的空态页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolUnavailableScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.plugin_tool_not_found),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(Spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.plugin_tool_not_found_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
