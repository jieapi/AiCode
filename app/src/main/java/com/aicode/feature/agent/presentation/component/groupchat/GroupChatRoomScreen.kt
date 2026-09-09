package com.aicode.feature.agent.presentation.component.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.GroupChatViewModel
import com.aicode.feature.agent.presentation.GroupRoomUiState
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.component.AgentMessageItem
import com.aicode.feature.agent.domain.groupchat.GroupChatMember
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.settings.presentation.component.McpPill
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.AtSign
import compose.icons.feathericons.Clock
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.StopCircle
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users

/**
 * 群聊房间全屏页：成员状态条 + 房间消息流（复用 AgentMessageItem）+ 输入栏。
 * 消息渲染与普通聊天一致；成员发言（USER+senderName）由 AgentMessageItem 渲染为成员气泡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatRoomScreen(
    roomId: String,
    viewModel: GroupChatViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val routines by viewModel.routinesFlow.collectAsStateWithLifecycle()
    val availableMembers by viewModel.availableMembers.collectAsStateWithLifecycle()
    val memberModes by viewModel.memberModesFlow.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    var showRoutines by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(roomId) {
        viewModel.openRoom(roomId)
    }

    // 新消息自动滚底
    LaunchedEffect(state?.messages?.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state?.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RoomStatusLine(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showRoutines = true }) {
                        Icon(
                            imageVector = FeatherIcons.Clock,
                            contentDescription = stringResource(R.string.group_chat_routines),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.stopRoom() }) {
                        Icon(
                            imageVector = FeatherIcons.StopCircle,
                            contentDescription = stringResource(R.string.group_chat_stop_room),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val roomState = state!!
                Column(modifier = Modifier.fillMaxSize()) {
                    // 成员条
                    MemberStrip(roomState, viewModel, memberModes, onManage = { showMembers = true })

                    // @user 请求介入提示（成员发言 @ 了用户）
                    if (roomState.needsUser) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = Spacing.lg, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = FeatherIcons.HelpCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = stringResource(R.string.group_chat_needs_user),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // 消息流
                    Box(modifier = Modifier.weight(1f)) {
                        if (roomState.messages.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.chat_no_sessions_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = Spacing.lg,
                                    end = Spacing.lg,
                                    top = Spacing.md,
                                    bottom = Spacing.lg
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                items(roomState.messages, key = { it.id }) { msg ->
                                    AgentMessageItem(
                                        message = AgentUIMessage(
                                            id = msg.id,
                                            role = MessageRole.USER,
                                            content = msg.text,
                                            timestamp = msg.at,
                                            senderName = if (msg.kind == "member") msg.sender else null,
                                            reasoning = if (msg.kind == "member") msg.reasoning else null
                                        )
                                    )
                                }
                            }
                        }
                    }

        // 输入栏
                    RoomInputBar(
                        members = roomState.members,
                        value = inputText,
                        onValueChange = { inputText = it },
                        onSend = {
                            val text = inputText.trim()
                            if (text.isNotEmpty()) {
                                viewModel.sendMessage(text)
                                inputText = ""
                            }
                        }
                    )
                }
            }
        }
    }

    if (showRoutines && state != null) {
        RoutinesSheet(
            members = state!!.members,
            routines = routines,
            onDismissRequest = { showRoutines = false },
            onCreate = { memberKey, schedule, instruction ->
                viewModel.createRoutine(memberKey, schedule, instruction)
            },
            onToggle = { id, enabled -> viewModel.setRoutineEnabled(id, enabled) },
            onDelete = { id -> viewModel.deleteRoutine(id) },
            onRun = { id -> viewModel.runRoutine(id) }
        )
    }

    if (showMembers && state != null) {
        MembersSheet(
            currentMembers = state!!.members,
            candidates = availableMembers,
            onAdd = { keys -> viewModel.addMembers(keys) },
            onRemove = { key -> viewModel.removeMember(key) },
            onDismissRequest = { showMembers = false }
        )
    }
}

/** 房间状态行：正在思考 / 已结束。 */
@Composable
private fun RoomStatusLine(state: GroupRoomUiState?) {
    val turn = state?.turn
    if (turn != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.group_chat_thinking) + " $turn",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Text(
            text = stringResource(R.string.group_chat_room_settled),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 成员条：头像 + 名（被 hold 的标「已暂停」，可点恢复；右侧「全部恢复」+ 管理入口）。
 * 成员为 AUTO/PLAN 预设时名旁显示模式徽章（与普通聊天模式按钮同色）。
 */
@Composable
private fun MemberStrip(
    state: GroupRoomUiState,
    viewModel: GroupChatViewModel,
    memberModes: Map<String, AgentMode>,
    onManage: () -> Unit
) {
    if (state.members.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.members.forEach { member ->
            val held = member.memberKey in state.holds
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (held) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { viewModel.releaseMember(member.memberKey) }
                    .padding(horizontal = Spacing.sm, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupAvatar(
                    name = member.memberKey,
                    avatarColor = member.avatarColor,
                    avatarShape = member.avatarShape,
                    size = 18.dp
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = if (held) {
                        stringResource(R.string.group_chat_member_held) + " " + member.title.ifEmpty { member.memberKey }
                    } else {
                        member.title.ifEmpty { member.memberKey }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (held) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                val mode = memberModes[member.memberKey]
                if (mode != null && !held) {
                    Spacer(Modifier.width(Spacing.xs))
                    McpPill(
                        text = mode.name,
                        textColor = if (mode == AgentMode.AUTO) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        backgroundColor = if (mode == AgentMode.AUTO) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }
            Spacer(Modifier.width(Spacing.xs))
        }
        if (state.holds.isNotEmpty()) {
            OutlinedButton(
                onClick = { viewModel.releaseAll() },
                modifier = Modifier.height(30.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.sm)
            ) {
                Icon(FeatherIcons.Play, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.group_chat_release_all),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.width(Spacing.xs))
        }
        // 管理成员入口
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onManage)
                .padding(horizontal = Spacing.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Users,
                contentDescription = stringResource(R.string.group_chat_manage_members),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.group_chat_manage_members),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 房间输入栏：成员选择器（@ 快捷插入）+ 文本 + 发送。
 *
 * 使用 imePadding() 让栏位随键盘上升；navigationBarsPadding() 避免被底部导航栏遮挡。
 */
@Composable
private fun RoomInputBar(
    members: List<GroupChatMember>,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = 4.dp)
    ) {
        if (showPicker && members.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                members.forEach { member ->
                    val handle = member.handle.ifEmpty { member.memberKey }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                val spacer = when {
                                    value.isBlank() -> ""
                                    value.endsWith(" ") || value.endsWith("@") -> ""
                                    else -> " "
                                }
                                onValueChange(value + spacer + "@$handle ")
                            }
                            .padding(horizontal = Spacing.sm, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(
                            name = member.memberKey,
                            avatarColor = member.avatarColor,
                            avatarShape = member.avatarShape,
                            size = 16.dp
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = member.title.ifEmpty { member.memberKey },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showPicker = !showPicker }) {
                Icon(
                    imageVector = FeatherIcons.AtSign,
                    contentDescription = stringResource(R.string.group_chat_pick_member_hint),
                    tint = if (showPicker) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.group_chat_input_hint)) },
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
            Spacer(Modifier.width(Spacing.sm))
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    imageVector = FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 成员管理弹层：当前成员（可移除）+ 可添加候选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(
    currentMembers: List<GroupChatMember>,
    candidates: List<GroupChatMember>,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addable = candidates.filter { c -> currentMembers.none { it.memberKey == c.memberKey } }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.group_chat_manage_members),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            if (currentMembers.isEmpty()) {
                Text(
                    text = stringResource(R.string.group_chat_no_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                )
            } else {
                Text(
                    text = stringResource(R.string.group_chat_members_current),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.xs))
                currentMembers.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(
                            name = member.memberKey,
                            avatarColor = member.avatarColor,
                            avatarShape = member.avatarShape,
                            size = 22.dp
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = member.title.ifEmpty { member.memberKey },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemove(member.memberKey) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.group_chat_member_remove),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.group_chat_members_addable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            if (addable.isEmpty()) {
                Text(
                    text = stringResource(R.string.group_chat_no_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                )
            } else {
                addable.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .clickable { onAdd(listOf(candidate.memberKey)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(
                            name = candidate.memberKey,
                            avatarColor = candidate.avatarColor,
                            avatarShape = candidate.avatarShape,
                            size = 22.dp
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = candidate.title.ifEmpty { candidate.memberKey },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = FeatherIcons.Plus,
                            contentDescription = stringResource(R.string.group_chat_member_add),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}
