package com.aicode.feature.agent.domain.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel

/** 插件发起的会话操作；由聊天页的 ViewModel 消费并转成普通用户轮次或 UI 动作。 */
sealed interface AgentChatRequest {
    /** 向 [sessionId] 追加一条用户消息并启动 AI 轮次。 */
    data class Send(
        val sessionId: String,
        val text: String,
        val workspacePath: String
    ) : AgentChatRequest

    /** 停止 [sessionId] 正在运行的 AI 轮次。 */
    data class Stop(val sessionId: String) : AgentChatRequest

    /** 把 [sessionId] 切换为聊天页当前会话。 */
    data class Select(val sessionId: String) : AgentChatRequest
}

/**
 * 插件宿主 API 与聊天页之间的单消费者队列。
 *
 * 插件可以独立创建会话、读取消息，但真正启动 AI 轮次仍由 [AIAgentViewModel] 执行：
 * 那里持有工具授权、运行状态、通知与队列逻辑，另起一套会绕过这些边界。只有聊天页
 * ViewModel 存活时 [isAttached] 才为 true；没有消费者时 [submit] 返回 false，调用方
 * 以 `CHAT_UI_UNAVAILABLE` 拒绝，避免消息静默丢失。
 */
@Singleton
class AgentChatRequestBus @Inject constructor() {

    private val channel = Channel<AgentChatRequest>(capacity = MAX_QUEUED_REQUESTS)
    private val consumers = mutableSetOf<String>()

    val isAttached: Boolean
        get() = synchronized(consumers) { consumers.isNotEmpty() }

    fun attach(consumerId: String) {
        synchronized(consumers) { consumers.add(consumerId) }
    }

    fun detach(consumerId: String) {
        synchronized(consumers) {
            consumers.remove(consumerId)
            // 聊天页真正销毁后，未处理的请求不再有合法执行上下文；丢弃而不是留到下次启动。
            if (consumers.isEmpty()) {
                while (channel.tryReceive().isSuccess) {
                    // drain
                }
            }
        }
    }

    /** 入队一条请求；队列满或没有消费者时返回 false。 */
    fun submit(request: AgentChatRequest): Boolean = synchronized(consumers) {
        if (consumers.isEmpty()) false else channel.trySend(request).isSuccess
    }

    suspend fun receive(): AgentChatRequest = channel.receive()

    private companion object {
        const val MAX_QUEUED_REQUESTS = 64
    }
}
