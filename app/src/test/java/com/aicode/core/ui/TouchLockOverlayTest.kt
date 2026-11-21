package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLockOverlayTest {

    private val eps = 0.0001f

    @Test
    fun `hold duration defaults to 3 seconds`() {
        assertEquals(3_000L, TOUCH_LOCK_HOLD_DURATION_MS)
    }

    @Test
    fun `holdProgress maps elapsed time and clamps bounds`() {
        assertEquals(0f, touchLockHoldProgress(-100L), eps)
        assertEquals(0f, touchLockHoldProgress(0L), eps)
        assertEquals(0.5f, touchLockHoldProgress(1_500L), eps)
        assertEquals(1f, touchLockHoldProgress(3_000L), eps)
        assertEquals(1f, touchLockHoldProgress(4_200L), eps)
    }

    @Test
    fun `remainingSeconds rounds up until completion`() {
        assertEquals(3, touchLockRemainingSeconds(0L))
        assertEquals(3, touchLockRemainingSeconds(1L))
        assertEquals(3, touchLockRemainingSeconds(999L))
        assertEquals(2, touchLockRemainingSeconds(1_000L))
        assertEquals(2, touchLockRemainingSeconds(1_999L))
        assertEquals(1, touchLockRemainingSeconds(2_000L))
        assertEquals(1, touchLockRemainingSeconds(2_999L))
        assertEquals(0, touchLockRemainingSeconds(3_000L))
        assertEquals(0, touchLockRemainingSeconds(3_500L))
    }

    @Test
    fun `pointer hit check enforces circular target plus tolerance`() {
        val w = 200f
        val h = 200f
        val tol = 20f
        // 中心与圆环内侧均有效
        assertTrue(isPointerWithinTouchLockTarget(100f, 100f, w, h, tol))
        assertTrue(isPointerWithinTouchLockTarget(200f, 100f, w, h, tol))
        // 恰好位于容差外缘（半径 100 + 20 = 120）
        assertTrue(isPointerWithinTouchLockTarget(220f, 100f, w, h, tol))
        // 超出圆形容差范围即判定脱离
        assertFalse(isPointerWithinTouchLockTarget(221f, 100f, w, h, tol))
        // 正方形角落到中心距离约 141.4 > 120，必须判为脱离
        assertFalse(isPointerWithinTouchLockTarget(200f, 200f, w, h, tol))
        // 未完成布局（宽高为 0）时返回 false
        assertFalse(isPointerWithinTouchLockTarget(0f, 0f, 0f, h, tol))
    }

    @Test
    fun `target brightness lowers bright or system-auto windows without brightening darker ones`() {
        assertEquals(TOUCH_LOCK_WINDOW_BRIGHTNESS, resolveTouchLockTargetBrightness(-1f), eps)
        assertEquals(TOUCH_LOCK_WINDOW_BRIGHTNESS, resolveTouchLockTargetBrightness(0.8f), eps)
        assertEquals(0.01f, resolveTouchLockTargetBrightness(0.01f), eps)
        assertEquals(0f, resolveTouchLockTargetBrightness(0f), eps)
    }
}
