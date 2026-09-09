package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.ContentWidth
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Database
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.RotateCcw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 落库思考气泡保持展开的窗口（ms）：刚结束的思考不立即折叠回缩，防止高度骤变抽搐。 */
private const val REASONING_FRESH_WINDOW_MS = 5_000L

/** 工具卡片入场时长（ms）与上浮起点：略微下移再淡入到位，只走 draw 层不影响布局。 */
private const val MESSAGE_ENTRY_ANIM_MS = 260
private val MESSAGE_ENTRY_RISE = 10.dp

/**
 * 每轮任务的总耗时（毫秒）：轮末助手消息落库时刻 − 该轮用户消息发出时刻，即用户按下发送
 * 到本轮 AI 收工的挂钟时间（含工具执行与等待用户授权的时间）。返回「消息 id → 耗时」，
 * 只有轮末的那条助手消息才有条目。
 *
 * 轮末判定：其后第一条消息是用户消息，或它就是列表末条且本轮已结束（[lastTurnFinished]，
 * 由 agent 是否空闲给出）。仍在生成中的末条不给耗时，收工落库后自然出现。
 *
 * 上下文压缩插入的锚点/摘要落在轮内（压缩发生在请求前），若参与划分会把轮起点算到压缩
 * 时刻上，故先剔除。
 */
internal fun computeTaskDurations(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, Long> {
    val turnMessages = messages.filter {
        !it.isCompactionMarker && !it.isContextSummary && !it.isCompactionFailure
    }
    if (turnMessages.isEmpty()) return emptyMap()
    val durations = mutableMapOf<String, Long>()
    var turnStart: Long? = null
    turnMessages.forEachIndexed { index, message ->
        when (message.role) {
            MessageRole.USER -> turnStart = message.timestamp
            MessageRole.ASSISTANT -> {
                val start = turnStart ?: return@forEachIndexed
                val isTurnEnd = if (index == turnMessages.lastIndex) {
                    lastTurnFinished
                } else {
                    turnMessages[index + 1].role == MessageRole.USER
                }
                if (isTurnEnd && message.timestamp > start) {
                    durations[message.id] = message.timestamp - start
                }
            }
            MessageRole.TOOL -> Unit
        }
    }
    return durations
}

/** 任务耗时格式化：不足 1 分钟显示 `12s`，不足 1 小时显示 `2:05`，更长显示 `1:02:05`。 */
internal fun formatTaskDuration(millis: Long): String {
    val totalSeconds = ((millis + 500) / 1000).coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return when {
        hours > 0 -> "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
        minutes > 0 -> "$minutes:$paddedSeconds"
        else -> "${seconds}s"
    }
}

/**
 * 单条消息的缓存命中率：命中缓存的输入 / 总输入，与设置页 Token 统计同口径。
 * Anthropic 的 input_tokens 不含 cache_read，该口径会偏大，故封顶 100%。
 * 无输入统计或本次未命中缓存时返回 null（不占位，避免把「渠道不报缓存数据」误示为 0% 命中）。
 */
internal fun formatCacheHitRate(inputTokens: Int, cachedInputTokens: Int): String? {
    if (inputTokens <= 0 || cachedInputTokens <= 0) return null
    val rate = (cachedInputTokens * 100.0 / inputTokens).coerceAtMost(100.0)
    return "${rate.roundToInt()}%"
}

@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onRewindClick: ((String) -> Unit)? = null,
    onMoreClick: ((AgentUIMessage) -> Unit)? = null,
    onToolToggle: (() -> Unit)? = null,
    /** 本轮任务总耗时（ms）：仅轮末助手消息非空，见 [computeTaskDurations]。 */
    taskDurationMs: Long? = null,
    /** 新消息入场动画延迟（ms）：null 表示历史消息直接显示；非 null 时首次组合延迟后淡入展开。 */
    entryDelayMs: Long? = null
) {
    if (message.isCompactionMarker) {
        // 压缩内部锚点不再渲染分隔线：摘要卡片已提供压缩反馈，避免与卡片重复。
        return
    }

    if (message.isContextSummary) {
        CompactionSummaryCard(message, markdownCache)
        return
    }

    if (message.isCompactionFailure) {
        CompactionFailureCard(message)
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }

    // 群聊成员发言：role=USER + senderName（协调器落库）。左对齐渲染为成员气泡（带名字），
    // 区别于用户自己的右对齐气泡。
    val isGroupMember = message.senderName != null
    val isUser = message.role == MessageRole.USER && !isGroupMember
    // 成员发言与 AI 回复一致：reasoning 由协调器从成员子会话带回，复用思考气泡。
    val hasReasoning = (isGroupMember || message.role == MessageRole.ASSISTANT) && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    // 模型直出的图片走附件落库，纯图消息没有正文与思考，同样要展示（不能提前 return）。
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning && !hasAttachments) return

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 用户气泡随文字撑开，最大撑到与 AI 气泡同宽（消息列宽 - 列表两侧 padding）。
    // 大屏下消息列已限宽居中，气泡上限跟着收窄，不能再拿整个屏宽算。
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // 工具卡片入场：只对本次浏览期间新追加进来的消息播（entryDelayMs 非空），
    // 历史、切页返回、item 回收重挂载都直接显示（谁该入场由 MessageEntryScheduler 定）。
    // 入场只改 alpha 与 translationY（draw 阶段生效），卡片高度从插入那一帧就到位：
    // 用 AnimatedVisibility 的话未入场的卡片完全不占位，每张卡片入场都让列表高度跳一次，
    // 与贴底跟随的 scrollToItem 叠加就是一连串抖动——而那正是这个动画本来要消除的。
    // 现在列表高度只在消息插入时变一次，之后动画全程不碰布局。
    var entered by rememberSaveable(message.id) { mutableStateOf(entryDelayMs == null) }
    LaunchedEffect(message.id) {
        if (entered) return@LaunchedEffect
        val delayMs = entryDelayMs ?: return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = MESSAGE_ENTRY_ANIM_MS, easing = LinearOutSlowInEasing),
        label = "tool-entry"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning) {
            // 刚结束思考落库的消息保持展开（流式思考展开→落库折叠会高度骤变抽搐）；稍后/历史默认折叠
            val reasoningJustFinished = System.currentTimeMillis() - message.timestamp < REASONING_FRESH_WINDOW_MS
            ReasoningBubble(text = message.reasoning.orEmpty(), initiallyExpanded = reasoningJustFinished, cache = markdownCache)
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            // 群聊成员发言：左对齐 + 成员名（带头像色块），与用户右对齐气泡区分。
            if (isGroupMember) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupMemberNameBadge(message.senderName.orEmpty())
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                // 助手消息左对齐，用户消息右对齐
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.role == MessageRole.TOOL) {
                            Surface(
                                shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = entryProgress
                                        translationY = (1f - entryProgress) * MESSAGE_ENTRY_RISE.toPx()
                                    }
                            ) {
                                ToolMessageBody(message, liveOutput = liveOutput, onToggle = onToolToggle)
                            }
                        } else {
                            Surface(
                                shape = if (isUser) {
                                    RoundedCornerShape(Radius.md, Radius.md, Radius.xs, Radius.md)
                                } else {
                                    RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs)
                                },
                                color = when {
                                    isGroupMember || message.role == MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surface
                                    message.role == MessageRole.USER -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                border = if (message.role == MessageRole.ASSISTANT || isGroupMember) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                } else null,
                                // 用户气泡随内容自适应宽度，最大撑到与 AI 气泡同宽；AI/工具气泡填满可用宽度
                                modifier = if (isUser) {
                                    Modifier.widthIn(max = maxUserBubbleWidth)
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                            ) {
                                val textColor = when {
                                    // 群聊成员发言渲染在 surface 气泡上，必须用 onSurface 深色文字；
                                    // 只有用户自己的右对齐气泡（primary 底）才用 onPrimary。
                                    isUser -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                SelectionContainer {
                                    val selectionColors = if (isUser) {
                                        TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.onPrimary,
                                            backgroundColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f),
                                        )
                                    } else {
                                        TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.primary,
                                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                        )
                                    }
                                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                                        if (isUser) {
                                            Text(
                                                text = message.content,
                                                color = textColor,
                                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                                            )
                                        } else {
                                            MarkdownContent(
                                                text = message.content,
                                                color = textColor,
                                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                                                cache = markdownCache
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if ((isUser || message.role == MessageRole.ASSISTANT) && hasAttachments) {
                    MessageAttachmentPreviewRow(attachments = message.attachments)
                }
                // 气泡下方操作行（工具消息不显示）。纯图片消息没有文字，同样要能撤销/删除，故附件也算
                if ((hasContent || hasAttachments) && message.role != MessageRole.TOOL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        if (hasContent) {
                            MessageActionIconButton(
                                icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                                contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                                tint = iconTint,
                                onClick = {
                                    copyScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("message", message.content))
                                        )
                                        copied = true
                                    }
                                }
                            )
                        }
                        if (isUser && onRewindClick != null) {
                            MessageActionIconButton(
                                icon = FeatherIcons.RotateCcw,
                                contentDescription = stringResource(R.string.checkpoint_rewind_title),
                                tint = iconTint,
                                onClick = { onRewindClick(message.id) }
                            )
                        }
                        if (onMoreClick != null) {
                            MessageActionIconButton(
                                icon = FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.chat_more_options),
                                tint = iconTint,
                                onClick = { onMoreClick(message) }
                            )
                        }
                        if (message.role == MessageRole.ASSISTANT && (message.inputTokens > 0 || message.outputTokens > 0)) {
                            val inStr = formatTokenCount(message.inputTokens.toLong())
                            val outStr = formatTokenCount(message.outputTokens.toLong())
                            Text(
                                text = "↑$inStr ↓$outStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val cacheHitRate = if (message.role == MessageRole.ASSISTANT) {
                            formatCacheHitRate(message.inputTokens, message.cachedInputTokens)
                        } else null
                        if (cacheHitRate != null) {
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Database,
                                contentDescription = stringResource(R.string.chat_cache_hit_rate, cacheHitRate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = cacheHitRate,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (taskDurationMs != null) {
                            val durationText = formatTaskDuration(taskDurationMs)
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Clock,
                                contentDescription = stringResource(R.string.chat_task_duration, durationText),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = durationText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 复制成功 1.5s 后恢复图标
                    if (copied) {
                        LaunchedEffect(copied) {
                            delay(1500)
                            copied = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
    }
}

/**
 * 后台任务完成通知的轻量提示条：不作为普通用户气泡展示，仅以紧凑横条形式告知用户
 * 哪个后台命令结束了、成功与否。从通知文本里提取 <status>/<summary> 字段。
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val content = message.content
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val isSuccess = statuses.all { it == "completed" }
    val dotColor = if (isSuccess) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.error
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            // 摘要原文由 domain 层生成（同一份还要喂给 AI），这里不按中文标记切字符串取任务名——
            // 换文案或切到英文界面这段解析就废了。多条时只报数量与失败数。
            val failedCount = statuses.count { it != "completed" }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 上下文压缩成功卡片：默认折叠为「圆点 + 上下文已压缩 + 箭头」，点击展开查看摘要全文。
 * 用 primary 色系与工具调用（surfaceVariant + 绿/红点）区分。
 */
@Composable
private fun CompactionSummaryCard(message: AgentUIMessage, markdownCache: MarkdownRenderCache?) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_context_compressed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded && message.content.hasVisibleContent()) {
                Spacer(Modifier.height(Spacing.sm))
                MarkdownContent(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    cache = markdownCache
                )
            }
        }
    }
}

/**
 * 上下文压缩失败卡片：默认折叠为「圆点 + 压缩失败 + 原因首行 + 箭头」，点击展开查看完整原因。
 * 用 error 色系与工具调用失败（surfaceVariant + 红点）区分。
 */
@Composable
private fun CompactionFailureCard(message: AgentUIMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reason = message.content.ifBlank { stringResource(R.string.chat_compaction_failed) }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_compaction_failed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (!expanded) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = reason.replace("\n", " ").trim(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded && reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 群聊成员发言上方的名字徽标：头像色块 + 成员名。 */
@Composable
private fun GroupMemberNameBadge(name: String) {
    Row(
        modifier = Modifier.padding(start = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(groupMemberColor(name))
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 成员名 → 稳定色（与 GroupAvatar 同规则）。 */
private fun groupMemberColor(name: String): Color {
    val hue = ((name.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.55f, 0.75f)
}