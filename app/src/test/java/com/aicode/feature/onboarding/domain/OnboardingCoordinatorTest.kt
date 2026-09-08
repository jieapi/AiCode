package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCoordinatorTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `initialize with IN_PROGRESS starts stateHolder`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        assertTrue(stateHolder.isActive)
        assertEquals(OnboardingStep.OPEN_SIDEBAR, stateHolder.state.value.step)
    }

    @Test
    fun `initialize with COMPLETED does not start stateHolder`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.COMPLETED)
        assertFalse(stateHolder.isActive)
    }

    @Test
    fun `nextStep through to end persists completed`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        // 依次推进所有步骤直到完成
        while (stateHolder.isActive) {
            coordinator.nextStep()
        }

        assertFalse(stateHolder.isActive)
        assertEquals(OnboardingStatus.COMPLETED, stateHolder.state.value.status)
        assertTrue(persisted.contains(OnboardingStatus.COMPLETED))
    }

    @Test
    fun `skip deactivates and persists skipped`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        coordinator.skip()

        assertFalse(stateHolder.isActive)
        assertEquals(OnboardingStatus.SKIPPED, stateHolder.state.value.status)
        assertEquals(listOf(OnboardingStatus.SKIPPED), persisted)
    }

    @Test
    fun `resetAndStart activates and persists IN_PROGRESS`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.resetAndStart()

        assertTrue(stateHolder.isActive)
        assertEquals(OnboardingStep.OPEN_SIDEBAR, stateHolder.state.value.step)
        assertEquals(listOf(OnboardingStatus.IN_PROGRESS), persisted)
    }
}
