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

private val Context.pluginShizukuDataStore by preferencesDataStore(
    name = "plugin_shizuku_permissions",
    corruptionHandler = preferencesCorruptionHandler
)

/** 使用者对某个插件的 Shizuku 授权。 */
@Serializable
data class PluginShizukuGrant(val grantedAt: Long)

/**
 * 插件级 Shizuku 授权。
 *
 * 与“启用即信任”的其他插件能力不同，Shizuku 可以操作整个 Android 系统，必须由使用者
 * 在插件详情里单独授权。授权后该插件可直接执行任意 Shizuku 命令，不再按命令过滤。
 */
@Singleton
class PluginShizukuPermissionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val grants: Flow<Map<String, PluginShizukuGrant>> =
        context.pluginShizukuDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun grant(pluginId: String, now: Long = System.currentTimeMillis()) {
        context.pluginShizukuDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            next[pluginId] = PluginShizukuGrant(grantedAt = now)
            prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun revoke(pluginId: String) {
        context.pluginShizukuDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            if (next.remove(pluginId) != null) prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun isGranted(pluginId: String): Boolean =
        grants.first().containsKey(pluginId)

    private fun decode(raw: String?): Map<String, PluginShizukuGrant> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginShizukuGrant>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("grants")
    }
}
