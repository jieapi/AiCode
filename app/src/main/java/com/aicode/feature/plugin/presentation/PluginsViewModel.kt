package com.aicode.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.WatchFilter
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.plugin.data.PluginBackupPermissionRepository
import com.aicode.feature.plugin.data.PluginDevPackageBuilder
import com.aicode.feature.plugin.data.PluginDevSettingsRepository
import com.aicode.feature.plugin.data.PluginOverlayPermissionRepository
import com.aicode.feature.plugin.data.PluginShizukuPermissionRepository
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.ManifestParseResult
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import com.aicode.feature.plugin.runtime.PluginDependencyResult
import com.aicode.feature.plugin.runtime.PluginChatService
import com.aicode.feature.plugin.runtime.PluginHttpService
import com.aicode.feature.plugin.runtime.PluginIntrospectionService
import com.aicode.feature.plugin.runtime.PluginModelService
import com.aicode.feature.plugin.runtime.PluginSecretStore
import com.aicode.feature.plugin.runtime.PluginSystemService
import com.aicode.feature.plugin.runtime.PluginEventBus
import com.aicode.feature.plugin.runtime.PluginHookDispatcher
import com.aicode.feature.plugin.runtime.PluginProviderRegistrar
import com.aicode.feature.plugin.runtime.PluginProviderService
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginShizukuRunner
import com.aicode.feature.plugin.runtime.PluginTerminalRunner
import com.aicode.feature.plugin.runtime.PluginTipManager
import com.aicode.feature.plugin.runtime.PluginToolService
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
        val sourceDirName: String? = null,
        /** 在线安装的来源；本地导入保持默认 local。 */
        val origin: PluginInstallOrigin = PluginInstallOrigin(),
        /** 非官方在线安装需要使用者手动输入该插件的源码仓库地址。 */
        val expectedRepository: String? = null,
        /** 展示用来源名称，例如「AiCode Plugins」。 */
        val sourceLabel: String? = null,
        /** 来源是否在宿主官方白名单内；只有它为 true 时跳过手输仓库。 */
        val officialSource: Boolean = false
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
    val installedDevelopment: Boolean = false,
    val lastModified: Long,
    val error: String? = null,
    val name: LocalizedText? = null,
    val description: LocalizedText? = null,
    val publisher: String? = null
)

/**
 * 同 ID 替换时来源是否变化：索引来源、源码仓库或官方标记任一不同，
 * 以及本地导入与在线来源互换，都算换了来源。
 */
internal fun installSourceChanged(preview: PluginPreview, origin: PluginInstallOrigin): Boolean {
    if (preview.installedVersion == null) return false
    val installedFromIndex = preview.installedSourceId != null
    val installFromIndex = origin.kind == PluginInstallOrigin.KIND_INDEX
    return installedFromIndex != installFromIndex ||
        preview.installedSourceId != origin.sourceId ||
        preview.installedRepository != origin.repository ||
        (preview.installedOfficial ?: false) != origin.official
}

@HiltViewModel
class PluginsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val fileChangeHub: FileChangeHub,
    private val commandEngine: CommandEngine,
    private val eventBus: PluginEventBus,
    private val pluginRuntimeManager: PluginRuntimeManager,
    private val pluginHookDispatcher: PluginHookDispatcher,
    private val pluginTerminalRunner: PluginTerminalRunner,
    private val pluginProviderRegistrar: PluginProviderRegistrar,
    private val shizukuPermissionRepository: PluginShizukuPermissionRepository,
    private val overlayPermissionRepository: PluginOverlayPermissionRepository,
    private val backupPermissionRepository: PluginBackupPermissionRepository,
    private val overlayManager: PluginOverlayManager,
    private val shizukuManager: ShizukuManager,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val devSettingsRepository: PluginDevSettingsRepository,
    private val indexSourceRepository: PluginIndexSourceRepository,
    private val storeSettingsRepository: PluginStoreSettingsRepository,
    private val pageEnvironment: PluginPageEnvironment,
    private val chatService: PluginChatService,
    private val modelService: PluginModelService,
    private val providerService: PluginProviderService,
    private val agentRunStateStore: AgentRunStateStore,
    private val secretStore: PluginSecretStore,
    private val systemService: PluginSystemService,
    private val introspectionService: PluginIntrospectionService,
    private val toolService: PluginToolService,
    private val permissionService: com.aicode.feature.plugin.runtime.PluginPermissionService,
    private val httpService: PluginHttpService,
    private val hostApiDispatcher: com.aicode.feature.plugin.runtime.PluginHostApiDispatcher
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

    /** 已开启悬浮窗授权的插件；停用、卸载或撤销后自动从集合中消失。 */
    val overlayGrantedPluginIds: StateFlow<Set<String>> =
        combine(manager.plugins, overlayPermissionRepository.grants) { plugins, grants ->
            plugins.filter { plugin ->
                plugin.isActive && plugin.content.overlay != null && grants.containsKey(plugin.id)
            }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 已授权接收备份 / 还原事件的插件；停用、卸载或撤销后自动从集合中消失。 */
    val backupGrantedPluginIds: StateFlow<Set<String>> =
        combine(manager.plugins, backupPermissionRepository.grants) { plugins, grants ->
            plugins.filter { plugin ->
                plugin.isActive && plugin.content.backup != null && grants.containsKey(plugin.id)
            }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _importState = MutableStateFlow<PluginImportState>(PluginImportState.Idle)
    val importState: StateFlow<PluginImportState> = _importState.asStateFlow()

    private val _dependencyStates = MutableStateFlow<Map<String, PluginDependencyUiState>>(emptyMap())
    val dependencyStates: StateFlow<Map<String, PluginDependencyUiState>> = _dependencyStates.asStateFlow()

    private val _devMode = MutableStateFlow(false)
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    /** 插件设置里的索引仓库输入框内容，一行一个地址。 */
    private val _indexSourceText = MutableStateFlow("")
    val indexSourceText: StateFlow<String> = _indexSourceText.asStateFlow()

    /** 插件设置里的镜像加速地址；只作用于 GitHub 托管的索引与插件包。 */
    private val _downloadMirror = MutableStateFlow("")
    val downloadMirror: StateFlow<String> = _downloadMirror.asStateFlow()

    private var indexSourceSaveJob: Job? = null
    private var downloadMirrorSaveJob: Job? = null

    /** 开发者目录固定挂在容器 `~/.aicode/plugin-dev`，每个子目录是一个插件项目。 */
    val devRootPath: String = "~/.aicode/plugin-dev"

    private val _devProjects = MutableStateFlow<List<PluginDevProject>>(emptyList())
    val devProjects: StateFlow<List<PluginDevProject>> = _devProjects.asStateFlow()

    private val _devRefreshing = MutableStateFlow(false)
    val devRefreshing: StateFlow<Boolean> = _devRefreshing.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

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
        viewModelScope.launch {
            val urls = indexSourceRepository.customIndexUrls.first()
            if (_indexSourceText.value.isEmpty()) _indexSourceText.value = urls.joinToString("\n")
        }
        viewModelScope.launch {
            if (_downloadMirror.value.isEmpty()) {
                _downloadMirror.value = storeSettingsRepository.downloadMirror.first()
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
            when (val result = manager.install(state.preview.token, state.origin)) {
                is PluginInstallResult.Installed -> {
                    pluginRuntimeManager.closePlugin(result.pluginId)
                    if (installSourceChanged(state.preview, state.origin)) {
                        // 来源变了就不沿用旧包拿到的高危授权，启用状态保持原样
                        shizukuPermissionRepository.revoke(result.pluginId)
                        overlayPermissionRepository.revoke(result.pluginId)
                        overlayManager.closePlugin(result.pluginId)
                    }
                    syncProviders(result.pluginId)
                    pluginHookDispatcher.dispatchPluginInstalled(
                        result.pluginId,
                        state.preview.installedVersion
                    )
                    state.sourceDirName?.let { dirName ->
                        if (!manager.bindDevelopment(result.pluginId, dirName)) {
                            FileLogger.w(TAG, "绑定开发插件失败: $dirName")
                        }
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

    /** 在线商店已完成下载与包校验后，把暂存包交给统一预览/安装弹层。 */
    fun showPreview(storePreview: PluginStorePreview) {
        showPreview(
            preview = storePreview.preview,
            origin = storePreview.origin,
            expectedRepository = storePreview.expectedRepository,
            sourceLabel = storePreview.sourceLabel,
            officialSource = storePreview.officialSource
        )
    }

    fun showPreview(
        preview: PluginPreview,
        origin: PluginInstallOrigin,
        expectedRepository: String?,
        sourceLabel: String?,
        officialSource: Boolean
    ) {
        if (_importState.value !is PluginImportState.Idle && _importState.value !is PluginImportState.Failed) return
        _importState.value = PluginImportState.Preview(
            preview = preview,
            origin = origin,
            expectedRepository = expectedRepository,
            sourceLabel = sourceLabel,
            officialSource = officialSource
        )
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

    /** 索引仓库按输入行保存；停顿后再落盘，避免每个按键都写一次 DataStore。 */
    fun setIndexSourceText(text: String) {
        _indexSourceText.value = text
        indexSourceSaveJob?.cancel()
        indexSourceSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            indexSourceRepository.setCustomSources(text.lines())
        }
    }

    fun setDownloadMirror(text: String) {
        _downloadMirror.value = text
        downloadMirrorSaveJob?.cancel()
        downloadMirrorSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SETTINGS_SAVE_DEBOUNCE_MS)
            storeSettingsRepository.setDownloadMirror(text)
        }
    }

    /** 关闭插件设置弹层时立即落盘，避免刚改完就切走而丢掉暂停写入。 */
    fun savePluginSettings() {
        indexSourceSaveJob?.cancel()
        downloadMirrorSaveJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            indexSourceRepository.setCustomSources(_indexSourceText.value.lines())
            storeSettingsRepository.setDownloadMirror(_downloadMirror.value)
        }
    }

    /** 重新扫描已安装插件；不重启运行中的插件进程。 */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { manager.refresh() }
                .onFailure { FileLogger.w(TAG, "刷新插件失败", it) }
            _refreshing.value = false
        }
    }

    fun refreshDevProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            _devRefreshing.value = true
            try {
                _devProjects.value = scanDevProjects()
            } finally {
                _devRefreshing.value = false
            }
        }
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
            if (!enabled) pluginHookDispatcher.dispatchPluginDisabled(id)
            if (manager.setEnabled(id, enabled)) {
                if (enabled) {
                    syncProviders(id)
                    pluginHookDispatcher.dispatchPluginEnabled(id)
                } else {
                    removeProviders(id)
                }
            }
            if (!enabled) {
                pluginTerminalRunner.closePlugin(id)
                tipManager.dismissPlugin(id)
                overlayManager.closePlugin(id)
            }
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pluginHookDispatcher.dispatchPluginUninstalled(id)
            manager.uninstall(id)
            if (manager.plugins.value.none { it.id == id }) {
                removeProviders(id)
                shizukuPermissionRepository.revoke(id)
                overlayPermissionRepository.revoke(id)
                backupPermissionRepository.revoke(id)
            }
            pluginTerminalRunner.closePlugin(id)
            tipManager.dismissPlugin(id)
            overlayManager.closePlugin(id)
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

    /** 插件级悬浮窗授权；撤销时立即关闭该插件已经打开的窗口。 */
    fun setOverlayGranted(plugin: InstalledPlugin, granted: Boolean) {
        if (plugin.content.overlay == null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (granted) {
                overlayPermissionRepository.grant(plugin.id)
            } else {
                overlayPermissionRepository.revoke(plugin.id)
                overlayManager.closePlugin(plugin.id)
            }
        }
    }

    /** 插件级备份事件授权；撤销后宿主不再向该插件下发备份与还原文件。 */
    fun setBackupGranted(plugin: InstalledPlugin, granted: Boolean) {
        if (plugin.content.backup == null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (granted) {
                backupPermissionRepository.grant(plugin.id)
            } else {
                backupPermissionRepository.revoke(plugin.id)
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
                        installedDevelopment = manager.plugins.value.any { plugin ->
                            plugin.id == parsed.manifest.id && plugin.record.sourceKind == "development" &&
                                plugin.record.devDirName == dir.name
                        },
                        lastModified = lastModified,
                        name = parsed.manifest.name,
                        description = parsed.manifest.description,
                        publisher = parsed.manifest.publisher
                    )
                    else -> PluginDevProject(
                        dirName = dir.name,
                        pluginId = "",
                        version = "",
                        installedVersion = null,
                        installedDevelopment = false,
                        lastModified = lastModified,
                        error = "manifest"
                    )
                }
            }
            .sortedBy { it.dirName }
    }

    /** 开发模式开启期间监听开发目录；事件批次合并后重扫，已安装项目变化时自动重新打包替换。 */
    @OptIn(FlowPreview::class)
    private fun startDevWatcher() {
        if (devWatcherJob?.isActive == true) return
        devWatcherJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    scanDevChanges()
                    fileChangeHub.watchHostDir(
                        hostDir = manager.devDir,
                        recursive = true,
                        filter = WatchFilter(ignoredNames = DEV_IGNORED_NAMES, followGitignore = false),
                        fallbackPoll = true
                    )
                        .debounce(DEV_RELOAD_DEBOUNCE_MS)
                        .collect { scanDevChanges() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "开发插件监听失败，稍后重试", e)
                    delay(DEV_WATCH_RETRY_MS)
                }
            }
        }
    }

    /** 重扫开发目录；已安装项目内容或版本有变化时重新打包替换。 */
    private suspend fun scanDevChanges() {
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
            if (project.installedVersion != null && project.installedDevelopment &&
                (versionChanged || project.lastModified > (baseline ?: 0L))
            ) {
                if (reloadDevProject(project)) {
                    devLastLoaded[project.dirName] = project.lastModified
                    FileLogger.i(TAG, "开发插件已重载: ${project.dirName}")
                }
            }
        }
    }

    private suspend fun reloadDevProject(project: PluginDevProject): Boolean {
        return try {
            if (!manager.reloadDevelopment(project.dirName)) return false
            pluginRuntimeManager.closePlugin(project.pluginId)
            syncProviders(project.pluginId)
            pluginHookDispatcher.dispatchPluginInstalled(project.pluginId, project.installedVersion)
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "自动重载开发插件失败: ${project.dirName}", e)
            false
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
                is PluginDependencyResult.Failed -> PluginDependencyUiState(
                    ready = false,
                    message = if (result.code == "STATE_NOT_SAVED") {
                        context.getString(R.string.plugins_dependencies_state_not_saved)
                    } else result.message
                )
            }
            _dependencyStates.update { it + (id to state) }
        }
    }

    /** 插件专属数据目录；槽位入口与桥必须使用同一落点。 */
    fun dataDirFor(pluginId: String): File = manager.dataDirFor(pluginId)

    /**
     * 槽位动作入口：复核插件仍启用后调用 runtime 的 `ui.invoke`。
     * handler 返回 `{message}` 时交给 UI 显示；失败返回错误文案。
     */
    fun runUiAction(
        entry: PluginUiEntry,
        entryContext: PluginEntryContext,
        onMessage: (String?) -> Unit
    ) {
        val handler = entry.handler ?: return
        viewModelScope.launch {
            val message = try {
                val plugin = manager.plugins.value.firstOrNull { it.id == entry.pluginId && it.isActive }
                    ?: throw IllegalStateException(context.getString(R.string.plugin_tool_not_authorized))
                val runtime = plugin.content.runtime
                    ?: throw IllegalStateException(context.getString(R.string.plugin_runtime_failed))
                val result = withContext(Dispatchers.IO) {
                    pluginRuntimeManager.invoke(
                        pluginId = plugin.id,
                        version = plugin.record.version,
                        runtime = runtime,
                        handler = handler,
                        args = buildJsonObject {
                            put("entryId", entry.localId)
                            put("slot", entry.slot)
                            entryContext.sessionId?.let { put("sessionId", it) }
                            entryContext.path?.let { put("path", it) }
                            entryContext.isDirectory?.let { put("isDirectory", it) }
                            entryContext.selection?.let { put("selection", it) }
                            entryContext.language?.let { put("language", it) }
                            entryContext.lineStart?.let { put("lineStart", it) }
                            entryContext.lineEnd?.let { put("lineEnd", it) }
                        },
                        method = "ui.invoke",
                        timeoutMs = ACTION_TIMEOUT_MS
                    )
                }
                (result as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "插件槽位动作失败: ${entry.pluginId}/${entry.localId}", e)
                e.message ?: context.getString(R.string.plugin_runtime_failed)
            }
            onMessage(message)
        }
    }

    /** 为插件设置页构造宿主桥；桥与 WebView 一起缓存，由 [PluginWebViewCache] 统一释放。 */
    fun createWebBridge(
        page: PluginPage,
        pluginVersion: String,
        languageTag: String,
        theme: () -> String,
        onClosePage: () -> Unit,
        post: (String) -> Unit,
        pageContext: () -> PluginEntryContext = { PluginEntryContext() }
    ): PluginWebBridge {
        return PluginWebBridge(
            context = context,
            page = page,
            pluginVersion = pluginVersion,
            languageTag = languageTag,
            fileAccess = fileAccess,
            homeResolver = pathHomeResolver,
            workspacePath = { workspaceRepository.currentPath() },
            eventBus = eventBus,
            pluginManager = manager,
            pluginRuntimeManager = pluginRuntimeManager,
            modelService = modelService,
            agentRunStateStore = agentRunStateStore,
            hostApiDispatcher = hostApiDispatcher,
            environment = pageEnvironment,
            theme = theme,
            onClosePage = onClosePage,
            post = post,
            pageContext = pageContext
        )
    }

    private companion object {
        const val TAG = "PluginsViewModel"
        const val DEV_RELOAD_DEBOUNCE_MS = 500L
        const val DEV_WATCH_RETRY_MS = 1_000L
        const val SETTINGS_SAVE_DEBOUNCE_MS = 500L
        const val ACTION_TIMEOUT_MS = 10_000L

        /** 构建产物类目录不参与开发目录监听，避免一次编译触发整批重扫。 */
        val DEV_IGNORED_NAMES = setOf(".git", "node_modules", ".venv", "__pycache__", ".idea")
    }
}
