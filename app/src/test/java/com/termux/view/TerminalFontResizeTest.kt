package com.termux.view

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.terminal.presentation.component.AppTerminalViewClient
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TerminalFontResizeTest {
    private lateinit var view: TerminalView
    private lateinit var session: TerminalSession
    private lateinit var emulator: TerminalEmulator
    private var fontSizeSp = 12
    private val commits = mutableListOf<Int>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        emulator = TerminalEmulator(mockk(relaxed = true), 80, 24, 2000, mockk(relaxed = true))
        session = mockk(relaxed = true)
        every { session.emulator } returns emulator
        every { session.updateSize(any(), any()) } answers {
            emulator.resize(firstArg(), secondArg())
        }
        view = TerminalView(context, null)
        view.setTerminalViewClient(
            AppTerminalViewClient(
                context = context,
                viewProvider = { view },
                modifiers = TerminalKeyModifiers(),
                currentFontSizeSp = { fontSizeSp },
                onFontSizeChange = {
                    fontSizeSp = it
                    view.setTextSize(it * 2)
                },
                onFontSizeCommit = { commits += it }
            )
        )
        view.setTextSize(fontSizeSp * 2)
        view.layout(0, 0, 480, 800)
        view.attachSession(session)
    }

    @Test
    fun pinch_changes_renderer_and_terminal_grid_before_release() {
        val oldColumns = emulator.mColumns
        val oldRows = emulator.mRows
        val listener = view.mGestureRecognizer.mListener

        assertTrue(listener.onScaleBegin())
        listener.onScale(1.5f)

        assertEquals(36, view.mRenderer.mTextSize)
        assertTrue(emulator.mColumns < oldColumns)
        assertTrue(emulator.mRows < oldRows)
        assertEquals(emptyList<Int>(), commits)

        listener.onScaleEnd()
        assertEquals(listOf(18), commits)
    }

    @Test
    fun same_text_size_keeps_renderer_and_does_not_resize_session() {
        val renderer = view.mRenderer
        clearMocks(session, answers = false)

        view.setTextSize(24)
        view.setTextSize(24)

        assertSame(renderer, view.mRenderer)
        verify(exactly = 0) { session.updateSize(any(), any()) }
    }

    @Test
    fun new_touch_stops_previous_fling_and_clears_scroll_remainder() {
        view.mScroller.fling(0, 0, 0, 2000, 0, 0, 0, 1000)
        view.mScrollRemainder = 10f

        view.mGestureRecognizer.mListener.onDown(100f, 100f)

        assertTrue(view.mScroller.isFinished)
        assertEquals(0f, view.mScrollRemainder, 0f)
    }
}
