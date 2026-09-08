package com.aicode.feature.onboarding.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

/** 首次启动引导的持久化状态。 */
enum class OnboardingStatus(val persisted: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    SKIPPED("skipped");

    companion object {
        fun fromPersisted(value: String?): OnboardingStatus? =
            entries.firstOrNull { it.persisted == value }
    }
}

/**
 * 首次启动引导的进度持久化：一个 key 记录三态。
 *
 * 每推进一格即写入，进程被杀、重启后回到当前步；完成/跳过只在状态发生跳变时写一次。
 * 风格对齐 [ThemeSettingsRepository]（同一个 DataStore 模式）。
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ONBOARDING_STATUS_KEY = stringPreferencesKey("onboarding_status")
    }

    /** 当前引导状态；首次安装（从未写过）时为 null，由调用方当作 IN_PROGRESS 处理。 */
    val statusFlow: Flow<OnboardingStatus?> =
        context.onboardingDataStore.data.map { OnboardingStatus.fromPersisted(it[ONBOARDING_STATUS_KEY]) }

    /** 当前状态快照，供启动判定一次读取。 */
    suspend fun status(): OnboardingStatus? = statusFlow.first()

    suspend fun markStatus(status: OnboardingStatus) {
        context.onboardingDataStore.edit { it[ONBOARDING_STATUS_KEY] = status.persisted }
    }

    /** 完成引导；收到真实完成事件后调用，只在状态未完成时写一次。 */
    suspend fun complete() {
        val current = status()
        if (current != OnboardingStatus.COMPLETED) markStatus(OnboardingStatus.COMPLETED)
    }

    /** 跳过引导；用户点跳过或强制重置时调用。 */
    suspend fun skip() {
        val current = status()
        if (current != OnboardingStatus.SKIPPED) markStatus(OnboardingStatus.SKIPPED)
    }

    /** 重置为进行中（设置页「重新运行引导」用）。 */
    suspend fun resetToInProgress() {
        val current = status()
        if (current != OnboardingStatus.IN_PROGRESS) markStatus(OnboardingStatus.IN_PROGRESS)
    }
}