package com.aicode.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.backup.data.BackupTargetRepository
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.RemoteBackup
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    /** 导入预览完成，等待选择要恢复的工作区。 */
    data class WorkspaceSelection(
        val workspaces: List<WorkspaceBackupMeta>
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

/** 网络备份的独立状态；导入/还原结果仍走 [BackupState]。 */
sealed class NetworkBackupState {
    data object Idle : NetworkBackupState()
    data object Working : NetworkBackupState()
    data class UploadDone(val targetName: String) : NetworkBackupState()
    data class RemoteList(
        val targetId: String,
        val targetName: String,
        val items: List<RemoteBackup>
    ) : NetworkBackupState()
    data class Error(val message: String) : NetworkBackupState()
}

/** 导入来源：SAF 选中的文件，或下载到本地的临时文件。 */
private sealed interface ImportSource {
    data class Saf(val uri: Uri) : ImportSource
    data class Local(val file: File) : ImportSource
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val targetRepository: BackupTargetRepository
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _networkState = MutableStateFlow<NetworkBackupState>(NetworkBackupState.Idle)
    val networkState: StateFlow<NetworkBackupState> = _networkState.asStateFlow()

    val targets: StateFlow<List<BackupTarget>> = targetRepository.targets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @Volatile
    private var preparedImport: File? = null

    /** 当前导入来源；Local 临时文件在导入结束/取消时删除。 */
    @Volatile
    private var pendingSource: ImportSource? = null

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

    /** 「选择导出数据」中插件数据项被关闭的键；缺省包含。 */
    private val _disabledPluginDataKeys = MutableStateFlow(
        prefs.getStringSet(KEY_PLUGIN_DISABLED, emptySet())?.toSet() ?: emptySet()
    )
    val disabledPluginDataKeys: StateFlow<Set<String>> = _disabledPluginDataKeys.asStateFlow()

    fun setPluginDataSelected(key: String, selected: Boolean) {
        val next = if (selected) _disabledPluginDataKeys.value - key else _disabledPluginDataKeys.value + key
        _disabledPluginDataKeys.value = next
        prefs.edit().putStringSet(KEY_PLUGIN_DISABLED, next).apply()
    }

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

    /** 流式导出到 [output]（调用方打开，本方法负责关闭）。[pluginIds] 是勾选的插件数据区段。 */
    fun export(password: String, options: BackupOptions, output: OutputStream, pluginIds: Set<String>) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                withContext(Dispatchers.IO) {
                    backupManager.export(pw, options, output, pluginIds)
                }
                _state.value = BackupState.ExportDone
            } catch (e: Exception) {
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                pw?.fill('\u0000')
                runCatching { output.close() }
            }
        }
    }

    // ── 网络备份目标 ──────────────────────────────────────────

    fun saveTarget(target: BackupTarget) {
        viewModelScope.launch { targetRepository.save(target) }
    }

    fun deleteTarget(id: String) {
        viewModelScope.launch { targetRepository.delete(id) }
    }

    /** 测试任意目标（可未保存）的连通性，结果通过 [onResult] 回调（成功布尔 + 文案）。 */
    fun testTarget(target: BackupTarget, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).testConnection().getOrThrow() }
            }
            result
                .onSuccess { onResult(true, context.getString(R.string.backup_net_test_success)) }
                .onFailure { onResult(false, it.message ?: context.getString(R.string.backup_net_test_failed)) }
        }
    }

    /** 导出当前备份并上传到 [target]。备份默认含解密后的 API Key / SSH 凭据，禁止明文上传远端。 */
    fun uploadToTarget(target: BackupTarget, password: String, pluginIds: Set<String>) {
        if (password.isEmpty()) {
            _networkState.value = NetworkBackupState.Error(
                context.getString(R.string.backup_net_upload_password_required)
            )
            return
        }
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            var temp: File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    File.createTempFile("netbackup", ".tar.gz", context.cacheDir)
                }
                val file = temp
                withContext(Dispatchers.IO) {
                    file.outputStream().buffered().use { backupManager.export(pw, _exportOptions.value, it, pluginIds) }
                }
                val name = "aicode-backup-${fileTimestamp()}.tar.gz"
                val result = withContext(Dispatchers.IO) {
                    runCatching { targetRepository.storageFor(target).upload(name, file).getOrThrow() }
                }
                result
                    .onSuccess { _networkState.value = NetworkBackupState.UploadDone(target.name) }
                    .onFailure {
                        _networkState.value = NetworkBackupState.Error(
                            it.message ?: context.getString(R.string.backup_net_upload_failed)
                        )
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "网络备份上传失败", e)
                _networkState.value = NetworkBackupState.Error(e.message ?: context.getString(R.string.backup_net_upload_failed))
            } finally {
                pw?.fill('\u0000')
                val file = temp
                if (file != null) withContext(Dispatchers.IO + NonCancellable) { file.delete() }
            }
        }
    }

    /** 拉取 [target] 上的备份列表。 */
    fun loadRemoteBackups(target: BackupTarget) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).list().getOrThrow() }
            }
            result
                .onSuccess { items ->
                    _networkState.value = NetworkBackupState.RemoteList(target.id, target.name, items)
                }
                .onFailure {
                    _networkState.value = NetworkBackupState.Error(
                        it.message ?: context.getString(R.string.backup_net_list_failed)
                    )
                }
        }
    }

    /** 删除远端某个备份后刷新列表。 */
    fun deleteRemoteBackup(target: BackupTarget, name: String) {
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).delete(name).getOrThrow() }
            }
            result
                .onSuccess { loadRemoteBackups(target) }
                .onFailure {
                    _networkState.value = NetworkBackupState.Error(
                        it.message ?: context.getString(R.string.backup_net_delete_failed)
                    )
                }
        }
    }

    /** 从 [target] 下载 [name] 并进入导入流程。 */
    fun restoreFromRemote(target: BackupTarget, name: String, password: String) {
        _state.value = BackupState.Working
        _networkState.value = NetworkBackupState.Idle
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                val temp = withContext(Dispatchers.IO) {
                    File.createTempFile("netrestore", ".tar.gz", context.cacheDir)
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching { targetRepository.storageFor(target).download(name, temp).getOrThrow() }
                }
                result
                    // 成功时把口令所有权交给 startImport（它在自己的 finally 里清零）；
                    // 这里不能提前清零，否则异步导入读到的会是全 NUL 口令，导致解密失败。
                    .onSuccess { startImport(ImportSource.Local(temp), pw) }
                    .onFailure {
                        withContext(Dispatchers.IO + NonCancellable) { temp.delete() }
                        pw?.fill('\u0000')
                        _state.value = BackupState.Error(describeImportError(it))
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "网络备份恢复失败", e)
                pw?.fill('\u0000')
                _state.value = BackupState.Error(describeImportError(e))
            }
        }
    }

    fun dismissNetworkState() {
        _networkState.value = NetworkBackupState.Idle
    }

    // ── 导入 ──────────────────────────────────────────────────

    /** 从 SAF Uri 流式导入：先预览，备份含工作区数据时进入勾选确认，否则全量还原。 */
    fun import(uri: Uri, password: String) {
        FileLogger.i(TAG, "导入请求：${describeUri(uri)}（${if (password.isNotEmpty()) "加密" else "明文"}）")
        startImport(ImportSource.Saf(uri), password.toCharArray().takeIf { it.isNotEmpty() })
    }

    private fun startImport(source: ImportSource, pw: CharArray?) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO + NonCancellable) {
                    val input = openStream(source)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                    input.use {
                        if (pw == null) {
                            backupManager.previewImport(it, null)
                        } else {
                            val file = backupManager.prepareImport(it, pw)
                            preparedImport = file
                            file.inputStream().use { source2 -> backupManager.previewImport(source2, null) }
                        }
                    }
                }
                preview.onSuccess { preview ->
                    if (preview.hasWorkspaceData) {
                        pendingSource = source
                        _state.value = BackupState.WorkspaceSelection(preview.workspaces)
                    } else {
                        restoreFromSource(source, null)
                    }
                }.onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            } finally {
                pw?.fill('\u0000')
                if (_state.value !is BackupState.WorkspaceSelection || !isActive) {
                    clearPreparedImport()
                    discardLocalSource(source)
                }
            }
        }
    }

    /** 勾选确认后按所选工作区恢复，复用已校验的解密文件。 */
    fun confirmImportSelection(selected: Set<String>) {
        if (_state.value !is BackupState.WorkspaceSelection) return
        _state.value = BackupState.Working
        val source = pendingSource
        viewModelScope.launch {
            if (source != null) {
                restoreFromSource(source, selected)
            } else {
                _state.value = BackupState.Error(context.getString(R.string.backup_read_failed))
            }
        }
    }

    /** 取消工作区勾选，中止导入。 */
    fun cancelImportSelection() {
        if (_state.value is BackupState.WorkspaceSelection) {
            _state.value = BackupState.Idle
            val source = pendingSource
            pendingSource = null
            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                preparedImport?.delete()
                preparedImport = null
                (source as? ImportSource.Local)?.file?.delete()
            }
        }
    }

    private suspend fun restoreFromSource(source: ImportSource, selected: Set<String>?) {
        try {
            FileLogger.i(TAG, "开始还原：${describeSource(source)}${if (selected != null) "，勾选工作区=${selected.size}个" else "，全量"}")
            val result = withContext(Dispatchers.IO) {
                val input = preparedImport?.inputStream() ?: openStream(source)
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
            discardLocalSource(source)
            pendingSource = null
        }
    }

    private suspend fun openStream(source: ImportSource): InputStream? = withContext(Dispatchers.IO) {
        when (source) {
            is ImportSource.Saf -> context.contentResolver.openInputStream(source.uri)
            is ImportSource.Local -> if (source.file.exists()) source.file.inputStream() else null
        }
    }

    /** 删除下载到本地的临时文件（SAF 来源无副作用）。 */
    private suspend fun discardLocalSource(source: ImportSource) {
        val file = (source as? ImportSource.Local)?.file ?: return
        withContext(Dispatchers.IO + NonCancellable) { file.delete() }
    }

    private suspend fun clearPreparedImport() {
        val file = preparedImport
        preparedImport = null
        withContext(Dispatchers.IO + NonCancellable) { file?.delete() }
    }

    override fun onCleared() {
        preparedImport?.delete()
        preparedImport = null
        (pendingSource as? ImportSource.Local)?.file?.delete()
        pendingSource = null
        super.onCleared()
    }

    /** 日志用 URI 摘要：只保留 scheme://authority/最后一段，避免记录完整路径。 */
    private fun describeUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: ""
        return "${uri.scheme}://${uri.authority}/…/$last"
    }

    private fun describeSource(source: ImportSource): String = when (source) {
        is ImportSource.Saf -> describeUri(source.uri)
        is ImportSource.Local -> "remote:${source.file.name}"
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

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "Backup"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_PERMISSION_RULES = "permission_rules"
        private const val KEY_APP_SETTINGS = "app_settings"
        private const val KEY_WORKSPACE_FILES = "workspace_files"
        private const val KEY_PLUGIN_DISABLED = "plugin_disabled"
    }
}
