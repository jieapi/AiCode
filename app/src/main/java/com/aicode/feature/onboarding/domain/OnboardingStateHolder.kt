package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 新手聚焦引导的 UI 运行状态。
 */
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.OPEN_SIDEBAR,
    val status: OnboardingStatus = OnboardingStatus.IN_PROGRESS
)

/**
 * 新手引导的状态机（纯内存状态，跨进程持久化由 OnboardingRepository 保障）。
 */
class OnboardingStateHolder {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** 启动引导：从第一步 [OnboardingStep.OPEN_SIDEBAR] 开始。 */
    fun start() {
        _state.value = OnboardingUiState(
            active = true,
            step = OnboardingStep.OPEN_SIDEBAR,
            status = OnboardingStatus.IN_PROGRESS
        )
    }

    /** 前进到下一步；如果是最后一步则完成引导。 */
    fun nextStep() {
        if (!_state.value.active) return
        val current = _state.value.step
        val next = current.next()
        if (next == null) {
            completeNow()
        } else {
            _state.value = _state.value.copy(step = next)
        }
    }

    /** 跳过引导。 */
    fun skip() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.SKIPPED
        )
    }

    /** 完成引导。 */
    fun completeNow() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.COMPLETED
        )
    }

    /** 当前是否处于引导中。 */
    val isActive: Boolean get() = _state.value.active
}

/** 返回当前步骤的下一步，若已是末步则返回 null。 */
internal fun OnboardingStep.next(): OnboardingStep? {
    val nextIndex = ordinal + 1
    return if (nextIndex < OnboardingStep.all.size) OnboardingStep.all[nextIndex] else null
}
