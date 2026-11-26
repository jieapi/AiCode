package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 右侧消息导航条：轮盘预览文案与刻度/触控几何映射。 */
class ChatMessageNavigatorTest {

    @Test
    fun previewFlattensNewlinesAndTruncates() {
        val preview = navigationMarkerPreview("第一行\n第二行" + "补".repeat(30))
        assertFalse(preview.contains('\n'))
        assertTrue(preview.startsWith("第一行 第二行"))
        assertEquals(19, preview.length)
    }

    @Test
    fun shortPreviewIsKeptAsIs() {
        assertEquals("改一下颜色", navigationMarkerPreview("  改一下颜色 \n"))
    }

    @Test
    fun wheelAngleSweepsSymmetricallyFromTopLeftToBottomLeft() {
        val topRad = wheelAngleRadians(0f, 5)
        val midRad = wheelAngleRadians(2f, 5)
        val bottomRad = wheelAngleRadians(4f, 5)

        assertEquals(PI.toFloat(), midRad, 1e-4f)
        assertTrue(cos(topRad) < 0f && sin(topRad) < 0f)
        assertTrue(cos(bottomRad) < 0f && sin(bottomRad) > 0f)
        assertEquals(cos(topRad), cos(bottomRad), 1e-4f)
        assertEquals(-sin(topRad), sin(bottomRad), 1e-4f)
    }

    @Test
    fun wheelSlotAtMapsHubToNullAndArcEndsToFirstAndLast() {
        val cx = 200f
        val cy = 200f
        val hub = 30f
        assertNull(wheelSlotAt(Offset(cx - 10f, cy), cx, cy, hub, 10))

        assertEquals(0, wheelSlotAt(Offset(cx - 5f, cy - 90f), cx, cy, hub, 10))
        assertEquals(9, wheelSlotAt(Offset(cx - 5f, cy + 90f), cx, cy, hub, 10))

        val midSlot = wheelSlotAt(Offset(cx - 80f, cy), cx, cy, hub, 11)
        assertEquals(5, midSlot)
    }
}
