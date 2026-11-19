package com.aicode.feature.agent.presentation.run

import android.content.Context
import android.os.PowerManager
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.workflow.AgentWorkflowRegistry
import com.aicode.feature.agent.domain.workflow.WorkflowDescriptor
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRunManagerExternalTurnTest {

    private val defaultWorkflow = mockk<AgentWorkflow>(relaxed = true)
    private val workflowRegistry = AgentWorkflowRegistry(defaultWorkflow)
    private val toolRegistry = mockk<ToolRegistry>(relaxed = true)
    private val agentMessageDao = mockk<AgentMessageDao>(relaxed = true)
    private val chatSessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val llmCallRecordDao = mockk<LlmCallRecordDao>(relaxed = true)
    private val aiProviderRepository = mockk<AIProviderRepository>(relaxed = true)
    private val modelCostCalculator = mockk<ModelCostCalculator>(relaxed = true)
    private val toolPermissionManager = mockk<ToolPermissionManager>(relaxed = true)
    private val askUserQuestionManager = mockk<AskUserQuestionManager>(relaxed = true)
    private val containerEngine = mockk<LinuxContainerEngine>(relaxed = true)
    private val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
    private val messagePersistenceUseCase = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val checkpointManager = mockk<CheckpointManager>(relaxed = true)
    private val slashCommandRegistry = mockk<SlashCommandRegistry>(relaxed = true)
    private val defaultModelSettingsRepository = mockk<DefaultModelSettingsRepository>(relaxed = true)
    private val agentChatRequestBus = mockk<AgentChatRequestBus>(relaxed = true)
    private val agentRunStateStore = AgentRunStateStore()
    private val planApprovalManager = mockk<PlanApprovalManager>(relaxed = true)
    private val terminalSessionManager = mockk<TerminalSessionManager>(relaxed = true)
    private val terminalSessionProvider = mockk<TerminalSessionProvider>(relaxed = true)
    private val agentSoundSettings = mockk<AgentSoundSettingsRepository>(relaxed = true)
    private val generalSettingsRepository = mockk<GeneralSettingsRepository>(relaxed = true)
    private val keepaliveSettings = mockk<KeepaliveSettingsRepository>(relaxed = true)
    private val liveNotificationSettings = mockk<LiveNotificationSettingsRepository>(relaxed = true)
    private val agentLiveNotificationManager = mockk<AgentLiveNotificationManager>(relaxed = true)
    private val approvalNotificationSettings = mockk<ApprovalNotificationSettingsRepository>(relaxed = true)
    private val agentApprovalNotificationManager = mockk<AgentApprovalNotificationManager>(relaxed = true)
    private val subAgentEventBus = mockk<SubAgentEventBus>(relaxed = true)
    private val agentNotificationCenter = mockk<AgentNotificationCenter>(relaxed = true)
    private val agentDefinitionRepository = mockk<AgentDefinitionRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val powerManager = mockk<PowerManager>(relaxed = true)
    private val wakeLock = mockk<PowerManager.WakeLock>(relaxed = true)

    private lateinit var manager: AgentRunManager

    @Before
    fun setUp() {
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock
        every { toolRegistry.getAvailableTools() } returns emptyList()
        every { defaultWorkflow.executeEvents(any(), any(), any()) } returns emptyFlow()

        manager = AgentRunManager(
            workflowRegistry = workflowRegistry,
            toolRegistry = toolRegistry,
            agentMessageDao = agentMessageDao,
            chatSessionDao = chatSessionDao,
            llmCallRecordDao = llmCallRecordDao,
            aiProviderRepository = aiProviderRepository,
            modelCostCalculator = modelCostCalculator,
            toolPermissionManager = toolPermissionManager,
            askUserQuestionManager = askUserQuestionManager,
            containerEngine = containerEngine,
            executionBackendRegistry = mockk(relaxed = true),
            sessionUseCase = sessionUseCase,
            messagePersistenceUseCase = messagePersistenceUseCase,
            checkpointManager = checkpointManager,
            slashCommandRegistry = slashCommandRegistry,
            defaultModelSettingsRepository = defaultModelSettingsRepository,
            agentChatRequestBus = agentChatRequestBus,
            agentRunStateStore = agentRunStateStore,
            planApprovalManager = planApprovalManager,
            terminalSessionManager = terminalSessionManager,
            terminalSessionProvider = terminalSessionProvider,
            agentSoundSettings = agentSoundSettings,
            generalSettingsRepository = generalSettingsRepository,
            keepaliveSettings = keepaliveSettings,
            liveNotificationSettings = liveNotificationSettings,
            agentLiveNotificationManager = agentLiveNotificationManager,
            approvalNotificationSettings = approvalNotificationSettings,
            agentApprovalNotificationManager = agentApprovalNotificationManager,
            subAgentEventBus = subAgentEventBus,
            agentNotificationCenter = agentNotificationCenter,
            agentDefinitionRepository = agentDefinitionRepository,
            context = context
        )
    }

    @Test
    fun launchExternalTurn_streamsPluginWorkflowWithoutWritingRoom() = runBlocking {
        val pluginWorkflow = mockk<AgentWorkflow>(relaxed = true)
        val workflowId = "plugin.remote:agent"
        val extSessionId = "ext:plugin.remote:s1"
        workflowRegistry.syncPluginWorkflows(
            "plugin.remote",
            listOf(WorkflowDescriptor(workflowId, "外部工作流") to pluginWorkflow)
        )

        val deltaGate = CompletableDeferred<Unit>()
        val toolGate = CompletableDeferred<Unit>()
        val finishGate = CompletableDeferred<Unit>()
        val assistantPersisted = CompletableDeferred<Unit>()
        val toolPersisted = CompletableDeferred<Unit>()
        val committedEvents = mutableListOf<AgentEvent>()
        var finishedSuccess: Boolean? = null
        val contextSlot = slot<AgentContext>()

        every { pluginWorkflow.executeEvents("请修复单测", capture(contextSlot), any()) } returns flow {
            emit(AgentEvent.Retrying(1, 3, RetryErrorInfo(RetryErrorKind.TIMEOUT)))
            emit(AgentEvent.KeySwitched(2, 3))
            emit(AgentEvent.CompactionStarted(1200))
            emit(AgentEvent.CompactionFinished)
            emit(AgentEvent.ContextUsage(inputTokens = 640, inputBudget = 8000, estimated = false))
            emit(AgentEvent.ReasoningDelta("先查看文件", "msg-1"))
            emit(AgentEvent.AssistantDelta("正在处理", "msg-1"))
            deltaGate.complete(Unit)

            emit(AgentEvent.ToolCallPreparing("bash"))
            emit(AgentEvent.ToolCallStarted("call-1", "bash", "{\"command\":\"ls\"}"))
            emit(AgentEvent.ToolCallProgress("call-1", "bash", "fileA.kt\n"))
            toolGate.complete(Unit)
            finishGate.await()

            emit(
                AgentEvent.ToolCallFinished(
                    id = "call-1",
                    toolName = "bash",
                    result = "fileA.kt",
                    isError = false,
                    persisted = toolPersisted
                )
            )
            emit(
                AgentEvent.AssistantText(
                    content = "已完成检查",
                    reasoning = "先查看文件",
                    inputTokens = 100,
                    outputTokens = 25,
                    messageId = "msg-1",
                    persisted = assistantPersisted
                )
            )
            emit(AgentEvent.Completed)
        }

        val history = listOf(AgentMessage.UserMessage(id = "u0", content = "上一轮"))
        val job = manager.launchExternalTurn(
            externalSessionId = extSessionId,
            workflowId = workflowId,
            request = "请修复单测",
            workspacePath = "/remote/proj",
            history = history,
            providerId = "ext-provider",
            model = "ext-model",
            mode = AgentMode.PLAN,
            onCommittedEvent = { committedEvents += it },
            onFinished = { succeeded -> finishedSuccess = succeeded }
        )

        withTimeout(3000) { deltaGate.await() }
        assertTrue(manager.agentStates.value[extSessionId] is AgentUIState.Streaming)
        val streaming = manager.streamingAssistants.value[extSessionId]
        assertNotNull(streaming)
        assertEquals("正在处理", streaming?.content)
        assertEquals("先查看文件", streaming?.reasoning)

        withTimeout(3000) { toolGate.await() }
        val runningTool = manager.runningTools.value[extSessionId]?.get("tool_call-1")
        assertNotNull(runningTool)
        assertEquals("bash", runningTool?.toolName)
        assertEquals("fileA.kt\n", runningTool?.text)
        assertEquals("{\"command\":\"ls\"}", runningTool?.toolArgs)
        assertEquals(640, manager.contextUsages.value[extSessionId]?.inputTokens)

        finishGate.complete(Unit)
        withTimeout(3000) { job.join() }

        assertTrue(toolPersisted.isCompleted)
        assertTrue(assistantPersisted.isCompleted)
        assertEquals(true, finishedSuccess)
        assertEquals(2, committedEvents.size)
        assertTrue(committedEvents[0] is AgentEvent.ToolCallFinished)
        assertTrue(committedEvents[1] is AgentEvent.AssistantText)
        assertTrue(manager.agentStates.value[extSessionId] is AgentUIState.Idle)
        assertTrue(manager.runningTools.value[extSessionId].isNullOrEmpty())
        assertEquals("已完成检查", manager.streamingAssistants.value[extSessionId]?.content)
        assertEquals(true, manager.streamingAssistants.value[extSessionId]?.persisted)
        assertEquals(extSessionId, contextSlot.captured.sessionId)
        assertEquals("/remote/proj", contextSlot.captured.projectRoot)
        assertEquals(AgentMode.PLAN, contextSlot.captured.mode)
        assertEquals(history, contextSlot.captured.history)

        verify(exactly = 0) { defaultWorkflow.executeEvents(any(), any(), any()) }
        verify {
            chatSessionDao wasNot Called
            agentMessageDao wasNot Called
            checkpointManager wasNot Called
            messagePersistenceUseCase wasNot Called
        }
        coVerify(exactly = 0) {
            sessionUseCase.touch(any(), any())
        }
    }

    @Test
    fun launchExternalTurn_preventsConcurrentRunsForSameSession() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val extSessionId = "ext:dup"
        every { defaultWorkflow.executeEvents("first", any(), any()) } returns flow {
            gate.await()
            emit(AgentEvent.Completed)
        }

        val firstJob = manager.launchExternalTurn(
            externalSessionId = extSessionId,
            workflowId = null,
            request = "first",
            workspacePath = "/tmp/ws"
        )
        val secondJob = manager.launchExternalTurn(
            externalSessionId = extSessionId,
            workflowId = null,
            request = "second",
            workspacePath = "/tmp/ws"
        )

        assertSame(firstJob, secondJob)
        gate.complete(Unit)
        withTimeout(3000) { firstJob.join() }

        verify(exactly = 1) { defaultWorkflow.executeEvents("first", any(), any()) }
        verify(exactly = 0) { defaultWorkflow.executeEvents("second", any(), any()) }
    }

    @Test
    fun launchExternalTurn_failureAndStopDoNotTouchRoom() = runBlocking {
        val failSessionId = "ext:fail"
        var failFinished: Boolean? = null
        every { defaultWorkflow.executeEvents("boom", any(), any()) } returns flow {
            emit(AgentEvent.Failed("remote error"))
        }

        val failJob = manager.launchExternalTurn(
            externalSessionId = failSessionId,
            workflowId = null,
            request = "boom",
            workspacePath = "/tmp/ws",
            onFinished = { succeeded -> failFinished = succeeded }
        )
        withTimeout(3000) { failJob.join() }

        assertEquals(false, failFinished)
        assertEquals(AgentUIState.Error("remote error"), manager.agentStates.value[failSessionId])

        val stopSessionId = "ext:stop"
        val startedGate = CompletableDeferred<Unit>()
        val hangGate = CompletableDeferred<Unit>()
        var stopFinished: Boolean? = null
        every { defaultWorkflow.executeEvents("hang", any(), any()) } returns flow {
            emit(AgentEvent.AssistantDelta("partial", "m-stop"))
            emit(AgentEvent.ToolCallStarted("c-stop", "bash", "{}"))
            startedGate.complete(Unit)
            hangGate.await()
        }

        val stopJob = manager.launchExternalTurn(
            externalSessionId = stopSessionId,
            workflowId = null,
            request = "hang",
            workspacePath = "/tmp/ws",
            onFinished = { succeeded -> stopFinished = succeeded }
        )
        withTimeout(3000) { startedGate.await() }
        manager.stopAgentSession(stopSessionId)
        withTimeout(3000) { stopJob.join() }

        assertEquals(false, stopFinished)
        assertFalse(manager.isSessionRunning(stopSessionId))
        assertTrue(manager.agentStates.value[stopSessionId] is AgentUIState.Idle)
        assertTrue(manager.runningTools.value[stopSessionId].isNullOrEmpty())

        verify {
            chatSessionDao wasNot Called
            agentMessageDao wasNot Called
            checkpointManager wasNot Called
            messagePersistenceUseCase wasNot Called
        }
    }
}
