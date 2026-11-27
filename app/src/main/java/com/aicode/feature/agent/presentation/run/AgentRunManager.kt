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
import com.aicode.core.util.formatCostUsd
import com.aicode.core.util.toUserMessage
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.BackendExecutionContext
import com.aicode.feature.agent.domain.workflow.SessionRef
import com.aicode.feature.agent.domain.workflow.TurnPersistence
import com.aicode.feature.agent.domain.workflow.WorkflowTurnContext
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.skill.Skill
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
import com.aicode.feature.agent.domain.workflow.AgentWorkflowRegistry
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
import com.aicode.feature.agent.presentation.RequestQueueState
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.StreamingAssistantMessage
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.component.formatTokenCount
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
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val workflowRegistry: AgentWorkflowRegistry,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val aiProviderRepository: AIProviderRepository,
    private val modelCostCalculator: ModelCostCalculator,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val executionBackendRegistry: ExecutionBackendRegistry,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val slashCommandRegistry: SlashCommandRegistry,
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

    private val sessionJobs = ConcurrentHashMap<String, Job>()
    private val externalSessionIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val commandJobs = ConcurrentHashMap<String, Job>()
    private val chatRequestConsumerId = UUID.randomUUID().toString()
    private val goalEmptyTurnCounts = ConcurrentHashMap<String, Int>()

    /** 工具调用传入参数（argsPreview）按落库消息 id 暂存。 */
    private val toolArgsByMsgId = ConcurrentHashMap<String, String>()

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

    private val streamingAssistantStore = StreamingAssistantStore()
    val streamingAssistants: StateFlow<Map<String, StreamingAssistantMessage>> = streamingAssistantStore.messages

    fun acknowledgeRenderedAssistant(sessionId: String, messageId: String) {
        streamingAssistantStore.acknowledgeRenderedAssistant(sessionId, messageId)
    }

    fun setSelectedLocalSession(ownerToken: Any, sessionId: String?) {
        streamingAssistantStore.setSelectedSession(ownerToken, sessionId)
    }

    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryStates: StateFlow<Map<String, RetryState?>> = _retryStates.asStateFlow()

    private val _requestQueueStates = MutableStateFlow<Map<String, RequestQueueState>>(emptyMap())
    val requestQueueStates: StateFlow<Map<String, RequestQueueState>> = _requestQueueStates.asStateFlow()

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
        _agentStates.update { it + (sessionId to state) }
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
        _runningTools.update { all ->
            all + (sessionId to ((all[sessionId] ?: emptyMap()) + (msgId to tool)))
        }
    }

    private fun removeRunningTool(sessionId: String, msgId: String) {
        _runningTools.update { all ->
            val sessionTools = all[sessionId] ?: return@update all
            val updated = sessionTools - msgId
            if (updated.isEmpty()) all - sessionId else all + (sessionId to updated)
        }
    }

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        _preparingTools.update { current ->
            when {
                toolName == null -> if (current.containsKey(sessionId)) current - sessionId else current
                current[sessionId] != toolName -> current + (sessionId to toolName)
                else -> current
            }
        }
    }

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.update { if (compacting) it + (sessionId to true) else it - sessionId }
    }

    private fun updateStreamingAssistant(
        sessionId: String,
        messageId: String,
        transform: (StreamingAssistantMessage) -> StreamingAssistantMessage
    ) {
        streamingAssistantStore.update(sessionId, messageId, transform)
    }

    private fun clearStreamingAssistant(sessionId: String, keepSettled: Boolean = false) {
        streamingAssistantStore.clear(sessionId, keepSettled)
    }

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.update { if (state == null) it - sessionId else it + (sessionId to state) }
    }

    private fun setRequestQueueState(sessionId: String, state: RequestQueueState?) {
        _requestQueueStates.update { if (state == null) it - sessionId else it + (sessionId to state) }
    }

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.update { if (state == null) it - sessionId else it + (sessionId to state) }
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
            _queuedRequests.update { all -> all + (sid to ((all[sid] ?: emptyList()) + req)) }
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
        _queuedRequests.update { all ->
            val queue = all[sessionId] ?: return@update all
            all + (sessionId to queue.filterNot { req -> req.id == id })
        }
    }

    fun moveQueuedRequest(sessionId: String, fromIndex: Int, toIndex: Int) {
        _queuedRequests.update { all ->
            val queue = all[sessionId] ?: return@update all
            if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return@update all
            all + (sessionId to queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) })
        }
    }

    fun updateQueuedRequest(sessionId: String, id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        _queuedRequests.update { all ->
            val queue = all[sessionId] ?: return@update all
            all + (
                sessionId to queue.map { req ->
                    if (req.id != id) req
                    else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
                }
                )
        }
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
        val req = _queuedRequests.value[sessionId]?.firstOrNull { it.id == id } ?: return
        _queuedRequests.update { all ->
            val queue = all[sessionId] ?: return@update all
            all + (sessionId to queue.filterNot { queued -> queued.id == id })
        }
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
        val next = _queuedRequests.value[sessionId]?.firstOrNull() ?: return
        _queuedRequests.update { all ->
            val queue = all[sessionId] ?: return@update all
            all + (sessionId to queue.drop(1))
        }
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

    /** 命令执行结束后的接续：清理命令占用并按需启动后续动作或消费队列。 */
    fun onCommandFinished(sessionId: String) {
        finishCommand(sessionId, cancelled = false, followUp = null)
    }

    fun markCommandStarted(sessionId: String) {
        streamingAssistantStore.setCommandRunning(sessionId, true)
        _runningCommandSessions.update { it + sessionId }
    }

    private fun finishCommand(sessionId: String, cancelled: Boolean, followUp: (() -> Unit)?) {
        _runningCommandSessions.update { it - sessionId }
        streamingAssistantStore.setCommandRunning(sessionId, false)
        if (cancelled) return
        if (followUp != null) {
            followUp()
        } else {
            flushPendingNotifications(sessionId)
            processNextInQueue(sessionId)
        }
    }

    /** 回滚前停止会话任务并清空运行态（队列、运行中工具、流式内容、压缩与重试提示）。 */
    fun suspendSessionForRewind(sessionId: String) {
        streamingAssistantStore.clearSession(sessionId)
        _queuedRequests.update { it + (sessionId to emptyList()) }
        agentNotificationCenter.clear(sessionId)
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        commandJobs[sessionId]?.cancel()
        commandJobs.remove(sessionId)
        _runningCommandSessions.update { it - sessionId }
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.update { it - sessionId }
        setCompacting(sessionId, false)
        _contextUsages.update { it - sessionId }
        _compactionFailedSessions.update { it - sessionId }
        setRetryState(sessionId, null)
    }

    /** /compress：手动压缩当前会话上下文；结束后续跑队列。 */
    fun compactSession(sessionId: String) {
        if (isSessionRunning(sessionId) || sessionId in _runningCommandSessions.value) return
        markCommandStarted(sessionId)
        val job = scope.launch(
            context = BackendExecutionContext.bind(executionBackendRegistry.currentBackend()),
            start = CoroutineStart.LAZY
        ) {
            var cancelled = false
            try {
                runCompaction(sessionId)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                if (commandJobs[sessionId] == coroutineContext[Job]) {
                    commandJobs.remove(sessionId)
                }
                finishCommand(sessionId, cancelled = cancelled, followUp = null)
            }
        }
        commandJobs[sessionId] = job
        job.start()
    }

    private suspend fun runCompaction(sessionId: String) {
        clearStreamingAssistant(sessionId)
        setCompacting(sessionId, true)
        var failed = false
        try {
            val session = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = session?.toDomain()
            val compactionContext = AgentContext(
                currentFile = null,
                selectedCode = null,
                projectRoot = session?.workspacePath.orEmpty(),
                language = null,
                sessionId = sessionId,
                mode = sessionDomain?.mode ?: AgentMode.BUILD,
                turn = WorkflowTurnContext(
                    runId = UUID.randomUUID().toString(),
                    sessionRef = SessionRef.Room(sessionId),
                    providerId = session?.providerId,
                    model = session?.model,
                    workflowState = session?.workflowState,
                    permissionMode = sessionDomain?.mode ?: AgentMode.BUILD,
                    persistence = roomTurnPersistence(sessionId)
                )
            )
            val changed = workflowRegistry.resolve(session?.workflowId).compactSession(compactionContext) { event ->
                when (event) {
                    is AgentEvent.CompactionStarted -> setCompacting(sessionId, true)
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        if (!failed) _contextUsages.update { it - sessionId }
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
        }
    }

    /** /usage：输出今日与累计调用次数、token 用量与预估费用。 */
    fun showUsage(sessionId: String) {
        scope.launch { persistUsageTable(sessionId) }
    }

    /** /goal：管理指定会话的持续目标。 */
    fun setSessionGoal(sessionId: String, rawArgs: String, workspacePath: String = "") {
        scope.launch {
            var followUp: (() -> Unit)? = null
            handleSetSessionGoal(sessionId, rawArgs, workspacePath) { goalPrompt, root ->
                followUp = { enqueueGoalTurn(sessionId, root, goalPrompt) }
            }
            followUp?.invoke()
        }
    }

    fun setGoalPaused(sessionId: String, paused: Boolean) {
        scope.launch {
            if (paused) {
                pauseGoal(sessionId)
            } else {
                var followUp: (() -> Unit)? = null
                resumeGoal(sessionId) { goalPrompt, root ->
                    followUp = { enqueueGoalTurn(sessionId, root, goalPrompt) }
                }
                followUp?.invoke()
            }
        }
    }

    fun clearGoal(sessionId: String) {
        scope.launch { clearGoalInternal(sessionId) }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    private suspend fun persistUsageTable(sessionId: String) {
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
        sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
        messagePersistenceUseCase.persist(sessionId, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
    }

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

    private suspend fun handleSetSessionGoal(
        sessionId: String,
        rawArgs: String,
        workspacePath: String = "",
        onStartTurn: (prompt: String, workspacePath: String) -> Unit
    ) {
        val session = sessionUseCase.getSessionById(sessionId) ?: return
        val resolvedRoot = workspacePath.ifBlank { session.workspacePath }
        val arg = rawArgs.trim()
        when {
            arg.isEmpty() -> persistGoalNotice(sessionId, goalSummary(session))
            arg.equals("clear", ignoreCase = true) || arg.equals("off", ignoreCase = true) -> clearGoalInternal(sessionId)
            arg.equals("pause", ignoreCase = true) -> pauseGoal(sessionId)
            arg.equals("resume", ignoreCase = true) -> resumeGoal(sessionId) { prompt, _ ->
                onStartTurn(prompt, resolvedRoot)
            }
            else -> setGoalText(sessionId, session, arg) { prompt, _ ->
                onStartTurn(prompt, resolvedRoot)
            }
        }
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

    private suspend fun setGoalText(
        sid: String,
        session: ChatSessionEntity,
        text: String,
        onStartTurn: (prompt: String, workspacePath: String) -> Unit
    ) {
        val goalText = text.trim().take(MAX_GOAL_CHARS)
        if (goalText.isEmpty()) return
        resetGoalEmptyTurns(sid)
        sessionUseCase.setGoal(sid, goalText, session.totalInputTokens, session.totalOutputTokens)
        if (runCatching { AgentMode.valueOf(session.mode) }.getOrNull() == AgentMode.PLAN) {
            sessionUseCase.updateMode(sid, AgentMode.BUILD.name)
        }
        persistGoalNotice(
            sid,
            "已设定目标：$goalText\nAI 将自主循环推进（工具自动放行、PLAN 停用），直到达成、受阻或预算耗尽。" +
                "可随时 `/goal pause` / `/goal resume` / `/goal clear`。"
        )
        onStartTurn(buildGoalPrompt(goalText, 1), session.workspacePath)
    }

    private suspend fun clearGoalInternal(sid: String) {
        resetGoalEmptyTurns(sid)
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

    private suspend fun resumeGoal(
        sid: String,
        onStartTurn: (prompt: String, workspacePath: String) -> Unit
    ) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        val goal = session.goal?.takeIf { it.isNotBlank() }
        if (goal == null) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        sessionUseCase.updateGoalStatus(sid, GoalStatus.ACTIVE)
        persistGoalNotice(sid, "目标已恢复，继续自主循环。")
        onStartTurn(buildGoalPrompt(goal, session.goalTurnCount + 1), session.workspacePath)
    }

    private fun launchResolvedCommand(
        resolved: ResolvedCommand,
        input: String,
        sessionId: String,
        projectRoot: String
    ): Job {
        markCommandStarted(sessionId)
        val job = scope.launch(
            context = BackendExecutionContext.bind(executionBackendRegistry.currentBackend()),
            start = CoroutineStart.LAZY
        ) {
            var cancelled = false
            var followUp: (() -> Unit)? = null
            var suspendAction: (suspend () -> Unit)? = null
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                val workspacePath = projectRoot.ifBlank {
                    sessionUseCase.getSessionById(sessionId)?.workspacePath.orEmpty()
                }
                val commandContext = object : SlashCommandContext {
                    override fun compactCurrentSession() {
                        suspendAction = { runCompaction(sessionId) }
                    }

                    override fun showUsage() {
                        suspendAction = { persistUsageTable(sessionId) }
                    }

                    override fun initProject(prompt: String) {
                        followUp = {
                            executeAgentRequestStream(
                                request = prompt,
                                projectRoot = workspacePath,
                                targetSessionId = sessionId,
                                isAutoTrigger = true
                            )
                        }
                    }

                    override fun runSkill(skill: Skill, args: String) {
                        followUp = {
                            executeAgentRequestStream(
                                request = SlashCommandRegistry.buildSkillPrompt(skill, args),
                                projectRoot = workspacePath,
                                targetSessionId = sessionId,
                                isAutoTrigger = true
                            )
                        }
                    }

                    override fun setSessionGoal(rawArgs: String) {
                        suspendAction = {
                            handleSetSessionGoal(sessionId, rawArgs, workspacePath) { goalPrompt, root ->
                                followUp = { enqueueGoalTurn(sessionId, root, goalPrompt) }
                            }
                        }
                    }
                }
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(commandContext, resolved.args)
                    is ResolvedCommand.SkillCommand -> commandContext.runSkill(resolved.skill, resolved.args)
                }
                suspendAction?.invoke()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                if (commandJobs[sessionId] == coroutineContext[Job]) {
                    commandJobs.remove(sessionId)
                }
                finishCommand(sessionId, cancelled = cancelled, followUp = followUp)
            }
        }
        commandJobs[sessionId] = job
        job.start()
        return job
    }

    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        val isExternal = sessionId in externalSessionIds
        if (!isExternal && subAgentEventBus.release(sessionId)) {
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
        val pendingPermission = if (isExternal) null else toolPermissionManager.pendingChatForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        FileLogger.d(
            TAG,
            "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} " +
                "pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}"
        )
        val stoppedAssistant = streamingAssistantStore.settlePartial(
            sessionId = sessionId,
            stoppedText = stoppedText,
            stoppedAt = System.currentTimeMillis(),
            hasRunningTools = runningTools.isNotEmpty()
        )
        stoppedAssistant?.reasoningStartMs?.let { start ->
            stoppedAssistant.reasoningEndMs?.minus(start)?.takeIf { it > 0 }?.let { duration ->
                _reasoningDurationEvents.tryEmit(ReasoningDurationEvent(stoppedAssistant.messageId, duration))
            }
        }
        job.cancel()
        if (sessionJobs[sessionId]?.isActive != true) {
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.update { it - sessionId }
        setPreparingTool(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        setKeySwitchState(sessionId, null)
        if (isExternal) return
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
                streamingAssistantStore.markPersisted(
                    sessionId, stoppedAssistant.messageId, stoppedAssistant.content, stoppedAssistant.reasoning
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
        _contextUsages.update { it - sessionId }
    }

    suspend fun stopAllAgentsAndJoin() {
        val jobs = (sessionJobs.values + commandJobs.values).distinct()
        stopAllAgents()
        jobs.forEach { it.join() }
        while (sessionJobs.isNotEmpty() || commandJobs.isNotEmpty()) {
            (sessionJobs.values + commandJobs.values).distinct().forEach { it.join() }
        }
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
        externalSessionIds.clear()
        commandJobs.values.forEach { it.cancel() }
        commandJobs.clear()
        _runningCommandSessions.value = emptySet()
        _agentStates.update { states -> states.mapValues { AgentUIState.Idle } }
        streamingAssistantStore.clearAll()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
    }

    fun clearSessionRuntimeState(sessionId: String) {
        streamingAssistantStore.clearSession(sessionId)
        subAgentEventBus.release(sessionId)
        agentNotificationCenter.clear(sessionId)
        _queuedRequests.update { it - sessionId }
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        externalSessionIds.remove(sessionId)
        commandJobs[sessionId]?.cancel()
        commandJobs.remove(sessionId)
        _runningCommandSessions.update { it - sessionId }
        _agentStates.update { it - sessionId }
        _runningTools.update { it - sessionId }
        _retryStates.update { it - sessionId }
    }

    private fun markSubAgentRunning(subSessionId: String) {
        scope.launch {
            val session = runCatching { sessionUseCase.getSessionById(subSessionId) }.getOrNull()
            val card = SubAgentCardState(
                subSessionId = subSessionId,
                agentName = session?.subagentType?.takeIf { it.isNotBlank() && it != "subagent" },
                title = session?.title.orEmpty(),
                status = SubAgentCardStatus.RUNNING,
                startedAt = System.currentTimeMillis()
            )
            _subAgentCards.update { it + (subSessionId to card) }
        }
    }

    private fun markSubAgentFinished(
        subSessionId: String,
        status: SubAgentCardStatus,
        digest: SubAgentDigest? = null
    ) {
        _subAgentCards.update { cards ->
            val previous = cards[subSessionId]
            val startedAt = previous?.startedAt ?: System.currentTimeMillis()
            val updated = cards + (
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
            if (updated.size > MAX_SUBAGENT_CARDS) {
                updated.entries.sortedByDescending { it.value.startedAt }
                    .take(MAX_SUBAGENT_CARDS).associate { it.key to it.value }
            } else {
                updated
            }
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
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: context.getString(R.string.subagent_card_default_agent)
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
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: context.getString(R.string.subagent_card_default_agent)
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
    ): Job {
        val resolvedCommand = if (
            !isAutoTrigger && persistUserMessage && notificationSeqs.isEmpty() &&
            request.trimStart().startsWith("/")
        ) {
            slashCommandRegistry.resolve(request)
        } else {
            null
        }
        if (resolvedCommand != null) {
            val sessionId = targetSessionId ?: return Job().apply { complete() }
            return launchResolvedCommand(resolvedCommand, request, sessionId, projectRoot)
        }
        val runToken = Any()
        if (targetSessionId != null) {
            streamingAssistantStore.startRun(targetSessionId, runToken)
        }
        val job = scope.launch(
            context = BackendExecutionContext.bind(executionBackendRegistry.currentBackend()),
            start = CoroutineStart.LAZY
        ) {
        val sessionId = targetSessionId ?: return@launch
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
                            workflowRegistry.resolve(currentSession?.workflowId)
                                .generateTitle(
                                    AgentContext(
                                        currentFile = null,
                                        selectedCode = null,
                                        projectRoot = currentSession?.workspacePath.orEmpty(),
                                        language = null,
                                        sessionId = sessionId,
                                        mode = currentSession?.toDomain()?.mode ?: AgentMode.BUILD,
                                        turn = WorkflowTurnContext(
                                            runId = UUID.randomUUID().toString(),
                                            sessionRef = SessionRef.Room(sessionId),
                                            providerId = currentSession?.providerId,
                                            model = currentSession?.model,
                                            workflowState = currentSession?.workflowState,
                                            permissionMode = currentSession?.toDomain()?.mode ?: AgentMode.BUILD,
                                            persistence = roomTurnPersistence(sessionId)
                                        )
                                    ),
                                    request
                                )?.let {
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
                fastMode = sessionDomain?.fastMode ?: false,
                agentDefinition = agentDefinition,
                turn = WorkflowTurnContext(
                    runId = runToken.toString(),
                    sessionRef = SessionRef.Room(sessionId),
                    providerId = sessionEntity?.providerId,
                    model = sessionEntity?.model,
                    workflowState = sessionEntity?.workflowState,
                    permissionMode = if (sessionDomain?.goalStatus == GoalStatus.ACTIVE) AgentMode.AUTO else mode,
                    persistence = roomTurnPersistence(sessionId)
                )
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
                    streamingAssistantStore.updateIfPresent(sessionId, currentAssistantMessageId) {
                        it.copy(reasoningStartMs = start, reasoningEndMs = end)
                    }
                }
            }
            workflowRegistry.resolve(sessionDomain?.workflowId).executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.RequestQueued -> setRequestQueueState(sessionId, RequestQueueState(event.requestsAhead))
                    AgentEvent.RequestStarted -> setRequestQueueState(sessionId, null)
                    is AgentEvent.AssistantDelta -> {
                        setRequestQueueState(sessionId, null)
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
                        setRequestQueueState(sessionId, null)
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
                        _compactionFailedSessions.update { it - sessionId }
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        val failed = sessionId in _compactionFailedSessions.value
                        if (!failed) _contextUsages.update { it - sessionId }
                        _compactionFailedSessions.update { it - sessionId }
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.update { it + (sessionId to event) }
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        _compactionFailedSessions.update { it + sessionId }
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
                                persisted = false,
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
                            cachedInputTokens = event.cachedInputTokens,
                            ttfbMillis = event.ttfbMillis,
                            generationWindowMillis = event.generationWindowMillis
                        )
                        streamingAssistantStore.markPersisted(sessionId, msgId, normalized, reasoning.orEmpty())
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
                        setRequestQueueState(sessionId, null)
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
                        setRequestQueueState(sessionId, null)
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
                    is AgentEvent.TransientInstruction -> {
                        // 外部工作流或插件发来的临时指令不写入本地消息表。
                    }
                    is AgentEvent.WorkflowStateChanged -> {
                        // workflowState 由当前 workflow 的 persistence sink 提交。
                    }
                }
            }
            turnSucceeded = true

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.update { it + sessionId }
            }

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob && (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.update { it - sessionId }
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
            _completedSessions.update { it - sessionId }
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
                streamingAssistantStore.finishRun(sessionId, runToken, System.currentTimeMillis())
            }
            _runningTools.update { it - sessionId }
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setRequestQueueState(sessionId, null)
            setKeySwitchState(sessionId, null)

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
    }
        if (targetSessionId != null) {
            sessionJobs[targetSessionId] = job
            job.invokeOnCompletion { streamingAssistantStore.finishRun(targetSessionId, runToken) }
        }
        job.start()
        return job
    }

    /**
     * 外部会话单轮执行：复用保活、`sessionJobs` 互斥与取消、`agentStates`、流式内容、工具进度与
     * `AgentWorkflowRegistry` 路由，但不写入 Room 数据库（`chatSessionDao` / `agentMessageDao` / `checkpointManager`）。
     */
    fun launchExternalTurn(
        externalSessionId: String,
        workflowId: String?,
        request: String,
        workspacePath: String,
        history: List<AgentMessage> = emptyList(),
        providerId: String? = null,
        model: String? = null,
        mode: AgentMode = AgentMode.BUILD,
        workflowState: String? = null,
        sessionRef: SessionRef = SessionRef.External(owner = "plugin", opaqueId = externalSessionId),
        onCommittedEvent: suspend (AgentEvent) -> Unit = {},
        onFinished: suspend (succeeded: Boolean) -> Unit = {}
    ): Job {
        val runToken = Any()
        val job: Job = synchronized(sessionJobs) {
            sessionJobs[externalSessionId]?.takeIf { it.isActive }?.let { return it }
            externalSessionIds.add(externalSessionId)
            streamingAssistantStore.startRun(externalSessionId, runToken)
            setAgentState(externalSessionId, AgentUIState.Streaming)
            acquireKeepalive()
            scope.launch(
                context = BackendExecutionContext.bind(executionBackendRegistry.currentBackend()),
                start = CoroutineStart.LAZY
            ) {
                var turnSucceeded = false
                var runStatus = "success"
                var runError: String? = null
                val externalToolArgs = mutableMapOf<String, String>()
                try {
                    agentRunStateStore.started(externalSessionId)
                    var failed = false
                    val agentContext = AgentContext(
                        currentFile = null,
                        selectedCode = null,
                        projectRoot = workspacePath,
                        language = null,
                        history = history,
                        sessionId = externalSessionId,
                        inputMessageId = UUID.randomUUID().toString(),
                        mode = mode,
                        turn = WorkflowTurnContext(
                            runId = runToken.toString(),
                            sessionRef = sessionRef,
                            providerId = providerId,
                            model = model,
                            workflowState = workflowState,
                            permissionMode = mode,
                            persistence = externalTurnPersistence(onCommittedEvent)
                        )
                    )
                    val tools = toolRegistry.getAvailableTools()
                        .filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
                        .filterNot { it.name == "goalStatus" }

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
                            streamingAssistantStore.updateIfPresent(externalSessionId, currentAssistantMessageId) {
                                it.copy(reasoningStartMs = start, reasoningEndMs = end)
                            }
                        }
                    }

                    workflowRegistry.resolve(workflowId).executeEvents(
                        userRequest = request,
                        context = agentContext,
                        tools = tools
                    ).collect { event ->
                        when (event) {
                            is AgentEvent.RequestQueued -> setRequestQueueState(externalSessionId, RequestQueueState(event.requestsAhead))
                            AgentEvent.RequestStarted -> setRequestQueueState(externalSessionId, null)
                            is AgentEvent.AssistantDelta -> {
                                setRequestQueueState(externalSessionId, null)
                                startAssistantMessage(event.messageId)
                                if (event.accumulated.hasVisibleContent()) {
                                    finishReasoning()
                                }
                                setRetryState(externalSessionId, null)
                                setKeySwitchState(externalSessionId, null)
                                updateStreamingAssistant(externalSessionId, event.messageId) {
                                    it.copy(content = event.accumulated)
                                }
                            }
                            is AgentEvent.ReasoningDelta -> {
                                setRequestQueueState(externalSessionId, null)
                                startAssistantMessage(event.messageId)
                                setRetryState(externalSessionId, null)
                                setKeySwitchState(externalSessionId, null)
                                if (currentReasoningStart == null) {
                                    currentReasoningStart = System.currentTimeMillis()
                                    currentReasoningEnd = null
                                }
                                updateStreamingAssistant(externalSessionId, event.messageId) {
                                    it.copy(
                                        reasoning = event.accumulated,
                                        reasoningStartMs = currentReasoningStart,
                                        reasoningEndMs = currentReasoningEnd
                                    )
                                }
                            }
                            is AgentEvent.ToolCallPreparing -> {
                                finishReasoning()
                                setPreparingTool(externalSessionId, event.toolName)
                            }
                            is AgentEvent.Retrying -> {
                                clearStreamingAssistant(externalSessionId)
                                currentAssistantMessageId = null
                                currentReasoningStart = null
                                currentReasoningEnd = null
                                setRetryState(externalSessionId, RetryState(event.attempt, event.maxRetries, event.error))
                                setKeySwitchState(externalSessionId, null)
                            }
                            is AgentEvent.KeySwitched -> {
                                clearStreamingAssistant(externalSessionId)
                                currentAssistantMessageId = null
                                currentReasoningStart = null
                                currentReasoningEnd = null
                                setKeySwitchState(externalSessionId, KeySwitchState(event.newIndex, event.total))
                            }
                            is AgentEvent.CompactionStarted -> {
                                clearStreamingAssistant(externalSessionId)
                                currentAssistantMessageId = null
                                currentReasoningStart = null
                                currentReasoningEnd = null
                                setRetryState(externalSessionId, null)
                                setKeySwitchState(externalSessionId, null)
                                setCompacting(externalSessionId, true)
                                _compactionFailedSessions.update { it - externalSessionId }
                            }
                            AgentEvent.CompactionFinished -> {
                                setCompacting(externalSessionId, false)
                                val compactionFailed = externalSessionId in _compactionFailedSessions.value
                                if (!compactionFailed) _contextUsages.update { it - externalSessionId }
                                _compactionFailedSessions.update { it - externalSessionId }
                            }
                            is AgentEvent.ContextUsage -> {
                                _contextUsages.update { it + (externalSessionId to event) }
                            }
                            is AgentEvent.CompactionFailed -> {
                                setCompacting(externalSessionId, false)
                                _compactionFailedSessions.update { it + externalSessionId }
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
                                updateStreamingAssistant(externalSessionId, msgId) {
                                    it.copy(
                                        content = normalized,
                                        reasoning = reasoning.orEmpty(),
                                        settled = true,
                                        persisted = false,
                                        reasoningStartMs = currentReasoningStart,
                                        reasoningEndMs = currentReasoningEnd
                                    )
                                }
                                currentReasoningStart = null
                                currentReasoningEnd = null
                                if (event.toolCalls.isEmpty()) {
                                    setPreparingTool(externalSessionId, null)
                                }
                                if (reasoningDuration != null && reasoningDuration > 0) {
                                    _reasoningDurationEvents.tryEmit(ReasoningDurationEvent(msgId, reasoningDuration))
                                }
                                if (event.inputTokens > 0 || event.outputTokens > 0) {
                                    _llmCallEvents.tryEmit(
                                        LlmCallEvent(
                                            externalSessionId,
                                            event.inputTokens,
                                            event.outputTokens,
                                            event.cachedInputTokens
                                        )
                                    )
                                } else {
                                    _llmCallEvents.tryEmit(LlmCallEvent(externalSessionId, 0, 0, 0))
                                }
                                streamingAssistantStore.markPersisted(
                                    externalSessionId,
                                    msgId,
                                    normalized,
                                    reasoning.orEmpty()
                                )
                                event.persisted?.complete(Unit)
                                onCommittedEvent(event)
                            }
                            is AgentEvent.ToolCallStarted -> {
                                val msgId = "tool_${event.id}"
                                setPreparingTool(externalSessionId, null)
                                externalToolArgs[msgId] = event.argsPreview
                                setRunningTool(
                                    externalSessionId,
                                    msgId,
                                    RunningToolOutput(msgId, "", event.toolName, event.argsPreview)
                                )
                            }
                            is AgentEvent.ToolCallProgress -> {
                                val msgId = "tool_${event.id}"
                                setRunningTool(
                                    externalSessionId,
                                    msgId,
                                    RunningToolOutput(
                                        msgId,
                                        event.accumulated,
                                        event.toolName,
                                        externalToolArgs[msgId] ?: ""
                                    )
                                )
                            }
                            is AgentEvent.ToolCallFinished -> {
                                val msgId = "tool_${event.id}"
                                externalToolArgs.remove(msgId)
                                removeRunningTool(externalSessionId, msgId)
                                event.persisted?.complete(Unit)
                                onCommittedEvent(event)
                            }
                            is AgentEvent.Failed -> {
                                setRequestQueueState(externalSessionId, null)
                                failed = true
                                setCompacting(externalSessionId, false)
                                setAgentState(externalSessionId, AgentUIState.Error(describeFailure(event)))
                            }
                            AgentEvent.Completed -> {
                                setRequestQueueState(externalSessionId, null)
                                setRetryState(externalSessionId, null)
                                setKeySwitchState(externalSessionId, null)
                                setCompacting(externalSessionId, false)
                            }
                            is AgentEvent.ModeChanged -> {
                            }
                            is AgentEvent.TransientInstruction -> {
                            }
                            is AgentEvent.WorkflowStateChanged -> {
                            }
                        }
                    }
                    turnSucceeded = !failed
                    val finishedState = _agentStates.value[externalSessionId]
                    if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                        setAgentState(externalSessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                        _completedSessions.update { it + externalSessionId }
                    }
                } catch (e: CancellationException) {
                    turnSucceeded = false
                    val cancelledState = _agentStates.value[externalSessionId]
                    val isOwnJob = sessionJobs[externalSessionId] == coroutineContext[Job]
                    if (isOwnJob && (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)) {
                        setAgentState(externalSessionId, AgentUIState.Idle)
                        _completedSessions.update { it - externalSessionId }
                    }
                    runStatus = "cancelled"
                    throw e
                } catch (e: Exception) {
                    turnSucceeded = false
                    FileLogger.e(TAG, "launchExternalTurn 失败: sid=$externalSessionId request=$request", e)
                    runStatus = "error"
                    runError = e.toUserMessage(context)
                    setAgentState(externalSessionId, AgentUIState.Error(runError))
                    _completedSessions.update { it - externalSessionId }
                } finally {
                    val isOwnJob = synchronized(sessionJobs) {
                        val own = sessionJobs[externalSessionId] == coroutineContext[Job]
                        if (own) {
                            sessionJobs.remove(externalSessionId)
                            externalSessionIds.remove(externalSessionId)
                        }
                        own
                    }
                    if (isOwnJob) {
                        streamingAssistantStore.finishRun(externalSessionId, runToken, System.currentTimeMillis())
                    }
                    _runningTools.update { it - externalSessionId }
                    setPreparingTool(externalSessionId, null)
                    setCompacting(externalSessionId, false)
                    setRetryState(externalSessionId, null)
                    setRequestQueueState(externalSessionId, null)
                    setKeySwitchState(externalSessionId, null)

                    val finishState = _agentStates.value[externalSessionId]
                    val finishStatus = when {
                        runStatus != "success" -> runStatus
                        finishState is AgentUIState.Error -> "error"
                        else -> "success"
                    }
                    val finishError = runError ?: (finishState as? AgentUIState.Error)?.message
                    agentRunStateStore.finished(externalSessionId, finishStatus, finishError)

                    val currentState = _agentStates.value[externalSessionId]
                    if (currentState !is AgentUIState.Error &&
                        currentState !is AgentUIState.Loading &&
                        currentState !is AgentUIState.Streaming
                    ) {
                        setAgentState(externalSessionId, AgentUIState.Idle)
                    }
                    if (activeSessionIds().isEmpty()) {
                        releaseKeepalive()
                    }
                    withContext(NonCancellable) {
                        runCatching { onFinished(turnSucceeded) }
                            .onFailure {
                                FileLogger.w(TAG, "launchExternalTurn onFinished 回调失败: sid=$externalSessionId", it)
                            }
                    }
                }
            }.also { created ->
                sessionJobs[externalSessionId] = created
                created.invokeOnCompletion { streamingAssistantStore.finishRun(externalSessionId, runToken) }
            }
        }
        job.start()
        return job
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

    private fun roomTurnPersistence(sessionId: String): TurnPersistence = object : TurnPersistence {
        override suspend fun persistWorkflowState(state: String?) {
            sessionUseCase.updateWorkflowState(sessionId, state)
        }

        override suspend fun persistUserContinuation(message: AgentMessage.UserMessage) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, message.content, id = message.id)
            messagePersistenceUseCase.invalidateHistory(sessionId)
        }

        override suspend fun publishTransientInstruction(messageId: String, text: String) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.TOOL, text, id = messageId)
        }

        override suspend fun persistMode(mode: AgentMode, modeBeforePlan: AgentMode?) {
            sessionUseCase.updateMode(sessionId, mode.name)
        }

        override suspend fun recordLlmCall(stats: com.aicode.feature.agent.domain.workflow.LlmCallStats) {
            llmCallRecordDao.insert(com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity(
                sessionId = sessionId,
                providerId = stats.providerId,
                model = stats.model,
                kind = stats.kind,
                inputTokens = stats.inputTokens,
                outputTokens = stats.outputTokens,
                cachedInputTokens = stats.cachedInputTokens,
                cacheCreationTokens = stats.cacheCreationTokens,
                reasoningEffort = stats.reasoningEffort,
                reasoningWire = stats.reasoningWire,
                reasoningClampedFrom = stats.reasoningClampedFrom,
                ttfbMillis = stats.ttfbMillis,
                retryCount = stats.retryCount,
                durationMillis = stats.durationMillis,
                status = stats.status,
                errorMessage = stats.errorMessage,
                stopReason = stats.stopReason,
                createdAt = stats.createdAt
            ))
        }
    }

    private fun externalTurnPersistence(onCommittedEvent: suspend (AgentEvent) -> Unit): TurnPersistence = object : TurnPersistence {
        override suspend fun persistWorkflowState(state: String?) {
            onCommittedEvent(AgentEvent.WorkflowStateChanged(state))
        }

        override suspend fun persistUserContinuation(message: AgentMessage.UserMessage) {
            onCommittedEvent(AgentEvent.TransientInstruction(message.id, message.content))
        }

        override suspend fun publishTransientInstruction(messageId: String, text: String) {
            onCommittedEvent(AgentEvent.TransientInstruction(messageId, text))
        }

        override suspend fun persistMode(mode: AgentMode, modeBeforePlan: AgentMode?) {
            onCommittedEvent(AgentEvent.ModeChanged(mode, "workflow"))
        }

        override suspend fun recordLlmCall(stats: com.aicode.feature.agent.domain.workflow.LlmCallStats) {
            onCommittedEvent(AgentEvent.ContextUsage(stats.inputTokens, 0, false))
        }
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
        const val MAX_GOAL_CHARS = 2000
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        const val WAKE_LOCK_RENEW_MARGIN_MS = 10 * 60 * 1000L
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
    }
}
