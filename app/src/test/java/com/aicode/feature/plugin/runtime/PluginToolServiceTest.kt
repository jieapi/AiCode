package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginToolServiceTest {
    private val pluginId = "demo.tools"
    private val workspacePath = "/root/workspace/project"

    private class FakeTool(
        override val name: String,
        private val result: ToolResult = ToolResult.Success(JsonPrimitive("ok")),
        override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE,
        override val capabilities: Set<ToolCapability> = emptySet(),
        private val delayMs: Long = 0
    ) : AgentTool() {
        override val description = "fake tool"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        var lastContext: AgentContext? = null
            private set
        var completed = false
            private set

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = result

        override suspend fun executeWithContext(
            args: Map<String, JsonElement>,
            context: AgentContext
        ): ToolResult {
            lastContext = context
            if (delayMs > 0) delay(delayMs)
            completed = true
            return result
        }
    }

    private class Env(
        val service: PluginToolService,
        val permissionManager: ToolPermissionManager,
        val policyEngine: ToolPermissionPolicyEngine,
        val project: PermissionRulesRepository.ProjectContext,
        val workspaces: MutableStateFlow<Workspace?>,
        val plugins: MutableStateFlow<List<InstalledPlugin>>,
        val backendRegistry: ExecutionBackendRegistry,
        val modeHolder: ExecutionModeHolder,
        val servers: MutableStateFlow<RemoteConnectionConfig?>,
        val connectionState: MutableStateFlow<ConnectionState>
    )

    private fun allow(): ToolPermissionPolicyEngine.EvalResult =
        ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())

    private fun ask(
        patterns: List<String> = listOf("*")
    ): ToolPermissionPolicyEngine.EvalResult =
        ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ASK, patterns)

    private fun deny(reason: String = "blocked"): ToolPermissionPolicyEngine.EvalResult =
        ToolPermissionPolicyEngine.EvalResult(
            ToolPermissionPolicyEngine.Verdict.DENY,
            emptyList(),
            denyReason = reason
        )

    private fun env(
        tools: List<AgentTool> = emptyList(),
        eval: ToolPermissionPolicyEngine.EvalResult = allow(),
        active: Boolean = true,
        workspace: String? = workspacePath,
        mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        server: RemoteConnectionConfig? = null
    ): Env {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns pluginId
            every { isActive } returns active
        }
        val pluginManager = mockk<PluginManager>(relaxed = true)
        val plugins = MutableStateFlow(listOf(plugin))
        every { pluginManager.plugins } returns plugins
        val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
        val workspaces = MutableStateFlow(workspace?.let { Workspace(name = "project", path = it) })
        every { workspaceRepository.current } returns workspaces
        val registry = ToolRegistry()
        tools.forEach { registry.register(it.name, it) }
        val policyEngine = mockk<ToolPermissionPolicyEngine>(relaxed = true)
        val project = mockk<PermissionRulesRepository.ProjectContext>()
        every { policyEngine.captureProject(any()) } returns project
        coEvery { policyEngine.evaluate(any(), any(), any(), any(), any()) } returns eval
        val permissionManager = ToolPermissionManager()
        val servers = MutableStateFlow(server)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        val sshConnection = mockk<RemoteSshConnection>()
        every { sshConnection.config } answers { servers.value }
        every { sshConnection.connectionState } returns connectionState
        every { sshConnection.remoteHome } returns null
        val localBackend = BuiltinLocalProotBackend(mockk<LinuxContainerEngine>(relaxed = true), mockk<LocalFileAccess>(relaxed = true), mockk<TerminalSessionManager>(relaxed = true))
        val remoteBackend = BuiltinRemoteSshBackend(mockk<RemoteSshEngine>(relaxed = true), mockk<RemoteSftpFileAccess>(relaxed = true), mockk<RemoteTerminalSessionManager>(relaxed = true), sshConnection)
        val backendRegistry = ExecutionBackendRegistry(localBackend, remoteBackend)
        val modeHolder = ExecutionModeHolder(backendRegistry).apply { setMode(mode) }
        val service = PluginToolService(
            pluginManager = pluginManager,
            workspaceRepository = workspaceRepository,
            toolRegistry = registry,
            policyEngine = policyEngine,
            permissionManager = permissionManager,
            executionModeHolder = modeHolder,
            remoteSshConnection = sshConnection
        )
        return Env(service, permissionManager, policyEngine, project, workspaces, plugins, backendRegistry, modeHolder, servers, connectionState)
    }

    private fun server(
        host: String = "server-a",
        port: Int = 22,
        username: String = "alice"
    ) = RemoteConnectionConfig(host, port, username, RemoteAuth.Password(""), "/srv/workspaces")

    private fun executeParams(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
        timeoutMs: Long? = null
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
        sessionId?.let { put("sessionId", it) }
        timeoutMs?.let { put("timeoutMs", it) }
    }

    private suspend fun errorOf(block: suspend () -> Unit): PluginHostException? {
        val error = runCatching { block() }.exceptionOrNull()
        return error as? PluginHostException
    }

    @Test
    fun execute_autoApproveRunsToolWithContext() = runTest {
        val tool = FakeTool(
            name = "readFile",
            result = ToolResult.Success(buildJsonObject { put("content", "hi") })
        )
        val env = env(tools = listOf(tool))

        val result = env.service.execute(
            pluginId,
            buildJsonObject {
                put("name", "readFile")
                put("arguments", buildJsonObject { put("path", "/root/workspace/project/a.txt") })
                put(
                    "context",
                    buildJsonObject {
                        put("path", "/root/workspace/project/a.txt")
                        put("selection", "line")
                        put("language", "kotlin")
                    }
                )
            }
        ).jsonObject

        assertEquals("success", result["status"]!!.jsonPrimitive.content)
        assertEquals("hi", result["data"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        val context = tool.lastContext!!
        assertEquals("/root/workspace/project/a.txt", context.currentFile)
        assertEquals("line", context.selectedCode)
        assertEquals("kotlin", context.language)
        assertEquals(workspacePath, context.projectRoot)
        assertEquals("plugin:$pluginId", context.sessionId)
        assertEquals(AgentMode.BUILD, context.mode)
    }

    @Test
    fun execute_inactivePluginDoesNotEvaluateOrExecute() = runTest {
        val tool = FakeTool(name = "writeFile")
        val env = env(tools = listOf(tool), active = false)

        val error = errorOf { env.service.execute(pluginId, executeParams("writeFile")) }

        assertEquals("PLUGIN_INACTIVE", error!!.code)
        assertNull(tool.lastContext)
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun execute_missingWorkspaceDoesNotEvaluateOrPublishApproval() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask(), workspace = null)

        val error = errorOf { env.service.execute(pluginId, executeParams("writeFile")) }

        assertEquals("WORKSPACE_UNAVAILABLE", error!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun execute_autoApprovePolicySkipsAskVerdict() = runTest {
        val env = env(tools = listOf(FakeTool(name = "readFile")), eval = ask())

        val result = env.service.execute(pluginId, executeParams("readFile")).jsonObject

        assertEquals("success", result["status"]!!.jsonPrimitive.content)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_rejectsSessionBoundTools() = runTest {
        val env = env(tools = listOf(FakeTool(name = "askUserQuestion")))

        val error = errorOf {
            env.service.execute(pluginId, executeParams("askUserQuestion"))
        }

        assertEquals("TOOL_NOT_ALLOWED", error!!.code)
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun execute_rejectsPluginToolNamespaceWithoutEvaluatingPolicy() = runTest {
        val env = env(tools = listOf(FakeTool(name = "plugin__demo__hello")))

        val error = errorOf {
            env.service.execute(pluginId, executeParams("plugin__demo__hello"))
        }

        assertNotNull(error)
        assertEquals("TOOL_NOT_ALLOWED", error!!.code)
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any(), any()) }
    }

    @Test
    fun execute_unknownToolReturnsNotFound() = runTest {
        val error = errorOf { env().service.execute(pluginId, executeParams("missing")) }

        assertEquals("TOOL_NOT_FOUND", error!!.code)
    }

    @Test
    fun execute_deniedByPolicyReturnsToolDenied() = runTest {
        val env = env(tools = listOf(FakeTool(name = "writeFile")), eval = deny("规则拒绝"))

        val error = errorOf { env.service.execute(pluginId, executeParams("writeFile")) }
        assertNotNull(error)

        assertEquals("TOOL_DENIED", error!!.code)
        assertEquals("规则拒绝", error!!.message)
    }

    @Test
    fun execute_askStaysOutOfChatPopupAndResolvesOnce() = runTest {
        val tool = FakeTool(
            name = "writeFile",
            result = ToolResult.Success(JsonPrimitive("done")),
            permissionPolicy = ToolPermissionPolicy.ASK
        )
        val env = env(tools = listOf(tool), eval = ask())

        val job = async {
            env.service.execute(
                pluginId,
                executeParams("writeFile", buildJsonObject { put("path", "a.txt") })
            )
        }
        runCurrent()

        val pending = env.permissionManager.pendingForSession("plugin:$pluginId")
        assertNotNull(pending)
        val request = pending!!
        assertEquals(PendingToolPermission.ORIGIN_PLUGIN, request.origin)
        assertNull(env.permissionManager.pendingRequest.value)
        assertTrue(env.permissionManager.awaitingSessionIds.value.isEmpty())

        val approval = (env.service.pending(pluginId, JsonObject(emptyMap())) as JsonObject)["approval"]!!
            .jsonObject
        assertEquals(request.id, approval["id"]!!.jsonPrimitive.content)
        assertEquals("writeFile", approval["toolName"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("*"),
            approval["rememberablePatterns"]!!.jsonArray.map { it.jsonPrimitive.content }
        )

        env.service.resolve(
            pluginId,
            buildJsonObject {
                put("requestId", request.id)
                put("choice", "once")
            }
        )
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("success", job.await().jsonObject["status"]!!.jsonPrimitive.content)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        assertEquals(workspacePath, tool.lastContext!!.projectRoot)
        coVerify(exactly = 1) { env.policyEngine.evaluate(tool, "writeFile", any(), AgentMode.BUILD, env.project) }
    }

    @Test
    fun execute_alwaysRemembersPatterns() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask(listOf("*")))
        val job = async { env.service.execute(pluginId, executeParams("writeFile")) }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id

        env.service.resolve(
            pluginId,
            buildJsonObject {
                put("requestId", requestId)
                put("choice", "always")
            }
        )
        runCurrent()

        assertEquals("success", job.await().jsonObject["status"]!!.jsonPrimitive.content)
        coVerify {
            env.policyEngine.remember("writeFile", listOf("*"), env.project)
        }
    }

    @Test
    fun execute_workspaceChangeInvalidatesPendingApprovalEvenAfterSwitchingBack() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id
        val original = env.workspaces.value

        env.workspaces.value = Workspace(name = "other", path = "/root/workspace/other")
        env.workspaces.value = original
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
        val error = errorOf {
            env.service.resolve(pluginId, buildJsonObject {
                put("requestId", requestId)
                put("choice", "always")
            })
        }
        assertEquals("APPROVAL_NOT_FOUND", error!!.code)
    }

    @Test
    fun execute_workspaceChangeAfterAlwaysChoiceDoesNotRememberOrExecute() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id

        env.service.resolve(pluginId, buildJsonObject {
            put("requestId", requestId)
            put("choice", "always")
        })
        env.workspaces.value = Workspace(name = "other", path = "/root/workspace/other")
        runCurrent()

        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_modeChangeInvalidatesPendingApprovalEvenAfterSwitchingBack() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

        env.modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        env.modeHolder.setMode(ExecutionMode.LOCAL_PROOT)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_pluginBackendChangeInvalidatesPendingApproval() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val pluginBackend = object : ExecutionBackend {
            override val id: String = "demo.cloud:sandbox"
            override val displayName: String = "Cloud Sandbox"
            override val capabilities: BackendCapabilities = BackendCapabilities(isRemoteEnvironment = true)
            override fun environmentDescription(): String = displayName
            override fun environmentCacheKey(): String = "plugin:demo.cloud:sandbox:1.0.0"
            override fun workspaceRootDisplay(workspacePath: String): String = workspacePath
            override fun projectConfigKey(workspacePath: String): String = workspacePath
            override val commandEngine: CommandEngine = mockk(relaxed = true)
            override val fileAccess: FileAccessProvider = mockk(relaxed = true)
            override val terminalSessionProvider: TerminalSessionProvider? = null
        }
        env.backendRegistry.register(pluginBackend)

        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

        env.modeHolder.setActiveBackend(pluginBackend.id)
        env.modeHolder.setMode(ExecutionMode.LOCAL_PROOT)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_serverIdentityChangesInvalidatePendingApprovalEvenAfterSwitchingBack() = runTest {
        val original = server()
        val alternatives = listOf(
            original.copy(host = "server-b"),
            original.copy(port = 2222),
            original.copy(username = "bob")
        )
        for (replacement in alternatives) {
            val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
            val env = env(tools = listOf(tool), eval = ask(), mode = ExecutionMode.REMOTE_SSH, server = original)
            val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
            runCurrent()
            assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

            env.servers.value = replacement
            env.connectionState.value = ConnectionState.CONNECTING
            env.servers.value = original
            env.connectionState.value = ConnectionState.CONNECTED
            runCurrent()

            assertTrue(job.isCompleted)
            assertEquals("STALE_CONTEXT", job.await()!!.code)
            assertNull(tool.lastContext)
            assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
            coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
        }
    }

    @Test
    fun execute_serverSnapshotChangeWithoutConnectionEventInvalidatesApproval() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask(), mode = ExecutionMode.REMOTE_SSH, server = server())
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

        env.servers.value = server(host = "server-b")
        advanceTimeBy(101)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_serverCredentialChangeKeepsApprovalValid() = runTest {
        val original = server()
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask(), mode = ExecutionMode.REMOTE_SSH, server = original)
        val job = async { env.service.execute(pluginId, executeParams("writeFile")) }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id

        env.servers.value = original.copy(auth = RemoteAuth.PrivateKey("/keys/test"))
        env.connectionState.value = ConnectionState.CONNECTING
        env.connectionState.value = ConnectionState.CONNECTED
        env.service.resolve(pluginId, buildJsonObject {
            put("requestId", requestId)
            put("choice", "once")
        })
        runCurrent()

        assertEquals("success", job.await().jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(workspacePath, tool.lastContext!!.projectRoot)
    }

    @Test
    fun execute_pluginDisableInvalidatesPendingApprovalEvenAfterEnablingAgain() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        val original = env.plugins.value

        env.plugins.value = emptyList()
        env.plugins.value = original
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("PLUGIN_INACTIVE", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_workspaceChangeDuringPolicyEvaluationDoesNotPublishApprovalOrExecute() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val evaluation = CompletableDeferred<ToolPermissionPolicyEngine.EvalResult>()
        coEvery { env.policyEngine.evaluate(any(), any(), any(), any(), env.project) } coAnswers {
            evaluation.await()
        }
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()

        env.workspaces.value = Workspace(name = "other", path = "/root/workspace/other")
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 1) { env.policyEngine.evaluate(tool, "writeFile", any(), AgentMode.BUILD, env.project) }
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_contextIsValidatedBeforePolicyEvaluation() = runTest {
        val env = env(tools = listOf(FakeTool(name = "writeFile")), eval = ask())
        val error = errorOf {
            env.service.execute(pluginId, buildJsonObject {
                put("name", "writeFile")
                put("context", JsonPrimitive("invalid"))
            })
        }

        assertEquals("PARAM_INVALID", error!!.code)
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any(), any()) }
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_workspaceChangeDuringRememberDoesNotExecute() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val writing = CompletableDeferred<Unit>()
        coEvery { env.policyEngine.remember("writeFile", any(), env.project) } coAnswers {
            writing.await()
        }
        val job = async { errorOf { env.service.execute(pluginId, executeParams("writeFile")) } }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id
        env.service.resolve(pluginId, buildJsonObject {
            put("requestId", requestId)
            put("choice", "always")
        })
        runCurrent()
        coVerify(exactly = 1) { env.policyEngine.remember("writeFile", listOf("*"), env.project) }

        env.workspaces.value = Workspace(name = "other", path = "/root/workspace/other")
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_customPluginSessionResolvesByRequestId() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val sessionId = "plugin:$pluginId:task-1"
        val job = async {
            env.service.execute(pluginId, executeParams("writeFile", sessionId = sessionId))
        }
        runCurrent()

        val pending = env.permissionManager.pendingForSession(sessionId)
        assertNotNull(pending)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

        env.service.resolve(
            pluginId,
            buildJsonObject {
                put("requestId", pending!!.id)
                put("choice", "once")
            }
        )
        runCurrent()

        assertEquals("success", job.await().jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_rejectsSessionOutsidePluginNamespace() = runTest {
        val env = env(tools = listOf(FakeTool(name = "readFile")))

        val error = errorOf {
            env.service.execute(pluginId, executeParams("readFile", sessionId = "session-1"))
        }

        assertEquals("SESSION_FORBIDDEN", error!!.code)
    }

    @Test
    fun execute_userRejectReturnsToolUserRejected() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async {
            runCatching { env.service.execute(pluginId, executeParams("writeFile")) }
        }
        runCurrent()
        val requestId = env.permissionManager.pendingForSession("plugin:$pluginId")!!.id

        env.service.resolve(
            pluginId,
            buildJsonObject {
                put("requestId", requestId)
                put("choice", "reject")
            }
        )
        runCurrent()

        val error = job.await().exceptionOrNull() as? PluginHostException
        assertEquals("TOOL_USER_REJECTED", error!!.code)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_approvalTimeoutReturnsApprovalTimeout() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async {
            runCatching { env.service.execute(pluginId, executeParams("writeFile", timeoutMs = 1_000)) }
        }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        val error = job.await().exceptionOrNull() as? PluginHostException
        assertEquals("TOOL_APPROVAL_TIMEOUT", error!!.code)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
    }

    @Test
    fun execute_contextChangeCancelsSuspendedTool() = runTest {
        val tool = FakeTool(name = "Bash", delayMs = 10_000)
        val env = env(tools = listOf(tool))
        val job = async { errorOf { env.service.execute(pluginId, executeParams("Bash")) } }
        runCurrent()
        assertEquals(workspacePath, tool.lastContext!!.projectRoot)

        env.workspaces.value = Workspace(name = "other", path = "/root/workspace/other")
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("STALE_CONTEXT", job.await()!!.code)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(!tool.completed)
    }

    @Test
    fun execute_cancellationClearsPendingApproval() = runTest {
        val tool = FakeTool(name = "writeFile", permissionPolicy = ToolPermissionPolicy.ASK)
        val env = env(tools = listOf(tool), eval = ask())
        val job = async { env.service.execute(pluginId, executeParams("writeFile")) }
        runCurrent()
        assertNotNull(env.permissionManager.pendingForSession("plugin:$pluginId"))

        job.cancel()
        job.join()
        runCurrent()

        assertTrue(job.isCancelled)
        assertNull(tool.lastContext)
        assertNull(env.permissionManager.pendingForSession("plugin:$pluginId"))
        coVerify(exactly = 0) { env.policyEngine.remember(any(), any(), env.project) }
    }

    @Test
    fun execute_executionTimeoutReturnsToolTimeout() = runTest {
        val env = env(tools = listOf(FakeTool(name = "Bash", delayMs = 10_000)), eval = allow())
        val job = async {
            runCatching { env.service.execute(pluginId, executeParams("Bash", timeoutMs = 1_000)) }
        }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        val error = job.await().exceptionOrNull() as? PluginHostException
        assertEquals("TOOL_TIMEOUT", error!!.code)
    }

    @Test
    fun execute_truncatesOversizedData() = runTest {
        val tool = FakeTool(
            name = "readFile",
            result = ToolResult.Success(JsonPrimitive("x".repeat(300 * 1024)))
        )
        val env = env(tools = listOf(tool))

        val result = env.service.execute(pluginId, executeParams("readFile")).jsonObject

        assertTrue(result["dataTruncated"]!!.jsonPrimitive.boolean)
        assertEquals(256 * 1024, result["data"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun execute_omitsOversizedImages() = runTest {
        val tool = FakeTool(
            name = "screenshot",
            result = ToolResult.Success(
                JsonPrimitive("ok"),
                listOf(
                    AgentImage("image/png", "a".repeat(1024)),
                    AgentImage("image/png", "b".repeat(3 * 1024 * 1024))
                )
            )
        )
        val env = env(tools = listOf(tool))

        val result = env.service.execute(pluginId, executeParams("screenshot")).jsonObject

        assertTrue(result["imagesTruncated"]!!.jsonPrimitive.boolean)
        val images = result["images"]!!.jsonArray
        assertEquals(1, images.size)
        assertEquals("image/png", images[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_mapsErrorAndPartialResults() = runTest {
        val errorEnv = env(
            tools = listOf(FakeTool(name = "readFile", result = ToolResult.Error("boom", "E_READ")))
        )
        val error = errorEnv.service.execute(pluginId, executeParams("readFile")).jsonObject
        assertEquals("error", error["status"]!!.jsonPrimitive.content)
        assertEquals("boom", error["message"]!!.jsonPrimitive.content)
        assertEquals("E_READ", error["code"]!!.jsonPrimitive.content)

        val partialEnv = env(
            tools = listOf(
                FakeTool(
                    name = "readFile",
                    result = ToolResult.Partial(JsonPrimitive("part"), "truncated")
                )
            )
        )
        val partial = partialEnv.service.execute(pluginId, executeParams("readFile")).jsonObject
        assertEquals("partial", partial["status"]!!.jsonPrimitive.content)
        assertEquals("part", partial["data"]!!.jsonPrimitive.content)
        assertEquals("truncated", partial["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun pending_withoutRequestReturnsNullApproval() = runTest {
        val result = env().service.pending(pluginId, JsonObject(emptyMap())) as JsonObject

        assertEquals(JsonNull, result["approval"])
    }

    @Test
    fun resolve_unknownRequestReturnsNotFound() = runTest {
        val error = errorOf {
            env().service.resolve(
                pluginId,
                buildJsonObject {
                    put("requestId", "missing")
                    put("choice", "once")
                }
            )
        }

        assertEquals("APPROVAL_NOT_FOUND", error!!.code)
    }
}
