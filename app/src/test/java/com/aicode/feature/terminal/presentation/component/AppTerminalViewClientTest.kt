package com.aicode.feature.terminal.presentation.component

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
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
class AppTerminalViewClientTest {
    private var fontSizeSp = 12
    private val changes = mutableListOf<Int>()
    private val commits = mutableListOf<Int>()
    private lateinit var client: AppTerminalViewClient

    @Before
    fun setUp() {
        client = AppTerminalViewClient(
            context = ApplicationProvider.getApplicationContext<Context>(),
            viewProvider = { null },
            modifiers = TerminalKeyModifiers(),
            currentFontSizeSp = { fontSizeSp },
            onFontSizeChange = {
                fontSizeSp = it
                changes += it
            },
            onFontSizeCommit = { commits += it }
        )
    }

    @Test
    fun pinch_changes_font_before_release_and_saves_only_on_release() {
        client.onScaleBegin()
        client.onScale(1.1f)

        assertEquals(listOf(13), changes)
        assertEquals(emptyList<Int>(), commits)

        client.onScaleEnd()

        assertEquals(listOf(13), changes)
        assertEquals(listOf(13), commits)
    }

    @Test
    fun small_movements_accumulate_without_rounding_each_increment() {
        client.onScaleBegin()
        client.onScale(1.01f)
        client.onScale(1.02f)
        assertEquals(emptyList<Int>(), changes)

        client.onScale(1.02f)
        assertEquals(listOf(13), changes)
    }

    @Test
    fun frequent_events_are_coalesced_before_resizing_again() {
        client.onScaleBegin()
        client.onScale(1.1f)
        client.onScale(1.1f)
        assertEquals(listOf(13), changes)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        client.onScale(1.1f)
        assertEquals(listOf(13, 16), changes)

        client.onScaleEnd()
        assertEquals(listOf(13, 16), changes)
        assertEquals(listOf(16), commits)
    }

    @Test
    fun release_applies_last_pending_size_before_saving() {
        client.onScaleBegin()
        client.onScale(1.1f)
        client.onScale(1.1f)
        assertEquals(13, fontSizeSp)

        client.onScaleEnd()

        assertEquals(15, fontSizeSp)
        assertEquals(listOf(13, 15), changes)
        assertEquals(listOf(15), commits)
    }

    @Test
    fun reversing_at_upper_limit_changes_font_without_overshoot_dead_zone() {
        client.onScaleBegin()
        client.onScale(10f)
        assertEquals(22, fontSizeSp)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        client.onScale(0.95f)
        assertEquals(21, fontSizeSp)
    }

    @Test
    fun reversing_at_lower_limit_changes_font_without_overshoot_dead_zone() {
        client.onScaleBegin()
        client.onScale(0.01f)
        assertEquals(5, fontSizeSp)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        client.onScale(1.2f)
        assertEquals(6, fontSizeSp)
    }

    @Test
    fun unchanged_font_does_not_resize_or_save() {
        client.onScaleBegin()
        client.onScale(1.01f)
        client.onScaleEnd()

        assertEquals(emptyList<Int>(), changes)
        assertEquals(emptyList<Int>(), commits)
    }

    @Test
    fun returning_to_initial_font_restores_it_without_saving() {
        client.onScaleBegin()
        client.onScale(1.5f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
        client.onScale(2f / 3f)
        client.onScaleEnd()

        assertEquals(listOf(18, 12), changes)
        assertEquals(emptyList<Int>(), commits)
    }

    @Test
    fun next_pinch_starts_from_current_font() {
        client.onScaleBegin()
        client.onScale(1.5f)
        client.onScaleEnd()
        client.onScaleBegin()
        client.onScale(0.5f)
        client.onScaleEnd()

        assertEquals(listOf(18, 9), changes)
        assertEquals(listOf(18, 9), commits)
    }
}
