package com.aicode.feature.agent.presentation.component.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.data.local.entity.GroupChatRoutineEntity
import com.aicode.feature.agent.domain.groupchat.GroupChatMember
import com.aicode.feature.agent.domain.groupchat.GroupRoutineScheduleParser
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/**
 * 房间定时任务（Routines）弹层：既有任务列表（启停/运行一次/删除）+ 新建表单。
 * 调度表达式合法性与输入校验在此完成，非法时不落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesSheet(
    members: List<GroupChatMember>,
    routines: List<GroupChatRoutineEntity>,
    onDismissRequest: () -> Unit,
    onCreate: suspend (memberKey: String, schedule: String, instruction: String) -> Boolean,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onDelete: (id: String) -> Unit,
    onRun: (id: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GroupChatRoutineEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        if (showForm) {
            RoutineCreateForm(
                members = members,
                onSaved = { memberKey, schedule, instruction ->
                    scope.launch {
                        if (onCreate(memberKey, schedule, instruction)) showForm = false
                    }
                },
                onBack = { showForm = false }
            )
        } else {
            RoutineList(
                members = members,
                routines = routines,
                onAdd = { showForm = true },
                onToggle = onToggle,
                onDelete = { id -> pendingDelete = routines.firstOrNull { it.id == id } },
                onRun = onRun
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }

    pendingDelete?.let { routine ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.group_chat_routine_delete)) },
            text = { Text(routine.instruction, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(routine.id)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
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
private fun RoutineList(
    members: List<GroupChatMember>,
    routines: List<GroupChatRoutineEntity>,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (String) -> Unit
) {
    val memberTitles = members.associate { it.memberKey to it.title.ifEmpty { it.memberKey } }
    Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.group_chat_routines),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.group_chat_routine_add),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = stringResource(R.string.group_chat_routine_min_interval_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (routines.isEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.group_chat_routine_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(routines, key = { it.id }) { routine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = FeatherIcons.Clock,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = GroupRoutineScheduleParser.describe(routine.schedule) +
                                        " · " + (memberTitles[routine.memberKey] ?: routine.memberKey),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = routine.instruction,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRun(routine.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = FeatherIcons.Play,
                                contentDescription = stringResource(R.string.group_chat_routine_run),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        AppSwitch(
                            checked = routine.enabled,
                            onCheckedChange = { onToggle(routine.id, it) },
                            modifier = Modifier.padding(horizontal = Spacing.xs)
                        )
                        IconButton(onClick = { onDelete(routine.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.group_chat_routine_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineCreateForm(
    members: List<GroupChatMember>,
    onSaved: (memberKey: String, schedule: String, instruction: String) -> Unit,
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var scheduleText by remember { mutableStateOf("") }
    var instructionText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = FeatherIcons.ArrowLeft,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
            Text(
                text = stringResource(R.string.group_chat_routine_new),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Text(
            text = stringResource(R.string.group_chat_routine_member),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        if (members.isEmpty()) {
            Text(
                text = stringResource(R.string.group_chat_no_members),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                members.forEach { member ->
                    val isSelected = selected == member.memberKey
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selected = member.memberKey }
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
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = scheduleText,
            onValueChange = { scheduleText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.group_chat_routine_schedule)) },
            placeholder = { Text(stringResource(R.string.group_chat_routine_schedule_hint)) },
            singleLine = true
        )

        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = instructionText,
            onValueChange = { instructionText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.group_chat_routine_instruction)) },
            placeholder = { Text(stringResource(R.string.group_chat_routine_instruction_hint)) },
            minLines = 2,
            maxLines = 4
        )

        error?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(Spacing.md))
        val errMemberRequired = stringResource(R.string.group_chat_routine_member_required)
        val errScheduleInvalid = stringResource(R.string.group_chat_routine_schedule_invalid)
        val errInstructionRequired = stringResource(R.string.group_chat_routine_instruction_required)
        Button(
            onClick = {
                val member = selected
                when {
                    member == null -> error = errMemberRequired
                    !GroupRoutineScheduleParser.isValid(scheduleText.trim()) ->
                        error = errScheduleInvalid
                    instructionText.isBlank() -> error = errInstructionRequired
                    else -> {
                        error = null
                        onSaved(member, scheduleText.trim(), instructionText.trim())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.group_chat_routine_save))
        }
    }
}