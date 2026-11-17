package com.aicode.feature.plugin.presentation

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginIcon
import com.aicode.feature.plugin.domain.PluginMcpServer
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.McpPill
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.openUrl
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Book
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Code
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Github
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Package
import compose.icons.feathericons.Search
import compose.icons.feathericons.Server
import compose.icons.feathericons.Settings
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Tag
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.User
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap

/** 当前界面语言标签，用于挑选插件自带的多语言文本。 */
@Composable
internal fun currentLanguageTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** 展示用名称：清单可读时取本地化名称，否则（文件损坏等）退回插件 ID。 */
internal fun InstalledPlugin.displayName(languageTag: String): String =
    manifest?.name?.resolve(languageTag) ?: id

@Composable
internal fun mcpDescription(server: PluginMcpServer): String {
    val transport = stringResource(
        if (server.config.isStdio) R.string.plugins_mcp_transport_stdio else R.string.plugins_mcp_transport_http
    )
    val target = if (server.config.isStdio) {
        (listOfNotNull(server.config.command) + server.config.args).joinToString(" ")
    } else {
        server.config.url.orEmpty()
    }
    return if (target.isBlank()) transport else "$transport · $target"
}

/**
 * 设置 → 插件：底部 tab 切换「已安装」「开发中」（开启开发者侧载时）与「插件商店」。
 * 已安装列表保留搜索；条目显示名称、图标、版本/作者标签与启停开关，卸载入口在详情页右上角。
 */
@Composable
internal fun PluginsSection(
    plugins: List<InstalledPlugin>,
    loaded: Boolean,
    devMode: Boolean,
    devProjects: List<PluginDevProject>,
    devRootPath: String,
    refreshing: Boolean,
    devRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRefreshDevProjects: () -> Unit,
    onOpenDevDetail: (PluginDevProject) -> Unit,
    onSetDevEnabled: (PluginDevProject, Boolean) -> Unit,
    onOpenDetail: (InstalledPlugin) -> Unit,
    onToggleEnabled: (InstalledPlugin, Boolean) -> Unit,
    onShowStorePreview: (PluginStorePreview) -> Unit,
    onOpenStoreDetail: (PluginStoreItem) -> Unit
) {
    if (!loaded) return

    val languageTag = currentLanguageTag()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val storeViewModel: PluginStoreViewModel = hiltViewModel()
    val storeState by storeViewModel.state.collectAsStateWithLifecycle()
    val pageCount = if (devMode) 3 else 2
    val installedListState = rememberLazyListState()
    val devListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        // 开发者侧载开关会改变 tab 数量；连同底栏一起重建，避免底栏内部缓存的 tab 位置残留。
        key(pageCount) {
            val pagerState = rememberPagerState { pageCount }
            val listScrolling by remember {
                derivedStateOf {
                    when {
                        pagerState.currentPage == 0 -> installedListState.isScrollInProgress
                        pageCount == 3 && pagerState.currentPage == 1 -> devListState.isScrollInProgress
                        else -> false
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when {
                    page == 0 -> InstalledPluginsPage(
                        plugins = plugins,
                        languageTag = languageTag,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        listState = installedListState,
                        updates = storeState.updatesByPluginId,
                        refreshing = refreshing,
                        onRefresh = {
                            onRefresh()
                            storeViewModel.refresh()
                        },
                        onOpenDetail = onOpenDetail,
                        onToggleEnabled = onToggleEnabled
                    )
                    pageCount == 3 && page == 1 -> DevPluginsPage(
                        projects = devProjects,
                        plugins = plugins,
                        devRootPath = devRootPath,
                        listState = devListState,
                        refreshing = devRefreshing,
                        onRefresh = onRefreshDevProjects,
                        onOpenDetail = onOpenDevDetail,
                        onSetEnabled = onSetDevEnabled
                    )
                    else -> PluginStorePage(
                        onShowPreview = onShowStorePreview,
                        onOpenDetail = onOpenStoreDetail,
                        viewModel = storeViewModel
                    )
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = buildList {
                    add(FloatingTabItem(FeatherIcons.Package, stringResource(R.string.plugins_tab_installed)))
                    if (devMode) {
                        add(FloatingTabItem(FeatherIcons.Code, stringResource(R.string.plugins_tab_dev)))
                    }
                    add(FloatingTabItem(FeatherIcons.ShoppingBag, stringResource(R.string.plugins_tab_store)))
                },
                maskColor = settingsPageBackground(),
                isScrolling = listScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledPluginsPage(
    plugins: List<InstalledPlugin>,
    languageTag: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    listState: LazyListState,
    updates: Map<String, PluginStoreItem>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenDetail: (InstalledPlugin) -> Unit,
    onToggleEnabled: (InstalledPlugin, Boolean) -> Unit
) {
    val filteredPlugins = remember(plugins, searchQuery, languageTag) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            plugins
        } else {
            plugins.filter { plugin ->
                plugin.displayName(languageTag).lowercase().contains(query) ||
                    plugin.id.lowercase().contains(query) ||
                    plugin.manifest?.publisher?.lowercase()?.contains(query) == true ||
                    plugin.manifest?.description?.resolve(languageTag)?.lowercase()?.contains(query) == true
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (plugins.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.Package,
                        title = stringResource(R.string.plugins_empty_title),
                        hint = stringResource(R.string.plugins_empty_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
            }
            if (plugins.size >= 3) {
                item(key = "search", contentType = "search") {
                    ModelSearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.plugins_search_placeholder),
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }

            if (filteredPlugins.isEmpty() && plugins.isNotEmpty()) {
                item(key = "search-empty", contentType = "empty") {
                    PluginSearchEmptyState()
                }
            } else {
                itemsIndexed(
                    items = filteredPlugins,
                    key = { _, plugin -> plugin.id },
                    contentType = { _, _ -> "plugin-row" }
                ) { index, plugin ->
                    LazyGroupItem(index = index, lastIndex = filteredPlugins.lastIndex) {
                        PluginRow(
                            plugin = plugin,
                            languageTag = languageTag,
                            update = updates[plugin.id],
                            onToggleEnabled = { onToggleEnabled(plugin, it) },
                            onClick = { onOpenDetail(plugin) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginStorePage(
    onShowPreview: (PluginStorePreview) -> Unit,
    onOpenDetail: (PluginStoreItem) -> Unit,
    viewModel: PluginStoreViewModel
) {
    PluginStoreSection(
        onShowPreview = onShowPreview,
        onOpenDetail = onOpenDetail,
        viewModel = viewModel
    )
}

/** 列表搜索无结果时的共用占位。 */
@Composable
internal fun PluginSearchEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                FeatherIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(R.string.plugins_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单个插件列表行：名称独占一行；下一行是图标、版本/作者标签、启停开关与详情箭头，支持左滑卸载。
 */
@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    languageTag: String,
    update: PluginStoreItem?,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val version = plugin.manifest?.version?.toString() ?: plugin.record.version
    val publisher = plugin.manifest?.publisher
    val switchEnabled = plugin.status == PluginStatus.OK

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            PluginIconContent(
                icon = plugin.content.icon,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = 22.dp,
                shape = RoundedCornerShape(8.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plugin.displayName(languageTag),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (update != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.plugins_update_version_transition,
                            plugin.record.version,
                            update.plugin.version.orEmpty()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                McpPill(
                    text = "v$version",
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer
                )
                if (!publisher.isNullOrBlank()) {
                    McpPill(
                        text = publisher,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        backgroundColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                }
                if (update != null) {
                    McpPill(
                        text = stringResource(R.string.plugins_update_available),
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        AppSwitch(
            checked = plugin.record.enabled && switchEnabled,
            onCheckedChange = onToggleEnabled,
            enabled = switchEnabled
        )

        Spacer(modifier = Modifier.width(Spacing.xs))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 插件详情与配置页面：
 * 采用 SegmentedTabs 分段控件，将【插件管理（AiCode 固定配置）】与【插件配置（WebView 专属配置页面）】清晰分离，
 * 若插件未声明专属配置页面（configPage）则直接全屏展示原生管理页面，无缝融入 App 主题。
 */
@Composable
internal fun PluginDetailSection(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    bridgeFactory: ((page: PluginPage, post: (String) -> Unit, onClosePage: () -> Unit) -> PluginWebBridge)? = null,
    onClosePage: () -> Unit = {},
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {},
    shizukuGranted: Boolean = false,
    shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
    onSetShizukuGranted: (Boolean) -> Unit = {},
    overlayGranted: Boolean = false,
    onSetOverlayGranted: (Boolean) -> Unit = {},
    backupGranted: Boolean = false,
    onSetBackupGranted: (Boolean) -> Unit = {}
) {
    val languageTag = currentLanguageTag()
    val configPage = plugin.content.configPage
    val hasConfigPage = plugin.isActive && configPage != null && bridgeFactory != null
    var selectedTab by remember(plugin.id, plugin.isActive) { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (hasConfigPage) {
            SegmentedTabs(
                selected = selectedTab,
                labels = listOf(
                    stringResource(R.string.plugins_tab_manage),
                    stringResource(R.string.plugins_tab_config)
                ),
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm, bottom = Spacing.sm)
            )
        }

        if (!hasConfigPage || selectedTab == 0) {
            // Tab 0: AiCode 固定配置与管理页面
            PluginNativeConfigContent(
                plugin = plugin,
                languageTag = languageTag,
                onToggleEnabled = onToggleEnabled,
                dependencyState = dependencyState,
                onPrepareDependencies = onPrepareDependencies,
                shizukuGranted = shizukuGranted,
                shizukuState = shizukuState,
                onSetShizukuGranted = onSetShizukuGranted,
                overlayGranted = overlayGranted,
                onSetOverlayGranted = onSetOverlayGranted,
                backupGranted = backupGranted,
                onSetBackupGranted = onSetBackupGranted
            )
        } else {
            // Tab 1: 插件自己的专属 WebView 配置页面
            PluginWebPanelScreen(
                page = configPage,
                onClosePage = onClosePage,
                bridgeFactory = { post, onClose ->
                    bridgeFactory(configPage, post, onClose)
                },
                modifier = Modifier.fillMaxSize(),
                applySystemBarInsets = false
            )
        }
    }
}

/** 插件原生的固定配置列表。 */
@Composable
private fun PluginNativeConfigContent(
    plugin: InstalledPlugin,
    languageTag: String,
    onToggleEnabled: (Boolean) -> Unit,
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {},
    shizukuGranted: Boolean = false,
    shizukuState: ShizukuState = ShizukuState.NOT_INSTALLED,
    onSetShizukuGranted: (Boolean) -> Unit = {},
    overlayGranted: Boolean = false,
    onSetOverlayGranted: (Boolean) -> Unit = {},
    backupGranted: Boolean = false,
    onSetBackupGranted: (Boolean) -> Unit = {}
) {
    val manifest = plugin.manifest
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Spacer(Modifier.height(Spacing.xs))

        if (manifest != null) {
            PluginHeaderCard(
                name = plugin.displayName(languageTag),
                description = manifest.description.resolve(languageTag),
                icon = plugin.content.icon
            )
        }

        // 启停开关
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.plugins_enable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(
                    checked = plugin.record.enabled && plugin.status == PluginStatus.OK,
                    onCheckedChange = onToggleEnabled,
                    enabled = plugin.status == PluginStatus.OK
                )
            }
        }

        if (plugin.content.shizuku != null || plugin.content.overlay != null || plugin.content.backup != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_permissions_section))
            SettingsGroup {
                plugin.content.shizuku?.let { declaration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.plugins_shizuku_section),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = declaration.purpose,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (shizukuState != ShizukuState.READY) {
                                Text(
                                    text = stringResource(
                                        R.string.plugins_shizuku_system_state,
                                        stringResource(shizukuState.statusLabelRes())
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        AppSwitch(
                            checked = shizukuGranted,
                            onCheckedChange = onSetShizukuGranted,
                            enabled = plugin.isActive
                        )
                    }
                }

                if (plugin.content.shizuku != null && (plugin.content.overlay != null || plugin.content.backup != null)) {
                    SettingsDivider()
                }

                plugin.content.overlay?.let { declaration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.plugins_overlay_section),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = declaration.purpose,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!canDrawOverlays) {
                                Text(
                                    text = stringResource(R.string.plugins_overlay_system_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        AppSwitch(
                            checked = overlayGranted,
                            onCheckedChange = onSetOverlayGranted,
                            enabled = plugin.isActive && canDrawOverlays
                        )
                    }
                }

                if (plugin.content.overlay != null && plugin.content.backup != null) {
                    SettingsDivider()
                }

                plugin.content.backup?.let { declaration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.plugins_backup_section),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = declaration.purpose,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppSwitch(
                            checked = backupGranted,
                            onCheckedChange = onSetBackupGranted,
                            enabled = plugin.isActive
                        )
                    }
                }
            }
        }

        if (manifest != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Tag,
                    title = stringResource(R.string.plugins_info_version),
                    trailing = { PluginInfoValue("v${manifest.version}") }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.User,
                    title = stringResource(R.string.plugins_info_publisher),
                    trailing = { PluginInfoValue(manifest.publisher) }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Book,
                    title = stringResource(R.string.plugins_info_license),
                    trailing = { PluginInfoValue(manifest.license) }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Github,
                    title = stringResource(R.string.plugins_info_repository),
                    onClick = { openUrl(context, manifest.repository) }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.plugins_info_origin),
                    trailing = { PluginInfoValue(stringResource(R.string.plugins_origin_local)) }
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_section_contents))
            SettingsGroup {
                PluginContentRows(
                    content = plugin.content,
                    languageTag = languageTag,
                    active = plugin.isActive
                )
            }
        }

        val dependency = plugin.content.runtime?.dependencies
        if (dependency != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_dependencies))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val ready = dependencyState?.ready ?: plugin.record.dependenciesReady
                        Text(
                            text = stringResource(
                                if (ready) R.string.plugins_dependencies_ready
                                else R.string.plugins_dependencies_missing
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = dependency.file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dependencyState?.message?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(
                        onClick = onPrepareDependencies,
                        enabled = dependencyState?.running != true
                    ) {
                        Text(
                            stringResource(
                                if (dependencyState?.running == true) R.string.plugins_dependencies_preparing
                                else R.string.plugins_dependencies_prepare
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHeaderCard(name: String, description: String, icon: PluginIcon?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                PluginIconContent(
                    icon = icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 26.dp,
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

internal data class PluginContentItem(
    val icon: ImageVector,
    val title: String,
    val description: String
)

/** 插件贡献逐项：(图标, 标题, 描述)。详情页与安装预览共用同一套条目。 */
@Composable
internal fun pluginContentItems(content: PluginContent, languageTag: String): List<PluginContentItem> {
    val items = mutableListOf<PluginContentItem>()
    content.skills.forEach { entry ->
        items += PluginContentItem(FeatherIcons.BookOpen, entry.localId, entry.skill.description)
    }
    content.agents.forEach { entry ->
        items += PluginContentItem(FeatherIcons.Users, entry.localId, entry.definition.description)
    }
    content.workspaceTools.forEach { entry ->
        items += PluginContentItem(
            FeatherIcons.Tool,
            entry.title.resolve(languageTag),
            entry.description.resolve(languageTag)
        )
    }
    content.uiEntries.forEach { entry ->
        items += PluginContentItem(FeatherIcons.Settings, entry.title.resolve(languageTag), entry.slot.orEmpty())
    }
    content.tools.forEach { entry ->
        items += PluginContentItem(
            FeatherIcons.Zap,
            entry.title.resolve(languageTag),
            entry.description.resolve(languageTag)
        )
    }
    content.hooks.forEach { entry ->
        items += PluginContentItem(FeatherIcons.Activity, entry.event, entry.handler)
    }
    content.mcp.forEach { entry ->
        items += PluginContentItem(FeatherIcons.Server, entry.originalName, mcpDescription(entry))
    }
    content.providers.forEach { entry ->
        items += PluginContentItem(FeatherIcons.Cloud, entry.name.resolve(languageTag), entry.baseUrl)
    }
    content.executionBackends.forEach { entry ->
        val desc = entry.description.resolve(languageTag).ifBlank {
            listOfNotNull(
                if (entry.capabilities.filesystem) stringResource(R.string.plugins_backend_cap_fs) else null,
                if (entry.capabilities.command) stringResource(R.string.plugins_backend_cap_command) else null,
                if (entry.capabilities.terminal) stringResource(R.string.plugins_backend_cap_terminal) else null
            ).joinToString(" · ")
        }
        items += PluginContentItem(
            FeatherIcons.Server,
            entry.name.resolve(languageTag),
            desc
        )
    }
    content.workflows.forEach { entry ->
        val desc = entry.description.resolve(languageTag).ifBlank {
            listOfNotNull(
                if (entry.capabilities.streaming) stringResource(R.string.plugins_workflow_cap_streaming) else null,
                if (entry.capabilities.tools) stringResource(R.string.plugins_workflow_cap_tools) else null,
                if (entry.capabilities.compaction) stringResource(R.string.plugins_workflow_cap_compaction) else null,
                if (entry.capabilities.titleGeneration) stringResource(R.string.plugins_workflow_cap_title_gen) else null
            ).joinToString(" · ")
        }
        items += PluginContentItem(
            FeatherIcons.Cpu,
            entry.title.resolve(languageTag),
            desc
        )
    }
    content.sessions.forEach { entry ->
        val desc = entry.description.resolve(languageTag).ifBlank {
            listOfNotNull(
                stringResource(R.string.plugins_session_cap_read),
                if (entry.handlers.createSession != null) stringResource(R.string.plugins_session_cap_create) else null,
                if (entry.handlers.renameSession != null) stringResource(R.string.plugins_session_cap_rename) else null,
                if (entry.handlers.pinSession != null) stringResource(R.string.plugins_session_cap_pin) else null,
                if (entry.handlers.deleteSession != null) stringResource(R.string.plugins_session_cap_delete) else null
            ).joinToString(" · ")
        }
        items += PluginContentItem(
            FeatherIcons.MessageSquare,
            entry.title.resolve(languageTag),
            desc
        )
    }
    content.modelAccess?.let { declaration ->
        items += PluginContentItem(
            FeatherIcons.Server,
            stringResource(R.string.plugins_model_access_section),
            "${declaration.purpose} · ${stringResource(R.string.plugins_model_access_desc)}"
        )
    }
    content.shizuku?.let { declaration ->
        items += PluginContentItem(
            FeatherIcons.Terminal,
            stringResource(R.string.plugins_shizuku_section),
            declaration.purpose
        )
    }
    content.overlay?.let { declaration ->
        items += PluginContentItem(
            FeatherIcons.Monitor,
            stringResource(R.string.plugins_overlay_section),
            declaration.purpose
        )
    }
    return items
}

/** 在 [SettingsGroup] 内绘制插件贡献列表；空列表时显示无内容提示。 */
@Composable
internal fun PluginContentRows(content: PluginContent, languageTag: String, active: Boolean) {
    val items = pluginContentItems(content, languageTag)
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.plugins_no_contents),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp)
        )
    } else {
        items.forEachIndexed { index, item ->
            if (index > 0) SettingsDivider()
            ContentRow(
                icon = item.icon,
                title = item.title,
                description = item.description,
                active = active
            )
        }
    }
}

@Composable
internal fun ContentRow(icon: ImageVector, title: String, description: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active) 1f else 0.5f)
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginSettingsSheet(
    devMode: Boolean,
    devRootPath: String,
    indexSourceText: String,
    downloadMirror: String,
    onSetIndexSourceText: (String) -> Unit,
    onSetDownloadMirror: (String) -> Unit,
    onSetDevMode: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.plugins_settings_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            PluginIndexSettingsSection(
                indexSourceText = indexSourceText,
                downloadMirror = downloadMirror,
                onSetIndexSourceText = onSetIndexSourceText,
                onSetDownloadMirror = onSetDownloadMirror
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            PluginDeveloperSection(
                devMode = devMode,
                devRootPath = devRootPath,
                onSetDevMode = onSetDevMode
            )
        }
    }
}

@Composable
private fun PluginIndexSettingsSection(
    indexSourceText: String,
    downloadMirror: String,
    onSetIndexSourceText: (String) -> Unit,
    onSetDownloadMirror: (String) -> Unit
) {
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            AppTextField(
                value = indexSourceText,
                onValueChange = onSetIndexSourceText,
                label = stringResource(R.string.plugins_store_sources_label),
                placeholder = "https://example.com/index.json",
                supportingText = { Text(stringResource(R.string.plugins_store_sources_hint)) },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            val mirrorInvalid = downloadMirror.isNotBlank() &&
                !downloadMirror.trim().let { it.startsWith("http://") || it.startsWith("https://") }
            AppTextField(
                value = downloadMirror,
                onValueChange = onSetDownloadMirror,
                label = stringResource(R.string.plugins_store_mirror_label),
                placeholder = "https://gh-proxy.com/",
                supportingText = {
                    Text(
                        stringResource(
                            if (mirrorInvalid) R.string.plugins_store_mirror_invalid
                            else R.string.plugins_store_mirror_hint
                        )
                    )
                },
                isError = mirrorInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
    }
}

@Composable
private fun PluginDeveloperSection(
    devMode: Boolean,
    devRootPath: String,
    onSetDevMode: (Boolean) -> Unit
) {
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plugins_dev_mode),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.plugins_dev_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppSwitch(checked = devMode, onCheckedChange = onSetDevMode)
        }
        if (devMode) {
            SettingsDivider()
            Text(
                text = stringResource(R.string.plugins_dev_root, devRootPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        }
    }
}

private fun ShizukuState.statusLabelRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_status_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_status_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_status_denied
    ShizukuState.READY -> R.string.settings_shizuku_status_ready
}
