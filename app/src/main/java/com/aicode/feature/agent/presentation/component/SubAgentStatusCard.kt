package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AIAgentViewModel.SubAgentStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.StopCircle
import compose.icons.feathericons.XCircle

/**
 * 子代理状态卡片：展示子代理的实时状态（正在执行的工具、流式输出、进度）。
 * 在侧边栏子代理列表项下方显示，提供停止和查看详情操作。
 */
@Composable
fun SubAgentStatusCard(
    status: SubAgentStatus?,
    onStop: () -> Unit,
    onViewDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status == null) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.md, bottom = Spacing.sm),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
        ) {
            // 状态行：状态指示器 + 标题 + 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态指示器
                    StatusIndicator(status.state, status.isRunning)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = status.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Row {
                    // 停止按钮（仅运行中显示）
                    if (status.isRunning) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.StopCircle,
                                contentDescription = stringResource(R.string.subagent_stop),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    // 查看详情按钮
                    IconButton(
                        onClick = onViewDetail,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.ChevronRight,
                            contentDescription = stringResource(R.string.subagent_view_detail),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 当前工具（如果有）
            if (status.currentTool != null && status.isRunning) {
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(Radius.sm)
                        )
                        .padding(horizontal = Spacing.sm, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.Activity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = status.currentTool,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 实时输出（如果有）
            if (status.toolProgress.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = status.toolProgress.takeLast(500), // 只显示最后500字符
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.sm)
                    )
                }
            }
        }
    }
}

/** 状态指示器：根据状态显示不同颜色和图标 */
@Composable
private fun StatusIndicator(state: AgentUIState, isRunning: Boolean) {
    val (color, icon) = when {
        isRunning || state is AgentUIState.Loading || state is AgentUIState.Streaming ->
            MaterialTheme.colorScheme.primary to FeatherIcons.Activity
        state is AgentUIState.Error ->
            MaterialTheme.colorScheme.error to FeatherIcons.XCircle
        state is AgentUIState.Result ->
            Color(0xFF4CAF50) to FeatherIcons.CheckCircle // 绿色表示成功
        else ->
            MaterialTheme.colorScheme.onSurfaceVariant to FeatherIcons.Activity
    }

    Icon(
        imageVector = icon,
        contentDescription = when {
            isRunning -> stringResource(R.string.subagent_status_running)
            state is AgentUIState.Error -> stringResource(R.string.subagent_status_failed)
            state is AgentUIState.Result -> stringResource(R.string.subagent_status_completed)
            else -> stringResource(R.string.subagent_status_idle)
        },
        tint = color,
        modifier = Modifier.size(16.dp)
    )
}
