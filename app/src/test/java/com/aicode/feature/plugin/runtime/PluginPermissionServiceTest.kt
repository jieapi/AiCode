package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginPermissionServiceTest {
    private val permissions = ToolPermissionManager()
    private val plugin = mockk<InstalledPlugin>(relaxed = true) {
        every { id } returns "demo"
        every { isActive } returns true
        every { manifest } returns null
    }
    private val plugins = MutableStateFlow(listOf(plugin))
    private val manager = mockk<PluginManager>(relaxed = true) {
        every { plugins } returns this@PluginPermissionServiceTest.plugins
    }
    private val workspaces = MutableStateFlow<Workspace?>(Workspace(name = "project", path = "/project"))
    private val workspace = mockk<WorkspaceRepository> {
        every { current } returns this@PluginPermissionServiceTest.workspaces
    }
    private val session = ChatSessionEntity("local", "Local", 0, 0, workspacePath = "/project")
    private val dao = mockk<ChatSessionDao> {
        coEvery { getById(any()) } returns null
        coEvery { getById("local") } returns session
        every { getByIdFlow("local") } returns MutableStateFlow(session)
    }
    private val registry = PluginSessionRegistry()
    private val service = PluginPermissionService(manager, workspace, dao, registry, permissions)
    private fun params(sessionId: String = "local") = buildJsonObject {
        put("sessionId", sessionId)
        put("requestId", "r1")
        put("title", "Execute")
        put("description", "Run a command")
        put("operation", buildJsonObject { put("kind", "shell"); put("command", "pwd") })
        put("timeoutMs", 1000)
        put("origin", "chat")
        put("pluginId", "victim")
    }

    @Test
    fun localApprovalUsesHostIdentityAndReturnsOnlyOnce() = runTest {
        val result = async { service.request("demo", params()) }
        runCurrent()
        val request = permissions.pendingRequest.value!!
        assertEquals("demo", request.pluginId)
        assertEquals(PendingToolPermission.ORIGIN_PLUGIN_PERMISSION, request.origin)
        assertNull(permissions.pendingChatForSession("local"))
        permissions.resolve(request.id, PermissionChoice.ONCE)
        assertEquals("allow", result.await().jsonObject["decision"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
    }

    @Test
    fun rejectAndCancelAreDistinctAndOtherPluginCannotCancel() = runTest {
        val first = async { service.request("demo", params()) }
        runCurrent()
        assertEquals("false", service.cancel("other", buildJsonObject { put("requestId", "r1") }).jsonObject["cancelled"]!!.jsonPrimitive.content)
        permissions.resolve(permissions.pendingRequest.value!!.id, PermissionChoice.REJECT)
        assertEquals("deny", first.await().jsonObject["decision"]!!.jsonPrimitive.content)
        val second = async { service.request("demo", params()) }
        runCurrent()
        service.cancel("demo", buildJsonObject { put("requestId", "r1") })
        assertEquals("cancel", second.await().jsonObject["decision"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
    }

    @Test
    fun timeoutAndDisableClearPending() = runTest {
        val timed = async { service.request("demo", params()) }
        runCurrent()
        advanceTimeBy(1001)
        assertEquals("timeout", timed.await().jsonObject["reason"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
        val disabled = async { service.request("demo", params()) }
        runCurrent()
        plugins.value = emptyList()
        assertEquals("cancel", disabled.await().jsonObject["decision"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
    }

    @Test
    fun duplicateIdsAreRejectedAndWorkspaceSwitchCancels() = runTest {
        val original = async { service.request("demo", params()) }
        runCurrent()
        val duplicate = try { service.request("demo", params()); null } catch (e: PluginHostException) { e }
        assertEquals("REQUEST_EXISTS", duplicate?.code)
        workspaces.value = Workspace(name = "other", path = "/other")
        assertEquals("cancel", original.await().jsonObject["decision"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
    }

    @Test
    fun ownExternalSessionIsAllowedAndRemovalCancelsApproval() = runTest {
        val sourceKey = com.aicode.feature.plugin.domain.pluginContributionName("demo", "remote")
        val externalId = "$sourceKey|opaque"
        val summary = mockk<com.aicode.feature.plugin.domain.session.PluginSessionSummary>(relaxed = true) {
            every { id } returns externalId
            every { pluginId } returns "demo"
            every { title } returns "External"
            every { updatedAt } returns 0L
        }
        registry.ensureSource("demo", "Demo", sourceKey, "Remote")
        registry.setSessions("demo", "remote", listOf(summary))
        val allowed = async { service.request("demo", params(externalId)) }
        runCurrent()
        permissions.resolve(permissions.pendingRequest.value!!.id, PermissionChoice.ONCE)
        assertEquals("allow", allowed.await().jsonObject["decision"]!!.jsonPrimitive.content)
        val removed = async { service.request("demo", params(externalId)) }
        runCurrent()
        registry.setSessions("demo", "remote", emptyList())
        assertEquals("cancel", removed.await().jsonObject["decision"]!!.jsonPrimitive.content)
        assertNull(permissions.pendingRequest.value)
    }

    @Test
    fun nonexistentAndForeignExternalSessionsAreRejected() = runTest {
        for (id in listOf("missing", "plugin:demo", "plugin__other__source|opaque")) {
            val error = try { service.request("demo", params(id)); null } catch (e: PluginHostException) { e }
            assertEquals("SESSION_FORBIDDEN", error?.code)
        }
        assertTrue(permissions.awaitingSessionIds.value.isEmpty())
    }
}
