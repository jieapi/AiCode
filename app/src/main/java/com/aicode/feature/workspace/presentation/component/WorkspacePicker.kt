package com.aicode.feature.workspace.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.aicode.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceChip(
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchConfirmed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { showSheet = true }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = current?.name ?: stringResource(R.string.workspace_select),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            FeatherIcons.MoreHorizontal,
            contentDescription = stringResource(R.string.workspace_switch),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }

    WorkspaceSelectionHost(
        visible = showSheet,
        onDismiss = { showSheet = false },
        viewModel = viewModel,
        hasRunningSessions = hasRunningSessions,
        onSwitchConfirmed = onSwitchConfirmed
    )
}

/**
 * 顶栏的工作区选择图标按钮：点击弹出选择/新建/删除面板。
 */
@Composable
fun WorkspaceIconButton(
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchConfirmed: () -> Unit = {},
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    var showSheet by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showSheet = true },
        modifier = modifier
    ) {
        Icon(
            FeatherIcons.Folder,
            contentDescription = stringResource(R.string.workspace_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }

    // 远程工作区路径/默认工作区创建失败提示：消费后清除，避免重复弹 Toast
    val initError by viewModel.initError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(initError) {
        if (initError != null) {
            Toast.makeText(context, initError, Toast.LENGTH_LONG).show()
            viewModel.consumeInitError()
        }
    }

    WorkspaceSelectionHost(
        visible = showSheet,
        onDismiss = { showSheet = false },
        viewModel = viewModel,
        hasRunningSessions = hasRunningSessions,
        onSwitchConfirmed = onSwitchConfirmed
    )
}

/**
 * 工作区选择面板 + 切换确认弹窗的公共宿主：Chip 与 IconButton 两个入口共用，
 * 有会话运行时切换需先确认（确认回调由调用方执行停止逻辑）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSelectionHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean,
    onSwitchConfirmed: () -> Unit
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val isLocalMode by viewModel.isLocalMode.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    val externalWarningDismissed by viewModel.externalWarningDismissed.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingWorkspaceSelect by remember { mutableStateOf<Workspace?>(null) }
    var showExternalWarning by remember { mutableStateOf(false) }
    var dontShowExternalWarningAgain by remember { mutableStateOf(false) }

    fun select(ws: Workspace) {
        if (hasRunningSessions()) {
            pendingWorkspaceSelect = ws
        } else {
            onSwitchConfirmed()
            viewModel.selectWorkspace(ws.ref)
            onDismiss()
        }
    }

    // 添加外部本地工作区失败提示：消费后清除，避免重复弹 Toast
    LaunchedEffect(addError) {
        if (addError != null) {
            Toast.makeText(context, addError, Toast.LENGTH_LONG).show()
            viewModel.consumeAddError()
        }
    }

    // 系统目录选择器：选设备上的目录注册为外部本地工作区
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.addExternalWorkspace(uri) { created ->
                if (created != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.workspace_external_added, created.name),
                        Toast.LENGTH_LONG
                    ).show()
                    onDismiss()
                    select(created)
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) viewModel.refreshAvailability()
    }

    if (visible) {
        WorkspaceSheet(
            workspaces = workspaces,
            current = current,
            isLocalMode = isLocalMode,
            onAddExternal = {
                if (externalWarningDismissed) folderLauncher.launch(null)
                else showExternalWarning = true
            },
            onSelect = ::select,
            onCreate = { name ->
                viewModel.createWorkspace(name) { created ->
                    if (created != null) select(created)
                }
            },
            onDelete = { viewModel.deleteWorkspace(it.ref) },
            onStopCurrentSessions = onSwitchConfirmed,
            deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
            onDismiss = onDismiss
        )
    }

    if (showExternalWarning) {
        AlertDialog(
            onDismissRequest = { showExternalWarning = false },
            title = { Text(stringResource(R.string.workspace_external_warning_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.workspace_external_warning_message))
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontShowExternalWarningAgain = !dontShowExternalWarningAgain },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowExternalWarningAgain,
                            onCheckedChange = { dontShowExternalWarningAgain = it }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.workspace_external_warning_dont_show_again))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowExternalWarningAgain) viewModel.setExternalWarningDismissed(true)
                    showExternalWarning = false
                    folderLauncher.launch(null)
                }) { Text(stringResource(R.string.workspace_external_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExternalWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingWorkspaceSelect?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingWorkspaceSelect = null },
            title = { Text(stringResource(R.string.workspace_switch)) },
            text = { Text(stringResource(R.string.workspace_switch_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    viewModel.selectWorkspace(ws.ref)
                    pendingWorkspaceSelect = null
                    onDismiss()
                }) { Text(stringResource(R.string.workspace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWorkspaceSelect = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSheet(
    workspaces: List<Workspace>,
    current: Workspace?,
    isLocalMode: Boolean,
    onAddExternal: () -> Unit,
    onSelect: (Workspace) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Workspace) -> Unit,
    onStopCurrentSessions: () -> Unit,
    deleteExternalWorkspaceSessions: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }
    val createWorkspaceLabel = stringResource(
        if (isLocalMode) R.string.workspace_new_internal else R.string.workspace_new_workspace
    )

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.common_workspace),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isLocalMode) {
                    TextButton(onClick = onAddExternal) {
                        Icon(FeatherIcons.HardDrive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.workspace_add_external))
                    }
                }
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(createWorkspaceLabel)
                }
            }

            if (workspaces.isEmpty()) {
                Text(
                    stringResource(R.string.workspace_empty_hint, createWorkspaceLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(workspaces, key = { it.ref.stableKey }) { ws ->
                        WorkspaceRow(
                            workspace = ws,
                            selected = ws.ref == current?.ref,
                            canDelete = ws.canDelete && workspaces.size > 1,
                            onClick = { onSelect(ws) },
                            onDelete = { pendingDelete = ws }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateWorkspaceDialog(
            title = createWorkspaceLabel,
            existingNames = workspaces.map { it.name },
            onConfirm = {
                onCreate(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.workspace_delete)) },
            text = {
                Text(
                    if (ws.type == WorkspaceType.EXTERNAL_LOCAL) {
                        stringResource(
                            if (deleteExternalWorkspaceSessions) R.string.workspace_external_remove_confirm_sessions
                            else R.string.workspace_external_remove_confirm,
                            ws.name
                        )
                    } else {
                        stringResource(R.string.workspace_delete_confirm, ws.name)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ws.ref == current?.ref) onStopCurrentSessions()
                    onDelete(ws)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val external = workspace.type == WorkspaceType.EXTERNAL_LOCAL
    val disabled = !workspace.available
    val displayPath = when (workspace.type) {
        WorkspaceType.INTERNAL -> "projects/${workspace.name}"
        WorkspaceType.EXTERNAL_LOCAL, WorkspaceType.REMOTE -> workspace.path
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (external) FeatherIcons.HardDrive else FeatherIcons.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (external && disabled) {
                Text(
                    text = stringResource(R.string.workspace_external_unavailable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (canDelete) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
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

@Composable
private fun CreateWorkspaceDialog(
    title: String,
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = stringResource(R.string.common_name),
                    placeholder = stringResource(R.string.workspace_name_hint),
                    isError = duplicate,
                    colors = dialogTextFieldColors()
                )
                if (duplicate) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.workspace_name_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
