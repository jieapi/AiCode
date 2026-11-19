package com.aicode.feature.workspace.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    /** 本地执行模式（非远程环境）：非远程后端下才可添加外部本地目录工作区。 */
    val isLocalMode: StateFlow<Boolean> = executionModeHolder.activeBackend
        .map { !it.capabilities.isRemoteEnvironment }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            !executionModeHolder.capabilities().isRemoteEnvironment
        )

    /** 远程工作区初始化失败提示（根路径/默认工作区创建失败）。 */
    val initError: StateFlow<String?> = repository.initError

    /** 添加外部本地工作区失败提示（目录不可写/无法解析等）。 */
    val addError: StateFlow<String?> = repository.addError
    val externalWarningDismissed: StateFlow<Boolean> = repository.externalWarningDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 移除外部本地工作区时是否一并删除其聊天记录（用于删除确认文案）。 */
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = repository.deleteExternalWorkspaceSessionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 消费初始化错误提示，避免重复弹 Toast。 */
    fun consumeInitError() {
        repository.consumeInitError()
    }

    /** 消费添加外部工作区失败提示，避免重复弹 Toast。 */
    fun consumeAddError() {
        repository.consumeAddError()
    }

    init {
        viewModelScope.launch {
            executionModeHolder.activeBackend.collectLatest {
                try {
                    repository.initialize()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    fun selectWorkspace(ref: WorkspaceRef) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(ref) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        onResult(ws)
    }

    /** 打开面板前刷新外部工作区可用性（存储卡拔插后列表与当前选中及时更新）。 */
    fun refreshAvailability() = viewModelScope.launch {
        runCatching { repository.refreshAvailability() }
    }

    fun setExternalWarningDismissed(dismissed: Boolean) = viewModelScope.launch {
        runCatching { repository.setExternalWarningDismissed(dismissed) }
    }

    /** 注册用户所选设备目录为外部本地工作区，不自动切换；切换由调用方走确认流程。 */
    fun addExternalWorkspace(uri: Uri, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.addExternalWorkspace(uri) }.getOrNull()
        onResult(ws)
    }

    fun deleteWorkspace(ref: WorkspaceRef) = viewModelScope.launch {
        runCatching { repository.deleteWorkspace(ref) }
    }
}
