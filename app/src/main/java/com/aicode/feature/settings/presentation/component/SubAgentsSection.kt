package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionBar
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SelectionActionIconButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users

/**
 * 子代理二级页：与「技能」一致的折叠分组列表——「当前项目 / 全局」两组各自可折叠，
 * 每行一个子代理（图标 + 名称 + 描述 + 模型标签），左滑删除，点击行进入详情。
 * 新建入口在顶栏的「＋」，启用开关与编辑入口在详情页。
 *
 * 列表走 [LazyColumn] 只组合可见行：子代理数量随项目增长，一次组合全部行会明显掉帧。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：可批量启用 / 停用 / 删除。
 * 全选覆盖两组（含折叠起来的组）。
 */
@Composable
internal fun SubAgentsSection(
    projectName: String?,
    entries: List<SubAgentUiEntry>,
    selection: ListSelectionState,
    deletingInProgress: Boolean,
    onDelete: (SubAgentUiEntry) -> Unit,
    onOpenDetail: (SubAgentUiEntry) -> Unit,
    onBatchDelete: (List<SubAgentUiEntry>) -> Unit,
    onBatchSetEnabled: (List<SubAgentUiEntry>, Boolean) -> Unit
) {
    // 只在数据变化时分组，避免每次重组都产生新 List。
    val (projectAgents, globalAgents, pluginAgents) = remember(entries) {
        Triple(
            entries.filter { it.scope == AgentDefinitionScope.PROJECT },
            entries.filter { it.scope == AgentDefinitionScope.GLOBAL },
            entries.filter { it.scope == AgentDefinitionScope.PLUGIN }
        )
    }

    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 插件子代理只读、不可通过子代理页批量删除，剔除出全选键集
    val allKeys = remember(entries) {
        entries.filter { it.scope != AgentDefinitionScope.PLUGIN }.map { subAgentSelectionKey(it) }
    }
    val keyToEntry = remember(entries) { entries.associateBy { subAgentSelectionKey(it) } }

    // 列表刷新后剔除已消失的选中项；全空了就退出多选。
    LaunchedEffect(allKeys, selection.isActive) {
        selection.retainOnly(allKeys.toSet())
        if (selection.isActive && entries.isEmpty()) selection.exit()
    }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        FeatherIcons.Users,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.subagents_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.subagents_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    var pluginExpanded by rememberSaveable { mutableStateOf(true) }
    val allSelected = selection.selectedCount > 0 && selection.selectedCount == allKeys.size
    val selectedEntries: List<SubAgentUiEntry> = selection.selectedKeys.mapNotNull { keyToEntry[it] }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            val enabled = selection.selectedCount > 0
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = allSelected,
                onToggleSelectAll = {
                    if (allSelected) selection.clear() else selection.replaceAll(allKeys)
                },
                onExit = { selection.exit() }
            ) {
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_enable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selectedEntries, true) }
                )
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_disable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selectedEntries, false) }
                )
                SelectionActionIconButton(
                    icon = FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_batch_delete),
                    enabled = enabled,
                    onClick = { showBatchDeleteConfirm = true },
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // 批量删除期间给一行文字提示：远程模式下逐条删除可能持续数秒，没有反馈会以为没生效。
        if (deletingInProgress) {
            Text(
                text = stringResource(R.string.common_batch_deleting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = Spacing.lg, bottom = Spacing.xs)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "project-header", contentType = "header") {
                CollapsibleGroupHeader(
                    text = if (projectName != null) {
                        stringResource(R.string.perm_current_project, projectName)
                    } else {
                        stringResource(R.string.perm_current_project_none)
                    },
                    expanded = projectExpanded,
                    onToggle = { projectExpanded = !projectExpanded }
                )
            }
            if (projectExpanded) {
                if (projectAgents.isEmpty()) {
                    item(key = "project-empty", contentType = "empty") {
                        SettingsGroup { SubAgentEmptyHint(stringResource(R.string.subagents_no_project)) }
                    }
                } else {
                    itemsIndexed(
                        items = projectAgents,
                        key = { _, entry -> "project:${entry.name}" },
                        contentType = { _, _ -> "agent-row" }
                    ) { index, entry ->
                        LazyGroupItem(index = index, lastIndex = projectAgents.lastIndex) {
                            SubAgentRow(
                                entry = entry,
                                onDelete = { onDelete(entry) },
                                onClick = { onOpenDetail(entry) },
                                selectionMode = selection.isActive,
                                selected = selection.isSelected(subAgentSelectionKey(entry)),
                                onToggleSelect = { selection.toggle(subAgentSelectionKey(entry)) }
                            )
                        }
                    }
                }
            }

            item(key = "global-header", contentType = "header") {
                CollapsibleGroupHeader(
                    text = stringResource(R.string.perm_global),
                    expanded = globalExpanded,
                    onToggle = { globalExpanded = !globalExpanded }
                )
            }
            if (globalExpanded) {
                if (globalAgents.isEmpty()) {
                    item(key = "global-empty", contentType = "empty") {
                        SettingsGroup { SubAgentEmptyHint(stringResource(R.string.subagents_no_global)) }
                    }
                } else {
                    itemsIndexed(
                        items = globalAgents,
                        key = { _, entry -> "global:${entry.name}" },
                        contentType = { _, _ -> "agent-row" }
                    ) { index, entry ->
                        LazyGroupItem(index = index, lastIndex = globalAgents.lastIndex) {
                            SubAgentRow(
                                entry = entry,
                                onDelete = { onDelete(entry) },
                                onClick = { onOpenDetail(entry) },
                                selectionMode = selection.isActive,
                                selected = selection.isSelected(subAgentSelectionKey(entry)),
                                onToggleSelect = { selection.toggle(subAgentSelectionKey(entry)) }
                            )
                        }
                    }
                }
            }

            if (pluginAgents.isNotEmpty()) {
                item(key = "plugin-header", contentType = "header") {
                    CollapsibleGroupHeader(
                        text = stringResource(R.string.subagents_group_plugin),
                        expanded = pluginExpanded,
                        onToggle = { pluginExpanded = !pluginExpanded }
                    )
                }
                if (pluginExpanded) {
                    itemsIndexed(
                        items = pluginAgents,
                        key = { _, entry -> "plugin:${entry.name}" },
                        contentType = { _, _ -> "agent-row" }
                    ) { index, entry ->
                        LazyGroupItem(index = index, lastIndex = pluginAgents.lastIndex) {
                            SubAgentRow(
                                entry = entry,
                                onDelete = {},
                                onClick = { onOpenDetail(entry) },
                                selectionMode = selection.isActive,
                                selected = false,
                                onToggleSelect = {}
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBatchDeleteConfirm) {
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.subagents_batch_delete_title),
            message = stringResource(R.string.subagents_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(selectedEntries)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/** 多选 key：同名子代理可能同时存在于项目层与全局层，必须带作用域前缀。 */
private fun subAgentSelectionKey(entry: SubAgentUiEntry): String = "${entry.scope.name}:${entry.name}"

/** 单个子代理行：图标 + 名称/描述 + 模型标签 + 右箭头；左滑删除，点击行进入详情。 */
@Composable
private fun SubAgentRow(
    entry: SubAgentUiEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val isPlugin = entry.scope == AgentDefinitionScope.PLUGIN
    // 行背景由 SwipeToDeleteRow 提供，这里不再重复画一层。
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        deleteEnabled = !isPlugin,
        selectable = !isPlugin,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Users,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isPlugin) {
                        McpPill(
                            text = stringResource(R.string.plugins_scope_badge),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }
                    entry.model?.let { model ->
                        McpPill(
                            text = model,
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }
                    // 只在禁用时挂标签：默认启用是常态，每行都挂一个反而护不住重点。
                    if (entry.disabled) {
                        McpPill(
                            text = stringResource(R.string.common_disabled),
                            textColor = MaterialTheme.colorScheme.outline,
                            backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    }
                }
                Text(
                    text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
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
        }
    }
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun SubAgentEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
