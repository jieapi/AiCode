package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.targetModeDataStore by preferencesDataStore(name = "target_mode_prefs")

/**
 * TARGET 目标驱动模式的阈值配置。默认最大步数 50、连续失败 5。
 * 由 [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow] 在工具循环中读取
 * 当前阈值用于终止判定（步数超限 / 连续失败）。
 */
@Singleton
class TargetModeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MAX_STEP_BUDGET = intPreferencesKey("max_step_budget")
        val MAX_CONSECUTIVE_FAILURES = intPreferencesKey("max_consecutive_failures")
        const val DEFAULT_MAX_STEP_BUDGET = 50
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5
    }

    data class Thresholds(
        val maxStepBudget: Int = DEFAULT_MAX_STEP_BUDGET,
        val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES
    )

    /** 当前阈值流；未设置时回退到默认值（50 / 5）。 */
    val thresholds: Flow<Thresholds> = context.targetModeDataStore.data.map { prefs ->
        Thresholds(
            maxStepBudget = prefs[MAX_STEP_BUDGET] ?: DEFAULT_MAX_STEP_BUDGET,
            maxConsecutiveFailures = prefs[MAX_CONSECUTIVE_FAILURES] ?: DEFAULT_MAX_CONSECUTIVE_FAILURES
        )
    }

    suspend fun setMaxStepBudget(value: Int) {
        context.targetModeDataStore.edit { it[MAX_STEP_BUDGET] = value.coerceAtLeast(1) }
    }

    suspend fun setMaxConsecutiveFailures(value: Int) {
        context.targetModeDataStore.edit { it[MAX_CONSECUTIVE_FAILURES] = value.coerceAtLeast(1) }
    }
}
