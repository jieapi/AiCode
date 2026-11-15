package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.core.util.toUserMessage
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.agent.domain.subagent.SubAgentDigester
import com.aicode.feature.agent.domain.subagent.SubAgentDigest
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.UriPathResolver
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.session.AgentChatRequest
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.SessionSelectionStore
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.mention.FileMentionRepository
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.run.AgentRunManager
import com.aicode.feature.agent.presentation.run.ReasoningDurationEvent
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val sessionSelectionStore: SessionSelectionStore,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val terminalSessionProvider: TerminalSessionProvider,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val fileMentionRepository: FileMentionRepository,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerOsDetector: ContainerOsDetector,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val liveNotificationSettings: LiveNotificationSettingsRepository,
    private val agentLiveNotificationManager: AgentLiveNotificationManager,
    private val approvalNotificationSettings: ApprovalNotificationSettingsRepository,
    private val agentApprovalNotificationManager: AgentApprovalNotificationManager,
    private val subAgentEventBus: SubAgentEventBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val executionModeHolder: ExecutionModeHolder,
    private val agentRunManager: AgentRunManager,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val agentStates: StateFlow<Map<String, AgentUIState>> = agentRunManager.agentStates

    val completedSessions: StateFlow<Set<String>> = agentRunManager.completedSessions

    val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = agentRunManager.subAgentCards

    val agentState: StateFlow<AgentUIState> = combine(_currentSessionId, agentRunManager.agentStates) { id, states ->
        if (id == null) AgentUIState.Idle else states[id] ?: AgentUIState.Idle
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30
    private val loadedMessageLimits = mutableMapOf<String, Int>()

    /** 聊天记录搜索：命中条数上限、输入防抖、片段上下文宽度、定位时预留的分页余量。 */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * 工具调用（分组头与单条工具卡片）的手动展开态：key = 分组 key（`toolgroup:<组内首条消息 id>`）
     * 或单条消息 id。
     *
     * 放在 ViewModel 而不是组合里：窄窗下打开设置 / 终端 / Git / 编辑器都是全屏路由，聊天页整棵
     * 组合被 dispose，`remember` 的 map 与按 message.id 的 remember 会一起丢——展开过的工具
     * 一离开视线（滚出屏幕被回收、切页返回）就缩回默认态。这里按 App 进程的内存保留，
     * 会话间互不影响（key 取消息 id，全局唯一），不落盘。
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次手动展开/收起（取值由调用方按当前可见态取反后传入）。 */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * 整轮任务折叠（外层「执行中 / 已完成」头）的手动展开态：key = `turn:<轮首消息 id>`。
     *
     * 与 [toolExpansionOverrides] 同款：按 App 进程内存保留、不落盘。没记录时按「末轮运行中默认展开、
     * 其余默认收起」推默认值（见 AIChatPanel.buildChatItems 的 activeTurnKey）；一旦用户手动点过，
     * 就以其选择为准，任务完成自动收起也不会覆盖它。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    val turnMessageKeys = mutableMapOf<String, MutableMap<String, String>>()

    /** 记录一次整轮折叠的手动展开/收起。 */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()
    val reasoningScrollStates = mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>()

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val state = messagesState.value
        if (state.sessionId != sid || !state.loaded || !state.hasMore) return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        if (loadedMessageLimits[sid] != currentLimit) return
        // 按当前上限的一定比例递增：一轮长过程折叠后可见项很少，定步长 30 条要连续补载很多次才能翻过它。
        // 单次步长封顶 90：每次补载都要重查、重转换并重建列表，步长随上限无界增长会让单次卡顿越来越长。
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + (currentLimit / 2).coerceIn(30, 90)))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // 展开状态、目录缓存与整树重读由 browseScopeFlow 的收集方按新工作区统一重置（见 init）。
        // 搜索限定当前工作区，切区后旧结果无意义，一并清空。
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有根会话的子代理，按父会话 id 分组，供侧边栏会话行就地展开。
     * 用全量查询而非逐行惰加载：同一张表一次读完，避开每展开一行开一个 Flow 的订阅风暴。
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 聊天记录全局搜索（限当前工作区）──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** 转义 LIKE 通配符，配合 SQL 里的 ESCAPE '!'（转义字符本身需最先处理）。 */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** 以命中词为中心截取片段：折叠换行空白，两端按需加省略号；未命中时退回开头一段。 */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** 文件树「+」添加的本机文件夹记录：宿主真实路径 + 目录选择器 URI。 */
    @Serializable
    private data class LocalFolderPref(val hostPath: String, val uri: String)

    /** 本机文件夹根的持久化（重启保留）。 */
    private val localFolderPrefs =
        context.getSharedPreferences("file_tree_local_folders", Context.MODE_PRIVATE)

    /** 文件树「+」添加的本机文件夹根，按添加顺序排在容器根之后。 */
    private val _localFolders = MutableStateFlow(readLocalFolders())

    /** 侧边栏「文件」Tab 已展开的目录集合（容器路径）。含工作区根：根也可折叠，默认展开；按工作区持久化。 */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** 文件树展开状态按工作区持久化（重启保留）；key 为工作区路径，值为已展开的容器路径集合。 */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** 读取某工作区持久化的展开集；无记录时默认只展开工作区根。 */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** 持久化当前工作区的展开集（传新集合副本，SharedPreferences 禁止复用已存实例）。 */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** 正在展开、等待列目录返回的目录路径（远程 SSH 下 listFiles 可能耗时数秒）；UI 在该行显示等待动画。 */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** 正在执行写操作（新建/重命名/删除/复制/移动）的条目路径集合；UI 在对应行与工具栏显示等待动画。 */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** 当前可见文件树；由 [browseCommands] 串行驱动的增量构建更新，不再随展开/折叠整树重读。 */
    private val _browseState = MutableStateFlow<FileBrowseState>(FileBrowseState.Loading)

    /** UI 是否有人在看文件树（[browseState] 的订阅生命周期）；为假时停掉全部目录监听。 */
    @Volatile
    private var browseSubscribed = false

    /**
     * 扁平化的可见文件树。只在侧边栏有订阅时工作：退到后台 5 秒后停掉 inotify 与兜底轮询，
     * 回到前台整树重读一次（后台期间目录可能已被改过）。
     */
    val browseState: StateFlow<FileBrowseState> = _browseState
        .onStart {
            browseSubscribed = true
            browseCommands.trySend(BrowseReload(full = true))
        }
        .onCompletion {
            browseSubscribed = false
            browseCommands.trySend(BrowseReload())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /**
     * 文件树目录列表的缓存（容器路径 → 已去重排序的条目）。文件树只在这里读盘/走网络：
     * 展开一个目录只重读它自己，折叠只按缓存重算可见节点，写操作只重读受影响的父目录。
     *
     * 缓存与监听状态只在 [browseCommandLoop] 所在协程里读写；对外方法只投递命令，不直接碰缓存。
     */
    private val browseListings = HashMap<String, List<FileEntry>>()

    /** 最近一次读取失败的目录 → 原因；成功后移除。行内标红用它，工作区根读不到且没有旧列表时整 tab 报错。 */
    private val browseDirErrors = HashMap<String, String?>()

    /** 已失效、下次访问要重读的目录；[browseDirtyAll] 为真时整树重读（手动刷新、切工作区）。 */
    private val browseDirtyDirs = mutableSetOf<String>()
    private var browseDirtyAll = false

    /** 工作区根 .gitignore 的模式缓存：随工作区根目录一起重读，展开子目录时无需再读。 */
    private var browseIgnorePatterns: List<String> = emptyList()

    /** 已展开目录 → 变更监听协程；展开集变化时按需增删，不整体重挂。 */
    private val browseWatchJobs = HashMap<String, Job>()

    /**
     * 文件树作用域：工作区 / 容器 profile / 执行模式。任一变化，目录内容就是另一批文件，
     * 缓存要丢弃重读（展开集按工作区各自持久化）。
     */
    private data class BrowseScope(val workspace: String, val profileId: String, val mode: ExecutionMode)

    private var browseScope: BrowseScope? = null

    /** 容器根行的显示信息（名称、系统图标）；与 [browseScopeFlow] 同步更新，供命令协程组装节点。 */
    @Volatile
    private var browseContainerRoot = FileTreeRoot.Container(label = "", osId = null, remote = false)

    /** 文件树命令：重读 [paths] 或整树（[full]）后重算可见节点；两者皆空时只按缓存重排（折叠）。 */
    private data class BrowseReload(val paths: Set<String> = emptySet(), val full: Boolean = false)

    private val browseCommands = Channel<BrowseReload>(Channel.UNLIMITED)

    /** 工作区 / 容器 profile / 执行模式，附带容器根节点的显示信息。 */
    private val browseScopeFlow: Flow<Pair<BrowseScope, FileTreeRoot.Container>> = combine(
        _currentWorkspace,
        containerSettingsRepository.activeProfileFlow,
        executionModeHolder.mode,
        containerOsDetector.osMap
    ) { workspace, profile, mode, osMap ->
        BrowseScope(workspace, profile.id, mode) to FileTreeRoot.Container(
            label = profile.name,
            osId = osMap[profile.id],
            remote = mode == ExecutionMode.REMOTE_SSH
        )
    }

    /**
     * 串行执行文件树命令。一轮里排队的命令先合并：同一目录的连续事件只重读一次，整树刷新吞掉其余命令。
     * 读目录本地是阻塞 IO、远程是网络调用，整个循环跑 IO 调度器。
     */
    private suspend fun browseCommandLoop() {
        while (true) {
            val first = browseCommands.receive()
            var full = first.full
            val paths = first.paths.toMutableSet()
            while (true) {
                val next = browseCommands.tryReceive().getOrNull() ?: break
                full = full || next.full
                paths += next.paths
            }
            // 作用域/工作区还没就绪：等 browseScopeFlow 自己的整树命令
            val scope = browseScope ?: continue
            if (scope.workspace.isBlank()) continue
            // 无人订阅（退到后台）：停掉目录监听，下次可见时由 onStart 的整树命令重读
            if (!browseSubscribed) {
                syncBrowseWatches()
                continue
            }
            if (full) {
                browseListings.clear()
                browseDirErrors.clear()
                browseDirtyDirs.clear()
                browseDirtyAll = true
                browseIgnorePatterns = emptyList()
            } else {
                browseDirtyDirs += paths
            }
            publishBrowseNodes()
            val expanding = _expandingPath.value
            if (expanding != null && (full || expanding in paths)) _expandingPath.value = null
        }
    }

    /** 重算一次可见节点并发布；顺带清掉已消失目录的展开状态与监听。 */
    private fun publishBrowseNodes() {
        val scope = browseScope
        var nodes = buildBrowseNodes() ?: return
        if (pruneBrowseExpansion()) nodes = buildBrowseNodes() ?: return
        // 组装期间作用域变了（切工作区/profile）：这批节点对应的是旧范围的目录，丢弃等整树重读
        if (scope != browseScope) return
        _browseState.value = FileBrowseState.Success(nodes)
        pruneBrowseCache()
        syncBrowseWatches()
    }

    /**
     * 按展开集把缓存里的目录条目拼成扁平节点列表，只在缓存缺失或已失效时读目录。
     * 返回 null 表示工作区根没有可用列表（已切到 [FileBrowseState.Error]）。
     */
    private fun buildBrowseNodes(): List<FileTreeNode>? {
        val expanded = _expandedPaths.value
        val workspaceRoot = WorkspacePathMapper.CONTAINER_ROOT
        val rootExpanded = workspaceRoot in expanded
        // 折叠着的工作区根不读目录：只有一行没有内容要显示，展开时才读
        val workspaceEntries = if (rootExpanded) ensureDir(workspaceRoot) else browseListings[workspaceRoot]
        if (rootExpanded && workspaceEntries == null) {
            _browseState.value = FileBrowseState.Error(browseDirErrors[workspaceRoot])
            return null
        }
        val nodes = mutableListOf<FileTreeNode>()
        nodes += rootNode(FileTreeRoot.Workspace, workspaceRoot, expanded, hasError = workspaceRoot in browseDirErrors)
        if (rootExpanded && workspaceEntries != null) {
            appendBrowseChildren(workspaceRoot, workspaceEntries, depth = 1, expanded = expanded, ignorePatterns = browseIgnorePatterns, relParts = emptyList(), out = nodes)
        }
        // 当前容器根：读不到（容器未安装、远程未连接）时只把该行标红，不影响工作区那棵。
        val containerExpanded = CONTAINER_FS_ROOT in expanded
        val containerEntries = if (containerExpanded) ensureDir(CONTAINER_FS_ROOT) else browseListings[CONTAINER_FS_ROOT]
        nodes += rootNode(browseContainerRoot, CONTAINER_FS_ROOT, expanded, hasError = CONTAINER_FS_ROOT in browseDirErrors)
        if (containerExpanded && containerEntries != null) {
            appendBrowseChildren(CONTAINER_FS_ROOT, containerEntries, depth = 1, expanded = expanded, ignorePatterns = emptyList(), relParts = emptyList(), out = nodes)
        }
        // 用户添加的本机文件夹：读不到（目录被删除/未授权）时只把该行标红，不影响其它根。
        for (folder in _localFolders.value) {
            val path = WorkspacePathMapper.toLocalFolderContainerPath(folder.hostPath)
            val open = path in expanded
            val entries = if (open) ensureDir(path) else browseListings[path]
            nodes += rootNode(
                FileTreeRoot.LocalFolder(folder.hostPath), path, expanded,
                hasError = path in browseDirErrors
            )
            if (open && entries != null) {
                appendBrowseChildren(path, entries, depth = 1, expanded = expanded, ignorePatterns = emptyList(), relParts = emptyList(), out = nodes)
            }
        }
        browseDirtyAll = false
        browseDirtyDirs.clear()
        return nodes
    }

    /**
     * 取 [path] 的目录列表：命中缓存且未失效就直接用，否则读一次。
     * 读失败时保留旧列表（若有）并登记原因，返回旧列表；没有旧列表时返回 null。
     */
    private fun ensureDir(path: String): List<FileEntry>? {
        val cached = browseListings[path]
        val dirty = browseDirtyAll || path in browseDirtyDirs
        if (cached != null && !dirty) return cached
        browseDirtyDirs -= path
        runCatching { fileAccess.listFiles(path) }
            .onSuccess { entries ->
                // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
                // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
                browseListings[path] = entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)
                browseDirErrors -= path
                if (path == WorkspacePathMapper.CONTAINER_ROOT) {
                    browseIgnorePatterns = loadRootGitignore(path)
                }
            }
            .onFailure { e ->
                FileLogger.w(TAG, "列目录失败: $path", e)
                browseDirErrors[path] = e.message
            }
        return browseListings[path]
    }

    /** 文件树的一个根节点行；[FileTreeNode.entry] 的 name 同时用作显示名（见 [FileTreeRoot.label]）。 */
    private fun rootNode(root: FileTreeRoot, path: String, expanded: Set<String>, hasError: Boolean = false) = FileTreeNode(
        entry = FileEntry(name = root.label, isDirectory = true, size = 0, lastModified = 0),
        path = path,
        depth = 0,
        root = root,
        isExpanded = path in expanded,
        hasError = hasError
    )

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!fileAccess.exists(path)) return emptyList()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    /** 按展开集递归展开缓存里的目录条目；不在这里读盘，缺失/失效的目录交给 [ensureDir]。 */
    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        for (entry in entries) {
            val path = childPathPrefix(parent) + entry.name
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isExpanded = false, ignored = ignored)
                continue
            }
            val children = ensureDir(path)
            out += FileTreeNode(entry, path, depth, isExpanded = true, hasError = path in browseDirErrors, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    /** [path] 的父目录容器路径；各根节点返回 null。 */
    private fun parentDirOf(path: String): String? = when {
        isBrowseRoot(path) -> null
        else -> {
            val index = path.lastIndexOf('/')
            if (index <= 0) CONTAINER_FS_ROOT else path.substring(0, index)
        }
    }

    /** [path] 是否是文件树的根节点（工作区 / 容器 / 本机文件夹）。 */
    private fun isBrowseRoot(path: String): Boolean =
        path == WorkspacePathMapper.CONTAINER_ROOT || path == CONTAINER_FS_ROOT ||
            _localFolders.value.any { WorkspacePathMapper.toLocalFolderContainerPath(it.hostPath) == path }

    /**
     * 清掉已不在树上的展开路径（目录被删除/改名），否则会一直给不存在的目录挂监听协程。
     * 只有「父目录列表读到且不报错、父目录里确实没有它」才判定已消失；读不到时保守保留。
     */
    private fun pruneBrowseExpansion(): Boolean {
        val expanded = _expandedPaths.value
        val gone = mutableSetOf<String>()
        // 容器根读出空列表通常意味着容器未安装/rootfs 刚建，不能据此判定其子目录都已消失
        val containerRootEmpty = browseListings[CONTAINER_FS_ROOT].isNullOrEmpty()
        // 按层级由浅到深：父目录已消失时子目录一并算消失，不必再依赖父目录列表
        for (path in expanded.sortedBy { p -> p.count { it == '/' } }) {
            if (isBrowseRoot(path)) continue
            val parent = parentDirOf(path) ?: continue
            if (parent in gone) {
                gone += path
                continue
            }
            if (parent == CONTAINER_FS_ROOT && containerRootEmpty) continue
            val listing = browseListings[parent] ?: continue
            if (parent in browseDirErrors) continue
            if (listing.none { it.isDirectory && childPathPrefix(parent) + it.name == path }) gone += path
        }
        if (gone.isEmpty()) return false
        val remaining = expanded - gone
        _expandedPaths.value = remaining
        saveExpansion(_currentWorkspace.value, remaining)
        return true
    }

    /** 缓存只保留当前展开集与全部根，其余（已折叠/已删除目录）丢弃，避免长期占用内存。 */
    private fun pruneBrowseCache() {
        val keep = _expandedPaths.value + WorkspacePathMapper.CONTAINER_ROOT + CONTAINER_FS_ROOT +
            _localFolders.value.map { WorkspacePathMapper.toLocalFolderContainerPath(it.hostPath) }
        browseListings.keys.retainAll(keep)
        browseDirErrors.keys.retainAll(keep)
    }

    /** 按展开集增删目录监听：只新开/关闭变化的那几个，不重挂全部（否则每次点开都重建所有 inotify）。 */
    private fun syncBrowseWatches() {
        val scope = browseScope ?: return
        // 无人订阅或远程模式（没有宿主目录可监听）：不挂任何监听
        val wanted = if (!browseSubscribed || scope.mode == ExecutionMode.REMOTE_SSH) {
            emptySet()
        } else {
            _expandedPaths.value + WorkspacePathMapper.CONTAINER_ROOT
        }
        for ((path, job) in browseWatchJobs.toList()) {
            if (path !in wanted || !job.isActive) {
                job.cancel()
                browseWatchJobs.remove(path)
            }
        }
        for (path in wanted) {
            if (path in browseWatchJobs) continue
            browseWatchJobs[path] = viewModelScope.launch(Dispatchers.IO) {
                fileChangeHub.watchWorkspace(path).asDirtySignal()
                    .debounce(BROWSE_DEBOUNCE_MS)
                    .collect { browseCommands.send(BrowseReload(paths = setOf(path))) }
            }
        }
    }

    /** 手动刷新：丢弃全部目录缓存后整树重读；远程模式没有 inotify，这是唯一的新鲜度来源。 */
    fun refreshBrowse() {
        browseCommands.trySend(BrowseReload(full = true))
    }

    /** 文件树「+」选择本机文件夹：登记为根并整树重读；重复添加同一目录视为成功。 */
    fun addLocalFolder(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val added = withContext(Dispatchers.IO) {
            val hostPath = UriPathResolver.toFilePath(context, uri) ?: return@withContext false
            val normalized = WorkspacePathMapper.normalizeLocalFolderHostPath(hostPath)
            if (_localFolders.value.any { it.hostPath == normalized }) return@withContext true
            val dir = File(normalized)
            if (!dir.isDirectory || !dir.canRead()) return@withContext false
            val entry = LocalFolderPref(normalized, uri.toString())
            WorkspacePathMapper.registerLocalFolder(normalized)
            saveLocalFolders(_localFolders.value + entry)
            _localFolders.value = _localFolders.value + entry
            true
        }
        if (added) browseCommands.trySend(BrowseReload(full = true))
        onResult(added)
    }

    /** 从文件树移除本机文件夹根：只解除关联，不删除目录内容。 */
    fun removeLocalFolder(hostPath: String) = viewModelScope.launch {
        val normalized = WorkspacePathMapper.normalizeLocalFolderHostPath(hostPath)
        val removed = _localFolders.value.firstOrNull { it.hostPath == normalized } ?: return@launch
        val updated = _localFolders.value - removed
        WorkspacePathMapper.unregisterLocalFolder(normalized)
        saveLocalFolders(updated)
        _localFolders.value = updated
        val rootPath = WorkspacePathMapper.toLocalFolderContainerPath(normalized)
        val expanded = _expandedPaths.value
            .filterNot { it == rootPath || it.startsWith("$rootPath/") }
            .toSet()
        _expandedPaths.value = expanded
        saveExpansion(_currentWorkspace.value, expanded)
        browseCommands.trySend(BrowseReload(full = true))
    }

    /** 让指定目录失效并在下一轮重读（写操作后只刷新受影响的父目录）。 */
    private fun refreshBrowseDirs(paths: Set<String>) {
        if (paths.isEmpty()) return
        browseCommands.trySend(BrowseReload(paths = paths))
    }

    /**
     * 展开/折叠目录；折叠时连同其所有后代一并移出展开集，避免残留监听与再展开时意外深开。改变后按工作区持久化。
     * 展开只重读该目录本身（其它已展开目录走缓存），折叠只按缓存重排可见节点、不读盘。
     * 展开需等列目录返回，期间标记 [expandingPath] 让 UI 显示等待动画，并忽略对同一目录的重复点击（否则会把它折回去）。
     */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated: Set<String>
        if (path in current) {
            updated = current.filterNot { it == path || it.startsWith(childPathPrefix(path)) }.toSet()
        } else {
            _expandingPath.value = path
            updated = current + path
        }
        // 先落展开集再投命令：命令协程按展开集决定要读哪些目录
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
        browseCommands.trySend(BrowseReload(paths = setOf(path)))
    }

    /** 重命名/移动目录后把展开状态跟到新路径上，避免已展开的子树莫名收起、旧路径继续挂着监听。 */
    private fun remapExpandedPath(oldPath: String, newPath: String) {
        val current = _expandedPaths.value
        if (current.none { it == oldPath || it.startsWith(childPathPrefix(oldPath)) }) return
        val updated = current.map {
            when {
                it == oldPath -> newPath
                it.startsWith(childPathPrefix(oldPath)) -> newPath + it.removePrefix(oldPath)
                else -> it
            }
        }.toSet()
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /**
     * 文件浏览的写操作共用包装：跑 IO 调度器，成功后只重读受影响的父目录（远程模式无 inotify）。
     * [busyPaths] 为本次操作涉及的条目路径，操作期间加入 [fileOpPaths] 让 UI 显示等待动画。
     * [block] 返回 false 表示名称非法或同名已存在，抛异常表示 IO 失败，两者均回报失败。
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "文件操作失败", it) }
                    .getOrDefault(false)
            }
            if (success) refreshBrowseDirs(busyPaths.mapNotNull { parentDirOf(it) }.toSet())
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** [parent] 目录下的子路径；名称非法时返回 null。 */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) childPathPrefix(parent) + name.trim() else null

    /** 容器路径下的子路径前缀：容器根 `/` 下为 `/`，其余为 `<path>/`。拼接子路径与折叠时连带删后代都用它，避免出现 `//name`。 */
    private fun childPathPrefix(path: String): String = if (path.endsWith("/")) path else "$path/"

    /** 在 [parent] 目录新建空文件。 */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** 在 [parent] 目录新建文件夹。 */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** 重命名条目（仅同目录内改名，不跨目录移动）。 */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                val target = "$parent/${newName.trim()}"
                fileAccess.rename(path, target)
                remapExpandedPath(path, target)
                true
            }
        }

    /** 删除条目；目录连同内容递归删除。 */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    // region 文件复制 / 剪切 / 粘贴

    /** 文件浏览剪切板：复制或剪切后暂存源条目，供粘贴到其它目录。 */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** 复制条目进剪切板（剪切板只能存一项，直接覆盖旧的）。 */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** 剪切条目进剪切板（粘贴成功后删除源，且只允许粘贴一次）。 */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** 清空剪切板。 */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** 粘贴冲突（目标已存在同名项）待用户确认覆盖。持有源路径与目标目录，确认后调 [pasteBrowseEntryOverwrite]。 */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** 把剪切板内容粘贴到 [targetDir]。目标已存在同名项时，发 [pasteConflict] 让 UI 弹窗询问是否覆盖，
     *  不执行粘贴；否则直接粘贴。粘贴成功即清空剪切板（无论复制/剪切，一次粘贴后失效），失败保留供重试。 */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** 用户确认覆盖后强制粘贴（目标已存在同名项也会覆盖）；若当前待确认的是上传，则执行上传。 */
    fun pasteBrowseEntryOverwrite() {
        pendingUploadAction?.let { upload ->
            pendingUploadAction = null
            _pasteConflict.value = null
            upload(true)
            return
        }
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** 清空粘贴/上传的覆盖待确认状态（用户点了「取消」时）。 */
    fun clearPasteConflict() {
        pendingUploadAction = null
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // 无论复制还是剪切，粘贴成功即清空剪切板，避免重复粘贴；失败保留，允许重试。
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            val sourceAccess = fileAccess.backendFor(clip.sourcePath)
            val targetAccess = fileAccess.backendFor(target)
            if (sourceAccess === targetAccess) {
                if (clip.isCut) {
                    sourceAccess.move(clip.sourcePath, target, overwrite)
                    remapExpandedPath(clip.sourcePath, target)
                } else {
                    sourceAccess.copy(clip.sourcePath, target, overwrite)
                }
            } else {
                // 本机文件夹 ↔ 容器/远程：两端不是同一个文件系统，逐项读写中转。
                copyAcrossBackends(sourceAccess, clip.sourcePath, targetAccess, target, overwrite)
                if (clip.isCut) {
                    sourceAccess.deleteRecursively(clip.sourcePath)
                    remapExpandedPath(clip.sourcePath, target)
                }
            }
            true
        }
    }

    /**
     * 跨后端复制（本机文件夹 ↔ 容器/远程）：目录递归，文件经本地临时文件中转——
     * [FileAccessProvider.copyToLocal] 在本地返回宿主文件、在远程下载到缓存，两端都不用整份进内存。
     */
    private fun copyAcrossBackends(
        source: FileAccessProvider,
        sourcePath: String,
        target: FileAccessProvider,
        targetPath: String,
        overwrite: Boolean
    ) {
        if (source.isDirectory(sourcePath)) {
            if (target.exists(targetPath)) {
                if (!overwrite) throw FileAlreadyExistsException(File(targetPath))
                target.deleteRecursively(targetPath)
            }
            target.mkdirs(targetPath)
            for (entry in source.listFiles(sourcePath)) {
                copyAcrossBackends(
                    source, childPathPrefix(sourcePath) + entry.name,
                    target, childPathPrefix(targetPath) + entry.name,
                    overwrite
                )
            }
        } else {
            val local = source.copyToLocal(sourcePath)
            local.inputStream().use { target.writeStream(targetPath, it, overwrite) }
        }
    }

    // endregion

    // region 上传（系统选择器 → 当前目录）

    /** 待上传的单个文件：URI 已解析出显示名。 */
    private data class UploadFile(val uri: Uri, val name: String)

    /** 覆盖确认后要执行的上传动作；与粘贴冲突共用同一个确认弹窗。只在主线程读写。 */
    private var pendingUploadAction: ((Boolean) -> Unit)? = null

    /** 上传本机文件（系统文件选择器多选）到 [targetDir]；同名项已存在时先弹覆盖确认。 */
    fun uploadBrowseFiles(targetDir: String, uris: List<Uri>, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch onResult(false)
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                displayNameOf(uri)?.takeIf { isValidFileEntryName(it) }?.let { UploadFile(uri, it) }
            }
        }
        if (files.isEmpty()) return@launch onResult(false)
        val conflict = withContext(Dispatchers.IO) {
            files.firstOrNull { fileAccess.exists(childPathPrefix(targetDir) + it.name) }
        }
        val upload: (Boolean) -> Unit = { overwrite ->
            performUpload(targetDir, files, folder = null, overwrite = overwrite, onResult = onResult)
        }
        if (conflict != null) {
            pendingUploadAction = upload
            _pasteConflict.value = conflict.name to childPathPrefix(targetDir) + conflict.name
        } else {
            upload(false)
        }
    }

    /** 上传本机文件夹（系统目录选择器）到 [targetDir]；同名目录已存在时先弹覆盖确认。 */
    fun uploadBrowseFolder(targetDir: String, treeUri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val folder = withContext(Dispatchers.IO) {
            UriPathResolver.toFilePath(context, treeUri)
                ?.let { File(it) }
                ?.takeIf { it.isDirectory && it.canRead() }
        }
        if (folder == null || folder.name.isBlank()) return@launch onResult(false)
        val target = childPathPrefix(targetDir) + folder.name
        val exists = withContext(Dispatchers.IO) { fileAccess.exists(target) }
        val upload: (Boolean) -> Unit = { overwrite ->
            performUpload(targetDir, emptyList(), folder, overwrite, onResult)
        }
        if (exists) {
            pendingUploadAction = upload
            _pasteConflict.value = folder.name to target
        } else {
            upload(false)
        }
    }

    private fun performUpload(
        targetDir: String,
        files: List<UploadFile>,
        folder: File?,
        overwrite: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val busyPaths = files.map { childPathPrefix(targetDir) + it.name }.toSet() +
            listOfNotNull(folder?.let { childPathPrefix(targetDir) + it.name })
        mutateBrowse(busyPaths = busyPaths, onResult = onResult) {
            for (file in files) {
                val target = childPathPrefix(targetDir) + file.name
                val input = context.contentResolver.openInputStream(file.uri) ?: return@mutateBrowse false
                input.use { fileAccess.writeStream(target, it, overwrite) }
            }
            if (folder != null) uploadHostDirectory(folder, childPathPrefix(targetDir) + folder.name, overwrite)
            true
        }
    }

    /** 递归把宿主目录写到 [targetDir]；目标在远程模式下经 SFTP 上传。 */
    private fun uploadHostDirectory(source: File, targetDir: String, overwrite: Boolean) {
        fileAccess.mkdirs(targetDir)
        source.listFiles().orEmpty().forEach { child ->
            val target = childPathPrefix(targetDir) + child.name
            if (child.isDirectory) {
                uploadHostDirectory(child, target, overwrite)
            } else {
                child.inputStream().use { fileAccess.writeStream(target, it, overwrite) }
            }
        }
    }

    /** 系统选择器 URI 的显示名；取不到时回退到路径末段。 */
    private fun displayNameOf(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) return cursor.getString(0)
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    // endregion

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** 当前会话的持续目标（goal）快照；null 表示未设定目标。 */
    val currentSessionGoal: StateFlow<SessionGoalUi?> = currentSessionState
        .map { s ->
            s?.goal?.takeIf { it.isNotBlank() }?.let {
                SessionGoalUi(it, s.goalStatus ?: GoalStatus.ACTIVE, s.goalTurnCount)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 「思考自动展开」偏好：没有手动展开/收起记录的思考卡片默认是否展开正文。 */
    val autoExpandReasoning: StateFlow<Boolean> = generalSettingsRepository.autoExpandReasoningFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false,
                    limit = limit
                )
            }.flowOn(Dispatchers.Default)
                .onEach { loadedMessageLimits[id] = limit }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    val runningTool: StateFlow<List<RunningToolOutput>> = combine(_currentSessionId, agentRunManager.runningTools) { id, tools ->
        if (id == null) emptyList() else tools[id]?.values?.toList() ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val preparingTool: StateFlow<String?> = combine(_currentSessionId, agentRunManager.preparingTools) { id, tools ->
        if (id == null) null else tools[id]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = agentRunManager.contextUsages

    val llmCallEvents: SharedFlow<LlmCallEvent> = agentRunManager.llmCallEvents

    val isCompacting: StateFlow<Boolean> = combine(_currentSessionId, agentRunManager.compactingSessions) { id, compacting ->
        compacting[id] == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val streamingAssistant: StateFlow<StreamingAssistantMessage?> = combine(
        _currentSessionId, agentRunManager.streamingAssistants
    ) { id, assistants ->
        if (id == null) null else assistants[id]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val retryState: StateFlow<RetryState?> = combine(_currentSessionId, agentRunManager.retryStates) { id, states ->
        if (id == null) null else states[id]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val keySwitchState: StateFlow<KeySwitchState?> = combine(_currentSessionId, agentRunManager.keySwitchStates) { id, states ->
        if (id == null) null else states[id]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 当前展示的计划审批面板所属会话标题（多会话并行时供面板标注归属，避免批错会话）。 */
    val pendingPlanApprovalSessionTitle: StateFlow<String> = combine(
        planApprovalManager.pendingApproval,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    val queuedRequests: StateFlow<List<QueuedRequest>> = combine(_currentSessionId, agentRunManager.queuedRequests) { id, queues ->
        if (id == null) emptyList() else queues[id] ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    /** 当前正阻塞等待用户审批的会话 id 集合（工具授权 / 提问 / 退出计划审批）。 */
    val approvalSessionIds: StateFlow<Set<String>> = combine(
        awaitingPermissionSessionIds,
        askUserQuestionManager.pendingQuestion,
        planApprovalManager.pendingApproval
    ) { awaiting, question, plan ->
        buildSet {
            addAll(awaiting)
            question?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
            plan?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return agentRunManager.isSessionRunning(sid)
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean =
        agentRunManager.hasActiveSessions(
            sessions.value.map { it.id } + subSessionsByParent.value.values.flatten().map { it.id }
        )

    private companion object {
        const val TAG = "AIAgentViewModel"

        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 写文件会连珠触发多个 inotify 事件，合并后再重读目录。 */
        const val BROWSE_DEBOUNCE_MS = 300L

        /** 会话目标文本长度上限，避免 system 提示词过长。 */
        const val MAX_GOAL_CHARS = 2000

        /** 当前容器在容器内的根路径，对应宿主上的 rootfs 目录（随当前 profile 变化）。 */
        const val CONTAINER_FS_ROOT = "/"

        /** 文件树本机文件夹根的持久化 key 与编解码器。 */
        const val LOCAL_FOLDERS_KEY = "folders"
        val localFolderJson = Json { ignoreUnknownKeys = true }
    }

    private fun readLocalFolders(): List<LocalFolderPref> {
        val raw = localFolderPrefs.getString(LOCAL_FOLDERS_KEY, null) ?: return emptyList()
        return runCatching { localFolderJson.decodeFromString<List<LocalFolderPref>>(raw) }
            .getOrElse {
                FileLogger.w(TAG, "本机文件夹记录解析失败", it)
                emptyList()
            }
    }

    private fun saveLocalFolders(folders: List<LocalFolderPref>) {
        localFolderPrefs.edit()
            .putString(LOCAL_FOLDERS_KEY, localFolderJson.encodeToString(folders))
            .apply()
    }

    init {
        // 恢复文件树添加的本机文件夹映射（宿主路径 → 显示路径的反向还原用）
        _localFolders.value.forEach { WorkspacePathMapper.registerLocalFolder(it.hostPath) }

        // 文件树：命令串行执行（读目录 + 拼节点）；作用域变化时整树重读，只变容器名/图标时仅重排行。
        viewModelScope.launch(Dispatchers.IO) { browseCommandLoop() }
        viewModelScope.launch(Dispatchers.IO) {
            browseScopeFlow.collect { (scope, root) ->
                val rootChanged = root != browseContainerRoot
                browseContainerRoot = root
                if (scope == browseScope) {
                    // 只有容器名/图标变了：重排行即可，不重读目录
                    if (rootChanged) browseCommands.send(BrowseReload())
                    return@collect
                }
                browseScope = scope
                // 切到新工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根），旧缓存整体作废。
                _expandedPaths.value = loadExpansion(scope.workspace)
                _browseState.value = FileBrowseState.Loading
                browseCommands.send(BrowseReload(full = true))
            }
        }

        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 启动与工作区切换时重新扫描技能（项目级技能随工作区变化），刷新 `/` 命令菜单。
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // Activity 被回收重建时，若原会话仍在运行，优先回到它，而不是跳去新会话。
                val saved = sessionSelectionStore.load()
                val savedId = saved.first?.takeIf { saved.second == path }?.takeIf {
                    sessionUseCase.getSessionById(it) != null
                }
                val runningSaved = savedId?.takeIf { agentRunManager.isSessionRunning(it) }
                // 立即确定并设置当前会话，确保首帧 UI 秒开、侧边栏立即出现新会话，彻底消除转圈卡顿。
                // 「打开最近会话」有历史就直接进最近那个（按 updatedAt，不受置顶影响）；
                // 「新开会话」复用还没发过消息的空会话（避免每次启动都堆一个空会话），其余情况新建。
                val targetId = when {
                    runningSaved != null -> runningSaved
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> savedId ?: recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // 异步回收多余的空会话（保留当前 targetId），在后台执行，不卡主线程与首帧渲染。
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // 记住当前会话：Activity 被回收重建后按它恢复。
        viewModelScope.launch {
            combine(_currentSessionId, _currentWorkspace) { id, ws -> id to ws }
                .collect { (id, ws) ->
                    if (id != null && ws.isNotBlank()) sessionSelectionStore.save(id, ws)
                }
        }

        // 运行器已接管执行与通知订阅；聊天页只转发 UI 请求、承接运行态里的 UI 副作用。
        agentRunManager.start()
        viewModelScope.launch {
            agentRunManager.uiRequests.collect { request -> handleUiRequest(request) }
        }
        viewModelScope.launch {
            agentRunManager.reasoningDurationEvents.collect { event ->
                reasoningDurations[event.messageId] = event.durationMs
            }
        }
    }

    /** 消费插件会话请求：发送/停止由运行器处理，切会话/写草稿/删除仍由聊天页执行。 */
    private suspend fun handleUiRequest(request: AgentChatRequest) {
        when (request) {
            is AgentChatRequest.Select -> selectSession(request.sessionId)
            is AgentChatRequest.SetDraft -> {
                val target = request.sessionId ?: _currentSessionId.value ?: ensureSession()
                if (target.isBlank() || sessionUseCase.getSessionById(target) == null) return
                _currentSessionId.value = target
                val next = if (request.append) {
                    val existing = _inputDrafts.value[target].orEmpty()
                    if (existing.isBlank()) request.text else existing.trimEnd() + "\n\n" + request.text
                } else {
                    request.text
                }
                updateInputDraft(next)
            }
            is AgentChatRequest.Delete -> deleteSession(request.sessionId)
            is AgentChatRequest.Send, is AgentChatRequest.Stop -> Unit
        }
    }









    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        targetSessionId: String? = null
    ) {
        agentRunManager.enqueueAgentRequest(
            request = request,
            modelRequest = modelRequest,
            currentFile = currentFile,
            selectedCode = selectedCode,
            projectRoot = projectRoot,
            inputImages = inputImages,
            inputAttachments = inputAttachments,
            isAutoTrigger = isAutoTrigger,
            persistUserMessage = persistUserMessage,
            targetSessionId = targetSessionId ?: _currentSessionId.value
        )
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        _currentSessionId.value?.let { agentRunManager.removeQueuedRequest(it, id) }
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        _currentSessionId.value?.let { agentRunManager.moveQueuedRequest(it, fromIndex, toIndex) }
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        _currentSessionId.value?.let { agentRunManager.updateQueuedRequest(it, id, newText) }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * 运行器的统一分发——忙碌时搭车注入本批工具结果，空闲时作为新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        _currentSessionId.value?.let { agentRunManager.interjectQueuedRequest(it, id) }
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再按类型派发——
     * 内置命令走本地动作，技能则把其正文作为本轮指令触发 agent 回合。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            agentRunManager.markCommandStarted(sessionId)
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                agentRunManager.onCommandFinished(sessionId)
            }
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        agentRunManager.stopAllAgents()
    }

    /**
     * 主动打断当前会话正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止/用户手动停止共用）。
     * 取消 job 并把未完成的流式内容落库为「已停止」；队列下一条照常执行。
     */
    fun stopAgentSession(sessionId: String) {
        agentRunManager.stopAgentSession(sessionId)
    }

    // region 会话管理

    /**
     * 清掉一个会话的全部运行时状态（并发名额、通知队列、待发请求、job、流式/工具占位）。
     * UI 删除与 task(action="del") 共用的收尾：不清的话，取消中 job 的 finally 会对着
     * 已删除的会话补送通知或执行队列下一条——白烧一轮 LLM、往不存在的会话落孤儿消息。
     */
    private fun clearSessionRuntimeState(sessionId: String) {
        agentRunManager.clearSessionRuntimeState(sessionId)
        _inputDrafts.value = _inputDrafts.value - sessionId
        draftPrefs.edit().remove(sessionId).apply()
    }

    /** 新建会话；工作区里已有空会话时直接切过去复用（当前会话为空则优先），避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return@launch
        val curId = _currentSessionId.value
        val reusableId = if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            curId
        } else {
            sessionUseCase.getEmptySessionOfWorkspace(ws)?.id
        }
        if (reusableId != null) {
            _currentSessionId.value = reusableId
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(ws)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // 工作期间切换：把新模式作为系统事件投递给运行中的 agent，本轮即时生效（见 StatefulAgentWorkflow）。
            // 连续切换时队列只保留最新一次，AI 只看到最终模式而非中间状态。
            // 空闲时无需投递——下一轮开轮会按新模式注入一次提醒。
            if (agentRunManager.isSessionRunning(sid)) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            agentRunManager.clearContextUsage(sid)
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 输入框 `@` 文件引用菜单状态。 */
    private val _fileMentionState = MutableStateFlow<FileMentionState>(FileMentionState.Idle)
    val fileMentionState: StateFlow<FileMentionState> = _fileMentionState.asStateFlow()

    private var fileMentionJob: Job? = null

    /**
     * 打开 `@` 菜单：当前无清单时拉取一次；一次 `@` 会话内（[dismissFileMention] 前）不重复拉取。
     */
    fun requestFileMention() {
        val current = _fileMentionState.value
        if (current is FileMentionState.Ready || current is FileMentionState.Loading) return
        fileMentionJob?.cancel()
        _fileMentionState.value = FileMentionState.Loading
        fileMentionJob = viewModelScope.launch {
            val result = fileMentionRepository.loadFiles(_currentWorkspace.value)
            _fileMentionState.value = when (result) {
                is FileMentionRepository.LoadResult.Success -> FileMentionState.Ready(result.files)
                FileMentionRepository.LoadResult.Unavailable -> FileMentionState.Unavailable
                is FileMentionRepository.LoadResult.Failed -> FileMentionState.Error(result.message)
            }
        }
    }

    /** 关闭 `@` 菜单：丢弃清单，下次打开重新拉取，保证拿到最新文件列表。 */
    fun dismissFileMention() {
        fileMentionJob?.cancel()
        fileMentionJob = null
        _fileMentionState.value = FileMentionState.Idle
    }

    /** 在已就绪的清单里匹配当前查询；未就绪返回空。 */
    fun matchFileMentions(query: String): List<String> {
        val files = (_fileMentionState.value as? FileMentionState.Ready)?.files ?: return emptyList()
        return fileMentionRepository.match(files, query)
    }

    /** 暴露给 UI：输入框 `/` 菜单展示的命令列表（内置命令 + 已启用技能）。 */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** 重新扫描技能并刷新命令菜单；进入 `/` 菜单时调用，反映技能的增删改。 */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init —— 触发 agent 回合，分析代码库并生成/改进项目规则文件。 */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        enqueueAgentRequest(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        enqueueAgentRequest(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| 项目 | 今日 | 累计 |")
                appendLine("|---|---|---|")
                appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
                appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    /** /goal —— 管理当前会话的持续目标。 */
    override fun setSessionGoal(rawArgs: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = sessionUseCase.getSessionById(sid) ?: return@launch
            val arg = rawArgs.trim()
            when {
                arg.isEmpty() -> persistGoalNotice(sid, goalSummary(session))
                arg.equals("clear", ignoreCase = true) || arg.equals("off", ignoreCase = true) -> clearGoal(sid)
                arg.equals("pause", ignoreCase = true) -> pauseGoal(sid)
                arg.equals("resume", ignoreCase = true) -> resumeGoal(sid)
                else -> setGoalText(sid, session, arg)
            }
        }
    }

    /** UI：暂停/恢复当前会话的 goal 自主循环。 */
    fun setGoalPaused(paused: Boolean) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch { if (paused) pauseGoal(sid) else resumeGoal(sid) }
    }

    /** UI：清除当前会话的 goal。 */
    fun clearGoal() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch { clearGoal(sid) }
    }

    private fun goalSummary(session: ChatSessionEntity): String {
        val goal = session.goal?.takeIf { it.isNotBlank() }
            ?: return "当前会话未设定目标。用 `/goal <目标>` 设定后，AI 会自主循环推进直到达成、受阻或预算耗尽。"
        val status = session.goalStatus
            ?.let { runCatching { GoalStatus.valueOf(it) }.getOrNull() }
            ?: GoalStatus.ACTIVE
        val statusLabel = when (status) {
            GoalStatus.ACTIVE -> "进行中"
            GoalStatus.PAUSED -> "已暂停"
            GoalStatus.COMPLETED -> "已完成"
            GoalStatus.BLOCKED -> "受阻"
            GoalStatus.EXHAUSTED -> "预算耗尽"
        }
        return "当前目标（$statusLabel，已续跑 ${session.goalTurnCount} 轮）：\n$goal"
    }

    private suspend fun setGoalText(sid: String, session: ChatSessionEntity, text: String) {
        val goalText = text.trim().take(MAX_GOAL_CHARS)
        if (goalText.isEmpty()) return
        agentRunManager.resetGoalEmptyTurns(sid)
        sessionUseCase.setGoal(sid, goalText, session.totalInputTokens, session.totalOutputTokens)
        // goal 与 PLAN 互斥：自主循环期间 PLAN 退出门禁需人工审查，故设定 goal 时若处于 PLAN 则强制退出。
        if (runCatching { AgentMode.valueOf(session.mode) }.getOrNull() == AgentMode.PLAN) {
            sessionUseCase.updateMode(sid, AgentMode.BUILD.name)
        }
        persistGoalNotice(
            sid,
            "已设定目标：$goalText\nAI 将自主循环推进（工具自动放行、PLAN 停用），直到达成、受阻或预算耗尽。" +
                "可随时 `/goal pause` / `/goal resume` / `/goal clear`。"
        )
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goalText, 1))
    }

    private suspend fun clearGoal(sid: String) {
        agentRunManager.resetGoalEmptyTurns(sid)
        sessionUseCase.setGoal(sid, null, 0, 0)
        persistGoalNotice(sid, "已清除当前目标。")
    }

    private suspend fun pauseGoal(sid: String) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        if (session.goal.isNullOrBlank()) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        sessionUseCase.updateGoalStatus(sid, GoalStatus.PAUSED)
        persistGoalNotice(sid, "目标已暂停。用 `/goal resume` 继续。")
    }

    private suspend fun resumeGoal(sid: String) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        val goal = session.goal?.takeIf { it.isNotBlank() }
        if (goal == null) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        sessionUseCase.updateGoalStatus(sid, GoalStatus.ACTIVE)
        persistGoalNotice(sid, "目标已恢复，继续自主循环。")
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

    /** 把一轮 goal 续跑/启动请求交给会话：空闲则立即执行，忙则入队（用户消息优先）。不落库。 */
    private fun enqueueGoalTurn(sessionId: String, workspacePath: String, prompt: String) {
        enqueueAgentRequest(
            request = prompt,
            modelRequest = prompt,
            projectRoot = workspacePath,
            isAutoTrigger = true,
            persistUserMessage = false,
            targetSessionId = sessionId
        )
    }

    private fun buildGoalPrompt(goal: String, turn: Int): String =
        "【目标自主循环 · 第 $turn 轮】继续推进目标：$goal\n" +
            "按系统提示中的完成审计与受阻审计规则决定是否调用 goalStatus；未达成则直接继续下一步。"

    private suspend fun persistGoalNotice(sid: String, text: String) {
        sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
        messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, text, isContextExcluded = true)
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        agentRunManager.compactSession(sid)
    }

    /**
     * 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。
     * 绑定请求 id：多会话下面板展示的可能是较早挂起的请求，隐式取队列头会批错对象；
     * 过期 id（已被通知路径解决）在 Manager 侧是 no-op。
     */
    fun approvePlanAndBuild(id: String) {
        planApprovalManager.resolve(id, PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。绑定请求 id，理由同 [approvePlanAndBuild]。 */
    fun refinePlan(id: String) {
        planApprovalManager.resolve(id, PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            clearSessionRuntimeState(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // 删掉运行中的子代理时先取它的父会话与标题（删库后就取不到了），稍后告知父代理。
        // 删的是父会话时无需通知（父会话本身也没了），但级联删掉的子会话仍要交回并发槽位。
        val deletedSession = sessionUseCase.getSessionById(id)
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            clearSessionRuntimeState(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            agentRunManager.notifyParentSubAgentStopped(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    /**
     * 最近一次「回滚代码」因文件已被其他会话/用户改过而跳过的文件清单（逻辑路径）。
     *
     * 用一次性状态而不是事件流：UI 消费后调用 [clearRewindConflicts] 置空，避免重组时重复提示。
     */
    private val _rewindConflicts = MutableStateFlow<List<String>>(emptyList())
    val rewindConflicts: StateFlow<List<String>> = _rewindConflicts.asStateFlow()

    /** UI 已展示过冲突提示，清空以免重复弹。 */
    fun clearRewindConflicts() {
        _rewindConflicts.value = emptyList()
    }

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队，清空运行态
        agentRunManager.suspendSessionForRewind(sessionId)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (targetMsgEntity.isCompactionMarker || targetMsgEntity.isContextSummary) return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    /**
     * 从指定消息处分叉新会话：复制该消息（含）之前的全部消息，标题「Fork of 原标题」，
     * 复制 provider/model/mode/思考档位/目标等配置，token 计数按复制消息重算，不复制
     * checkpoint/todo/LLM 调用记录（分叉是干净副本），完成后切换到新会话。
     *
     * 截断点按 timestamp 取前缀，与 rewind 同口径；被上下文压缩吞掉的旧消息 UI 不展示、
     * 选不中，分叉点必在压缩摘要之后，摘要行按时间戳自然随副本带走，回放上下文完整。
     */
    fun forkFromMessage(messageId: String) = viewModelScope.launch {
        val sourceId = _currentSessionId.value ?: return@launch
        try {
            val target = agentMessageDao.getMessageById(messageId) ?: return@launch
            if (target.sessionId != sourceId) return@launch
            if (target.isCompactionMarker || target.isContextSummary) return@launch
            val source = chatSessionDao.getById(sourceId) ?: return@launch

            val copied = trimIncompleteTurnTail(
                agentMessageDao.getMessagesBySessionOnce(sourceId).filter { it.timestamp <= target.timestamp }
            )
            if (copied.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            val fork = source.copy(
                id = UUID.randomUUID().toString(),
                title = context.getString(R.string.chat_fork_title, source.title),
                createdAt = now,
                updatedAt = now,
                goalTurnCount = 0,
                goalStartInputTokens = 0,
                goalStartOutputTokens = 0,
                totalInputTokens = copied.sumOf { it.inputTokens },
                totalOutputTokens = copied.sumOf { it.outputTokens },
                lastInputTokens = 0,
                isPinned = false,
                parentId = null,
                subagentType = null
            )
            chatSessionDao.upsert(fork)
            agentMessageDao.insertAll(
                copied.map { it.copy(id = UUID.randomUUID().toString(), sessionId = fork.id) }
            )
            selectSession(fork.id)
        } catch (e: Exception) {
            FileLogger.e(TAG, "分叉会话失败", e)
        }
    }

    /**
     * 截齐尾部不完整的轮次：末尾是 TOOL 行（配对的 assistant 工具调用被截走）或带
     * toolCalls 的 ASSISTANT 行（工具结果在截断点之后）时从末尾逐行丢弃，保证副本
     * 以完整轮次收尾——否则回放时出现悬挂 tool_use，Anthropic 等端点会直接 400。
     */
    private fun trimIncompleteTurnTail(messages: List<AgentMessageEntity>): List<AgentMessageEntity> {
        var result = messages
        while (result.isNotEmpty()) {
            val last = result.last()
            val incomplete = last.role == MessageRole.TOOL.name ||
                (last.role == MessageRole.ASSISTANT.name && !last.toolCallsJson.isNullOrBlank())
            if (!incomplete) break
            result = result.dropLast(1)
        }
        return result
    }

}