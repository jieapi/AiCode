package com.aicode.feature.onboarding.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.onboarding.domain.OnboardingStep
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu
import kotlin.math.roundToInt

/**
 * 全屏聚光灯新手引导遮罩层（Spotlight Overlay）。
 *
 * 1. 统一深色半透明遮罩 + 硬件加速圆角挖孔（BlendMode.Clear），无缝融入应用深浅主题；
 * 2. 聚焦目标边缘带有柔和呼吸微光边框；
 * 3. 在拉取模型与模型选择阶段，直接在遮罩层上模拟呈现演示面板，不侵入修改真实数据，不弹独立系统窗口；
 * 4. 悬浮精致 Material 3 说明小卡片，包含步骤角标、标题、说明文案、“跳过”与“下一步/完成”按钮；
 * 5. 全程常驻顶层，平滑插值过渡，页面切换绝无闪烁。
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
    val holePaddingPx = with(density) { 6.dp.toPx() }
    val holeRadiusPx = with(density) { 12.dp.toPx() }
    val cardMarginPx = with(density) { 16.dp.toPx() }
    val cardGapPx = with(density) { 12.dp.toPx() }

    // 呼吸灯动画：在高亮描边周围产生柔和微光脉冲
    val infiniteTransition = rememberInfiniteTransition(label = "SpotlightPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val isSimulatedStep = currentStep == OnboardingStep.SIMULATE_FETCH_DIALOG ||
        currentStep == OnboardingStep.SIMULATE_CHOOSE_MODEL

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        // 仅在非纯模拟步骤且目标有效时计算挖孔
        val animLeft by animateFloatAsState(
            targetValue = targetRect?.left ?: (screenWidthPx * 0.5f - 40f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "HoleLeft"
        )
        val animTop by animateFloatAsState(
            targetValue = targetRect?.top ?: (screenHeightPx * 0.4f - 40f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "HoleTop"
        )
        val animRight by animateFloatAsState(
            targetValue = targetRect?.right ?: (screenWidthPx * 0.5f + 40f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "HoleRight"
        )
        val animBottom by animateFloatAsState(
            targetValue = targetRect?.bottom ?: (screenHeightPx * 0.4f + 40f),
            animationSpec = tween(350, easing = FastOutSlowInEasing),
            label = "HoleBottom"
        )

        val activeHoleLeft = animLeft - holePaddingPx
        val activeHoleTop = animTop - holePaddingPx
        val activeHoleWidth = (animRight - animLeft) + holePaddingPx * 2
        val activeHoleHeight = (animBottom - animTop) + holePaddingPx * 2

        val primaryColor = MaterialTheme.colorScheme.primary

        // 1. 全屏 Canvas：半透明遮罩 + 挖孔清除 + 高亮边框
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(targetRect, isSimulatedStep, onTargetClick) {
                    detectTapGestures { offset ->
                        // 如果点击落在当前高亮区域内，优先响应高亮目标行为
                        if (!isSimulatedStep && targetRect != null) {
                            val inHole = offset.x in (targetRect.left - holePaddingPx)..(targetRect.right + holePaddingPx) &&
                                offset.y in (targetRect.top - holePaddingPx)..(targetRect.bottom + holePaddingPx)
                            if (inHole) {
                                onTargetClick?.invoke() ?: onNext()
                                return@detectTapGestures
                            }
                        }
                        // 遮罩外部点击拦截消费，防止误触背后元素
                    }
                }
        ) {
            // 半透明暗色遮罩
            drawRect(color = Color.Black.copy(alpha = 0.65f))

            if (!isSimulatedStep && targetRect != null) {
                // 挖孔
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(activeHoleLeft, activeHoleTop),
                    size = Size(activeHoleWidth, activeHoleHeight),
                    cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                    blendMode = BlendMode.Clear
                )
                // 高亮聚焦外边框（带呼吸脉冲）
                drawRoundRect(
                    color = primaryColor.copy(alpha = pulseAlpha),
                    topLeft = Offset(activeHoleLeft, activeHoleTop),
                    size = Size(activeHoleWidth, activeHoleHeight),
                    cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // 2. 纯模拟步骤：直接在蒙版上渲染演示面板
        if (isSimulatedStep) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    if (currentStep == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                        SimulatedSheetCard(
                            title = stringResource(R.string.provider_fetch_models),
                            modelName = "claude-3-5-sonnet",
                            subtitle = "Anthropic · 智能编程推荐模型",
                            actionText = stringResource(R.string.common_add),
                            pulseAlpha = pulseAlpha,
                            onSelect = onNext
                        )
                    } else if (currentStep == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                        SimulatedSheetCard(
                            title = stringResource(R.string.common_model),
                            modelName = "claude-3-5-sonnet",
                            subtitle = "当前推荐 · 具备代码生成与分析能力",
                            actionText = stringResource(R.string.common_select),
                            pulseAlpha = pulseAlpha,
                            onSelect = onNext
                        )
                    }

                    // 悬浮指示小卡片
                    SpotlightCard(
                        step = currentStep,
                        onNext = onNext,
                        onSkip = onSkip,
                        modifier = Modifier.widthIn(max = 340.dp)
                    )
                }
            }
        } else {
            // 3. 常规步骤说明小卡片：自适应计算位置
            val cardWidthDp = 300.dp
            val cardWidthPx = with(density) { cardWidthDp.toPx() }

            // 水平对齐高亮中心，并向内限制边界
            val targetCenterX = (activeHoleLeft + activeHoleWidth / 2f)
            val cardLeftPx = (targetCenterX - cardWidthPx / 2f).coerceIn(
                cardMarginPx,
                (screenWidthPx - cardWidthPx - cardMarginPx).coerceAtLeast(cardMarginPx)
            )

            // 垂直定位：优先放在目标下方；若处于下半屏（如底部输入框）则放在目标上方
            val isBottomAligned = targetRect != null && (targetRect.top > screenHeightPx * 0.55f)
            val cardTopPx = if (targetRect == null) {
                screenHeightPx * 0.4f
            } else if (isBottomAligned) {
                // 目标上方
                val estimatedCardHeightPx = with(density) { 180.dp.toPx() }
                (activeHoleTop - cardGapPx - estimatedCardHeightPx).coerceAtLeast(cardMarginPx)
            } else {
                // 目标下方
                (activeHoleTop + activeHoleHeight + cardGapPx).coerceAtMost(screenHeightPx - with(density) { 200.dp.toPx() })
            }

            val animCardX by animateFloatAsState(
                targetValue = cardLeftPx,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                label = "CardX"
            )
            val animCardY by animateFloatAsState(
                targetValue = cardTopPx,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                label = "CardY"
            )

            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.offset {
                    IntOffset(animCardX.roundToInt(), animCardY.roundToInt())
                }
            ) {
                SpotlightCard(
                    step = currentStep,
                    onNext = onNext,
                    onSkip = onSkip,
                    modifier = Modifier.widthIn(max = cardWidthDp)
                )
            }
        }
    }
}

/**
 * 模拟弹出的模型列表面板（拉取模型 / 主页模型选择演示用）。
 */
@Composable
private fun SimulatedSheetCard(
    title: String,
    modelName: String,
    subtitle: String,
    actionText: String,
    pulseAlpha: Float,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 340.dp),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(Radius.xs),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "DEMO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // 模拟高亮模型行
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelect),
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    FeatherIcons.Cpu,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(Spacing.md))
                        Column {
                            Text(
                                text = modelName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onSelect,
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(text = actionText, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * 契合 Material 3 风格的高质感指示说明小卡片。
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            // 步骤徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.xs),
                    color = MaterialTheme.colorScheme.primaryContainer
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // 底部操作区：跳过 + 下一步/完成
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
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
                    )
                ) {
                    Text(
                        text = stringResource(if (step.isLastStep) R.string.onboarding_done else R.string.onboarding_next),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
