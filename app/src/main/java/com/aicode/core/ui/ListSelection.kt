package com.aicode.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.X

/**
 * 列表多选状态。key 由各页面自定义（提供商 id / `作用域:名称` / 提示词编号 / 镜像 id / 会话 id），
 * 这样一套组件能服务所有「可批量操作的字符串键列表」，页面侧只需提供 key 映射。
 *
 * 约定：
 * - key 必须在列表内唯一，且数据刷新后保持稳定（不要用下标）。
 * - 数据刷新后调用 [retainOnly] 剔除已消失的选中项，避免批量操作打到已删除的条目。
 * - 退出多选（[exit]）会清空选中集合，返回键与顶栏开关共用它。
 */
class ListSelectionState {

    /** 是否处于多选模式。false 时列表行为与改造前完全一致（左滑删除、点击进详情）。 */
    var isActive by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<String>()

    val selectedCount: Int get() = _selected.size

    /** 当前选中 key 的快照：批量回调触发时取一次，避免边遍历边被改动。 */
    val selectedKeys: List<String> get() = _selected.toList()

    fun isSelected(key: String): Boolean = key in _selected

    fun toggle(key: String) {
        if (!_selected.remove(key)) _selected.add(key)
    }

    fun enter() {
        isActive = true
    }

    /** 进入多选并默认选中单个条目（如长按某条打开批量选择）。 */
    fun enterWith(key: String) {
        isActive = true
        _selected.clear()
        _selected.add(key)
    }

    fun exit() {
        isActive = false
        _selected.clear()
    }

    fun clear() {
        _selected.clear()
    }

    /** 全选 / 取消全选：整体替换选中集合。 */
    fun replaceAll(keys: Collection<String>) {
        _selected.clear()
        _selected.addAll(keys)
    }

    /** 数据刷新后剔除失效 key（被删掉、被筛掉的条目）。 */
    fun retainOnly(keys: Set<String>) {
        _selected.retainAll(keys)
    }

    companion object {
        val Saver: Saver<ListSelectionState, Any> = listSaver(
            save = { listOf(it.isActive) + it._selected },
            restore = { saved ->
                ListSelectionState().apply {
                    if (saved.firstOrNull() == true) enter()
                    saved.drop(1).filterIsInstance<String>().forEach { _selected.add(it) }
                }
            }
        )
    }
}

/**
 * 创建一份多选状态。设置页各列表的多选状态由 SettingsScreen 统一持有（顶栏按钮在那边），
 * 离开列表页进详情 / 编辑时显式退出，因此这里用 remember 而不做跨配置持久化。
 */
@Composable
fun rememberListSelectionState(): ListSelectionState = remember { ListSelectionState() }

/** 跨配置与组合重建持久化多选状态（供侧边抽屉等会离开组合树的场景使用）。 */
@Composable
fun rememberSaveableListSelectionState(): ListSelectionState =
    rememberSaveable(saver = ListSelectionState.Saver) { ListSelectionState() }

/**
 * 顶部栏右上角的多选开关：激活态带圆形浅底高亮，未激活态为常规图标按钮。
 * 列表为空时调用方不显示它；[enabled] = false 时置灰不可点。
 */
@Composable
fun SelectionToggleAction(selection: ListSelectionState, enabled: Boolean = true) {
    SelectionToggleAction(
        active = selection.isActive,
        onToggle = { if (selection.isActive) selection.exit() else selection.enter() },
        enabled = enabled
    )
}

/** 无状态版多选开关，供自持选区状态的页面（如模型列表）复用一致的顶栏样式。 */
@Composable
fun SelectionToggleAction(
    active: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String = stringResource(R.string.common_select_mode)
) {
    IconButton(
        onClick = onToggle,
        enabled = enabled
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.CheckSquare,
                contentDescription = contentDescription,
                tint = when {
                    active -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onBackground
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 底部悬浮多选操作条：左侧（可选关闭按钮 +）「已选 N 项」，右侧「全选 / 取消全选」胶囊按钮 + 页面批量动作。
 *
 * 与前端 `.aicode-selection-bar` 保持同一套卡片、描边与阴影规范；默认由顶栏开关或系统返回键退出，
 * 无顶栏开关的容器（如侧边抽屉）可通过 [showCloseButton] 开启左侧关闭按钮。
 */
@Composable
fun FloatingSelectionBar(
    visible: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    showCloseButton: Boolean = false,
    selectedCountText: String = stringResource(R.string.common_selected_count, selectedCount),
    selectAllText: String = stringResource(
        if (allSelected) R.string.common_deselect_all else R.string.common_select_all
    ),
    outerPadding: PaddingValues = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md),
    actions: @Composable RowScope.() -> Unit = {}
) {
    BackHandler(enabled = visible) { onExit() }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val shape = RoundedCornerShape(Radius.lg)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(outerPadding)
                .shadow(
                    elevation = 6.dp,
                    shape = shape,
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.36f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.50f)
                )
                .clip(shape)
                .background(MaterialTheme.semanticColors.cardSurface)
                .border(1.dp, MaterialTheme.semanticColors.subtleBorder, shape)
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                if (showCloseButton) {
                    IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = stringResource(R.string.common_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = selectedCountText,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SelectionActionButton(
                    text = selectAllText,
                    onClick = onToggleSelectAll
                )
                actions()
            }
        }
    }
}

/** 底部悬浮条内的紧凑圆角填充按钮（用于「全选 / 取消全选」及常规批量操作）。 */
@Composable
fun SelectionActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    icon: ImageVector? = null
) {
    val shape = RoundedCornerShape(Radius.sm)
    val bgAlpha = if (enabled) 1f else 0.5f
    val contentColor = if (enabled) tint else tint.copy(alpha = 0.45f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bgAlpha),
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder.copy(alpha = bgAlpha)),
        modifier = modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(Spacing.xs))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                maxLines = 1
            )
        }
    }
}

/** 底部悬浮条内的危险文本按钮（无填充底，红色高亮，用于单个危险动作如「删除」）。 */
@Composable
fun SelectionActionDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
        ),
        modifier = modifier.height(36.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            maxLines = 1
        )
    }
}

/**
 * 底部悬浮条上的图标按钮：动作多于两个时（如启用/停用/重连/删除），
 * 避免窄屏上文字过长挤掉计数与全选按钮。
 */
@Composable
fun SelectionActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 批量删除二次确认弹窗。[inProgress] 为 true 时确认按钮转圈并锁定两个按钮，
 * 避免删除中途被关掉导致「看不见到底成没成」。
 */
@Composable
fun BatchDeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    inProgress: Boolean = false,
    progressText: String? = null
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                if (progressText != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !inProgress) {
                if (inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
