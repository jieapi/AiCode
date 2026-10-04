package com.aicode.feature.backup.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.backup.data.SyncIntervalUnit
import com.aicode.feature.backup.data.WebDavClient
import com.aicode.feature.backup.data.WebDavSettingsRepository
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** 立即同步/测试连接的操作状态。 */
sealed interface WebDavOpState {
    data object Idle : WebDavOpState
    data object Testing : WebDavOpState
    data object Syncing : WebDavOpState
    data class Success(val message: String) : WebDavOpState
    data class Error(val message: String) : WebDavOpState
}

@HiltViewModel
class WebDavSyncViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: WebDavSettingsRepository,
    private val client: WebDavClient,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _opState = MutableStateFlow<WebDavOpState>(WebDavOpState.Idle)
    val opState: StateFlow<WebDavOpState> = _opState.asStateFlow()

    val url = repository.urlFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val username = repository.usernameFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val password = repository.passwordFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val autoSync = repository.autoSyncFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val syncProviders = repository.syncProvidersFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncRemote = repository.syncRemoteFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncChatHistory = repository.syncChatHistoryFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncMcp = repository.syncMcpFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncPermissions = repository.syncPermissionsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncAppSettings = repository.syncAppSettingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val syncWorkspaceFiles = repository.syncWorkspaceFilesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val intervalValue = repository.intervalValueFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val intervalUnit = repository.intervalUnitFlow.stateIn(viewModelScope, SharingStarted.Eagerly, SyncIntervalUnit.HOUR)
    val lastSyncAt = repository.lastSyncAtFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setUrl(value: String) = viewModelScope.launch { repository.setUrl(value) }
    fun setUsername(value: String) = viewModelScope.launch { repository.setUsername(value) }
    fun setPassword(value: String) = viewModelScope.launch { repository.setPassword(value) }

    fun setAutoSync(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoSync(enabled)
        if (enabled) {
            val prefs = repository.snapshot()
            com.aicode.feature.backup.data.WebDavAutoSyncWorker
                .schedule(context, prefs.intervalValue, prefs.intervalUnit)
        } else {
            com.aicode.feature.backup.data.WebDavAutoSyncWorker.cancel(context)
        }
    }
    fun setSyncProviders(enabled: Boolean) = viewModelScope.launch { repository.setSyncProviders(enabled) }
    fun setSyncRemote(enabled: Boolean) = viewModelScope.launch { repository.setSyncRemote(enabled) }
    fun setSyncMcp(enabled: Boolean) = viewModelScope.launch { repository.setSyncMcp(enabled) }
    fun setSyncPermissions(enabled: Boolean) = viewModelScope.launch { repository.setSyncPermissions(enabled) }
    fun setSyncAppSettings(enabled: Boolean) = viewModelScope.launch { repository.setSyncAppSettings(enabled) }
    fun setSyncChatHistory(enabled: Boolean) = viewModelScope.launch { repository.setSyncChatHistory(enabled) }
    fun setSyncWorkspaceFiles(enabled: Boolean) = viewModelScope.launch { repository.setSyncWorkspaceFiles(enabled) }

    fun setIntervalValue(value: Int) = viewModelScope.launch {
        repository.setIntervalValue(value)
        rescheduleIfAuto()
    }
    fun setIntervalUnit(unit: SyncIntervalUnit) = viewModelScope.launch {
        repository.setIntervalUnit(unit)
        rescheduleIfAuto()
    }

    private suspend fun rescheduleIfAuto() {
        val prefs = repository.snapshot()
        if (prefs.autoSync) {
            com.aicode.feature.backup.data.WebDavAutoSyncWorker
                .schedule(context, prefs.intervalValue, prefs.intervalUnit)
        }
    }

    /** 连接测试。 */
    fun testConnection() {
        if (_opState.value is WebDavOpState.Testing) return
        viewModelScope.launch {
            _opState.value = WebDavOpState.Testing
            val config = repository.snapshot()
            _opState.value = client.test(config).fold(
                onSuccess = { WebDavOpState.Success("连接成功") },
                onFailure = { WebDavOpState.Error(it.message ?: "连接失败") }
            )
        }
    }

    /** 立即同步：本地生成一份备份并上传到 WebDAV。 */
    fun syncNow() {
        if (_opState.value is WebDavOpState.Syncing) return
        viewModelScope.launch {
            _opState.value = WebDavOpState.Syncing
            val config = repository.snapshot()
            if (!config.isConfigured) {
                _opState.value = WebDavOpState.Error("请先填写 WebDAV 地址")
                return@launch
            }
            try {
                val options = buildOptions()
                val tempFile = File(context.cacheDir, "webdav-backup-${System.currentTimeMillis()}.tar.gz")
                tempFile.outputStream().use { out ->
                    backupManager.export(null, options, out)
                }
                val result = client.upload(config, tempFile, REMOTE_FILE_PATH)
                tempFile.delete()
                _opState.value = result.fold(
                    onSuccess = {
                        val now = System.currentTimeMillis()
                        repository.setLastSyncAt(now)
                        WebDavOpState.Success("同步完成")
                    },
                    onFailure = { WebDavOpState.Error(it.message ?: "同步失败") }
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "WebDAV 立即同步失败", e)
                _opState.value = WebDavOpState.Error(e.message ?: "同步失败")
            }
        }
    }

    fun resetOpState() {
        _opState.value = WebDavOpState.Idle
    }

    private suspend fun buildOptions(): BackupOptions {
        return BackupOptions(
            providers = repository.syncProvidersFlow.first(),
            remoteConnections = repository.syncRemoteFlow.first(),
            chatHistory = repository.syncChatHistoryFlow.first(),
            mcpServers = repository.syncMcpFlow.first(),
            permissionRules = repository.syncPermissionsFlow.first(),
            appSettings = repository.syncAppSettingsFlow.first(),
            workspaceFiles = repository.syncWorkspaceFilesFlow.first()
        )
    }

    companion object {
        private const val TAG = "WebDavSync"

        /** 云端根目录下自动创建的文件夹与备份文件名。 */
        private const val REMOTE_FILE_PATH = "AiCode/aicode-backup.tar.gz"
    }
}
