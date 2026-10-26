package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginPermission
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Package

/** 当前界面语言标签，用于挑选插件自带的多语言文本。 */
@Composable
internal fun currentLanguageTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** 展示用名称：清单可读时取本地化名称，否则（文件损坏等）退回插件 ID。 */
internal fun InstalledPlugin.displayName(languageTag: String): String =
    manifest?.name?.resolve(languageTag) ?: id

@Composable
internal fun PluginPermission.label(): String = stringResource(
    when (this) {
        PluginPermission.SKILLS_REGISTER -> R.string.plugins_permission_skills_register
        PluginPermission.AGENTS_REGISTER -> R.string.plugins_permission_agents_register
    }
)

/** 设置 → 插件：已安装插件列表。导入入口在页面顶栏的「＋」。 */
@Composable
internal fun PluginsSection(
    plugins: List<InstalledPlugin>,
    loaded: Boolean,
    onOpenDetail: (InstalledPlugin) -> Unit,
    onToggleEnabled: (InstalledPlugin, Boolean) -> Unit
) {
    // 读盘完成前列表为空不代表没有插件，不画空状态以免闪一下
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Spacer(Modifier.height(Spacing.sm))
        SettingsGroup {
            plugins.forEachIndexed { index, plugin ->
                if (index > 0) SettingsDivider()
                val publisher = plugin.manifest?.publisher ?: plugin.id
                val version = stringResource(R.string.plugins_item_subtitle, plugin.record.version, publisher)
                val status = pluginStatusText(plugin)
                SettingsRow(
                    icon = FeatherIcons.Package,
                    title = plugin.displayName(languageTag),
                    subtitle = if (status == null) version else "$version · $status",
                    onClick = { onOpenDetail(plugin) },
                    trailing = {
                        AppSwitch(
                            checked = plugin.record.enabled && plugin.status == PluginStatus.OK,
                            onCheckedChange = { onToggleEnabled(plugin, it) },
                            enabled = plugin.status == PluginStatus.OK
                        )
                    }
                )
            }
        }
    }
}

/** 列表行副标题里的状态；一切正常返回 null。 */
@Composable
private fun pluginStatusText(plugin: InstalledPlugin): String? = when {
    plugin.status == PluginStatus.INVALID -> stringResource(R.string.plugins_status_incompatible)
    plugin.status != PluginStatus.OK -> stringResource(R.string.plugins_status_damaged)
    !plugin.record.enabled -> stringResource(R.string.plugins_status_disabled)
    plugin.missingRequired.isNotEmpty() -> stringResource(R.string.plugins_status_needs_permission)
    else -> null
}

/** 插件详情：启停、简介、来源信息、提供的内容与逐项权限，以及卸载。 */
@Composable
internal fun PluginDetailSection(
    plugin: InstalledPlugin,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePermission: (PluginPermission, Boolean) -> Unit,
    onUninstall: () -> Unit
) {
    val languageTag = currentLanguageTag()
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
                if (skills.isEmpty() && agents.isEmpty()) {
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
                    Triple(
                        stringResource(R.string.plugins_content_skill, it.skill.name),
                        it.skill.description,
                        PluginPermission.SKILLS_REGISTER
                    )
                } + agents.map {
                    Triple(
                        stringResource(R.string.plugins_content_agent, it.definition.name),
                        it.definition.description,
                        PluginPermission.AGENTS_REGISTER
                    )
                }
                rows.forEachIndexed { index, (title, description, permission) ->
                    if (index > 0) SettingsDivider()
                    ContentRow(
                        title = title,
                        description = description,
                        active = plugin.isActive && permission in plugin.granted
                    )
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_section_permissions))
            SettingsGroup {
                manifest.permissions.forEachIndexed { index, request ->
                    if (index > 0) SettingsDivider()
                    PermissionRow(
                        label = request.permission.label(),
                        reason = request.reason.resolve(languageTag),
                        required = request.required,
                        granted = request.permission in plugin.granted,
                        onToggle = { onTogglePermission(request.permission, it) }
                    )
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
    plugin.missingRequired.isNotEmpty() -> stringResource(R.string.plugins_hint_needs_permission)
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

@Composable
private fun PermissionRow(
    label: String,
    reason: String,
    required: Boolean,
    granted: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$label · ${stringResource(if (required) R.string.plugins_permission_required else R.string.plugins_permission_optional)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        AppSwitch(checked = granted, onCheckedChange = onToggle)
    }
}
