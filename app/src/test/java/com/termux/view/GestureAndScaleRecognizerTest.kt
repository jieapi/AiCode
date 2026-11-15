package com.termux.view

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GestureAndScaleRecognizerTest {
    private lateinit var recognizer: GestureAndScaleRecognizer
    private lateinit var listener: GestureAndScaleRecognizer.Listener
    private var downTime = 0L

    @Before
    fun setUp() {
        listener = mockk(relaxed = true)
        every { listener.onDown(any(), any()) } returns true
        every { listener.onScaleBegin() } returns true
        every { listener.onScale(any()) } returns true
        recognizer = GestureAndScaleRecognizer(
            ApplicationProvider.getApplicationContext<Context>(), listener
        )
    }

    @Test
    fun pinch_and_remaining_finger_do_not_scroll_fling_click_or_long_press() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 400f)
        touch(MotionEvent.ACTION_MOVE, 80f, 440f, y = 130f)
        touch(MotionEvent.ACTION_MOVE, 60f, 500f, y = 190f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 60f, 500f, y = 190f)
        touch(MotionEvent.ACTION_MOVE, 60f, y = 300f)
        touch(MotionEvent.ACTION_UP, 60f, y = 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        verify(atLeast = 1) { listener.onScale(any()) }
        verify(exactly = 1) { listener.onScaleEnd() }
        verify(exactly = 0) { listener.onScroll(any(), any(), any()) }
        verify(exactly = 0) { listener.onFling(any(), any(), any()) }
        verify(exactly = 0) { listener.onUp(any()) }
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
        verify(exactly = 0) { listener.onLongPress(any()) }
    }

    @Test
    fun two_finger_tap_below_scale_threshold_is_not_a_terminal_click() {
        twoFingerTap()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        verify(exactly = 0) { listener.onScaleBegin() }
        verify(exactly = 0) { listener.onUp(any()) }
        verify(exactly = 0) { listener.onSingleTapUp(any()) }
        verify(exactly = 0) { listener.onLongPress(any()) }
    }

    @Test
    fun new_single_finger_tap_after_multitouch_still_works() {
        twoFingerTap()
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_UP, 50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        verify(exactly = 1) { listener.onUp(any()) }
        verify(exactly = 1) { listener.onSingleTapUp(any()) }
    }

    @Test
    fun new_single_finger_scroll_after_multitouch_still_works() {
        twoFingerTap()
        touch(MotionEvent.ACTION_DOWN, 50f)
        touch(MotionEvent.ACTION_MOVE, 50f, y = 200f)
        touch(MotionEvent.ACTION_UP, 50f, y = 200f)

        verify(atLeast = 1) { listener.onScroll(any(), any(), any()) }
    }

    @Test
    fun single_finger_long_press_still_suppresses_release_click() {
        touch(MotionEvent.ACTION_DOWN, 50f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(MotionEvent.ACTION_UP, 50f)

        verify(exactly = 1) { listener.onLongPress(any()) }
        verify(exactly = 0) { listener.onUp(any()) }
    }

    private fun twoFingerTap() {
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f, 120f)
        touch(MotionEvent.ACTION_UP, 100f)
    }

    private fun touch(action: Int, vararg xs: Float, y: Float = 100f) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val eventTime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val properties = Array(xs.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(xs.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                this.y = y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, xs.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            recognizer.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
