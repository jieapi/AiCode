package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.pluginOverlayPositionDataStore by preferencesDataStore(
    name = "plugin_overlay_positions",
    corruptionHandler = preferencesCorruptionHandler
)

/** 某个插件浮层最后一次的窗口位置，单位是屏幕像素。 */
@Serializable
data class PluginOverlayPosition(val x: Int, val y: Int)

/** 按 `pluginId:surfaceId` 保存浮层位置，重启或自动拉起时恢复。 */
@Singleton
class PluginOverlayPositionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(pluginId: String, surfaceId: String): PluginOverlayPosition? {
        val all = context.pluginOverlayPositionDataStore.data.first()[KEY]
        return decode(all)[key(pluginId, surfaceId)]
    }

    suspend fun save(pluginId: String, surfaceId: String, x: Int, y: Int) {
        context.pluginOverlayPositionDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            next[key(pluginId, surfaceId)] = PluginOverlayPosition(x, y)
            prefs[KEY] = json.encodeToString(next)
        }
    }

    private fun key(pluginId: String, surfaceId: String): String = "$pluginId:$surfaceId"

    private fun decode(raw: String?): Map<String, PluginOverlayPosition> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginOverlayPosition>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("positions")
    }
}
