package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.feature.agent.domain.tool.browser.BrowserActionCatalog
import com.aicode.feature.agent.domain.tool.browser.BrowserUserAgent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.browserDataStore by preferencesDataStore(name = "browser_prefs")

/**
 * 浏览器设置：
 *
 * - User-Agent 预设（默认 / 安卓手机 / 安卓平板 / Windows / Mac），默认「系统默认」。
 * - AI 控制浏览器的逐 action 权限开关，默认全开。
 *
 * 持久化设计与 [ToolSafetySettingsRepository] 一致（独立 DataStore）。
 */
@Singleton
class BrowserSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val USER_AGENT_KEY = stringPreferencesKey("user_agent")

        /** 被禁用的 action 集合，逗号分隔（空 = 全开）。 */
        val DISABLED_ACTIONS_KEY = stringPreferencesKey("disabled_actions")
    }

    /** 当前 UA 预设；未设置时回退「系统默认」。 */
    val userAgentFlow: Flow<BrowserUserAgent> = context.browserDataStore.data.map { prefs ->
        BrowserUserAgent.fromId(prefs[USER_AGENT_KEY])
    }

    suspend fun setUserAgent(userAgent: BrowserUserAgent) {
        context.browserDataStore.edit { it[USER_AGENT_KEY] = userAgent.id }
    }

    suspend fun userAgent(): BrowserUserAgent = userAgentFlow.first()

    /** 被禁用的 action 集合。 */
    val disabledActionsFlow: Flow<Set<String>> = context.browserDataStore.data.map { prefs ->
        prefs[DISABLED_ACTIONS_KEY]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * 逐 action 开关的当前状态（默认全开）：返回 action 名 → 是否启用。
     * 仅包含 [BrowserActionCatalog.ALL] 中已知的 action，避免脏数据影响 UI。
     */
    val actionEnabledFlow: Flow<Map<String, Boolean>> =
        disabledActionsFlow.map { disabled ->
            BrowserActionCatalog.ALL.associateWith { it !in disabled }
        }

    suspend fun setActionEnabled(action: String, enabled: Boolean) {
        context.browserDataStore.edit { prefs ->
            val current = prefs[DISABLED_ACTIONS_KEY]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (enabled) current.remove(action) else current.add(action)
            prefs[DISABLED_ACTIONS_KEY] = current.joinToString(",")
        }
    }

    suspend fun isActionEnabled(action: String): Boolean =
        action !in disabledActionsFlow.first()
}
