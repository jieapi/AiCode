package com.aicode.feature.agent.presentation.component.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.MAX_MEMBERS
import com.aicode.feature.agent.domain.groupchat.GroupChatMember
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Users

/**
 * 侧边栏「群聊」Tab：群聊房间列表 + 新建入口。
 */
@Composable
fun GroupChatListTab(
    rooms: List<ChatSession>,
    onOpenRoom: (String) -> Unit,
    members: List<GroupChatMember>,
    onCreateRoom: (name: String, memberKeys: List<String>) -> Unit,
    onDeleteRoom: (ChatSession) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (rooms.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.group_chat_no_rooms),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rooms, key = { it.id }) { room ->
                    GroupRoomRow(room = room, onClick = { onOpenRoom(room.id) }, onLongClick = { pendingDelete = room })
                }
            }
        }

        // 新建按钮（右下角 FAB）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { showCreate = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.group_chat_new_room),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }

    if (showCreate) {
        NewGroupChatDialog(
            members = members,
            onDismiss = { showCreate = false },
            onCreate = { name, memberKeys ->
                showCreate = false
                onCreateRoom(name, memberKeys)
            }
        )
    }

    // 删除房间确认
    pendingDelete?.let { room ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.group_chat_delete_room)) },
            text = { Text(stringResource(R.string.group_chat_delete_room_confirm, room.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRoom(room)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun GroupRoomRow(room: ChatSession, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupAvatar(name = room.title, size = 32.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = room.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = FeatherIcons.Users,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 新建群聊对话框：房间名 + 成员候选多选（1-MAX_MEMBERS 个；候选含 agents 目录下 .md 定义与子代理预设）。
 */
@Composable
fun NewGroupChatDialog(
    members: List<GroupChatMember>,
    onDismiss: () -> Unit,
    onCreate: (name: String, memberKeys: List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_chat_new_room)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = stringResource(R.string.group_chat_room_name),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.group_chat_pick_members, MAX_MEMBERS),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.group_chat_no_members),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(members, key = { it.memberKey }) { member ->
                            val name = member.memberKey
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Radius.md))
                                    .clickable {
                                        selected = if (name in selected) {
                                            selected - name
                                        } else {
                                            if (selected.size >= MAX_MEMBERS) selected else selected + name
                                        }
                                    }
                                    .padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = name in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            if (selected.size >= MAX_MEMBERS) selected else selected + name
                                        } else {
                                            selected - name
                                        }
                                    }
                                )
                                GroupAvatar(
                                    name = name,
                                    avatarColor = member.avatarColor,
                                    avatarShape = member.avatarShape,
                                    size = 24.dp
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = member.title.ifEmpty { name },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            val errNameRequired = stringResource(R.string.group_chat_room_name_required)
            val errMembersRequired = stringResource(R.string.group_chat_members_required)
            val errTooMany = stringResource(R.string.group_chat_room_too_many, MAX_MEMBERS)
            Button(
                onClick = {
                    when {
                        name.isBlank() -> error = errNameRequired
                        selected.isEmpty() -> error = errMembersRequired
                        selected.size > MAX_MEMBERS -> error = errTooMany
                        else -> onCreate(name.trim(), selected.toList())
                    }
                }
            ) { Text(stringResource(R.string.group_chat_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
