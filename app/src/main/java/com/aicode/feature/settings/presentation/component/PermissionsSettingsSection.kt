package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.permission.PermissionDecision
import com.aicode.feature.agent.domain.permission.PermissionRule
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Globe
import compose.icons.feathericons.XCircle

/**
 * 「工具授权」二级页：与设置页其它二级页一致的 iOS 分组列表。
 * 按「当前项目 / 全局」分组列出已保存的授权规则，分组可折叠收起；
 * 规则左滑删除；项目规则可「提升为全局」。
 */
@Composable
internal fun PermissionsSection(
    projectName: String?,
    projectRules: List<PermissionRule>,
    globalRules: List<PermissionRule>,
    approvalSwitches: Map<String, Boolean>,
    onToggleApproval: (String, Boolean) -> Unit,
    onDeleteProject: (PermissionRule) -> Unit,
    onPromote: (PermissionRule) -> Unit,
    onDeleteGlobal: (PermissionRule) -> Unit
) {
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.perm_approval_switches_title))
        SettingsGroup {
            ApprovalSwitchRow("Bash", R.string.perm_tool_bash, approvalSwitches["Bash"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("terminal", R.string.perm_tool_terminal, approvalSwitches["terminal"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("writeFile", R.string.perm_tool_writefile, approvalSwitches["writeFile"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("editFile", R.string.perm_tool_editfile, approvalSwitches["editFile"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("task", R.string.perm_tool_task, approvalSwitches["task"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("mcp", R.string.perm_tool_mcp, approvalSwitches["mcp"] ?: true, onToggleApproval)
        }
        FooterNote(stringResource(R.string.perm_approval_switches_desc))

        SettingsGroupHeader(text = stringResource(R.string.perm_approval_git_title))
        SettingsGroup {
            ApprovalSwitchRow("git_commit", R.string.perm_tool_git_commit, approvalSwitches["git_commit"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("git_push", R.string.perm_tool_git_push, approvalSwitches["git_push"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("git_pull", R.string.perm_tool_git_pull, approvalSwitches["git_pull"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("git_branch", R.string.perm_tool_git_branch, approvalSwitches["git_branch"] ?: true, onToggleApproval)
            SettingsDivider()
            ApprovalSwitchRow("git_other", R.string.perm_tool_git_other, approvalSwitches["git_other"] ?: true, onToggleApproval)
        }
        FooterNote(stringResource(R.string.perm_approval_git_desc))

        CollapsibleGroupHeader(
            text = if (projectName != null) {
                stringResource(R.string.perm_current_project, projectName)
            } else {
                stringResource(R.string.perm_current_project_none)
            },
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded }
        )
        AnimatedVisibility(visible = projectExpanded) {
            SettingsGroup {
                if (projectRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_project_rules))
                } else {
                    projectRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteProject(rule) },
                            onPromote = { onPromote(rule) }
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_global),
            expanded = globalExpanded,
            onToggle = { globalExpanded = !globalExpanded }
        )
        AnimatedVisibility(visible = globalExpanded) {
            SettingsGroup {
                if (globalRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_global_rules))
                } else {
                    globalRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteGlobal(rule) },
                            onPromote = null
                        )
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.perm_whitelist_short))
        FooterNote(stringResource(R.string.perm_rules_short))
    }
}

/** 单条工具审批开关行：标题 + 右侧 AppSwitch。 */
@Composable
private fun ApprovalSwitchRow(
    toolKey: String,
    titleRes: Int,
    checked: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    SettingsRow(
        title = stringResource(titleRes),
        trailing = {
            AppSwitch(
                checked = checked,
                onCheckedChange = { enabled -> onToggle(toolKey, enabled) }
            )
        }
    )
}

/**
 * 单条规则行：紧凑布局（同设置页行），判定图标（允许对勾 / 禁止叉）+ 工具名（主）+ 匹配范围/判定（次），
 * 右侧「提升为全局」图标 (仅项目规则)；左滑露出删除按钮。
 */
@Composable
internal fun RuleRow(
    rule: PermissionRule,
    onDelete: () -> Unit,
    onPromote: (() -> Unit)?
) {
    val allowed = rule.decision == PermissionDecision.ALLOW
    val iconTint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    SwipeToDeleteRow(onDelete = onDelete) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (allowed) FeatherIcons.CheckCircle else FeatherIcons.XCircle,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.toolName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = (if (rule.pattern == PermissionRule.WHOLE_TOOL) {
                        stringResource(R.string.perm_entire_tool)
                    } else {
                        rule.pattern
                    }) + " · " + if (allowed) {
                        stringResource(R.string.common_allow)
                    } else {
                        stringResource(R.string.perm_deny)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onPromote != null) {
                IconButton(onClick = onPromote) {
                    Icon(
                        imageVector = FeatherIcons.Globe,
                        contentDescription = stringResource(R.string.perm_promote_to_global),
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun RuleEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

/** 页脚说明：灰色小字。 */
@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs)
    )
}
