package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.session.AgentChatRequest
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.AgentRunStateStore
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginChatServiceTest {

    private val pluginId = "demo.chat"
    private val workspacePath = "/workspace/project"

    private fun activePlugin(): InstalledPlugin = mockk {
        every { id } returns pluginId
        every { isActive } returns true
    }

    private fun session(
        id: String = "session-1",
        workspace: String = workspacePath,
        mode: String = AgentMode.BUILD.name
    ) = ChatSessionEntity(
        id = id,
        title = "会话",
        createdAt = 1L,
        updatedAt = 2L,
        workspacePath = workspace,
        mode = mode
    )

    private fun message(id: String, timestamp: Long, content: String = id) = AgentMessageEntity(
        id = id,
        sessionId = "session-1",
        role = "user",
        content = content,
        timestamp = timestamp
    )

    private fun service(
        bus: AgentChatRequestBus = AgentChatRequestBus(),
        sessionDao: ChatSessionDao = mockk(relaxed = true),
        messageDao: AgentMessageDao = mockk(relaxed = true),
        sessionUseCase: SessionUseCase = mockk(relaxed = true),
        runStateStore: AgentRunStateStore = AgentRunStateStore(),
        toolPermissionManager: ToolPermissionManager = mockk(relaxed = true),
        askUserQuestionManager: AskUserQuestionManager = mockk(relaxed = true),
        planApprovalManager: PlanApprovalManager = mockk(relaxed = true),
        workspace: String? = workspacePath
    ): PluginChatService {
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.plugins } returns MutableStateFlow(listOf(activePlugin()))

        val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaceRepository.current } returns MutableStateFlow(
            workspace?.let { Workspace(name = "project", path = it) }
        )

        val defaultModel = mockk<DefaultModelSettingsRepository>(relaxed = true)
        coEvery { defaultModel.getDefaultProviderId() } returns "provider"
        coEvery { defaultModel.getDefaultModel() } returns "model"

        val reasoning = mockk<ModelReasoningEffortRepository>(relaxed = true)
        every { reasoning.get("provider", "model") } returns "high"

        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.chat_new_session_btn) } returns "New Chat"
        every { askUserQuestionManager.pendingQuestion } returns MutableStateFlow(null)

        return PluginChatService(
            context = context,
            pluginManager = pluginManager,
            workspaceRepository = workspaceRepository,
            sessionUseCase = sessionUseCase,
            chatSessionDao = sessionDao,
            agentMessageDao = messageDao,
            defaultModelSettingsRepository = defaultModel,
            modelReasoningEffortRepository = reasoning,
            requestBus = bus,
            runStateStore = runStateStore,
            toolPermissionManager = toolPermissionManager,
            askUserQuestionManager = askUserQuestionManager,
            planApprovalManager = planApprovalManager
        )
    }

    @Test
    fun createSession_usesCurrentWorkspaceAndDefaultModel() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        every {
            sessionUseCase.newSessionEntity(
                workspacePath,
                "provider",
                "model",
                "high"
            )
        } returns session()
        coEvery { sessionUseCase.upsertSession(any()) } just Runs
        val service = service(sessionUseCase = sessionUseCase)

        val result = service.createSession(pluginId, buildJsonObject { put("mode", "plan") }) as JsonObject
        val created = result["session"]!!.jsonObject

        assertEquals("session-1", created["id"]!!.jsonPrimitive.content)
        assertEquals("New Chat", created["title"]!!.jsonPrimitive.content)
        assertEquals("plan", created["mode"]!!.jsonPrimitive.content)
        coVerify {
            sessionUseCase.upsertSession(match { it.workspacePath == workspacePath && it.mode == AgentMode.PLAN.name })
        }
    }

    @Test
    fun createSession_rejectsAutoMode() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        val service = service(sessionUseCase = sessionUseCase)

        val error = runCatching {
            service.createSession(pluginId, buildJsonObject { put("mode", "auto") })
        }.exceptionOrNull()

        assertEquals("MODE_UNSUPPORTED", (error as PluginHostException).code)
        coVerify(exactly = 0) { sessionUseCase.upsertSession(any()) }
    }

    @Test
    fun sendMessage_rejectsWhenChatUiIsUnavailable() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(sessionUseCase = sessionUseCase)

        val error = runCatching {
            service.sendMessage(pluginId, buildJsonObject {
                put("sessionId", "session-1")
                put("text", "hello")
            })
        }.exceptionOrNull()

        assertEquals("CHAT_UI_UNAVAILABLE", (error as PluginHostException).code)
    }

    @Test
    fun sendMessage_submitsSendRequest() = runTest {
        val bus = AgentChatRequestBus().apply { attach("test") }
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(bus = bus, sessionUseCase = sessionUseCase)

        service.sendMessage(pluginId, buildJsonObject {
            put("sessionId", "session-1")
            put("text", "hello")
        })

        val request = bus.receive() as AgentChatRequest.Send
        assertEquals("session-1", request.sessionId)
        assertEquals("hello", request.text)
        assertEquals(workspacePath, request.workspacePath)
    }

    @Test
    fun sendMessage_rejectsSessionFromAnotherWorkspace() = runTest {
        val bus = AgentChatRequestBus().apply { attach("test") }
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session(workspace = "/other/project")
        val service = service(bus = bus, sessionUseCase = sessionUseCase)

        val error = runCatching {
            service.sendMessage(pluginId, buildJsonObject {
                put("sessionId", "session-1")
                put("text", "hello")
            })
        }.exceptionOrNull()

        assertEquals("SESSION_FORBIDDEN", (error as PluginHostException).code)
    }

    @Test
    fun listMessages_returnsChronologicalPage() = runTest {
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { messageDao.getMessagesPageDescending("session-1", null, null, 51) } returns
            listOf(message("m2", 2), message("m1", 1))
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(messageDao = messageDao, sessionUseCase = sessionUseCase)

        val result = service.listMessages(pluginId, buildJsonObject {
            put("sessionId", "session-1")
        }) as JsonObject

        val ids = result["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("m1", "m2"), ids)
        assertFalse(result["hasMore"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun listSessions_excludesSubagentsByDefault() = runTest {
        val sessionDao = mockk<ChatSessionDao>(relaxed = true)
        coEvery { sessionDao.getRootSessionsByWorkspaceOnce(workspacePath) } returns listOf(session())
        val service = service(sessionDao = sessionDao)

        service.listSessions(pluginId, buildJsonObject {})

        coVerify { sessionDao.getRootSessionsByWorkspaceOnce(workspacePath) }
        coVerify(exactly = 0) { sessionDao.getAllSessionsByWorkspaceOnce(any()) }
    }

    @Test
    fun stop_submitsStopRequest() = runTest {
        val bus = AgentChatRequestBus().apply { attach("test") }
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(bus = bus, sessionUseCase = sessionUseCase)

        service.stop(pluginId, buildJsonObject { put("sessionId", "session-1") })

        val request = bus.receive() as AgentChatRequest.Stop
        assertEquals("session-1", request.sessionId)
    }

    @Test
    fun openSession_submitsSelectRequest() = runTest {
        val bus = AgentChatRequestBus().apply { attach("test") }
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(bus = bus, sessionUseCase = sessionUseCase)

        service.openSession(pluginId, buildJsonObject { put("sessionId", "session-1") })

        val request = bus.receive() as AgentChatRequest.Select
        assertEquals("session-1", request.sessionId)
    }

    @Test
    fun listMessages_rejectsBeforeAndAfterTogether() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(sessionUseCase = sessionUseCase)

        val error = runCatching {
            service.listMessages(pluginId, buildJsonObject {
                put("sessionId", "session-1")
                put("before", 10)
                put("after", 5)
            })
        }.exceptionOrNull()

        assertEquals("PARAM_INVALID", (error as PluginHostException).code)
    }

    @Test
    fun renameSession_updatesTitle() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        coEvery { sessionUseCase.updateTitle(any(), any()) } just Runs
        val service = service(sessionUseCase = sessionUseCase)

        val result = service.renameSession(pluginId, buildJsonObject {
            put("sessionId", "session-1")
            put("title", "新标题")
        }) as JsonObject

        assertEquals("新标题", result["session"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        coVerify { sessionUseCase.updateTitle("session-1", "新标题") }
    }

    @Test
    fun deleteSession_submitsDeleteRequest() = runTest {
        val bus = AgentChatRequestBus().apply { attach("test") }
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(bus = bus, sessionUseCase = sessionUseCase)

        service.deleteSession(pluginId, buildJsonObject { put("sessionId", "session-1") })

        val request = bus.receive() as AgentChatRequest.Delete
        assertEquals("session-1", request.sessionId)
    }

    @Test
    fun getRunState_andWaitForRun_useRunStore() = runTest {
        val runStore = AgentRunStateStore()
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val service = service(sessionUseCase = sessionUseCase, runStateStore = runStore)

        val idle = service.getRunState(pluginId, buildJsonObject { put("sessionId", "session-1") }) as JsonObject
        assertEquals("idle", idle["run"]!!.jsonObject["status"]!!.jsonPrimitive.content)

        runStore.started("session-1")
        val running = service.getRunState(pluginId, buildJsonObject { put("sessionId", "session-1") }) as JsonObject
        assertEquals(true, running["run"]!!.jsonObject["running"]!!.jsonPrimitive.boolean)

        runStore.finished("session-1", "success", null)
        val waited = service.waitForRun(pluginId, buildJsonObject {
            put("sessionId", "session-1")
            put("timeoutMs", 1000)
        }) as JsonObject
        assertEquals(false, waited["timedOut"]!!.jsonPrimitive.boolean)
        assertEquals("success", waited["run"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun listApprovals_mapsPendingToolPermission() = runTest {
        val sessionUseCase = mockk<SessionUseCase>(relaxed = true)
        coEvery { sessionUseCase.getSessionById("session-1") } returns session()
        val toolPermissionManager = mockk<ToolPermissionManager>(relaxed = true)
        every { toolPermissionManager.pendingForSession("session-1") } returns PendingToolPermission(
            id = "approval-1",
            toolName = "Bash",
            title = "执行命令",
            summary = "ls",
            details = "完整参数",
            argsPreview = "{\"command\":\"ls\"}",
            sessionId = "session-1"
        )
        val service = service(
            sessionUseCase = sessionUseCase,
            toolPermissionManager = toolPermissionManager
        )

        val result = service.listApprovals(pluginId, buildJsonObject {
            put("sessionId", "session-1")
        }) as JsonObject

        assertEquals("approval-1", result["tool"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Bash", result["tool"]!!.jsonObject["toolName"]!!.jsonPrimitive.content)
    }
}
