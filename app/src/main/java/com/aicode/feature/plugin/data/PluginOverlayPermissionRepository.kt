package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.pluginOverlayDataStore by preferencesDataStore(
    name = "plugin_overlay_permissions",
    corruptionHandler = preferencesCorruptionHandler
)

/** 使用者对某个插件的系统悬浮窗授权。 */
@Serializable
data class PluginOverlayGrant(val grantedAt: Long)

/**
 * 插件级悬浮窗授权。
 *
 * 悬浮窗能覆盖其他应用，必须与插件启用分开授权；系统级
 * [android.provider.Settings.canDrawOverlays] 仍由使用者单独开启。
 */
@Singleton
class PluginOverlayPermissionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val grants: Flow<Map<String, PluginOverlayGrant>> =
        context.pluginOverlayDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun grant(pluginId: String, now: Long = System.currentTimeMillis()) {
        context.pluginOverlayDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            next[pluginId] = PluginOverlayGrant(grantedAt = now)
            prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun revoke(pluginId: String) {
        context.pluginOverlayDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            if (next.remove(pluginId) != null) prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun isGranted(pluginId: String): Boolean =
        grants.first().containsKey(pluginId)

    private fun decode(raw: String?): Map<String, PluginOverlayGrant> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginOverlayGrant>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("grants")
    }
}
