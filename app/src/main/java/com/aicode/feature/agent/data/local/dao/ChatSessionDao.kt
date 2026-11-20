package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId IS NULL AND workspaceId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllStandardSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath IN (:workspacePaths) AND backendInstanceId IS NULL AND workspaceId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspaces(workspacePaths: List<String>): Flow<List<ChatSessionEntity>>

    @Query("UPDATE chat_sessions SET workspacePath = :targetWorkspacePath WHERE id IN (:sessionIds) AND backendInstanceId IS NULL AND workspaceId IS NULL")
    suspend fun updateWorkspacePathByIds(sessionIds: List<String>, targetWorkspacePath: String): Int

    @Query("UPDATE chat_sessions SET workspacePath = :targetWorkspacePath WHERE parentId IN (:parentIds) AND backendInstanceId IS NULL AND workspaceId IS NULL")
    suspend fun updateWorkspacePathByParents(parentIds: List<String>, targetWorkspacePath: String): Int

    /** 跨工作区迁移根会话及其派生子代理会话，保证父子归属在同一事务内同步切换。 */
    @Transaction
    suspend fun moveSessionsToWorkspace(sessionIds: List<String>, targetWorkspacePath: String): Int {
        if (sessionIds.isEmpty()) return 0
        var moved = 0
        sessionIds.chunked(500).forEach { chunk ->
            moved += updateWorkspacePathByIds(chunk, targetWorkspacePath)
            updateWorkspacePathByParents(chunk, targetWorkspacePath)
        }
        return moved
    }

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL AND parentId IS NULL AND NOT EXISTS (SELECT 1 FROM agent_messages WHERE sessionId = chat_sessions.id) ORDER BY isPinned DESC, updatedAt DESC LIMIT 1")
    suspend fun getEmptyRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 最近更新的根会话（忽略置顶，仅按 updatedAt 降序），供启动时「打开最近会话」。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL AND parentId IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspaceRef(backendInstanceId: String, workspaceId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspaceRef(backendInstanceId: String, workspaceId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceRefOnce(backendInstanceId: String, workspaceId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceRefOnce(backendInstanceId: String, workspaceId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId AND parentId IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspaceRef(backendInstanceId: String, workspaceId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId AND parentId IS NULL AND NOT EXISTS (SELECT 1 FROM agent_messages WHERE sessionId = chat_sessions.id) ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getEmptyRootSessionByWorkspaceRef(backendInstanceId: String, workspaceId: String): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId")
    suspend fun deleteByWorkspaceRef(backendInstanceId: String, workspaceId: String)

    @Query("SELECT * FROM chat_sessions WHERE backendInstanceId IS NULL AND workspaceId IS NULL AND workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getLegacySessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET backendInstanceId = :backendInstanceId, workspaceId = :workspaceId WHERE id IN (:sessionIds) AND backendInstanceId IS NULL AND workspaceId IS NULL")
    suspend fun claimLegacySessions(sessionIds: List<String>, backendInstanceId: String, workspaceId: String): Int

    /** 指定父会话的全部子会话（子代理），按最近更新降序。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    fun getSubSessionsByParent(parentId: String): Flow<List<ChatSessionEntity>>

    /** 指定父会话的全部子会话（子代理），一次性查询。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    suspend fun getSubSessionsByParentOnce(parentId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id AND backendInstanceId = :backendInstanceId AND workspaceId = :workspaceId")
    suspend fun getByIdAndWorkspaceRef(id: String, backendInstanceId: String, workspaceId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    /** 单个会话的实时流（根会话与子会话通用）。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ChatSessionEntity?>

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_sessions WHERE workspacePath = :workspacePath AND backendInstanceId IS NULL AND workspaceId IS NULL")
    suspend fun deleteByWorkspace(workspacePath: String)

    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model, lastInputTokens = 0 WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    /** 设定/覆盖 goal：同时重置轮次计数与 token 快照。goal 为 null 表示清除。 */
    @Query("UPDATE chat_sessions SET goal = :goal, goalStatus = :status, goalTurnCount = 0, goalStartInputTokens = :startInput, goalStartOutputTokens = :startOutput WHERE id = :id")
    suspend fun updateGoal(id: String, goal: String?, status: String?, startInput: Int, startOutput: Int)

    @Query("UPDATE chat_sessions SET goalStatus = :status WHERE id = :id")
    suspend fun updateGoalStatus(id: String, status: String?)

    @Query("UPDATE chat_sessions SET goalTurnCount = goalTurnCount + 1 WHERE id = :id")
    suspend fun incrementGoalTurn(id: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)

    @Query("UPDATE chat_sessions SET workflowId = :workflowId, workflowState = :workflowState WHERE id = :id")
    suspend fun updateWorkflow(id: String, workflowId: String?, workflowState: String? = null)

    @Query("UPDATE chat_sessions SET workflowState = :workflowState WHERE id = :id")
    suspend fun updateWorkflowState(id: String, workflowState: String?)
}
