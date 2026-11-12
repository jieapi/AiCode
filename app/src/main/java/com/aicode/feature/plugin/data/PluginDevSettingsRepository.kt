package com.aicode.feature.plugin.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pluginDevDataStore by preferencesDataStore(
    name = "plugin_dev_settings",
    corruptionHandler = preferencesCorruptionHandler
)

/** 开发者侧载开关；默认关闭，普通本地 zip 导入不受影响。 */
@Singleton
class PluginDevSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val enabled: Flow<Boolean> = context.pluginDevDataStore.data.map { it[KEY] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.pluginDevDataStore.edit { it[KEY] = enabled }
    }

    private companion object {
        val KEY = booleanPreferencesKey("developer_sideload")
    }
}
