package com.aicode.feature.plugin.presentation

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.util.FileLogger
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHookModes
import com.aicode.feature.plugin.domain.PluginMcpServer
import com.aicode.feature.plugin.domain.PluginSettingsPage
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.McpPill
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Package
import compose.icons.feathericons.Search
import kotlinx.coroutines.delay

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
 * 设置 → 插件：类似 MCP 风格的插件卡片列表。
 * 包含左侧方块图标、主标题、描述摘要、元信息/能力 Pills、状态指示 Pill 与左滑卸载。
 */
@Composable
internal fun PluginsSection(
    plugins: List<InstalledPlugin>,
    loaded: Boolean,
    onOpenDetail: (InstalledPlugin) -> Unit,
    onToggleEnabled: (InstalledPlugin, Boolean) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit = {}
) {
    if (!loaded) return
    if (plugins.isEmpty()) {
        SettingsEmptyState(
            icon = FeatherIcons.Package,
            title = stringResource(R.string.plugins_empty_title),
            hint = stringResource(R.string.plugins_empty_hint),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl, vertical = 48.dp)
        )
        return
    }

    val languageTag = currentLanguageTag()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pluginToUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (plugins.size >= 3) {
                item(key = "search", contentType = "search") {
                    ModelSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.plugins_search_placeholder),
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }

            if (filteredPlugins.isEmpty()) {
                item(key = "search-empty", contentType = "empty") {
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
                            onClick = { onOpenDetail(plugin) },
                            onDelete = { pluginToUninstall = plugin }
                        )
                    }
                }
            }
        }
    }

    pluginToUninstall?.let { target ->
        AlertDialog(
            onDismissRequest = { pluginToUninstall = null },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title)) },
            text = {
                Text(stringResource(R.string.plugins_uninstall_confirm_message, target.displayName(languageTag)))
            },
            confirmButton = {
                TextButton(onClick = {
                    pluginToUninstall = null
                    onUninstall(target)
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pluginToUninstall = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * 单个插件列表行：MCP 风格卡片行。
 * 左侧 40dp 方形图标容器，中间名称/简介/元信息 Pill 标签，右侧状态指示 Pill + Chevron 箭头，支持左滑删除。
 */
@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    languageTag: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when {
        plugin.status == PluginStatus.INVALID -> stringResource(R.string.plugins_status_incompatible)
        plugin.status != PluginStatus.OK -> stringResource(R.string.plugins_status_damaged)
        !plugin.record.enabled -> stringResource(R.string.plugins_status_disabled)
        else -> stringResource(R.string.plugins_status_active)
    }

    val statusColor = when {
        plugin.status == PluginStatus.INVALID || plugin.status != PluginStatus.OK ->
            MaterialTheme.colorScheme.error
        !plugin.record.enabled ->
            MaterialTheme.colorScheme.outline
        else ->
            MaterialTheme.colorScheme.tertiary
    }
    val statusBgColor = statusColor.copy(alpha = 0.12f)

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧容器图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(
                    imageVector = FeatherIcons.Package,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称 / 简介 / 胶囊信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.displayName(languageTag),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                val desc = plugin.manifest?.description?.resolve(languageTag).orEmpty()
                if (desc.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // 版本
                    val version = plugin.manifest?.version?.toString() ?: plugin.record.version
                    McpPill(
                        text = "v$version",
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )

                    // 发布者
                    val publisher = plugin.manifest?.publisher
                    if (!publisher.isNullOrBlank()) {
                        McpPill(
                            text = publisher,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    // 专属配置页
                    if (plugin.content.configPage != null) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_config),
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }

                    // 技能数
                    val skillCount = plugin.content.skills.size
                    if (skillCount > 0) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_skills, skillCount),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }

                    // 工具数
                    val toolCount = plugin.content.tools.size + plugin.content.workspaceTools.size
                    if (toolCount > 0) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_tools, toolCount),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }

                    // 智能体数
                    val agentCount = plugin.content.agents.size
                    if (agentCount > 0) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_agents, agentCount),
                            textColor = MaterialTheme.colorScheme.primary,
                            backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    }

                    // 自定义页面
                    if (plugin.content.settingsPages.isNotEmpty()) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_page),
                            textColor = MaterialTheme.colorScheme.secondary,
                            backgroundColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                        )
                    }

                    // Hook 数量
                    val hookCount = plugin.content.hooks.size
                    if (hookCount > 0) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_hooks, hookCount),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    // MCP 数量
                    val mcpCount = plugin.content.mcp.size
                    if (mcpCount > 0) {
                        McpPill(
                            text = stringResource(R.string.plugins_tag_mcp, mcpCount),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 状态 pill 与右箭头
            McpPill(
                text = statusText,
                textColor = statusColor,
                backgroundColor = statusBgColor
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
    onUninstall: () -> Unit,
    bridgeFactory: ((page: PluginSettingsPage, post: (String) -> Unit, onClosePage: () -> Unit) -> PluginWebBridge)? = null,
    onClosePage: () -> Unit = {},
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {}
) {
    val languageTag = currentLanguageTag()
    val configPage = plugin.content.configPage
    val hasConfigPage = configPage != null && bridgeFactory != null
    var selectedTab by remember(plugin.id) { mutableIntStateOf(0) }

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val fontScale = LocalConfiguration.current.fontScale
    val themeDark = PluginThemeTokens.isDark(colorScheme)
    val themeVariables = remember(colorScheme, semantic, fontScale) {
        PluginThemeTokens.variables(colorScheme, semantic, fontScale)
    }
    val pageBackground = semantic.pageBackground.toArgb()

    // 详情页打开后预热专属配置页：切到「插件配置」时直接复用已加载页面，避免首次进入现建 WebView 的白屏。
    // WebView 尚未挂到窗口，先按屏幕尺寸布局再加载，否则页面会按 0×0 视口完成首次布局。
    LaunchedEffect(plugin.id, hasConfigPage) {
        if (!hasConfigPage) return@LaunchedEffect
        val url = PluginWebViewFactory.pageUrl(configPage)
        delay(200)
        if (PluginWebViewCache.isLoaded(configPage.key, url)) return@LaunchedEffect
        runCatching {
            val webView = PluginWebViewCache.take(configPage.key) ?: PluginWebViewFactory.create(
                context = context,
                page = configPage,
                dark = themeDark,
                backgroundColor = pageBackground,
                themeVariables = { themeVariables },
                onPageFinished = { view ->
                    PluginWebViewCache.markLoaded(configPage.key, url)
                    view.evaluateJavascript(PluginThemeTokens.applyScript(themeVariables), null)
                },
                onMainFrameError = { PluginWebViewCache.clearLoading(configPage.key) }
            )
            if (PluginWebViewCache.takeBridge(configPage.key) == null) {
                val bridge = bridgeFactory(
                    configPage,
                    { script -> runCatching { webView.evaluateJavascript(script, null) } },
                    onClosePage
                )
                webView.addJavascriptInterface(bridge, "__aicodeBridge")
                PluginWebViewCache.putBridge(configPage.key, bridge)
            }
            PluginWebViewCache.put(configPage.key, webView)
            PluginWebViewCache.markLoading(configPage.key, url)
            val metrics = context.resources.displayMetrics
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            webView.loadUrl(url)
        }.onFailure { error ->
            FileLogger.w("PluginsSection", "插件配置页预热失败", error)
        }
    }

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
                hasConfigPage = hasConfigPage,
                onNavigateToConfigTab = { selectedTab = 1 },
                onToggleEnabled = onToggleEnabled,
                onUninstall = onUninstall,
                dependencyState = dependencyState,
                onPrepareDependencies = onPrepareDependencies
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
    hasConfigPage: Boolean,
    onNavigateToConfigTab: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    dependencyState: PluginDependencyUiState? = null,
    onPrepareDependencies: () -> Unit = {}
) {
    val manifest = plugin.manifest
    var confirmUninstall by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Spacer(Modifier.height(Spacing.xs))

        // 启停开关
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.plugins_enable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = enableHint(plugin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppSwitch(
                    checked = plugin.record.enabled && plugin.status == PluginStatus.OK,
                    onCheckedChange = onToggleEnabled,
                    enabled = plugin.status == PluginStatus.OK
                )
            }
        }

        // 插件专属配置页面直达入口卡片
        if (hasConfigPage) {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToConfigTab() }
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.plugins_config_card_title),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.plugins_config_card_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = FeatherIcons.ExternalLink,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (manifest != null) {
            SettingsGroupHeader(text = stringResource(R.string.plugins_section_summary))
            SettingsGroup {
                Text(
                    text = manifest.description.resolve(languageTag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
            SettingsGroup {
                InfoRow(stringResource(R.string.plugins_info_version), manifest.version.toString())
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_publisher), manifest.publisher)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_license), manifest.license)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_repository), manifest.repository)
                SettingsDivider()
                InfoRow(stringResource(R.string.plugins_info_origin), stringResource(R.string.plugins_origin_local))
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_section_contents))
            SettingsGroup {
                val skills = plugin.content.skills
                val agents = plugin.content.agents
                val workspaceTools = plugin.content.workspaceTools
                val settingsPages = plugin.content.settingsPages
                val aiTools = plugin.content.tools
                val hooks = plugin.content.hooks
                val mcpServers = plugin.content.mcp
                if (skills.isEmpty() && agents.isEmpty() && workspaceTools.isEmpty() && settingsPages.isEmpty() &&
                    aiTools.isEmpty() && hooks.isEmpty() && mcpServers.isEmpty()
                ) {
                    Text(
                        text = stringResource(R.string.plugins_no_contents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp)
                    )
                }
                val rows = skills.map {
                    stringResource(R.string.plugins_content_skill, it.skill.name) to it.skill.description
                } + agents.map {
                    stringResource(R.string.plugins_content_agent, it.definition.name) to it.definition.description
                } + workspaceTools.map {
                    stringResource(R.string.plugins_content_tool, it.title.resolve(languageTag)) to
                        it.description.resolve(languageTag)
                } + settingsPages.map {
                    stringResource(R.string.plugins_content_settings_page, it.title.resolve(languageTag)) to it.anchor
                } + aiTools.map {
                    stringResource(R.string.plugins_content_ai_tool, it.title.resolve(languageTag)) to
                        it.description.resolve(languageTag)
                } + hooks.map {
                    val title = when (it.mode) {
                        PluginHookModes.TRANSFORM -> stringResource(R.string.plugins_content_transform_hook, it.event)
                        PluginHookModes.REVIEW -> stringResource(R.string.plugins_content_review_hook, it.event)
                        else -> stringResource(R.string.plugins_content_hook, it.event)
                    }
                    title to it.handler
                } + mcpServers.map {
                    stringResource(R.string.plugins_content_mcp, it.originalName) to mcpDescription(it)
                }
                rows.forEachIndexed { index, (title, description) ->
                    if (index > 0) SettingsDivider()
                    ContentRow(title = title, description = description, active = plugin.isActive)
                }
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

        SettingsGroup {
            Text(
                text = stringResource(R.string.plugins_uninstall),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { confirmUninstall = true }
                    .padding(horizontal = Spacing.lg, vertical = 14.dp)
            )
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title)) },
            text = {
                Text(stringResource(R.string.plugins_uninstall_confirm_message, plugin.displayName(languageTag)))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    onUninstall()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun enableHint(plugin: InstalledPlugin): String = when {
    plugin.status == PluginStatus.INVALID -> stringResource(R.string.plugins_hint_incompatible)
    plugin.status != PluginStatus.OK -> stringResource(R.string.plugins_hint_damaged)
    else -> stringResource(R.string.plugins_enable_hint)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun ContentRow(title: String, description: String, active: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active) 1f else 0.5f)
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
