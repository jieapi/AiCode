package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.pluginProviderSelectionDataStore by preferencesDataStore(
    name = "plugin_provider_selections",
    corruptionHandler = preferencesCorruptionHandler
)

/** 使用者为某个插件提供商添加的模型；插件声明的完整目录仍以清单为准。 */
@Serializable
data class PluginProviderSelection(
    val models: List<String> = emptyList(),
    val selectedModel: String = ""
)

/**
 * 插件提供商的模型选择持久化。插件清单每次更新都会重新解析，但使用者已添加的模型
 * 不应被覆盖；插件停用/卸载后条目保留，重新启用仍可恢复。
 */
@Singleton
class PluginProviderSelectionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val selections: Flow<Map<String, PluginProviderSelection>> =
        context.pluginProviderSelectionDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun setModels(providerId: String, models: List<String>, selectedModel: String) {
        context.pluginProviderSelectionDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            next[providerId] = PluginProviderSelection(
                models = models.distinct(),
                selectedModel = selectedModel
            )
            prefs[KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): Map<String, PluginProviderSelection> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginProviderSelection>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("selections")
    }
}
