package com.aicode.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.NonCancellable
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    /** 导入预览完成，等待选择要恢复的工作区。 */
    data class WorkspaceSelection(
        val workspaces: List<WorkspaceBackupMeta>,
        val uri: Uri
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    @Volatile
    private var preparedImport: File? = null

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

    private val _exportOptions = MutableStateFlow(loadExportOptions())
    val exportOptions: StateFlow<BackupOptions> = _exportOptions.asStateFlow()

    /** 导出数据范围：勾选即持久化，下次进入页面沿用。 */
    fun updateExportOptions(options: BackupOptions) {
        _exportOptions.value = options
        prefs.edit()
            .putBoolean(KEY_PROVIDERS, options.providers)
            .putBoolean(KEY_REMOTE_CONNECTIONS, options.remoteConnections)
            .putBoolean(KEY_CHAT_HISTORY, options.chatHistory)
            .putBoolean(KEY_MCP_SERVERS, options.mcpServers)
            .putBoolean(KEY_PERMISSION_RULES, options.permissionRules)
            .putBoolean(KEY_APP_SETTINGS, options.appSettings)
            .putBoolean(KEY_WORKSPACE_FILES, options.workspaceFiles)
            .apply()
    }

    private fun loadExportOptions(): BackupOptions = BackupOptions(
        providers = prefs.getBoolean(KEY_PROVIDERS, true),
        remoteConnections = prefs.getBoolean(KEY_REMOTE_CONNECTIONS, true),
        chatHistory = prefs.getBoolean(KEY_CHAT_HISTORY, true),
        mcpServers = prefs.getBoolean(KEY_MCP_SERVERS, true),
        permissionRules = prefs.getBoolean(KEY_PERMISSION_RULES, true),
        appSettings = prefs.getBoolean(KEY_APP_SETTINGS, true),
        workspaceFiles = prefs.getBoolean(KEY_WORKSPACE_FILES, false)
    )

    /** 流式导出到 [output]（调用方打开，本方法负责关闭）。 */
    fun export(password: String, options: BackupOptions, output: OutputStream) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                backupManager.export(pw, options, output)
                _state.value = BackupState.ExportDone
            } catch (e: Exception) {
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /** 从 SAF Uri 流式导入：先预览，备份含工作区数据时进入勾选确认，否则全量还原。 */
    fun import(uri: Uri, password: String) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            FileLogger.i(TAG, "导入请求：${describeUri(uri)}（${if (pw != null) "加密" else "明文"}）")
            try {
                val preview = withContext(Dispatchers.IO + NonCancellable) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                    input.use {
                        if (pw == null) {
                            backupManager.previewImport(it, null)
                        } else {
                            val file = backupManager.prepareImport(it, pw)
                            preparedImport = file
                            file.inputStream().use { source -> backupManager.previewImport(source, null) }
                        }
                    }
                }
                preview.onSuccess { preview ->
                    if (preview.hasWorkspaceData) {
                        _state.value = BackupState.WorkspaceSelection(preview.workspaces, uri)
                    } else {
                        restoreFromUri(uri, null)
                    }
                }.onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            } finally {
                pw?.fill('\u0000')
                if (_state.value !is BackupState.WorkspaceSelection || !isActive) {
                    clearPreparedImport()
                }
            }
        }
    }

    /** 勾选确认后按所选工作区恢复，复用已校验的解密文件。 */
    fun confirmImportSelection(selected: Set<String>) {
        val current = _state.value as? BackupState.WorkspaceSelection ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            restoreFromUri(current.uri, selected)
        }
    }

    /** 取消工作区勾选，中止导入。 */
    fun cancelImportSelection() {
        if (_state.value is BackupState.WorkspaceSelection) {
            _state.value = BackupState.Idle
            val file = preparedImport
            preparedImport = null
            viewModelScope.launch(Dispatchers.IO + NonCancellable) { file?.delete() }
        }
    }

    private suspend fun restoreFromUri(uri: Uri, selected: Set<String>?) {
        try {
            FileLogger.i(TAG, "开始还原：${describeUri(uri)}${if (selected != null) "，勾选工作区=${selected.size}个" else "，全量"}")
            val result = withContext(Dispatchers.IO) {
                val input = preparedImport?.inputStream() ?: context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                input.use { backupManager.import(it, null, selected) }
            }
            result.onSuccess {
                    FileLogger.i(TAG, "还原成功：$it")
                    _state.value = BackupState.ImportSuccess(it)
                }
                .onFailure {
                    FileLogger.e(TAG, "还原失败", it)
                    _state.value = BackupState.Error(describeImportError(it))
                }
        } catch (e: Exception) {
            FileLogger.e(TAG, "还原异常", e)
            _state.value = BackupState.Error(describeImportError(e))
        } finally {
            clearPreparedImport()
        }
    }

    private suspend fun clearPreparedImport() {
        val file = preparedImport
        preparedImport = null
        withContext(Dispatchers.IO + NonCancellable) { file?.delete() }
    }

    override fun onCleared() {
        preparedImport?.delete()
        preparedImport = null
        super.onCleared()
    }

    /** 日志用 URI 摘要：只保留 scheme://authority/最后一段，避免记录完整路径。 */
    private fun describeUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: ""
        return "${uri.scheme}://${uri.authority}/…/$last"
    }

    private fun describeImportError(e: Throwable): String = when (e) {
        is BackupDecryptionException -> e.message ?: context.getString(R.string.backup_wrong_password)
        else -> buildString {
            append(e.message ?: e::class.simpleName ?: context.getString(R.string.backup_import_failed))
            // 诊断期附上 cause 链（最多两层），方便定位真实异常
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by: ")
                append(cause::class.simpleName ?: "未知异常")
                append(": ")
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
    }

    fun reset() {
        if (_state.value is BackupState.WorkspaceSelection) {
            cancelImportSelection()
        } else {
            _state.value = BackupState.Idle
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_PERMISSION_RULES = "permission_rules"
        private const val KEY_APP_SETTINGS = "app_settings"
        private const val KEY_WORKSPACE_FILES = "workspace_files"
    }
}
