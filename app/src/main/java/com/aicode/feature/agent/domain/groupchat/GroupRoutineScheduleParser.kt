package com.aicode.feature.agent.domain.groupchat

import java.util.Calendar

/**
 * 群聊定时任务（Routines）调度表达式解析，纯函数，语义对齐 Hermes Bot Mode 的 cron 子集。
 *
 * 支持三种格式：
 * - `every Nm` / `every Nh` / `every Nd`：每 N 分钟 / 小时 / 天（N 为正整数）
 * - `hourly`：每小时（等价 every 1h）
 * - `daily HH:MM`：每天固定时刻（24 小时制，如 daily 09:30）
 *
 * 解析不校验 WorkManager 15 分钟最小周期（那是调度层的事），只负责给出
 * 「距上次执行后下一次该跑的时间点」。
 */
object GroupRoutineScheduleParser {

    private val EVERY_RE = Regex("""^every\s+(\d+)\s*([mhd])$""", RegexOption.IGNORE_CASE)
    private val DAILY_RE = Regex("""^daily\s+(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)
    private val HOURLY = "hourly"

    /** 分钟 → 毫秒。 */
    private fun minutesToMs(minutes: Long): Long = minutes * 60_000L

    /**
     * 间隔型表达式的分钟数（every Nm/Nh/Nd、hourly）；非间隔型返回 null。
     */
    fun intervalMinutes(schedule: String): Long? {
        val s = schedule.trim()
        if (s.equals(HOURLY, ignoreCase = true)) return 60L
        val m = EVERY_RE.matchEntire(s) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        if (n <= 0) return null
        return when (m.groupValues[2].lowercase()) {
            "m" -> n
            "h" -> n * 60
            "d" -> n * 1440
            else -> null
        }
    }

    /**
     * 固定时刻型（daily HH:MM）的今天触发时间点（毫秒），解析失败返回 null。
     */
    fun dailyTimeToday(schedule: String, now: Long = System.currentTimeMillis()): Long? {
        val s = schedule.trim()
        val m = DAILY_RE.matchEntire(s) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * 计算该 schedule 下一次触发的时间点。
     *
     * @param lastRunAt 上次执行时间；null 表示从未执行（立即视为到期）。
     * @return 下一个触发时刻；表达式非法返回 null。
     */
    fun nextRunAfter(schedule: String, lastRunAt: Long?, now: Long = System.currentTimeMillis()): Long? {
        val s = schedule.trim()
        intervalMinutes(s)?.let { minutes ->
            val intervalMs = minutesToMs(minutes)
            return if (lastRunAt == null) now else maxOf(lastRunAt + intervalMs, now)
        }
        dailyTimeToday(s, now)?.let { today ->
            return if (today > now) today else today + 24 * 60 * 60_000L
        }
        return null
    }

    /** 人类可读描述（UI 列表展示用），非法表达式返回原串。 */
    fun describe(schedule: String): String {
        val s = schedule.trim()
        intervalMinutes(s)?.let { minutes ->
            return when {
                minutes % 1440 == 0L -> "每 ${minutes / 1440} 天"
                minutes % 60 == 0L -> "每 ${minutes / 60} 小时"
                else -> "每 $minutes 分钟"
            }
        }
        dailyTimeToday(s)?.let {
            val m = DAILY_RE.matchEntire(s)!!
            return "每天 ${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}"
        }
        return s
    }

    /** 表达式是否合法。 */
    fun isValid(schedule: String): Boolean = nextRunAfter(schedule, System.currentTimeMillis(), System.currentTimeMillis()) != null
}
