package com.aicode.feature.plugin.presentation

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.McpPill
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.openUrl
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CloudOff
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Package
import compose.icons.feathericons.Server
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Tag

/** 非官方徽标固定用黄色，与主题主色区分开。 */
private val UnofficialBadgeBackground = Color(0xFFFFE082)
private val UnofficialBadgeText = Color(0xFF5C4300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginStoreSection(
    onShowPreview: (PluginStorePreview) -> Unit,
    onOpenDetail: (PluginStoreItem) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.preview) {
        state.preview?.let { preview ->
            onShowPreview(preview)
            viewModel.consumePreview()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshResult.collect { failed ->
            val message = if (failed > 0) {
                context.getString(R.string.plugins_store_refresh_done_failed, failed)
            } else {
                context.getString(R.string.plugins_store_refresh_done)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val filteredItems = remember(state.items, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            state.items
        } else {
            state.items.filter { item ->
                item.plugin.name.lowercase().contains(query) ||
                    item.plugin.id.lowercase().contains(query) ||
                    item.plugin.author.lowercase().contains(query) ||
                    item.plugin.description.lowercase().contains(query)
            }
        }
    }
    // 安装/下载错误归属具体插件，只在该插件详情页展示；整页错误只处理索引错误。
    val indexError = state.error?.takeIf { it.pluginId == null }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                state.loading && state.items.isEmpty() -> item(key = "loading", contentType = "loading") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                indexError != null && state.items.isEmpty() -> item(key = "error", contentType = "empty") {
                    StoreErrorState(
                        error = indexError,
                        onRetry = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.ShoppingBag,
                        title = stringResource(R.string.plugins_store_empty_title),
                        hint = stringResource(R.string.plugins_store_empty_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
                else -> {
                    if (state.items.size >= 3) {
                        item(key = "search", contentType = "search") {
                            ModelSearchField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                placeholder = stringResource(R.string.plugins_search_placeholder),
                                modifier = Modifier.padding(bottom = Spacing.xs)
                            )
                        }
                    }
                    if (filteredItems.isEmpty()) {
                        item(key = "search-empty", contentType = "empty") {
                            PluginSearchEmptyState()
                        }
                    } else {
                        itemsIndexed(
                            items = filteredItems,
                            key = { _, item -> "${item.source.id}:${item.plugin.id}" },
                            contentType = { _, _ -> "store-row" }
                        ) { index, item ->
                            LazyGroupItem(index = index, lastIndex = filteredItems.lastIndex) {
                                StorePluginRow(
                                    item = item,
                                    onClick = { onOpenDetail(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun pluginVersionText(version: String?): String =
    version?.let { "v$it" } ?: stringResource(R.string.plugins_store_version_latest)

/** 商店列表与已安装列表同版式：图标 + 名称 + 描述；商店不需要开关，点击进入独立详情页。 */
@Composable
private fun StorePluginRow(item: PluginStoreItem, onClick: () -> Unit) {
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
            Icon(
                imageVector = FeatherIcons.Package,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.plugin.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!item.official) {
                    Spacer(modifier = Modifier.width(6.dp))
                    McpPill(
                        text = stringResource(R.string.plugins_store_badge_unofficial),
                        textColor = UnofficialBadgeText,
                        backgroundColor = UnofficialBadgeBackground
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.plugin.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun PluginStoreDetailSection(
    item: PluginStoreItem,
    onShowPreview: (PluginStorePreview) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latest = state.items.firstOrNull {
        it.source.id == item.source.id && it.plugin.id == item.plugin.id
    } ?: item
    val context = LocalContext.current
    val installing = state.installingPluginId == latest.plugin.id

    LaunchedEffect(state.preview) {
        // 预览是全局单槽状态；只弹出当前插件的预览，避免 A 的安装结果覆盖到 B 的详情页。
        val preview = state.preview?.takeIf { it.preview.manifest.id == latest.plugin.id }
            ?: return@LaunchedEffect
        onShowPreview(preview)
        viewModel.consumePreview()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.md, bottom = Spacing.xl)
    ) {
        StoreHeaderCard(item = latest)

        SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.plugins_store_detail_version),
                trailing = { StoreInfoValue(pluginVersionText(latest.plugin.version)) }
            )
            if (latest.plugin.tags.isNotEmpty()) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = stringResource(R.string.plugins_store_detail_tags),
                    trailing = { StoreInfoValue(latest.plugin.tags.joinToString(" · ")) }
                )
            }
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(R.string.plugins_store_detail_index_source),
                trailing = { StoreInfoValue(latest.source.name) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Github,
                title = stringResource(R.string.plugins_store_detail_source),
                onClick = { openUrl(context, latest.plugin.repository) }
            )
            latest.plugin.license?.let { license ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Book,
                    title = stringResource(R.string.plugins_store_detail_license),
                    trailing = { StoreInfoValue(license) }
                )
            }
            latest.plugin.homepage?.let { homepage ->
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.plugins_store_detail_homepage),
                    onClick = { openUrl(context, homepage) }
                )
            }
        }

        Button(
            onClick = { viewModel.install(latest) },
            enabled = latest.compatible && !installing &&
                (latest.installedVersion == null || latest.updateAvailable),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg)
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                when {
                    installing -> stringResource(R.string.plugins_installing)
                    !latest.compatible -> latest.plugin.minAppVersion?.let { minimum ->
                        stringResource(R.string.plugins_store_requires_min_version, minimum)
                    } ?: stringResource(R.string.plugins_store_incompatible)
                    latest.installedVersion != null && !latest.updateAvailable ->
                        stringResource(R.string.plugins_store_installed)
                    latest.updateAvailable -> stringResource(R.string.plugins_store_update)
                    else -> stringResource(R.string.plugins_store_install)
                }
            )
        }
        val installError = state.error?.takeIf {
            it.pluginId == latest.plugin.id &&
                (it.code == PluginStoreErrorCode.DOWNLOAD_FAILED ||
                    it.code == PluginStoreErrorCode.NO_COMPATIBLE_RELEASE ||
                    it.code == PluginStoreErrorCode.PREVIEW_FAILED)
        }
        if (installError != null) {
            Text(
                text = storeErrorMessage(installError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
            )
        }
    }
}

@Composable
private fun StoreHeaderCard(item: PluginStoreItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
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
                Icon(
                    imageVector = FeatherIcons.Package,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.plugin.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!item.official) {
                        Spacer(modifier = Modifier.width(6.dp))
                        McpPill(
                            text = stringResource(R.string.plugins_store_badge_unofficial),
                            textColor = UnofficialBadgeText,
                            backgroundColor = UnofficialBadgeBackground
                        )
                    }
                }
                Text(
                    text = item.plugin.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.plugin.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = item.plugin.description,
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
private fun StoreInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

/** 索引读取失败时的整页占位，与商店空态用同一套版式。 */
@Composable
private fun StoreErrorState(
    error: PluginStoreError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsEmptyState(
        icon = FeatherIcons.CloudOff,
        title = storeErrorMessage(error),
        hint = error.detail?.takeIf { it.isNotBlank() },
        actions = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.plugins_store_retry))
            }
        },
        modifier = modifier
    )
}

@Composable
private fun storeErrorMessage(error: PluginStoreError): String = when (error.code) {
    PluginStoreErrorCode.NETWORK -> stringResource(R.string.plugins_store_error_network)
    PluginStoreErrorCode.INVALID_INDEX -> stringResource(R.string.plugins_store_error_invalid)
    PluginStoreErrorCode.NO_COMPATIBLE_RELEASE -> stringResource(R.string.plugins_store_error_no_release)
    PluginStoreErrorCode.DOWNLOAD_FAILED -> stringResource(R.string.plugins_store_error_download)
    PluginStoreErrorCode.PREVIEW_FAILED -> error.pluginError?.let { pluginErrorMessage(it) }
        ?: stringResource(R.string.plugins_store_error_preview)
}
