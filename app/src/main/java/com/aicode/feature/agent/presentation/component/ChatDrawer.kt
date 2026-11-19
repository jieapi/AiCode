package com.aicode.feature.agent.presentation.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.FloatingSelectionBar
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SelectionActionDangerButton
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.core.ui.rememberSaveableListSelectionState
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.ExternalSessionUiMapper
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.plugin.domain.session.ExternalSessionGroup
import com.aicode.feature.plugin.domain.session.ExternalSessionSummary
import com.aicode.feature.agent.presentation.ChatSearchHit
import com.aicode.feature.agent.presentation.ChatSearchState
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.presentation.pluginPageIcon
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.ui.containerOsLogo
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Server
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import compose.icons.feathericons.UploadCloud
import compose.icons.feathericons.X
import androidx.compose.ui.res.stringResource
import com.aicode.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
private fun DrawerProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth
) {
    if (chatAnimationsEnabled()) {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else {
        CircularProgressIndicator(
            progress = { 0.75f },
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth
        )
    }
}

/**
 * 侧边栏内容：顶部 Tab 切换「会话」/「文件」，底部「设置」入口卡片。
 * Tab0 为根会话列表，带子代理的会话行可就地展开；Tab1 为当前工作区的文件树。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    externalSessionGroups: List<ExternalSessionGroup> = emptyList(),
    currentExternalSessionId: String? = null,
    onSelectExternalSession: (ExternalSessionSummary) -> Unit = {},
    onDeleteExternalSession: (ExternalSessionSummary) -> Unit = {},
    onRenameExternalSession: (ExternalSessionSummary, String) -> Unit = { _, _ -> },
    onTogglePinExternalSession: (ExternalSessionSummary) -> Unit = {},
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String> = emptySet(),
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDeleteSessions: ((Set<String>) -> Unit)? = null,
    onRename: (ChatSession, String) -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onExport: (ChatSession) -> Unit,
    subSessionsByParent: Map<String, List<ChatSession>> = emptyMap(),
    browseState: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String? = null,
    fileOpPaths: Set<String> = emptySet(),
    clipboard: BrowseClipboard? = null,
    pasteConflict: Pair<String, String>? = null,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onPasteOverwrite: () -> Unit,
    onCancelPasteOverwrite: () -> Unit,
    onClearClipboard: () -> Unit,
    workspaceName: String = "",
    onAddLocalFolder: (Uri) -> Unit = {},
    onRemoveLocalFolder: (String) -> Unit = {},
    onUploadFiles: (String, List<Uri>) -> Unit = { _, _ -> },
    onUploadFolder: (String, Uri) -> Unit = { _, _ -> },
    onNavigateToBrowser: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit,
    pluginEntries: List<PluginUiEntry> = emptyList(),
    pluginLanguageTag: String = "zh-CN",
    onPluginEntry: (PluginUiEntry) -> Unit = {},
    sessionPluginEntries: List<PluginUiEntry> = emptyList(),
    onSessionPluginEntry: (ChatSession, PluginUiEntry) -> Unit = { _, _ -> },
    filePluginEntries: List<PluginUiEntry> = emptyList(),
    onFilePluginEntry: (FileTreeNode, PluginUiEntry) -> Unit = { _, _ -> },
    searchQuery: String,
    searchState: ChatSearchState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    // tab 与展开状态进 saveable：大屏下侧栏收起后整棵子树会离开组合（见 MainActivity 的
    // SaveableStateProvider），用 remember 存会让每次回到聊天页都重置回「会话」页。
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val selection = rememberSaveableListSelectionState()
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    var pendingExternalDelete by remember { mutableStateOf<ExternalSessionSummary?>(null) }
    var pendingExternalRename by remember { mutableStateOf<ExternalSessionSummary?>(null) }
    var menuExternalSession by remember { mutableStateOf<ExternalSessionSummary?>(null) }
    var showAllPlugins by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val allSessionIds: List<String> = remember(sessions, subSessionsByParent) {
        buildList {
            sessions.forEach { s ->
                add(s.id)
                subSessionsByParent[s.id]?.forEach { sub -> add(sub.id) }
            }
        }
    }
    LaunchedEffect(allSessionIds, selection.isActive) {
        selection.retainOnly(allSessionIds.toSet())
        if (selection.isActive && allSessionIds.isEmpty()) selection.exit()
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != 0 && selection.isActive) selection.exit()
    }

    // 点击会话/重开侧边栏保持原滚动位置；仅当同一会话的最后回复时间变化（发消息/收到回复）时滚回顶部。
    var lastTouched by remember { mutableStateOf<Pair<String?, Long?>?>(null) }
    val currentUpdatedAt = sessions.firstOrNull { it.id == currentSessionId }?.updatedAt
    LaunchedEffect(currentSessionId, currentUpdatedAt) {
        val cur = currentSessionId to currentUpdatedAt
        val prev = lastTouched
        lastTouched = cur
        if (prev != null && prev.first == cur.first && prev.second != cur.second) {
            listState.scrollToItem(0)
        }
    }

    val imeInset = rememberImeBottomInset()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(settingsPageBackground())
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            .padding(horizontal = Spacing.lg)
            .padding(bottom = imeInset + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 顶部 Tab 切换
        SegmentedTabs(
            selected = selectedTab,
            labels = listOf(
                stringResource(R.string.subagent_tab_sessions),
                stringResource(R.string.drawer_tab_files)
            ),
            onSelect = { selectedTab = it }
        )

        // 聊天记录搜索框放在 Tab 栏正下方，仅「会话」Tab 可见。
        if (selectedTab == 0) {
            ChatSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SessionListTab(
                    sessions = sessions,
                    externalSessionGroups = externalSessionGroups,
                    currentExternalSessionId = currentExternalSessionId,
                    onSelectExternalSession = onSelectExternalSession,
                    onLongClickExternalSession = { menuExternalSession = it },
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    awaitingPermissionSessionIds = awaitingPermissionSessionIds,
                    subSessionsByParent = subSessionsByParent,
                    listState = listState,
                    searchQuery = searchQuery,
                    searchState = searchState,
                    selectionMode = selection.isActive,
                    selectedSessionIds = selection.selectedKeys,
                    onToggleSelectSession = { s -> selection.toggle(s.id) },
                    onSelect = onSelect,
                    onLongClick = { menuSession = it },
                    onOpenSearchHit = onOpenSearchHit
                )
                1 -> FileBrowserTab(
                    workspaceName = workspaceName,
                    state = browseState,
                    expandedPaths = expandedPaths,
                    expandingPath = expandingPath,
                    fileOpPaths = fileOpPaths,
                    clipboard = clipboard,
                    onToggleExpand = onToggleExpand,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshBrowse,
                    onCreateFile = onCreateFile,
                    onCreateFolder = onCreateFolder,
                    onRenameEntry = onRenameEntry,
                    onDeleteEntry = onDeleteEntry,
                    onCopyEntry = onCopyEntry,
                    onCutEntry = onCutEntry,
                    onPasteEntry = onPasteEntry,
                    onClearClipboard = onClearClipboard,
                    onAddLocalFolder = onAddLocalFolder,
                    onRemoveLocalFolder = onRemoveLocalFolder,
                    onUploadFiles = onUploadFiles,
                    onUploadFolder = onUploadFolder,
                    filePluginEntries = filePluginEntries,
                    pluginLanguageTag = pluginLanguageTag,
                    onFilePluginEntry = onFilePluginEntry
                )
            }

            val allSelected = allSessionIds.isNotEmpty() && selection.selectedCount == allSessionIds.size
            FloatingSelectionBar(
                visible = selectedTab == 0 && selection.isActive,
                selectedCount = selection.selectedCount,
                allSelected = allSelected,
                onToggleSelectAll = {
                    if (allSelected) selection.clear() else selection.replaceAll(allSessionIds)
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
                SelectionActionDangerButton(
                    text = stringResource(R.string.common_batch_delete),
                    enabled = selection.selectedCount > 0,
                    onClick = { showBatchDeleteConfirm = true }
                )
            }
        }

        if (pluginEntries.isNotEmpty()) {
            SettingsGroupHeader(text = stringResource(R.string.settings_plugins))
            SettingsGroup {
                pluginEntries.take(3).forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = pluginPageIcon(entry.icon),
                        title = entry.title.resolve(pluginLanguageTag),
                        subtitle = stringResource(R.string.plugins_ext_entry_subtitle, entry.pluginId),
                        onClick = { onPluginEntry(entry) }
                    )
                }
                if (pluginEntries.size > 3) {
                    SettingsDivider()
                    SettingsRow(
                        icon = FeatherIcons.ChevronRight,
                        title = stringResource(R.string.plugins_more_in_drawer),
                        onClick = { showAllPlugins = true }
                    )
                }
            }
        }

        SettingsGroup {
            if (onNavigateToBrowser != null) {
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.browser_title),
                    onClick = onNavigateToBrowser
                )
                SettingsDivider()
            }
            SettingsRow(
                icon = FeatherIcons.Settings,
                title = stringResource(R.string.chat_settings),
                onClick = onNavigateToSettings,
                modifier = Modifier.onboardingTarget(OnboardingStep.ENTER_SETTINGS)
            )
        }
    }

    if (showAllPlugins && pluginEntries.isNotEmpty()) {
        AdaptiveModalBottomSheet(onDismissRequest = { showAllPlugins = false }) {
            Text(
                text = stringResource(R.string.settings_plugins),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
            Column(modifier = Modifier.padding(bottom = Spacing.xl)) {
                pluginEntries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = pluginPageIcon(entry.icon),
                        title = entry.title.resolve(pluginLanguageTag),
                        subtitle = stringResource(R.string.plugins_ext_entry_subtitle, entry.pluginId),
                        onClick = {
                            showAllPlugins = false
                            onPluginEntry(entry)
                        }
                    )
                }
            }
        }
    }

    pasteConflict?.let { (_, targetPath) ->
        AlertDialog(
            onDismissRequest = onCancelPasteOverwrite,
            title = { Text(stringResource(R.string.file_browser_paste_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.file_browser_paste_conflict_message,
                        targetPath.substringAfterLast('/')
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onPasteOverwrite) { Text(stringResource(R.string.common_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelPasteOverwrite) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onTogglePin = {
                menuSession = null
                onTogglePin(session)
            },
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onBatchSelect = {
                menuSession = null
                selection.enterWith(session.id)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            pluginEntries = sessionPluginEntries,
            pluginLanguageTag = pluginLanguageTag,
            onPluginEntry = { entry -> onSessionPluginEntry(session, entry) },
            onDismiss = { menuSession = null }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_batch_delete_title)) },
            text = {
                Text(stringResource(R.string.chat_batch_delete_confirm, selection.selectedCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    val toDelete = selection.selectedKeys.toSet()
                    selection.exit()
                    if (onDeleteSessions != null) {
                        onDeleteSessions(toDelete)
                    } else {
                        toDelete.forEach { sid: String ->
                            sessions.firstOrNull { it.id == sid }?.let { onDelete(it) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    menuExternalSession?.let { ext ->
        SessionActionSheet(
            session = ExternalSessionUiMapper.toChatSession(ext),
            onTogglePin = {
                menuExternalSession = null
                onTogglePinExternalSession(ext)
            },
            onRename = {
                menuExternalSession = null
                pendingExternalRename = ext
            },
            onExport = {},
            onBatchSelect = {},
            onDelete = {
                menuExternalSession = null
                pendingExternalDelete = ext
            },
            pluginEntries = emptyList(),
            pluginLanguageTag = pluginLanguageTag,
            onPluginEntry = {},
            onDismiss = { menuExternalSession = null },
            isExternal = true
        )
    }

    pendingExternalDelete?.let { ext ->
        AlertDialog(
            onDismissRequest = { pendingExternalDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, ext.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteExternalSession(ext)
                    pendingExternalDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingExternalRename?.let { ext ->
        var renameText by remember(ext.id) { mutableStateOf(ext.title) }
        AlertDialog(
            onDismissRequest = { pendingExternalRename = null },
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
                        onRenameExternalSession(ext, renameText)
                        pendingExternalRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != ext.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingExternalRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
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
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Tab0：会话列表。搜索词非空时列表区切换为跨会话搜索结果。
 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    externalSessionGroups: List<ExternalSessionGroup>,
    currentExternalSessionId: String?,
    onSelectExternalSession: (ExternalSessionSummary) -> Unit,
    onLongClickExternalSession: (ExternalSessionSummary) -> Unit,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    searchQuery: String,
    searchState: ChatSearchState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit
) {
    if (searchQuery.isBlank()) {
        SessionListContent(
            sessions = sessions,
            externalSessionGroups = externalSessionGroups,
            currentExternalSessionId = currentExternalSessionId,
            onSelectExternalSession = onSelectExternalSession,
            onLongClickExternalSession = onLongClickExternalSession,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            listState = listState,
            selectionMode = selectionMode,
            selectedSessionIds = selectedSessionIds,
            onToggleSelectSession = onToggleSelectSession,
            onSelect = onSelect,
            onLongClick = onLongClick
        )
    } else {
        ChatSearchResults(state = searchState, onOpenHit = onOpenSearchHit)
    }
}

/**
 * Tab0 的会话列表本体：置顶与时间分组；所有分组头都可以点击折叠/展开。
 */
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    externalSessionGroups: List<ExternalSessionGroup>,
    currentExternalSessionId: String?,
    onSelectExternalSession: (ExternalSessionSummary) -> Unit,
    onLongClickExternalSession: (ExternalSessionSummary) -> Unit,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty() && externalSessionGroups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_no_sessions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        return
    }
    var expandedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    var collapsedGroupKeys by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    val groups = remember(sessions) {
        val now = System.currentTimeMillis()
        val pinned = sessions.filter { it.isPinned }
        val unpinned = sessions.filterNot { it.isPinned }
        buildList {
            if (pinned.isNotEmpty()) add(SessionGroup("pinned", pinned))
            addAll(buildSessionGroups(unpinned, now))
        }
    }

    fun toggleGroup(key: String) {
        collapsedGroupKeys = if (key in collapsedGroupKeys) {
            collapsedGroupKeys - key
        } else {
            collapsedGroupKeys + key
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (selectionMode) 80.dp else 0.dp)
    ) {
        externalSessionGroups.forEach { extGroup ->
            val groupKey = "external:${extGroup.pluginId}"
            val expanded = groupKey !in collapsedGroupKeys
            item(key = "group-$groupKey", contentType = "group-header") {
                CollapsibleSessionGroupHeader(
                    text = extGroup.pluginName.ifBlank { extGroup.pluginId },
                    count = extGroup.sessions.size,
                    expanded = expanded,
                    loading = extGroup.loading,
                    onClick = { toggleGroup(groupKey) }
                )
            }
            if (expanded) {
                itemsIndexed(
                    items = extGroup.sessions,
                    key = { _, extSession -> "ext-${extSession.id}" },
                    contentType = { _, _ -> "session" }
                ) { index, extSession ->
                    Column {
                        if (index > 0) SettingsDivider()
                        val extState = agentStates[extSession.id]
                        val isExecuting = extSession.running ||
                            extState is AgentUIState.Loading ||
                            extState is AgentUIState.Streaming
                        ChatSessionRow(
                            session = ExternalSessionUiMapper.toChatSession(extSession),
                            selected = !selectionMode && currentExternalSessionId == extSession.id,
                            isExecuting = isExecuting,
                            awaitingPermission = extSession.id in awaitingPermissionSessionIds,
                            pinned = extSession.isPinned,
                            selectionMode = false,
                            checked = false,
                            onCheckedChange = {},
                            onClick = { onSelectExternalSession(extSession) },
                            onLongClick = { onLongClickExternalSession(extSession) }
                        )
                    }
                }
            }
        }
        // 本机会话：每个分组占一个 item、组内 forEach 全量渲染会让 LazyColumn 失去惰性：
        // 「最近 30 天」这种分组有上百个会话时，一个 item 就要组合上百行。分组头与会话各自成 item。
        groups.forEach { group ->
            val expanded = group.groupKey !in collapsedGroupKeys
            item(key = "group-${group.groupKey}", contentType = "group-header") {
                CollapsibleSessionGroupHeader(
                    text = sessionGroupLabel(group.groupKey, group.sessions.first()),
                    count = group.sessions.size,
                    expanded = expanded,
                    onClick = { toggleGroup(group.groupKey) }
                )
            }
            if (expanded) {
                itemsIndexed(
                    items = group.sessions,
                    key = { _, session -> session.id },
                    contentType = { _, _ -> "session" }
                ) { index, session ->
                    Column {
                        if (index > 0) {
                            if (group.groupKey == "pinned") Spacer(Modifier.height(Spacing.sm)) else SettingsDivider()
                        }
                        val state = agentStates[session.id]
                        val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                        val subSessions = subSessionsByParent[session.id].orEmpty()
                        val expanded = session.id in expandedIds
                        ChatSessionRow(
                            session = session,
                            selected = !selectionMode && currentExternalSessionId == null && session.id == currentSessionId,
                            isExecuting = isExecuting,
                            awaitingPermission = session.id in awaitingPermissionSessionIds,
                            pinned = session.isPinned,
                            selectionMode = selectionMode,
                            checked = session.id in selectedSessionIds,
                            onCheckedChange = { onToggleSelectSession(session) },
                            onClick = {
                                if (selectionMode) {
                                    onToggleSelectSession(session)
                                } else {
                                    onSelect(session)
                                }
                            },
                            onLongClick = {
                                if (selectionMode) {
                                    onToggleSelectSession(session)
                                } else {
                                    onLongClick(session)
                                }
                            },
                            trailing = if (subSessions.isEmpty()) null else {
                                {
                                    SubAgentExpandToggle(
                                        expanded = expanded,
                                        count = subSessions.size,
                                        onToggle = {
                                            expandedIds = if (expanded) {
                                                expandedIds - session.id
                                            } else {
                                                expandedIds + session.id
                                            }
                                        }
                                    )
                                }
                            }
                        )
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut()
                        ) {
                            Column {
                                subSessions.forEach { sub ->
                                    val subState = agentStates[sub.id]
                                    Row(modifier = Modifier.padding(start = Spacing.lg)) {
                                        ChatSessionRow(
                                            session = sub,
                                            selected = !selectionMode && currentExternalSessionId == null && sub.id == currentSessionId,
                                            isExecuting = subState is AgentUIState.Loading ||
                                                subState is AgentUIState.Streaming,
                                            awaitingPermission = sub.id in awaitingPermissionSessionIds,
                                            pinned = false,
                                            selectionMode = selectionMode,
                                            checked = sub.id in selectedSessionIds,
                                            onCheckedChange = { onToggleSelectSession(sub) },
                                            onClick = {
                                                if (selectionMode) {
                                                    onToggleSelectSession(sub)
                                                } else {
                                                    onSelect(sub)
                                                }
                                            },
                                            onLongClick = {
                                                if (selectionMode) {
                                                    onToggleSelectSession(sub)
                                                } else {
                                                    onLongClick(sub)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 可点击折叠的分组头：标题 + 数量/加载转圈 + 展开箭头。 */
@Composable
private fun CollapsibleSessionGroupHeader(
    text: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (loading) {
            DrawerProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        } else {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** Tab0 底部搜索框：复用设置页的胶囊搜索框，非空时带清除按钮。 */
@Composable
private fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    ModelSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.chat_search_hint),
        modifier = Modifier.fillMaxWidth(),
        trailing = if (query.isEmpty()) null else {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
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
}

/** 搜索结果区：加载中 / 无结果 / 命中列表三态。 */
@Composable
private fun ChatSearchResults(
    state: ChatSearchState,
    onOpenHit: (ChatSearchHit) -> Unit
) {
    when {
        state.hits.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DrawerProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.hits.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_search_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits, key = { it.messageId }) { hit ->
                    ChatSearchResultRow(
                        hit = hit,
                        query = state.query,
                        onClick = { onOpenHit(hit) }
                    )
                    SettingsDivider()
                }
            }
        }
    }
}

/** 单条搜索结果行：会话标题 + 命中片段（关键词高亮），点击跳转并定位。 */
@Composable
private fun ChatSearchResultRow(
    hit: ChatSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Text(
            text = hit.sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = highlightSnippet(
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

/** 把片段中所有命中词标为高亮样式。 */
private fun highlightSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        if (query.isBlank()) return@buildAnnotatedString
        var start = 0
        while (true) {
            val index = snippet.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            addStyle(highlight, index, index + query.length)
            start = index + query.length
        }
    }

/** 会话行尾的子代理展开开关：显示数量与箭头，自己消费点击，不触发整行选中。 */
@Composable
private fun SubAgentExpandToggle(
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = stringResource(
                if (expanded) R.string.drawer_collapse_subagents else R.string.drawer_expand_subagents
            ),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tab1：当前工作区的文件树。从工作区根就地展开，缩进表示层级；
 * 每行共享同一横向滚动，路径过深时左右滑动查看完整名称，不再截断成省略号。
 * 顶部标题为当前工作区名，右侧「+」添加设备上的文件夹、「⟳」重读全部目录。
 * 新建文件/文件夹、上传与移除本机文件夹均通过长按目录（含根）行的菜单发起。
 */
@Composable
private fun FileBrowserTab(
    workspaceName: String,
    state: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit,
    onAddLocalFolder: (Uri) -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onUploadFiles: (String, List<Uri>) -> Unit,
    onUploadFolder: (String, Uri) -> Unit,
    filePluginEntries: List<PluginUiEntry> = emptyList(),
    pluginLanguageTag: String = "zh-CN",
    onFilePluginEntry: (FileTreeNode, PluginUiEntry) -> Unit = { _, _ -> }
) {
    var creating by remember { mutableStateOf<CreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    // 上传时记住长按的目标目录；系统选择器回调时目标目录行可能已被刷新，不能用菜单节点。
    var uploadTarget by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val hScroll = rememberScrollState()

    val localFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            onAddLocalFolder(uri)
        }
    }
    val uploadFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = uploadTarget
        uploadTarget = null
        if (target != null && uris.isNotEmpty()) onUploadFiles(target, uris)
    }
    val uploadFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = uploadTarget
        uploadTarget = null
        if (target != null && uri != null) onUploadFolder(target, uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val title = if (workspaceName.isBlank()) stringResource(R.string.file_browser_title) else workspaceName
        // 标题行：当前工作区名 + 剪贴板/进行中指示 + 添加本机文件夹 + 刷新。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            clipboard?.let { clip ->
                ClipboardIndicator(
                    clipboard = clip,
                    onClear = onClearClipboard,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            if (fileOpPaths.isNotEmpty()) {
                DrawerProgressIndicator(
                    modifier = Modifier.padding(end = Spacing.xs).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = { localFolderLauncher.launch(null) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.file_browser_add_folder),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                is FileBrowseState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerProgressIndicator(modifier = Modifier.size(24.dp))
                }

                is FileBrowseState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.detail ?: stringResource(R.string.file_browser_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.md)
                    )
                }

                is FileBrowseState.Success -> {
                    val listState = rememberLazyListState()
                    val textMeasurer = rememberTextMeasurer()
                    val textStyle = MaterialTheme.typography.bodyMedium
                    val density = LocalDensity.current
                    // 预算最宽一行的内容宽度（固定开销 + 缩进 + 文本），供所有行取统一宽度，
                    // 以实现整棵树统一横向平移（而非每行各自滚动）。
                    // 逐个 measure 在大目录下会在组合期同步跑上千次文本测量；先按「缩进 + 字符宽度权重」
                    // 挑出最宽的那一行，只对它做一次真实测量。估算不精确，但这里只用来给横向滚动留够宽度。
                    val maxContentPx = remember(state.nodes, textStyle) {
                        with(density) {
                            val widest = state.nodes.maxByOrNull { node ->
                                node.depth * 4 + node.displayName.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }
                            } ?: return@with 0
                            val textW = textMeasurer.measure(widest.displayName, textStyle).size.width
                            (FILE_TREE_ROW_OVERHEAD.toPx() + (Spacing.lg * widest.depth).toPx() + textW).toInt()
                        }
                    }
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                        val rowWidth = with(density) { rowWidthPx.toDp() }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxHeight()
                                .horizontalScroll(hScroll)
                        ) {
                            items(state.nodes, key = { it.path }) { node ->
                                FileTreeRow(
                                    node = node,
                                    rowWidth = rowWidth,
                                    expanding = node.entry.isDirectory && node.path == expandingPath,
                                    busy = node.path in fileOpPaths,
                                    onClick = {
                                        if (node.entry.isDirectory) onToggleExpand(node.path)
                                        else onOpenFile(node.path)
                                    },
                                    onLongClick = { menuNode = node },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                        fadeOutSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                        placementSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = stringResource(
                if (target.kind == CreateKind.FILE) R.string.file_browser_new_file
                else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (target.kind == CreateKind.FILE) onCreateFile(target.parent, name)
                else onCreateFolder(target.parent, name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        val root = node.root
        // 上传是从设备选择文件/文件夹写入当前目录：只对容器内目录（工作区、容器、其子目录）提供，
        // 本机文件夹本来就在设备上，不需要上传。
        val isLocalFolderPath = WorkspacePathMapper.isLocalFolderContainerPath(node.path)
        val uploadFileAction: (() -> Unit)? = if (node.entry.isDirectory && !isLocalFolderPath) {
            {
                menuNode = null
                uploadTarget = node.path
                uploadFileLauncher.launch(arrayOf("*/*"))
            }
        } else null
        val uploadFolderAction: (() -> Unit)? = if (node.entry.isDirectory && !isLocalFolderPath) {
            {
                menuNode = null
                uploadTarget = node.path
                uploadFolderLauncher.launch(null)
            }
        } else null
        val removeFolderAction: (() -> Unit)? = if (root is FileTreeRoot.LocalFolder) {
            {
                menuNode = null
                onRemoveLocalFolder(root.hostPath)
            }
        } else null
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onUploadFile = uploadFileAction,
            onUploadFolder = uploadFolderAction,
            onRemoveLocalFolder = removeFolderAction,
            onNewFile = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (node.root == null && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onCopy = {
                menuNode = null
                onCopyEntry(node.path, node.entry.name)
            },
            onCut = {
                menuNode = null
                onCutEntry(node.path, node.entry.name)
            },
            onPasteHere = {
                val target = node.path
                menuNode = null
                onPasteEntry(target) { /* 结果由调用方决定是否提示，这里保持静默 */ }
            },
            pluginEntries = filePluginEntries,
            pluginLanguageTag = pluginLanguageTag,
            onPluginEntry = { entry -> onFilePluginEntry(node, entry) },
            onDismiss = { menuNode = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = node.entry.name,
            onConfirm = { name ->
                onRenameEntry(node.path, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (node.entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        node.entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(node.path)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 新建对象类型，决定确认后调创建文件还是创建文件夹。 */
private enum class CreateKind { FILE, FOLDER }

/** 新建目标：在哪个目录（[parent]）下新建，以及新建文件还是文件夹（[kind]）。 */
private data class CreateTarget(val parent: String, val kind: CreateKind)

/** 新建 / 重命名共用的名称输入弹窗：名称非法或与原名相同时禁用确认。 */
@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 剪切板指示器：右上角刷新按钮旁的主题色图标，显示复制/剪切状态，点击清空。 */
@Composable
private fun ClipboardIndicator(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClear,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (clipboard.isCut) Icons.Filled.ContentCut else FeatherIcons.Copy,
            contentDescription = stringResource(
                if (clipboard.isCut) R.string.file_browser_clipboard_cut
                else R.string.file_browser_clipboard_copy
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** 文件树节点长按弹出的功能菜单：容器内目录（含工作区根）可新建与上传文件/文件夹，
 * 非根节点可复制/剪切/重命名/删除，剪切板非空时目录可「粘贴到此处」；本机文件夹根可移除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onUploadFile: (() -> Unit)?,
    onUploadFolder: (() -> Unit)?,
    onRemoveLocalFolder: (() -> Unit)?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    pluginEntries: List<PluginUiEntry>,
    pluginLanguageTag: String,
    onPluginEntry: (PluginUiEntry) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            // 剪切板非空且当前节点是目录 / 根：优先提供「粘贴到此处」。
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile
                )
                SheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder
                )
            }
            onUploadFile?.let { action ->
                SheetActionRow(
                    icon = FeatherIcons.Upload,
                    label = stringResource(R.string.file_browser_upload_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = action
                )
            }
            onUploadFolder?.let { action ->
                SheetActionRow(
                    icon = FeatherIcons.UploadCloud,
                    label = stringResource(R.string.file_browser_upload_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = action
                )
            }
            if (node.root == null) {
                SheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy
                )
                SheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut
                )
                SheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename
                )
            }
            if (pluginEntries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_plugins),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
                pluginEntries.forEach { entry ->
                    SheetActionRow(
                        icon = pluginPageIcon(entry.icon),
                        label = entry.title.resolve(pluginLanguageTag),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onDismiss()
                            onPluginEntry(entry)
                        }
                    )
                }
            }
            if (node.root == null) {
                SheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
            onRemoveLocalFolder?.let { action ->
                SheetActionRow(
                    icon = FeatherIcons.X,
                    label = stringResource(R.string.file_browser_remove_folder),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = action
                )
            }
        }
    }
}

/** 文件树的一行：按 [FileTreeNode.depth] 缩进，目录带展开箭头。
 * 所有行取统一的 [rowWidth]（与外层 horizontalScroll 配合），横向滑动时整树一起平移，
 * 名称不截断、不换行。着色优先级：读取失败 > .gitignore 命中（橙）> dotfile（弱化）> 常规。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    expanding: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 展开加载态加最小延迟：本地模式列目录极快，避免每次展开都闪一下转圈。
    var showExpanding by remember { mutableStateOf(false) }
    LaunchedEffect(expanding) {
        if (expanding) {
            delay(180)
            showExpanding = true
        } else {
            showExpanding = false
        }
    }
    val isDotfile = node.root == null && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> MaterialTheme.colorScheme.error
        node.ignored -> FileTreeIgnoredColor
        isDotfile -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> null
    }
    Row(
        modifier = modifier
            .width(rowWidth)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.xs + 2.dp)
            .padding(start = Spacing.sm, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(Spacing.lg * node.depth))
        if (node.entry.isDirectory) {
            if (showExpanding) {
                DrawerProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                ExpandableChevronIcon(
                    expanded = node.isExpanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    contentDescription = stringResource(
                        if (node.isExpanded) R.string.file_browser_collapse else R.string.file_browser_expand
                    ),
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(Spacing.xs))
        if (busy) {
            DrawerProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FileTreeIcon(node = node, decorationColor = decorationColor)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = node.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 文件树行的类型图标：目录用文件夹，文件按扩展名挑选单色线性图标。容器根用「容器与镜像」列表同款图标，
 * 本机文件夹根用「添加外部工作区」同款磁盘图标。
 * [decorationColor] 非空时统一染色（.gitignore/dotfile 弱化）。 */
@Composable
private fun FileTreeIcon(node: FileTreeNode, decorationColor: Color?) {
    val containerRoot = node.root as? FileTreeRoot.Container
    if (containerRoot != null) {
        val tint = decorationColor ?: MaterialTheme.colorScheme.primary
        val logo = containerOsLogo(containerRoot.osId)
        if (logo != null) {
            Icon(
                painter = logo,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        } else {
            Icon(
                imageVector = if (containerRoot.remote) FeatherIcons.Server else FeatherIcons.HardDrive,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
        }
        return
    }
    if (node.root is FileTreeRoot.LocalFolder) {
        Icon(
            imageVector = FeatherIcons.HardDrive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = decorationColor ?: MaterialTheme.colorScheme.primary
        )
        return
    }
    val vector = if (node.entry.isDirectory) {
        FeatherIcons.Folder
    } else {
        fileTypeIconFor(node.entry.name)
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (node.entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** .gitignore 命中条目的弱化色（橙，类 VSCode）。 */
private val FileTreeIgnoredColor = Color(0xFFCC8844)

/** 文件树一行的显示名：根节点用根标签（工作区根 `workspace` / 当前容器名），其余用条目名。 */
private val FileTreeNode.displayName: String get() = root?.label ?: entry.name

/** 文件树一行除缩进与文本外的固定宽度开销（start 8 + 箭头 16 + 4 + 图标 20 + 8 + end 12），供预算整树统一行宽。 */
private val FILE_TREE_ROW_OVERHEAD = 68.dp


/**
 * 会话行长按弹出的功能菜单：置顶 / 重命名 / 导出 / 删除。底部 sheet 样式参照 git 分支的 RefActionSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    pluginEntries: List<PluginUiEntry>,
    pluginLanguageTag: String,
    onPluginEntry: (PluginUiEntry) -> Unit,
    onDismiss: () -> Unit,
    isExternal: Boolean = false
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
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            if (!isExternal) {
                SheetActionRow(
                    icon = FeatherIcons.Download,
                    label = stringResource(R.string.chat_export_session),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        onDismiss()
                        onExport()
                    }
                )
                SheetActionRow(
                    icon = FeatherIcons.CheckSquare,
                    label = stringResource(R.string.chat_batch_select),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        onDismiss()
                        onBatchSelect()
                    }
                )
            }
            if (pluginEntries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_plugins),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
                pluginEntries.forEach { entry ->
                    SheetActionRow(
                        icon = pluginPageIcon(entry.icon),
                        label = entry.title.resolve(pluginLanguageTag),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onDismiss()
                            onPluginEntry(entry)
                        }
                    )
                }
            }
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun SheetActionRow(
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** 侧边栏会话分组：同一时间组（今天 / 昨天 / 7天内 / 30天内 / 月份）内的会话，按最后回复时间降序。 */
internal data class SessionGroup(
    val groupKey: String,
    val sessions: List<ChatSession>
)

/**
 * 按最后回复时间（updatedAt）降序的会话列表分组：今天 / 昨天 / 7天内 / 30天内 / 更早按月。
 */
internal fun buildSessionGroups(sessions: List<ChatSession>, now: Long): List<SessionGroup> {
    val groups = mutableListOf<SessionGroup>()
    for (session in sessions) {
        val groupKey = sessionGroupKey(session.updatedAt, now)
        val lastIndex = groups.lastIndex
        if (lastIndex >= 0 && groups[lastIndex].groupKey == groupKey) {
            groups[lastIndex] = groups[lastIndex].copy(sessions = groups[lastIndex].sessions + session)
        } else {
            groups += SessionGroup(groupKey, listOf(session))
        }
    }
    return groups
}

/** 返回会话所属分组 key；月份分组为 ISO 年月（如 2026-05），其余为固定字面量。 */
internal fun sessionGroupKey(updatedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days <= 7L -> "7d"
        days <= 30L -> "30d"
        else -> YearMonth.from(day).toString()
    }
}

@Composable
private fun sessionGroupLabel(groupKey: String, anchorSession: ChatSession): String = when (groupKey) {
    "pinned" -> stringResource(R.string.session_group_pinned)
    "today" -> stringResource(R.string.session_group_today)
    "yesterday" -> stringResource(R.string.session_group_yesterday)
    "7d" -> stringResource(R.string.session_group_last_7_days)
    "30d" -> stringResource(R.string.session_group_last_30_days)
    else -> SimpleDateFormat(
        stringResource(R.string.session_group_month_format),
        Locale.getDefault()
    ).format(Date(anchorSession.updatedAt))
}
