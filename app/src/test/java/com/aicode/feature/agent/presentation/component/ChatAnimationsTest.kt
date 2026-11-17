package com.aicode.feature.agent.presentation.component

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatAnimationsTest {
    @Test
    fun lifecycle_only_animates_while_resumed_and_stops_after_destroy() = runTest {
        val animation = AnimationComposition(this, Lifecycle.State.INITIALIZED)
        try {
            for (state in listOf(
                Lifecycle.State.INITIALIZED,
                Lifecycle.State.CREATED,
                Lifecycle.State.STARTED,
                Lifecycle.State.RESUMED,
                Lifecycle.State.STARTED,
                Lifecycle.State.CREATED,
                Lifecycle.State.DESTROYED
            )) {
                animation.owner.lifecycle.currentState = state
                animation.settle()

                val resumed = state == Lifecycle.State.RESUMED
                assertEquals(state.name, resumed, animation.animationsEnabled)
                assertEquals(state.name, resumed, animation.frameClock.hasAwaiters)
                if (!resumed) {
                    assertEquals(1f, animation.value.value, 0f)
                    animation.frame(1_000)
                    assertFalse(state.name, animation.frameClock.hasAwaiters)
                    assertEquals(1f, animation.value.value, 0f)
                }
            }
        } finally {
            animation.close()
        }
    }

    @Test
    fun changing_local_keeps_composition_but_removes_frame_requests_and_restores_animation() = runTest {
        val animation = AnimationComposition(this, Lifecycle.State.RESUMED, visible = false)
        try {
            animation.settle()
            val token = animation.contentToken
            val pausedState = animation.value
            assertFalse(animation.animationsEnabled)
            assertFalse(animation.frameClock.hasAwaiters)

            animation.pausedValue.value = 0.8f
            animation.settle()
            assertSame(pausedState, animation.value)
            assertEquals(0.8f, animation.value.value, 0f)
            assertFalse(animation.frameClock.hasAwaiters)

            repeat(2) {
                animation.visible.value = true
                animation.settle()
                assertTrue(animation.animationsEnabled)
                assertTrue(animation.frameClock.hasAwaiters)
                animation.frame(325)
                assertTrue(animation.value.value in 0.25f..0.99f)
                assertSame(token, animation.contentToken)

                animation.visible.value = false
                animation.settle()
                assertFalse(animation.frameClock.hasAwaiters)
                assertEquals(0.8f, animation.value.value, 0f)
                animation.frame(2_000)
                assertEquals(0.8f, animation.value.value, 0f)
                assertFalse(animation.frameClock.hasAwaiters)
                assertSame(token, animation.contentToken)
            }
        } finally {
            animation.close()
        }
    }

    @Test
    fun background_lifecycle_stays_paused_when_local_is_reenabled() = runTest {
        val animation = AnimationComposition(this, Lifecycle.State.RESUMED)
        try {
            animation.settle()
            assertTrue(animation.frameClock.hasAwaiters)

            animation.visible.value = false
            animation.owner.lifecycle.currentState = Lifecycle.State.STARTED
            animation.settle()
            animation.visible.value = true
            animation.settle()
            assertFalse(animation.animationsEnabled)
            assertFalse(animation.frameClock.hasAwaiters)
            assertEquals(1f, animation.value.value, 0f)

            animation.owner.lifecycle.currentState = Lifecycle.State.RESUMED
            animation.settle()
            animation.frame(325)
            assertTrue(animation.animationsEnabled)
            assertTrue(animation.value.value in 0.25f..0.99f)
        } finally {
            animation.close()
        }
    }

    @Test
    fun inactive_component_has_no_animation_and_animation_value_does_not_recompose_consumer() = runTest {
        val animation = AnimationComposition(this, Lifecycle.State.RESUMED)
        try {
            animation.running.value = false
            animation.settle()
            assertFalse(animation.frameClock.hasAwaiters)
            assertEquals(1f, animation.value.value, 0f)

            animation.running.value = true
            animation.settle()
            val passes = animation.compositionPasses
            val firstValue = animation.value.value
            repeat(8) { animation.frame(32) }
            assertTrue(animation.value.value < firstValue)
            assertEquals(passes, animation.compositionPasses)

            animation.running.value = false
            animation.settle()
            assertFalse(animation.frameClock.hasAwaiters)
            assertEquals(1f, animation.value.value, 0f)
        } finally {
            animation.close()
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class AnimationComposition(
        private val scope: TestScope,
        initialLifecycleState: Lifecycle.State,
        visible: Boolean = true
    ) : AutoCloseable {
        val owner = TestLifecycleOwner()
        val visible = mutableStateOf(visible)
        val running = mutableStateOf(true)
        val pausedValue = mutableStateOf(1f)
        val frameClock = BroadcastFrameClock()
        var animationsEnabled = false
            private set
        lateinit var value: State<Float>
            private set
        lateinit var contentToken: Any
            private set
        var compositionPasses = 0
            private set
        private var frameNanos = 0L
        private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + frameClock)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)

        init {
            owner.lifecycle.currentState = initialLifecycleState
            scope.backgroundScope.launch(frameClock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            composition.setContent {
                CompositionLocalProvider(
                    LocalChatAnimationsEnabled provides this.visible.value,
                    LocalLifecycleOwner provides owner
                ) {
                    val token = remember { Any() }
                    val enabled = chatAnimationsEnabled()
                    val pulse = rememberChatInfiniteFloat(
                        initialValue = 1f,
                        targetValue = 0.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "test-pulse",
                        enabled = running.value,
                        pausedValue = pausedValue.value
                    )
                    SideEffect {
                        animationsEnabled = enabled
                        value = pulse
                        contentToken = token
                        compositionPasses++
                    }
                }
            }
        }

        fun settle() {
            repeat(4) { frame(16) }
        }

        fun frame(millis: Long) {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
            frameNanos += millis * 1_000_000L
            frameClock.sendFrame(frameNanos)
            scope.runCurrent()
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
        }

        override fun close() {
            composition.dispose()
            recomposer.cancel()
            scope.runCurrent()
        }
    }
}
