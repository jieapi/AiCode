package com.aicode.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginPermission
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader

/** 单次最多逐条列出的导入问题数，其余折叠成「还有 N 个问题」。 */
private const val MAX_LISTED_ERRORS = 5

/** 导入流程的全部弹层：读取中、失败列表、预览与授权确认。放在设置页底部与其他对话框并列。 */
@Composable
internal fun PluginImportHost(viewModel: PluginsViewModel) {
    val state by viewModel.importState.collectAsStateWithLifecycle()
    when (val current = state) {
        PluginImportState.Idle -> Unit
        PluginImportState.Reading -> ProgressDialog(R.string.plugins_reading)
        is PluginImportState.Failed -> ImportFailedDialog(current.errors, onDismiss = viewModel::dismissImport)
        is PluginImportState.Preview -> PluginPreviewSheet(
            state = current,
            onToggleGrant = viewModel::toggleGrant,
            onInstall = viewModel::confirmInstall,
            onDismiss = viewModel::dismissImport
        )
    }
}

@Composable
private fun ProgressDialog(@StringRes titleRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(titleRes)) },
        text = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
        confirmButton = {}
    )
}

@Composable
private fun ImportFailedDialog(errors: List<PluginError>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_import_failed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                errors.take(MAX_LISTED_ERRORS).forEach { error ->
                    Text(text = pluginErrorMessage(error), style = MaterialTheme.typography.bodyMedium)
                }
                val hidden = errors.size - MAX_LISTED_ERRORS
                if (hidden > 0) {
                    Text(
                        text = stringResource(R.string.plugins_import_failed_more, hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginPreviewSheet(
    state: PluginImportState.Preview,
    onToggleGrant: (PluginPermission) -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val preview = state.preview
    val manifest = preview.manifest
    val languageTag = currentLanguageTag()

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
                text = stringResource(R.string.plugins_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = manifest.name.resolve(languageTag),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.plugins_item_subtitle, manifest.version.toString(), manifest.publisher),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = manifest.description.resolve(languageTag),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Notice(stringResource(R.string.plugins_preview_local_notice))
            Notice(stringResource(R.string.plugins_preview_behavior_notice))
            preview.installedVersion?.let { installed ->
                Notice(
                    stringResource(
                        if (preview.identical) R.string.plugins_preview_identical_notice
                        else R.string.plugins_preview_replace_notice,
                        installed
                    )
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_preview_contents))
            SettingsGroup {
                contentLines(preview).forEachIndexed { index, line ->
                    if (index > 0) SettingsDivider()
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp)) {
                        Text(text = line.first, style = MaterialTheme.typography.bodyLarge)
                        if (line.second.isNotBlank()) {
                            Text(
                                text = line.second,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.plugins_preview_permissions))
            SettingsGroup {
                manifest.permissions.forEachIndexed { index, request ->
                    if (index > 0) SettingsDivider()
                    val required = request.required
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val tag = stringResource(
                                if (required) R.string.plugins_permission_required else R.string.plugins_permission_optional
                            )
                            Text(text = "${request.permission.label()} · $tag", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = request.reason.resolve(languageTag),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(Spacing.md))
                        AppSwitch(
                            checked = request.permission in state.granted,
                            onCheckedChange = { onToggleGrant(request.permission) },
                            enabled = !required && !state.installing
                        )
                    }
                }
            }
            if (manifest.permissions.any { !it.required }) {
                Text(
                    text = stringResource(R.string.plugins_preview_optional_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.error?.let { error ->
                Text(
                    text = pluginErrorMessage(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !state.installing) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(onClick = onInstall, enabled = !state.installing) {
                    if (state.installing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(
                                if (preview.installedVersion != null) R.string.plugins_install_replace
                                else R.string.plugins_install
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.subtleText
    )
}

/** 预览里「将提供的内容」逐行：(标题, 描述)。 */
@Composable
private fun contentLines(preview: PluginPreview): List<Pair<String, String>> =
    preview.content.skills.map {
        stringResource(R.string.plugins_content_skill, it.skill.name) to it.skill.description
    } + preview.content.agents.map {
        stringResource(R.string.plugins_content_agent, it.definition.name) to it.definition.description
    }

/** 把领域层的错误码翻译成界面文案；[PluginError.detail] 是语言无关的定位信息，原样带入。 */
@Composable
internal fun pluginErrorMessage(error: PluginError): String {
    val detail = error.detail
    return when (error.code) {
        PluginErrorCode.ARCHIVE_UNREADABLE -> stringResource(R.string.plugins_error_archive_unreadable)
        PluginErrorCode.ARCHIVE_TOO_LARGE -> stringResource(R.string.plugins_error_archive_too_large)
        PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES -> stringResource(R.string.plugins_error_archive_too_many)
        PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE -> stringResource(R.string.plugins_error_entry_too_large, detail)
        PluginErrorCode.ARCHIVE_UNSAFE_PATH -> stringResource(R.string.plugins_error_unsafe_path, detail)
        PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY -> stringResource(R.string.plugins_error_duplicate_entry, detail)
        PluginErrorCode.MANIFEST_MISSING -> stringResource(R.string.plugins_error_manifest_missing)
        PluginErrorCode.MANIFEST_TOO_LARGE -> stringResource(R.string.plugins_error_manifest_too_large)
        PluginErrorCode.MANIFEST_NOT_JSON -> stringResource(R.string.plugins_error_manifest_not_json)
        PluginErrorCode.FIELD_MISSING -> stringResource(R.string.plugins_error_field_missing, detail)
        PluginErrorCode.FIELD_INVALID -> stringResource(R.string.plugins_error_field_invalid, detail)
        PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION -> stringResource(R.string.plugins_error_manifest_version, detail)
        PluginErrorCode.UNSUPPORTED_FIELD -> stringResource(R.string.plugins_error_unsupported_field, detail)
        PluginErrorCode.UNSUPPORTED_CONTRIBUTION -> stringResource(R.string.plugins_error_unsupported_contribution, detail)
        PluginErrorCode.UNSUPPORTED_PERMISSION -> stringResource(R.string.plugins_error_unsupported_permission, detail)
        PluginErrorCode.API_INCOMPATIBLE -> stringResource(R.string.plugins_error_api_incompatible, detail)
        PluginErrorCode.DUPLICATE_ID -> stringResource(R.string.plugins_error_duplicate_id, detail)
        PluginErrorCode.PERMISSION_SCOPE_MISMATCH -> stringResource(R.string.plugins_error_scope_mismatch, detail)
        PluginErrorCode.FILE_MISSING -> stringResource(R.string.plugins_error_file_missing, detail)
        PluginErrorCode.CONTENT_INVALID -> stringResource(R.string.plugins_error_content_invalid, detail)
        PluginErrorCode.STORAGE_FAILED -> stringResource(R.string.plugins_error_storage_failed)
        PluginErrorCode.PREVIEW_EXPIRED -> stringResource(R.string.plugins_error_preview_expired)
        PluginErrorCode.PERMISSION_NOT_GRANTED -> stringResource(R.string.plugins_error_permission_not_granted, detail)
    }
}
