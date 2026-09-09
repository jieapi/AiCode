package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.subagent.SubagentPreset
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Zap
import java.util.UUID

/**
 * 子代理预设区块（设置在「子代理」页内）：iOS 分组列表——每个预设一行（名称 + 模型 + 提醒标记），
 * 点击行弹编辑面板（改名称/选模型/配模式提醒），行尾上/下移与删除按钮，列表底部「+ 添加子代理」。
 */
@Composable
internal fun SubagentPresetsSection(
    presets: List<SubagentPreset>,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSave: (List<SubagentPreset>) -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SettingsGroupHeader(stringResource(R.string.settings_subagent_model))

        if (presets.isEmpty()) {
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Layers,
                    title = stringResource(R.string.subagent_presets_empty),
                    subtitle = stringResource(R.string.subagent_presets_empty_hint)
                )
            }
        } else {
            SettingsGroup {
                presets.forEachIndexed { index, preset ->
                    if (index > 0) SettingsDivider()
                    SubagentPresetRow(
                        preset = preset,
                        onClick = { editingIndex = index },
                        onMoveUp = if (index > 0) ({ onSave(presets.toMutableList().apply { add(index - 1, removeAt(index)) }) }) else null,
                        onMoveDown = if (index < presets.lastIndex) ({ onSave(presets.toMutableList().apply { add(index + 1, removeAt(index)) }) }) else null,
                        onDelete = { onSave(presets.filterIndexed { i, _ -> i != index }) }
                    )
                }
            }
        }

        // 底部「+ 添加子代理」
        Surface(
            onClick = {
                onSave(presets + SubagentPreset(id = UUID.randomUUID().toString(), name = defaultPresetName(presets)))
            },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(Radius.md)
        ) {
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.subagent_preset_add),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // 编辑面板：点击某一行时弹出
    editingIndex?.let { index ->
        val preset = presets.getOrNull(index) ?: return@let
        SubagentPresetEditSheet(
            preset = preset,
            providers = providers,
            modelMetadata = modelMetadata,
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                onSave(presets.mapIndexed { i, p -> if (i == index) updated else p })
                editingIndex = null
            }
        )
    }
}

/** 列表行：名称 + 模型摘要 + 提醒标记 + 上/下移 + 删除按钮，点击整行编辑。 */
@Composable
private fun SubagentPresetRow(
    preset: SubagentPreset,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    SettingsRow(
        icon = FeatherIcons.Layers,
        title = preset.name.ifBlank { stringResource(R.string.settings_subagent_preset_name) },
        subtitle = preset.model?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.settings_subagent_follow_chat),
        onClick = onClick,
        trailing = {
            if (preset.modeReminders.isNotEmpty()) {
                Icon(
                    imageVector = FeatherIcons.AlertCircle,
                    contentDescription = stringResource(R.string.settings_subagent_reminder),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = FeatherIcons.ChevronUp,
                        contentDescription = stringResource(R.string.common_move_up),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = FeatherIcons.ChevronDown,
                        contentDescription = stringResource(R.string.common_move_down),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

/** 单个预设的编辑面板：改名称、选模型、配模式提醒。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentPresetEditSheet(
    preset: SubagentPreset,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onDismiss: () -> Unit,
    onSave: (SubagentPreset) -> Unit
) {
    // 按 preset.id 重置表单状态：连续编辑不同预设时不残留上一个的值
    key(preset.id) {
        var name by remember { mutableStateOf(preset.name) }
        var providerId by remember { mutableStateOf(preset.providerId.orEmpty()) }
        var model by remember { mutableStateOf(preset.model.orEmpty()) }
        var reminders by remember { mutableStateOf(preset.modeReminders) }
        var showModelSheet by remember { mutableStateOf(false) }
        var showReminderSheet by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
            containerColor = settingsPageBackground()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = stringResource(R.string.settings_subagent_model),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )

                // 名称输入
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_subagent_preset_name),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Spacing.md))

                SettingsGroup {
                    // 模型选择行：点击弹模型选择
                    SettingsRow(
                        icon = FeatherIcons.Zap,
                        title = stringResource(R.string.settings_subagent_model),
                        onClick = { showModelSheet = true },
                        trailing = {
                            Text(
                                text = model.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.settings_subagent_follow_chat),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    )
                    SettingsDivider()
                    // 模式提醒行：点击弹提醒面板
                    SettingsRow(
                        icon = FeatherIcons.AlertCircle,
                        title = stringResource(R.string.settings_subagent_reminder),
                        subtitle = reminders.joinToString("、").takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subagent_reminder_none),
                        onClick = { showReminderSheet = true }
                    )
                }

                Spacer(Modifier.height(Spacing.lg))
                // 保存
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(Spacing.sm))
                    TextButton(
                        onClick = {
                            onSave(
                                preset.copy(
                                    name = name.trim().ifBlank { preset.name },
                                    providerId = providerId.ifBlank { null },
                                    model = model.ifBlank { null },
                                    modeReminders = reminders
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }
        }

        if (showModelSheet) {
            ModelSelectionSheet(
                title = stringResource(R.string.settings_subagent_model),
                noModelsText = stringResource(R.string.subagent_no_models),
                providers = providers,
                currentProviderId = providerId,
                currentModel = model,
                modelMetadata = modelMetadata,
                onSelect = { pid, m ->
                    providerId = pid
                    model = m
                    showModelSheet = false
                },
                onClear = {
                    providerId = ""
                    model = ""
                    showModelSheet = false
                },
                onDismiss = { showModelSheet = false }
            )
        }

        if (showReminderSheet) {
            SubagentReminderSheet(
                reminders = reminders,
                onSave = { reminders = it },
                onDismiss = { showReminderSheet = false }
            )
        }
    }
}

/** 默认预设名：子代理1、子代理2…（取现有最大序号的下一个，避免删除中间行后重名）。 */
private fun defaultPresetName(existing: List<SubagentPreset>): String {
    val next = existing.mapNotNull { p ->
        Regex("^子代理(\\d+)$").matchEntire(p.name.trim())?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull()?.plus(1) ?: 1
    return "子代理$next"
}

/**
 * 模式提醒配置弹窗：勾选内置 key（AUTO/PLAN，注入对应模式规则全文）+ 自定义文本列表。
 * 保存的是原始条目（key 或自定义文本），由 TaskTool 在注入时解析 key 展开全文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentReminderSheet(
    reminders: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val autoSelected = remember { mutableStateOf("AUTO" in reminders) }
    val planSelected = remember { mutableStateOf("PLAN" in reminders) }
    val customItems = remember { mutableStateListOf<String>().apply { addAll(reminders.filter { it != "AUTO" && it != "PLAN" }) } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.settings_subagent_reminder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.md)
            )

            // 内置 key 勾选
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Zap,
                    title = stringResource(R.string.subagent_reminder_auto),
                    onClick = { autoSelected.value = !autoSelected.value },
                    trailing = {
                        androidx.compose.material3.Checkbox(
                            checked = autoSelected.value,
                            onCheckedChange = { autoSelected.value = it }
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Zap,
                    title = stringResource(R.string.subagent_reminder_plan),
                    onClick = { planSelected.value = !planSelected.value },
                    trailing = {
                        androidx.compose.material3.Checkbox(
                            checked = planSelected.value,
                            onCheckedChange = { planSelected.value = it }
                        )
                    }
                )
            }

            // 自定义提醒列表
            Text(
                text = stringResource(R.string.subagent_reminder_custom),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm)
            )
            if (customItems.isEmpty()) {
                Text(
                    text = stringResource(R.string.subagent_reminder_custom_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            } else {
                customItems.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppTextField(
                            value = value,
                            onValueChange = { customItems[index] = it },
                            label = null,
                            placeholder = stringResource(R.string.subagent_reminder_custom_hint),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { customItems.removeAt(index) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Surface(
                onClick = { customItems.add("") },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = Spacing.xs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.common_add),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.width(Spacing.sm))
                TextButton(
                    onClick = {
                        val merged = buildList {
                            if (autoSelected.value) add("AUTO")
                            if (planSelected.value) add("PLAN")
                            addAll(customItems.map { it.trim() }.filter { it.isNotBlank() })
                        }
                        onSave(merged)
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}
