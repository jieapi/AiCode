package com.aicode.feature.plugin.presentation

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.settings.presentation.component.LazyGroupItem
import com.aicode.feature.settings.presentation.component.McpPill
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Package
import compose.icons.feathericons.Power
import compose.icons.feathericons.Tag

/** 开发者侧载目录里的插件项目：列表版式与已安装一致，点击进入独立详情页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevPluginsPage(
    projects: List<PluginDevProject>,
    plugins: List<InstalledPlugin>,
    devRootPath: String,
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenDetail: (PluginDevProject) -> Unit,
    onSetEnabled: (PluginDevProject, Boolean) -> Unit
) {
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
            if (projects.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    SettingsEmptyState(
                        icon = FeatherIcons.Code,
                        title = stringResource(R.string.plugins_dev_empty_title),
                        hint = stringResource(R.string.plugins_dev_empty_hint, devRootPath),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 48.dp)
                    )
                }
            } else {
                itemsIndexed(
                    items = projects,
                    key = { _, project -> project.dirName },
                    contentType = { _, _ -> "dev-row" }
                ) { index, project ->
                    val installed = plugins.firstOrNull { it.id == project.pluginId }
                    LazyGroupItem(index = index, lastIndex = projects.lastIndex) {
                        DevPluginRow(
                            project = project,
                            installed = installed,
                            onClick = { onOpenDetail(project) },
                            onSetEnabled = { onSetEnabled(project, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DevPluginRow(
    project: PluginDevProject,
    installed: InstalledPlugin?,
    onClick: () -> Unit,
    onSetEnabled: (Boolean) -> Unit
) {
    val languageTag = currentLanguageTag()
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
                icon = installed?.content?.icon,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = 22.dp,
                shape = RoundedCornerShape(8.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.displayName(languageTag),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (project.version.isNotBlank()) {
                    McpPill(
                        text = "v${project.version}",
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                }
                McpPill(
                    text = projectStatusText(project, installed),
                    textColor = if (project.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        AppSwitch(
            checked = installed?.record?.enabled == true,
            onCheckedChange = onSetEnabled,
            enabled = project.error == null && (installed == null || installed.status == PluginStatus.OK)
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

@Composable
private fun projectStatusText(project: PluginDevProject, installed: InstalledPlugin?): String = when {
    project.error != null -> stringResource(R.string.plugins_dev_invalid)
    installed != null -> stringResource(R.string.plugins_dev_installed, installed.record.version)
    else -> stringResource(R.string.plugins_dev_not_installed)
}

@Composable
internal fun PluginDevDetailSection(
    project: PluginDevProject,
    plugins: List<InstalledPlugin>,
    devRootPath: String,
    onInstall: () -> Unit
) {
    val languageTag = currentLanguageTag()
    val installed = plugins.firstOrNull { it.id == project.pluginId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.md, bottom = Spacing.xl)
    ) {
        DevHeaderCard(project = project, installed = installed, languageTag = languageTag)

        SettingsGroupHeader(text = stringResource(R.string.plugins_section_info))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Folder,
                title = stringResource(R.string.plugins_dev_detail_dir),
                trailing = { DevInfoValue(project.dirName) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Code,
                title = stringResource(R.string.plugins_dev_detail_id),
                trailing = { DevInfoValue(project.pluginId.ifBlank { "—" }) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.plugins_dev_detail_version),
                trailing = { DevInfoValue(project.version.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "—") }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Package,
                title = stringResource(R.string.plugins_dev_detail_installed),
                trailing = {
                    DevInfoValue(
                        installed?.record?.version?.let { "v$it" }
                            ?: stringResource(R.string.plugins_dev_not_installed)
                    )
                }
            )
            if (installed != null) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Power,
                    title = stringResource(R.string.plugins_dev_detail_status),
                    trailing = {
                        DevInfoValue(
                            stringResource(
                                if (installed.record.enabled) R.string.plugins_status_active
                                else R.string.plugins_status_disabled
                            )
                        )
                    }
                )
            }
        }

        if (project.error != null) {
            Text(
                text = stringResource(R.string.plugins_dev_invalid),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.md, start = Spacing.xs, end = Spacing.xs)
            )
        }

        Button(
            onClick = onInstall,
            enabled = project.error == null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg)
        ) {
            Text(
                stringResource(
                    if (installed != null) R.string.plugins_dev_reload
                    else R.string.plugins_dev_install
                )
            )
        }
        Text(
            text = stringResource(R.string.plugins_dev_detail_hint, devRootPath),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)
        )
    }
}

@Composable
private fun DevHeaderCard(
    project: PluginDevProject,
    installed: InstalledPlugin?,
    languageTag: String
) {
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
                PluginIconContent(
                    icon = installed?.content?.icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 26.dp,
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.displayName(languageTag),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                project.descriptionText(languageTag)?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                project.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
                    Text(
                        text = publisher,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DevInfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 190.dp)
    )
}

internal fun PluginDevProject.displayName(languageTag: String): String =
    name?.resolve(languageTag) ?: dirName

internal fun PluginDevProject.descriptionText(languageTag: String): String? =
    description?.resolve(languageTag)
