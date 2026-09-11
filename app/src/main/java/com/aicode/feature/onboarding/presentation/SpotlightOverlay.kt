package com.aicode.feature.onboarding.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.onboarding.domain.OnboardingStep
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 全屏聚光灯新手引导遮罩层（Spotlight Overlay）。
 *
 * 1. 契合系统风格的通透遮罩（浅色 40% / 深色 52%），不产生压抑感；
 * 2. 硬件加速圆角挖孔（BlendMode.Clear）+ 原地平滑淡入（绝不从屏幕中央滑向目标）；
 * 3. 聚焦目标边缘带有极细腻的呼吸微光边框；
 * 4. 模拟面板使用原生 [ModelLogoIcon] 与应用级卡片层次，去除卡通玩具感；
 * 5. 悬浮精致 Material 3 规范说明卡片，去除厚重黑阴影，平滑呼吸淡入过渡。
 */
@Composable
fun SpotlightOverlay(
    currentStep: OnboardingStep,
    targetRect: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onTargetClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cardMarginPx = with(density) { 16.dp.toPx() }
    val cardGapPx = with(density) { 12.dp.toPx() }

    // 呼吸灯动画：在高亮描边周围产生柔和微光脉冲
    val infiniteTransition = rememberInfiniteTransition(label = "SpotlightPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val isSheetStep = currentStep == OnboardingStep.SIMULATE_FETCH_DIALOG ||
            currentStep == OnboardingStep.SIMULATE_CHOOSE_MODEL

        // 针对目标形态（图标小按钮 vs 宽组件）自适应计算最佳聚焦尺寸与安全边距，杜绝出界切边
        val (optimalTargetRect, holeRadiusPx) = remember(targetRect, screenWidthPx, screenHeightPx, density, isSheetStep) {
            if (targetRect != null && !isSheetStep) {
                calculateOptimalHoleBounds(targetRect, screenWidthPx, screenHeightPx, density)
            } else {
                Rect.Zero to with(density) { 12.dp.toPx() }
            }
        }

        // 聚光灯位置与透明度状态机
        val holeLeft = remember { Animatable(0f) }
        val holeTop = remember { Animatable(0f) }
        val holeRight = remember { Animatable(0f) }
        val holeBottom = remember { Animatable(0f) }
        val spotlightAlpha = remember { Animatable(0f) }
        var isFirstTarget by remember { mutableStateOf(true) }
        var lastTargetRect by remember { mutableStateOf<Rect?>(null) }

        LaunchedEffect(optimalTargetRect, targetRect, isSheetStep) {
            if (isSheetStep || targetRect == null) {
                spotlightAlpha.animateTo(0f, animationSpec = tween(200))
            } else {
                val last = lastTargetRect
                val isBigJump = last == null ||
                    hypot(
                        (optimalTargetRect.center.x - last.center.x).toDouble(),
                        (optimalTargetRect.center.y - last.center.y).toDouble()
                    ) > with(density) { 240.dp.toPx() }

                if (isFirstTarget || isBigJump) {
                    // 首次出现或跨页面大跳跃：
                    // 立即对准新目标位置，在目标处淡入展开（消除全屏斜向拖拽拉扯的奇怪动画）
                    holeLeft.snapTo(optimalTargetRect.left)
                    holeTop.snapTo(optimalTargetRect.top)
                    holeRight.snapTo(optimalTargetRect.right)
                    holeBottom.snapTo(optimalTargetRect.bottom)
                    isFirstTarget = false
                    spotlightAlpha.animateTo(1f, animationSpec = tween(260, easing = FastOutSlowInEasing))
                } else {
                    // 同一区域内的近距离切换：平滑过渡到位
                    launch { holeLeft.animateTo(optimalTargetRect.left, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeTop.animateTo(optimalTargetRect.top, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeRight.animateTo(optimalTargetRect.right, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeBottom.animateTo(optimalTargetRect.bottom, tween(300, easing = FastOutSlowInEasing)) }
                    if (spotlightAlpha.value < 1f) {
                        launch { spotlightAlpha.animateTo(1f, tween(200)) }
                    }
                }
                lastTargetRect = optimalTargetRect
            }
        }

        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val scrimColor = if (isLight) {
            Color.Black.copy(alpha = 0.40f)
        } else {
            Color.Black.copy(alpha = 0.52f)
        }

        val primaryColor = MaterialTheme.colorScheme.primary

        // 1. 全屏 Canvas：柔和半透明遮罩 + 挖孔清除 + 高亮微光边框
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(targetRect, optimalTargetRect, onTargetClick) {
                    detectTapGestures { offset ->
                        // 如果点击落在当前高亮区域内，优先响应高亮目标行为
                        if (targetRect != null && spotlightAlpha.value > 0.3f) {
                            val inHole = offset.x in optimalTargetRect.left..optimalTargetRect.right &&
                                offset.y in optimalTargetRect.top..optimalTargetRect.bottom
                            if (inHole) {
                                onTargetClick?.invoke() ?: onNext()
                                return@detectTapGestures
                            }
                        }
                        // 遮罩外部点击拦截消费，防止误触背后元素
                    }
                }
        ) {
            // 半透明遮罩
            drawRect(color = scrimColor)

            if (spotlightAlpha.value > 0f) {
                val currentAlpha = spotlightAlpha.value
                val activeHoleLeft = holeLeft.value
                val activeHoleTop = holeTop.value
                val activeHoleWidth = holeRight.value - holeLeft.value
                val activeHoleHeight = holeBottom.value - holeTop.value

                if (activeHoleWidth > 0f && activeHoleHeight > 0f) {
                    // 挖孔
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        blendMode = BlendMode.Clear
                    )
                    // 高亮聚焦外边框（实线微光）
                    drawRoundRect(
                        color = primaryColor.copy(alpha = pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // 外层微晕光泽（两倍发光半径）
                    val glowPadding = 2.dp.toPx()
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.20f * pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft - glowPadding, activeHoleTop - glowPadding),
                        size = Size(activeHoleWidth + glowPadding * 2, activeHoleHeight + glowPadding * 2),
                        cornerRadius = CornerRadius(holeRadiusPx + glowPadding, holeRadiusPx + glowPadding),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        // 2. 说明指示小卡片：根据实测高度精确锚定，杜绝与聚光灯重叠
        val cardWidthDp = 310.dp
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        var actualCardHeightPx by remember { mutableFloatStateOf(with(density) { 210.dp.toPx() }) }

        val targetCenterX = if (targetRect != null && spotlightAlpha.value > 0.1f) {
            (holeLeft.value + holeRight.value) / 2f
        } else {
            screenWidthPx * 0.5f
        }

        val cardLeftPx = if (isSheetStep) {
            (screenWidthPx - cardWidthPx) / 2f
        } else {
            (targetCenterX - cardWidthPx / 2f).coerceIn(
                cardMarginPx,
                (screenWidthPx - cardWidthPx - cardMarginPx).coerceAtLeast(cardMarginPx)
            )
        }

        val isBottomHalf = targetRect != null && ((holeTop.value + holeBottom.value) / 2f > screenHeightPx * 0.52f)
        val cardTopPx = if (isSheetStep) {
            // 底部弹窗步骤：弹窗占据下半屏，卡片稳稳居中悬浮在屏幕上半区安全区域，绝不被弹窗遮挡
            (screenHeightPx * 0.10f).coerceIn(
                cardMarginPx + with(density) { 36.dp.toPx() },
                (screenHeightPx * 0.38f - actualCardHeightPx).coerceAtLeast(cardMarginPx + with(density) { 36.dp.toPx() })
            )
        } else if (targetRect == null || spotlightAlpha.value < 0.1f) {
            screenHeightPx * 0.38f
        } else if (isBottomHalf) {
            // 目标处于屏幕下半区：卡片居于目标上方，卡片底边距离高亮顶边严格保留 cardGapPx
            (holeTop.value - cardGapPx - actualCardHeightPx).coerceAtLeast(cardMarginPx)
        } else {
            // 目标处于屏幕上半区：卡片居于目标下方，卡片顶边距离高亮底边严格保留 cardGapPx
            (holeBottom.value + cardGapPx).coerceAtMost(screenHeightPx - actualCardHeightPx - cardMarginPx)
        }

        val animCardX by animateFloatAsState(
            targetValue = cardLeftPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardX"
        )
        val animCardY by animateFloatAsState(
            targetValue = cardTopPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardY"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(animCardX.roundToInt(), animCardY.roundToInt()) }
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        actualCardHeightPx = size.height.toFloat()
                    }
                }
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 40)) + slideInVertically(tween(200)) { it / 8 })
                        .togetherWith(fadeOut(tween(130)))
                },
                label = "CardContentTransition"
            ) { step ->
                SpotlightCard(
                    step = step,
                    onNext = onNext,
                    onSkip = onSkip,
                    modifier = Modifier.widthIn(max = cardWidthDp)
                )
            }
        }
    }
}

/**
 * 契合应用开发工具风格的高质感指示说明小卡片。
 */
@Composable
private fun SpotlightCard(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            // 步骤徽章：极简药丸胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_step_badge,
                            step.stepIndex,
                            OnboardingStep.totalSteps
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // 步骤标题
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // 步骤说明描述
            Text(
                text = stringResource(step.descRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // 底部操作区：跳过 + 下一步/完成
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSkip,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(if (step.isLastStep) R.string.onboarding_done else R.string.onboarding_next),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 根据目标元素在根视图中的边界，结合屏幕边界与组件特征计算最佳聚焦挖孔区域。
 *
 * 1. 紧凑型小目标（如 IconButton 顶栏按钮、模型切换键）：以其几何视觉中心对称聚焦，
 *    避免 48dp 交互热区叠加 padding 造成大方块撞墙截断；
 * 2. 宽型大目标（输入框、设置行）：保持外围 padding 并进行屏幕边缘防溢出夹紧；
 * 3. 屏幕边缘留有安全间距 [minEdgeMarginPx]，确保圆角和呼吸描边 100% 完整可见，绝不出界截断。
 */
private fun calculateOptimalHoleBounds(
    target: Rect,
    screenWidth: Float,
    screenHeight: Float,
    density: androidx.compose.ui.unit.Density
): Pair<Rect, Float> {
    val smallTargetThresholdPx = with(density) { 56.dp.toPx() }
    val minEdgeMarginPx = with(density) { 6.dp.toPx() }

    val isSmallTarget = target.width <= smallTargetThresholdPx && target.height <= smallTargetThresholdPx

    return if (isSmallTarget) {
        val targetSizePx = with(density) { 42.dp.toPx() }
        val halfSize = targetSizePx / 2f
        val centerX = target.center.x
        val centerY = target.center.y

        // 确保不会超出屏幕边缘，保留 minEdgeMarginPx
        val left = (centerX - halfSize).coerceIn(minEdgeMarginPx, (screenWidth - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val top = (centerY - halfSize).coerceIn(minEdgeMarginPx, (screenHeight - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val rect = Rect(left, top, left + targetSizePx, top + targetSizePx)
        val cornerRadiusPx = with(density) { 10.dp.toPx() }
        rect to cornerRadiusPx
    } else {
        val paddingPx = with(density) { 4.dp.toPx() }
        val left = (target.left - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val top = (target.top - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val right = (target.right + paddingPx).coerceAtMost(screenWidth - minEdgeMarginPx).coerceAtLeast(left)
        val bottom = (target.bottom + paddingPx).coerceAtMost(screenHeight - minEdgeMarginPx).coerceAtLeast(top)
        val rect = Rect(left, top, right, bottom)
        val cornerRadiusPx = with(density) { 12.dp.toPx() }
        rect to cornerRadiusPx
    }
}
