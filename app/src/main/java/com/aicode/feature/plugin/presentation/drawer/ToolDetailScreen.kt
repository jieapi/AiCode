package com.aicode.feature.plugin.presentation.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.component.AdaptiveCardView
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.presentation.drawer.resolveToolIcon
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.presentation.component.SettingsGroup
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.MoreVertical

/**
 * 界面 ②：工具详情（全屏页面）。
 * 顶部展示工具摘要 Header，核心交互区直接由通用结构化 UI 引擎 [AdaptiveCardView] 绘制插件声明的节点树。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(
    tool: PluginWorkspaceTool,
    savedParams: Map<String, Any> = emptyMap(),
    languageTag: String = "zh-CN",
    onBack: () -> Unit,
    onExecute: (actionId: String, commandTemplate: String?, params: Map<String, Any>) -> Unit
) {
    val formState = remember(savedParams) {
        val map = mutableStateMapOf<String, Any>()
        map.putAll(savedParams)
        map
    }
    val cardRoot = tool.cardRoot ?: AdaptiveCardRoot()

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
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = FeatherIcons.MoreVertical,
                            contentDescription = null,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 顶部摘要卡片（大图标 + 标题 + 详细说明）
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(Radius.mdLarge)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveToolIcon(tool.icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(Spacing.md))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tool.title.resolve(languageTag),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val desc = tool.description.resolve(languageTag)
                        if (desc.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 核心交互与表单区：直接由通用 AdaptiveCardView 绘制
            AdaptiveCardView(
                card = cardRoot,
                isExpanded = true,
                formState = formState,
                onSubmit = { actionId, template, values ->
                    onExecute(actionId, template, values)
                }
            )
        }
    }
}
