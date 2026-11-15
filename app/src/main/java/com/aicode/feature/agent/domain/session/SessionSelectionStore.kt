package com.aicode.feature.agent.domain.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionSelectionDataStore by preferencesDataStore(
    name = "session_selection",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 记住上次所在的会话与工作区：Activity 被系统回收后重建时优先回到原会话，
 * 而不是按启动偏好跳去新会话。
 */
@Singleton
class SessionSelectionStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val SESSION_ID_KEY = stringPreferencesKey("last_session_id")
        val WORKSPACE_KEY = stringPreferencesKey("last_workspace")
    }

    val selection: Flow<Pair<String?, String?>> = context.sessionSelectionDataStore.data.map { prefs ->
        prefs[SESSION_ID_KEY] to prefs[WORKSPACE_KEY]
    }

    suspend fun load(): Pair<String?, String?> = selection.first()

    suspend fun save(sessionId: String, workspace: String) {
        if (sessionId.isBlank() || workspace.isBlank()) return
        context.sessionSelectionDataStore.edit { prefs ->
            prefs[SESSION_ID_KEY] = sessionId
            prefs[WORKSPACE_KEY] = workspace
        }
    }
}
