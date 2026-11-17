package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.permission.PermissionChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolPermissionManager @Inject constructor() {
    /**
     * 所有待决授权请求，按插入顺序保存（key = 请求 id）。多会话并行时每个会话可各自挂起一个请求，
     * 互不阻塞——这样侧边栏才能同时点亮所有等待授权的会话。同一会话内的权限请求由 workflow 串行处理，
     * 故一个会话在此表中至多出现一次。
     */
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** 弹窗展示用：当前应展示的单个请求（取最早进入的），解决后自动切到下一个。 */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** 侧边栏用：当前正在等待授权的会话 id 集合。 */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /** 挂起等待用户在弹窗中的选择（拒绝/本次/始终）。不同会话可并行挂起，互不阻塞。 */
    suspend fun awaitApproval(request: PendingToolPermission): PermissionChoice {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /** UI 回传用户选择，唤醒对应 id 的挂起请求。 */
    fun resolve(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pending[id] } ?: return
        if (entry.request.origin == PendingToolPermission.ORIGIN_PLUGIN_PERMISSION && choice == PermissionChoice.ALWAYS) return
        entry.decision.complete(choice)
    }

    /** 取某会话当前的待决请求（stopAgent 停止单个会话时用，避免误取其它会话的弹窗）。 */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    fun pendingChatForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) {
            pending.values.firstOrNull {
                it.request.sessionId == sessionId && it.request.origin == PendingToolPermission.ORIGIN_CHAT
            }?.request
        }

    /**
     * 在持锁区内调用：把内部表投影到两个对外 StateFlow。
     * 插件发起的授权（[PendingToolPermission.ORIGIN_PLUGIN]）只通过 [pendingForSession] 交给
     * 插件侧处理，不占用聊天页弹窗与侧边栏等待指示。
     */
    private fun publish() {
        val chatRequests = pending.values
            .map { it.request }
            .filter { it.origin != PendingToolPermission.ORIGIN_PLUGIN }
        _pendingRequest.value = chatRequests.firstOrNull()
        _awaitingSessionIds.value = chatRequests.mapNotNull { it.sessionId.ifBlank { null } }.toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )
}
