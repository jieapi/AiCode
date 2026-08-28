package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.util.LogLevel
import com.aicode.R
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.backup.presentation.BackupSection
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.presentation.SkillUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.BarChart2
import compose.icons.feathericons.Book
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Download
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Image
import compose.icons.feathericons.Info
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Server
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.presentation.component.TerminalSettingsSheet

/** 使用手册 Wiki 地址。 */
private const val USER_GUIDE_WIKI_URL = "https://github.com/jieapi/aicode/wiki"

/** 设置页内部二级菜单分区。Menu 为首页菜单，其余为各自的二级页。 */
internal enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    Providers(R.string.settings_providers),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    Mcp(R.string.settings_mcp),
    Skills(R.string.settings_skills),
    SkillDetail(R.string.settings_skills),
    Container(R.string.settings_container),
    ContainerDownloads(R.string.container_download_image),
    Proxy(R.string.proxy_title),
    Log(R.string.settings_log),
    Permissions(R.string.settings_permissions),
    AppPermissions(R.string.settings_app_permissions),
    RemoteServers(R.string.settings_remote_servers),
    TokenStats(R.string.settings_token_stats_title),
    Backup(R.string.settings_backup),
    About(R.string.settings_about)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit = {}
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logViewerState by viewModel.logViewerState.collectAsStateWithLifecycle()
    val mcpEntries by viewModel.mcpEntries.collectAsStateWithLifecycle()
    val mcpStatuses by viewModel.mcpStatuses.collectAsStateWithLifecycle()
    val mcpReloading by viewModel.mcpReloading.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val globalRules by viewModel.globalRules.collectAsStateWithLifecycle()
    val projectRules by viewModel.projectRules.collectAsStateWithLifecycle()
    val currentProjectName by viewModel.currentProjectName.collectAsStateWithLifecycle()
    val keepaliveEnabled by viewModel.keepaliveEnabled.collectAsStateWithLifecycle()
    val agentSoundEnabled by viewModel.agentSoundEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val backgroundImagePath by viewModel.backgroundImagePath.collectAsStateWithLifecycle()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val visionProviderId by viewModel.visionProviderId.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val compactionProviderId by viewModel.compactionProviderId.collectAsStateWithLifecycle()
    val compactionModel by viewModel.compactionModel.collectAsStateWithLifecycle()
    val titleProviderId by viewModel.titleProviderId.collectAsStateWithLifecycle()
    val titleModel by viewModel.titleModel.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val containerProfiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val defaultContainerId by viewModel.defaultContainerId.collectAsStateWithLifecycle()
    val containerOsMap by viewModel.containerOsMap.collectAsStateWithLifecycle()
    val remoteConnections by viewModel.remoteConnections.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStats.collectAsStateWithLifecycle()
    val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsStateWithLifecycle()
    val updateCheckChannel by viewModel.updateCheckChannel.collectAsStateWithLifecycle()
    val containerAnnouncementText by viewModel.containerAnnouncementText.collectAsStateWithLifecycle()
    val containerAnnouncementOutdated by viewModel.containerAnnouncementOutdated.collectAsStateWithLifecycle()
    val imageCatalog by viewModel.imageCatalog.collectAsStateWithLifecycle()
    val imageDownload by viewModel.containerImageDownload.collectAsStateWithLifecycle()
    val imageSourceOptions by viewModel.imageSourceOptions.collectAsStateWithLifecycle()
    val selectedImageSource by viewModel.selectedImageSource.collectAsStateWithLifecycle()
    val downloadedImages by viewModel.downloadedImages.collectAsStateWithLifecycle()
    val sourceUnavailableIds by viewModel.sourceUnavailableIds.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    val proxyConfig by viewModel.proxyConfig.collectAsStateWithLifecycle()
    val proxyTestState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    var showTerminalSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.aicode.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }


    var section by remember { mutableStateOf(SettingsSection.Menu) }
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerEntry?>(null) }
    var selectedSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    var skillToDelete by remember { mutableStateOf<SkillUiEntry?>(null) }
    // 技能正文 Markdown 解析缓存：详情页多次进入复用，避免重复解析卡顿
    val skillMarkdownCache = remember { MarkdownRenderCache() }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showContainerAnnouncement by remember { mutableStateOf(false) }
    var showImageSourceSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showResetTokenStats by remember { mutableStateOf(false) }

    // 处于二级页时，系统返回键先回到上一层；首页时交还给上层导航。
    BackHandler(enabled = section != SettingsSection.Menu) {
        when (section) {
            SettingsSection.ProviderEditor -> section = SettingsSection.Providers
            SettingsSection.Log -> section = logReturnSection
            SettingsSection.SkillDetail -> section = SettingsSection.Skills
            SettingsSection.ContainerDownloads -> section = SettingsSection.Container
            else -> section = SettingsSection.Menu
        }
    }

    // settingsViewModel 为 Activity 级共享实例，每次进入设置页重新扫描技能，反映磁盘增删改。
    LaunchedEffect(Unit) { viewModel.refreshSkills() }

    // 首次（或公告内容更新后）进入「容器与镜像」页自动弹出使用说明；哈希比对在 ViewModel 完成。
    LaunchedEffect(section, containerAnnouncementOutdated) {
        if (section == SettingsSection.Container && containerAnnouncementOutdated && containerAnnouncementText.isNotBlank()) {
            showContainerAnnouncement = true
        }
    }

    // 提供商编辑为独立全屏页，直接渲染（不嵌套 Scaffold）
    if (section == SettingsSection.ProviderEditor) {
        ProviderEditorScreen(
            viewModel = viewModel,
            initialProvider = editingProvider,
            onNavigateBack = { section = SettingsSection.Providers },
            onSave = { provider ->
                viewModel.saveProvider(provider)
            }
        )
        return
    }

    if (section == SettingsSection.RemoteServers) {
        com.aicode.feature.workspace.presentation.remote.RemoteServerScreen(
            onNavigateBack = { section = SettingsSection.Menu }
        )
        return
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (section == SettingsSection.SkillDetail) {
                            selectedSkill?.name ?: stringResource(section.titleRes)
                        } else {
                            stringResource(section.titleRes)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (section == SettingsSection.Menu) {
                            onNavigateBack()
                        } else if (section == SettingsSection.Log) {
                            section = logReturnSection
                        } else if (section == SettingsSection.SkillDetail) {
                            section = SettingsSection.Skills
                        } else {
                            section = SettingsSection.Menu
                        }
                    }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    when (section) {
                        SettingsSection.Providers -> IconButton(onClick = {
                            editingProvider = null
                            section = SettingsSection.ProviderEditor
                        }) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider))
                        }
                        SettingsSection.Mcp -> {
                            IconButton(onClick = { viewModel.reloadMcp() }) {
                                if (mcpReloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(R.string.settings_reconnect),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingMcp = null
                                showMcpDialog = true
                            }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.settings_add_mcp_server),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Container -> {
                            IconButton(onClick = { showContainerAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.container_announcement_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { section = SettingsSection.ContainerDownloads }) {
                                Icon(
                                    FeatherIcons.Download,
                                    contentDescription = stringResource(R.string.container_download_image),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showContainerAddSheet = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image))
                            }
                        }
                        SettingsSection.ContainerDownloads -> {
                            IconButton(onClick = { showImageSourceSheet = true }) {
                                Icon(
                                    FeatherIcons.Globe,
                                    contentDescription = stringResource(R.string.container_download_current_source_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Log -> {
                            IconButton(onClick = { viewModel.refreshLogs() }) {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs))
                            }
                        }
                        SettingsSection.TokenStats -> {
                            IconButton(onClick = { showResetTokenStats = true }) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.settings_token_stats_reset),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (section) {
                SettingsSection.Menu -> SettingsMenu(
                    themeMode = themeMode,
                    terminalSettings = terminalSettings,
                    currentLanguageDisplayName = currentLanguageDisplayName,
                    backgroundImagePath = backgroundImagePath,
                    backgroundAlpha = backgroundAlpha,
                    onOpenThemeSheet = { showThemeSheet = true },
                    onOpenTerminalSettingsSheet = { showTerminalSettingsSheet = true },
                    onOpenBackgroundSheet = { showBackgroundSheet = true },
                    onOpenLanguageSheet = { showLanguageSheet = true },
                    onOpenManual = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_WIKI_URL))
                        )
                    },
                    onOpen = {
                        if (it == SettingsSection.Log) {
                            logReturnSection = SettingsSection.Menu
                            viewModel.refreshLogs(filterServerName = null)
                        }
                        section = it
                    }
                )
                SettingsSection.Providers -> ProvidersSection(
                    providers = providers,
                    onEdit = {
                        editingProvider = it
                        section = SettingsSection.ProviderEditor
                    },
                    onDelete = { viewModel.deleteProvider(it.id) },
                    onReorder = { fromIndex, toIndex ->
                        viewModel.reorderProviders(fromIndex, toIndex)
                    }
                )
                SettingsSection.DefaultModels -> DefaultModelsSection(
                    providers = providers,
                    visionProviderId = visionProviderId,
                    visionModel = visionModel,
                    compactionProviderId = compactionProviderId,
                    compactionModel = compactionModel,
                    titleProviderId = titleProviderId,
                    titleModel = titleModel,
                    modelMetadata = modelMetadata,
                    onLoadMetadata = { viewModel.loadAllModelMetadata() },
                    onSelectVisionModel = { pid, m -> viewModel.setVisionModel(pid, m) },
                    onClearVisionModel = { viewModel.clearVisionModel() },
                    onSelectCompactionModel = { pid, m -> viewModel.setCompactionModel(pid, m) },
                    onClearCompactionModel = { viewModel.clearCompactionModel() },
                    onSelectTitleModel = { pid, m -> viewModel.setTitleModel(pid, m) },
                    onClearTitleModel = { viewModel.clearTitleModel() }
                )
                SettingsSection.Mcp -> McpSection(
                    entries = mcpEntries,
                    statuses = mcpStatuses,
                    reloading = mcpReloading,
                    onReload = { viewModel.reloadMcp() },
                    onToggle = { name, enabled, scope -> viewModel.setMcpServerEnabled(name, enabled, scope) },
                    onEdit = {
                        editingMcp = it
                        showMcpDialog = true
                    },
                    onDelete = { name, scope -> viewModel.deleteMcpServer(name, scope) }
                )
                SettingsSection.Skills -> SkillsSection(
                    projectName = currentProjectName,
                    entries = skills,
                    onDelete = { skillToDelete = it },
                    onOpenDetail = {
                        selectedSkill = it
                        section = SettingsSection.SkillDetail
                    }
                )
                SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                    SkillDetailSection(
                        entry = entry,
                        cache = skillMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSkillEnabled(entry.name, enabled, entry.scope)
                            // 同步更新详情页快照，开关立即响应
                            selectedSkill = selectedSkill?.copy(disabled = !enabled)
                        }
                    )
                }
                SettingsSection.Container -> ContainerSection(
                    profiles = containerProfiles,
                    activeProfileId = activeProfileId,
                    defaultContainerId = defaultContainerId,
                    osMap = containerOsMap,
                    showAddSheetExternal = showContainerAddSheet,
                    onDismissAddSheet = { showContainerAddSheet = false },
                    onSelect = { viewModel.setActiveContainerProfile(it) },
                    onSetDefaultContainer = { viewModel.setDefaultContainerId(it) },
                    onSaveCustom = { viewModel.saveCustomContainerProfile(it) },
                    onEditCustom = { viewModel.editCustomContainerProfile(it) },
                    onDeleteProfile = { viewModel.deleteContainerProfile(it) },
                    onSwitchConfirmed = onStopAllAndCloseTerminal,
                    onResetProfile = { viewModel.resetContainer(it) },
                    onRestoreBuiltin = { viewModel.restoreBuiltinAlpine() },
                    remoteConnections = remoteConnections
                )
                SettingsSection.ContainerDownloads -> ContainerImageDownloadSection(
                    catalog = imageCatalog,
                    state = imageDownload,
                    downloadedImages = downloadedImages,
                    sourceUnavailableIds = sourceUnavailableIds,
                    selectedSourceName = viewModel.sourceDisplayName(selectedImageSource, languageTag),
                    onDownload = { entry -> viewModel.startContainerImageDownload(entry, selectedImageSource) },
                    onCancel = { viewModel.cancelContainerImageDownload() },
                    onImport = { entryId, fileUri -> viewModel.importDownloadedImage(entryId, fileUri) },
                    onDelete = { entryId -> viewModel.deleteDownloadedImage(entryId) }
                )
                SettingsSection.Proxy -> ProxySection(
                    config = proxyConfig,
                    testState = proxyTestState,
                    onTestProxy = viewModel::testProxy,
                    onSetEnabled = viewModel::setProxyEnabled,
                    onSetType = viewModel::setProxyType,
                    onSetHost = viewModel::setProxyHost,
                    onSetPort = viewModel::setProxyPort,
                    onSetUsername = viewModel::setProxyUsername,
                    onSetPassword = viewModel::setProxyPassword,
                    onSetNoProxy = viewModel::setProxyNoProxy
                )
                SettingsSection.Log -> LogSection(
                    current = logLevel,
                    onSelect = { viewModel.setLogLevel(it) },
                    state = logViewerState,
                    onSelectFile = { viewModel.selectLogFile(it) },
                    onClearFilter = { viewModel.refreshLogs(filterServerName = null) },
                    onRefresh = { viewModel.refreshLogs(silent = true) }
                )
                SettingsSection.Permissions -> PermissionsSection(
                    projectName = currentProjectName,
                    projectRules = projectRules,
                    globalRules = globalRules,
                    onDeleteProject = { viewModel.deleteProjectRule(it) },
                    onPromote = { viewModel.promoteRuleToGlobal(it) },
                    onDeleteGlobal = { viewModel.deleteGlobalRule(it) }
                )
                SettingsSection.AppPermissions -> AppPermissionsSection(
                    keepaliveEnabled = keepaliveEnabled,
                    onToggleKeepalive = { viewModel.setKeepaliveEnabled(it) },
                    agentSoundEnabled = agentSoundEnabled,
                    onToggleAgentSound = { viewModel.setAgentSoundEnabled(it) }
                )
                SettingsSection.Backup -> {
                    val backupViewModel: com.aicode.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
                }
                SettingsSection.TokenStats -> TokenStatsSection(
                    state = tokenStats,
                    onSelectPeriod = { viewModel.setTokenStatsPeriod(it) },
                    onSelectPage = { viewModel.setTokenStatsPage(it) }
                )
                SettingsSection.ProviderEditor -> {} // 已在上方 early return 处理
                SettingsSection.RemoteServers -> {} // 已在上方 early return 处理
                SettingsSection.About -> AboutSection(
                    updateCheckEnabled = updateCheckEnabled,
                    updateCheckChannel = updateCheckChannel,
                    onToggleUpdateCheck = { viewModel.setUpdateCheckEnabled(it) },
                    onSelectChannel = { viewModel.setUpdateCheckChannel(it) },
                    onCheckUpdate = { viewModel.checkUpdate(manual = true) }
                )
            }
        }
    }

    if (showMcpDialog) {
        McpServerEditDialog(
            initial = editingMcp?.server,
            initialScope = editingMcp?.scope,
            tools = viewModel.getMcpServerTools(editingMcp?.server?.name),
            onRefreshTools = { editingMcp?.let { viewModel.reloadMcpServer(it.server.name) } },
            onOpenLogs = editingMcp?.let { existing ->
                {
                    showMcpDialog = false
                    logReturnSection = SettingsSection.Mcp
                    viewModel.refreshLogs(filterServerName = existing.server.name)
                    section = SettingsSection.Log
                }
            },
            onDismiss = { showMcpDialog = false },
            onSave = { config, scope ->
                viewModel.upsertMcpServer(editingMcp?.server?.name, editingMcp?.scope, config, scope)
                showMcpDialog = false
            }
        )
    }

    if (showThemeSheet) {
        ThemeSelectionSheet(
            selected = themeMode,
            onSelected = { viewModel.setThemeMode(it) },
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showTerminalSettingsSheet) {
        TerminalSettingsSheet(
            settings = terminalSettings,
            onDismiss = { showTerminalSettingsSheet = false },
            onSelectTheme = { viewModel.setTerminalTheme(it) },
            onChangeFontSize = { viewModel.setTerminalFontSize(it) },
            onChangeCursorStyle = { viewModel.setTerminalCursorStyle(it) }
        )
    }

    if (showBackgroundSheet) {
        BackgroundImageSheet(
            imagePath = backgroundImagePath,
            alpha = backgroundAlpha,
            onPickImage = { viewModel.setBackgroundImage(it) },
            onAlphaChange = { viewModel.setBackgroundAlpha(it) },
            onRemove = { viewModel.clearBackgroundImage() },
            onDismiss = { showBackgroundSheet = false }
        )
    }

    if (showLanguageSheet) {
        LanguageSelectionSheet(
            currentTag = languageTag,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showImageSourceSheet) {
        SourceSelectionSheet(
            options = imageSourceOptions.map { it to viewModel.sourceDisplayName(it, languageTag) },
            selected = selectedImageSource,
            onSelected = { viewModel.setImageSource(it) },
            onDismiss = { showImageSourceSheet = false }
        )
    }

    if (showResetTokenStats) {
        AlertDialog(
            onDismissRequest = { showResetTokenStats = false },
            title = { Text(stringResource(R.string.settings_token_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_token_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTokenStats()
                    showResetTokenStats = false
                }) { Text(stringResource(R.string.settings_token_stats_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTokenStats = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    skillToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { skillToDelete = null },
            title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSkill(target.name, target.scope)
                    skillToDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { skillToDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    // 「容器与镜像」使用说明公告：首次进入（或内容更新后）自动弹出，右上角 Info 按钮可随时重看。
    if (showContainerAnnouncement) {
        val dismiss = {
            showContainerAnnouncement = false
            viewModel.markContainerAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.container_announcement_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (containerAnnouncementText.isNotBlank()) {
                        // mikepenz Markdown 内部是 Column（非 LazyColumn），本身不可滚动，
                        // 必须由外层提供滚动容器，否则超出弹窗高度的内容被直接裁剪。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = containerAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_announcement_got_it))
                    }
                }
            }
        }
    }
}

/** 设置首页：每个分区一个可点击的二级菜单入口。 */
@Composable
internal fun SettingsMenu(
    themeMode: AppThemeMode,
    terminalSettings: TerminalSettings,
    currentLanguageDisplayName: String,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onOpenThemeSheet: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onOpenManual: () -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── AI 配置 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_ai))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cloud,
                title = stringResource(SettingsSection.Providers.titleRes),
                onClick = { onOpen(SettingsSection.Providers) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(SettingsSection.DefaultModels.titleRes),
                onClick = { onOpen(SettingsSection.DefaultModels) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Box,
                title = stringResource(SettingsSection.Mcp.titleRes),
                onClick = { onOpen(SettingsSection.Mcp) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(SettingsSection.Skills.titleRes),
                onClick = { onOpen(SettingsSection.Skills) }
            )
        }

        // ── 运行环境 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.HardDrive,
                title = stringResource(SettingsSection.Container.titleRes),
                onClick = { onOpen(SettingsSection.Container) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(SettingsSection.Proxy.titleRes),
                onClick = { onOpen(SettingsSection.Proxy) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(SettingsSection.RemoteServers.titleRes),
                onClick = { onOpen(SettingsSection.RemoteServers) }
            )
        }

        // ── 工具与权限 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_tools))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(SettingsSection.Permissions.titleRes),
                onClick = { onOpen(SettingsSection.Permissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.AppPermissions.titleRes),
                onClick = { onOpen(SettingsSection.AppPermissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(SettingsSection.Log.titleRes),
                onClick = { onOpen(SettingsSection.Log) }
            )
        }

        // ── 外观与语言 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_appearance))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenThemeSheet,
                trailing = {
                    Text(
                        text = stringResource(themeMode.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.terminal_settings_title),
                onClick = onOpenTerminalSettingsSheet,
                trailing = {
                    Text(
                        text = stringResource(terminalSettings.theme.nameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_background_image),
                onClick = onOpenBackgroundSheet,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) "${BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).toInt()}%"
                        else stringResource(R.string.settings_background_image_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.settings_language),
                onClick = onOpenLanguageSheet,
                trailing = {
                    Text(
                        text = currentLanguageDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        // ── 系统 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_system))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BarChart2,
                title = stringResource(SettingsSection.TokenStats.titleRes),
                onClick = { onOpen(SettingsSection.TokenStats) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Save,
                title = stringResource(SettingsSection.Backup.titleRes),
                onClick = { onOpen(SettingsSection.Backup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.BookOpen,
                title = stringResource(R.string.settings_user_guide),
                onClick = onOpenManual
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(SettingsSection.About.titleRes),
                onClick = { onOpen(SettingsSection.About) }
            )
        }
    }

}
