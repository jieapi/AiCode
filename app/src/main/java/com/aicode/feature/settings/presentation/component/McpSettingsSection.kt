package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.FloatingSelectionBar
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SelectionActionIconButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import com.aicode.R

/**
 * MCP 二级页：与提供商/默认模型一致的 iOS 分组列表。
 * 白色分组卡片内每台 server 一行，两行布局（名称+状态 / 类型+摘要），支持左滑删除。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：可批量启用 / 停用 / 重连 / 删除。
 * 多选模式下全选只作用于**当前搜索筛选结果**，与列表里看到的条目一致。
 */
@Composable
internal fun McpSection(
    entries: List<McpServerEntry>,
    statuses: List<McpServerStatus>,
    reloading: Boolean,
    selection: ListSelectionState,
    onReload: () -> Unit,
    onToggle: (String, Boolean, McpScope) -> Unit,
    onEdit: (McpServerEntry) -> Unit,
    onDelete: (String, McpScope) -> Unit,
    onBatchDelete: (List<Pair<String, McpScope>>) -> Unit,
    onBatchSetEnabled: (List<Pair<String, McpScope>>, Boolean) -> Unit,
    onBatchReconnect: (List<String>) -> Unit
) {
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        LaunchedEffect(selection.isActive) {
            if (selection.isActive) selection.exit()
        }
        SettingsEmptyState(
            icon = FeatherIcons.Terminal,
            title = stringResource(R.string.mcp_empty),
            hint = stringResource(R.string.mcp_empty_hint),
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp)
        )
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { entry ->
            entry.server.name.contains(searchQuery, ignoreCase = true) ||
                entry.server.url?.contains(searchQuery, ignoreCase = true) == true ||
                entry.server.command?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    // 状态按名字建索引：长列表里每行 firstOrNull 扫一遍 statuses 是 O(n*m)，搜索时更明显。
    val statusByName = remember(statuses) { statuses.associateBy { it.name } }

    val visibleKeys = remember(filteredEntries) {
        filteredEntries.map { mcpSelectionKey(it.server.name, it.scope) }
    }
    // key → (名称, 作用域)，批量操作时把选中集合还原成目标列表；搜索筛掉的条目自然不在表里。
    val keyToTarget = remember(filteredEntries) {
        filteredEntries.associate {
            mcpSelectionKey(it.server.name, it.scope) to (it.server.name to it.scope)
        }
    }
    // 搜索条件变化后，被筛掉的条目要从选中集合里剔除；否则批量删除会误伤看不见的 server。
    LaunchedEffect(visibleKeys) { selection.retainOnly(visibleKeys.toSet()) }
    val allSelected = selection.selectedCount > 0 && selection.selectedCount == visibleKeys.size

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                bottom = if (selection.isActive) 88.dp else Spacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (entries.size >= 3) {
                item(key = "search", contentType = "search") {
                    ModelSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.mcp_search_placeholder),
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }

            if (filteredEntries.isEmpty()) {
                item(key = "search-empty", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                FeatherIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = stringResource(R.string.mcp_search_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredEntries,
                    key = { _, entry -> "${entry.scope}:${entry.server.name}" },
                    contentType = { _, _ -> "mcp-row" }
                ) { index, entry ->
                    LazyGroupItem(index = index, lastIndex = filteredEntries.lastIndex) {
                        McpServerRow(
                            server = entry.server,
                            scope = entry.scope,
                            status = statusByName[entry.server.name],
                            onClick = { onEdit(entry) },
                            onDelete = { onDelete(entry.server.name, entry.scope) },
                            selectionMode = selection.isActive,
                            selected = selection.isSelected(mcpSelectionKey(entry.server.name, entry.scope)),
                            onToggleSelect = { selection.toggle(mcpSelectionKey(entry.server.name, entry.scope)) }
                        )
                    }
                }
            }
        }

        FloatingSelectionBar(
            visible = selection.isActive,
            selectedCount = selection.selectedCount,
            allSelected = allSelected,
            onToggleSelectAll = {
                if (allSelected) selection.clear() else selection.replaceAll(visibleKeys)
            },
            onExit = { selection.exit() },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val enabled = selection.selectedCount > 0
            val targets = selection.selectedKeys.mapNotNull { keyToTarget[it] }
            SelectionActionButton(
                text = stringResource(R.string.common_batch_enable),
                enabled = enabled,
                tint = MaterialTheme.colorScheme.primary,
                onClick = { onBatchSetEnabled(targets, true) }
            )
            SelectionActionButton(
                text = stringResource(R.string.common_batch_disable),
                enabled = enabled,
                onClick = { onBatchSetEnabled(targets, false) }
            )
            SelectionActionIconButton(
                icon = FeatherIcons.RefreshCw,
                contentDescription = stringResource(R.string.common_batch_reconnect),
                enabled = enabled,
                onClick = { onBatchReconnect(targets.map { it.first }) }
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

    if (showBatchDeleteConfirm) {
        val targets = selection.selectedKeys.mapNotNull { keyToTarget[it] }
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.mcp_batch_delete_title),
            message = stringResource(R.string.mcp_batch_delete_confirm, targets.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(targets)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/** 多选 key：同名 server 可能在不同作用域并存（项目层覆盖全局层），所以必须带作用域前缀。 */
private fun mcpSelectionKey(name: String, scope: McpScope): String = "${scope.name}:$name"

/**
 * 单个 MCP server 行：分组内白底行，图标 + 名称/状态 + 类型/摘要 + 右箭头，左滑删除。
 */
@Composable
internal fun McpServerRow(
    server: McpServerConfig,
    scope: McpScope,
    status: McpServerStatus?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val isConnected = server.enabled && status?.state == McpServerStatus.State.CONNECTED
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val statusText = when {
        !server.enabled -> stringResource(R.string.mcp_disabled)
        status == null -> stringResource(R.string.mcp_not_connected)
        else -> when (status.state) {
            McpServerStatus.State.CONNECTED -> stringResource(R.string.mcp_connected)
            McpServerStatus.State.CONNECTING -> stringResource(R.string.mcp_connecting)
            McpServerStatus.State.NEEDS_AUTH -> stringResource(R.string.mcp_needs_auth)
            McpServerStatus.State.FAILED -> stringResource(R.string.mcp_connection_failed)
            McpServerStatus.State.DISABLED -> stringResource(R.string.mcp_disabled)
        }
    }

    val statusColor = when {
        !server.enabled || status == null || status.state == McpServerStatus.State.DISABLED ->
            MaterialTheme.colorScheme.outline
        status.state == McpServerStatus.State.CONNECTED ->
            MaterialTheme.colorScheme.tertiary
        status.state == McpServerStatus.State.CONNECTING ->
            MaterialTheme.colorScheme.primary
        status.state == McpServerStatus.State.NEEDS_AUTH ->
            MaterialTheme.colorScheme.primary
        else ->
            MaterialTheme.colorScheme.error
    }

    val statusBgColor = statusColor.copy(alpha = 0.12f)

    val typeText = if (server.isStdio) stringResource(R.string.mcp_type_stdio) else "HTTP"
    // HTTP 未连接时不再展示完整地址：URL 很长，在列表里只能看到一截断的头，占位而无信息量。
    val infoText = when {
        isConnected -> stringResource(R.string.mcp_tools_count, status?.toolCount ?: 0)
        server.isStdio -> server.command.orEmpty().ifEmpty { "stdio" }
        else -> null
    }

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧容器图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = FeatherIcons.Box,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 类型 + 摘要
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McpPill(
                        text = if (scope == McpScope.PROJECT) stringResource(R.string.mcp_scope_project) else stringResource(R.string.mcp_scope_global),
                        textColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = if (scope == McpScope.PROJECT) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    McpPill(
                        text = typeText,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                    if (infoText != null) {
                        McpPill(
                            text = infoText,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 状态 pill 与右箭头垂直居中，与整行中心对齐
            McpPill(
                text = statusText,
                textColor = statusColor,
                backgroundColor = statusBgColor
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.mcp_details),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 紧凑 pill 标签：胶囊背景 + 小字，用于状态/类型/摘要。 */
@Composable
internal fun McpPill(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}