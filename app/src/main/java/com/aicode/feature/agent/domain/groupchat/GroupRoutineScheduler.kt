package com.aicode.feature.agent.domain.groupchat

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.GroupChatDao
import com.aicode.feature.agent.data.local.entity.GroupChatRoutineEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 群聊定时任务（Routines）调度器。
 *
 * 数据落在 group_chat_routines 表（migration 47）。触发方式：把 routine 指令以
 * 用户消息形式发进房间（带 @成员 前缀），复用 GroupChatCoordinator 的 drive 链路
 * 让成员执行并把结果发言到房间——对齐 Hermes「routine 结果落在 bot 自己的聊天历史」。
 *
 * 实际触发由 [GroupRoutineWorker]（WorkManager 周期扫描，最小 15 分钟）调用
 * [runDueRoutines] 完成；本类不持有 Context，保持可单测。
 */
@Singleton
class GroupRoutineScheduler @Inject constructor(
    private val groupChatDao: GroupChatDao,
    private val chatSessionDao: ChatSessionDao,
    private val coordinator: GroupChatCoordinator
) {
    companion object {
        private const val TAG = "GroupRoutineScheduler"
        /** routine 触发消息的固定前缀，用户可在房间内识别这是定时任务。 */
        const val ROUTINE_MARKER = "[Scheduled routine]"
    }

    /** 防止并发扫描（Worker 与手动触发可能同时进入）。 */
    private val running = AtomicBoolean(false)

    /** 检查全部启用的 routine，到期的触发一次。 */
    suspend fun runDueRoutines(now: Long = System.currentTimeMillis()) {
        if (!running.compareAndSet(false, true)) return
        try {
            groupChatDao.enabledRoutinesOnce().forEach { routine ->
                val next = GroupRoutineScheduleParser.nextRunAfter(routine.schedule, routine.lastRunAt, now)
                if (next != null && next <= now) {
                    fireRoutine(routine, now)
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** 立即触发一个 routine（用户手动「运行一次」）。 */
    suspend fun runNow(routineId: String) {
        val routine = groupChatDao.getRoutineById(routineId) ?: return
        fireRoutine(routine, System.currentTimeMillis())
    }

    /**
     * 触发一个 routine：成员仍在该房间时，把指令作为用户消息发进房间
     * （@成员 前缀让 drive 轮转到目标成员执行），随后标记已运行。
     */
    private suspend fun fireRoutine(routine: GroupChatRoutineEntity, now: Long) {
        val room = chatSessionDao.getById(routine.roomId)
        if (room == null || !room.isGroupChat) return
        val member = coordinator.decodeMembers(room)
            .firstOrNull { it.memberKey == routine.memberKey }
        if (member == null) {
            // 成员已不在房间：标记已执行，避免每次扫描重复尝试（不刷消息）
            FileLogger.w(TAG, "routine=${routine.id} 成员 ${routine.memberKey} 不在房间，跳过")
            groupChatDao.markRoutineRun(routine.id, now)
            return
        }
        val handle = member.handle.ifEmpty { member.memberKey }
        val text = "$ROUTINE_MARKER @$handle ${routine.instruction.trim()}"
        // parseHold=false：routine 指令正文可能含 stop/pause 等词，不能误暂停成员
        coordinator.sendUserMessage(routine.roomId, text, parseHold = false)
        groupChatDao.markRoutineRun(routine.id, now)
        FileLogger.i(TAG, "routine=${routine.id} 已触发 room=${routine.roomId} member=${routine.memberKey}")
    }

    /** 新建 routine；schedule 非法、指令为空或成员不在房间时返回 false（不落库）。 */
    suspend fun createRoutine(roomId: String, memberKey: String, schedule: String, instruction: String): Boolean {
        val s = schedule.trim()
        val ins = instruction.trim()
        if (!GroupRoutineScheduleParser.isValid(s) || ins.isEmpty()) return false
        val room = chatSessionDao.getById(roomId) ?: return false
        if (coordinator.decodeMembers(room).none { it.memberKey == memberKey }) return false
        groupChatDao.upsertRoutine(
            GroupChatRoutineEntity(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                memberKey = memberKey,
                schedule = s,
                instruction = ins
            )
        )
        return true
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        // 必须按 id 全量查：enabledRoutinesOnce 只含启用项，重新启用（false→true）时查不到会静默失效
        val routine = groupChatDao.getRoutineById(id) ?: return
        groupChatDao.upsertRoutine(routine.copy(enabled = enabled))
    }

    suspend fun deleteRoutine(id: String) {
        groupChatDao.deleteRoutine(id)
    }
}
