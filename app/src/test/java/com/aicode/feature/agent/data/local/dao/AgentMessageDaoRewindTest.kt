package com.aicode.feature.agent.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回退与上下文压缩的交互：回退点之后的压缩被撤销，回退点之前已生效的摘要必须保留，
 * 否则上下文会退回压缩前的全量历史，下一次发送前又要重新压缩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AgentMessageDaoRewindTest {

    private lateinit var db: AgentDatabase
    private lateinit var dao: AgentMessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.agentMessageDao()
    }

    @After
    fun tearDown() = db.close()

    private fun message(
        id: String,
        timestamp: Long,
        role: String = "USER",
        compacted: Boolean = false,
        summaryId: String? = null,
        summary: Boolean = false,
        marker: Boolean = false
    ) = AgentMessageEntity(
        id = id,
        sessionId = SESSION,
        role = role,
        content = id,
        timestamp = timestamp,
        isCompacted = compacted,
        isContextSummary = summary,
        isCompactionMarker = marker,
        compactedBySummaryId = summaryId
    )

    @Test
    fun rewindAfterCompactionKeepsSummaryAndCompactedHistory() = runTest {
        dao.insertAll(
            listOf(
                message("old", 100, compacted = true, summaryId = "summary"),
                message("marker", 200, marker = true),
                message("summary", 201, role = "ASSISTANT", summary = true),
                message("kept", 300),
                message("dropped", 400, role = "ASSISTANT")
            )
        )

        dao.rewindConversation(SESSION, 400)

        val byId = dao.getMessagesBySessionOnce(SESSION).associateBy { it.id }
        assertEquals(setOf("old", "marker", "summary", "kept"), byId.keys)
        assertTrue(byId.getValue("old").isCompacted)
        assertEquals("summary", byId.getValue("old").compactedBySummaryId)
        assertFalse(byId.getValue("summary").isCompacted)
        assertFalse(byId.getValue("marker").isCompacted)

        // 回放上下文仍是「摘要 + 保留的压缩后历史」，没有退回压缩前的全量历史。
        val history = MessagePersistenceUseCase(dao, db).buildHistory(SESSION, "pending")
        assertEquals(listOf("marker", "summary", "kept"), history.map { it.id })
    }

    @Test
    fun rewindBeforeCompactionDropsSummaryAndRestoresHistory() = runTest {
        dao.insertAll(
            listOf(
                message("old", 100, compacted = true, summaryId = "summary"),
                message("cut", 150, compacted = true, summaryId = "summary"),
                message("marker", 200, marker = true),
                message("summary", 201, role = "ASSISTANT", summary = true)
            )
        )

        dao.rewindConversation(SESSION, 150)

        val byId = dao.getMessagesBySessionOnce(SESSION).associateBy { it.id }
        assertEquals(setOf("old"), byId.keys)
        assertFalse(byId.getValue("old").isCompacted)
        assertNull(byId.getValue("old").compactedBySummaryId)
    }

    @Test
    fun rewindBetweenCompactionsRestoresOlderSummary() = runTest {
        dao.insertAll(
            listOf(
                message("old", 100, compacted = true, summaryId = "summary1"),
                message("marker1", 200, marker = true, compacted = true, summaryId = "summary2"),
                message("summary1", 201, role = "ASSISTANT", summary = true, compacted = true, summaryId = "summary2"),
                message("mid", 300, compacted = true, summaryId = "summary2"),
                message("marker2", 400, marker = true),
                message("summary2", 401, role = "ASSISTANT", summary = true)
            )
        )

        dao.rewindConversation(SESSION, 300)

        val byId = dao.getMessagesBySessionOnce(SESSION).associateBy { it.id }
        assertEquals(setOf("old", "marker1", "summary1"), byId.keys)
        // 第二次压缩被撤销：它吞掉的旧摘要恢复为存活，旧摘要覆盖的历史继续保持压缩。
        assertFalse(byId.getValue("summary1").isCompacted)
        assertNull(byId.getValue("summary1").compactedBySummaryId)
        assertFalse(byId.getValue("marker1").isCompacted)
        assertTrue(byId.getValue("old").isCompacted)
        assertEquals("summary1", byId.getValue("old").compactedBySummaryId)
    }

    @Test
    fun rewindAfterCompactionKeepsLegacyUnattributedHistoryCompacted() = runTest {
        dao.insertAll(
            listOf(
                message("legacy", 100, compacted = true),
                message("marker", 200, marker = true),
                message("summary", 201, role = "ASSISTANT", summary = true),
                message("kept", 300)
            )
        )

        dao.rewindConversation(SESSION, 300)

        val legacy = dao.getMessageById("legacy")!!
        // 仍有存活摘要时，旧版无归属的压缩历史不能被重新放回上下文。
        assertTrue(legacy.isCompacted)
    }

    private companion object {
        const val SESSION = "session"
    }
}
