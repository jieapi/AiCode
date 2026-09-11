package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicode.R

internal val DiffAddBg = Color(0x2622C55E)
internal val DiffAddText = Color(0xFF22C55E)
internal val DiffRemoveBg = Color(0x26EF4444)
internal val DiffRemoveText = Color(0xFFEF4444)

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/**
 * 工具消息：默认折叠为一行「状态圆点 + 工具名 + 参数摘要 + 箭头」，点击展开查看「指令」与「结果」。
 * 状态圆点仿 Claude Code：运行中=白点闪烁、成功=绿、失败=红。
 * [liveOutput] 非空时进入「实时输出」模式：显示逐行累积输出。
 * 对 edit_file / write_file 这类带结构化差异的结果，展开后以「+新增/−删除」的彩色差异视图呈现。
 */
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    onToggle: (() -> Unit)? = null
) {
    val streaming = liveOutput != null
    val running = streaming || message.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        message.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    val edit = if (!running && !message.isError &&
        (message.toolName == "editFile" || message.toolName == "writeFile")
    ) {
        remember(message.id, message.content) { parseEditDiff(message.content) }
    } else null

    val resultText = if (!running) {
        remember(message.id, message.content) { formatToolResult(message.content) }
    } else null
    val argHint = remember(message.toolArgs) { toolArgHint(message.toolArgs) }
    val argsFull = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }

    val todoData = if (message.toolName == "todo" && !running && !message.isError) {
        remember(message.id, message.content) { parseTodoResult(message.content) }
    } else null
    val webSearchData = if (message.toolName == "websearch" && !running && !message.isError) {
        remember(message.id, message.content) { parseWebSearchResult(message.content) }
    } else null
    // 后台任务/子代理完成通知：搭车在本次工具结果里送给 AI 的，同时常显给用户看。
    val notifications = if (!running) {
        remember(message.id, message.content) { parseToolNotifications(message.content) }
    } else emptyList()

    // 执行中也可折叠/展开（如 bash 刷屏时可收起只看标题行），无论当前是否有输出；无输出时折叠态无内容，但保持可点击与箭头一致
    val hasLiveOutput = !liveOutput.isNullOrBlank()
    val expandable = streaming || (!running && (edit != null || !resultText.isNullOrBlank() || !argsFull.isNullOrBlank()
            || (todoData != null && todoData.items.isNotEmpty()) || webSearchData != null))
    var expanded by remember(message.id) { mutableStateOf(edit != null || todoData != null) }
    var userToggled by remember(message.id) { mutableStateOf(false) }
    // 执行中默认收起（可手动展开看实时输出与指令）；落库后按内容类型决定（edit/todo 默认展开，其余折叠）；用户手动 toggle 后以用户选择为准
    val effectiveExpanded = if (userToggled) expanded else (edit != null || todoData != null)

    val toolLabel = message.toolName ?: stringResource(R.string.common_tool)
    // 文件相关工具：从结构化 diff 或工具参数里取路径，统一按「工具名 + 路径 + 文件名」展示
    val filePath = if (edit != null) {
        edit.path
    } else {
        remember(message.toolArgs) { extractFilePathArg(message.toolArgs) }
    }

    Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) Modifier.clickable {
                        userToggled = true
                        expanded = !effectiveExpanded
                        onToggle?.invoke()
                    } else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolStatusDot(running = running, isError = message.isError)
            Spacer(Modifier.width(Spacing.sm))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!filePath.isNullOrBlank()) {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    // 路径段（可省略）+ 文件名段（永远完整，优先级最高）
                    val pathDir = filePath.substringBeforeLast('/')
                    if (pathDir.isNotEmpty()) {
                        Text(
                            text = pathDir + "/",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = filePath.substringAfterLast('/'),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!argHint.isNullOrBlank()) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = argHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (edit != null) {
                DiffStat(added = edit.added, removed = edit.removed)
                Spacer(Modifier.width(Spacing.sm))
            }
            if (todoData != null && todoData.total > 0) {
                Text(
                    text = "${todoData.completed}/${todoData.total}",
                    color = if (todoData.completed == todoData.total) DiffAddText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            if (expandable) {
                Icon(
                    if (effectiveExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (streaming) {
            // 展开态与落库卡片同构：先「指令」（工具参数），再「结果」（实时输出尾部），输出进行中指示放结果下方；
            // 折叠态只保留标题行
            if (effectiveExpanded) {
                if (!argsFull.isNullOrBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                }
                if (hasLiveOutput) {
                    val truncated = remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
                    Spacer(Modifier.height(Spacing.sm))
                    ToolSection(label = stringResource(R.string.tool_result), content = truncated)
                }
                Spacer(Modifier.height(Spacing.sm))
                TypingDots(color = MaterialTheme.colorScheme.onSurfaceVariant, dotSize = 5.dp)
            }
        } else if (effectiveExpanded) {
            Column(
                modifier = Modifier.pointerInput(message.id) {
                    detectDoubleTapToCollapse {
                        userToggled = true
                        expanded = false
                        onToggle?.invoke()
                    }
                }
            ) {
                if (todoData != null && todoData.items.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    TodoCard(items = todoData.items)
                } else if (webSearchData != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    WebSearchResultCard(result = webSearchData)
                } else if (edit != null) {
                    edit.hunks.forEach { h ->
                        Spacer(Modifier.height(Spacing.xs))
                        DiffView(diff = h.diff, startLine = h.startLine)
                    }
                } else {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (!resultText.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = resultText)
                    }
                }
            }
        }
        if (notifications.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            notifications.forEach { ToolNotificationRow(it) }
        }
        // 文件卡片：工具结束后常显在消息底部，点击用系统 app 打开。
        if (!running && message.attachments.isNotEmpty()) {
            val context = LocalContext.current
            MessageAttachmentPreviewRow(
                attachments = message.attachments,
                onClick = { openAttachment(context, it) }
            )
        }
    }
}

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false)

/**
 * 从工具结果 transport JSON 顶层的 `notifications` 字段提取搭车通知（后台任务/子代理完成）。
 * 该字段由 [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow] 在 AI 忙碌时注入。
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message"
        )
    }
}

/** 工具卡片底部的搭车通知提示条：状态点 + 摘要。 */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/**
 * 双击折叠检测：detectTapGestures 的 awaitFirstDown 默认 requireUnconsumed=true，
 * 而展开区内容包在 SelectionContainer / clickable 里会消费 down，导致首击被忽略、
 * 双击永远不触发。这里首击不要求未消费，只做「快速连点两次」判定。
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
    val viewConfig = viewConfiguration
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        // 两次点击期间若发生明显位移（用户在滑动），放弃双击判定
        if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
            return@awaitEachGesture
        }
        if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
            return@awaitEachGesture
        }
        if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        onDoubleTap()
    }
}

/** 等待指定指针抬起；期间若位移超过 slop（发生滑动），返回 false。 */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - downPosition).getDistance() > slop) return false
        if (!change.pressed) return true
    }
}

/**
 * 工具状态圆点（仿 Claude Code）：运行中=主题中性「白点」并循环闪烁，成功=绿，失败=红。
 */
@Composable
internal fun ToolStatusDot(running: Boolean, isError: Boolean) {
    val baseColor = when {
        running -> MaterialTheme.colorScheme.onSurface
        isError -> DiffRemoveText
        else -> DiffAddText
    }
    val dotAlpha = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-status-dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
            label = "tool-status-dot-alpha"
        ).value
    } else {
        1f
    }
    val statusLabel = stringResource(
        when {
            running -> R.string.chat_tool_running
            isError -> R.string.chat_tool_failed
            else -> R.string.chat_tool_succeeded
        }
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { alpha = dotAlpha }
            .clip(CircleShape)
            .background(baseColor)
            // 颜色是唯一信号，读屏与色弱用户没法区分，补上文字语义。
            .semantics { contentDescription = statusLabel }
    )
}

/** 展开区的一段带小标题的内容块（如「指令」「结果」） */
@Composable
internal fun ToolSection(label: String, content: String) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(2.dp))

    val lines = remember(content) { content.split("\n") }
    val collapsible = lines.size > TOOL_SECTION_LINE_LIMIT
    var expanded by remember(content) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.takeLast(TOOL_SECTION_LINE_LIMIT) else lines
    val hiddenCount = lines.size - TOOL_SECTION_LINE_LIMIT

    SelectionContainer {
        Text(
            text = visibleLines.joinToString("\n"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            )
        )
    }

    if (collapsible) {
        DiffExpandToggle(
            expanded = expanded,
            hiddenCount = hiddenCount,
            onToggle = { expanded = !expanded }
        )
    }
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 彩色行级差异视图
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
    val lines = remember(diff) { diff.split("\n") }
    val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(diff) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
    val removeCount = lines.count { it.startsWith("-") }
    val addCount = lines.count { it.startsWith("+") }
    val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
    val gutterChars = maxOf(2, maxLineNo.toString().length)
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 横向滚动容器在外层、文本选择在内层：滚动手势优先，
        // 避免 SelectionContainer 偶发抢占左右滑动（选择模式激活后拖动被选词消费）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.background)
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    var oldLineNo = startLine
                    var newLineNo = startLine
                    visibleLines.forEach { line ->
                        val marker = line.firstOrNull()
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val lineNo = when (marker) {
                            '-' -> oldLineNo++
                            '+' -> newLineNo++
                            else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                        }
                        val gutter = lineNo.toString().padStart(gutterChars)
                        val styled = buildAnnotatedString {
                            withStyle(SpanStyle(color = gutterColor)) {
                                append(gutter)
                                append("  ")
                            }
                            withStyle(SpanStyle(color = fg)) {
                                append(line.ifEmpty { " " })
                            }
                        }
                        Text(
                            text = styled,
                            style = mono,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        if (collapsible) {
            DiffExpandToggle(
                expanded = expanded,
                hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/** 长差异的页脚切换 */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.tool_expand_remaining, hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** editFile 单处编辑的差异片段。 */
internal data class EditHunk(val startLine: Int, val diff: String)

/** editFile 结果中解析出的结构化差异 */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>
)

/**
 * 从持久化的 TOOL 内容中解析 editFile / writeFile 的结构化差异
 */
internal fun parseEditDiff(content: String): EditDiff? {
    val dataObj = extractToolDataObject(content)
    if (dataObj != null) {
        return parseEditDiffObject(dataObj)
    }

    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
    }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

    val hunks = obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d)
    } ?: run {
        val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
        listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
    }
    if (hunks.isEmpty()) return null
    return EditDiff(path = path, added = added, removed = removed, hunks = hunks)
}

/**
 * 把落库的原始工具结果清洗成可读文本
 */
internal fun formatToolResult(raw: String): String {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
            "success", "partial" -> formatToolData(obj["data"]) ?: s
            else -> s
        }
    }

    when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx >= 0) {
                var body = s.substring(msgIdx + "message=".length)
                val codeIdx = body.lastIndexOf(", code=")
                body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
                return body.trim()
            }
        }
        s.startsWith("Success(data=") -> {
            val inner = s.removePrefix("Success(data=").removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
        s.startsWith("Partial(data=") -> {
            var inner = s.removePrefix("Partial(data=")
            val msgIdx = inner.lastIndexOf(", message=")
            inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
    }
    return s
}

private fun parseToolTransport(raw: String): JsonObject? {
    return runCatching {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        if (obj["status"] != null) obj else null
    }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
    return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
    return when (data) {
        is JsonPrimitive -> data.contentOrNull ?: data.toString()
        is JsonObject -> {
            val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
            val mainStr = (main as? JsonPrimitive)?.contentOrNull
            mainStr ?: data.entries.joinToString("\n") { (k, v) ->
                val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                "$k: $vv"
            }
        }
        null -> null
        else -> data.toString()
    }
}

internal fun String.withoutToolStatusPrefix(): String = trim()
    .removePrefix(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
    .removePrefix(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    .removePrefix(SessionUseCase.PENDING_TOOL_MARKER)
    .trim()

/**
 * 把 `data=` 里的 JsonElement 文本渲染成可读结果
 */
internal fun formatJsonData(jsonStr: String): String? = runCatching {
    when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
    }
}.getOrNull()

/** 把传入参数 JSON 列成 `key: value` 多行 */
internal fun formatToolArgs(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        if (obj.isEmpty()) return null
        obj.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
        }
    }.getOrNull() ?: argsJson.trim()
}

/** 标题行内联的参数摘要 */
internal fun toolArgHint(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        val preferred = listOf("command", "cmd", "path", "file_path", "file", "query", "pattern", "url", "name")
        val v = preferred.firstNotNullOfOrNull { obj[it] } ?: obj.values.firstOrNull()
        val str = (v as? JsonPrimitive)?.contentOrNull ?: v?.toString()
        str?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** 从工具参数 JSON 中提取文件路径（readFile/editFile/writeFile 的 path 参数）。 */
private fun extractFilePathArg(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        listOf("path", "file_path", "file").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()
}
