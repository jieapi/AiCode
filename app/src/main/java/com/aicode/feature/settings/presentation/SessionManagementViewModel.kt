package com.aicode.feature.settings.presentation

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.session.SessionSelectionStore
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.run.AgentRunManager
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SessionScopeFilter {
    ALL,
    CURRENT_ENV,
    REMOTE_OR_OFFLINE
}

enum class WorkspaceBucketSource {
    CURRENT_INTERNAL,
    CURRENT_EXTERNAL,
    CURRENT_REMOTE,
    LOCAL_OFFLINE,
    EXTERNAL_OFFLINE,
    REMOTE_OR_OTHER;

    val isCurrentBackend: Boolean
        get() = this == CURRENT_INTERNAL || this == CURRENT_EXTERNAL || this == CURRENT_REMOTE

    val isRemoteLike: Boolean
        get() = this == CURRENT_REMOTE || this == REMOTE_OR_OTHER
}

@Immutable
data class WorkspaceBucket(
    val path: String,
    val name: String,
    val source: WorkspaceBucketSource,
    val workspace: Workspace? = null,
    val isCurrent: Boolean = false,
    val isAvailable: Boolean = false,
    val sessions: List<ChatSession> = emptyList()
)

@Immutable
data class SessionMessageSearchHit(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val workspacePath: String,
    val workspaceName: String,
    val snippet: String,
    val timestamp: Long
)

@Immutable
data class SessionDetailState(
    val session: ChatSession? = null,
    val bucket: WorkspaceBucket? = null,
    val messages: List<AgentUIMessage> = emptyList(),
    val subSessions: List<ChatSession> = emptyList(),
    val loading: Boolean = false
)

@Immutable
data class MoveSessionsOutcome(
    val movedCount: Int,
    val skippedRunningCount: Int = 0,
    val followedWorkspace: Workspace? = null,
    val followedSessionId: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionManagementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionUseCase: SessionUseCase,
    private val agentMessageDao: AgentMessageDao,
    private val workspaceRepository: WorkspaceRepository,
    private val agentRunManager: AgentRunManager,
    private val checkpointManager: CheckpointManager,
    private val backupManager: BackupManager,
    private val sessionSelectionStore: SessionSelectionStore
) : ViewModel() {

    private companion object {
        const val TAG = "SessionManagementVM"
        const val SEARCH_DEBOUNCE_MS = 220L
        const val SEARCH_LIMIT = 80
        const val SNIPPET_CONTEXT = 32
    }

    private val projectsRootPath: String by lazy {
        File(context.filesDir, "projects").absolutePath.trimEnd('/')
    }

    private val _scopeFilter = MutableStateFlow(SessionScopeFilter.ALL)
    val scopeFilter: StateFlow<SessionScopeFilter> = _scopeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    private val currentSelection = sessionSelectionStore.selection
        .stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    private val allSessionEntities = sessionUseCase.getAllStandardSessionsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val messageCounts: StateFlow<Map<String, Int>> = agentMessageDao.getSessionMessageCountsFlow()
        .map { rows -> rows.associate { it.sessionId to it.messageCount } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = allSessionEntities
        .map { list ->
            list.filter { it.parentId != null }
                .map { it.toDomain() }
                .groupBy { it.parentId!! }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val activeOrQueuedSessionIds: StateFlow<Set<String>> = combine(
        allSessionEntities,
        agentRunManager.agentStates,
        agentRunManager.queuedRequests
    ) { entities, states, queued ->
        val subsByParent = entities.filter { it.parentId != null }.groupBy { it.parentId!! }
        buildSet {
            entities.filter { it.parentId == null }.forEach { root ->
                val ids = listOf(root.id) + subsByParent[root.id].orEmpty().map { it.id }
                val busy = ids.any { id ->
                    val s = states[id]
                    s is AgentUIState.Loading || s is AgentUIState.Streaming || !queued[id].isNullOrEmpty()
                }
                if (busy) add(root.id)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val allBuckets: StateFlow<List<WorkspaceBucket>> = combine(
        workspaceRepository.workspaces,
        workspaceRepository.current,
        allSessionEntities
    ) { workspaces, currentWs, entities ->
        buildWorkspaceBuckets(
            workspaces = workspaces,
            currentWorkspace = currentWs,
            rootSessions = entities.filter { it.parentId == null }.map { it.toDomain() },
            projectsRootPath = projectsRootPath
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredBuckets: StateFlow<List<WorkspaceBucket>> = combine(
        allBuckets,
        scopeFilter,
        searchQuery
    ) { buckets, filter, query ->
        val byScope = when (filter) {
            SessionScopeFilter.ALL -> buckets
            SessionScopeFilter.CURRENT_ENV -> buckets.filter { it.source.isCurrentBackend && it.isAvailable }
            SessionScopeFilter.REMOTE_OR_OFFLINE -> buckets.filter { !it.source.isCurrentBackend || !it.isAvailable }
        }
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            byScope
        } else {
            byScope.mapNotNull { bucket ->
                val bucketHit = bucket.name.contains(keyword, ignoreCase = true) ||
                    bucket.path.contains(keyword, ignoreCase = true)
                val matchingSessions = if (bucketHit) {
                    bucket.sessions
                } else {
                    bucket.sessions.filter { it.title.contains(keyword, ignoreCase = true) }
                }
                if (bucketHit || matchingSessions.isNotEmpty()) {
                    bucket.copy(sessions = matchingSessions)
                } else {
                    null
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val messageSearchHits: StateFlow<List<SessionMessageSearchHit>> = combine(
        searchQuery,
        allBuckets
    ) { query, buckets -> query to buckets }
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { (query, buckets) ->
            val keyword = query.trim()
            if (keyword.isEmpty()) {
                flowOf(emptyList())
            } else {
                flow {
                    val nameByPath = buckets.associate { it.path to it.name }
                    val matches = withContext(Dispatchers.IO) {
                        agentMessageDao.searchAllWorkspaces(escapeLike(keyword), SEARCH_LIMIT)
                    }
                    emit(
                        matches.map { m ->
                            SessionMessageSearchHit(
                                messageId = m.messageId,
                                sessionId = m.sessionId,
                                sessionTitle = m.sessionTitle,
                                workspacePath = m.workspacePath,
                                workspaceName = nameByPath[m.workspacePath]
                                    ?: m.workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { m.workspacePath },
                                snippet = buildSnippet(m.content, keyword),
                                timestamp = m.timestamp
                            )
                        }
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val detailState: StateFlow<SessionDetailState> = combine(
        selectedSessionId,
        allSessionEntities,
        allBuckets
    ) { sid, entities, buckets -> Triple(sid, entities, buckets) }
        .flatMapLatest { (sid, entities, buckets) ->
            if (sid.isNullOrBlank()) {
                flowOf(SessionDetailState())
            } else {
                val session = entities.firstOrNull { it.id == sid }?.toDomain()
                if (session == null) {
                    flowOf(SessionDetailState())
                } else {
                    val bucket = buckets.firstOrNull { it.path == session.workspacePath }
                    val subs = entities.filter { it.parentId == sid }.map { it.toDomain() }
                    agentMessageDao.getMessagesBySession(sid).map { rows ->
                        val uiMessages = rows
                            .filterNot { it.isCompacted || it.isContextSummary || it.isCompactionMarker }
                            .map { it.toUIMessage() }
                        SessionDetailState(
                            session = session,
                            bucket = bucket,
                            messages = uiMessages,
                            subSessions = subs,
                            loading = false
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionDetailState())

    fun setScopeFilter(filter: SessionScopeFilter) {
        _scopeFilter.value = filter
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openSessionDetail(sessionId: String) {
        _selectedSessionId.value = sessionId
    }

    fun closeSessionDetail() {
        _selectedSessionId.value = null
    }

    fun isCurrentWorkspaceActiveSession(sessionId: String): Boolean {
        val currentPath = workspaceRepository.current.value?.path ?: return false
        val saved = currentSelection.value
        return saved.second == currentPath && saved.first == sessionId
    }

    fun willMoveTriggerCurrentFollow(sessionIds: Collection<String>, targetBucket: WorkspaceBucket): Boolean {
        val targetWs = targetBucket.workspace ?: return false
        if (!targetWs.available) return false
        val currentWsPath = workspaceRepository.current.value?.path ?: return false
        if (targetWs.path == currentWsPath) return false
        val saved = currentSelection.value
        val activeId = saved.first?.takeIf { saved.second == currentWsPath } ?: return false
        return activeId in sessionIds ||
            allSessionEntities.value.any { it.id == activeId && it.parentId in sessionIds }
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        val currentWsPath = workspaceRepository.current.value?.path ?: return false
        val ids = allSessionEntities.value
            .filter { it.workspacePath == currentWsPath }
            .map { it.id }
        return agentRunManager.hasActiveSessions(ids)
    }

    fun needsSwitchConfirmation(targetWorkspace: Workspace): Boolean {
        val currentWsPath = workspaceRepository.current.value?.path ?: return false
        return targetWorkspace.path != currentWsPath && hasRunningSessionsInCurrentWorkspace()
    }

    fun isCrossEnvironmentMove(sourcePaths: Set<String>, targetBucket: WorkspaceBucket): Boolean {
        if (!targetBucket.isAvailable || !targetBucket.source.isCurrentBackend) return true
        val bucketByPath = allBuckets.value.associateBy { it.path }
        return sourcePaths.any { srcPath ->
            val src = bucketByPath[srcPath] ?: return@any true
            !src.source.isCurrentBackend || !src.isAvailable || src.source.isRemoteLike != targetBucket.source.isRemoteLike
        }
    }

    fun moveSessions(
        sessionIds: Collection<String>,
        targetBucket: WorkspaceBucket,
        onResult: (MoveSessionsOutcome) -> Unit = {}
    ) = viewModelScope.launch {
        val distinctIds = sessionIds.distinct()
        if (distinctIds.isEmpty()) {
            onResult(MoveSessionsOutcome(movedCount = 0))
            return@launch
        }
        val activeSet = activeOrQueuedSessionIds.value
        val movableIds = distinctIds.filterNot { it in activeSet }
        val skippedRunning = distinctIds.size - movableIds.size
        if (movableIds.isEmpty()) {
            onResult(MoveSessionsOutcome(movedCount = 0, skippedRunningCount = skippedRunning))
            return@launch
        }

        val currentWsPath = workspaceRepository.current.value?.path
        val saved = sessionSelectionStore.load()
        val activeId = saved.first?.takeIf { saved.second == currentWsPath }
        val targetWs = targetBucket.workspace?.takeIf { it.available }
        val follows = activeId != null &&
            targetWs != null &&
            targetWs.path != currentWsPath &&
            (activeId in movableIds || allSessionEntities.value.any { it.id == activeId && it.parentId in movableIds })

        val preferredEmptyId = activeId?.takeIf { it in movableIds }
        val moved = sessionUseCase.moveSessionsToWorkspace(
            sessionIds = movableIds,
            targetWorkspacePath = targetBucket.path,
            preferredEmptyId = preferredEmptyId
        )
        if (moved.isNotEmpty() && follows) {
            sessionSelectionStore.save(activeId!!, targetWs!!.path)
        }
        onResult(
            MoveSessionsOutcome(
                movedCount = moved.size,
                skippedRunningCount = skippedRunning,
                followedWorkspace = if (moved.isNotEmpty() && follows) targetWs else null,
                followedSessionId = if (moved.isNotEmpty() && follows) activeId else null
            )
        )
    }

    fun renameSession(sessionId: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(sessionId, trimmed)
    }

    fun togglePinSession(session: ChatSession) = viewModelScope.launch {
        sessionUseCase.updatePinned(session.id, !session.isPinned)
    }

    fun deleteSessions(sessionIds: Collection<String>, onDone: () -> Unit = {}) = viewModelScope.launch {
        val activeSet = activeOrQueuedSessionIds.value
        sessionIds.distinct()
            .filterNot { it in activeSet }
            .forEach { id ->
                checkpointManager.clearSessionCheckpoints(id)
                sessionUseCase.deleteSession(id)
            }
        if (_selectedSessionId.value in sessionIds) {
            _selectedSessionId.value = null
        }
        onDone()
    }

    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e(TAG, "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(SNIPPET_CONTEXT * 2)
        val start = (index - SNIPPET_CONTEXT).coerceAtLeast(0)
        val end = (index + keyword.length + SNIPPET_CONTEXT).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }
}

internal fun buildWorkspaceBuckets(
    workspaces: List<Workspace>,
    currentWorkspace: Workspace?,
    rootSessions: List<ChatSession>,
    projectsRootPath: String
): List<WorkspaceBucket> {
    val sessionsByPath = rootSessions.groupBy { it.workspacePath }
    val knownPaths = mutableSetOf<String>()

    val currentBackendBuckets = workspaces.map { ws ->
        knownPaths += ws.path
        val source = when (ws.type) {
            WorkspaceType.INTERNAL -> WorkspaceBucketSource.CURRENT_INTERNAL
            WorkspaceType.EXTERNAL_LOCAL -> WorkspaceBucketSource.CURRENT_EXTERNAL
            WorkspaceType.REMOTE -> WorkspaceBucketSource.CURRENT_REMOTE
        }
        WorkspaceBucket(
            path = ws.path,
            name = ws.name,
            source = source,
            workspace = ws,
            isCurrent = currentWorkspace?.ref == ws.ref,
            isAvailable = ws.available,
            sessions = sessionsByPath[ws.path].orEmpty()
        )
    }

    val offlineBuckets = sessionsByPath.entries
        .filter { (path, list) -> path !in knownPaths && list.isNotEmpty() }
        .map { (path, list) ->
            val source = classifyOfflineWorkspacePath(path, projectsRootPath)
            val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            WorkspaceBucket(
                path = path,
                name = name,
                source = source,
                workspace = null,
                isCurrent = false,
                isAvailable = false,
                sessions = list
            )
        }
        .sortedByDescending { bucket -> bucket.sessions.maxOfOrNull { it.updatedAt } ?: 0L }

    val orderedCurrent = currentBackendBuckets.sortedWith(
        compareByDescending<WorkspaceBucket> { it.isCurrent }
            .thenByDescending { it.isAvailable }
            .thenBy { it.name.lowercase() }
    )
    return orderedCurrent + offlineBuckets
}

internal fun classifyOfflineWorkspacePath(
    workspacePath: String,
    projectsRootPath: String
): WorkspaceBucketSource {
    val normalized = workspacePath.trimEnd('/')
    val normalizedProjectsRoot = projectsRootPath.trimEnd('/')
    return when {
        normalizedProjectsRoot.isNotEmpty() &&
            (normalized == normalizedProjectsRoot || normalized.startsWith("$normalizedProjectsRoot/")) ->
            WorkspaceBucketSource.LOCAL_OFFLINE
        normalized.startsWith("/storage/") ||
            normalized.startsWith("/sdcard/") ||
            normalized.startsWith("/mnt/") ->
            WorkspaceBucketSource.EXTERNAL_OFFLINE
        else -> WorkspaceBucketSource.REMOTE_OR_OTHER
    }
}
