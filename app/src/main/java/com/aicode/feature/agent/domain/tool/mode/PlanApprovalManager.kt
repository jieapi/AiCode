package com.aicode.feature.agent.domain.tool.mode

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 管理计划审查流程的挂起/恢复：
 * 当 AI 从 PLAN 模式切回 BUILD 模式时，workflow 调用 [awaitApproval] 挂起，
 * 等 UI 上的计划审查面板得到用户决策后恢复。
 *
 * 多会话并行时每个会话各自挂起一个请求，互不阻塞：内部按请求（会话）分槽保存，
 * UI 展示最早进入的那个，解决后自动切到下一个。若只保留单槽，后到的会话会覆盖
 * 前者的 decision，导致前者永久挂起。
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) {
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    // 回滚协程所在 Scope 必须带 SupervisorJob：否则某个回滚失败会让整个 Scope 永久取消，
    // 之后所有「拒绝计划后回滚到 PLAN」静默失效。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 挂起等待用户在计划审查面板中的决策。 */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val decision = CompletableDeferred<PlanApprovalChoice>()
        val key = UUID.randomUUID().toString()
        synchronized(lock) {
            pending[key] = Entry(reason, sessionId, decision)
            publish()
        }

        try {
            return decision.await()
        } catch (e: CancellationException) {
            // 用户未批准切换（点「停止」等取消路径）：SwitchModeTool 已把目标模式写入 DB，
            // 取消意味着未获批准，需回滚，否则模式会未经批准直接切过去。
            rollbackModeToPlan(sessionId)
            throw e
        } finally {
            synchronized(lock) {
                pending.remove(key)
                publish()
            }
        }
    }

    /** UI 回传用户选择，唤醒最早挂起的 [awaitApproval]。 */
    fun resolve(choice: PlanApprovalChoice) {
        val id = pendingApproval.value?.id ?: return
        resolve(id, choice)
    }

    fun resolve(id: String, choice: PlanApprovalChoice) {
        val entry = synchronized(lock) {
            val target = pending.remove(id) ?: return
            publish()
            target
        }

        // 用户拒绝时，回滚数据库中的模式到 PLAN（SwitchModeTool 已写入 BUILD）
        if (choice == PlanApprovalChoice.REFINE) {
            rollbackModeToPlan(entry.sessionId)
        }

        entry.decision.complete(choice)
    }

    /** 取某会话当前最早的待决计划审批；用于插件按会话展示，不影响原有全局最早请求。 */
    fun pendingForSession(sessionId: String): PlanApprovalRequest? = synchronized(lock) {
        pending.entries.firstOrNull { it.value.sessionId == sessionId }?.let { (id, entry) ->
            PlanApprovalRequest(reason = entry.reason, sessionId = entry.sessionId, id = id)
        }
    }

    /** 在持锁区内调用：把内部表投影到对外展示的单个请求（取最早进入的）。 */
    private fun publish() {
        _pendingApproval.value = pending.entries.firstOrNull()?.let { (id, entry) ->
            PlanApprovalRequest(reason = entry.reason, sessionId = entry.sessionId, id = id)
        }
    }

    /** 回滚会话模式到 PLAN（SwitchModeTool 已提前写入目标模式，未获批准时还原）。 */
    private fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        scope.launch {
            runCatching {
                val entity = chatSessionDao.getById(sid)
                if (entity != null && entity.mode != "PLAN") {
                    chatSessionDao.upsert(entity.copy(mode = "PLAN"))
                }
            }.onFailure { FileLogger.w(TAG, "计划审批回滚到 PLAN 失败: $sid", it) }
        }
    }

    private class Entry(
        val reason: String,
        val sessionId: String?,
        val decision: CompletableDeferred<PlanApprovalChoice>
    )

    private companion object {
        const val TAG = "PlanApprovalManager"
    }
}

data class PlanApprovalRequest(val reason: String, val sessionId: String? = null, val id: String = UUID.randomUUID().toString())

enum class PlanApprovalChoice {
    /** 批准计划，切换到 BUILD 模式执行 */
    APPROVE,
    /** 拒绝，留在 PLAN 模式继续反馈 */
    REFINE
}
