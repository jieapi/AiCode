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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import java.net.URI

/** 单次最多逐条列出的导入问题数，其余折叠成「还有 N 个问题」。 */
private const val MAX_LISTED_ERRORS = 5

/** 只做明确的等价归一：去空白、末尾斜杠与 .git 后缀，scheme/host 小写。 */
private fun normalizeRepositoryInput(raw: String): String {
    val text = raw.trim().trimEnd('/').removeSuffix(".git")
    return runCatching {
        val uri = URI(text)
        "${uri.scheme.lowercase()}://${uri.host.lowercase()}${uri.path.orEmpty().trimEnd('/')}"
    }.getOrDefault(text)
}

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
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val preview = state.preview
    val manifest = preview.manifest
    val languageTag = currentLanguageTag()
    val expectedRepository = state.expectedRepository
    val requiresManualRepository = expectedRepository != null && !state.officialSource
    var repositoryInput by rememberSaveable(state.preview.token) { mutableStateOf("") }
    val repositoryMatches = !requiresManualRepository ||
        normalizeRepositoryInput(repositoryInput) == normalizeRepositoryInput(expectedRepository.orEmpty())

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
            state.sourceLabel?.let { source ->
                Text(
                    text = if (state.officialSource) {
                        stringResource(R.string.plugins_install_source_official, source)
                    } else {
                        stringResource(R.string.plugins_install_source_community, source)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (installSourceChanged(preview, state.origin)) {
                Text(
                    text = stringResource(R.string.plugins_install_source_changed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = manifest.description.resolve(languageTag),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            SettingsGroupHeader(text = stringResource(R.string.plugins_preview_contents))
            SettingsGroup {
                PluginContentRows(
                    content = preview.content,
                    languageTag = languageTag,
                    active = true
                )
            }

            if (requiresManualRepository) {
                SettingsGroupHeader(text = stringResource(R.string.plugins_install_repository_header))
                Text(
                    text = stringResource(R.string.plugins_install_repository_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = expectedRepository.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AppTextField(
                    value = repositoryInput,
                    onValueChange = { repositoryInput = it },
                    label = stringResource(R.string.plugins_install_repository_label),
                    placeholder = "https://github.com/owner/repo",
                    colors = dialogTextFieldColors(),
                    isError = repositoryInput.isNotBlank() && !repositoryMatches,
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (repositoryInput.isBlank() || repositoryMatches) {
                                    R.string.plugins_install_repository_hint
                                } else {
                                    R.string.plugins_install_repository_mismatch
                                }
                            )
                        )
                    }
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
                Button(onClick = onInstall, enabled = !state.installing && repositoryMatches) {
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
        PluginErrorCode.API_INCOMPATIBLE -> stringResource(R.string.plugins_error_api_incompatible, detail)
        PluginErrorCode.DUPLICATE_ID -> stringResource(R.string.plugins_error_duplicate_id, detail)
        PluginErrorCode.FILE_MISSING -> stringResource(R.string.plugins_error_file_missing, detail)
        PluginErrorCode.CONTENT_INVALID -> stringResource(R.string.plugins_error_content_invalid, detail)
        PluginErrorCode.STORAGE_FAILED -> stringResource(R.string.plugins_error_storage_failed)
        PluginErrorCode.PREVIEW_EXPIRED -> stringResource(R.string.plugins_error_preview_expired)
    }
}
