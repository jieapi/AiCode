package com.aicode.feature.backup.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.webDavDataStore by preferencesDataStore(name = "webdav_prefs")

/** 自动同步频率单位。 */
enum class SyncIntervalUnit {
    MINUTE, HOUR, DAY;

    companion object {
        fun fromName(name: String?): SyncIntervalUnit =
            entries.firstOrNull { it.name == name } ?: HOUR
    }
}

/**
 * WebDAV 同步设置：
 *
 * - 服务器地址 / 用户名 / 密码（密码加密存储由调用方负责，这里存明文占位）。
 * - 是否自动备份同步；自动同步时选择要同步的内容与间隔。
 *
 * 持久化用独立 DataStore。密码不做加密属已知取舍：与内置 FTP 服务端等本地凭据同级，
 * 仅存于应用私有目录。
 */
@Singleton
class WebDavSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val URL_KEY = stringPreferencesKey("url")
        val USERNAME_KEY = stringPreferencesKey("username")
        val PASSWORD_KEY = stringPreferencesKey("password")

        val AUTO_SYNC_KEY = booleanPreferencesKey("auto_sync")
        val SYNC_PROVIDERS_KEY = booleanPreferencesKey("sync_providers")
        val SYNC_REMOTE_KEY = booleanPreferencesKey("sync_remote_connections")
        val SYNC_CHAT_HISTORY_KEY = booleanPreferencesKey("sync_chat_history")
        val SYNC_MCP_KEY = booleanPreferencesKey("sync_mcp_servers")
        val SYNC_PERMISSIONS_KEY = booleanPreferencesKey("sync_permission_rules")
        val SYNC_APP_SETTINGS_KEY = booleanPreferencesKey("sync_app_settings")
        val SYNC_WORKSPACE_FILES_KEY = booleanPreferencesKey("sync_workspace_files")
        val INTERVAL_VALUE_KEY = intPreferencesKey("interval_value")
        val INTERVAL_UNIT_KEY = stringPreferencesKey("interval_unit")
        val LAST_SYNC_AT_KEY = stringPreferencesKey("last_sync_at")
    }

    val urlFlow: Flow<String> = context.webDavDataStore.data.map { it[URL_KEY] ?: "" }
    val usernameFlow: Flow<String> = context.webDavDataStore.data.map { it[USERNAME_KEY] ?: "" }
    val passwordFlow: Flow<String> = context.webDavDataStore.data.map { it[PASSWORD_KEY] ?: "" }

    val autoSyncFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[AUTO_SYNC_KEY] ?: false }
    val syncProvidersFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_PROVIDERS_KEY] ?: true }
    val syncRemoteFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_REMOTE_KEY] ?: true }
    val syncChatHistoryFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_CHAT_HISTORY_KEY] ?: true }
    val syncMcpFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_MCP_KEY] ?: true }
    val syncPermissionsFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_PERMISSIONS_KEY] ?: true }
    val syncAppSettingsFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_APP_SETTINGS_KEY] ?: true }
    val syncWorkspaceFilesFlow: Flow<Boolean> = context.webDavDataStore.data.map { it[SYNC_WORKSPACE_FILES_KEY] ?: false }

    /** 自动同步间隔数值；默认 1。 */
    val intervalValueFlow: Flow<Int> = context.webDavDataStore.data.map {
        (it[INTERVAL_VALUE_KEY] ?: 1).coerceAtLeast(1)
    }

    /** 自动同步间隔单位；默认小时。 */
    val intervalUnitFlow: Flow<SyncIntervalUnit> = context.webDavDataStore.data.map {
        SyncIntervalUnit.fromName(it[INTERVAL_UNIT_KEY])
    }

    /** 上次同步时间（毫秒时间戳字符串）；从未同步时为 null。 */
    val lastSyncAtFlow: Flow<Long?> = context.webDavDataStore.data.map {
        it[LAST_SYNC_AT_KEY]?.toLongOrNull()
    }

    suspend fun setUrl(url: String) = edit { it[URL_KEY] = url }
    suspend fun setUsername(username: String) = edit { it[USERNAME_KEY] = username }
    suspend fun setPassword(password: String) = edit { it[PASSWORD_KEY] = password }

    suspend fun setAutoSync(enabled: Boolean) = edit { it[AUTO_SYNC_KEY] = enabled }
    suspend fun setSyncProviders(enabled: Boolean) = edit { it[SYNC_PROVIDERS_KEY] = enabled }
    suspend fun setSyncRemote(enabled: Boolean) = edit { it[SYNC_REMOTE_KEY] = enabled }
    suspend fun setSyncChatHistory(enabled: Boolean) = edit { it[SYNC_CHAT_HISTORY_KEY] = enabled }
    suspend fun setSyncMcp(enabled: Boolean) = edit { it[SYNC_MCP_KEY] = enabled }
    suspend fun setSyncPermissions(enabled: Boolean) = edit { it[SYNC_PERMISSIONS_KEY] = enabled }
    suspend fun setSyncAppSettings(enabled: Boolean) = edit { it[SYNC_APP_SETTINGS_KEY] = enabled }
    suspend fun setSyncWorkspaceFiles(enabled: Boolean) = edit { it[SYNC_WORKSPACE_FILES_KEY] = enabled }

    suspend fun setIntervalValue(value: Int) = edit { it[INTERVAL_VALUE_KEY] = value.coerceAtLeast(1) }
    suspend fun setIntervalUnit(unit: SyncIntervalUnit) = edit { it[INTERVAL_UNIT_KEY] = unit.name }

    suspend fun setLastSyncAt(millis: Long) = edit { it[LAST_SYNC_AT_KEY] = millis.toString() }

    /** 读取一次当前配置（供同步任务构造请求）。 */
    suspend fun snapshot(): WebDavConfig {
        val prefs = context.webDavDataStore.data.first()
        return WebDavConfig(
            url = prefs[URL_KEY] ?: "",
            username = prefs[USERNAME_KEY] ?: "",
            password = prefs[PASSWORD_KEY] ?: "",
            autoSync = prefs[AUTO_SYNC_KEY] ?: false,
            intervalValue = (prefs[INTERVAL_VALUE_KEY] ?: 1).coerceAtLeast(1),
            intervalUnit = SyncIntervalUnit.fromName(prefs[INTERVAL_UNIT_KEY])
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.webDavDataStore.edit(block)
    }
}

/** WebDAV 连接与同步参数快照。 */
data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
    val autoSync: Boolean,
    val intervalValue: Int,
    val intervalUnit: SyncIntervalUnit
) {
    val isConfigured: Boolean get() = url.isNotBlank()
}
