package com.aicode.feature.agent.domain.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** 会话当前或最近一轮 AI 运行的快照；[running] 为 false 时 [status] 是终态。 */
data class AgentRunSnapshot(
    val sessionId: String,
    val running: Boolean,
    val status: String,
    val error: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

/** 运行状态变化事件；页面桥据此推送 chat.run.started / chat.run.finished。 */
sealed interface AgentRunEvent {
    val sessionId: String

    data class Started(
        override val sessionId: String,
        val startedAt: Long
    ) : AgentRunEvent

    data class Finished(
        override val sessionId: String,
        val status: String,
        val error: String?,
        val finishedAt: Long
    ) : AgentRunEvent
}

/**
 * 跨进程/页面共享的 AI 运行状态。
 *
 * 只记录轮次开始与结束，不保存消息正文；流式内容仍由聊天页自己的状态流维护。
 * 插件页面用它判断「这一轮是否还在跑」，避免只靠 hook 或固定轮询。
 */
@Singleton
class AgentRunStateStore @Inject constructor() {

    private val _states = MutableStateFlow<Map<String, AgentRunSnapshot>>(emptyMap())
    val states: StateFlow<Map<String, AgentRunSnapshot>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<AgentRunEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentRunEvent> = _events.asSharedFlow()

    fun started(sessionId: String) {
        val now = System.currentTimeMillis()
        _states.value = _states.value + (sessionId to AgentRunSnapshot(
            sessionId = sessionId,
            running = true,
            status = "running",
            startedAt = now
        ))
        _events.tryEmit(AgentRunEvent.Started(sessionId, now))
    }

    fun finished(sessionId: String, status: String, error: String?) {
        val now = System.currentTimeMillis()
        val previous = _states.value[sessionId]
        _states.value = _states.value + (sessionId to AgentRunSnapshot(
            sessionId = sessionId,
            running = false,
            status = status,
            error = error,
            startedAt = previous?.startedAt,
            finishedAt = now
        ))
        _events.tryEmit(AgentRunEvent.Finished(sessionId, status, error, now))
    }

    fun snapshot(sessionId: String): AgentRunSnapshot? = _states.value[sessionId]

    /** 等到该会话不在运行；[timeoutMs] 内未结束返回最后一次快照。 */
    suspend fun awaitIdle(sessionId: String, timeoutMs: Long): AgentRunSnapshot? {
        val current = _states.value[sessionId]
        if (current?.running != true) return current
        return withTimeoutOrNull(timeoutMs) {
            _states.map { it[sessionId] }.first { it?.running != true }
        } ?: _states.value[sessionId]
    }
}
