package com.aicode.feature.plugin.presentation.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.presentation.drawer.resolveToolIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Search

/**
 * 界面 ③：全部配置 / 工具库（展示所有可用插件提供的工具，支持添加到当前项目）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllToolsScreen(
    availableTools: List<PluginWorkspaceTool>,
    enabledToolKeys: Set<String>,
    languageTag: String = "zh-CN",
    onBack: () -> Unit,
    onAddTool: (PluginWorkspaceTool) -> Unit,
    onViewDetail: (PluginWorkspaceTool) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredTools = remember(availableTools, searchQuery) {
        if (searchQuery.isBlank()) availableTools
        else availableTools.filter {
            it.title.resolve(languageTag).contains(searchQuery, ignoreCase = true) ||
                it.description.resolve(languageTag).contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.plugins_all_tools),
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
                .padding(horizontal = Spacing.lg)
        ) {
            // 搜索栏
            AppTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                placeholder = stringResource(R.string.plugins_search_tools),
                leadingIcon = {
                    Icon(
                        imageVector = FeatherIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            if (filteredTools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.plugins_no_available_tools),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = filteredTools, key = { it.key }) { tool ->
                        val isAdded = tool.key in enabledToolKeys
                        AllToolItemRow(
                            tool = tool,
                            isAdded = isAdded,
                            languageTag = languageTag,
                            onAdd = { onAddTool(tool) },
                            onClick = { onViewDetail(tool) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllToolItemRow(
    tool: PluginWorkspaceTool,
    isAdded: Boolean,
    languageTag: String,
    onAdd: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 类别/图标
            val iconColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = iconColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = resolveToolIcon(tool.icon),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.title.resolve(languageTag),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${tool.category} · ${tool.description.resolve(languageTag)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(Spacing.xs))

            Button(
                onClick = onAdd,
                enabled = !isAdded,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAdded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                    contentColor = if (isAdded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                if (isAdded) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.plugins_tool_added),
                        style = MaterialTheme.typography.labelMedium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.plugins_tool_add),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
