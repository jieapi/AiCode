package com.aicode.feature.plugin.overlay

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.plugin.domain.PluginOverlaySnap
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginOverlayWindowTest {
    private lateinit var window: PluginOverlayWindow
    private lateinit var webView: WebView
    private lateinit var windowManager: WindowManager
    private var anchorX = 0f
    private var anchorY = 0f

    @Before
    fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        windowManager = mockk(relaxed = true)
        window = createWindow(PluginOverlaySnap.NONE)
        anchorX = position("x")
        anchorY = position("y")
    }

    private fun createWindow(snap: String): PluginOverlayWindow {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = context.resources.displayMetrics
        val size = (48 * metrics.density).toInt()
        val params = WindowManager.LayoutParams().apply {
            width = size
            height = size
            x = metrics.widthPixels - size
            y = metrics.heightPixels - size
        }
        val localWebView = WebView(context)
        webView = localWebView
        return PluginOverlayWindow(
            context = context,
            pluginId = "test.approval",
            overlayId = "test.approval:ball",
            windowManager = windowManager,
            layoutParams = params,
            webView = localWebView,
            draggable = true,
            snap = snap,
            initialVisibilityPolicy = "background",
            initialForeground = false,
            onPositionChanged = { _, _ -> }
        )
    }

    @After
    fun tearDown() {
        window.dispose()
    }

    @Test
    fun animatedResizeClampsPanelAndRestoresBallInTheSameWindow() {
        window.resize(320, 280, animate = true)
        settle()
        assertEquals(320 to 280, window.sizeDp())
        assertSame(webView, (window.getChildAt(0) as FrameLayout).getChildAt(0))
        assertTrue(position("x") <= anchorX)
        assertTrue(position("y") <= anchorY)

        window.resize(48, 48, animate = true, x = anchorX, y = anchorY)
        settle()
        assertEquals(48 to 48, window.sizeDp())
        assertEquals(anchorX, position("x"), .01f)
        assertEquals(anchorY, position("y"), .01f)
    }

    @Test
    fun animatedResizeOnlyRelayoutsTheSystemWindowAtItsEndpoints() {
        window.resize(320, 280, animate = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        verify(exactly = 1) { windowManager.updateViewLayout(window, any()) }
        val content = window.getChildAt(0)
        assertTrue(content.clipBounds != null)
        settle()
        verify(exactly = 2) { windowManager.updateViewLayout(window, any()) }
        assertEquals(null, content.clipBounds)

        clearMocks(windowManager, answers = false)
        window.resize(48, 48, animate = true, x = anchorX, y = anchorY)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        verify(exactly = 1) { windowManager.updateViewLayout(window, any()) }
        settle()
        verify(exactly = 2) { windowManager.updateViewLayout(window, any()) }
        assertEquals(anchorX, position("x"), .01f)
        assertEquals(anchorY, position("y"), .01f)
    }

    @Test
    fun aNewResizeCancelsThePreviousTarget() {
        window.resize(320, 280, animate = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        window.resize(48, 48, animate = true, x = anchorX, y = anchorY)
        settle()
        assertEquals(48 to 48, window.sizeDp())
        assertEquals(anchorX, position("x"), .01f)
        assertEquals(anchorY, position("y"), .01f)
    }

    @Test
    fun hiddenWindowsFinishAnimationsAndResizeImmediately() {
        window.resize(320, 280, animate = true)
        window.onAppForegroundChanged(true)
        assertEquals(320 to 280, window.sizeDp())
        window.resize(48, 48, animate = true)
        assertEquals(48 to 48, window.sizeDp())
        settle()
        assertEquals(48 to 48, window.sizeDp())
    }

    @Test
    fun draggingCannotContinueWithCoordinatesFromBeforeAnAnimation() {
        val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
            handle.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 10f, 10f)
        window.resize(320, 280, animate = true)
        settle()
        val x = position("x")
        val y = position("y")
        touch(MotionEvent.ACTION_MOVE, 100f, 100f)
        touch(MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(x, position("x"), .01f)
        assertEquals(y, position("y"), .01f)
    }

    @Test
    fun immediateResizeKeepsTheOriginalApiBehavior() {
        window.resize(320, 280)
        assertEquals(320 to 280, window.sizeDp())
        assertEquals(320, window.selfInfo()["width"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun dragReleaseSnapsToTheNearestHorizontalEdge() {
        window = createWindow(PluginOverlaySnap.X)
        ShadowChoreographer.setPaused(false)
        try {
            val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
            val startX = position("x")
            sendTouch(handle, MotionEvent.ACTION_DOWN, 20f, 20f)
            sendTouch(handle, MotionEvent.ACTION_MOVE, 20f - 250f * density(), 20f)
            assertEquals(startX - 250f, position("x"), .5f)
            sendTouch(handle, MotionEvent.ACTION_UP, 20f - 250f * density(), 20f)
            settle()
            assertEquals(0f, position("x"), .5f)
        } finally {
            ShadowChoreographer.setPaused(true)
        }
    }

    @Test
    fun dragReleaseFarFromEdgesKeepsTheDroppedPosition() {
        window = createWindow(PluginOverlaySnap.X)
        ShadowChoreographer.setPaused(false)
        try {
            val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
            val startX = position("x")
            sendTouch(handle, MotionEvent.ACTION_DOWN, 20f, 20f)
            sendTouch(handle, MotionEvent.ACTION_MOVE, 20f - 200f * density(), 20f)
            sendTouch(handle, MotionEvent.ACTION_UP, 20f - 200f * density(), 20f)
            settle()
            assertEquals(startX - 200f, position("x"), .5f)
        } finally {
            ShadowChoreographer.setPaused(true)
        }
    }

    @Test
    fun snapNoneKeepsTheDroppedPosition() {
        val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
        val startX = position("x")
        sendTouch(handle, MotionEvent.ACTION_DOWN, 20f, 20f)
        sendTouch(handle, MotionEvent.ACTION_MOVE, 20f - 200f * density(), 20f)
        sendTouch(handle, MotionEvent.ACTION_UP, 20f - 200f * density(), 20f)
        settle()
        assertEquals(startX - 200f, position("x"), .5f)
    }

    @Test
    fun resizeKeepsTheSnappedEdge() {
        window = createWindow(PluginOverlaySnap.X)
        ShadowChoreographer.setPaused(false)
        try {
            val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
            sendTouch(handle, MotionEvent.ACTION_DOWN, 20f, 20f)
            sendTouch(handle, MotionEvent.ACTION_MOVE, 20f - 400f * density(), 20f)
            sendTouch(handle, MotionEvent.ACTION_UP, 20f - 400f * density(), 20f)
            settle()
            assertEquals(0f, position("x"), .5f)
            window.resize(120, 48)
            settle()
            assertEquals(0f, position("x"), .5f)
        } finally {
            ShadowChoreographer.setPaused(true)
        }
    }

    @Test
    fun snapEventCarriesEdgeAndDpPosition() {
        window = createWindow(PluginOverlaySnap.X)
        ShadowChoreographer.setPaused(false)
        try {
            val handle = (window.getChildAt(0) as FrameLayout).getChildAt(1)
            sendTouch(handle, MotionEvent.ACTION_DOWN, 20f, 20f)
            sendTouch(handle, MotionEvent.ACTION_MOVE, 20f - 400f * density(), 20f)
            sendTouch(handle, MotionEvent.ACTION_UP, 20f - 400f * density(), 20f)
            settle()
            val script = shadowOf(webView).lastEvaluatedJavascript.orEmpty()
            assertTrue(script.contains("snap"))
            assertTrue(script.contains("left"))
        } finally {
            ShadowChoreographer.setPaused(true)
        }
    }

    private fun sendTouch(handle: android.view.View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        handle.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun density(): Float =
        ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density

    private fun position(key: String): Float = window.selfInfo().getValue(key).jsonPrimitive.floatOrNull!!

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
}
