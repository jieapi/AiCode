package com.aicode.feature.agent.domain.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRoutineScheduleParserTest {

    // ── intervalMinutes ──────────────────────────────────────────────────────

    @Test
    fun `every Nm parses to minutes`() {
        assertEquals(15L, GroupRoutineScheduleParser.intervalMinutes("every 15m"))
        assertEquals(90L, GroupRoutineScheduleParser.intervalMinutes("every 90m"))
        assertEquals(5L, GroupRoutineScheduleParser.intervalMinutes("every 5M"))
    }

    @Test
    fun `every Nh and Nd expand`() {
        assertEquals(120L, GroupRoutineScheduleParser.intervalMinutes("every 2h"))
        assertEquals(1440L, GroupRoutineScheduleParser.intervalMinutes("every 1d"))
        assertEquals(2880L, GroupRoutineScheduleParser.intervalMinutes("every 2D"))
    }

    @Test
    fun `hourly is 60 minutes`() {
        assertEquals(60L, GroupRoutineScheduleParser.intervalMinutes("hourly"))
        assertEquals(60L, GroupRoutineScheduleParser.intervalMinutes("HOURLY"))
    }

    @Test
    fun `non-interval and invalid return null`() {
        assertNull(GroupRoutineScheduleParser.intervalMinutes("daily 09:30"))
        assertNull(GroupRoutineScheduleParser.intervalMinutes("every 0m"))
        assertNull(GroupRoutineScheduleParser.intervalMinutes("every -5m"))
        assertNull(GroupRoutineScheduleParser.intervalMinutes("every m"))
        assertNull(GroupRoutineScheduleParser.intervalMinutes(""))
        assertNull(GroupRoutineScheduleParser.intervalMinutes("weekly"))
    }

    // ── dailyTimeToday ───────────────────────────────────────────────────────

    @Test
    fun `daily time resolves today in ms`() {
        val now = GroupRoutineScheduleParser.dailyTimeToday("daily 09:30") ?: throw AssertionError()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        assertEquals(9, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `invalid daily times return null`() {
        assertNull(GroupRoutineScheduleParser.dailyTimeToday("daily 24:00"))
        assertNull(GroupRoutineScheduleParser.dailyTimeToday("daily 09:60"))
        assertNull(GroupRoutineScheduleParser.dailyTimeToday("daily 9"))
        assertNull(GroupRoutineScheduleParser.dailyTimeToday("every 30m"))
    }

    // ── nextRunAfter ─────────────────────────────────────────────────────────

    @Test
    fun `interval with no last run is due now`() {
        val now = 1_700_000_000_000L
        assertEquals(now, GroupRoutineScheduleParser.nextRunAfter("every 30m", null, now))
    }

    @Test
    fun `interval advances from last run`() {
        val now = 1_700_000_000_000L
        val last = now - 20 * 60_000L
        assertEquals(last + 30 * 60_000L, GroupRoutineScheduleParser.nextRunAfter("every 30m", last, now))
    }

    @Test
    fun `interval overdue snaps to now instead of stacking`() {
        val now = 1_700_000_000_000L
        val last = now - 90 * 60_000L
        assertEquals(now, GroupRoutineScheduleParser.nextRunAfter("every 30m", last, now))
    }

    @Test
    fun `daily before now rolls to tomorrow`() {
        // 用「今天 00:01」做 now：daily 00:00 已过 → 明天
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 1)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val next = GroupRoutineScheduleParser.nextRunAfter("daily 00:00", null, now) ?: throw AssertionError()
        assertTrue(next > now)
        val nextCal = java.util.Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(0, nextCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, nextCal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `invalid schedule returns null`() {
        assertNull(GroupRoutineScheduleParser.nextRunAfter("bogus", null, 0L))
        assertNull(GroupRoutineScheduleParser.nextRunAfter("", null, 0L))
    }

    // ── describe / isValid ───────────────────────────────────────────────────

    @Test
    fun `describe humanizes schedules`() {
        assertEquals("每 30 分钟", GroupRoutineScheduleParser.describe("every 30m"))
        assertEquals("每 2 小时", GroupRoutineScheduleParser.describe("every 2h"))
        assertEquals("每 1 天", GroupRoutineScheduleParser.describe("every 1d"))
        assertEquals("每天 09:05", GroupRoutineScheduleParser.describe("daily 9:05"))
        assertEquals("每天 09:05", GroupRoutineScheduleParser.describe("daily 09:05"))
        assertEquals("bogus", GroupRoutineScheduleParser.describe("bogus"))
    }

    @Test
    fun `isValid accepts supported and rejects others`() {
        assertTrue(GroupRoutineScheduleParser.isValid("every 15m"))
        assertTrue(GroupRoutineScheduleParser.isValid("hourly"))
        assertTrue(GroupRoutineScheduleParser.isValid("daily 23:59"))
        assertFalse(GroupRoutineScheduleParser.isValid("cron 0 0 * * *"))
        assertFalse(GroupRoutineScheduleParser.isValid(""))
    }

    @Test
    fun `parse result is stable across calls`() {
        val now = 1_700_000_000_000L
        val a = GroupRoutineScheduleParser.nextRunAfter("every 1h", now - 60 * 60_000L, now)
        val b = GroupRoutineScheduleParser.nextRunAfter("every 1h", now - 60 * 60_000L, now)
        assertNotNull(a)
        assertEquals(a, b)
    }
}
