package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 新手引导协调器：连接 UI 状态机 [OnboardingStateHolder] 与持久化。
 */
class OnboardingCoordinator(
    private val stateHolder: OnboardingStateHolder,
    private val onPersist: suspend (OnboardingStatus) -> Unit,
    private val scope: CoroutineScope
) {
    /** 启动引导判定：仅当未完成/进行中时激活。 */
    fun initialize(status: OnboardingStatus) {
        if (status == OnboardingStatus.IN_PROGRESS) {
            stateHolder.start()
        }
    }

    /** 推进到下一步（或完成）。 */
    fun nextStep() {
        stateHolder.nextStep()
        val current = stateHolder.state.value
        scope.launch { onPersist(current.status) }
    }

    /** 用户主动跳过引导。 */
    fun skip() {
        stateHolder.skip()
        scope.launch { onPersist(OnboardingStatus.SKIPPED) }
    }

    /** 用户完成引导。 */
    fun complete() {
        stateHolder.completeNow()
        scope.launch { onPersist(OnboardingStatus.COMPLETED) }
    }

    /** 重置并重新启动引导（设置页触发）。 */
    fun resetAndStart() {
        stateHolder.start()
        scope.launch { onPersist(OnboardingStatus.IN_PROGRESS) }
    }
}
