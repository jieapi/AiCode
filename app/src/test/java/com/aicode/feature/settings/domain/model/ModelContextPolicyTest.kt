package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextPolicyTest {

    // ---------- preserveRecentTokens：usableTokens / 4 后 clamp 到 [2000, 20000] ----------

    @Test
    fun preserveRecentTokens_zero_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(0))
    }

    /** 低于下界（整除后不足 2000）时被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_belowMinimum_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(7_999))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(1))
    }

    /** 恰好落在下界：无需 clamp。 */
    @Test
    fun preserveRecentTokens_atMinimum_boundary() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(8_000))
    }

    /** 区间内正常整除 4。 */
    @Test
    fun preserveRecentTokens_inRange_quarterDown() {
        assertEquals(2_500, ModelContextPolicy.preserveRecentTokens(10_000))
        assertEquals(19_999, ModelContextPolicy.preserveRecentTokens(79_999))
    }

    /** 上界边界：整除后恰好 20000 以及略超（整除截断仍为 20000）。 */
    @Test
    fun preserveRecentTokens_atMaximum_boundary() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_001))
    }

    /** 超过上界时 clamp 到上界，包括超大值与 Int.MAX_VALUE。 */
    @Test
    fun preserveRecentTokens_aboveMaximum_clampedToMaximum() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(128_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(Int.MAX_VALUE))
    }

    /** 负数（理论上不会出现）同样被 clamp 到下界。 */
    @Test
    fun preserveRecentTokens_negative_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(-1))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(Int.MIN_VALUE))
    }

    // ---------- estimateTokens：(chars + 3) / 4 向上取整 ----------

    @Test
    fun estimateTokens_zero_isZero() {
        assertEquals(0, ModelContextPolicy.estimateTokens(0))
    }

    /** 恰为 4 的倍数：无需进位。 */
    @Test
    fun estimateTokens_exactMultiple() {
        assertEquals(1, ModelContextPolicy.estimateTokens(4))
        assertEquals(25, ModelContextPolicy.estimateTokens(100))
    }

    /** 有余数时向上取整。 */
    @Test
    fun estimateTokens_roundsUp() {
        assertEquals(1, ModelContextPolicy.estimateTokens(1))
        assertEquals(2, ModelContextPolicy.estimateTokens(5))
        assertEquals(26, ModelContextPolicy.estimateTokens(101))
    }

    /** 超大值不溢出（不能用 Int.MAX_VALUE：+3 会整数溢出成负数）。 */
    @Test
    fun estimateTokens_largeValue() {
        assertEquals(250_000_000, ModelContextPolicy.estimateTokens(1_000_000_000))
    }
}