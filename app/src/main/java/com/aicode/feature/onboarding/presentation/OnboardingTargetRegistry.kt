package com.aicode.feature.onboarding.presentation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aicode.feature.onboarding.domain.OnboardingStep

/**
 * 引导目标组件注册表：集中记录各步骤高亮组件在 Root 坐标系中的边界矩形。
 */
class OnboardingTargetRegistry {
    private val _targets = mutableStateMapOf<OnboardingStep, Rect>()
    val targets: Map<OnboardingStep, Rect> get() = _targets

    fun register(step: OnboardingStep, rect: Rect) {
        _targets[step] = rect
    }

    fun unregister(step: OnboardingStep) {
        _targets.remove(step)
    }

    fun targetFor(step: OnboardingStep): Rect? = _targets[step]
}

/**
 * 全局 CompositionLocal，便于深层组件无感知上报高亮位置。
 */
val LocalOnboardingTargetRegistry = compositionLocalOf<OnboardingTargetRegistry?> { null }

/**
 * 快捷 Modifier：为目标组件打标并在布局完成后自动上报坐标边界。
 */
fun Modifier.onboardingTarget(step: OnboardingStep): Modifier = composed {
    val registry = LocalOnboardingTargetRegistry.current
    if (registry == null) {
        this
    } else {
        this.onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
                registry.register(step, coordinates.boundsInRoot())
            }
        }
    }
}
