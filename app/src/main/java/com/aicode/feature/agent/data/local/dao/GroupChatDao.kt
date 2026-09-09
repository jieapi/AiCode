package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.GroupChatRoutineEntity
import kotlinx.coroutines.flow.Flow

/**
 * 群聊相关持久化。房间本身是 chat_sessions 里 isGroupChat=1 的根会话，
 * 消息复用 agent_messages（成员发言 role=USER + senderName），故此处只管 Routines。
 */
@Dao
interface GroupChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(routine: GroupChatRoutineEntity)

    @Query("DELETE FROM group_chat_routines WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    @Query("DELETE FROM group_chat_routines WHERE roomId = :roomId")
    suspend fun deleteByRoom(roomId: String)

    @Query("SELECT * FROM group_chat_routines WHERE id = :id")
    suspend fun getRoutineById(id: String): GroupChatRoutineEntity?

    @Query("SELECT * FROM group_chat_routines WHERE roomId = :roomId ORDER BY schedule ASC")
    fun routinesByRoom(roomId: String): Flow<List<GroupChatRoutineEntity>>

    @Query("SELECT * FROM group_chat_routines WHERE roomId = :roomId")
    suspend fun routinesByRoomOnce(roomId: String): List<GroupChatRoutineEntity>

    @Query("SELECT * FROM group_chat_routines WHERE enabled = 1")
    suspend fun enabledRoutinesOnce(): List<GroupChatRoutineEntity>

    @Query("UPDATE group_chat_routines SET lastRunAt = :at WHERE id = :id")
    suspend fun markRoutineRun(id: String, at: Long)
}
