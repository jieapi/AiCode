package com.aicode.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.plugin.data.PluginDevPackageBuilder
import com.aicode.feature.plugin.data.PluginDevSettingsRepository
import com.aicode.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.ManifestParseResult
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
import com.aicode.feature.plugin.runtime.PluginProviderRegistrar
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginShizukuRunner
import com.aicode.feature.plugin.runtime.PluginTerminalRunner
import com.aicode.feature.plugin.runtime.PluginTipManager
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        val error: PluginError? = null,
        /** 开发者侧载来源目录名；普通 zip 导入为 null。 */
        val sourceDirName: String? = null
    ) : PluginImportState

    data class Failed(val errors: List<PluginError>) : PluginImportState
}

/** 依赖准备的界面状态；按插件 ID 隔离。 */
data class PluginDependencyUiState(
    val running: Boolean = false,
    val ready: Boolean? = null,
    val message: String? = null
)

/** 开发者目录里的一个插件项目；安装/重载按目录重新打包。 */
data class PluginDevProject(
    val dirName: String,
    val pluginId: String,
    val version: String,
    val installedVersion: String?,
    val lastModified: Long,
    val error: String? = null
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val commandEngine: CommandEngine,
    private val eventBus: PluginEventBus,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val pluginTerminalRunner: PluginTerminalRunner,
    private val pluginProviderRegistrar: PluginProviderRegistrar,
    private val shizukuPermissionRepository: PluginShizukuPermissionRepository,
    private val shizukuManager: ShizukuManager,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val devSettingsRepository: PluginDevSettingsRepository
) : ViewModel() {

    val plugins: StateFlow<List<InstalledPlugin>> = manager.plugins

    val loaded: StateFlow<Boolean> = manager.isLoaded

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    /** 已授权的插件；停用/卸载后自动从集合中消失。 */
    val shizukuGrantedPluginIds: StateFlow<Set<String>> =
        combine(manager.plugins, shizukuPermissionRepository.grants) { plugins, grants ->
            plugins.filter { plugin ->
                plugin.isActive && plugin.content.shizuku != null && grants.containsKey(plugin.id)
            }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _importState = MutableStateFlow<PluginImportState>(PluginImportState.Idle)
    val importState: StateFlow<PluginImportState> = _importState.asStateFlow()

    private val _dependencyStates = MutableStateFlow<Map<String, PluginDependencyUiState>>(emptyMap())
    val dependencyStates: StateFlow<Map<String, PluginDependencyUiState>> = _dependencyStates.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    /** 开发者目录固定挂在容器 `~/.aicode/plugin-dev`，每个子目录是一个插件项目。 */
    val devRootPath: String = "~/.aicode/plugin-dev"

    private val _devProjects = MutableStateFlow<List<PluginDevProject>>(emptyList())
    val devProjects: StateFlow<List<PluginDevProject>> = _devProjects.asStateFlow()

    private var devWatcherJob: Job? = null
    private val devLastLoaded = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch(Dispatchers.IO) { manager.ensureLoaded() }
        viewModelScope.launch {
            devSettingsRepository.enabled.collect { enabled ->
                _devMode.value = enabled
                if (enabled) {
                    refreshDevProjects()
                    startDevWatcher()
                } else {
                    devWatcherJob?.cancel()
                    devWatcherJob = null
                    _devProjects.value = emptyList()
                }
            }
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = manager.install(state.preview.token)) {
                is PluginInstallResult.Installed -> {
                    syncProviders(result.pluginId)
                    state.sourceDirName?.let { dirName ->
                        devLastLoaded[dirName] = devDirModified(dirName)
                        refreshDevProjects()
                    }
                    _importState.value = PluginImportState.Idle
                }
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

    fun setDevMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { devSettingsRepository.setEnabled(enabled) }
    }

    fun refreshDevProjects() {
        viewModelScope.launch(Dispatchers.IO) { _devProjects.value = scanDevProjects() }
    }

    /** 从开发目录打 zip 后走现有预览流程，安装仍需使用者在预览里确认。 */
    fun installDevProject(project: PluginDevProject) {
        if (_importState.value !is PluginImportState.Idle && _importState.value !is PluginImportState.Failed) return
        _importState.value = PluginImportState.Reading
        viewModelScope.launch(Dispatchers.IO) {
            val zip = File(context.cacheDir, "plugin-dev-${project.dirName}.zip")
            val result = try {
                PluginDevPackageBuilder.zip(File(manager.devDir, project.dirName), zip)
                zip.inputStream().use { manager.preview(it) }
            } catch (e: Exception) {
                FileLogger.w(TAG, "打包开发插件失败: ${project.dirName}", e)
                PluginPreviewResult.Failed(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
            } finally {
                zip.delete()
            }
            _importState.value = when (result) {
                is PluginPreviewResult.Ready -> PluginImportState.Preview(
                    preview = result.preview,
                    sourceDirName = project.dirName
                )
                is PluginPreviewResult.Failed -> PluginImportState.Failed(result.errors)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (manager.setEnabled(id, enabled)) {
                if (enabled) syncProviders(id) else removeProviders(id)
            }
            if (!enabled) {
                pluginTerminalRunner.closePlugin(id)
                tipManager.dismissPlugin(id)
            }
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (manager.uninstall(id)) {
                removeProviders(id)
                shizukuPermissionRepository.revoke(id)
            }
            pluginTerminalRunner.closePlugin(id)
            tipManager.dismissPlugin(id)
        }
    }

    /** 插件级 Shizuku 授权；关闭后该插件无法再通过 Shizuku 执行命令。 */
    fun setShizukuGranted(plugin: InstalledPlugin, granted: Boolean) {
        if (plugin.content.shizuku == null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (granted) {
                shizukuPermissionRepository.grant(plugin.id)
            } else {
                shizukuPermissionRepository.revoke(plugin.id)
            }
        }
    }

    /** 提供商注册失败不影响安装/启停本身；插件页重新切换一次即可重试。 */
    private suspend fun syncProviders(pluginId: String) {
        try {
            pluginProviderRegistrar.sync(pluginId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "同步插件提供商失败: $pluginId", e)
        }
    }

    private suspend fun removeProviders(pluginId: String) {
        try {
            pluginProviderRegistrar.removeAll(pluginId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "移除插件提供商失败: $pluginId", e)
        }
    }

    /** 扫描开发目录：每个含 plugin.json 的子目录视为一个项目。 */
    private fun scanDevProjects(): List<PluginDevProject> {
        manager.ensureLoaded()
        val installed = manager.plugins.value.associate { it.id to it.record.version }
        return manager.devDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                val manifestFile = File(dir, "plugin.json")
                val parsed = runCatching {
                    if (manifestFile.isFile) PluginManifestParser.parse(manifestFile.readText()) else null
                }.getOrNull()
                val lastModified = dir.walkTopDown().filter { it.isFile }
                    .maxOfOrNull { it.lastModified() } ?: 0L
                when (parsed) {
                    is ManifestParseResult.Success -> PluginDevProject(
                        dirName = dir.name,
                        pluginId = parsed.manifest.id,
                        version = parsed.manifest.version.toString(),
                        installedVersion = installed[parsed.manifest.id],
                        lastModified = lastModified
                    )
                    else -> PluginDevProject(
                        dirName = dir.name,
                        pluginId = "",
                        version = "",
                        installedVersion = null,
                        lastModified = lastModified,
                        error = "manifest"
                    )
                }
            }
            .sortedBy { it.dirName }
    }

    /** 开发者模式开启期间轮询目录 mtime；已安装项目变化时自动重新打包替换。 */
    private fun startDevWatcher() {
        if (devWatcherJob?.isActive == true) return
        devWatcherJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _devMode.value) {
                delay(DEV_POLL_MS)
                if (!_devMode.value) break
                val projects = scanDevProjects()
                _devProjects.value = projects
                projects.forEach { project ->
                    if (project.error != null) return@forEach
                    val baseline = devLastLoaded[project.dirName]
                    val versionChanged = project.installedVersion != null &&
                        project.installedVersion != project.version
                    if (baseline == null && !versionChanged) {
                        if (project.installedVersion != null) devLastLoaded[project.dirName] = project.lastModified
                        return@forEach
                    }
                    if (project.installedVersion != null && (versionChanged || project.lastModified > (baseline ?: 0L))) {
                        if (reloadDevProject(project)) {
                            devLastLoaded[project.dirName] = project.lastModified
                            FileLogger.i(TAG, "开发插件已重载: ${project.dirName}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun reloadDevProject(project: PluginDevProject): Boolean {
        val zip = File(context.cacheDir, "plugin-dev-${project.dirName}.zip")
        return try {
            PluginDevPackageBuilder.zip(File(manager.devDir, project.dirName), zip)
            val preview = zip.inputStream().use { manager.preview(it) }
            if (preview !is PluginPreviewResult.Ready) return false
            if (preview.preview.manifest.id != project.pluginId) return false
            if (preview.preview.installedVersion == null) return false
            when (val result = manager.install(preview.preview.token)) {
                is PluginInstallResult.Installed -> {
                    syncProviders(result.pluginId)
                    true
                }
                is PluginInstallResult.Failed -> false
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "自动重载开发插件失败: ${project.dirName}", e)
            false
        } finally {
            zip.delete()
        }
    }

    private fun devDirModified(dirName: String): Long =
        File(manager.devDir, dirName).walkTopDown().filter { it.isFile }
            .maxOfOrNull { it.lastModified() } ?: 0L

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
            pluginManager = manager,
            pluginRuntimeManager = pluginRuntimeManager,
            terminalRunner = pluginTerminalRunner,
            shizukuRunner = shizukuRunner,
            tipManager = tipManager,
            theme = theme,
            onClosePage = onClosePage,
            post = post
        )
    }

    private companion object {
        const val TAG = "PluginsViewModel"
        const val DEV_POLL_MS = 2_000L
    }
}
