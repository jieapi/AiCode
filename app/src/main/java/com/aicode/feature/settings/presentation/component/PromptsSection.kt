package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.FloatingSelectionBar
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionDangerButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.settings.presentation.PromptsUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 编辑目标：区分「新建」与「编辑既有编号」。
 *
 * - [number] = null：新建一条编号片段
 * - 非 null：编辑该编号最终生效的片段
 */
internal data class PromptEditTarget(val number: Int? = null)

/**
 * 提示词页：顶部是「完全禁用内置提示词」开关与帮助入口，下面是按编号列出的生效片段。
 *
 * 每行一张卡片（图标 + 编号·名称 + 摘要 + 来源徽章），长按行内容可拖拽调整顺序，
 * 点击进入详情预览（右上角再进编辑），来源可写时左滑删除。
 *
 * 顶栏右上角「多选」进入批量模式（[selection] 由 SettingsScreen 持有）：只有可编辑（非内置来源）的片段能勾选，
 * 批量删除后对应编号回落到下一层来源。
 */
@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    selection: ListSelectionState,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit,
    onToggleBuiltinDisabled: (Boolean) -> Unit,
    onBatchDeleteFragments: (List<Int>) -> Unit
) {
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 内置来源的片段删不了，也就不参与多选：勾选框置灰、全选也不会选中它们。
    val selectableKeys = remember(state.fragments) {
        state.fragments.filter { it.editable }.map { it.number.toString() }
    }

    // 删除 / 重置后列表变化：剔除已消失的编号，全空了就退出多选。
    LaunchedEffect(selectableKeys, selection.isActive) {
        selection.retainOnly(selectableKeys.toSet())
        if (selection.isActive && selectableKeys.isEmpty()) selection.exit()
    }

    val allSelected = selection.selectedCount > 0 && selection.selectedCount == selectableKeys.size

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsGroup(
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm)
            ) {
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.prompts_disable_builtin),
                    subtitle = stringResource(R.string.prompts_disable_builtin_hint),
                    trailing = {
                        AppSwitch(checked = state.builtinDisabled, onCheckedChange = onToggleBuiltinDisabled)
                    }
                )
            }

            if (state.fragments.isEmpty()) {
                Text(
                    text = stringResource(R.string.prompts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            } else {
                PromptList(
                    fragments = state.fragments,
                    selection = selection,
                    onOpenFragment = onOpenFragment,
                    onDeleteFragment = onDeleteFragment,
                    onReorder = onReorder,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        FloatingSelectionBar(
            visible = selection.isActive,
            selectedCount = selection.selectedCount,
            allSelected = allSelected,
            onToggleSelectAll = {
                if (allSelected) selection.clear() else selection.replaceAll(selectableKeys)
            },
            onExit = { selection.exit() },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionActionDangerButton(
                text = stringResource(R.string.common_batch_delete),
                enabled = selection.selectedCount > 0,
                onClick = { showBatchDeleteConfirm = true }
            )
        }
    }

    if (showBatchDeleteConfirm) {
        val numbers = selection.selectedKeys.mapNotNull { it.toIntOrNull() }
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.prompts_batch_delete_title),
            message = stringResource(R.string.prompts_batch_delete_confirm, numbers.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDeleteFragments(numbers)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }
}

/**
 * 片段列表。拖拽时只更新本地顺序（不落盘），松手才把最终顺序交给 [onReorder] 持久化，
 * 避免拖动过程中反复写盘 + 重读导致列表抽搐。
 */
@Composable
private fun PromptList(
    fragments: List<PromptFragment>,
    selection: ListSelectionState,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit,
    modifier: Modifier = Modifier
) {
    var localFragments by remember { mutableStateOf(fragments) }
    LaunchedEffect(fragments) { localFragments = fragments }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localFragments = localFragments.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val hapticFeedback = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(
            top = Spacing.xs,
            bottom = if (selection.isActive) 88.dp else Spacing.xl
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = localFragments,
            key = { index, fragment ->
                "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            }
        ) { index, fragment ->
            val rowKey = "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            ReorderableItem(state = reorderableState, key = rowKey) { isDragging ->
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 0.97f else 1f,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragElevation"
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
                    PromptRow(
                        fragment = fragment,
                        onClick = { onOpenFragment(fragment) },
                        onDelete = { onDeleteFragment(fragment.number) },
                        deleteEnabled = fragment.editable,
                        selectionMode = selection.isActive,
                        selected = selection.isSelected(fragment.number.toString()),
                        onToggleSelect = { selection.toggle(fragment.number.toString()) },
                        dragModifier = if (selection.isActive) {
                            Modifier
                        } else {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    // 立即重编号（槽化），与落盘后的编号一致，避免 key 变化引起列表跳动
                                    val numbers = localFragments.map { it.number }.sorted()
                                    val renumbered = localFragments.mapIndexed { index, item ->
                                        item.copy(number = numbers[index])
                                    }
                                    localFragments = renumbered
                                    onReorder(renumbered)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

/** 单条片段行：图标 + 编号·名称 + 摘要 + 来源徽章 + 箭头；长按内容区拖拽排序，左滑删除。 */
@Composable
private fun PromptRow(
    fragment: PromptFragment,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    dragModifier: Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val sortDescription = stringResource(R.string.prompts_sort_long_press)
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics { contentDescription = sortDescription },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "%02d · %s".format(fragment.number, fragment.title),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (fragment.description.isNotBlank()) {
                        Text(
                            text = fragment.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))
            SourceBadge(fragment.source)
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        deleteEnabled = deleteEnabled,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect,
        selectable = deleteEnabled
    ) { row() }
}

/** 来源徽章：一眼看出该编号的内容来自哪一层。 */
@Composable
private fun SourceBadge(source: PromptFragmentSource) {
    val color = when (source) {
        PromptFragmentSource.PROJECT -> MaterialTheme.colorScheme.primary
        PromptFragmentSource.GLOBAL -> MaterialTheme.colorScheme.tertiary
        PromptFragmentSource.LOCAL -> MaterialTheme.colorScheme.secondary
        PromptFragmentSource.BUILTIN -> MaterialTheme.semanticColors.subtleText
    }
    Text(
        text = stringResource(
            when (source) {
                PromptFragmentSource.PROJECT -> R.string.prompts_source_project
                PromptFragmentSource.GLOBAL -> R.string.prompts_source_global
                PromptFragmentSource.LOCAL -> R.string.prompts_source_local
                PromptFragmentSource.BUILTIN -> R.string.prompts_source_builtin
            }
        ),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
