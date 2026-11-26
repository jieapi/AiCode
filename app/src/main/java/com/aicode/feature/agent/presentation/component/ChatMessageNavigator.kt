package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 少于这么多轮对话时不显示导航条：两三个横条既定位不了什么，又白白占住右缘。 */
internal const val CHAT_NAVIGATOR_MIN_MARKERS = 3

/** 常显窗口固定 7 条：当前消息 + 上方 3 条 + 下方 3 条，顶部到底部时自动让出 6 条给同侧。 */
private const val WINDOW_SLOTS = 7
private const val WINDOW_CENTER_SLOT = WINDOW_SLOTS / 2

/** 预览文案最多取多少个字符。 */
private const val PREVIEW_CHARS = 18

/** 导航条宽度：容纳最长的选中横条加右端留白。列表右侧留白按它加大，横条不压气泡与按钮行。 */
internal val CHAT_NAVIGATOR_WIDTH = Spacing.xl
private val SlotSpacing = 10.dp

/** 未选中横条长度 = 旧的选中长度；选中再长一倍。 */
private val TickWidth = 10.dp
private val ActiveTickWidth = 20.dp
private val TickHeight = 3.dp

/** 横条右端与聊天区右缘的留白；[CHAT_NAVIGATOR_WIDTH] 恰好容纳选中条加这点留白。 */
private val TickEndInset = Spacing.xs

/**
 * 整列上移的距离。
 *
 * 贴着屏幕垂直居中会偏下，底部悬浮的输入框又把视觉重心压得更低，
 * 上移约一个整列高度，让它在聊天区中部偏上。
 */
private val NavigatorLift = 64.dp

private val WheelRadius = 104.dp
private val WheelTrackOuterInset = 10.dp
private val WheelTrackInnerInset = 38.dp
private val WheelHubRadius = 30.dp
private val WheelPreviewMinWidth = 124.dp
private val WheelPreviewMaxWidth = 204.dp
private val WheelPreviewTopMargin = 48.dp
private val WheelPreviewBottomMargin = 64.dp

/** 刻度弧从顶缘偏左 12° 起扫到底缘偏左 12°：首尾刻度与指示器不贴屏幕右缘被裁切。 */
private const val WHEEL_START_DEGREES = 258f
private const val WHEEL_SWEEP_DEGREES = 156f
private const val MAX_INDIVIDUAL_TICKS = 33
private const val SCALE_TICK_COUNT = 33

private val LeftSemicircleShape = GenericShape { size, _ ->
    val rect = Rect(0f, 0f, size.width * 2f, size.height)
    arcTo(rect, startAngleDegrees = 270f, sweepAngleDegrees = -180f, forceMoveTo = true)
    close()
}

/** 导航条里的一条用户消息。 */
internal data class ChatNavigationMarker(
    val messageId: String,
    val timestamp: Long,
    val preview: String,
)

/** 预览文案：正文去掉换行后的前 [PREVIEW_CHARS] 个字符。 */
internal fun navigationMarkerPreview(content: String): String {
    val text = content.replace('\n', ' ').trim()
    return if (text.length > PREVIEW_CHARS) text.take(PREVIEW_CHARS) + "…" else text
}

/**
 * 聊天区右缘的消息导航条。
 *
 * 常显部分只是一扇 7 条的窗口（当前 ±3，到顶/到底时改为 6 条同侧），
 * **长按**会在窗口处展开半圆轮盘，轮盘按角度铺满整个会话的全部用户消息，
 * 沿弧推动即切换选中项，选中项旁边浮出该消息的开头几个字作预览，松手跳到那一条。
 *
 * @param markers 整个会话的用户消息，按时间正序（不受聊天区分页影响）
 * @param currentIndex 当前读到的那一轮在 [markers] 中的下标
 * @param onSeek 跳到指定用户消息，由调用方负责加载与滚动
 */
@Composable
internal fun ChatMessageNavigator(
    markers: List<ChatNavigationMarker>,
    currentIndex: Int,
    onSeek: (ChatNavigationMarker) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (markers.size < CHAT_NAVIGATOR_MIN_MARKERS) return

    val safeCurrentIndex = currentIndex.coerceIn(0, markers.lastIndex)
    // 轮盘选中项（markers 下标）；null = 轮盘未打开。
    // 不用 markers 做 key：流式输出时列表会反复重建，重建会把正在推的轮盘关掉。
    var wheelSelection by remember { mutableStateOf<Int?>(null) }
    // 收起动画期间保留最后选中的下标，避免退场帧闪烁或跳回起点。
    var lastWheelSelection by remember { mutableIntStateOf(safeCurrentIndex) }
    wheelSelection?.let { lastWheelSelection = it.coerceIn(0, markers.lastIndex) }

    val latestMarkers by rememberUpdatedState(markers)
    val latestCurrentIndex by rememberUpdatedState(safeCurrentIndex)
    val hapticFeedback = LocalHapticFeedback.current
    val animationsEnabled = chatAnimationsEnabled()
    // 横条列与轮盘圆心一起上移：两者必须同源，否则轮盘会偏离横条。
    val liftPx = with(LocalDensity.current) { NavigatorLift.roundToPx() }

    val wheelOpen = wheelSelection != null
    val openProgress by animateFloatAsState(
        targetValue = if (wheelOpen) 1f else 0f,
        animationSpec = when {
            !animationsEnabled -> snap()
            wheelOpen -> spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f)
            else -> tween(durationMillis = 150)
        },
        label = "chat-navigator-wheel-open",
    )

    Box(modifier = modifier.fillMaxSize()) {
        if (openProgress > 0.01f) {
            ChatMessageWheel(
                markers = markers,
                selection = lastWheelSelection.coerceIn(0, markers.lastIndex),
                currentIndex = safeCurrentIndex,
                liftPx = liftPx,
                openProgress = openProgress.coerceIn(0f, 1f),
                animationsEnabled = animationsEnabled,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ChatMessageStrip(
            markers = markers,
            currentSlot = safeCurrentIndex,
            stripAlpha = (1f - openProgress * 2f).coerceIn(0f, 1f),
            onTap = { slot -> onSeek(latestMarkers[slot]) },
            onWheelStart = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                wheelSelection = latestCurrentIndex
            },
            onWheelSelect = { slot ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                wheelSelection = slot
            },
            onWheelEnd = {
                val selected = wheelSelection?.coerceIn(0, latestMarkers.lastIndex)
                wheelSelection = null
                // 没推动轮盘就松手（选中项仍是当前条）不跳，避免「只是想看一眼」被拽到别处。
                if (selected != null && selected != latestCurrentIndex) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    onSeek(latestMarkers[selected])
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(0, -liftPx) },
        )
    }
}

/**
 * 常显的 7 条窗口。轮盘展开时渐隐（触摸区保持挂载，避免手势中断）。
 */
@Composable
private fun ChatMessageStrip(
    markers: List<ChatNavigationMarker>,
    currentSlot: Int,
    stripAlpha: Float,
    onTap: (Int) -> Unit,
    onWheelStart: () -> Unit,
    onWheelSelect: (Int) -> Unit,
    onWheelEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    val activeColor = MaterialTheme.colorScheme.primary
    val windowStart = (currentSlot - WINDOW_CENTER_SLOT)
        .coerceIn(0, (markers.size - WINDOW_SLOTS).coerceAtLeast(0))
    val visibleSlots = minOf(WINDOW_SLOTS, markers.size - windowStart)

    // 手势块只用最新值：markers 在流式输出时随消息落库频繁重建，
    // 拿它当 pointerInput 的 key 会在长按/推动途中断掉手势。
    val latestMarkers by rememberUpdatedState(markers)
    val latestCurrentSlot by rememberUpdatedState(currentSlot)
    val latestWindowStart by rememberUpdatedState(windowStart)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnWheelStart by rememberUpdatedState(onWheelStart)
    val latestOnWheelSelect by rememberUpdatedState(onWheelSelect)
    val latestOnWheelEnd by rememberUpdatedState(onWheelEnd)

    Canvas(
        modifier = modifier
            .width(CHAT_NAVIGATOR_WIDTH)
            // 窗口不满 7 条时按实际条数收窄，整列仍垂直居中：轮盘圆心与横条列中点重合。
            .height(SlotSpacing * (visibleSlots - 1) + TickHeight)
            .pointerInput(Unit) {
                val spacingPx = SlotSpacing.toPx()
                val tickHeightPx = TickHeight.toPx()
                // 手指还压在右缘中心枢轴区内时角度不稳定，离开枢轴才按弧度选条；拖回枢轴则复位当前条。
                val wheelStartDistancePx = viewConfiguration.touchSlop * 3f

                fun slotAt(y: Float): Int {
                    val raw = ((y - tickHeightPx / 2f) / spacingPx).roundToInt()
                    return (latestWindowStart + raw).coerceIn(latestWindowStart, latestMarkers.lastIndex)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // 没到长按时长就返回：手已松开 = 点了一条，按滑到别处（想滚列表）= 什么也不做。
                        val released = currentEvent.changes.none { it.id == down.id && it.pressed }
                        if (released) latestOnTap(slotAt(down.position.y))
                        return@awaitEachGesture
                    }
                    longPress.consume()
                    latestOnWheelStart()
                    var lastSlot = latestCurrentSlot
                    fun follow(position: Offset) {
                        val slot = wheelSlotAt(
                            position = position,
                            centerX = size.width.toFloat(),
                            centerY = size.height / 2f,
                            hubRadiusPx = wheelStartDistancePx,
                            count = latestMarkers.size,
                        ) ?: latestCurrentSlot
                        if (slot != lastSlot) {
                            lastSlot = slot
                            latestOnWheelSelect(slot)
                        }
                    }
                    follow(longPress.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        change.consume()
                        follow(change.position)
                    }
                    latestOnWheelEnd()
                }
            }
    ) {
        if (stripAlpha <= 0f) return@Canvas
        for (slot in 0 until visibleSlots) {
            val markerSlot = windowStart + slot
            val active = markerSlot == currentSlot
            val tickWidth = if (active) ActiveTickWidth.toPx() else TickWidth.toPx()
            val top = slot * SlotSpacing.toPx()
            val baseColor = if (active) activeColor else idleColor
            drawRoundRect(
                color = baseColor.copy(alpha = baseColor.alpha * stripAlpha),
                topLeft = Offset(size.width - TickEndInset.toPx() - tickWidth, top),
                size = Size(tickWidth, TickHeight.toPx()),
                cornerRadius = CornerRadius(TickHeight.toPx() / 2f),
            )
        }
    }
}

/**
 * 半圆表盘轮盘：以实底卡片承托刻度环与指针，避开聊天正文/壁纸杂色干扰，
 * 左侧浮出带轮次序号与时间的预览卡。手势由 [ChatMessageStrip] 接管，这里只负责展示。
 */
@Composable
private fun ChatMessageWheel(
    markers: List<ChatNavigationMarker>,
    selection: Int,
    currentIndex: Int,
    liftPx: Int,
    openProgress: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semanticColors = MaterialTheme.semanticColors
    val selectedMarker = markers.getOrNull(selection) ?: return

    val animatedSelection by animateFloatAsState(
        targetValue = selection.toFloat(),
        animationSpec = if (animationsEnabled) {
            spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        } else {
            snap()
        },
        label = "chat-navigator-needle",
    )

    val dialSurfaceColor = colorScheme.surface
    val trackSurfaceColor = semanticColors.mutedSurface.copy(alpha = 0.72f)
    val hubSurfaceColor = semanticColors.capsuleSurface.copy(alpha = 0.35f)
    val borderColor = colorScheme.outlineVariant
    val minorTickColor = colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val majorTickColor = colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    val originDotColor = colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val activeColor = colorScheme.primary
    val onPrimaryColor = colorScheme.onPrimary

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val radiusPx = with(density) { WheelRadius.toPx() }
        val centerY = constraints.maxHeight / 2f - liftPx
        val activeRadians = wheelAngleRadians(animatedSelection, markers.size)

        // 轻度压暗底层内容，让表盘与预览卡从长文档和自定义壁纸上浮出来
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.scrim.copy(alpha = 0.14f * openProgress))
        )

        // 半圆表盘底板：沿用 AiCode 悬浮卡的 surface + 1dp 细描边 + 柔和阴影
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(0, -liftPx) }
                .width(WheelRadius)
                .height(WheelRadius * 2)
                .graphicsLayer {
                    alpha = openProgress
                    val scale = 0.74f + 0.26f * openProgress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            shape = LeftSemicircleShape,
            color = dialSurfaceColor,
            shadowElevation = 8.dp,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width, size.height / 2f)
                val dialRadius = size.width
                val hairlinePx = ChatStyle.hairline.toPx()
                val subHairlinePx = 0.75.dp.toPx()
                val trackOuterR = dialRadius - WheelTrackOuterInset.toPx()
                val trackInnerR = dialRadius - WheelTrackInnerInset.toPx()
                val trackMidR = (trackOuterR + trackInnerR) / 2f
                val trackWidth = trackOuterR - trackInnerR
                val hubR = WheelHubRadius.toPx()

                // 凹槽刻度环带
                drawArc(
                    color = trackSurfaceColor,
                    startAngle = 270f,
                    sweepAngle = -180f,
                    useCenter = false,
                    topLeft = Offset(center.x - trackMidR, center.y - trackMidR),
                    size = Size(trackMidR * 2f, trackMidR * 2f),
                    style = Stroke(width = trackWidth),
                )
                for (ringR in floatArrayOf(trackOuterR, trackInnerR)) {
                    drawArc(
                        color = borderColor.copy(alpha = 0.55f),
                        startAngle = 270f,
                        sweepAngle = -180f,
                        useCenter = false,
                        topLeft = Offset(center.x - ringR, center.y - ringR),
                        size = Size(ringR * 2f, ringR * 2f),
                        style = Stroke(width = subHairlinePx),
                    )
                }

                // 右缘指腹枢轴区
                drawCircle(
                    color = hubSurfaceColor,
                    radius = hubR,
                    center = center,
                )
                drawArc(
                    color = borderColor.copy(alpha = 0.45f),
                    startAngle = 270f,
                    sweepAngle = -180f,
                    useCenter = false,
                    topLeft = Offset(center.x - hubR, center.y - hubR),
                    size = Size(hubR * 2f, hubR * 2f),
                    style = Stroke(width = subHairlinePx),
                )

                // 刻度线：轮数较少时逐条对应，多轮长会话改绘等距精密标尺避免糊成一团
                val tickCount = if (markers.size <= MAX_INDIVIDUAL_TICKS) markers.size else SCALE_TICK_COUNT
                for (i in 0 until tickCount) {
                    val isMajor = i == 0 || i == tickCount - 1 || (tickCount > 6 && i % 4 == 0)
                    val tickRadians = wheelAngleRadians(i.toFloat(), tickCount)
                    val dirX = cos(tickRadians)
                    val dirY = sin(tickRadians)
                    val inset = if (isMajor) 5.dp.toPx() else 8.dp.toPx()
                    val inner = Offset(center.x + dirX * (trackInnerR + inset), center.y + dirY * (trackInnerR + inset))
                    val outer = Offset(center.x + dirX * (trackOuterR - inset), center.y + dirY * (trackOuterR - inset))
                    drawLine(
                        color = if (isMajor) majorTickColor else minorTickColor,
                        start = inner,
                        end = outer,
                        strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.15.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // 起始位置锚点：离开当前阅读轮后仍可看清「本来在哪」
                if (currentIndex != selection) {
                    val originRadians = wheelAngleRadians(currentIndex.toFloat(), markers.size)
                    val originR = trackInnerR - 6.dp.toPx()
                    drawCircle(
                        color = originDotColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(
                            center.x + cos(originRadians) * originR,
                            center.y + sin(originRadians) * originR,
                        ),
                    )
                }

                // 选中指示针：枢轴细导引线 + 环带胶囊指针 + 外缘游标
                val activeDirX = cos(activeRadians)
                val activeDirY = sin(activeRadians)
                drawLine(
                    color = activeColor.copy(alpha = 0.25f),
                    start = Offset(center.x + activeDirX * (hubR + 2.dp.toPx()), center.y + activeDirY * (hubR + 2.dp.toPx())),
                    end = Offset(center.x + activeDirX * (trackInnerR - 3.dp.toPx()), center.y + activeDirY * (trackInnerR - 3.dp.toPx())),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = activeColor,
                    start = Offset(center.x + activeDirX * (trackInnerR + 2.dp.toPx()), center.y + activeDirY * (trackInnerR + 2.dp.toPx())),
                    end = Offset(center.x + activeDirX * (trackOuterR - 2.dp.toPx()), center.y + activeDirY * (trackOuterR - 2.dp.toPx())),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val cursorCenter = Offset(
                    center.x + activeDirX * (trackOuterR + 3.dp.toPx()),
                    center.y + activeDirY * (trackOuterR + 3.dp.toPx()),
                )
                drawCircle(
                    color = activeColor.copy(alpha = 0.18f),
                    radius = 7.dp.toPx(),
                    center = cursorCenter,
                )
                drawCircle(
                    color = activeColor,
                    radius = 4.25.dp.toPx(),
                    center = cursorCenter,
                )
                drawCircle(
                    color = onPrimaryColor,
                    radius = 1.6.dp.toPx(),
                    center = cursorCenter,
                )

                // 只在弧形外缘绘 1dp 细描边，右缘紧贴屏幕边不画接缝线
                val borderR = dialRadius - hairlinePx / 2f
                drawArc(
                    color = borderColor,
                    startAngle = 270f,
                    sweepAngle = -180f,
                    useCenter = false,
                    topLeft = Offset(center.x - borderR, center.y - borderR),
                    size = Size(borderR * 2f, borderR * 2f),
                    style = Stroke(width = hairlinePx),
                )
            }
        }

        // 选中预览卡：跟随指示针上下滑动，展示轮次序号、时间与消息摘要
        val halfHeightPx = constraints.maxHeight / 2f
        val topLimit = -(halfHeightPx - with(density) { WheelPreviewTopMargin.toPx() }).coerceAtLeast(0f)
        val bottomLimit = (halfHeightPx - with(density) { WheelPreviewBottomMargin.toPx() }).coerceAtLeast(0f)
        val pointerY = centerY + sin(activeRadians) * (radiusPx - with(density) { 14.dp.toPx() })
        val clampedOffsetY = (pointerY - constraints.maxHeight / 2f).coerceIn(topLimit, bottomLimit)

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(start = Spacing.lg, end = WheelRadius + Spacing.md)
                .offset { IntOffset(0, clampedOffsetY.roundToInt()) }
                .graphicsLayer {
                    alpha = openProgress
                    val scale = 0.90f + 0.10f * openProgress
                    scaleX = scale
                    scaleY = scale
                    translationX = (1f - openProgress) * with(density) { 16.dp.toPx() }
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            shape = RoundedCornerShape(Radius.mdLarge),
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            border = BorderStroke(ChatStyle.hairline, colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = WheelPreviewMinWidth, max = WheelPreviewMaxWidth)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = colorScheme.primaryContainer,
                        contentColor = colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = "${selection + 1}/${markers.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = formatClockTime(selectedMarker.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = colorScheme.onSurfaceVariant,
                    )
                    if (selection == currentIndex) {
                        Text(
                            text = stringResource(R.string.chat_navigator_current_turn),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = colorScheme.primary,
                        )
                    }
                }
                val hasPreview = selectedMarker.preview.isNotEmpty()
                Text(
                    text = if (hasPreview) {
                        selectedMarker.preview
                    } else {
                        stringResource(R.string.chat_navigator_empty_preview)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasPreview) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 第 [index] 条在轮盘上的方向（弧度，屏幕坐标：y 向下）。 */
internal fun wheelAngleRadians(index: Float, count: Int): Float {
    val fraction = if (count <= 1) 0.5f else (index / (count - 1)).coerceIn(0f, 1f)
    return (WHEEL_START_DEGREES - WHEEL_SWEEP_DEGREES * fraction) * (PI.toFloat() / 180f)
}

/** 触点落在枢轴内返回 null（由调用方复位到当前条），落在外环则按弧角映射到对应下标。 */
internal fun wheelSlotAt(
    position: Offset,
    centerX: Float,
    centerY: Float,
    hubRadiusPx: Float,
    count: Int,
): Int? {
    if (count <= 0) return null
    val dx = position.x - centerX
    val dy = position.y - centerY
    if (hypot(dx, dy) < hubRadiusPx) return null
    if (count == 1) return 0
    val halfSweepRad = (WHEEL_SWEEP_DEGREES / 2f) * (PI.toFloat() / 180f)
    val theta = atan2(dy, (-dx).coerceAtLeast(0f))
    val fraction = ((theta + halfSweepRad) / (2f * halfSweepRad)).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt()
}
