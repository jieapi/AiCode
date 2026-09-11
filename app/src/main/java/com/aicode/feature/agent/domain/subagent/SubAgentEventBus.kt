package com.aicode.feature.agent.domain.subagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理事件类型。前四种为生命周期事件；后两种为收发消息事件，只投递内容，不影响活跃集合。
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /** 主会话向子代理发来一条消息（发送方是父会话）。 */
    MESSAGE_FROM_PARENT,

    /** 子代理向主会话发来一条消息（发送方是子会话）。 */
    MESSAGE_FROM_SUB
}

/**
 * 子代理生命周期事件。
 *
 * @property subSessionId 子代理会话 id。
 * @property parentSessionId 父会话 id（子会话记录里 parentId）。
 * @property type 事件类型。
 * @property detail 附加说明：SPAWNED 为任务指令；COMPLETED/FAILED 为子代理最终输出/错误信息；
 *   MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB 为消息正文。
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = ""
)

/**
 * 子代理事件总线：TaskTool 发出 SPAWNED（子代理已创建），ViewModel 收集后自动
 * 在子会话上启动 AI 工作流；子会话工作流结束时 ViewModel 再发 COMPLETED/FAILED，
 * 父会话据此注入后台通知（类比 terminal 的 notify=true 异步回调）。
 *
 * 同时维护活跃子代理会话 id 集合，供 TaskTool 查询并发上限（最多 5 个运行中）。
 */
@Singleton
class SubAgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /** 当前活跃（运行中）的子代理会话 id 集合。 */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /** 运行中的子代理数量。 */
    val activeCount: Int get() = _activeSubSessionIds.value.size

    /** 是否已达并发上限。 */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    /**
     * 直接把某个子代理移出活跃集合，不广播事件；返回它此前是否处于活跃状态。
     *
     * 用户在界面上手动停止或删除运行中的子会话时走这条路径：不能改用 [emit]，
     * 因为 ViewModel 收到 STOPPED 事件后又会回调 stopAgentSession，形成无限循环。
     * 返回值同时用于区分「用户手动终止」与「TaskTool 已处理过」（后者返回 false），
     * 避免向父代理重复投递通知。
     */
    fun release(subSessionId: String): Boolean {
        val current = _activeSubSessionIds.value
        if (subSessionId !in current) return false
        _activeSubSessionIds.value = current - subSessionId
        return true
    }

    fun emit(event: SubAgentEvent) {
        // 同步维护活跃集合
        when (event.type) {
            SubAgentEventType.SPAWNED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value + event.subSessionId
            }
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.STOPPED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value - event.subSessionId
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                // 收发消息不改变运行状态，活跃集合保持不变。
            }
        }
        _events.tryEmit(event)
    }

    companion object {
        /** 同时运行的子代理上限。判定以此为准，工具层只引用不自己再写一份。 */
        const val MAX_RUNNING = 5
    }
}