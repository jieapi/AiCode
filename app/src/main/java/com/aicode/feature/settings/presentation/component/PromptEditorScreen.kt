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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 提示词片段新建/编辑页：自带顶栏（返回 + 右上角保存），与 [SubAgentEditorScreen] 一致，
 * 不能嵌进设置页的 Scaffold。
 *
 * 编号即身份，决定片段在系统提示词里的位置；可改。作用域选项目或全局，决定覆盖写到哪一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptEditorScreen(
    isNew: Boolean,
    initialNumber: Int,
    initialTitle: String,
    initialDescription: String,
    initialContent: String,
    initialScope: PromptFragmentSource,
    hasWorkspace: Boolean,
    onSave: (number: Int, title: String, scope: PromptFragmentSource, content: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var numberText by rememberSaveable { mutableStateOf(if (isNew) "" else "%02d".format(initialNumber)) }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var content by rememberSaveable { mutableStateOf(initialContent) }
    var scopeIsProject by rememberSaveable { mutableStateOf(initialScope == PromptFragmentSource.PROJECT) }

    val number = numberText.toIntOrNull()
    val numberValid = number != null && number in 0..99
    val canSave = numberValid && title.isNotBlank()

    BackHandler { onNavigateBack() }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.prompts_editor_new else R.string.prompts_editor_edit
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
                                number ?: 0,
                                title.trim(),
                                if (scopeIsProject) PromptFragmentSource.PROJECT else PromptFragmentSource.GLOBAL,
                                PromptFragment.composeContent(description, content)
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
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.prompts_field_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = numberText,
                        onValueChange = { new -> numberText = new.filter { it.isDigit() }.take(2) },
                        label = stringResource(R.string.prompts_field_number),
                        placeholder = stringResource(R.string.prompts_field_number_placeholder),
                        singleLine = true
                    )
                    AppTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = stringResource(R.string.prompts_field_title),
                        placeholder = stringResource(R.string.prompts_field_title_placeholder),
                        singleLine = true
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
                        FilterChip(
                            selected = scopeIsProject,
                            enabled = hasWorkspace,
                            onClick = { scopeIsProject = true },
                            label = { Text(stringResource(R.string.subagent_scope_project)) }
                        )
                        FilterChip(
                            selected = !scopeIsProject,
                            onClick = { scopeIsProject = false },
                            label = { Text(stringResource(R.string.subagent_scope_global)) }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.prompts_field_summary))
            SettingsGroup {
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.prompts_field_summary_placeholder),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.prompts_field_content))
            SettingsGroup {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = stringResource(R.string.prompts_field_content_placeholder),
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
