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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.workspace.domain.ProjectToolItem
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Code
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Trash2

/**
 * 界面 ①：右侧抽屉（当前项目已启用的工具列表与快捷操作）。
 */
@Composable
fun ProjectToolsDrawer(
    workspacePath: String,
    tools: List<ProjectToolItem>,
    pluginToolsMap: Map<String, PluginWorkspaceTool>,
    languageTag: String = "zh-CN",
    listState: LazyListState = rememberLazyListState(),
    onOpenToolDetail: (PluginWorkspaceTool, ProjectToolItem?) -> Unit,
    onQuickRun: (PluginWorkspaceTool, ProjectToolItem) -> Unit,
    onOpenAllTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = Spacing.md)
        ) {
            // 顶部卡片：当前项目路径与设置按钮
            ProjectHeaderCard(
                workspacePath = workspacePath,
                onOpenSettings = onOpenAllTools
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            if (tools.isEmpty()) {
                EmptyToolsView(onAddTools = onOpenAllTools)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = tools, key = { it.toolKey }) { item ->
                        val pluginTool = pluginToolsMap[item.toolKey]
                        ToolListItemRow(
                            item = item,
                            onClick = {
                                if (pluginTool != null) {
                                    onOpenToolDetail(pluginTool, item)
                                }
                            },
                            onRun = {
                                if (pluginTool != null) {
                                    onQuickRun(pluginTool, item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 顶部当前项目卡片 */
@Composable
private fun ProjectHeaderCard(
    workspacePath: String,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plugins_current_project),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = workspacePath.ifBlank { "/workspace" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = FeatherIcons.Settings,
                    contentDescription = "全部配置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 单个工具项行 */
@Composable
private fun ToolListItemRow(
    item: ProjectToolItem,
    onClick: () -> Unit,
    onRun: () -> Unit
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
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconColor = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = iconColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = resolveToolIcon(item.icon),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.xs))

            // 右侧「运行」快捷胶囊按钮
            Button(
                onClick = onRun,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = stringResource(R.string.plugins_quick_run),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 空状态 */
@Composable
private fun EmptyToolsView(
    onAddTools: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.plugins_no_project_tools),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onAddTools,
                shape = RoundedCornerShape(Radius.mdLarge),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.plugins_add_tool))
            }
        }
    }
}

fun resolveToolIcon(name: String): ImageVector = when (name.lowercase()) {
    "play", "run" -> FeatherIcons.Play
    "hammer", "build" -> FeatherIcons.Tool
    "trash", "clean" -> FeatherIcons.Trash2
    "android", "phone" -> FeatherIcons.Smartphone
    "terminal", "shell" -> FeatherIcons.Terminal
    "code", "format" -> FeatherIcons.Code
    "flask", "test" -> FeatherIcons.Activity
    else -> FeatherIcons.Play
}
