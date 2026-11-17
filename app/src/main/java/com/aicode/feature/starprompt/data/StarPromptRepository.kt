package com.aicode.feature.starprompt.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.starPromptDataStore by preferencesDataStore(
    name = "star_prompt_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 求 Star 弹窗的生命周期状态。 */
enum class StarPromptStatus(val persisted: String) {
    /** 正在累计前台使用时长，尚未达到首次触发门槛。 */
    TRACKING("tracking"),

    /** 用户选择了「下次一定」，处于阶梯冷却期内。 */
    SNOOZED("snoozed"),

    /** 用户已点击前往 GitHub 点 Star，不再主动弹出。 */
    STARRED("starred"),

    /** 用户点击了「不再提示」，不再主动弹出。 */
    DISMISSED("dismissed");

    companion object {
        fun fromPersisted(value: String?): StarPromptStatus =
            entries.firstOrNull { it.persisted == value } ?: TRACKING
    }
}

/** 持久化记录快照。 */
data class StarPromptRecord(
    val status: StarPromptStatus = StarPromptStatus.TRACKING,
    val accumulatedForegroundMs: Long = 0L,
    val snoozeCount: Int = 0,
    val nextPromptAtMs: Long = 0L
)

/**
 * 求 Star 持久化存储契约，便于协调器在纯 JVM 单测中替换为内存实现。
 */
interface StarPromptStore {
    val recordFlow: Flow<StarPromptRecord>

    suspend fun addForegroundDuration(deltaMs: Long)

    suspend fun markSnoozed(nowMs: Long)

    suspend fun markStarred()

    suspend fun markDismissed()
}

/**
 * 求 Star 弹窗的数据持久化仓库。
 *
 * 规则：
 * - 首次累计前台使用满 [TRIGGER_FOREGROUND_MS]（3 小时）后具备触发资格；
 * - 每次点击「下次一定」按 3 天 -> 7 天 -> 14 天阶梯推迟，之后每 14 天循环；
 * - 点击前往 GitHub 点 Star 或「不再提示」后永久静默。
 */
@Singleton
class StarPromptRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : StarPromptStore {

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val TRIGGER_FOREGROUND_MS = 3L * 60L * 60L * 1000L
        val SNOOZE_DELAYS_MS = listOf(3L * DAY_MS, 7L * DAY_MS, 14L * DAY_MS)

        /** 根据已推迟次数计算本次延迟毫秒数：3 天 -> 7 天 -> 14 天（之后每 14 天循环）。 */
        fun snoozeDelayMsFor(snoozeCount: Int): Long =
            SNOOZE_DELAYS_MS[snoozeCount.coerceIn(0, SNOOZE_DELAYS_MS.lastIndex)]

        private val KEY_STATUS = stringPreferencesKey("status")
        private val KEY_ACCUMULATED_FOREGROUND_MS = longPreferencesKey("accumulated_foreground_ms")
        private val KEY_SNOOZE_COUNT = intPreferencesKey("snooze_count")
        private val KEY_NEXT_PROMPT_AT_MS = longPreferencesKey("next_prompt_at_ms")
    }

    override val recordFlow: Flow<StarPromptRecord> = context.starPromptDataStore.data.map { prefs ->
        StarPromptRecord(
            status = StarPromptStatus.fromPersisted(prefs[KEY_STATUS]),
            accumulatedForegroundMs = (prefs[KEY_ACCUMULATED_FOREGROUND_MS] ?: 0L).coerceAtLeast(0L),
            snoozeCount = (prefs[KEY_SNOOZE_COUNT] ?: 0).coerceAtLeast(0),
            nextPromptAtMs = (prefs[KEY_NEXT_PROMPT_AT_MS] ?: 0L).coerceAtLeast(0L)
        )
    }

    override suspend fun addForegroundDuration(deltaMs: Long) {
        if (deltaMs <= 0L) return
        context.starPromptDataStore.edit { prefs ->
            val currentStatus = StarPromptStatus.fromPersisted(prefs[KEY_STATUS])
            if (currentStatus != StarPromptStatus.TRACKING) return@edit
            val current = (prefs[KEY_ACCUMULATED_FOREGROUND_MS] ?: 0L).coerceAtLeast(0L)
            val updated = (current + deltaMs).coerceAtMost(TRIGGER_FOREGROUND_MS)
            if (updated != current) {
                prefs[KEY_ACCUMULATED_FOREGROUND_MS] = updated
            }
        }
    }

    override suspend fun markSnoozed(nowMs: Long) {
        context.starPromptDataStore.edit { prefs ->
            val currentStatus = StarPromptStatus.fromPersisted(prefs[KEY_STATUS])
            if (currentStatus == StarPromptStatus.STARRED || currentStatus == StarPromptStatus.DISMISSED) {
                return@edit
            }
            val currentCount = (prefs[KEY_SNOOZE_COUNT] ?: 0).coerceAtLeast(0)
            val delayMs = snoozeDelayMsFor(currentCount)
            prefs[KEY_STATUS] = StarPromptStatus.SNOOZED.persisted
            prefs[KEY_SNOOZE_COUNT] = currentCount + 1
            prefs[KEY_NEXT_PROMPT_AT_MS] = nowMs + delayMs
        }
    }

    override suspend fun markStarred() {
        context.starPromptDataStore.edit { prefs ->
            prefs[KEY_STATUS] = StarPromptStatus.STARRED.persisted
        }
    }

    override suspend fun markDismissed() {
        context.starPromptDataStore.edit { prefs ->
            prefs[KEY_STATUS] = StarPromptStatus.DISMISSED.persisted
        }
    }
}
