package com.aicode.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPermission
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 导入插件包的界面状态。 */
sealed interface PluginImportState {
    data object Idle : PluginImportState

    data object Reading : PluginImportState

    /**
     * 包已通过校验，等待使用者确认。[granted] 是当前勾选的能力：必要能力固定勾选，
     * 可选能力默认不开启，由使用者逐项决定。
     */
    data class Preview(
        val preview: PluginPreview,
        val granted: Set<PluginPermission>,
        val installing: Boolean = false,
        val error: PluginError? = null
    ) : PluginImportState

    data class Failed(val errors: List<PluginError>) : PluginImportState
}

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager
) : ViewModel() {

    val plugins: StateFlow<List<InstalledPlugin>> = manager.plugins

    val loaded: StateFlow<Boolean> = manager.isLoaded

    private val _importState = MutableStateFlow<PluginImportState>(PluginImportState.Idle)
    val importState: StateFlow<PluginImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { manager.ensureLoaded() }
    }

    fun import(uri: Uri) {
        if (_importState.value !is PluginImportState.Idle && _importState.value !is PluginImportState.Failed) return
        _importState.value = PluginImportState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { manager.preview(it) }
                    ?: PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } catch (e: IOException) {
                FileLogger.w(TAG, "打开插件包失败", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } catch (e: SecurityException) {
                FileLogger.w(TAG, "没有读取插件包的权限", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            }
            _importState.value = when (result) {
                is PluginPreviewResult.Ready -> PluginImportState.Preview(
                    preview = result.preview,
                    granted = result.preview.manifest.requiredPermissions
                )
                is PluginPreviewResult.Failed -> PluginImportState.Failed(result.errors)
            }
        }
    }

    /** 切换一项可选能力；必要能力不能取消。 */
    fun toggleGrant(permission: PluginPermission) {
        _importState.update { state ->
            if (state !is PluginImportState.Preview || state.installing) return@update state
            if (permission in state.preview.manifest.requiredPermissions) return@update state
            val granted = if (permission in state.granted) state.granted - permission else state.granted + permission
            state.copy(granted = granted, error = null)
        }
    }

    fun confirmInstall() {
        val state = _importState.value as? PluginImportState.Preview ?: return
        if (state.installing) return
        _importState.value = state.copy(installing = true, error = null)
        viewModelScope.launch {
            when (val result = manager.install(state.preview.token, state.granted)) {
                is PluginInstallResult.Installed -> _importState.value = PluginImportState.Idle
                is PluginInstallResult.Failed -> _importState.update { current ->
                    (current as? PluginImportState.Preview)?.copy(installing = false, error = result.error) ?: current
                }
            }
        }
    }

    /** 关闭预览 / 错误提示；预览中的暂存包同时丢弃。 */
    fun dismissImport() {
        val state = _importState.value
        if (state is PluginImportState.Preview && state.installing) return
        _importState.value = PluginImportState.Idle
        if (state is PluginImportState.Preview) {
            viewModelScope.launch { manager.discard(state.preview.token) }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { manager.setEnabled(id, enabled) }
    }

    fun setGranted(id: String, permission: PluginPermission, granted: Boolean) {
        viewModelScope.launch { manager.setGranted(id, permission, granted) }
    }

    fun uninstall(id: String) {
        viewModelScope.launch { manager.uninstall(id) }
    }

    private companion object {
        const val TAG = "PluginsViewModel"
    }
}
