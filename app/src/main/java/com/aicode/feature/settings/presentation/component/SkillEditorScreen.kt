package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.skill.SkillForm
import com.aicode.feature.agent.domain.skill.SkillSaveError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.settings.presentation.SkillSaveState
import com.aicode.feature.settings.presentation.SkillUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 技能新建/编辑页：自带顶栏（与 [SubAgentEditorScreen] 一致，不能嵌进设置页的 Scaffold）。
 *
 * 表单字段对应 `SKILL.md` 的 frontmatter 与正文，保存时由 SkillParser.serialize 写回文件，
 * 所以在 App 内建的技能与手写的技能完全等价，可继续用文本编辑器改。
 *
 * @param initial 编辑目标；null 表示新建。
 * @param onSaved 保存成功后回调，带回存盘后的名称（改过名就是新名），供调用方定位详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillEditorScreen(
    initial: SkillUiEntry?,
    saveState: SkillSaveState,
    onSave: (SkillForm, SkillScope) -> Unit,
    onSaved: (String) -> Unit,
    onNavigateBack: () -> Unit,
    defaultScope: SkillScope = SkillScope.GLOBAL
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var instructions by rememberSaveable { mutableStateOf(initial?.instructions ?: "") }
    var scopeIsGlobal by rememberSaveable {
        mutableStateOf(initial?.scope?.let { it == SkillScope.GLOBAL } ?: (defaultScope == SkillScope.GLOBAL))
    }

    LaunchedEffect(saveState) {
        if (saveState is SkillSaveState.Saved) onSaved(name.trim())
    }

    BackHandler { onNavigateBack() }

    val scope = if (scopeIsGlobal) SkillScope.GLOBAL else SkillScope.PROJECT
    val canSave = name.isNotBlank() && instructions.isNotBlank()

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (initial == null) R.string.skills_editor_new else R.string.skills_editor_edit
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
                        enabled = canSave && saveState !is SkillSaveState.Saving,
                        onClick = {
                            onSave(
                                SkillForm(
                                    name = name.trim(),
                                    description = description.trim(),
                                    instructions = instructions.trim(),
                                    requiredTools = initial?.requiredTools ?: emptyList()
                                ),
                                scope
                            )
                        }
                    ) {
                        if (saveState is SkillSaveState.Saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            (saveState as? SkillSaveState.Failed)?.let { failed ->
                Text(
                    text = stringResource(failed.error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.skills_editor_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.common_name),
                        placeholder = stringResource(R.string.skills_name_hint),
                        singleLine = true
                    )
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.skills_description),
                        placeholder = stringResource(R.string.skills_description_hint),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.skills_editor_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 作用域决定技能目录落在哪儿，改已有技能的作用域等于搬目录，这里只允许新建时选。
                        FilterChip(
                            selected = !scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = false },
                            label = { Text(stringResource(R.string.skills_scope_project)) }
                        )
                        FilterChip(
                            selected = scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = true },
                            label = { Text(stringResource(R.string.skills_scope_global)) }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.skills_instructions))
            SettingsGroup {
                AppTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    placeholder = stringResource(R.string.skills_instructions_hint),
                    singleLine = false,
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }
    }
}

private fun SkillSaveError.messageRes(): Int = when (this) {
    SkillSaveError.INVALID_NAME -> R.string.skills_editor_error_invalid_name
    SkillSaveError.EMPTY_INSTRUCTIONS -> R.string.skills_editor_error_empty_instructions
    SkillSaveError.NAME_CONFLICT -> R.string.skills_editor_error_name_conflict
    SkillSaveError.IO_FAILED -> R.string.skills_editor_error_io
}
