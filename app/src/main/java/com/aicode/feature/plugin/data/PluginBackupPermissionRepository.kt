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

private val Context.pluginBackupDataStore by preferencesDataStore(
    name = "plugin_backup_permissions",
    corruptionHandler = preferencesCorruptionHandler
)

/** 使用者对某个插件接收备份 / 还原事件的授权。 */
@Serializable
data class PluginBackupGrant(val grantedAt: Long)

/**
 * 插件级备份事件授权。
 *
 * 事件会带出完整备份文件，可能包含 API Key、SSH 凭据与会话记录，不能随插件启用自动生效，
 * 必须由使用者在插件详情里单独授权。撤销后不再派发，已下发到插件数据目录的副本由插件自行清理。
 */
@Singleton
class PluginBackupPermissionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    val grants: Flow<Map<String, PluginBackupGrant>> =
        context.pluginBackupDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun grant(pluginId: String, now: Long = System.currentTimeMillis()) {
        context.pluginBackupDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            next[pluginId] = PluginBackupGrant(grantedAt = now)
            prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun revoke(pluginId: String) {
        context.pluginBackupDataStore.edit { prefs ->
            val next = decode(prefs[KEY]).toMutableMap()
            if (next.remove(pluginId) != null) prefs[KEY] = json.encodeToString(next)
        }
    }

    suspend fun isGranted(pluginId: String): Boolean =
        grants.first().containsKey(pluginId)

    private fun decode(raw: String?): Map<String, PluginBackupGrant> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginBackupGrant>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("grants")
    }
}
