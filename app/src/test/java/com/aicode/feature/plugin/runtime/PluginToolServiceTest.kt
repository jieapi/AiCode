package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.permission.PermissionScope
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
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = result

        override suspend fun executeWithContext(
            args: Map<String, JsonElement>,
            context: AgentContext
        ): ToolResult {
            lastContext = context
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class Env(
        val service: PluginToolService,
        val permissionManager: ToolPermissionManager,
        val policyEngine: ToolPermissionPolicyEngine
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
        workspace: String? = workspacePath
    ): Env {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns pluginId
            every { isActive } returns active
        }
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.plugins } returns MutableStateFlow(listOf(plugin))
        val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaceRepository.current } returns MutableStateFlow(
            workspace?.let { Workspace(name = "project", path = it) }
        )
        val registry = ToolRegistry()
        tools.forEach { registry.register(it.name, it) }
        val policyEngine = mockk<ToolPermissionPolicyEngine>(relaxed = true)
        coEvery { policyEngine.evaluate(any(), any(), any(), any()) } returns eval
        val permissionManager = ToolPermissionManager()
        val service = PluginToolService(
            pluginManager = pluginManager,
            workspaceRepository = workspaceRepository,
            toolRegistry = registry,
            policyEngine = policyEngine,
            permissionManager = permissionManager
        )
        return Env(service, permissionManager, policyEngine)
    }

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
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any()) }
    }

    @Test
    fun execute_rejectsPluginToolNamespaceWithoutEvaluatingPolicy() = runTest {
        val env = env(tools = listOf(FakeTool(name = "plugin__demo__hello")))

        val error = errorOf {
            env.service.execute(pluginId, executeParams("plugin__demo__hello"))
        }

        assertNotNull(error)
        assertEquals("TOOL_NOT_ALLOWED", error!!.code)
        coVerify(exactly = 0) { env.policyEngine.evaluate(any(), any(), any(), any()) }
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
            env.policyEngine.remember("writeFile", listOf("*"), PermissionScope.PROJECT)
        }
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
