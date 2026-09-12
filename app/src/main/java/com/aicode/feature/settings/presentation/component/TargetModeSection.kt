package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.repository.TargetModeSettingsRepository

/**
 * 目标驱动模式设置：调整 TARGET 模式的最大步数上限与连续失败阈值。
 * 阈值经 TargetModeSettingsRepository 持久化，StatefulAgentWorkflow 工具循环读取用于终止判定。
 */
@Composable
internal fun TargetModeSection(
    thresholds: TargetModeSettingsRepository.Thresholds,
    onSetMaxStepBudget: (Int) -> Unit,
    onSetMaxConsecutiveFailures: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.mode_target_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsGroup {
            IntEditRow(
                title = stringResource(R.string.settings_target_max_step_budget),
                value = thresholds.maxStepBudget,
                onCommit = onSetMaxStepBudget
            )
            SettingsDivider()
            IntEditRow(
                title = stringResource(R.string.settings_target_max_consecutive_failures),
                value = thresholds.maxConsecutiveFailures,
                onCommit = onSetMaxConsecutiveFailures
            )
        }
    }
}

@Composable
private fun IntEditRow(
    title: String,
    value: Int,
    onCommit: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.sm))
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter { c -> c.isDigit() }.take(4) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(96.dp),
            textStyle = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = { text.toIntOrNull()?.let { onCommit(it.coerceAtLeast(1)) } }) {
            Text(stringResource(R.string.common_save))
        }
    }
}
