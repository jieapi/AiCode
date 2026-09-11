package com.aicode.feature.agent.domain.notification

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 待送通知的来源类型。 */
enum class AgentNotificationKind { BACKGROUND_TASK, SUBAGENT, AGENT_MESSAGE }

/**
 * 异步任务的结束方式。[STOPPED] 与 [FAILED] 必须区分：被用户手动终止不是执行出错，
 * 混为一谈会让 AI 把人为中断当成故障去排查或重试。
 */
enum class NotificationOutcome { COMPLETED, FAILED, STOPPED }

/**
 * 一条待送给 AI 的异步完成通知（后台终端命令 / 子代理）。
 *
 * @property sourceId 终端 tabId 或子代理会话 id，AI 据此调 terminal(read) / task(read) 取完整结果。
 * @property detail 结束原因补充：子代理失败时的错误信息、被终止时的说明；正常完成时为 null。
 * @property message [AgentNotificationKind.AGENT_MESSAGE] 的消息正文。
 * @property fromParent [AgentNotificationKind.AGENT_MESSAGE] 的方向：true 表示发送方是主会话（收件人为子代理），
 *   false 表示发送方是子代理（收件人为主会话）。供 Formatter 生成对应的回复提示。
 * @property seq [AgentNotificationCenter] 分配的单调序号，供 peek 后精确 ack；未入队时为 0。
 */
data class PendingNotification(
    val kind: AgentNotificationKind,
    val sourceId: String,
    val title: String,
    val outcome: NotificationOutcome,
    val command: String? = null,
    val exitCode: Int? = null,
    val tailOutput: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val fromParent: Boolean = false,
    val seq: Long = 0
)

/**
 * 会话级待送通知队列：后台任务与子代理完成事件在 AI 忙碌时先入队，由两条路径之一送达。
 *
 * - **搭车**（首选）：AI 每批工具执行完成后，[StatefulAgentWorkflow][com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 *   peek 出通知注入工具结果，本轮内立即送达，省掉一次「等本轮结束再起一轮」的 LLM 往返。
 * - **兜底**：AI 整轮没调用任何工具时，ViewModel 在本轮 finally 里 [drain] 后作为系统通知消息触发新一轮。
 *
 * 入队来自主线程（ViewModel 收 Flow 事件），peek/ack 来自工具执行的 IO 协程，故全部操作加锁。
 * [peek] 与 [ack] 分两段而非直接取走：通知只有在工具结果真正落库（ToolCallFinished 已发出）后才算送达，
 * 中途被取消时通知仍留在队列里，交给下一批工具或兜底路径。
 */
@Singleton
class AgentNotificationCenter @Inject constructor() {

    private val lock = Any()
    private val pending = mutableMapOf<String, MutableList<PendingNotification>>()
    private val seqGenerator = AtomicLong(0)

    /** 入队一条通知，返回其分配到的序号。 */
    fun enqueue(sessionId: String, item: PendingNotification): Long {
        val seq = seqGenerator.incrementAndGet()
        synchronized(lock) {
            pending.getOrPut(sessionId) { mutableListOf() }.add(item.copy(seq = seq))
        }
        return seq
    }

    /** 读取该会话当前所有待送通知（不移除）。 */
    fun peek(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending[sessionId]?.toList() ?: emptyList()
    }

    /** 确认已送达：按序号移除，peek 之后新入队的条目不受影响。 */
    fun ack(sessionId: String, seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        synchronized(lock) {
            val list = pending[sessionId] ?: return
            list.removeAll { it.seq in seqs }
            if (list.isEmpty()) pending.remove(sessionId)
        }
    }

    /** 原子取走该会话全部待送通知。 */
    fun drain(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending.remove(sessionId) ?: emptyList()
    }

    fun pendingCount(sessionId: String): Int = synchronized(lock) {
        pending[sessionId]?.size ?: 0
    }

    fun clear(sessionId: String) {
        synchronized(lock) { pending.remove(sessionId) }
    }

    fun clearAll() {
        synchronized(lock) { pending.clear() }
    }
}
