package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    /**
     * 分页读取当前会话消息。
     *
     * 走只读事务执行：一页可能横跨多个 CursorWindow，框架游标在翻页时会重跑语句，
     * 若此时同会话的消息被并发删除，窗口里的行数与首次计数的行数不再一致，
     * getString 会抛 "Couldn't read row N, col 0 from CursorWindow"。
     */
    @Transaction
    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** 一次性读取（非 Flow），用于跨请求重建上下文历史。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    /**
     * 按时间倒序读取一页消息，供插件只读会话内容。
     * [before] / [after] 为空表示不设该边界；返回按 timestamp 倒序，调用方需要时可再反转。
     */
    @Query(
        """
        SELECT * FROM agent_messages
        WHERE sessionId = :sessionId
          AND (:before IS NULL OR timestamp < :before)
          AND (:after IS NULL OR timestamp > :after)
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getMessagesPageDescending(
        sessionId: String,
        before: Long?,
        after: Long?,
        limit: Int
    ): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("UPDATE agent_messages SET isCompacted = 1, compactedBySummaryId = :summaryId WHERE sessionId = :sessionId AND id IN (:headIds) AND isContextExcluded = 0")
    suspend fun markMessagesCompactedByIds(sessionId: String, headIds: List<String>, summaryId: String)

    @Query("UPDATE chat_sessions SET lastInputTokens = 0 WHERE id = :sessionId")
    suspend fun resetLastInputTokens(sessionId: String)

    @Transaction
    suspend fun commitCompaction(
        sessionId: String,
        headIds: List<String>,
        messages: List<AgentMessageEntity>,
        summaryId: String
    ) {
        headIds.chunked(900).forEach { ids ->
            markMessagesCompactedByIds(sessionId, ids, summaryId)
        }
        insertAll(messages)
        resetLastInputTokens(sessionId)
    }

    /**
     * 恢复「所有者摘要已不存在」的压缩行，含被更晚压缩吞掉的旧摘要与压缩标记。返回受影响行数。
     * 回退点在压缩之前时，该压缩的摘要被删掉，它覆盖的历史才需要重新参与回放。
     */
    @Query(
        """
        UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL
        WHERE sessionId = :sessionId
          AND isCompacted = 1
          AND isContextExcluded = 0
          AND compactedBySummaryId IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM agent_messages s WHERE s.id = agent_messages.compactedBySummaryId)
        """
    )
    suspend fun restoreCompactedByMissingSummary(sessionId: String): Int

    /**
     * 旧版压缩没有逐条摘要归属（[AgentMessageEntity.compactedBySummaryId] 为空）：
     * 无法判断归属哪个摘要，只在会话已无存活摘要（压缩被整体撤销）时恢复。
     */
    @Query(
        """
        UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL
        WHERE sessionId = :sessionId
          AND isCompacted = 1
          AND isContextExcluded = 0
          AND isContextSummary = 0
          AND isCompactionMarker = 0
          AND compactedBySummaryId IS NULL
          AND NOT EXISTS (SELECT 1 FROM agent_messages s WHERE s.sessionId = :sessionId AND s.isContextSummary = 1 AND s.isCompacted = 0)
        """
    )
    suspend fun restoreUnattributedCompactedWithoutSummary(sessionId: String): Int

    @Transaction
    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        // 先删回滚点及之后：落在该范围内的压缩摘要一并删除，留在库里的才是回退点之前已生效的摘要。
        deleteMessagesFromTimestamp(sessionId, cutoff)
        // 压缩链逐层恢复：被删摘要吞掉的旧摘要本身也带 isCompacted 标记，需先恢复为存活，
        // 它覆盖的历史才能继续留在压缩状态。每轮解开一层，直到没有归属失效的压缩行为止。
        while (restoreCompactedByMissingSummary(sessionId) > 0) {
            // 仅用于迭代至收敛，无循环体。
        }
        restoreUnattributedCompactedWithoutSummary(sessionId)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    /** 会话是否已有任何消息（LIMIT 1 快速判断，避免全量读取）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_messages WHERE sessionId = :sessionId LIMIT 1)")
    suspend fun hasMessages(sessionId: String): Boolean

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * 把残留的「执行中」工具行（content 以占位标记开头）批量收尾为「已中断」。
     * 用于冷启动：上次进程被杀时正在执行的工具不可能仍在跑，否则其占位行会永久显示转圈。
     * 返回受影响的行数。
     */
    @Query("UPDATE agent_messages SET content = :interruptedContent, isError = 1 WHERE role = :toolRole AND content LIKE :pendingPrefix")
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    /**
     * 跨会话搜索某工作区下的聊天记录：命中用户 / 助手正文，排除已压缩与内部摘要行。
     * [escapedQuery] 已由调用方转义 LIKE 通配符（`!` `%` `_`），配合 SQL 里的 ESCAPE '!'。
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               s.workspacePath AS workspacePath,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND s.backendInstanceId IS NULL
          AND s.workspaceId IS NULL
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspace(workspacePath: String, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 跨多个工作区搜索聊天记录，供「全部工作区」侧栏视图检索。 */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               s.workspacePath AS workspacePath,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath IN (:workspacePaths)
          AND s.backendInstanceId IS NULL
          AND s.workspaceId IS NULL
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspaces(workspacePaths: List<String>, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 全库搜索所有工作区（含本地与离线远程历史）的聊天记录，供设置页「会话管理」使用。 */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               s.workspacePath AS workspacePath,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.backendInstanceId IS NULL
          AND s.workspaceId IS NULL
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchAllWorkspaces(escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** 实时统计各会话的可见消息条数，供设置页「会话管理」列表展示。 */
    @Query(
        """
        SELECT sessionId, COUNT(*) AS messageCount
        FROM agent_messages
        WHERE isCompacted = 0
          AND isContextSummary = 0
          AND isCompactionMarker = 0
        GROUP BY sessionId
        """
    )
    fun getSessionMessageCountsFlow(): Flow<List<SessionMessageCount>>

    /** 指定会话中时间戳不早于 [timestamp] 的消息条数（含并列时间戳），供定位时确定所需分页上限。 */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun countMessagesFromTimestamp(sessionId: String, timestamp: Long): Int

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** 分页读取（keyset：按 timestamp,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /**
     * 各会话消息正文占用的字节数（降序取前 [limit] 个），供存储空间页拆解「聊天记录」构成。
     *
     * `LENGTH(CAST(x AS BLOB))` 取的是 UTF-8 字节数——直接 `LENGTH(x)` 对文本返回字符数，中文会少算三分之二。
     * 只统计几个大字段，因此是估算值：不含索引、页对齐与 WAL 开销，必然小于数据库文件本身。
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS title,
               COUNT(*) AS messageCount,
               SUM(
                   LENGTH(CAST(m.content AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolCallsJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolArgs, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.reasoning, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.thinkingBlocksJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.attachmentsJson, '') AS BLOB))
               ) AS bytes
        FROM agent_messages m
        LEFT JOIN chat_sessions s ON s.id = m.sessionId
        GROUP BY m.sessionId
        ORDER BY bytes DESC
        LIMIT :limit
        """
    )
    suspend fun sessionStorageUsage(limit: Int): List<SessionStorageUsage>
}

/** 跨会话聊天记录搜索的命中投影（[AgentMessageDao.searchInWorkspace] 的投影）。 */
data class ChatSearchMatch(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val workspacePath: String = ""
)

/** 单个会话的消息条数投影（[AgentMessageDao.getSessionMessageCountsFlow] 的投影）。 */
data class SessionMessageCount(
    val sessionId: String,
    val messageCount: Int
)

/** 单个会话的消息占用估算（[AgentMessageDao.sessionStorageUsage] 的投影）。 */
data class SessionStorageUsage(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val bytes: Long
)
