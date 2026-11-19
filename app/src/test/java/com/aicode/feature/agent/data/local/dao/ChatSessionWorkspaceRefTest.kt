package com.aicode.feature.agent.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ChatSessionWorkspaceRefTest {
    private lateinit var db: AgentDatabase
    private lateinit var sessions: ChatSessionDao
    private lateinit var messages: AgentMessageDao
    private lateinit var useCase: SessionUseCase
    private val first = WorkspaceRef("backend-a", "same-id")
    private val second = WorkspaceRef("backend-b", "same-id")
    private val path = "/shared/path"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java
        ).allowMainThreadQueries().build()
        sessions = db.chatSessionDao()
        messages = db.agentMessageDao()
        useCase = SessionUseCase(sessions, messages)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun samePathAndWorkspaceIdAcrossInstancesAreIsolated() = runTest {
        val a = useCase.newSessionEntity(first, path).copy(id = "a", updatedAt = 2)
        val child = useCase.newSubSessionEntity("child", a.id, a, "explore")
        val aEmpty = useCase.newSessionEntity(first, "/moved/path").copy(id = "empty", updatedAt = 1)
        val b = useCase.newSessionEntity(second, path).copy(id = "b", updatedAt = 3)
        val c = useCase.newSessionEntity(first.copy(workspaceId = "other"), path).copy(id = "c")
        sessions.upsertAll(listOf(a, child, aEmpty, b, c))
        messages.insertAll(listOf(a, child, b).map {
            AgentMessageEntity(it.id, it.id, "USER", it.title, it.updatedAt)
        })

        assertEquals(first, child.workspaceRef)
        assertEquals(first, ChatSessionEntity.fromDomain(a.toDomain()).workspaceRef)
        assertEquals(listOf(a, aEmpty), useCase.getRootSessionsOfWorkspace(first).first())
        assertEquals(a, useCase.getMostRecentSessionOfWorkspace(first))
        assertEquals(aEmpty, useCase.getEmptySessionOfWorkspace(first))
        assertNull(sessions.getByIdAndWorkspaceRef(b.id, first.backendInstanceId, first.workspaceId))

        assertEquals(3, useCase.deleteSessionsByWorkspace(first))
        assertNull(sessions.getById(a.id))
        assertNull(sessions.getById(child.id))
        assertTrue(messages.getMessagesBySessionOnce(a.id).isEmpty())
        assertTrue(messages.getMessagesBySessionOnce(child.id).isEmpty())
        assertEquals(listOf(b), useCase.getSessionsOfWorkspace(second).first())
        assertEquals(1, messages.getMessagesBySessionOnce(b.id).size)
        assertEquals(c, sessions.getById(c.id))
    }

    @Test
    fun legacySessionsAreVisibleOnlyThroughLegacyReadsUntilExplicitlyClaimed() = runTest {
        val claimed = useCase.newSessionEntity(path).copy(id = "claimed")
        val unclaimed = useCase.newSessionEntity(path).copy(id = "unclaimed")
        val bound = useCase.newSessionEntity(second, path).copy(id = "bound")
        sessions.upsertAll(listOf(claimed, unclaimed, bound))

        assertTrue(useCase.getSessionsOfWorkspace(first).first().isEmpty())
        assertEquals(setOf(claimed.id, unclaimed.id), useCase.getLegacySessionsByWorkspace(path).map { it.id }.toSet())
        assertEquals(0, useCase.deleteSessionsByWorkspace(first))
        assertEquals(1, useCase.claimLegacySessions(listOf(claimed.id, bound.id), first))
        assertEquals(0, useCase.claimLegacySessions(listOf(claimed.id), second))
        assertEquals(listOf(claimed.id), useCase.getSessionsOfWorkspace(first).first().map { it.id })
        assertEquals(listOf(unclaimed), useCase.getLegacySessionsByWorkspace(path))
        assertEquals(listOf(bound), useCase.getSessionsOfWorkspace(second).first())
    }
}
