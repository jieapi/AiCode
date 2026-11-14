package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Package
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Trash2
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PluginStoreSection(
    onShowPreview: (PluginStorePreview) -> Unit,
    viewModel: PluginStoreViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showAddSource by rememberSaveable { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<PluginStoreItem?>(null) }

    LaunchedEffect(state.preview) {
        state.preview?.let { preview ->
            onShowPreview(preview)
            viewModel.consumePreview()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StoreToolbar(
            state = state,
            onSelectSource = viewModel::selectSource,
            onRefresh = viewModel::refresh,
            onManageSources = { showSources = true }
        )
        when {
            state.loading && state.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            state.error != null && state.items.isEmpty() -> StoreErrorState(
                error = state.error!!,
                onRetry = viewModel::refresh
            )
            state.items.isEmpty() -> SettingsEmptyState(
                icon = FeatherIcons.ShoppingBag,
                title = stringResource(R.string.plugins_store_empty_title),
                hint = stringResource(R.string.plugins_store_empty_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = 48.dp)
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (state.stale) {
                    item(key = "stale", contentType = "notice") {
                        Text(
                            text = stringResource(
                                R.string.plugins_store_stale_notice,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(state.fetchedAt))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs)
                        )
                    }
                }
                state.error?.let { error ->
                    item(key = "error", contentType = "error") {
                        StoreErrorState(error = error, onRetry = viewModel::refresh, compact = true)
                    }
                }
                itemsIndexed(
                    items = state.items,
                    key = { _, item -> "${item.source.id}:${item.plugin.id}" },
                    contentType = { _, _ -> "store-row" }
                ) { index, item ->
                    LazyGroupItem(index = index, lastIndex = state.items.lastIndex) {
                        StorePluginRow(
                            item = item,
                            installing = state.installingPluginId == item.plugin.id,
                            onClick = { detailItem = item },
                            onInstall = { viewModel.install(item) }
                        )
                    }
                }
            }
        }
    }

    if (showSources) {
        SourceManagerSheet(
            sources = state.sources,
            onDismiss = { showSources = false },
            onAdd = { showSources = false; showAddSource = true },
            onToggle = viewModel::setSourceEnabled,
            onDelete = viewModel::removeSource
        )
    }
    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onAdd = { name, url, done -> viewModel.addSource(name, url, done) }
        )
    }
    detailItem?.let { item ->
        StoreDetailSheet(
            item = item,
            installing = state.installingPluginId == item.plugin.id,
            onDismiss = { detailItem = null },
            onInstall = {
                detailItem = null
                viewModel.install(item)
            }
        )
    }
}

@Composable
private fun StoreToolbar(
    state: PluginStoreUiState,
    onSelectSource: (String) -> Unit,
    onRefresh: () -> Unit,
    onManageSources: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            items(state.sources.filter { it.enabled }, key = { it.id }) { source ->
                FilterChip(
                    selected = state.selectedSourceId == source.id,
                    onClick = { onSelectSource(source.id) },
                    label = {
                        Text(
                            text = source.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        if (state.refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.plugins_store_refresh))
            }
        }
        IconButton(onClick = onManageSources) {
            Icon(FeatherIcons.Settings, contentDescription = stringResource(R.string.plugins_store_manage_sources))
        }
    }
}

@Composable
private fun pluginVersionText(version: String?): String =
    version?.let { "v$it" } ?: stringResource(R.string.plugins_store_version_latest)

@Composable
private fun StorePluginRow(
    item: PluginStoreItem,
    installing: Boolean,
    onClick: () -> Unit,
    onInstall: () -> Unit
) {
    val versionText = pluginVersionText(item.plugin.version)
    SettingsRow(
        icon = FeatherIcons.Package,
        title = item.plugin.name,
        subtitle = "${item.plugin.author} · $versionText",
        onClick = onClick,
        trailing = {
            if (installing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (!item.compatible) {
                Text(
                    text = stringResource(R.string.plugins_store_incompatible),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (item.installedVersion != null && !item.updateAvailable) {
                Text(
                    text = stringResource(R.string.plugins_store_installed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TextButton(onClick = onInstall) {
                    Text(
                        stringResource(
                            if (item.updateAvailable) R.string.plugins_store_update
                            else R.string.plugins_store_install
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun StoreErrorState(
    error: PluginStoreError,
    onRetry: () -> Unit,
    compact: Boolean = false
) {
    SettingsGroup(modifier = Modifier.padding(horizontal = if (compact) 0.dp else Spacing.lg)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = storeErrorMessage(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            error.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.plugins_store_retry))
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreDetailSheet(
    item: PluginStoreItem,
    installing: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = item.plugin.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.plugins_store_detail_publisher, item.plugin.author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = item.plugin.description,
                style = MaterialTheme.typography.bodyMedium
            )
            SettingsGroupHeader(text = stringResource(R.string.plugins_store_detail_version))
            Text(
                text = pluginVersionText(item.plugin.version),
                style = MaterialTheme.typography.bodyMedium
            )
            if (item.plugin.tags.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.plugins_store_detail_tags))
                Text(
                    text = item.plugin.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            SettingsGroupHeader(text = stringResource(R.string.plugins_store_detail_source))
            Text(
                text = item.plugin.repository,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.plugin.license?.let { license ->
                SettingsGroupHeader(text = stringResource(R.string.plugins_store_detail_license))
                Text(text = license, style = MaterialTheme.typography.bodyMedium)
            }
            item.plugin.homepage?.let { homepage ->
                SettingsGroupHeader(text = stringResource(R.string.plugins_store_detail_homepage))
                Text(
                    text = homepage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!item.compatible) {
                Text(
                    text = stringResource(R.string.plugins_store_incompatible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !installing) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = onInstall,
                    enabled = !installing && item.compatible &&
                        (item.installedVersion == null || item.updateAvailable)
                ) {
                    if (installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(
                                when {
                                    item.updateAvailable -> R.string.plugins_store_update
                                    item.installedVersion != null -> R.string.plugins_store_installed
                                    else -> R.string.plugins_store_install
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceManagerSheet(
    sources: List<PluginIndexSource>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.plugins_store_sources_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            SettingsGroup {
                sources.forEachIndexed { index, source ->
                    val officialLabel = stringResource(R.string.plugins_store_source_official)
                    SettingsRow(
                        title = source.name,
                        subtitle = source.indexUrl + if (source.official) " · $officialLabel" else "",
                        trailing = {
                            if (source.official) {
                                Icon(
                                    FeatherIcons.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppSwitch(
                                        checked = source.enabled,
                                        onCheckedChange = { onToggle(source.id, it) }
                                    )
                                    IconButton(onClick = { onDelete(source.id) }) {
                                        Icon(FeatherIcons.Trash2, contentDescription = stringResource(R.string.plugins_store_source_delete))
                                    }
                                }
                            }
                        }
                    )
                    if (index < sources.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.plugins_store_source_add))
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, (Boolean) -> Unit) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    val valid = name.isNotBlank() && url.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_store_source_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it; error = false },
                    label = stringResource(R.string.plugins_store_source_name),
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = url,
                    onValueChange = { url = it; error = false },
                    label = stringResource(R.string.plugins_store_source_url),
                    placeholder = "https://example.com/index.json",
                    colors = dialogTextFieldColors(),
                    isError = error || (url.isNotBlank() && !url.trim().startsWith("https://")),
                    supportingText = {
                        Text(
                            stringResource(
                                if (error) R.string.plugins_store_source_invalid
                                else R.string.plugins_store_source_url_hint
                            )
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onAdd(name.trim(), url.trim()) { ok ->
                        if (ok) onDismiss() else error = true
                    }
                }
            ) { Text(stringResource(R.string.plugins_store_source_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
