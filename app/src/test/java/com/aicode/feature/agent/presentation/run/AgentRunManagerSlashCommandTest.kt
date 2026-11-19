package com.aicode.feature.agent.presentation.run

import android.content.Context
import android.os.PowerManager
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.CallSummary
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.command.CompressCommandHandler
import com.aicode.feature.agent.domain.command.GoalCommandHandler
import com.aicode.feature.agent.domain.command.InitCommandHandler
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.UsageCommandHandler
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
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
import com.aicode.feature.agent.presentation.MessageRole
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRunManagerSlashCommandTest {

    private val agentWorkflow = mockk<AgentWorkflow>(relaxed = true)
    private val workflowRegistry = AgentWorkflowRegistry(agentWorkflow)
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
    private val skillRepository = mockk<SkillRepository>(relaxed = true)
    private val systemPromptProvider = mockk<SystemPromptProvider>(relaxed = true)
    private val defaultModelSettingsRepository = mockk<DefaultModelSettingsRepository>(relaxed = true)
    private val agentChatRequestBus = mockk<AgentChatRequestBus>(relaxed = true)
    private val agentRunStateStore = mockk<AgentRunStateStore>(relaxed = true)
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

    private lateinit var slashCommandRegistry: SlashCommandRegistry
    private lateinit var manager: AgentRunManager

    @Volatile
    private var skillsForTest: List<Skill> = emptyList()

    private val sessionId = "session-1"
    private val workspace = "/root/workspace"

    @Before
    fun setUp() {
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock
        every { context.getString(R.string.slash_command_compress_desc) } returns "压缩上下文"
        every { context.getString(R.string.slash_command_usage_desc) } returns "查看用量"
        every { context.getString(R.string.slash_command_init_desc) } returns "初始化规则"
        every { context.getString(R.string.slash_command_goal_desc) } returns "设定目标"
        every { context.getString(R.string.agent_context_no_compaction) } returns "无需压缩"
        every { skillRepository.listSkills() } answers { skillsForTest }
        every { toolRegistry.getAvailableTools() } returns emptyList()
        coEvery { defaultModelSettingsRepository.getDefaultProviderId() } returns "p1"
        coEvery { defaultModelSettingsRepository.getDefaultModel() } returns "m1"
        every { systemPromptProvider.resolvePrompt(InitCommandHandler.PROMPT_NAME) } returns "init prompt \$ARGUMENTS"
        every { llmCallRecordDao.getSummary(any()) } returns flowOf(
            CallSummary(0, 0, 0, 0, 0, 0, null, null, 0)
        )
        every { llmCallRecordDao.getModelProviderCostStats(any()) } returns flowOf(emptyList())
        every { aiProviderRepository.getAllProviders() } returns flowOf(emptyList())
        every { agentNotificationCenter.peek(any()) } returns emptyList()
        every { agentWorkflow.executeEvents(any(), any(), any()) } returns emptyFlow()

        coEvery { sessionUseCase.getSessionById(sessionId) } returns ChatSessionEntity(
            id = sessionId,
            title = "测试会话",
            workspacePath = workspace,
            createdAt = 1L,
            updatedAt = 1L,
            providerId = "p1",
            model = "m1",
            mode = AgentMode.BUILD.name
        )
        coEvery { messagePersistenceUseCase.buildHistory(any(), any()) } returns emptyList()

        slashCommandRegistry = SlashCommandRegistry(
            actionHandlers = setOf(
                CompressCommandHandler(),
                UsageCommandHandler(),
                InitCommandHandler(systemPromptProvider),
                GoalCommandHandler()
            ),
            skillRepository = skillRepository,
            context = context
        )
        runBlocking { slashCommandRegistry.refresh() }

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
            executionBackendRegistry = mockk<com.aicode.feature.agent.domain.container.ExecutionBackendRegistry>(relaxed = true),
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
    fun enqueueCompress_runsCompactionAndQueuesSubsequentMessagesUntilFinished() {
        val compactionGate = CompletableDeferred<Unit>()
        coEvery {
            agentWorkflow.compactSession(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()
            )
        } coAnswers {
            compactionGate.await()
            true
        }

        manager.enqueueAgentRequest(
            request = "/compress",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/compress", id = any())
            agentWorkflow.compactSession(any<com.aicode.feature.agent.domain.model.AgentContext>(), any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>())
        }
        assertTrue(sessionId in manager.runningCommandSessions.value)

        // 压缩未完成前新消息入队，不立即触发 executeEvents
        manager.enqueueAgentRequest(
            request = "hello after compress",
            projectRoot = workspace,
            targetSessionId = sessionId
        )
        coVerify(exactly = 0) { agentWorkflow.executeEvents("hello after compress", any(), any()) }

        compactionGate.complete(Unit)

        coVerify(timeout = 2000) {
            agentWorkflow.executeEvents("hello after compress", any(), any())
        }
    }

    @Test
    fun enqueueUsage_persistsMarkdownSummaryWithoutInvokingWorkflow() {
        manager.enqueueAgentRequest(
            request = "/usage",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/usage", id = any())
            messagePersistenceUseCase.persist(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = match { it.contains("| 调用次数 |") && it.contains("| 预估费用 |") },
                id = any(),
                isContextExcluded = true
            )
        }
        coVerify(exactly = 0) { agentWorkflow.executeEvents(any(), any(), any()) }
    }

    @Test
    fun enqueueSkillLeadingSlash_dispatchesSkillBodyAndDoesNotSwallowAsCommand() {
        skillsForTest = listOf(
            Skill(
                name = "deploy",
                description = "部署",
                instructions = "/usr/bin/deploy \$ARGUMENTS"
            )
        )
        runBlocking { slashCommandRegistry.refresh() }

        manager.enqueueAgentRequest(
            request = "/deploy prod",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/deploy prod", id = any())
            agentWorkflow.executeEvents("/usr/bin/deploy prod", any(), any())
        }
    }

    @Test
    fun enqueueGoal_updatesGoalAndStartsFirstTurn() {
        manager.enqueueAgentRequest(
            request = "/goal 完成单测",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/goal 完成单测", id = any())
            sessionUseCase.setGoal(sessionId, "完成单测", any(), any())
            agentWorkflow.executeEvents(match { it.contains("继续推进目标：完成单测") }, any(), any())
        }
    }

    @Test
    fun enqueueInit_persistsUserCommandAndRunsResolvedPrompt() {
        manager.enqueueAgentRequest(
            request = "/init focus on tests",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/init focus on tests", id = any())
            agentWorkflow.executeEvents("init prompt focus on tests", any(), any())
        }
    }

    @Test
    fun enqueueCompressWhileBusy_queuesAndCompactsAfterActiveTurnFinishes() {
        val turnGate = CompletableDeferred<Unit>()
        every { agentWorkflow.executeEvents("running turn", any(), any()) } returns flow {
            turnGate.await()
        }
        coEvery {
            agentWorkflow.compactSession(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()
            )
        } returns true

        manager.enqueueAgentRequest(
            request = "running turn",
            projectRoot = workspace,
            targetSessionId = sessionId
        )
        coVerify(timeout = 2000) {
            agentWorkflow.executeEvents("running turn", any(), any())
        }

        manager.enqueueAgentRequest(
            request = "/compress",
            projectRoot = workspace,
            targetSessionId = sessionId
        )
        coVerify(exactly = 0) { agentWorkflow.compactSession(any<com.aicode.feature.agent.domain.model.AgentContext>(), any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()) }

        turnGate.complete(Unit)

        coVerify(timeout = 2000) {
            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, "/compress", id = any())
            agentWorkflow.compactSession(any<com.aicode.feature.agent.domain.model.AgentContext>(), any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>())
        }
    }

    @Test
    fun enqueueUnknownSlashText_fallsBackToNormalUserMessage() {
        manager.enqueueAgentRequest(
            request = "/etc/hosts",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            agentWorkflow.executeEvents("/etc/hosts", any(), any())
        }
    }

    @Test
    fun routeBySessionWorkflowId_usesPluginWorkflowForExecuteCompactAndTitle() {
        val pluginWorkflow = mockk<AgentWorkflow>(relaxed = true)
        val pluginWorkflowId = "plugin-a:custom-workflow"
        workflowRegistry.syncPluginWorkflows(
            "plugin-a",
            listOf(WorkflowDescriptor(pluginWorkflowId, "自定义工作流") to pluginWorkflow)
        )
        every { pluginWorkflow.executeEvents(any(), any(), any()) } returns flow {
            emit(AgentEvent.AssistantDelta("ok", "m1"))
            emit(AgentEvent.Completed)
        }
        coEvery {
            pluginWorkflow.generateTitle(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<String>()
            )
        } returns "自定义标题"
        coEvery {
            pluginWorkflow.compactSession(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()
            )
        } returns true
        coEvery { sessionUseCase.getSessionById(sessionId) } returns ChatSessionEntity(
            id = sessionId,
            title = "测试会话",
            workspacePath = workspace,
            createdAt = 1L,
            updatedAt = 1L,
            providerId = "p1",
            model = "m1",
            mode = AgentMode.BUILD.name,
            workflowId = pluginWorkflowId
        )

        manager.enqueueAgentRequest(
            request = "hello plugin workflow",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            pluginWorkflow.executeEvents(
                "hello plugin workflow",
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<List<com.aicode.feature.agent.domain.tool.AgentTool>>()
            )
            pluginWorkflow.generateTitle(any<com.aicode.feature.agent.domain.model.AgentContext>(), "hello plugin workflow")
            sessionUseCase.updateTitle(sessionId, "自定义标题")
        }
        coVerify(exactly = 0) {
            agentWorkflow.executeEvents(
                any<String>(),
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<List<com.aicode.feature.agent.domain.tool.AgentTool>>()
            )
            agentWorkflow.generateTitle(any<com.aicode.feature.agent.domain.model.AgentContext>(), any<String>())
        }

        manager.enqueueAgentRequest(
            request = "/compress",
            projectRoot = workspace,
            targetSessionId = sessionId
        )

        coVerify(timeout = 2000) {
            pluginWorkflow.compactSession(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()
            )
        }
        coVerify(exactly = 0) {
            agentWorkflow.compactSession(
                any<com.aicode.feature.agent.domain.model.AgentContext>(),
                any<suspend (com.aicode.feature.agent.domain.workflow.AgentEvent) -> Unit>()
            )
        }
    }
}
