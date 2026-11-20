package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingSelectionBar
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SelectionActionButton
import com.aicode.core.ui.SelectionActionDangerButton
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.presentation.MoveSessionsOutcome
import com.aicode.feature.settings.presentation.SessionDetailState
import com.aicode.feature.settings.presentation.SessionManagementViewModel
import com.aicode.feature.settings.presentation.SessionMessageSearchHit
import com.aicode.feature.settings.presentation.SessionScopeFilter
import com.aicode.feature.settings.presentation.WorkspaceBucket
import com.aicode.feature.settings.presentation.WorkspaceBucketSource
import com.aicode.feature.workspace.domain.model.Workspace
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Folder
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Server
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PendingConfirmMove {
    data class CrossEnv(
        val sessionIds: Set<String>,
        val targetBucket: WorkspaceBucket
    ) : PendingConfirmMove

    data class SwitchWorkspace(
        val sessionIds: Set<String>,
        val targetBucket: WorkspaceBucket
    ) : PendingConfirmMove

    data class OpenInChat(
        val workspace: Workspace,
        val sessionId: String
    ) : PendingConfirmMove
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionManagementSectionHost(
    viewModel: SessionManagementViewModel,
    selection: ListSelectionState,
    onOpenDetail: (String) -> Unit,
    onOpenInChat: (Workspace, String) -> Unit,
    onFollowMovedSession: (Workspace, String) -> Unit,
    onStopAllAndCloseTerminal: () -> Unit,
    onSessionsMutated: () -> Unit
) {
    val allBuckets by viewModel.allBuckets.collectAsStateWithLifecycle()
    val filteredBuckets by viewModel.filteredBuckets.collectAsStateWithLifecycle()
    val scopeFilter by viewModel.scopeFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val messageHits by viewModel.messageSearchHits.collectAsStateWithLifecycle()
    val messageCounts by viewModel.messageCounts.collectAsStateWithLifecycle()
    val subSessionsByParent by viewModel.subSessionsByParent.collectAsStateWithLifecycle()
    val activeOrQueuedIds by viewModel.activeOrQueuedSessionIds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runningBlockedText = stringResource(R.string.chat_move_running_blocked)

    var pendingMoveIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingConfirmMove by remember { mutableStateOf<PendingConfirmMove?>(null) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingRenameSession by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<Pair<ChatSession, WorkspaceBucket>?>(null) }
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val sid = pendingExportSessionId
        pendingExportSessionId = null
        if (uri != null && sid != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    viewModel.exportSession(sid, os) { ok ->
                        Toast.makeText(
                            context,
                            context.getString(
                                if (ok) R.string.chat_export_session_done else R.string.chat_export_session_failed
                            ),
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_export_session_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val allRootSessions = remember(allBuckets) {
        allBuckets.flatMap { it.sessions }.associateBy { it.id }
    }
    val visibleSelectableIds = remember(filteredBuckets) {
        filteredBuckets.flatMap { bucket -> bucket.sessions.map { it.id } }
    }

    LaunchedEffect(visibleSelectableIds, selection.isActive) {
        selection.retainOnly(visibleSelectableIds.toSet())
    }

    fun handleMoveOutcome(outcome: MoveSessionsOutcome, targetBucket: WorkspaceBucket) {
        if (outcome.movedCount == 0 && outcome.skippedRunningCount > 0) {
            Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            return
        }
        if (outcome.movedCount == 0) return
        onSessionsMutated()
        val followedWs = outcome.followedWorkspace
        val followedSid = outcome.followedSessionId
        if (followedWs != null && followedSid != null) {
            onFollowMovedSession(followedWs, followedSid)
        }
        val msg = when {
            followedWs != null ->
                context.getString(R.string.chat_move_done_followed, followedWs.name)
            outcome.skippedRunningCount > 0 ->
                context.getString(
                    R.string.chat_move_done_partial,
                    outcome.movedCount,
                    targetBucket.name,
                    outcome.skippedRunningCount
                )
            outcome.movedCount == 1 ->
                context.getString(R.string.chat_move_done_single, targetBucket.name)
            else ->
                context.getString(R.string.chat_move_done_multiple, outcome.movedCount, targetBucket.name)
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun executeMove(ids: Set<String>, targetBucket: WorkspaceBucket, stopRunningFirst: Boolean) {
        if (stopRunningFirst) onStopAllAndCloseTerminal()
        viewModel.moveSessions(ids, targetBucket) { outcome ->
            handleMoveOutcome(outcome, targetBucket)
        }
    }

    fun requestMove(sessionIds: Set<String>, targetBucket: WorkspaceBucket) {
        val movable = sessionIds.filter { sid ->
            val s = allRootSessions[sid]
            s != null && s.workspacePath != targetBucket.path
        }.toSet()
        if (movable.isEmpty()) return
        val idle = movable.filterNot { it in activeOrQueuedIds }.toSet()
        if (idle.isEmpty()) {
            Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            return
        }
        val sourcePaths = idle.mapNotNull { allRootSessions[it]?.workspacePath }.toSet()
        if (viewModel.isCrossEnvironmentMove(sourcePaths, targetBucket)) {
            pendingConfirmMove = PendingConfirmMove.CrossEnv(idle, targetBucket)
            return
        }
        if (viewModel.willMoveTriggerCurrentFollow(idle, targetBucket) && viewModel.hasRunningSessionsInCurrentWorkspace()) {
            pendingConfirmMove = PendingConfirmMove.SwitchWorkspace(idle, targetBucket)
            return
        }
        executeMove(idle, targetBucket, stopRunningFirst = false)
    }

    SessionManagementContent(
        allBuckets = allBuckets,
        filteredBuckets = filteredBuckets,
        scopeFilter = scopeFilter,
        onScopeFilterChange = viewModel::setScopeFilter,
        searchQuery = searchQuery,
        onSearchQueryChange = viewModel::updateSearchQuery,
        messageHits = messageHits,
        messageCounts = messageCounts,
        subSessionsByParent = subSessionsByParent,
        activeOrQueuedIds = activeOrQueuedIds,
        selection = selection,
        onOpenDetail = { sessionId ->
            viewModel.openSessionDetail(sessionId)
            onOpenDetail(sessionId)
        },
        onLongClickSession = { session, bucket ->
            menuSession = session to bucket
        },
        onBatchMove = { pendingMoveIds = selection.selectedKeys.toSet() },
        onBatchDelete = {
            val deletable = selection.selectedKeys.filterNot { it in activeOrQueuedIds }.toSet()
            if (deletable.isEmpty()) {
                Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            } else {
                pendingDeleteIds = deletable
            }
        }
    )

    menuSession?.let { (session, bucket) ->
        ManagedSessionActionSheet(
            session = session,
            bucket = bucket,
            isBusy = session.id in activeOrQueuedIds,
            canMove = allBuckets.size > 1,
            onViewContent = {
                menuSession = null
                viewModel.openSessionDetail(session.id)
                onOpenDetail(session.id)
            },
            onOpenInChat = if (bucket.workspace != null && bucket.isAvailable) {
                {
                    val targetWs = bucket.workspace
                    menuSession = null
                    if (viewModel.needsSwitchConfirmation(targetWs)) {
                        pendingConfirmMove = PendingConfirmMove.OpenInChat(targetWs, session.id)
                    } else {
                        onOpenInChat(targetWs, session.id)
                    }
                }
            } else null,
            onMoveToWorkspace = {
                menuSession = null
                if (session.id in activeOrQueuedIds) {
                    Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
                } else {
                    pendingMoveIds = setOf(session.id)
                }
            },
            onTogglePin = {
                menuSession = null
                viewModel.togglePinSession(session)
            },
            onRename = {
                menuSession = null
                pendingRenameSession = session
            },
            onExport = {
                menuSession = null
                pendingExportSessionId = session.id
                val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
                exportLauncher.launch("aicode-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
            },
            onBatchSelect = {
                menuSession = null
                selection.enterWith(session.id)
            },
            onDelete = {
                menuSession = null
                if (session.id in activeOrQueuedIds) {
                    Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
                } else {
                    pendingDeleteIds = setOf(session.id)
                }
            },
            onDismiss = { menuSession = null }
        )
    }

    pendingMoveIds?.let { ids ->
        val sourcePaths = ids.mapNotNull { allRootSessions[it]?.workspacePath }.toSet()
        TargetWorkspacePickerSheet(
            buckets = allBuckets,
            disabledPaths = if (sourcePaths.size == 1) sourcePaths else emptySet(),
            onSelectBucket = { targetBucket ->
                pendingMoveIds = null
                if (selection.isActive) selection.exit()
                requestMove(ids, targetBucket)
            },
            onDismiss = { pendingMoveIds = null }
        )
    }

    pendingConfirmMove?.let { confirm ->
        when (confirm) {
            is PendingConfirmMove.CrossEnv -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.session_mgmt_cross_env_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.session_mgmt_cross_env_confirm,
                            confirm.sessionIds.size,
                            confirm.targetBucket.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        if (viewModel.willMoveTriggerCurrentFollow(confirm.sessionIds, confirm.targetBucket) &&
                            viewModel.hasRunningSessionsInCurrentWorkspace()
                        ) {
                            pendingConfirmMove = PendingConfirmMove.SwitchWorkspace(confirm.sessionIds, confirm.targetBucket)
                        } else {
                            executeMove(confirm.sessionIds, confirm.targetBucket, stopRunningFirst = false)
                        }
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
            is PendingConfirmMove.SwitchWorkspace -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.workspace_switch)) },
                text = { Text(stringResource(R.string.workspace_switch_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        executeMove(confirm.sessionIds, confirm.targetBucket, stopRunningFirst = true)
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
            is PendingConfirmMove.OpenInChat -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.workspace_switch)) },
                text = { Text(stringResource(R.string.workspace_switch_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        onStopAllAndCloseTerminal()
                        onOpenInChat(confirm.workspace, confirm.sessionId)
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }

    pendingDeleteIds?.let { ids ->
        val title = if (ids.size == 1) {
            allRootSessions[ids.first()]?.title.orEmpty()
        } else ""
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null },
            title = {
                Text(
                    stringResource(
                        if (ids.size == 1) R.string.chat_delete_session else R.string.chat_batch_delete_title
                    )
                )
            },
            text = {
                Text(
                    if (ids.size == 1) {
                        stringResource(R.string.chat_delete_session_confirm, title)
                    } else {
                        stringResource(R.string.chat_batch_delete_confirm, ids.size)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteIds = null
                    if (selection.isActive) selection.exit()
                    viewModel.deleteSessions(ids) {
                        onSessionsMutated()
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIds = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingRenameSession?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRenameSession = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameSession(session.id, renameText)
                        pendingRenameSession = null
                    },
                    enabled = renameText.isNotBlank() && renameText.trim() != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameSession = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SessionManagementContent(
    allBuckets: List<WorkspaceBucket>,
    filteredBuckets: List<WorkspaceBucket>,
    scopeFilter: SessionScopeFilter,
    onScopeFilterChange: (SessionScopeFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    messageHits: List<SessionMessageSearchHit>,
    messageCounts: Map<String, Int>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    activeOrQueuedIds: Set<String>,
    selection: ListSelectionState,
    onOpenDetail: (String) -> Unit,
    onLongClickSession: (ChatSession, WorkspaceBucket) -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit
) {
    var collapsedPaths by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
    ) {
        Spacer(Modifier.height(Spacing.xs))
        ModelSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = stringResource(R.string.session_mgmt_search_hint),
            trailing = if (searchQuery.isEmpty()) null else {
                {
                    IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.chat_search_clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        Spacer(Modifier.height(Spacing.sm))
        SegmentedTabs(
            selected = scopeFilter.ordinal,
            labels = listOf(
                stringResource(R.string.session_mgmt_filter_all),
                stringResource(R.string.session_mgmt_filter_current_env),
                stringResource(R.string.session_mgmt_filter_remote_offline)
            ),
            onSelect = { index ->
                onScopeFilterChange(SessionScopeFilter.entries[index])
            }
        )
        Spacer(Modifier.height(Spacing.xs))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val hasResults = filteredBuckets.isNotEmpty() || (searchQuery.isNotBlank() && messageHits.isNotEmpty())
            if (!hasResults) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (searchQuery.isNotBlank()) R.string.chat_search_no_result
                            else R.string.session_mgmt_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = Spacing.xs,
                        bottom = if (selection.isActive) 88.dp else Spacing.xl
                    )
                ) {
                    filteredBuckets.forEach { bucket ->
                        val expanded = bucket.path !in collapsedPaths
                        item(key = "mgmt-hdr-${bucket.path}", contentType = "bucket-header") {
                            WorkspaceBucketHeader(
                                bucket = bucket,
                                expanded = expanded,
                                onToggleExpand = {
                                    collapsedPaths = if (expanded) collapsedPaths + bucket.path else collapsedPaths - bucket.path
                                }
                            )
                        }

                        if (expanded) {
                            if (bucket.sessions.isEmpty()) {
                                item(key = "mgmt-empty-${bucket.path}", contentType = "bucket-empty") {
                                    Surface(
                                        shape = RoundedCornerShape(Radius.mdLarge),
                                        color = MaterialTheme.semanticColors.cardSurface.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.chat_no_sessions_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = bucket.sessions,
                                    key = { _, s -> s.id },
                                    contentType = { _, _ -> "session" }
                                ) { _, session ->
                                    val isBusy = session.id in activeOrQueuedIds
                                    val subCount = subSessionsByParent[session.id]?.size ?: 0
                                    val msgCount = messageCounts[session.id] ?: 0
                                    Box(modifier = Modifier.padding(top = Spacing.xs)) {
                                        ManagedSessionCard(
                                            session = session,
                                            messageCount = msgCount,
                                            subAgentCount = subCount,
                                            isBusy = isBusy,
                                            selectionMode = selection.isActive,
                                            checked = selection.isSelected(session.id),
                                            onClick = {
                                                if (selection.isActive) selection.toggle(session.id)
                                                else onOpenDetail(session.id)
                                            },
                                            onLongClick = {
                                                if (selection.isActive) selection.toggle(session.id)
                                                else onLongClickSession(session, bucket)
                                            },
                                            onCheckedChange = { selection.toggle(session.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (searchQuery.isNotBlank() && messageHits.isNotEmpty()) {
                        item(key = "msg-hits-header") {
                            SettingsGroupHeader(text = stringResource(R.string.session_mgmt_message_hits_header))
                        }
                        items(messageHits, key = { "hit-${it.messageId}" }) { hit ->
                            MessageSearchHitCard(
                                hit = hit,
                                query = searchQuery,
                                onClick = { onOpenDetail(hit.sessionId) }
                            )
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                }
            }

            val selectableSet = remember(filteredBuckets, activeOrQueuedIds) {
                filteredBuckets.flatMap { it.sessions }.filterNot { it.id in activeOrQueuedIds }.map { it.id }
            }
            val allSelected = selectableSet.isNotEmpty() && selectableSet.all { selection.isSelected(it) }
            FloatingSelectionBar(
                visible = selection.isActive,
                selectedCount = selection.selectedCount,
                allSelected = allSelected,
                onToggleSelectAll = {
                    if (allSelected) selection.clear() else selection.replaceAll(selectableSet)
                },
                onExit = { selection.exit() },
                showCloseButton = true,
                selectedCountText = stringResource(R.string.chat_sessions_selected_count, selection.selectedCount),
                selectAllText = stringResource(
                    if (allSelected) R.string.provider_models_deselect_all else R.string.provider_models_select_all
                ),
                outerPadding = PaddingValues(bottom = Spacing.xs),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (allBuckets.size > 1) {
                    SelectionActionButton(
                        text = stringResource(R.string.chat_batch_move),
                        enabled = selection.selectedCount > 0,
                        onClick = onBatchMove
                    )
                }
                SelectionActionDangerButton(
                    text = stringResource(R.string.common_batch_delete),
                    enabled = selection.selectedCount > 0,
                    onClick = onBatchDelete
                )
            }
        }
    }
}

@Composable
private fun WorkspaceBucketHeader(
    bucket: WorkspaceBucket,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.mdLarge)
    val bgColor = if (bucket.isCurrent) MaterialTheme.semanticColors.mutedSurface else Color.Transparent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = 2.dp)
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = iconForBucketSource(bucket.source),
                contentDescription = null,
                tint = if (bucket.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = bucket.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (bucket.isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.xs))
            SourceTagChip(bucket = bucket)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = bucket.sessions.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = bucket.path,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, top = 2.dp)
        )
    }
}

@Composable
private fun SourceTagChip(bucket: WorkspaceBucket) {
    val label = when {
        bucket.isCurrent -> stringResource(R.string.chat_drawer_current_workspace_badge)
        bucket.source == WorkspaceBucketSource.CURRENT_REMOTE -> stringResource(R.string.session_mgmt_tag_remote_connected)
        bucket.source == WorkspaceBucketSource.CURRENT_EXTERNAL -> stringResource(R.string.session_mgmt_tag_external)
        bucket.source == WorkspaceBucketSource.CURRENT_INTERNAL -> stringResource(R.string.session_mgmt_tag_local)
        bucket.source == WorkspaceBucketSource.LOCAL_OFFLINE -> stringResource(R.string.session_mgmt_tag_local_offline)
        bucket.source == WorkspaceBucketSource.EXTERNAL_OFFLINE -> stringResource(R.string.session_mgmt_tag_external_offline)
        else -> stringResource(R.string.session_mgmt_tag_remote_offline)
    }
    val containerColor = when {
        bucket.isCurrent -> MaterialTheme.colorScheme.primaryContainer
        bucket.source.isRemoteLike -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.semanticColors.capsuleSurface
    }
    val contentColor = when {
        bucket.isCurrent -> MaterialTheme.colorScheme.primary
        bucket.source.isRemoteLike -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = containerColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagedSessionCard(
    session: ChatSession,
    messageCount: Int,
    subAgentCount: Int,
    isBusy: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = if (session.isPinned) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.semanticColors.cardSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Checkbox(
                        checked = checked,
                        enabled = !isBusy,
                        onCheckedChange = { onCheckedChange() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            if (isBusy) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.semanticColors.success)
                )
                Spacer(Modifier.width(Spacing.sm))
            } else if (session.isPinned) {
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    append(stringResource(R.string.session_mgmt_msg_count, messageCount))
                    if (subAgentCount > 0) {
                        append(" · ")
                        append(stringResource(R.string.session_mgmt_subagent_count, subAgentCount))
                    }
                    append(" · ")
                    append(dateFormat.format(Date(session.updatedAt)))
                    if (isBusy) {
                        append(" · ")
                        append(stringResource(R.string.session_mgmt_running_tag))
                    }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = if (isBusy) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MessageSearchHitCard(
    hit: SessionMessageSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = hit.workspaceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp)
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = hit.sessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = highlightSearchSnippet(
                    snippet = hit.snippet,
                    query = query,
                    highlight = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun highlightSearchSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@buildAnnotatedString
        var start = 0
        while (true) {
            val index = snippet.indexOf(trimmed, start, ignoreCase = true)
            if (index < 0) break
            addStyle(highlight, index, index + trimmed.length)
            start = index + trimmed.length
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagedSessionActionSheet(
    session: ChatSession,
    bucket: WorkspaceBucket,
    isBusy: Boolean,
    canMove: Boolean,
    onViewContent: () -> Unit,
    onOpenInChat: (() -> Unit)?,
    onMoveToWorkspace: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = 2.dp)
            )
            Text(
                text = bucket.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            ManagedSheetActionRow(
                icon = FeatherIcons.Eye,
                label = stringResource(R.string.session_mgmt_view_content),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onViewContent
            )
            if (onOpenInChat != null) {
                ManagedSheetActionRow(
                    icon = FeatherIcons.ExternalLink,
                    label = stringResource(R.string.session_mgmt_open_in_chat),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onOpenInChat
                )
            }
            if (canMove) {
                ManagedSheetActionRow(
                    icon = FeatherIcons.Folder,
                    label = stringResource(R.string.chat_move_to_workspace),
                    tint = if (isBusy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    onClick = onMoveToWorkspace
                )
            }
            ManagedSheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onTogglePin
            )
            ManagedSheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onRename
            )
            ManagedSheetActionRow(
                icon = FeatherIcons.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onExport
            )
            ManagedSheetActionRow(
                icon = FeatherIcons.ArrowRight,
                label = stringResource(R.string.chat_batch_select),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBatchSelect
            )
            ManagedSheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun ManagedSheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(Spacing.lg))
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetWorkspacePickerSheet(
    buckets: List<WorkspaceBucket>,
    disabledPaths: Set<String>,
    onSelectBucket: (WorkspaceBucket) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
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
            Text(
                text = stringResource(R.string.chat_move_workspace_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items(buckets, key = { it.path }) { bucket ->
                    val isSource = bucket.path in disabledPaths
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable(enabled = !isSource) { onSelectBucket(bucket) }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = iconForBucketSource(bucket.source),
                            contentDescription = null,
                            tint = if (isSource) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                Text(
                                    text = bucket.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSource) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                SourceTagChip(bucket = bucket)
                            }
                            Text(
                                text = bucket.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isSource) 0.5f else 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSource) {
                            Spacer(Modifier.width(Spacing.xs))
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 会话内容详情页：展示本地/远程会话的完整聊天记录，支持只读查看、复制、切换打开及移动到其他工作区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDetailSectionHost(
    viewModel: SessionManagementViewModel,
    onOpenInChat: (Workspace, String) -> Unit,
    onFollowMovedSession: (Workspace, String) -> Unit,
    onStopAllAndCloseTerminal: () -> Unit,
    onSessionsMutated: () -> Unit,
    onDeletedNavigateBack: () -> Unit
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val allBuckets by viewModel.allBuckets.collectAsStateWithLifecycle()
    val activeOrQueuedIds by viewModel.activeOrQueuedSessionIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runningBlockedText = stringResource(R.string.chat_move_running_blocked)

    var showMoveSheet by remember { mutableStateOf(false) }
    var pendingConfirmMove by remember { mutableStateOf<PendingConfirmMove?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val sid = pendingExportId
        pendingExportId = null
        if (uri != null && sid != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    viewModel.exportSession(sid, os) { ok ->
                        Toast.makeText(
                            context,
                            context.getString(
                                if (ok) R.string.chat_export_session_done else R.string.chat_export_session_failed
                            ),
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    val session = detailState.session
    val bucket = detailState.bucket
    if (session == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.session_mgmt_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val isBusy = session.id in activeOrQueuedIds

    fun executeMove(targetBucket: WorkspaceBucket, stopRunningFirst: Boolean) {
        if (stopRunningFirst) onStopAllAndCloseTerminal()
        viewModel.moveSessions(setOf(session.id), targetBucket) { outcome ->
            if (outcome.movedCount == 0 && outcome.skippedRunningCount > 0) {
                Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            } else if (outcome.movedCount > 0) {
                onSessionsMutated()
                val followedWs = outcome.followedWorkspace
                val followedSid = outcome.followedSessionId
                if (followedWs != null && followedSid != null) {
                    onFollowMovedSession(followedWs, followedSid)
                }
                Toast.makeText(
                    context,
                    if (followedWs != null) context.getString(R.string.chat_move_done_followed, followedWs.name)
                    else context.getString(R.string.chat_move_done_single, targetBucket.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    SessionDetailContent(
        state = detailState,
        isBusy = isBusy,
        canMove = allBuckets.size > 1,
        onOpenInChat = if (bucket?.workspace != null && bucket.isAvailable) {
            {
                if (viewModel.needsSwitchConfirmation(bucket.workspace)) {
                    pendingConfirmMove = PendingConfirmMove.OpenInChat(bucket.workspace, session.id)
                } else {
                    onOpenInChat(bucket.workspace, session.id)
                }
            }
        } else null,
        onMove = {
            if (isBusy) Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            else showMoveSheet = true
        },
        onRename = { showRenameDialog = true },
        onExport = {
            pendingExportId = session.id
            val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
            exportLauncher.launch("aicode-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
        },
        onDelete = {
            if (isBusy) Toast.makeText(context, runningBlockedText, Toast.LENGTH_SHORT).show()
            else showDeleteConfirm = true
        },
        onCopyMessage = { text ->
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("message", text))
            Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
        }
    )

    if (showMoveSheet) {
        TargetWorkspacePickerSheet(
            buckets = allBuckets,
            disabledPaths = setOf(session.workspacePath),
            onSelectBucket = { targetBucket ->
                showMoveSheet = false
                if (viewModel.isCrossEnvironmentMove(setOf(session.workspacePath), targetBucket)) {
                    pendingConfirmMove = PendingConfirmMove.CrossEnv(setOf(session.id), targetBucket)
                } else if (viewModel.willMoveTriggerCurrentFollow(setOf(session.id), targetBucket) &&
                    viewModel.hasRunningSessionsInCurrentWorkspace()
                ) {
                    pendingConfirmMove = PendingConfirmMove.SwitchWorkspace(setOf(session.id), targetBucket)
                } else {
                    executeMove(targetBucket, stopRunningFirst = false)
                }
            },
            onDismiss = { showMoveSheet = false }
        )
    }

    pendingConfirmMove?.let { confirm ->
        when (confirm) {
            is PendingConfirmMove.CrossEnv -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.session_mgmt_cross_env_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.session_mgmt_cross_env_confirm,
                            1,
                            confirm.targetBucket.name
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        if (viewModel.willMoveTriggerCurrentFollow(setOf(session.id), confirm.targetBucket) &&
                            viewModel.hasRunningSessionsInCurrentWorkspace()
                        ) {
                            pendingConfirmMove = PendingConfirmMove.SwitchWorkspace(setOf(session.id), confirm.targetBucket)
                        } else {
                            executeMove(confirm.targetBucket, stopRunningFirst = false)
                        }
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
            is PendingConfirmMove.SwitchWorkspace -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.workspace_switch)) },
                text = { Text(stringResource(R.string.workspace_switch_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        executeMove(confirm.targetBucket, stopRunningFirst = true)
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
            is PendingConfirmMove.OpenInChat -> AlertDialog(
                onDismissRequest = { pendingConfirmMove = null },
                title = { Text(stringResource(R.string.workspace_switch)) },
                text = { Text(stringResource(R.string.workspace_switch_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingConfirmMove = null
                        onStopAllAndCloseTerminal()
                        onOpenInChat(confirm.workspace, confirm.sessionId)
                    }) { Text(stringResource(R.string.workspace_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirmMove = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSessions(setOf(session.id)) {
                        onSessionsMutated()
                        onDeletedNavigateBack()
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameSession(session.id, renameText)
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank() && renameText.trim() != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SessionDetailContent(
    state: SessionDetailState,
    isBusy: Boolean,
    canMove: Boolean,
    onOpenInChat: (() -> Unit)?,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onCopyMessage: (String) -> Unit
) {
    val session = state.session ?: return
    val bucket = state.bucket
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "meta-card") {
                Surface(
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.semanticColors.cardSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (bucket != null) {
                                SourceTagChip(bucket = bucket)
                            }
                        }
                        Text(
                            text = session.workspacePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val metaLine = buildString {
                            append(stringResource(R.string.session_mgmt_msg_count, state.messages.size))
                            append(" · ")
                            append(dateFormat.format(Date(session.updatedAt)))
                            if (!session.model.isNullOrBlank()) {
                                append(" · ")
                                append(session.model)
                            }
                            if (isBusy) {
                                append(" · ")
                                append(stringResource(R.string.session_mgmt_running_tag))
                            }
                        }
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            if (onOpenInChat != null) {
                                item {
                                    DetailActionPill(
                                        icon = FeatherIcons.ExternalLink,
                                        label = stringResource(R.string.session_mgmt_open_in_chat),
                                        emphasized = true,
                                        onClick = onOpenInChat
                                    )
                                }
                            }
                            if (canMove) {
                                item {
                                    DetailActionPill(
                                        icon = FeatherIcons.Folder,
                                        label = stringResource(R.string.chat_move_to_workspace),
                                        onClick = onMove
                                    )
                                }
                            }
                            item {
                                DetailActionPill(
                                    icon = FeatherIcons.Edit2,
                                    label = stringResource(R.string.common_rename),
                                    onClick = onRename
                                )
                            }
                            item {
                                DetailActionPill(
                                    icon = FeatherIcons.Download,
                                    label = stringResource(R.string.chat_export_session),
                                    onClick = onExport
                                )
                            }
                            item {
                                DetailActionPill(
                                    icon = FeatherIcons.Trash2,
                                    label = stringResource(R.string.common_delete),
                                    danger = true,
                                    onClick = onDelete
                                )
                            }
                        }
                        if (onOpenInChat == null) {
                            Text(
                                text = stringResource(R.string.session_mgmt_offline_readonly_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.messages.isEmpty()) {
                item(key = "empty-msgs") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.session_mgmt_empty_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(state.messages, key = { it.id }) { msg ->
                    SessionDetailMessageCard(
                        message = msg,
                        onCopy = { onCopyMessage(msg.content) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailActionPill(
    icon: ImageVector,
    label: String,
    emphasized: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = when {
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        danger -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.semanticColors.capsuleSurface
    }
    val fgColor = when {
        emphasized -> MaterialTheme.colorScheme.primary
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fgColor, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SessionDetailMessageCard(
    message: AgentUIMessage,
    onCopy: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var reasoningExpanded by remember { mutableStateOf(false) }

    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.session_mgmt_role_user)
        MessageRole.ASSISTANT -> stringResource(R.string.session_mgmt_role_assistant)
        MessageRole.TOOL -> message.toolName?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.session_mgmt_role_tool)
    }
    val cardColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        MessageRole.TOOL -> MaterialTheme.semanticColors.mutedSurface
        else -> MaterialTheme.semanticColors.cardSurface
    }
    val roleColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.TOOL -> if (message.isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = cardColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = roleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.content.isNotBlank()) {
                    Spacer(Modifier.width(Spacing.xs))
                    IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = FeatherIcons.Copy,
                            contentDescription = stringResource(R.string.common_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
            if (!message.toolArgs.isNullOrBlank()) {
                Text(
                    text = message.toolArgs,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!message.reasoning.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.sm))
                        .clickable { reasoningExpanded = !reasoningExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (reasoningExpanded) R.string.session_mgmt_hide_reasoning
                            else R.string.session_mgmt_show_reasoning
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedVisibility(
                    visible = reasoningExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Text(
                        text = message.reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = if (message.role == MessageRole.TOOL) {
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (message.role == MessageRole.TOOL) 12 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun iconForBucketSource(source: WorkspaceBucketSource): ImageVector = when (source) {
    WorkspaceBucketSource.CURRENT_REMOTE,
    WorkspaceBucketSource.REMOTE_OR_OTHER -> FeatherIcons.Server
    WorkspaceBucketSource.CURRENT_EXTERNAL,
    WorkspaceBucketSource.EXTERNAL_OFFLINE -> FeatherIcons.HardDrive
    WorkspaceBucketSource.CURRENT_INTERNAL,
    WorkspaceBucketSource.LOCAL_OFFLINE -> FeatherIcons.Folder
}
