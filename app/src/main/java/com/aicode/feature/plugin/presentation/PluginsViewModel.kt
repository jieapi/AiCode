package com.aicode.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.PluginSettingsPage
import com.aicode.feature.plugin.runtime.PluginDependencyResult
import com.aicode.feature.plugin.runtime.PluginEventBus
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
     * 包已通过校验，等待使用者确认。
     */
    data class Preview(
        val preview: PluginPreview,
        val installing: Boolean = false,
        val error: PluginError? = null
    ) : PluginImportState

    data class Failed(val errors: List<PluginError>) : PluginImportState
}

/** 依赖准备的界面状态；按插件 ID 隔离。 */
data class PluginDependencyUiState(
    val running: Boolean = false,
    val ready: Boolean? = null,
    val message: String? = null
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val commandEngine: CommandEngine,
    private val eventBus: PluginEventBus
) : ViewModel() {

    val plugins: StateFlow<List<InstalledPlugin>> = manager.plugins

    val loaded: StateFlow<Boolean> = manager.isLoaded

    private val _importState = MutableStateFlow<PluginImportState>(PluginImportState.Idle)
    val importState: StateFlow<PluginImportState> = _importState.asStateFlow()

    private val _dependencyStates = MutableStateFlow<Map<String, PluginDependencyUiState>>(emptyMap())
    val dependencyStates: StateFlow<Map<String, PluginDependencyUiState>> = _dependencyStates.asStateFlow()

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
                    preview = result.preview
                )
                is PluginPreviewResult.Failed -> PluginImportState.Failed(result.errors)
            }
        }
    }

    fun confirmInstall() {
        val state = _importState.value as? PluginImportState.Preview ?: return
        if (state.installing) return
        _importState.value = state.copy(installing = true, error = null)
        viewModelScope.launch {
            when (val result = manager.install(state.preview.token)) {
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

    fun uninstall(id: String) {
        viewModelScope.launch { manager.uninstall(id) }
    }

    /** 准备依赖：在本地容器、插件安装目录执行；同一插件不并发启动两次。 */
    fun prepareDependencies(id: String) {
        if (_dependencyStates.value[id]?.running == true) return
        _dependencyStates.update { it + (id to PluginDependencyUiState(running = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val state = when (val result = manager.prepareDependencies(id)) {
                is PluginDependencyResult.Ready -> PluginDependencyUiState(ready = true)
                is PluginDependencyResult.MissingRuntime ->
                    PluginDependencyUiState(ready = false, message = result.message)
                is PluginDependencyResult.Failed ->
                    PluginDependencyUiState(ready = false, message = result.message)
            }
            _dependencyStates.update { it + (id to state) }
        }
    }

    /** 插件专属数据目录；设置页入口与桥必须使用同一落点。 */
    fun dataDirFor(pluginId: String): File = manager.dataDirFor(pluginId)

    /** 为插件设置页构造宿主桥；桥与 WebView 一起缓存，由 [PluginWebViewCache] 统一释放。 */
    fun createWebBridge(
        page: PluginSettingsPage,
        pluginVersion: String,
        languageTag: String,
        theme: () -> String,
        onClosePage: () -> Unit,
        post: (String) -> Unit
    ): PluginWebBridge {
        val workspaceName = workspaceRepository.currentPath()
            .trimEnd('/')
            .substringAfterLast('/')
            .ifBlank { "workspace" }
        return PluginWebBridge(
            context = context,
            page = page,
            dataDir = manager.dataDirFor(page.pluginId),
            pluginVersion = pluginVersion,
            languageTag = languageTag,
            workspaceName = workspaceName,
            fileAccess = fileAccess,
            homeResolver = pathHomeResolver,
            commandEngine = commandEngine,
            workspacePath = { workspaceRepository.currentPath() },
            eventBus = eventBus,
            theme = theme,
            onClosePage = onClosePage,
            post = post
        )
    }

    private companion object {
        const val TAG = "PluginsViewModel"
    }
}
