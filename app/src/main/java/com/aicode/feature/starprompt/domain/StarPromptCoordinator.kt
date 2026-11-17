package com.aicode.feature.starprompt.domain

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.run.AgentRunManager
import com.aicode.feature.starprompt.data.StarPromptRecord
import com.aicode.feature.starprompt.data.StarPromptRepository
import com.aicode.feature.starprompt.data.StarPromptStatus
import com.aicode.feature.starprompt.data.StarPromptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 宿主 UI 门控快照：所有条件满足时才允许弹出求 Star 弹窗。 */
data class StarPromptUiGate(
    val agreementAccepted: Boolean = false,
    val onboardingActive: Boolean = true,
    val updateDialogVisible: Boolean = false,
    val pluginActionPending: Boolean = false,
    val activeSlotPageOpen: Boolean = false,
    val drawerOpen: Boolean = false,
    val currentRoute: String? = null
) {
    fun allowsPrompt(manual: Boolean = false): Boolean =
        if (manual) {
            agreementAccepted &&
                !onboardingActive &&
                !updateDialogVisible
        } else {
            agreementAccepted &&
                !onboardingActive &&
                !updateDialogVisible &&
                !pluginActionPending &&
                !activeSlotPageOpen &&
                !drawerOpen &&
                currentRoute == CHAT_ROUTE
        }

    companion object {
        const val CHAT_ROUTE = "chat"
    }
}

/**
 * 求 Star 弹窗协调器：
 * 1. 追踪进程级前台使用时长并周期落盘，累计满 3 小时后获得首次弹出资格；
 * 2. 点击「下次一定」后按 3 -> 7 -> 14 天阶梯推迟（之后每 14 天循环）；
 * 3. 弹出前严格门控：AI 必须处于完全空闲态（无运行中会话、无排队请求、无待审批/提问）、
 *    用户停留在聊天主界面且无其它全局弹窗遮挡，并经过短暂防抖后再展示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class StarPromptCoordinator internal constructor(
    private val store: StarPromptStore,
    private val aiBusyFlow: Flow<Boolean>,
    private val isForegroundFlow: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val wallClockMs: () -> Long,
    private val triggerThresholdMs: Long = StarPromptRepository.TRIGGER_FOREGROUND_MS,
    private val foregroundSettleMs: Long = DEFAULT_FOREGROUND_SETTLE_MS,
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
    private val idleDebounceMs: Long = DEFAULT_IDLE_DEBOUNCE_MS
) {

    @Inject
    constructor(
        repository: StarPromptRepository,
        agentRunStateStore: AgentRunStateStore,
        agentRunManager: AgentRunManager,
        toolPermissionManager: ToolPermissionManager,
        askUserQuestionManager: AskUserQuestionManager,
        planApprovalManager: PlanApprovalManager
    ) : this(
        store = repository,
        aiBusyFlow = combineAiBusyFlow(
            agentRunStateStore = agentRunStateStore,
            agentRunManager = agentRunManager,
            toolPermissionManager = toolPermissionManager,
            askUserQuestionManager = askUserQuestionManager,
            planApprovalManager = planApprovalManager
        ),
        isForegroundFlow = observeProcessForeground(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        elapsedRealtimeMs = { SystemClock.elapsedRealtime() },
        wallClockMs = { System.currentTimeMillis() }
    )

    companion object {
        const val GITHUB_REPO_URL = "https://github.com/jieapi/aicode"
        const val DEFAULT_FOREGROUND_SETTLE_MS = 30_000L
        const val DEFAULT_PERSIST_INTERVAL_MS = 30_000L
        const val DEFAULT_IDLE_DEBOUNCE_MS = 1_500L

        private fun observeProcessForeground(): StateFlow<Boolean> {
            val state = MutableStateFlow(false)
            Handler(Looper.getMainLooper()).post {
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                state.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        state.value = true
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        state.value = false
                    }
                })
            }
            return state.asStateFlow()
        }

        private fun combineAiBusyFlow(
            agentRunStateStore: AgentRunStateStore,
            agentRunManager: AgentRunManager,
            toolPermissionManager: ToolPermissionManager,
            askUserQuestionManager: AskUserQuestionManager,
            planApprovalManager: PlanApprovalManager
        ): Flow<Boolean> {
            val executionBusyFlow = combine(
                agentRunStateStore.states,
                agentRunManager.agentStates,
                agentRunManager.queuedRequests
            ) { runStates, uiStates, queues ->
                runStates.values.any { it.running } ||
                    uiStates.values.any { it is AgentUIState.Loading || it is AgentUIState.Streaming } ||
                    queues.values.any { it.isNotEmpty() }
            }
            val interactionBusyFlow = combine(
                toolPermissionManager.awaitingSessionIds,
                askUserQuestionManager.pendingQuestion,
                planApprovalManager.pendingApproval
            ) { awaiting, question, plan ->
                awaiting.isNotEmpty() || question != null || plan != null
            }
            return combine(executionBusyFlow, interactionBusyFlow) { execBusy, interBusy ->
                execBusy || interBusy
            }.distinctUntilChanged()
        }
    }

    private val _uiGate = MutableStateFlow(StarPromptUiGate())
    private val _autoDialogVisible = MutableStateFlow(false)
    private val _manualDialogVisible = MutableStateFlow(false)
    val isManualPrompt: StateFlow<Boolean> = _manualDialogVisible.asStateFlow()
    val dialogVisible: StateFlow<Boolean> = combine(_autoDialogVisible, _manualDialogVisible) { auto, manual ->
        auto || manual
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _evaluationTick = MutableStateFlow(0L)

    private val elapsedLock = Any()

    @Volatile
    private var foregroundEnteredElapsedMs: Long? = null

    @Volatile
    private var lastPersistedElapsedMs: Long? = null

    private var foregroundTickerJob: Job? = null

    init {
        startForegroundTracking()
        startEligibilityEvaluation()
    }

    fun updateUiGate(gate: StarPromptUiGate) {
        _uiGate.value = gate
    }

    /** 从「设置 → 关于」手动调起求 Star 弹窗（不受 3 小时/冷却期与聊天路由限制）。 */
    fun showManual() {
        _manualDialogVisible.value = true
    }

    /** 用户点击「前往 GitHub 点 Star」：关闭弹窗并永久不再提示。 */
    fun onGoToStar() {
        _autoDialogVisible.value = false
        _manualDialogVisible.value = false
        scope.launch {
            store.markStarred()
        }
    }

    /** 用户点击「下次一定」或关闭弹窗：自动触发时按 3 -> 7 -> 14 天阶梯推迟；纯手动预览仅关闭弹窗。 */
    fun onSnooze() {
        val wasAuto = _autoDialogVisible.value
        _autoDialogVisible.value = false
        _manualDialogVisible.value = false
        if (wasAuto) {
            val now = wallClockMs()
            scope.launch {
                store.markSnoozed(now)
            }
        }
    }

    /** 用户点击「不再提示」：关闭弹窗并永久静默。 */
    fun onDismissForever() {
        _autoDialogVisible.value = false
        _manualDialogVisible.value = false
        scope.launch {
            store.markDismissed()
        }
    }

    private fun startForegroundTracking() {
        scope.launch {
            isForegroundFlow.collect { foreground ->
                if (foreground) {
                    val now = elapsedRealtimeMs()
                    synchronized(elapsedLock) {
                        foregroundEnteredElapsedMs = now
                        lastPersistedElapsedMs = now
                    }
                    startForegroundTicker()
                    _evaluationTick.value++
                } else {
                    stopForegroundTickerAndFlush()
                    synchronized(elapsedLock) {
                        foregroundEnteredElapsedMs = null
                        lastPersistedElapsedMs = null
                    }
                    _evaluationTick.value++
                }
            }
        }
    }

    private fun startForegroundTicker() {
        foregroundTickerJob?.cancel()
        foregroundTickerJob = scope.launch {
            while (isActive && isForegroundFlow.value) {
                val record = store.recordFlow.first()
                if (record.status == StarPromptStatus.STARRED || record.status == StarPromptStatus.DISMISSED) {
                    break
                }
                val nextDelay = computeNextWakeupDelayMs(record)
                delay(nextDelay)
                if (!isActive || !isForegroundFlow.value) break
                flushElapsedForeground()
                _evaluationTick.value++
            }
        }
    }

    private fun stopForegroundTickerAndFlush() {
        foregroundTickerJob?.cancel()
        foregroundTickerJob = null
        val delta = synchronized(elapsedLock) {
            val last = lastPersistedElapsedMs ?: return
            lastPersistedElapsedMs = null
            (elapsedRealtimeMs() - last).coerceAtLeast(0L)
        }
        if (delta > 0L) {
            scope.launch {
                store.addForegroundDuration(delta)
            }
        }
    }

    private suspend fun flushElapsedForeground() {
        val delta = synchronized(elapsedLock) {
            val last = lastPersistedElapsedMs ?: return
            val now = elapsedRealtimeMs()
            lastPersistedElapsedMs = now
            (now - last).coerceAtLeast(0L)
        }
        if (delta > 0L) {
            store.addForegroundDuration(delta)
        }
    }

    private fun computeNextWakeupDelayMs(record: StarPromptRecord): Long {
        val nowElapsed = elapsedRealtimeMs()
        val entered = foregroundEnteredElapsedMs ?: nowElapsed
        val unsaved = (nowElapsed - (lastPersistedElapsedMs ?: nowElapsed)).coerceAtLeast(0L)
        val settleRemaining = (foregroundSettleMs - (nowElapsed - entered)).coerceAtLeast(0L)

        val candidates = mutableListOf(persistIntervalMs)
        if (settleRemaining > 0L) {
            candidates += settleRemaining
        }
        when (record.status) {
            StarPromptStatus.TRACKING -> {
                val remainingThreshold = (triggerThresholdMs - (record.accumulatedForegroundMs + unsaved))
                    .coerceAtLeast(0L)
                if (remainingThreshold > 0L) {
                    candidates += remainingThreshold
                }
            }
            StarPromptStatus.SNOOZED -> {
                val remainingSnooze = (record.nextPromptAtMs - wallClockMs()).coerceAtLeast(0L)
                if (remainingSnooze in 1L..persistIntervalMs) {
                    candidates += remainingSnooze
                }
            }
            StarPromptStatus.STARRED, StarPromptStatus.DISMISSED -> Unit
        }
        return (candidates.minOrNull() ?: persistIntervalMs).coerceAtLeast(50L)
    }

    private fun startEligibilityEvaluation() {
        val canPromptNowFlow = combine(
            combine(store.recordFlow, isForegroundFlow, aiBusyFlow) { record, foreground, aiBusy ->
                Triple(record, foreground, aiBusy)
            },
            combine(_uiGate, _autoDialogVisible, _manualDialogVisible, _evaluationTick) { gate, autoVisible, manualVisible, _ ->
                Triple(gate, autoVisible, manualVisible)
            }
        ) { (record, foreground, aiBusy), (gate, autoVisible, manualVisible) ->
            canTriggerPrompt(
                record = record,
                isForeground = foreground,
                aiBusy = aiBusy,
                uiGate = gate,
                dialogVisible = autoVisible || manualVisible
            )
        }.distinctUntilChanged()

        scope.launch {
            canPromptNowFlow.collectLatest { ready ->
                if (!ready) return@collectLatest
                if (idleDebounceMs > 0L) {
                    delay(idleDebounceMs)
                }
                flushElapsedForeground()
                val latestRecord = store.recordFlow.first()
                val stillReady = canTriggerPrompt(
                    record = latestRecord,
                    isForeground = isForegroundFlow.value,
                    aiBusy = aiBusyFlow.first(),
                    uiGate = _uiGate.value,
                    dialogVisible = _autoDialogVisible.value || _manualDialogVisible.value
                )
                if (stillReady) {
                    _autoDialogVisible.value = true
                }
            }
        }
    }

    @VisibleForTesting
    internal fun canTriggerPrompt(
        record: StarPromptRecord,
        isForeground: Boolean,
        aiBusy: Boolean,
        uiGate: StarPromptUiGate,
        dialogVisible: Boolean
    ): Boolean {
        if (dialogVisible || !isForeground || aiBusy || !uiGate.allowsPrompt()) {
            return false
        }
        val entered = foregroundEnteredElapsedMs ?: return false
        val nowElapsed = elapsedRealtimeMs()
        if (nowElapsed - entered < foregroundSettleMs) {
            return false
        }
        return when (record.status) {
            StarPromptStatus.STARRED, StarPromptStatus.DISMISSED -> false
            StarPromptStatus.TRACKING -> {
                val unsaved = (nowElapsed - (lastPersistedElapsedMs ?: nowElapsed)).coerceAtLeast(0L)
                record.accumulatedForegroundMs + unsaved >= triggerThresholdMs
            }
            StarPromptStatus.SNOOZED -> wallClockMs() >= record.nextPromptAtMs
        }
    }
}
