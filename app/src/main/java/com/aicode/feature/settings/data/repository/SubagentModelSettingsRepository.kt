package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.feature.agent.domain.subagent.SubagentPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subagentModelDataStore by preferencesDataStore(name = "subagent_model_prefs")

/**
 * 持久化「子代理预设列表」（每个预设 = 模型 + 模式提醒，见 [SubagentPreset]）。
 *
 * 用户在设置页「默认模型 → 子代理模型」中自由增删（子代理1、子代理2…），
 * AI 批量创建子代理且未显式指定模型时按序自动分配。列表为空时回退主会话模型。
 * JSON 列表存储方式与 [ContainerSettingsRepository] 一致。
 */
@Singleton
class SubagentModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val PRESETS_KEY = stringPreferencesKey("subagent_presets_json")
        val presetSerializer = ListSerializer(SubagentPreset.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /** 当前全部子代理预设（按列表顺序 = 自动分配顺序）；解析失败回退空列表。 */
    val presetsFlow: Flow<List<SubagentPreset>> = context.subagentModelDataStore.data.map { prefs ->
        prefs[PRESETS_KEY]?.let { raw ->
            runCatching { json.decodeFromString(presetSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    /** 读取一次当前预设列表（冷读用）。 */
    suspend fun getPresets(): List<SubagentPreset> = presetsFlow.first()

    /** 整表保存（列表编辑用：改名/选模型/增删后一次性落库，保持顺序）。 */
    suspend fun savePresets(presets: List<SubagentPreset>) {
        context.subagentModelDataStore.edit { prefs ->
            prefs[PRESETS_KEY] = json.encodeToString(presetSerializer, presets)
        }
    }
}