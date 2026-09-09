package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 群聊定时任务（Routines）：绑定到群聊房间中某个成员，按 [schedule] 触发一次成员 turn。
 */
@Entity(tableName = "group_chat_routines")
data class GroupChatRoutineEntity(
    @PrimaryKey val id: String,
    /** 所属群聊房间会话 id。 */
    val roomId: String,
    /** 执行任务的成员（agent 名）。 */
    val memberKey: String,
    /** 调度表达式（支持：every Nm/Nh/Nd、hourly、daily HH:MM，见 GroupRoutineScheduleParser）。 */
    val schedule: String,
    /** 每次触发时注入成员的指令。 */
    val instruction: String,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null
)
