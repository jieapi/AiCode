package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionBar
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SelectionActionIconButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import com.aicode.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 提供商二级页：列表 + 空态提示。新增/编辑由顶栏「+」与点击触发 [ProviderEditorScreen]，左滑删除。
 * 每行为一组圆角卡片，长按内容区域可调整提供商顺序（持久化到 sortOrder）。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：勾选后可批量启用 / 停用 / 删除。
 * 多选模式下拖拽排序停用、整行点击改为勾选（与模型页一致）。
 */
@Composable
internal fun ProvidersSection(
    providers: List<AIProviderConfig>,
    selection: ListSelectionState,
    onEdit: (AIProviderConfig) -> Unit,
    onDelete: (AIProviderConfig) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    onBatchDelete: (Set<String>) -> Unit,
    onBatchSetEnabled: (Set<String>, Boolean) -> Unit
) {
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    val allKeys = remember(providers) { providers.filter { it.pluginId == null }.map { it.id } }

    // 列表刷新（删除 / 外部改动）后剔除已消失的选中项；全空了就退出多选，避免顶栏按钮停在选中态。
    LaunchedEffect(allKeys, selection.isActive) {
        selection.retainOnly(allKeys.toSet())
        if (selection.isActive && providers.isEmpty()) selection.exit()
    }

    if (providers.isEmpty()) {
        EmptyHint(stringResource(R.string.providers_empty))
        return
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }
    val hapticFeedback = LocalHapticFeedback.current
    val allSelected = selection.selectedCount > 0 && selection.selectedCount == allKeys.size

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = allSelected,
                onToggleSelectAll = {
                    if (allSelected) selection.clear() else selection.replaceAll(allKeys)
                },
                onExit = { selection.exit() }
            ) {
                val enabled = selection.selectedCount > 0
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_enable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selection.selectedKeys.toSet(), true) }
                )
                SelectionActionButton(
                    text = stringResource(R.string.common_batch_disable),
                    enabled = enabled,
                    onClick = { onBatchSetEnabled(selection.selectedKeys.toSet(), false) }
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
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            contentPadding = PaddingValues(top = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = providers,
                key = { _, provider -> provider.id }
            ) { _, provider ->
                ReorderableItem(
                    state = reorderableState,
                    key = provider.id
                ) { isDragging ->
                    // 拖拽中卡片轻微缩小（对齐 rikkahub 交互）、平滑过渡、阴影提升 + 层级前置，避免盖住相邻卡片
                    val dragScale by animateFloatAsState(
                        targetValue = if (isDragging) 0.95f else 1f,
                        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                        label = "providerDragScale"
                    )
                    val dragElevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                        label = "providerDragElevation"
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.semanticColors.cardSurface,
                        shadowElevation = dragElevation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                scaleX = dragScale
                                scaleY = dragScale
                            }
                    ) {
                        ProviderItem(
                            provider = provider,
                            onEdit = { onEdit(provider) },
                            onDelete = { if (provider.pluginId == null) onDelete(provider) },
                            deleteEnabled = provider.pluginId == null,
                            selectionMode = selection.isActive,
                            selected = selection.isSelected(provider.id),
                            onToggleSelect = { if (provider.pluginId == null) selection.toggle(provider.id) },
                            dragModifier = if (selection.isActive || provider.pluginId != null) {
                                Modifier
                            } else {
                                Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showBatchDeleteConfirm) {
        val targets = selection.selectedKeys.toSet()
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.providers_batch_delete_title),
            message = stringResource(R.string.providers_batch_delete_confirm, targets.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(targets)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/** 居中空态提示。 */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 提供商行：左侧品牌 logo（按提供商名称自动识别），
 * 中部两行（名称 / 类型 + 模型数量 pills），右侧状态 pill + 箭头。
 * 整行点击进入编辑，左滑露出删除按钮；长按左侧信息区域可调整顺序。
 */
@Composable
fun ProviderItem(
    provider: AIProviderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    dragModifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val sortDescription = stringResource(R.string.provider_sort_long_press)
    // 状态色与 MCP 行一致：启用用主题 tertiary（绿调），停用用 outline（灰）。
    val statusColor = if (provider.isEnabled) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.outline

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onEdit,
        deleteEnabled = deleteEnabled,
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics {
                        contentDescription = sortDescription
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧品牌 logo 容器
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    ProviderLogoIcon(
                        provider = provider,
                        size = 22.dp,
                        modifier = Modifier.size(22.dp).align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                // 中间：名称 / 类型 + 模型数量
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
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
                            text = providerTypeLabel(provider.type),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        McpPill(
                            text = stringResource(R.string.provider_models_count_tag, provider.models.size),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (provider.pluginId != null) {
                            McpPill(
                                text = stringResource(R.string.provider_source_plugin),
                                textColor = MaterialTheme.colorScheme.primary,
                                backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 启用/停用状态 pill + 右箭头
            McpPill(
                text = stringResource(if (provider.isEnabled) R.string.common_enabled else R.string.common_disabled),
                textColor = statusColor,
                backgroundColor = statusColor.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
