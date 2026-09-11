package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionForm
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.AgentSaveError
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.settings.presentation.SubAgentSaveState
import com.aicode.feature.settings.presentation.SubAgentUiEntry
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check

/**
 * 子代理新建/编辑页：自带顶栏（与 [ProviderEditorScreen] 一致，不能嵌进设置页的 Scaffold）。
 *
 * 表单字段对应定义文件的 frontmatter，保存时由 AgentDefinitionParser.serialize 写回 `.md`，
 * 所以在 App 内建的子代理与手写的定义完全等价，可继续用文本编辑器改。
 *
 * @param initial 编辑目标；null 表示新建。
 * @param onSaved 保存成功后回调，带回存盘后的名称（改过名就是新名），供调用方定位详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentEditorScreen(
    initial: SubAgentUiEntry?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    availableTools: List<String>,
    saveState: SubAgentSaveState,
    onLoadMetadata: () -> Unit,
    onSave: (AgentDefinitionForm, AgentDefinitionScope) -> Unit,
    onSaved: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var providerId by rememberSaveable { mutableStateOf(initial?.providerId ?: "") }
    var model by rememberSaveable { mutableStateOf(initial?.model ?: "") }
    var effort by rememberSaveable { mutableStateOf(initial?.reasoningEffort ?: "") }
    var mode by rememberSaveable { mutableStateOf(initial?.mode?.name ?: "") }
    var prompt by rememberSaveable { mutableStateOf(initial?.prompt ?: "") }
    var scopeIsGlobal by rememberSaveable {
        mutableStateOf(initial?.scope?.let { it == AgentDefinitionScope.GLOBAL } ?: true)
    }
    // Set<InjectPart> 进不了 Bundle，存 token 列表；切页或旋转后仍能还原。
    var injectTokens by rememberSaveable {
        mutableStateOf((initial?.inject ?: AgentDefinition.DEFAULT_INJECT).map { it.token })
    }
    var allowTools by rememberSaveable { mutableStateOf(initial?.allowedTools ?: emptyList()) }
    var denyTools by rememberSaveable { mutableStateOf(initial?.disallowedTools ?: emptyList()) }

    var showModelSheet by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }
    var showModeSheet by remember { mutableStateOf(false) }
    var showAllowSheet by remember { mutableStateOf(false) }
    var showDenySheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadMetadata() }
    LaunchedEffect(saveState) {
        if (saveState is SubAgentSaveState.Saved) onSaved(name.trim())
    }

    BackHandler { onNavigateBack() }

    val scope = if (scopeIsGlobal) AgentDefinitionScope.GLOBAL else AgentDefinitionScope.PROJECT
    val canSave = name.isNotBlank() && prompt.isNotBlank()

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (initial == null) R.string.subagent_editor_new else R.string.subagent_editor_edit
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                AgentDefinitionForm(
                                    name = name.trim(),
                                    description = description.trim(),
                                    providerId = providerId.ifBlank { null },
                                    model = model.ifBlank { null },
                                    reasoningEffort = effort.ifBlank { null },
                                    mode = mode.takeIf { it.isNotBlank() }?.let { AgentMode.valueOf(it) },
                                    allowedTools = allowTools,
                                    disallowedTools = denyTools,
                                    inject = injectTokens.mapNotNull { InjectPart.fromToken(it) }.toSet(),
                                    prompt = prompt.trim()
                                ),
                                scope
                            )
                        }
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            (saveState as? SubAgentSaveState.Failed)?.let { failed ->
                Text(
                    text = stringResource(failed.error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.subagent_editor_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.common_name),
                        placeholder = stringResource(R.string.subagent_name_hint),
                        singleLine = true
                    )
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.subagent_editor_description),
                        placeholder = stringResource(R.string.subagent_description_hint),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_editor_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 作用域决定文件落在哪个目录，改已有定义的作用域等于移动文件，这里只允许新建时选。
                        FilterChip(
                            selected = !scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = false },
                            label = { Text(stringResource(R.string.subagent_scope_project)) }
                        )
                        FilterChip(
                            selected = scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = true },
                            label = { Text(stringResource(R.string.subagent_scope_global)) }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.subagent_editor_model_group))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.subagent_model),
                    onClick = { showModelSheet = true },
                    trailing = { ValueText(model.ifBlank { stringResource(R.string.subagent_inherit_parent) }) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.subagent_editor_effort),
                    onClick = { showEffortSheet = true },
                    trailing = {
                        ValueText(
                            effort.takeIf { it.isNotBlank() }
                                ?.let { value ->
                                    ReasoningEffort.entries.firstOrNull { it.apiValue == value }
                                        ?.let { stringResource(it.labelRes()) }
                                }
                                ?: stringResource(R.string.subagent_inherit_parent)
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.subagent_editor_mode),
                    onClick = { showModeSheet = true },
                    trailing = { ValueText(mode.ifBlank { stringResource(R.string.subagent_inherit_parent) }) }
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.subagent_tools))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.subagent_editor_tools_allow),
                    subtitle = stringResource(R.string.subagent_editor_tools_allow_hint),
                    onClick = { showAllowSheet = true },
                    trailing = {
                        ValueText(
                            if (allowTools.isEmpty()) {
                                stringResource(R.string.subagent_all_tools)
                            } else {
                                stringResource(R.string.subagent_editor_selected_count, allowTools.size)
                            }
                        )
                    }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.subagent_editor_tools_deny),
                    subtitle = stringResource(R.string.subagent_editor_tools_deny_hint),
                    onClick = { showDenySheet = true },
                    trailing = {
                        ValueText(
                            if (denyTools.isEmpty()) {
                                stringResource(R.string.subagent_editor_none)
                            } else {
                                stringResource(R.string.subagent_editor_selected_count, denyTools.size)
                            }
                        )
                    }
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.subagent_inject))
            SettingsGroup {
                InjectPart.entries.forEachIndexed { index, part ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(part.labelRes()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(part.hintRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppSwitch(
                            checked = part.token in injectTokens,
                            onCheckedChange = { checked ->
                                injectTokens = if (checked) {
                                    injectTokens + part.token
                                } else {
                                    injectTokens - part.token
                                }
                            }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.subagent_prompt))
            SettingsGroup {
                AppTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = stringResource(R.string.subagent_prompt_hint),
                    singleLine = false,
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }
    }

    if (showModelSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.subagent_model),
            noModelsText = stringResource(R.string.subagent_no_models),
            providers = providers,
            currentProviderId = providerId,
            currentModel = model,
            modelMetadata = modelMetadata,
            onSelect = { pid, selected ->
                providerId = pid
                model = selected
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

    if (showEffortSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEffortSheet = false },
            sheetState = sheetState,
            containerColor = settingsPageBackground()
        ) {
            Column(
                modifier = Modifier
                    .nestedScroll(rememberSheetFlingFix(sheetState))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SettingsGroupHeader(text = stringResource(R.string.subagent_editor_effort))
                SettingsGroup {
                    PickerRow(
                        title = stringResource(R.string.subagent_inherit_parent),
                        selected = effort.isBlank(),
                        onClick = {
                            effort = ""
                            showEffortSheet = false
                        }
                    )
                    ReasoningEffort.entries.forEach { item ->
                        SettingsDivider()
                        PickerRow(
                            title = stringResource(item.labelRes()),
                            selected = effort == item.apiValue,
                            onClick = {
                                effort = item.apiValue
                                showEffortSheet = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (showModeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            sheetState = sheetState,
            containerColor = settingsPageBackground()
        ) {
            Column(
                modifier = Modifier
                    .nestedScroll(rememberSheetFlingFix(sheetState))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SettingsGroupHeader(text = stringResource(R.string.subagent_editor_mode))
                SettingsGroup {
                    PickerRow(
                        title = stringResource(R.string.subagent_inherit_parent),
                        selected = mode.isBlank(),
                        onClick = {
                            mode = ""
                            showModeSheet = false
                        }
                    )
                    AgentMode.entries.forEach { item ->
                        SettingsDivider()
                        PickerRow(
                            title = item.name,
                            selected = mode == item.name,
                            onClick = {
                                mode = item.name
                                showModeSheet = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAllowSheet) {
        ToolPickerSheet(
            title = stringResource(R.string.subagent_editor_tools_allow),
            hint = stringResource(R.string.subagent_editor_tools_allow_hint),
            allTools = availableTools,
            selected = allowTools,
            onConfirm = {
                allowTools = it
                showAllowSheet = false
            },
            onDismiss = { showAllowSheet = false }
        )
    }

    if (showDenySheet) {
        ToolPickerSheet(
            title = stringResource(R.string.subagent_editor_tools_deny),
            hint = stringResource(R.string.subagent_editor_tools_deny_hint),
            allTools = availableTools,
            selected = denyTools,
            onConfirm = {
                denyTools = it
                showDenySheet = false
            },
            onDismiss = { showDenySheet = false }
        )
    }
}

/**
 * 工具多选 Sheet：带搜索的勾选列表。工具名里的 `task` 也会列出来但选它没用——
 * 子代理不能嵌套派发，保存时由 AgentDefinition.filterToolNames 统一剔除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolPickerSheet(
    title: String,
    hint: String,
    allTools: List<String>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected.toSet()) }
    val visible = remember(query, allTools) {
        if (query.isBlank()) allTools else allTools.filter { it.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { picked = emptySet() }) {
                    Text(stringResource(R.string.subagent_editor_clear))
                }
                TextButton(onClick = { onConfirm(picked.toList().sorted()) }) {
                    Text(stringResource(R.string.common_save))
                }
            }

            ModelSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.subagent_editor_tools_search)
            )

            Box(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .nestedScroll(rememberSheetFlingFix(sheetState))
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SettingsGroup {
                        visible.forEachIndexed { index, tool ->
                            if (index > 0) SettingsDivider()
                            PickerRow(
                                title = tool,
                                selected = tool in picked,
                                onClick = {
                                    picked = if (tool in picked) picked - tool else picked + tool
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单选/多选列表行。不用 [SettingsRow]：它在可点击时会追加右尖头（勾选列表里是误导），
 * 而且只在选中时渲染尾部图标会让行内文字随勾选左右跳。图标始终占位，未选中时透明。
 */
@Composable
private fun PickerRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = FeatherIcons.Check,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier
                .padding(start = Spacing.sm)
                .size(18.dp)
        )
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = Spacing.sm)
    )
}

private fun AgentSaveError.messageRes(): Int = when (this) {
    AgentSaveError.INVALID_NAME -> R.string.subagent_editor_error_invalid_name
    AgentSaveError.EMPTY_PROMPT -> R.string.subagent_editor_error_empty_prompt
    AgentSaveError.NAME_CONFLICT -> R.string.subagent_editor_error_name_conflict
    AgentSaveError.IO_FAILED -> R.string.subagent_editor_error_io
}

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> R.string.chat_reasoning_effort_none
    ReasoningEffort.MINIMAL -> R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> R.string.chat_reasoning_effort_max
}

private fun InjectPart.labelRes(): Int = when (this) {
    InjectPart.BASE -> R.string.subagent_inject_base
    InjectPart.MAIN_RULES -> R.string.subagent_inject_main_rules
    InjectPart.SKILLS -> R.string.subagent_inject_skills
    InjectPart.MEMORY -> R.string.subagent_inject_memory
    InjectPart.PROJECT_RULES -> R.string.subagent_inject_project_rules
}

private fun InjectPart.hintRes(): Int = when (this) {
    InjectPart.BASE -> R.string.subagent_inject_base_hint
    InjectPart.MAIN_RULES -> R.string.subagent_inject_main_rules_hint
    InjectPart.SKILLS -> R.string.subagent_inject_skills_hint
    InjectPart.MEMORY -> R.string.subagent_inject_memory_hint
    InjectPart.PROJECT_RULES -> R.string.subagent_inject_project_rules_hint
}
