package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Server
import compose.icons.feathericons.Trash2
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 容器镜像二级页：所有容器（含内置 Alpine）统一管理——列表单选切换、编辑、重置、左滑删除；
 * 删光后空态可一键恢复内置 Alpine。内置 Alpine 首次启动自动写入列表，不享受特殊待遇。
 *
 * 选中某个 profile 时按其 [ContainerProfile.mode] 同步切全局执行模式——本地镜像走 PRoot 容器，
 * 远程 SSH 镜像走 SSH exec/SFTP。
 */
@Composable
internal fun ContainerSection(
    profiles: List<ContainerProfile>,
    activeProfileId: String,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    osMap: Map<String, String> = emptyMap(),
    showAddSheetExternal: Boolean = false,
    onDismissAddSheet: () -> Unit = {},
    onSelect: (String) -> Unit,
    onSetDefaultContainer: (String) -> Unit = {},
    onSaveCustom: (ContainerProfile) -> Unit,
    onEditCustom: (ContainerProfile) -> Unit,
    onDeleteProfile: (ContainerProfile) -> Unit,
    onSwitchConfirmed: () -> Unit = {},
    onResetProfile: (ContainerProfile) -> Unit = {},
    onRestoreBuiltin: () -> Unit = {},
    remoteConnections: List<RemoteConnection> = emptyList()
) {
    val context = LocalContext.current
    var showAddSheetInternal by remember { mutableStateOf(false) }
    val showAddSheet = showAddSheetInternal || showAddSheetExternal
    var editingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingSwitch by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingReset by remember { mutableStateOf<ContainerProfile?>(null) }

    if (profiles.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.container_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.container_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg)
            )
            Button(onClick = onRestoreBuiltin) {
                Text(stringResource(R.string.container_restore_builtin))
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            SettingsGroup {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) {
                        SettingsDivider()
                    }
                    ContainerRow(
                        profile = profile,
                        active = profile.id == activeProfileId,
                        defaultContainerId = defaultContainerId,
                        osId = osMap[profile.id],
                        subtitle = profileSubtitle(context, profile, remoteConnections),
                        onSelect = { if (profile.id != activeProfileId) pendingSwitch = profile },
                        onEdit = { editingProfile = profile },
                        onDelete = { deletingProfile = profile }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        ProfileEditSheet(
            initial = null,
            defaultContainerId = defaultContainerId,
            remoteConnections = remoteConnections,
            onDismiss = {
                showAddSheetInternal = false
                onDismissAddSheet()
            },
            onConfirm = { profile, setDefault ->
                val id = "custom-${System.currentTimeMillis()}"
                val final = if (profile.mode == ExecutionMode.REMOTE_SSH) {
                    profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_remote_ssh) })
                } else {
                    profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_custom_image) })
                }
                onSaveCustom(final)
                if (setDefault) onSetDefaultContainer(final.id)
                showAddSheetInternal = false
                onDismissAddSheet()
            }
        )
    }

    editingProfile?.let { editing ->
        ProfileEditSheet(
            initial = editing,
            defaultContainerId = defaultContainerId,
            remoteConnections = remoteConnections,
            onDismiss = { editingProfile = null },
            onReset = { pendingReset = editing },
            onConfirm = { profile, setDefault ->
                onEditCustom(profile.copy(id = editing.id))
                // 本地镜像才可作默认容器；关闭默认时若原本就是默认则回退内置 Alpine。
                if (profile.mode == ExecutionMode.LOCAL_PROOT) {
                    if (setDefault) {
                        onSetDefaultContainer(editing.id)
                    } else if (editing.id == defaultContainerId) {
                        onSetDefaultContainer(ContainerProfile.BUILTIN_ID)
                    }
                }
                editingProfile = null
            }
        )
    }

    deletingProfile?.let { deleting ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.container_delete_config)) },
            text = { Text(stringResource(R.string.container_delete_confirm, deleting.name, deleteHint(context, deleting))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteImageCopy(deleting)
                    onDeleteProfile(deleting)
                    deletingProfile = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(R.string.container_switch_image)) },
            text = { Text(stringResource(R.string.container_switch_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    onSelect(target.id)
                    pendingSwitch = null
                }) { Text(stringResource(R.string.common_switch)) }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingReset?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.container_reset_title)) },
            text = { Text(stringResource(R.string.container_reset_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetProfile(target)
                    pendingReset = null
                    if (editingProfile?.id == target.id) editingProfile = null
                }) { Text(stringResource(R.string.container_reset)) }
            },
            dismissButton = { TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** 删除确认的补充说明：内置可恢复、自定义本地清 rootfs、远程 SSH 无额外说明。 */
private fun deleteHint(context: Context, profile: ContainerProfile): String = when {
    profile.isBuiltin -> context.getString(R.string.container_builtin_restorable)
    profile.mode == ExecutionMode.REMOTE_SSH -> ""
    else -> context.getString(R.string.container_rootfs_will_be_cleared)
}

/** 系统 logo 映射：已识别的系统返回对应单色图标，未知/未识别返回 null（调用方回退通用图标）。 */
@Composable
private fun osLogo(osId: String?): Painter? = when (osId) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "centos" -> painterResource(R.drawable.logo_centos)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    else -> null
}

/**
 * 单个容器行：分组内白底行，左侧图标方块 + 名称（内置带徽章）/副标题，右侧选中勾选 + 编辑/重置按钮，左滑删除。
 * 行主体点击切换镜像（未选中时弹确认）。
 */
@Composable
private fun ContainerRow(
    profile: ContainerProfile,
    active: Boolean,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    osId: String?,
    subtitle: String,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val light = settingsLightMode()

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标方块
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                val osIcon = osLogo(osId)
                if (osIcon != null) {
                    Icon(
                        painter = osIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center)
                    )
                } else {
                    Icon(
                        imageVector = if (profile.mode == ExecutionMode.REMOTE_SSH) FeatherIcons.Server else FeatherIcons.HardDrive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // 中间：名称（内置徽章）+ 副标题
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (profile.isBuiltin) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_builtin_badge),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (profile.mode == ExecutionMode.REMOTE_SSH) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_ssh_badge),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_custom_badge),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (profile.id == defaultContainerId) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_default_badge),
                            color = MaterialTheme.semanticColors.warning
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 选中勾选
            if (active) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = stringResource(R.string.container_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 编辑入口（所有容器）：行尾铅笔，点击进入编辑弹窗（行主体点击是切换）
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = FeatherIcons.Edit3,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 来源徽章：主题色浅底胶囊小字，按来源类型传入不同颜色。 */
@Composable
private fun SourceBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 镜像列表项副标题：本地镜像（内置/自定义）统一显示生效 shell，不区分来源；远程 SSH 显示通道名。 */
private fun profileSubtitle(context: Context, profile: ContainerProfile, connections: List<RemoteConnection>): String {
    if (profile.mode == ExecutionMode.REMOTE_SSH) {
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh
        val connName = ssh?.connectionId?.let { cid -> connections.firstOrNull { it.id == cid }?.name }
        return connName ?: context.getString(R.string.container_channel_deleted)
    }
    val shellDesc = profile.shellPath?.ifBlank { null } ?: "/bin/sh"
    return context.getString(R.string.container_shell_desc, shellDesc)
}

/**
 * 添加/编辑镜像的 ModalBottomSheet：顶部标签切换本地镜像 / 远程 SSH。
 * 本地镜像分支：名称、shell 路径、额外绑定、额外参数、选 tar.gz 文件。
 * 远程 SSH 分支：名称、下拉选工作区已配置的 SFTP 通道、远程工作区路径。
 * 编辑内置 Alpine 时镜像来源保持内置（可选导入新文件覆盖，导入后转为自定义容器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(
    initial: ContainerProfile?,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    remoteConnections: List<RemoteConnection>,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    onConfirm: (ContainerProfile, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    // SFTP 通道才适合 SSH exec（FTP/LOCAL 不走 sshj）
    val sshConnections = remoteConnections.filter { it.protocol == RemoteProtocol.SFTP }
    // 编辑内置 Alpine 时保持 Asset 来源（可选导入覆盖）
    val initialAsset = initial?.rootfsSource as? RootfsSource.Asset

    var mode by remember { mutableStateOf(initial?.mode ?: ExecutionMode.LOCAL_PROOT) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // 是否设为默认容器（仅本地镜像生效；默认容器是远程模式下本地 MCP 等服务的运行容器）
    var setAsDefault by remember { mutableStateOf(initial?.id == defaultContainerId) }
    // 本地镜像字段
    var shellPath by remember { mutableStateOf(initial?.shellPath ?: "/bin/sh") }
    val bindingsList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.extraBindings?.map { b ->
                val parts = b.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else b to ""
            } ?: emptyList())
        }
    }
    val argsList = remember {
        mutableStateListOf<String>().apply { addAll(initial?.extraArgs ?: emptyList()) }
    }
    val envList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.env?.toList() ?: emptyList())
        }
    }
    val initialUri = (initial?.rootfsSource as? RootfsSource.LocalFile)?.uri
    var pickedUri by remember { mutableStateOf(initialUri) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(pickedUri) {
        pickedName = pickedUri?.let { queryImageDisplayName(context, it) }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pickedUri = uri.toString() }
    // 远程 SSH 字段
    val initialSsh = (initial?.rootfsSource as? RootfsSource.RemoteSsh)
    var selectedConnId by remember { mutableStateOf(initialSsh?.connectionId ?: sshConnections.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(initialSsh?.remoteWorkspacePath ?: "") }
    var connExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
                .nestedScroll(flingFix)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部：居中标题 + 右上角重置（编辑态）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(36.dp))
                Text(
                    text = if (initial == null) stringResource(R.string.container_add_image) else stringResource(R.string.container_edit_image),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                if (initial != null && onReset != null) {
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.container_reset),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val tabs = listOf(
                    ExecutionMode.LOCAL_PROOT to stringResource(R.string.container_local_image),
                    ExecutionMode.REMOTE_SSH to stringResource(R.string.container_remote_ssh)
                )
                tabs.forEach { (m, title) ->
                    val isSelected = mode == m
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { mode = m }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            ContainerField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.common_name)
            )

            if (mode == ExecutionMode.LOCAL_PROOT) {
                ContainerField(
                    value = shellPath,
                    onValueChange = { shellPath = it },
                    label = stringResource(R.string.container_shell_path)
                )

                MountListEditor(
                    title = stringResource(R.string.container_extra_bindings),
                    items = bindingsList,
                    emptyText = stringResource(R.string.container_no_bindings),
                    addText = stringResource(R.string.container_add_binding)
                )

                StringListEditor(
                    title = stringResource(R.string.container_extra_proot_args),
                    items = argsList,
                    itemLabel = stringResource(R.string.container_arg_value),
                    itemHint = "-k",
                    emptyText = stringResource(R.string.container_no_args),
                    addText = stringResource(R.string.container_add_arg)
                )

                PairListEditor(
                    title = stringResource(R.string.container_env_vars),
                    items = envList,
                    keyLabel = stringResource(R.string.container_env_name),
                    keyHint = "MY_VAR",
                    valueLabel = stringResource(R.string.container_env_value),
                    valueHint = "value",
                    emptyText = stringResource(R.string.container_no_env_vars),
                    addText = stringResource(R.string.container_add_env_var)
                )

                // 内置镜像固定为内置来源，不支持导入覆盖，隐藏文件选择
                if (initial?.rootfsSource !is RootfsSource.Asset) {
                    Spacer(modifier = Modifier.size(Spacing.xs))
                    Surface(
                        onClick = { pickLauncher.launch(arrayOf("*/*")) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                FeatherIcons.HardDrive,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = pickedName
                                    ?: if (pickedUri != null) stringResource(R.string.container_file_selected)
                                    else stringResource(R.string.container_select_image_file),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 设为默认容器：远程工作区模式下本地 MCP 等服务的运行容器
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.container_set_as_default),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.container_default_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    AppSwitch(
                        checked = setAsDefault,
                        onCheckedChange = { setAsDefault = it }
                    )
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = connExpanded,
                    onExpandedChange = { connExpanded = !connExpanded }
                ) {
                    val selectedName = sshConnections.firstOrNull { it.id == selectedConnId }?.name
                        ?: stringResource(R.string.container_select_ssh_channel)
                    ContainerField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        label = stringResource(R.string.container_ssh_channel),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = connExpanded,
                        onDismissRequest = { connExpanded = false }
                    ) {
                        if (sshConnections.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.container_no_sftp_channel)) },
                                onClick = { connExpanded = false }
                            )
                        } else {
                            sshConnections.forEach { conn ->
                                DropdownMenuItem(
                                    text = { Text("${conn.name} (${conn.host}:${conn.port})") },
                                    onClick = {
                                        selectedConnId = conn.id
                                        if (remotePath.isBlank()) {
                                            remotePath = "/home/${conn.username}/workspace"
                                        }
                                        connExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                ContainerField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = stringResource(R.string.container_remote_workspace_path),
                    placeholder = "/home/user/workspace"
                )
            }

            Spacer(modifier = Modifier.size(Spacing.xs))
            Button(
                onClick = {
                    importing = true
                    scope.launch {
                        val finalUri = pickedUri?.let { uri ->
                            withContext(Dispatchers.IO) { copyImageToPrivate(context, uri, initial?.id) }
                                ?: pickedUri
                        } ?: pickedUri
                        importing = false
                        val profile = buildProfile(
                            initial = initial,
                            mode = mode,
                            name = name,
                            shellPath = shellPath,
                            bindings = bindingsList
                                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                .map { (local, container) -> "${local.trim()}:${container.trim()}" },
                            args = argsList.map { it.trim() }.filter { it.isNotEmpty() },
                            env = envList
                                .map { it.first.trim() to it.second }
                                .filter { it.first.isNotEmpty() }
                                .toMap(),
                            pickedUri = finalUri,
                            selectedConnId = selectedConnId,
                            remotePath = remotePath
                        )
                        if (profile != null) {
                            onConfirm(profile, setAsDefault && profile.mode == ExecutionMode.LOCAL_PROOT)
                        }
                    }
                },
                enabled = !importing && canConfirm(mode, pickedUri, initialAsset, selectedConnId, sshConnections),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            importing -> R.string.container_importing
                            initial == null -> R.string.common_add
                            else -> R.string.common_save
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/** 字符串列表编辑器：标题 + 逐项卡片（输入框 + 删除）+ 底部「添加」按钮，样式对齐 MCP 弹窗的参数编辑。 */
@Composable
private fun StringListEditor(
    title: String,
    items: SnapshotStateList<String>,
    itemLabel: String,
    itemHint: String?,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, value ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = value,
                        onValueChange = { items[index] = it },
                        label = itemLabel,
                        placeholder = itemHint,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("") },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
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
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 挂载编辑器：每项卡片「本地目录 + 容器目录」两个输入框与删除按钮，保存时拼成 `本地:容器`。 */
@Composable
private fun MountListEditor(
    title: String,
    items: SnapshotStateList<Pair<String, String>>,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, (local, container) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = local,
                        onValueChange = { items[index] = it to container },
                        label = stringResource(R.string.container_mount_local),
                        placeholder = "/sdcard",
                    )
                    ContainerField(
                        value = container,
                        onValueChange = { items[index] = local to it },
                        label = stringResource(R.string.container_mount_container),
                        placeholder = "/mnt/sdcard",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("" to "") },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
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
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 键值对列表编辑器：每项卡片含「变量名 + 变量值」与删除按钮，样式对齐 MCP 弹窗的环境变量编辑。 */
@Composable
private fun PairListEditor(
    title: String,
    items: SnapshotStateList<Pair<String, String>>,
    keyLabel: String,
    keyHint: String?,
    valueLabel: String,
    valueHint: String?,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, (k, v) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = k,
                        onValueChange = { items[index] = it to v },
                        label = keyLabel,
                        placeholder = keyHint,
                    )
                    ContainerField(
                        value = v,
                        onValueChange = { items[index] = k to it },
                        label = valueLabel,
                        placeholder = valueHint,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("" to "") },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
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
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 据表单状态构造 ContainerProfile；校验不通过返回 null（按钮已 disabled，此处再兜底）。
 * 本地镜像：选了文件 → 导入的自定义容器（isBuiltin=false）；未选文件且原为内置 → 保持内置 Asset 来源（isBuiltin=true）。
 */
private fun buildProfile(
    initial: ContainerProfile?,
    mode: ExecutionMode,
    name: String,
    shellPath: String,
    bindings: List<String>,
    args: List<String>,
    env: Map<String, String>,
    pickedUri: String?,
    selectedConnId: String,
    remotePath: String
): ContainerProfile? {
    return when (mode) {
        ExecutionMode.LOCAL_PROOT -> {
            val assetSource = initial?.rootfsSource as? RootfsSource.Asset
            val rootfsSource = if (pickedUri != null) RootfsSource.LocalFile(pickedUri) else assetSource ?: return null
            ContainerProfile(
                id = "", // 由调用方覆写
                name = name,
                rootfsSource = rootfsSource,
                shellPath = shellPath.ifBlank { null },
                extraBindings = bindings,
                // 与下载导入一致：本地导入默认带 --link2symlink（Android 宿主不支持硬链接时
                // PRoot 用符号链接模拟，Debian/Ubuntu 系镜像的 apt/dpkg 依赖它，否则安装即失败）
                extraArgs = if (args.isEmpty() && rootfsSource is RootfsSource.LocalFile) listOf("--link2symlink") else args,
                env = env,
                isBuiltin = rootfsSource is RootfsSource.Asset,
                mode = ExecutionMode.LOCAL_PROOT
            )
        }

        ExecutionMode.REMOTE_SSH -> {
            if (selectedConnId.isBlank()) return null
            ContainerProfile(
                id = "", // 由调用方覆写
                name = name,
                rootfsSource = RootfsSource.RemoteSsh(selectedConnId, remotePath),
                shellPath = null,
                isBuiltin = false,
                mode = ExecutionMode.REMOTE_SSH
            )
        }
    }
}

/** 保存按钮可用条件：本地镜像需选了文件（编辑内置时保持内置也可保存），远程 SSH 需选了通道。 */
private fun canConfirm(
    mode: ExecutionMode,
    pickedUri: String?,
    initialAsset: RootfsSource.Asset?,
    selectedConnId: String,
    sshConnections: List<RemoteConnection>
): Boolean = when (mode) {
    ExecutionMode.LOCAL_PROOT -> pickedUri != null || initialAsset != null
    ExecutionMode.REMOTE_SSH -> sshConnections.isNotEmpty() && selectedConnId.isNotBlank()
}

/**
 * 把所选镜像文件复制一份到 App 私有目录（rootfs_images/）保存副本，返回副本 file uri；
 * 已是 file uri（私有副本或历史数据）时不复制直接返回原值；复制失败返回 null（调用方回退原 uri）。
 */
private fun copyImageToPrivate(context: Context, uriString: String, profileId: String?): String? {
    if (uriString.startsWith("file://")) return uriString
    val uri = Uri.parse(uriString)
    return runCatching {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.let { ".$it" } ?: ""
        val destDir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(destDir, "import_${profileId ?: System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Uri.fromFile(dest).toString()
    }.getOrNull()
}

/**
 * 删除 profile 时清理其私有目录镜像副本（仅清理 rootfs_images/ 下的 file uri 副本）。
 * 跳过 `download_` 前缀的副本——那是「下载镜像」页下载的镜像文件，删除容器不应连带清掉，
 * 否则下载页记录还在但文件没了；手动导入的副本（import_*）照旧清理。
 */
private fun deleteImageCopy(profile: ContainerProfile) {
    val src = profile.rootfsSource as? RootfsSource.LocalFile ?: return
    if (!src.uri.startsWith("file://")) return
    runCatching {
        val file = File(Uri.parse(src.uri).path ?: return)
        if (file.parentFile?.name == "rootfs_images" && !file.name.startsWith("download_")) file.delete()
    }
}

/** 查询所选镜像文件的显示名：content uri 用 OpenableColumns，file uri 回退路径末段。 */
private fun queryImageDisplayName(context: Context, uriString: String): String? {
    val uri = Uri.parse(uriString)
    if (uri.scheme == "content") {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }
    return uri.lastPathSegment
}

/**
 * 本文件统一的输入框样式：圆角 + 定制 colors + 全宽。
 * 历史上有 19 处整块复制，抽成此组件消除重复；需要 readOnly/placeholder/trailingIcon 的场景也走这里。
 */
/** 统一使用全局 AppTextField 组件。 */
@Composable
private fun ContainerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        readOnly = readOnly,
        trailingIcon = trailingIcon,
        modifier = modifier.fillMaxWidth()
    )
}
