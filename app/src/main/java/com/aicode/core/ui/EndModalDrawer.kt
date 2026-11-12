package com.aicode.core.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 关闭状态下允许起手滑开的区域：右半屏。与左抽屉「从任意位置向右滑」相比收窄，
 * 左侧阅读/选中文本时不会触发；起点也不要求贴着最右边缘，避免被系统返回手势拦截。 */
private const val END_DRAWER_OPEN_START_FRACTION = 0.5f

private const val DRAWER_SETTLE_DURATION_MS = 256

/** 位置阈值取整段行程的一半，与 Material 左抽屉一致；速度阈值沿用 Material 的 400dp/s。 */
private val DrawerVelocityThreshold = 400.dp

/**
 * 终点侧（右侧）模态抽屉的状态。
 *
 * Material3 的 [androidx.compose.material3.ModalNavigationDrawer] 只支持起点侧抽屉，
 * 常用的 RTL 包法又依赖其内部拖拽状态（`internal`，应用层拿不到），关闭手势时就只能
 * 「够距离后直接 open()」。这里用公开的 [AnchoredDraggableState] 自行驱动偏移，
 * 让右抽屉和左抽屉一样跟手。
 */
@Stable
class EndDrawerState internal constructor(
    initialValue: DrawerValue,
    velocityThreshold: Float
) {
    // 不走 Modifier.anchoredDraggable 是因为它没有「起手区域」参数，会把左抽屉的
    // 边缘手势一起吃掉；阈值与吸附规格仍沿用 Material 的同一套实现。
    @Suppress("DEPRECATION")
    internal val anchored = AnchoredDraggableState(
        initialValue = initialValue,
        positionalThreshold = { distance -> distance * 0.5f },
        velocityThreshold = { velocityThreshold },
        snapAnimationSpec = tween(durationMillis = DRAWER_SETTLE_DURATION_MS),
        decayAnimationSpec = AnchoredDraggableDefaults.DecayAnimationSpec
    )

    val isOpen: Boolean
        get() = anchored.currentValue == DrawerValue.Open

    suspend fun open() {
        anchored.animateTo(DrawerValue.Open, tween(durationMillis = DRAWER_SETTLE_DURATION_MS))
    }

    suspend fun close() {
        anchored.animateTo(DrawerValue.Closed, tween(durationMillis = DRAWER_SETTLE_DURATION_MS))
    }

    @Suppress("DEPRECATION")
    internal suspend fun settle(velocity: Float) {
        anchored.settle(velocity)
    }
}

@Composable
fun rememberEndDrawerState(
    initialValue: DrawerValue = DrawerValue.Closed
): EndDrawerState {
    val density = LocalDensity.current
    return remember(density, initialValue) {
        EndDrawerState(
            initialValue = initialValue,
            velocityThreshold = with(density) { DrawerVelocityThreshold.toPx() }
        )
    }
}

/**
 * 右侧模态抽屉：抽屉停靠在布局终点侧，内容不被挤压，遮罩与偏移随手指实时变化。
 *
 * [gesturesEnabled] 控制关闭状态下能否起手滑开；抽屉已经打开时始终允许拖拽关闭，
 * 即使当前页面不再允许打开新抽屉。
 */
@Composable
fun EndModalDrawer(
    state: EndDrawerState,
    gesturesEnabled: Boolean,
    drawerWidth: Dp,
    modifier: Modifier = Modifier,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val widthPx = with(density) { drawerWidth.toPx() }
    val scope = rememberCoroutineScope()
    val isOpen = state.isOpen
    val scrimColor = DrawerDefaults.scrimColor
    val paneTitleText = stringResource(R.string.project_tools_drawer_title)

    LaunchedEffect(state, widthPx) {
        if (widthPx > 0f) {
            state.anchored.updateAnchors(
                DraggableAnchors {
                    DrawerValue.Closed at widthPx
                    DrawerValue.Open at 0f
                }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(state, widthPx, gesturesEnabled) {
                val openStartX = size.width * END_DRAWER_OPEN_START_FRACTION
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val wasOpen = state.isOpen
                    if (!wasOpen && (!gesturesEnabled || down.position.x < openStartX)) {
                        return@awaitEachGesture
                    }
                    if (state.anchored.offset.isNaN()) return@awaitEachGesture

                    var dragging = false
                    var totalX = 0f
                    var totalY = 0f
                    var dragJob: Job? = null
                    val deltas = Channel<Float>(Channel.UNLIMITED)
                    val velocityTracker = VelocityTracker().apply {
                        addPointerInputChange(down)
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            velocityTracker.addPointerInputChange(change)

                            if (change.changedToUpIgnoreConsumed()) {
                                if (dragging) change.consume()
                                break
                            }
                            // 子级（横向滚动等）先接管的手势不再抢。
                            if (!dragging && change.isConsumed) break

                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y

                            if (!dragging) {
                                totalX += dx
                                totalY += dy
                                if (abs(totalX) < viewConfiguration.touchSlop) continue
                                if (abs(totalX) <= abs(totalY)) return@awaitEachGesture

                                // 只认能把抽屉带向另一端的拖动方向；反方向留给内容。
                                if (wasOpen && totalX < 0f) return@awaitEachGesture
                                if (!wasOpen && totalX > 0f) return@awaitEachGesture

                                dragging = true
                                change.consume()
                                dragJob = scope.launch {
                                    state.anchored.anchoredDrag(MutatePriority.UserInput) {
                                        for (delta in deltas) {
                                            val current = state.anchored.offset
                                            if (!current.isNaN()) {
                                                dragTo((current + delta).coerceIn(0f, widthPx))
                                            }
                                        }
                                    }
                                }
                                deltas.trySend(totalX)
                            } else {
                                change.consume()
                                if (dx != 0f) deltas.trySend(dx)
                            }
                        }
                    } finally {
                        deltas.close()
                        if (dragging) {
                            val velocity = velocityTracker.calculateVelocity().x
                            scope.launch {
                                dragJob?.join()
                                state.settle(velocity)
                            }
                        }
                    }
                }
            }
    ) {
        content()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isOpen) {
                        Modifier.pointerInput(state) {
                            detectTapGestures { scope.launch { state.close() } }
                        }
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    val offset = state.anchored.offset
                    val fraction = if (offset.isNaN() || widthPx <= 0f) {
                        0f
                    } else {
                        (1f - offset / widthPx).coerceIn(0f, 1f)
                    }
                    if (fraction > 0f) {
                        drawRect(scrimColor.copy(alpha = scrimColor.alpha * fraction))
                    }
                }
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .absoluteOffset {
                    val offset = state.anchored.offset
                    IntOffset((if (offset.isNaN()) widthPx else offset).roundToInt(), 0)
                }
                .width(drawerWidth)
                .semantics {
                    paneTitle = paneTitleText
                    if (isOpen) {
                        dismiss {
                            scope.launch { state.close() }
                            true
                        }
                    }
                }
        ) {
            drawerContent()
        }
    }
}
