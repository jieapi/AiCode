package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.model.ChatSession
import compose.icons.FeatherIcons

/**
 * 单条会话行：短按选中，长按弹出功能菜单（置顶/重命名/导出/删除）。供侧边栏历史记录列表复用。
 * 置顶会话显示浅蓝背景（primaryContainer）。
 * [trailing] 用于行尾额外控件（如子代理展开箭头），它自己的点击不应触发整行选中。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSessionRow(
    session: ChatSession,
    selected: Boolean,
    isExecuting: Boolean = false,
    awaitingPermission: Boolean = false,
    pinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pinned) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides 0.dp
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Spacer(Modifier.width(Spacing.sm))
        }
        if (awaitingPermission) {
            // 等待用户授权：橙色常亮圆点，优先级高于执行中的绿色脉冲，提示该会话已挂起待处理。
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.warning)
            )
            Spacer(Modifier.width(Spacing.md))
        } else if (isExecuting) {
            val alpha by rememberChatInfiniteFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
                label = "tool-status-dot-alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.success)
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Text(
            text = session.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}
