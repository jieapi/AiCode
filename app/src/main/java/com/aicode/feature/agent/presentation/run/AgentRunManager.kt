package com.aicode.feature.agent.presentation.run

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.toUserMessage
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentDigest
import com.aicode.feature.agent.domain.subagent.SubAgentDigester
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.session.AgentChatRequest
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.COMPACTION_FAILURE_TOOL_NAME
import com.aicode.feature.agent.presentation.KeySwitchState
import com.aicode.feature.agent.presentation.LlmCallEvent
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.WorkflowStatus
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.agent.presentation.RetryState
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.StreamingAssistantMessage
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 一轮思考耗时：UI 内存态，由聊天页收到后写入自己的 map。 */
data class ReasoningDurationEvent(val messageId: String, val durationMs: Long)

/**
 * 进程级 AI 执行器：承载 agent 运行态与执行协程，不随 Activity 重建销毁。
 *
 * 聊天页在 Activity 重建后通过 ViewModel 重新订阅这里的 StateFlow，接上进行中的流式输出与工具进度；
 * 进程被杀时运行态随之丢失（不在本类处理）。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class AgentRunManager @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val agentChatRequestBus: AgentChatRequestBus,
    private val agentRunStateStore: AgentRunStateStore,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val terminalSessionProvider: TerminalSessionProvider,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val liveNotificationSettings: LiveNotificationSettingsRepository,
    private val agentLiveNotificationManager: AgentLiveNotificationManager,
    private val approvalNotificationSettings: ApprovalNotificationSettingsRepository,
    private val agentApprovalNotificationManager: AgentApprovalNotificationManager,
    private val subAgentEventBus: SubAgentEventBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sessionJobs = mutableMapOf<String, Job>()
    private val chatRequestConsumerId = UUID.randomUUID().toString()
    private val goalEmptyTurnCounts = mutableMapOf<String, Int>()

    /** 工具调用传入参数（argsPreview）按落库消息 id 暂存。 */
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    @Volatile
    private var userKeepaliveEnabled = false

    private var wakeLockRenewJob: Job? = null

    @Volatile
    private var started = false

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    private val _subAgentCards = MutableStateFlow<Map<String, SubAgentCardState>>(emptyMap())
    val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = _subAgentCards.asStateFlow()

    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTools: StateFlow<Map<String, Map<String, RunningToolOutput>>> = _runningTools.asStateFlow()

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())
    val preparingTools: StateFlow<Map<String, String>> = _preparingTools.asStateFlow()

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val compactingSessions: StateFlow<Map<String, Boolean>> = _compactingSessions.asStateFlow()
    private val _compactionFailedSessions = MutableStateFlow<Set<String>>(emptySet())

    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()

    private val _reasoningDurationEvents = MutableSharedFlow<ReasoningDurationEvent>(extraBufferCapacity = 32)
    val reasoningDurationEvents: SharedFlow<ReasoningDurationEvent> = _reasoningDurationEvents.asSharedFlow()

    private val _streamingAssistants = MutableStateFlow<Map<String, StreamingAssistantMessage>>(emptyMap())
    val streamingAssistants: StateFlow<Map<String, StreamingAssistantMessage>> = _streamingAssistants.asStateFlow()

    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryStates: StateFlow<Map<String, RetryState?>> = _retryStates.asStateFlow()

    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchStates: StateFlow<Map<String, KeySwitchState?>> = _keySwitchStates.asStateFlow()

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    val queuedRequests: StateFlow<Map<String, List<QueuedRequest>>> = _queuedRequests.asStateFlow()

    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningCommandSessions: StateFlow<Set<String>> = _runningCommandSessions.asStateFlow()

    /** UI 请求（切换会话/写草稿/删除会话）：运行器不持有 UI 状态，转发给聊天页处理。 */
    private val _uiRequests = MutableSharedFlow<AgentChatRequest>(extraBufferCapacity = 32)
    val uiRequests: SharedFlow<AgentChatRequest> = _uiRequests.asSharedFlow()

    fun started(sessionId: String) = agentRunStateStore.started(sessionId)

    fun isSessionRunning(sessionId: String): Boolean = sessionJobs[sessionId]?.isActive == true

    fun hasActiveSessions(sessionIds: Collection<String>): Boolean =
        sessionIds.any { sessionJobs[it]?.isActive == true }

    fun activeSessionIds(): Set<String> = sessionJobs.filterValues { it.isActive }.keys

    /** 进程启动时拉起订阅；重复调用幂等。 */
    fun start() {
        if (started) return
        started = true
        agentChatRequestBus.attach(chatRequestConsumerId)
        scope.launch {
            try {
                while (true) {
                    val request = agentChatRequestBus.receive()
                    try {
                        handleAgentChatRequest(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "处理插件会话请求失败: $request", e)
                    }
                }
            } finally {
                agentChatRequestBus.detach(chatRequestConsumerId)
            }
        }

        scope.launch {
            terminalSessionProvider.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        scope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        scope.launch {
            liveNotificationSettings.enabledFlow.collectLatest { enabled ->
                if (!enabled) agentLiveNotificationManager.dismiss()
            }
        }

        scope.launch {
            approvalNotificationSettings.enabledFlow.collectLatest { enabled ->
                if (!enabled) agentApprovalNotificationManager.dismiss()
            }
        }

        scope.launch {
            combine(
                toolPermissionManager.awaitingSessionIds,
                askUserQuestionManager.pendingQuestion,
                planApprovalManager.pendingApproval,
                toolPermissionManager.pendingRequest
            ) { awaiting, question, plan, permission ->
                val labels = LinkedHashMap<String, Triple<Int, ApprovalKind, String?>>()
                awaiting.forEach {
                    labels[it] = Triple(
                        R.string.notification_approval_label_permission,
                        ApprovalKind.PERMISSION,
                        permission?.takeIf { request -> request.sessionId == it }?.id
                    )
                }
                question?.sessionId?.takeIf { it.isNotBlank() }?.let {
                    labels.putIfAbsent(
                        it,
                        Triple(R.string.notification_approval_label_question, ApprovalKind.QUESTION, null)
                    )
                }
                plan?.sessionId?.takeIf { it.isNotBlank() }?.let {
                    labels.putIfAbsent(it, Triple(R.string.notification_approval_label_plan, ApprovalKind.PLAN, plan.id))
                }
                labels
            }.collect { labels ->
                val pending = labels.mapNotNull { (sid, info) ->
                    val session = sessionUseCase.getSessionById(sid) ?: return@mapNotNull null
                    AgentApprovalNotificationManager.PendingApproval(
                        sessionId = sid,
                        sessionTitle = session.title.ifBlank { context.getString(R.string.chat_new_session_btn) },
                        label = context.getString(info.first),
                        kind = info.second,
                        requestId = info.third
                    )
                }
                agentApprovalNotificationManager.update(pending)
            }
        }

        scope.launch {
            combine(
                _agentStates,
                _runningTools,
                _preparingTools,
                _compactingSessions,
                approvalSessionIds()
            ) { states, tools, preparing, compacting, approvals ->
                states.filterValues { it is AgentUIState.Loading || it is AgentUIState.Streaming }
                    .keys
                    .associateWith { sid ->
                        val toolName = tools[sid]?.values?.lastOrNull()?.toolName?.takeIf { it.isNotBlank() }
                            ?: preparing[sid]
                        when {
                            sid in approvals -> context.getString(R.string.notification_approval_needed)
                            toolName != null -> context.getString(toolRunningLabelRes(toolName))
                            compacting[sid] == true -> context.getString(R.string.chat_compressing_context)
                            else -> context.getString(R.string.chat_status_thinking)
                        }
                    }
            }.debounce(500)
                .collect { statusMap ->
                    val running = statusMap.mapNotNull { (sid, label) ->
                        val session = sessionUseCase.getSessionById(sid) ?: return@mapNotNull null
                        if (session.parentId != null) return@mapNotNull null
                        AgentLiveNotificationManager.RunningSession(
                            sessionId = sid,
                            title = session.title.ifBlank { context.getString(R.string.chat_new_session_btn) },
                            statusLabel = label
                        )
                    }
                    agentLiveNotificationManager.update(running)
                }
        }

        scope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> {
                        markSubAgentRunning(event.subSessionId)
                        spawnSubAgentWorkflow(event)
                    }
                    SubAgentEventType.STOPPED -> {
                        markSubAgentFinished(event.subSessionId, SubAgentCardStatus.STOPPED)
                        stopAgentSession(event.subSessionId)
                    }
                    SubAgentEventType.DELETED -> {
                        markSubAgentFinished(event.subSessionId, SubAgentCardStatus.STOPPED)
                        clearSessionRuntimeState(event.subSessionId)
                    }
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }
    }

    private fun approvalSessionIds(): Flow<Set<String>> = combine(
        toolPermissionManager.awaitingSessionIds,
        askUserQuestionManager.pendingQuestion,
        planApprovalManager.pendingApproval
    ) { awaiting, question, plan ->
        buildSet {
            addAll(awaiting)
            question?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
            plan?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        }
        return localized ?: event.error
    }

    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private fun updateStreamingAssistant(
        sessionId: String,
        messageId: String,
        transform: (StreamingAssistantMessage) -> StreamingAssistantMessage
    ) {
        _streamingAssistants.update { assistants ->
            val current = assistants[sessionId]?.takeIf { it.messageId == messageId }
                ?: StreamingAssistantMessage(sessionId, messageId)
            assistants + (sessionId to transform(current))
        }
    }

    private fun clearStreamingAssistant(sessionId: String, keepSettled: Boolean = false) {
        _streamingAssistants.update { assistants ->
            if (keepSettled && assistants[sessionId]?.settled == true) assistants
            else assistants - sessionId
        }
    }

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) {
            _retryStates.value - sessionId
        } else {
            _retryStates.value + (sessionId to state)
        }
    }

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) {
            _keySwitchStates.value - sessionId
        } else {
            _keySwitchStates.value + (sessionId to state)
        }
    }

    private fun acquireKeepalive() {
        TerminalKeepaliveService.enablePersistent(context)
        if (!wakeLock.isHeld) {
            runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
                .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
        }
        startWakeLockRenewal()
    }

    private fun releaseKeepalive() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    private fun startWakeLockRenewal() {
        if (wakeLockRenewJob?.isActive == true) return
        wakeLockRenewJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_TIMEOUT_MS - WAKE_LOCK_RENEW_MARGIN_MS)
                if (activeSessionIds().isEmpty()) break
                runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
                    .onFailure { FileLogger.e(TAG, "renew wakeLock failed", it) }
            }
        }
    }

    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
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
        val sid = targetSessionId ?: return
        val isCurrentRunning = sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage
            )
        }
    }

    fun removeQueuedRequest(sessionId: String, id: String) {
        val queue = _queuedRequests.value[sessionId] ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.filterNot { it.id == id })
    }

    fun moveQueuedRequest(sessionId: String, fromIndex: Int, toIndex: Int) {
        val queue = _queuedRequests.value[sessionId] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sessionId to reordered)
    }

    fun updateQueuedRequest(sessionId: String, id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val queue = _queuedRequests.value[sessionId] ?: return
        _queuedRequests.value = _queuedRequests.value + (
            sessionId to queue.map { req ->
                if (req.id != id) req
                else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
            }
            )
    }

    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    fun interjectQueuedRequest(sessionId: String, id: String) {
        val queue = _queuedRequests.value[sessionId] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.filterNot { it.id == id })
        deliverSystemEvent(
            sessionId,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sessionId,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger,
            persistUserMessage = next.persistUserMessage
        )
    }

    /** 命令执行结束后的接续（斜杠命令在聊天页执行，结束后通知运行器消费队列）。 */
    fun onCommandFinished(sessionId: String) {
        _runningCommandSessions.value = _runningCommandSessions.value - sessionId
        flushPendingNotifications(sessionId)
        processNextInQueue(sessionId)
    }

    fun markCommandStarted(sessionId: String) {
        _runningCommandSessions.value = _runningCommandSessions.value + sessionId
    }

    /** 回滚前停止会话任务并清空运行态（队列、运行中工具、流式内容、压缩与重试提示）。 */
    fun suspendSessionForRewind(sessionId: String) {
        clearStreamingAssistant(sessionId)
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setCompacting(sessionId, false)
        _contextUsages.value = _contextUsages.value - sessionId
        _compactionFailedSessions.value = _compactionFailedSessions.value - sessionId
        setRetryState(sessionId, null)
    }

    /** /compress：手动压缩当前会话上下文；结束后续跑队列。 */
    fun compactSession(sessionId: String) {
        if (isSessionRunning(sessionId)) return
        scope.launch {
            clearStreamingAssistant(sessionId)
            setCompacting(sessionId, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sessionId) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sessionId, true)
                        AgentEvent.CompactionFinished -> {
                            setCompacting(sessionId, false)
                            if (!failed) _contextUsages.value = _contextUsages.value - sessionId
                        }
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sessionId, false)
                            messagePersistenceUseCase.persist(
                                sessionId = sessionId,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sessionId", e)
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sessionId, false)
                processNextInQueue(sessionId)
            }
        }
    }

    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        if (subAgentEventBus.release(sessionId)) {
            scope.launch {
                val sub = sessionUseCase.getSessionById(sessionId)
                val parentId = sub?.parentId
                if (parentId != null) {
                    notifyParentSubAgentFinished(
                        parentSessionId = parentId,
                        subSessionId = sessionId,
                        title = sub.title,
                        outcome = NotificationOutcome.STOPPED,
                        detail = "用户在界面上手动停止了这个子代理，任务未完成。"
                    )
                }
            }
        }
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val pendingPermission = toolPermissionManager.pendingChatForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        FileLogger.d(
            TAG,
            "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} " +
                "pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}"
        )
        val stoppedAt = System.currentTimeMillis()
        fun settlePartialAssistant(current: StreamingAssistantMessage): StreamingAssistantMessage? {
            if (runningTools.isNotEmpty() || current.settled ||
                (current.content.isEmpty() && current.reasoning.isEmpty())
            ) return null
            val partial = current.content.trimEnd()
            return current.copy(
                content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText,
                reasoning = current.reasoning.takeIf { it.hasVisibleContent() }.orEmpty(),
                settled = true,
                reasoningEndMs = current.reasoningStartMs?.let { current.reasoningEndMs ?: stoppedAt }
            )
        }
        val previousAssistant = _streamingAssistants.getAndUpdate { assistants ->
            val current = assistants[sessionId] ?: return@getAndUpdate assistants
            val stopped = settlePartialAssistant(current)
            when {
                stopped != null -> assistants + (sessionId to stopped)
                current.settled -> assistants
                else -> assistants - sessionId
            }
        }[sessionId]
        val stoppedAssistant = previousAssistant?.let { settlePartialAssistant(it) }
        stoppedAssistant?.reasoningStartMs?.let { start ->
            stoppedAssistant.reasoningEndMs?.minus(start)?.takeIf { it > 0 }?.let { duration ->
                _reasoningDurationEvents.tryEmit(ReasoningDurationEvent(stoppedAssistant.messageId, duration))
            }
        }
        job.cancel()
        if (sessionJobs[sessionId]?.isActive != true) {
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        scope.launch {
            if (runningTools.isNotEmpty()) {
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (stoppedAssistant != null) {
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = stoppedAssistant.content,
                    id = stoppedAssistant.messageId,
                    reasoning = stoppedAssistant.reasoning.ifEmpty { null }
                )
            }
            if (pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
                    toolCallId = pendingPermission.id,
                    toolName = pendingPermission.toolName,
                    isError = true
                )
            }
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            processNextInQueue(sessionId)
        }
    }

    fun resetGoalEmptyTurns(sessionId: String) {
        goalEmptyTurnCounts.remove(sessionId)
    }

    fun clearContextUsage(sessionId: String) {
        _contextUsages.value = _contextUsages.value - sessionId
    }

    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        goalEmptyTurnCounts.clear()
        val activeSubIds = subAgentEventBus.activeSubSessionIds.value.toList()
        activeSubIds.forEach { subAgentEventBus.release(it) }
        activeSubIds.forEach { id ->
            if (_subAgentCards.value.containsKey(id)) {
                markSubAgentFinished(id, SubAgentCardStatus.STOPPED)
            }
        }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingAssistants.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
    }

    fun clearSessionRuntimeState(sessionId: String) {
        clearStreamingAssistant(sessionId)
        subAgentEventBus.release(sessionId)
        agentNotificationCenter.clear(sessionId)
        _queuedRequests.value = _queuedRequests.value - sessionId
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        _agentStates.value = _agentStates.value - sessionId
        _runningTools.value = _runningTools.value - sessionId
        _retryStates.value = _retryStates.value - sessionId
    }

    private fun markSubAgentRunning(subSessionId: String) {
        scope.launch {
            val session = runCatching { sessionUseCase.getSessionById(subSessionId) }.getOrNull()
            _subAgentCards.value = _subAgentCards.value + (
                subSessionId to SubAgentCardState(
                    subSessionId = subSessionId,
                    agentName = session?.subagentType?.takeIf { it.isNotBlank() && it != "subagent" },
                    title = session?.title.orEmpty(),
                    status = SubAgentCardStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )
                )
        }
    }

    private fun markSubAgentFinished(
        subSessionId: String,
        status: SubAgentCardStatus,
        digest: SubAgentDigest? = null
    ) {
        val previous = _subAgentCards.value[subSessionId]
        val startedAt = previous?.startedAt ?: System.currentTimeMillis()
        _subAgentCards.value = _subAgentCards.value + (
            subSessionId to SubAgentCardState(
                subSessionId = subSessionId,
                agentName = previous?.agentName,
                title = previous?.title.orEmpty(),
                status = status,
                excerpt = digest?.excerpt ?: previous?.excerpt,
                changedFiles = digest?.changedFiles?.takeIf { it.isNotEmpty() } ?: previous?.changedFiles.orEmpty(),
                elapsedMs = System.currentTimeMillis() - startedAt,
                startedAt = startedAt
            )
            )
        if (_subAgentCards.value.size > MAX_SUBAGENT_CARDS) {
            val keep = _subAgentCards.value.entries.sortedByDescending { it.value.startedAt }
                .take(MAX_SUBAGENT_CARDS).associate { it.key to it.value }
            _subAgentCards.value = keep
        }
    }

    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
    }

    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        scope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            val digest = if (event.type == SubAgentEventType.COMPLETED) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        SubAgentDigester.digest(agentMessageDao.getMessagesBySessionOnce(event.subSessionId))
                    }
                        .onFailure { FileLogger.w(TAG, "子代理结果摘要提取失败: ${event.subSessionId}", it) }
                        .getOrNull()
                }
            } else {
                null
            }
            markSubAgentFinished(
                subSessionId = event.subSessionId,
                status = if (event.type == SubAgentEventType.FAILED) {
                    SubAgentCardStatus.FAILED
                } else {
                    SubAgentCardStatus.COMPLETED
                },
                digest = digest
            )
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type == SubAgentEventType.FAILED },
                resultExcerpt = digest?.excerpt,
                changedFiles = digest?.changedFiles.orEmpty()
            )
        }
    }

    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    suspend fun notifyParentSubAgentStopped(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        detail: String
    ) {
        notifyParentSubAgentFinished(
            parentSessionId = parentSessionId,
            subSessionId = subSessionId,
            title = title,
            outcome = NotificationOutcome.STOPPED,
            detail = detail
        )
    }

    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?,
        resultExcerpt: String? = null,
        changedFiles: List<String> = emptyList()
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail,
            resultExcerpt = resultExcerpt,
            changedFiles = changedFiles
        )
        deliverSystemEvent(parentSessionId, item)
    }

    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        FileLogger.d(
            TAG,
            "handleBgFinished: eventSid=$sessionId jobActive=$jobActive state=${_agentStates.value[sessionId]}"
        )
        deliverSystemEvent(sessionId, event.toPendingNotification())
    }

    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        agentNotificationCenter.enqueue(sessionId, item)
        flushPendingNotifications(sessionId)
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    private fun flushPendingNotifications(sessionId: String) {
        if (sessionJobs[sessionId]?.isActive == true || sessionId in _runningCommandSessions.value) return
        val items = agentNotificationCenter.peek(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(
            TAG,
            "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}"
        )
        scope.launch {
            val workspacePath = sessionUseCase.getSessionById(sessionId)?.workspacePath.orEmpty()
            executeAgentRequestStream(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = workspacePath,
                targetSessionId = sessionId,
                notificationSeqs = items.map { it.seq }
            )
        }
    }

    private suspend fun handleAgentChatRequest(request: AgentChatRequest) {
        when (request) {
            is AgentChatRequest.Send -> {
                if (sessionUseCase.getSessionById(request.sessionId) == null) return
                enqueueAgentRequest(
                    request = request.text,
                    projectRoot = request.workspacePath,
                    targetSessionId = request.sessionId
                )
            }
            is AgentChatRequest.Stop -> stopAgentSession(request.sessionId)
            is AgentChatRequest.Select,
            is AgentChatRequest.SetDraft,
            is AgentChatRequest.Delete -> _uiRequests.tryEmit(request)
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        skipTitleUpdate: Boolean = false,
        notificationSeqs: List<Long> = emptyList()
    ): Job = scope.launch {
        val sessionId = targetSessionId ?: return@launch
        if (request.startsWith("/")) {
            return@launch
        }
        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)
        acquireKeepalive()

        val isSubSession = currentSession?.parentId != null
        var turnSucceeded = false
        var goalActiveAtTurn = false
        var turnHadActivity = false
        var pendingTitleRequest: (() -> Unit)? = null
        fun startPendingTitle() {
            val start = pendingTitleRequest ?: return
            pendingTitleRequest = null
            start()
        }
        val isGoalTurn = !persistUserMessage
        var runStatus = "success"
        var runError: String? = null
        try {
            agentRunStateStore.started(sessionId)
            var failed = false
            val historyStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(
                TAG,
                "history.start",
                details = "operation=$historyStartedNs requestChars=${request.length} attachments=${inputAttachments.size}"
            )
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            FileLogger.memoryCheckpoint(
                TAG,
                "history.ready",
                elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
                details = "operation=$historyStartedNs messages=${history.size}"
            )
            val isFirst = history.isEmpty()

            val userMsgId = UUID.randomUUID().toString()
            if (!persistUserMessage) {
                // 目标续跑等内部轮次：不落库、不建检查点。
            } else if (!isAutoTrigger) {
                if (notificationSeqs.isEmpty()) {
                    messagePersistenceUseCase.persist(
                        sessionId, MessageRole.USER, request, id = userMsgId, attachments = inputAttachments
                    )
                } else {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        messagePersistenceUseCase.persist(
                            sessionId, MessageRole.USER, request, id = userMsgId, attachments = inputAttachments
                        )
                        agentNotificationCenter.ack(sessionId, notificationSeqs)
                    }
                }
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    pendingTitleRequest = {
                        scope.launch {
                            agentWorkflow.generateTitle(sessionId, request)?.let {
                                sessionUseCase.updateTitle(sessionId, it)
                            }
                        }
                    }
                }
            } else {
                messagePersistenceUseCase.persist(
                    sessionId, MessageRole.USER, modelRequest, id = userMsgId, attachments = inputAttachments
                )
            }
            messagePersistenceUseCase.invalidateHistory(sessionId)
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD
            val agentDefinition = withContext(Dispatchers.IO) {
                sessionEntity?.takeIf { it.parentId != null }
                    ?.subagentType
                    ?.let { agentDefinitionRepository.findIncludingDisabled(it) }
            }

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                inputMessageId = userMsgId,
                lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                mode = mode,
                modeBeforePlan = sessionDomain?.modeBeforePlan,
                goal = sessionDomain?.goal,
                goalStatus = sessionDomain?.goalStatus,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                agentDefinition = agentDefinition
            )

            val allTools = toolRegistry.getAvailableTools()
            val isSub = isSubSession
            val goalActive = sessionDomain?.goalStatus == GoalStatus.ACTIVE
            goalActiveAtTurn = goalActive
            val tools = when {
                agentDefinition != null -> {
                    val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                    allTools.filter { it.name in allowed }
                }
                isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
                else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
            }.let { list ->
                if (goalActive) list.filterNot { it.name == "planMode" }
                else list.filterNot { it.name == "goalStatus" }
            }

            var currentAssistantMessageId: String? = null
            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun startAssistantMessage(messageId: String) {
                if (currentAssistantMessageId == messageId) return
                currentAssistantMessageId = messageId
                currentReasoningStart = null
                currentReasoningEnd = null
            }
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _streamingAssistants.update { assistants ->
                        val current = assistants[sessionId]
                            ?.takeIf { it.messageId == currentAssistantMessageId }
                            ?: return@update assistants
                        assistants + (sessionId to current.copy(reasoningStartMs = start, reasoningEndMs = end))
                    }
                }
            }
            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        startPendingTitle()
                        startAssistantMessage(event.messageId)
                        if (event.accumulated.hasVisibleContent()) {
                            finishReasoning()
                            turnHadActivity = true
                        }
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        updateStreamingAssistant(sessionId, event.messageId) {
                            it.copy(content = event.accumulated)
                        }
                    }
                    is AgentEvent.ReasoningDelta -> {
                        startPendingTitle()
                        startAssistantMessage(event.messageId)
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                        }
                        updateStreamingAssistant(sessionId, event.messageId) {
                            it.copy(
                                reasoning = event.accumulated,
                                reasoningStartMs = currentReasoningStart,
                                reasoningEndMs = currentReasoningEnd
                            )
                        }
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        clearStreamingAssistant(sessionId)
                        currentAssistantMessageId = null
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        clearStreamingAssistant(sessionId)
                        currentAssistantMessageId = null
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                    }
                    is AgentEvent.CompactionStarted -> {
                        clearStreamingAssistant(sessionId)
                        currentAssistantMessageId = null
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, true)
                        _compactionFailedSessions.value = _compactionFailedSessions.value - sessionId
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        val failed = sessionId in _compactionFailedSessions.value
                        if (!failed) _contextUsages.value = _contextUsages.value - sessionId
                        _compactionFailedSessions.value = _compactionFailedSessions.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        _compactionFailedSessions.value = _compactionFailedSessions.value + sessionId
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        startAssistantMessage(event.messageId)
                        finishReasoning()
                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        updateStreamingAssistant(sessionId, msgId) {
                            it.copy(
                                content = normalized,
                                reasoning = reasoning.orEmpty(),
                                settled = true,
                                reasoningStartMs = currentReasoningStart,
                                reasoningEndMs = currentReasoningEnd
                            )
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        if (reasoningDuration != null && reasoningDuration > 0) {
                            _reasoningDurationEvents.tryEmit(ReasoningDurationEvent(msgId, reasoningDuration))
                        }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            id = msgId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                            attachments = event.attachments,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            cachedInputTokens = event.cachedInputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(
                                LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens)
                            )
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        } else {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, 0, 0, 0))
                        }
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        turnHadActivity = true
                        val msgId = "tool_${event.id}"
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(
                            sessionId,
                            msgId,
                            RunningToolOutput(msgId, "", event.toolName, event.argsPreview)
                        )
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(
                            sessionId,
                            msgId,
                            RunningToolOutput(
                                msgId,
                                event.accumulated,
                                event.toolName,
                                toolArgsByMsgId[msgId] ?: ""
                            )
                        )
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                    is AgentEvent.ModeChanged -> {
                    }
                }
            }
            turnSucceeded = true

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob && (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            if (goalActiveAtTurn) {
                scope.launch {
                    runCatching { sessionUseCase.updateGoalStatus(sessionId, GoalStatus.PAUSED) }
                }
            }
            runStatus = "cancelled"
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
            runStatus = "error"
            runError = e.toUserMessage(context)
            setAgentState(sessionId, AgentUIState.Error(e.toUserMessage(context)))
            _completedSessions.value = _completedSessions.value - sessionId
            if (isSubSession) {
                sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                    subAgentEventBus.emit(
                        SubAgentEvent(
                            subSessionId = sessionId,
                            parentSessionId = parentId,
                            type = SubAgentEventType.FAILED,
                            detail = e.toUserMessage(context)
                        )
                    )
                }
            }
        } finally {
            startPendingTitle()
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isSubSession) subAgentEventBus.release(sessionId)
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
                clearStreamingAssistant(sessionId, keepSettled = true)
            }
            _runningTools.value = _runningTools.value - sessionId
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setKeySwitchState(sessionId, null)
            _streamingAssistants.update { assistants ->
                val current = assistants[sessionId] ?: return@update assistants
                if (current.reasoningStartMs != null && current.reasoningEndMs == null) {
                    assistants + (sessionId to current.copy(reasoningEndMs = System.currentTimeMillis()))
                } else {
                    assistants
                }
            }

            val finishState = _agentStates.value[sessionId]
            val finishStatus = when {
                runStatus != "success" -> runStatus
                finishState is AgentUIState.Error -> "error"
                else -> "success"
            }
            val finishError = runError ?: (finishState as? AgentUIState.Error)?.message
            agentRunStateStore.finished(sessionId, finishStatus, finishError)

            if (notificationSeqs.isEmpty() ||
                agentNotificationCenter.peek(sessionId).none { it.seq in notificationSeqs }
            ) {
                flushPendingNotifications(sessionId)
            }

            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error &&
                currentState !is AgentUIState.Loading &&
                currentState !is AgentUIState.Streaming
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                if (turnSucceeded) runCatching { maybeQueueGoalContinuation(sessionId, isGoalTurn, turnHadActivity) }
                processNextInQueue(sessionId)
            }
            if (activeSessionIds().isEmpty()) {
                releaseKeepalive()
            }
        }
    }.also { job ->
        if (targetSessionId != null) {
            sessionJobs[targetSessionId] = job
        }
    }

    private suspend fun maybeQueueGoalContinuation(sessionId: String, isGoalTurn: Boolean, hadActivity: Boolean) {
        val session = sessionUseCase.getSessionById(sessionId) ?: return
        val goal = session.goal?.takeIf { it.isNotBlank() } ?: return
        if (session.goalStatus != GoalStatus.ACTIVE.name) return
        if (!_queuedRequests.value[sessionId].isNullOrEmpty()) return
        if (isGoalTurn) {
            if (hadActivity) {
                goalEmptyTurnCounts.remove(sessionId)
            } else {
                val n = (goalEmptyTurnCounts[sessionId] ?: 0) + 1
                if (n >= MAX_GOAL_EMPTY_TURNS) {
                    goalEmptyTurnCounts.remove(sessionId)
                    sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
                    persistGoalNotice(
                        sessionId,
                        "目标自主循环连续 $MAX_GOAL_EMPTY_TURNS 轮无进展，已置为「受阻」并停止。可 `/goal resume` 继续或 `/goal clear` 清除。"
                    )
                    return
                }
                goalEmptyTurnCounts[sessionId] = n
            }
        }
        val maxTurns = generalSettingsRepository.goalMaxTurns()
        val maxTokens = generalSettingsRepository.goalMaxTokens()
        val usedTokens = (session.totalInputTokens - session.goalStartInputTokens).coerceAtLeast(0) +
            (session.totalOutputTokens - session.goalStartOutputTokens).coerceAtLeast(0)
        if (session.goalTurnCount >= maxTurns || usedTokens >= maxTokens) {
            sessionUseCase.updateGoalStatus(sessionId, GoalStatus.EXHAUSTED)
            persistGoalNotice(
                sessionId,
                "目标自主循环已达预算上限（轮次上限 $maxTurns 轮 / token 预算 $maxTokens），已停止。" +
                    "可 `/goal resume` 继续或 `/goal clear` 清除。"
            )
            return
        }
        sessionUseCase.incrementGoalTurn(sessionId)
        enqueueGoalTurn(sessionId, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

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

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }

    private companion object {
        const val TAG = "AgentRunManager"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        const val MAX_SUBAGENT_CARDS = 50
        const val MAX_GOAL_EMPTY_TURNS = 3
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        const val WAKE_LOCK_RENEW_MARGIN_MS = 10 * 60 * 1000L
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
    }
}
