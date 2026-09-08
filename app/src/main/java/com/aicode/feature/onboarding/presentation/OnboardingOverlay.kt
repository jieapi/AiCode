package com.aicode.feature.onboarding.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aicode.feature.onboarding.domain.OnboardingCoordinator
import com.aicode.feature.onboarding.domain.OnboardingStateHolder

/**
 * 新手聚焦引导总覆层（悬浮在页面最顶层）。
 */
@Composable
fun OnboardingOverlay(
    stateHolder: OnboardingStateHolder,
    coordinator: OnboardingCoordinator,
    registry: OnboardingTargetRegistry,
    modifier: Modifier = Modifier,
    onNextStep: (() -> Unit)? = null,
    onTargetClick: (() -> Unit)? = null
) {
    val state by stateHolder.state.collectAsState()
    if (!state.active) return

    val currentStep = state.step
    val targetRect = registry.targetFor(currentStep)

    SpotlightOverlay(
        currentStep = currentStep,
        targetRect = targetRect,
        onNext = { onNextStep?.invoke() ?: coordinator.nextStep() },
        onSkip = { coordinator.skip() },
        onTargetClick = onTargetClick,
        modifier = modifier
    )
}
