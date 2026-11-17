package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.permission.PermissionChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多会话并行授权语义：不同会话可同时挂起各自的授权请求（互不阻塞），
 * 侧边栏据 [ToolPermissionManager.awaitingSessionIds] 点亮，弹窗按插入顺序逐个展示，
 * resolve 按请求 id 精确唤醒对应会话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolPermissionManagerTest {

    private fun req(id: String, sessionId: String) = PendingToolPermission(
        id = id,
        toolName = "Bash",
        title = "确认执行工具",
        summary = "",
        details = "",
        argsPreview = "",
        sessionId = sessionId
    )

    @Test
    fun twoSessionsPendSimultaneously_resolveByIdTargetsCorrectSession() = runBlocking {
        val mgr = ToolPermissionManager()

        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        val d2 = async { mgr.awaitApproval(req("id2", "s2")) }

        withTimeout(1000) { while (mgr.awaitingSessionIds.value.size < 2) yield() }

        // 两个会话都点亮，互不阻塞
        assertEquals(setOf("s1", "s2"), mgr.awaitingSessionIds.value)
        // 弹窗展示最早进入的请求
        assertEquals("id1", mgr.pendingRequest.value?.id)
        // 按会话查询待决请求
        assertEquals("id2", mgr.pendingForSession("s2")?.id)

        // 先解决 s2（乱序），只唤醒 s2
        mgr.resolve("id2", PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, d2.await())

        withTimeout(1000) { while (mgr.awaitingSessionIds.value != setOf("s1")) yield() }
        assertEquals("id1", mgr.pendingRequest.value?.id)
        assertNull(mgr.pendingForSession("s2"))

        mgr.resolve("id1", PermissionChoice.ALWAYS)
        assertEquals(PermissionChoice.ALWAYS, d1.await())

        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun cancelledRequest_oldIdDoesNotApproveSameSessionReplacement() = runTest {
        val mgr = ToolPermissionManager()
        val oldRequest = req("old-id", "s1")
        val old = async { mgr.awaitApproval(oldRequest) }
        runCurrent()
        assertEquals(oldRequest, mgr.pendingRequest.value)

        old.cancelAndJoin()
        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())

        val replacementRequest = req("new-id", "s1")
        val replacement = async { mgr.awaitApproval(replacementRequest) }
        runCurrent()
        assertEquals(oldRequest.toolName, replacementRequest.toolName)

        mgr.resolve(oldRequest.id, PermissionChoice.ONCE)
        runCurrent()

        assertTrue(replacement.isActive)
        assertEquals(replacementRequest, mgr.pendingRequest.value)
        assertEquals(replacementRequest, mgr.pendingForSession("s1"))
        assertEquals(setOf("s1"), mgr.awaitingSessionIds.value)

        mgr.resolve(replacementRequest.id, PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, replacement.await())
        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun pluginOriginRequestStaysOutOfChatPopup() = runTest {
        val mgr = ToolPermissionManager()
        val pluginRequest = req("plugin-id", "plugin:demo")
            .copy(origin = PendingToolPermission.ORIGIN_PLUGIN)
        val plugin = async { mgr.awaitApproval(pluginRequest) }
        runCurrent()

        // 插件请求不占聊天页弹窗与侧边栏指示，但仍可按会话查询
        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
        assertEquals("plugin-id", mgr.pendingForSession("plugin:demo")?.id)

        val chatRequest = req("chat-id", "s1")
        val chat = async { mgr.awaitApproval(chatRequest) }
        runCurrent()
        assertEquals("chat-id", mgr.pendingRequest.value?.id)
        assertEquals(setOf("s1"), mgr.awaitingSessionIds.value)

        mgr.resolve("plugin-id", PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, plugin.await())
        assertEquals("chat-id", mgr.pendingRequest.value?.id)

        mgr.resolve("chat-id", PermissionChoice.REJECT)
        assertEquals(PermissionChoice.REJECT, chat.await())
        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun proactivePluginPermissionIsVisibleButCannotBeRememberedOrPersistedAsChat() = runTest {
        val mgr = ToolPermissionManager()
        val request = req("explicit", "source|opaque").copy(
            origin = PendingToolPermission.ORIGIN_PLUGIN_PERMISSION,
            pluginId = "demo",
            pluginName = "Demo"
        )
        val decision = async { mgr.awaitApproval(request) }
        runCurrent()
        assertEquals(request, mgr.pendingRequest.value)
        assertEquals(setOf("source|opaque"), mgr.awaitingSessionIds.value)
        assertNull(mgr.pendingChatForSession(request.sessionId))
        mgr.resolve(request.id, PermissionChoice.ALWAYS)
        runCurrent()
        assertTrue(decision.isActive)
        mgr.resolve(request.id, PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, decision.await())
        assertNull(mgr.pendingRequest.value)
    }

    @Test
    fun resolveUnknownId_isNoop() = runBlocking {
        val mgr = ToolPermissionManager()
        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        withTimeout(1000) { while (mgr.pendingRequest.value == null) yield() }

        mgr.resolve("nonexistent", PermissionChoice.REJECT)
        // 原请求仍挂起
        assertTrue(d1.isActive)
        assertEquals("id1", mgr.pendingRequest.value?.id)

        mgr.resolve("id1", PermissionChoice.REJECT)
        assertEquals(PermissionChoice.REJECT, d1.await())
    }
}
