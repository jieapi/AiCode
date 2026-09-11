package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateHolderTest {

    private fun holder(): OnboardingStateHolder = OnboardingStateHolder()

    @Test
    fun `start activates from OPEN_SIDEBAR`() {
        val h = holder()
        h.start()
        assertTrue(h.isActive)
        assertEquals(OnboardingStep.OPEN_SIDEBAR, h.state.value.step)
        assertEquals(OnboardingStatus.IN_PROGRESS, h.state.value.status)
    }

    @Test
    fun `nextStep progresses through full ten-step flow`() {
        val h = holder()
        h.start()
        assertEquals(OnboardingStep.OPEN_SIDEBAR, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.ENTER_SETTINGS, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.CONFIG_PROVIDER, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.PROVIDER_ADD, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.PROVIDER_CONFIG_INFO, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.PROVIDER_FETCH_MODELS, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.SIMULATE_FETCH_DIALOG, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.OPEN_MODEL_PICKER, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.SIMULATE_CHOOSE_MODEL, h.state.value.step)

        h.nextStep()
        assertEquals(OnboardingStep.SEND_MESSAGE, h.state.value.step)
        assertTrue(h.isActive)

        h.nextStep() // complete on last step
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.COMPLETED, h.state.value.status)
    }

    @Test
    fun `goToStep changes current step when active`() {
        val h = holder()
        h.start()
        h.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
        assertEquals(OnboardingStep.PROVIDER_FETCH_MODELS, h.state.value.step)

        h.goToStep(OnboardingStep.SIMULATE_FETCH_DIALOG)
        assertEquals(OnboardingStep.SIMULATE_FETCH_DIALOG, h.state.value.step)

        // 模拟关闭弹窗回退
        h.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
        assertEquals(OnboardingStep.PROVIDER_FETCH_MODELS, h.state.value.step)
        assertTrue(h.isActive)
    }

    @Test
    fun `skip deactivates and marks skipped`() {
        val h = holder()
        h.start()
        h.skip()
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.SKIPPED, h.state.value.status)
    }

    @Test
    fun `completeNow marks completed immediately`() {
        val h = holder()
        h.start()
        h.completeNow()
        assertFalse(h.isActive)
        assertEquals(OnboardingStatus.COMPLETED, h.state.value.status)
    }

    @Test
    fun `nextStep does nothing when inactive`() {
        val h = holder()
        h.nextStep()
        assertFalse(h.isActive)
    }
}
