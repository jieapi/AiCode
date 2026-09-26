package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.data.repository.StartupSessionMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 偏好设置：集中放置不属于单个提供商配置、也不针对某个专用模型的全局偏好。
 */
@Composable
internal fun GeneralSettingsSection(
    autoRemoveStaleModels: Boolean,
    onToggleAutoRemoveStaleModels: (Boolean) -> Unit,
    startupSessionMode: StartupSessionMode,
    onSelectStartupSessionMode: (StartupSessionMode) -> Unit,
    firstByteTimeoutSec: Int,
    onSetFirstByteTimeoutSec: (Int) -> Unit,
    streamIdleTimeoutSec: Int,
    onSetStreamIdleTimeoutSec: (Int) -> Unit,
    maxNetworkRetries: Int,
    onSetMaxNetworkRetries: (Int) -> Unit,
    enterToSend: Boolean,
    onToggleEnterToSend: (Boolean) -> Unit,
    compactionThresholdPercent: Int,
    onSetCompactionThresholdPercent: (Int) -> Unit,
    softCompactionThresholdPercent: Int,
    onSetSoftCompactionThresholdPercent: (Int) -> Unit,
    sendFileMaxSizeMb: Int,
    onSetSendFileMaxSizeMb: (Int) -> Unit,
    deleteExternalWorkspaceSessions: Boolean,
    onToggleDeleteExternalWorkspaceSessions: (Boolean) -> Unit
) {
    var showStartupSessionSheet by remember { mutableStateOf(false) }
    var editingFirstByteTimeout by remember { mutableStateOf(false) }
    var editingStreamIdleTimeout by remember { mutableStateOf(false) }
    var editingMaxNetworkRetries by remember { mutableStateOf(false) }
    var editingCompactionThreshold by remember { mutableStateOf(false) }
    var editingSoftCompactionThreshold by remember { mutableStateOf(false) }
    var editingSendFileMaxSize by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_general_models))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_auto_remove_stale_models),
                subtitle = stringResource(R.string.settings_auto_remove_stale_models_desc),
                trailing = {
                    AppSwitch(
                        checked = autoRemoveStaleModels,
                        onCheckedChange = onToggleAutoRemoveStaleModels
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_compaction_threshold),
                subtitle = stringResource(R.string.settings_compaction_threshold_desc),
                onClick = { editingCompactionThreshold = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_percent_value, compactionThresholdPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_soft_compaction_threshold),
                subtitle = stringResource(R.string.settings_soft_compaction_threshold_desc),
                onClick = { editingSoftCompactionThreshold = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_percent_value, softCompactionThresholdPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_general_session))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_startup_session),
                onClick = { showStartupSessionSheet = true },
                trailing = {
                    Text(
                        text = stringResource(startupSessionMode.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_delete_external_workspace_sessions),
                subtitle = stringResource(R.string.settings_delete_external_workspace_sessions_desc),
                trailing = {
                    AppSwitch(
                        checked = deleteExternalWorkspaceSessions,
                        onCheckedChange = onToggleDeleteExternalWorkspaceSessions
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_general_input))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_enter_to_send),
                subtitle = stringResource(R.string.settings_enter_to_send_desc),
                trailing = {
                    AppSwitch(
                        checked = enterToSend,
                        onCheckedChange = onToggleEnterToSend
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_general_network))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_first_byte_timeout),
                subtitle = stringResource(R.string.settings_first_byte_timeout_desc),
                onClick = { editingFirstByteTimeout = true },
                trailing = {
                    Text(
                        text = timeoutLabel(firstByteTimeoutSec),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_stream_idle_timeout),
                subtitle = stringResource(R.string.settings_stream_idle_timeout_desc),
                onClick = { editingStreamIdleTimeout = true },
                trailing = {
                    Text(
                        text = timeoutLabel(streamIdleTimeoutSec),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_max_network_retries),
                subtitle = stringResource(R.string.settings_max_network_retries_desc),
                onClick = { editingMaxNetworkRetries = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_retries_count, maxNetworkRetries),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_general_tools))
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_sendfile_max_size),
                subtitle = stringResource(R.string.settings_sendfile_max_size_desc),
                onClick = { editingSendFileMaxSize = true },
                trailing = {
                    Text(
                        text = stringResource(R.string.settings_mb_value, sendFileMaxSizeMb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }
    }

    if (showStartupSessionSheet) {
        StartupSessionSheet(
            selected = startupSessionMode,
            onSelect = {
                onSelectStartupSessionMode(it)
                showStartupSessionSheet = false
            },
            onDismiss = { showStartupSessionSheet = false }
        )
    }

    if (editingFirstByteTimeout) {
        NumberInputDialog(
            title = stringResource(R.string.settings_first_byte_timeout),
            initialValue = firstByteTimeoutSec,
            hint = stringResource(R.string.settings_timeout_input_hint),
            onConfirm = {
                onSetFirstByteTimeoutSec(it)
                editingFirstByteTimeout = false
            },
            onDismiss = { editingFirstByteTimeout = false }
        )
    }

    if (editingStreamIdleTimeout) {
        NumberInputDialog(
            title = stringResource(R.string.settings_stream_idle_timeout),
            initialValue = streamIdleTimeoutSec,
            hint = stringResource(R.string.settings_timeout_input_hint),
            onConfirm = {
                onSetStreamIdleTimeoutSec(it)
                editingStreamIdleTimeout = false
            },
            onDismiss = { editingStreamIdleTimeout = false }
        )
    }

    if (editingMaxNetworkRetries) {
        NumberInputDialog(
            title = stringResource(R.string.settings_max_network_retries),
            initialValue = maxNetworkRetries,
            hint = stringResource(R.string.settings_retries_input_hint),
            onConfirm = {
                onSetMaxNetworkRetries(it)
                editingMaxNetworkRetries = false
            },
            onDismiss = { editingMaxNetworkRetries = false }
        )
    }

    if (editingCompactionThreshold) {
        NumberInputDialog(
            title = stringResource(R.string.settings_compaction_threshold),
            initialValue = compactionThresholdPercent,
            hint = stringResource(R.string.settings_compaction_threshold_input_hint),
            minValue = 1,
            maxValue = 100,
            onConfirm = {
                onSetCompactionThresholdPercent(it)
                editingCompactionThreshold = false
            },
            onDismiss = { editingCompactionThreshold = false }
        )
    }

    if (editingSoftCompactionThreshold) {
        NumberInputDialog(
            title = stringResource(R.string.settings_soft_compaction_threshold),
            initialValue = softCompactionThresholdPercent,
            hint = stringResource(R.string.settings_compaction_threshold_input_hint),
            minValue = 1,
            maxValue = 100,
            onConfirm = {
                onSetSoftCompactionThresholdPercent(it)
                editingSoftCompactionThreshold = false
            },
            onDismiss = { editingSoftCompactionThreshold = false }
        )
    }

    if (editingSendFileMaxSize) {
        NumberInputDialog(
            title = stringResource(R.string.settings_sendfile_max_size),
            initialValue = sendFileMaxSizeMb,
            hint = stringResource(R.string.settings_sendfile_max_size_input_hint),
            minValue = 1,
            onConfirm = {
                onSetSendFileMaxSizeMb(it)
                editingSendFileMaxSize = false
            },
            onDismiss = { editingSendFileMaxSize = false }
        )
    }
}

/** 超时值的行尾展示：0（或负数）视为不限制。 */
@Composable
private fun timeoutLabel(sec: Int): String =
    if (sec <= 0) stringResource(R.string.settings_timeout_unlimited)
    else stringResource(R.string.settings_timeout_seconds, sec)

/**
 * 数值输入弹窗：只接受非负整数，留空或填 0 按 0 处理（由调用方决定 0 的语义）。
 */
@Composable
private fun NumberInputDialog(
    title: String,
    initialValue: Int,
    hint: String,
    minValue: Int = 0,
    maxValue: Int = Int.MAX_VALUE,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(if (initialValue > 0) initialValue.toString() else "") }
    val parsed = text.trim().toIntOrNull()
    // 留空按 [minValue] 的语义处理：允许 0 的场景（超时/重试）视为不限制，否则视为未填。
    val isValid = if (text.isBlank()) minValue <= 0 else parsed != null && parsed in minValue..maxValue

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            AppTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !isValid,
                supportingText = { Text(hint) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsed ?: 0) },
                enabled = isValid
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartupSessionSheet(
    selected: StartupSessionMode,
    onSelect: (StartupSessionMode) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.settings_startup_session),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            StartupSessionMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(mode.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(mode.descRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun StartupSessionMode.labelRes(): Int = when (this) {
    StartupSessionMode.NEW_SESSION -> R.string.settings_startup_session_new
    StartupSessionMode.RECENT_SESSION -> R.string.settings_startup_session_recent
}

private fun StartupSessionMode.descRes(): Int = when (this) {
    StartupSessionMode.NEW_SESSION -> R.string.settings_startup_session_new_desc
    StartupSessionMode.RECENT_SESSION -> R.string.settings_startup_session_recent_desc
}