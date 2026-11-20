package com.aicode.feature.agent.domain.session

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.settings.presentation.WorkspaceBucketSource
import com.aicode.feature.settings.presentation.buildWorkspaceBuckets
import com.aicode.feature.settings.presentation.classifyOfflineWorkspacePath
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import com.aicode.feature.workspace.domain.model.WorkspaceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SessionUseCaseMoveTest {
    private lateinit var db: AgentDatabase
    private lateinit var sessions: ChatSessionDao
    private lateinit var messages: AgentMessageDao
    private lateinit var useCase: SessionUseCase

    private val wsA = "/data/projects/alpha"
    private val wsB = "/data/projects/beta"
    private val wsRemote = "/root/.aicode/workspaces/cloud-api"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java
        ).allowMainThreadQueries().build()
        sessions = db.chatSessionDao()
        messages = db.agentMessageDao()
        useCase = SessionUseCase(sessions, messages)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun moveRootSessionMovesChildrenPreservesTimestampsAndPin() = runTest {
        val root = useCase.newSessionEntity(wsA).copy(
            id = "root-1",
            title = "Alpha task",
            updatedAt = 12345L,
            isPinned = true
        )
        val child = useCase.newSubSessionEntity("sub", root.id, root, "explore").copy(
            id = "sub-1",
            updatedAt = 12346L
        )
        val stayInA = useCase.newSessionEntity(wsA).copy(id = "stay-a", updatedAt = 99999L)
        val existingInB = useCase.newSessionEntity(wsB).copy(id = "exist-b", updatedAt = 50000L)
        sessions.upsertAll(listOf(root, child, stayInA, existingInB))
        listOf(root, child, stayInA, existingInB).forEach { s ->
            messages.insert(AgentMessageEntity("msg-${s.id}", s.id, "USER", "hello ${s.id}", s.updatedAt))
        }

        val moved = useCase.moveSessionsToWorkspace(listOf(root.id), wsB)
        assertEquals(listOf(root.id), moved.map { it.id })

        val movedRoot = sessions.getById(root.id)
        val movedChild = sessions.getById(child.id)
        assertNotNull(movedRoot)
        assertNotNull(movedChild)
        assertEquals(wsB, movedRoot?.workspacePath)
        assertEquals(wsB, movedChild?.workspacePath)
        assertEquals(12345L, movedRoot?.updatedAt)
        assertEquals(true, movedRoot?.isPinned)

        assertEquals(listOf(stayInA.id), sessions.getRootSessionsByWorkspaceOnce(wsA).map { it.id })
        val bRootIds = sessions.getRootSessionsByWorkspaceOnce(wsB).map { it.id }
        assertEquals(listOf(root.id, existingInB.id), bRootIds)
    }

    @Test
    fun moveIgnoresSubSessionsSameWorkspaceAndScopedWorkspaceRefSessions() = runTest {
        val rootA = useCase.newSessionEntity(wsA).copy(id = "root-a")
        val subA = useCase.newSubSessionEntity("sub", rootA.id, rootA, "explore").copy(id = "sub-a")
        val alreadyInB = useCase.newSessionEntity(wsB).copy(id = "already-b")
        val refSession = useCase.newSessionEntity(WorkspaceRef("plugin", "w1"), wsA).copy(id = "ref-a")
        sessions.upsertAll(listOf(rootA, subA, alreadyInB, refSession))

        val moved = useCase.moveSessionsToWorkspace(
            listOf(subA.id, alreadyInB.id, refSession.id, "missing-id"),
            wsB
        )
        assertTrue(moved.isEmpty())
        assertEquals(wsA, sessions.getById(subA.id)?.workspacePath)
        assertEquals(wsB, sessions.getById(alreadyInB.id)?.workspacePath)
        assertEquals(wsA, sessions.getById(refSession.id)?.workspacePath)
    }

    @Test
    fun movingEmptySessionRecyclesRedundantEmptyInTargetWhileKeepingPreferred() = runTest {
        val movedEmpty = useCase.newSessionEntity(wsA).copy(id = "empty-a", updatedAt = 100L)
        val targetEmpty = useCase.newSessionEntity(wsB).copy(id = "empty-b", updatedAt = 200L)
        sessions.upsertAll(listOf(movedEmpty, targetEmpty))

        val moved = useCase.moveSessionsToWorkspace(
            sessionIds = listOf(movedEmpty.id),
            targetWorkspacePath = wsB,
            preferredEmptyId = movedEmpty.id
        )
        assertEquals(listOf(movedEmpty.id), moved.map { it.id })
        assertNotNull(sessions.getById(movedEmpty.id))
        assertEquals(wsB, sessions.getById(movedEmpty.id)?.workspacePath)
        assertNull(sessions.getById(targetEmpty.id))
    }

    @Test
    fun searchAllWorkspacesAndMessageCountsIncludeRemoteSessions() = runTest {
        val a1 = useCase.newSessionEntity(wsA).copy(id = "a1", title = "Local Alpha", updatedAt = 100L)
        val r1 = useCase.newSessionEntity(wsRemote).copy(id = "r1", title = "Remote Cloud", updatedAt = 300L)
        sessions.upsertAll(listOf(a1, r1))
        messages.insertAll(
            listOf(
                AgentMessageEntity("m1", a1.id, "USER", "alpha target keyword", 101L),
                AgentMessageEntity("m2", r1.id, "ASSISTANT", "remote target keyword", 301L),
                AgentMessageEntity("m3", r1.id, "USER", "second visible message", 302L),
                AgentMessageEntity("m4", r1.id, "ASSISTANT", "compacted summary", 303L, isCompacted = true)
            )
        )

        val allEntities = useCase.getAllStandardSessionsFlow().first()
        assertEquals(listOf("r1", "a1"), allEntities.map { it.id })

        val hits = messages.searchAllWorkspaces("target", 10)
        assertEquals(listOf("m2", "m1"), hits.map { it.messageId })
        assertEquals(wsRemote, hits[0].workspacePath)
        assertEquals(wsA, hits[1].workspacePath)

        val counts = messages.getSessionMessageCountsFlow().first().associate { it.sessionId to it.messageCount }
        assertEquals(1, counts["a1"])
        assertEquals(2, counts["r1"])
    }

    @Test
    fun buildWorkspaceBucketsOrdersCurrentAndClassifiesOfflineRemote() {
        val projectsRoot = "/data/user/0/com.aicode/files/projects"
        val currentWs = Workspace(name = "alpha", path = "$projectsRoot/alpha", type = WorkspaceType.INTERNAL)
        val otherLocal = Workspace(name = "beta", path = "$projectsRoot/beta", type = WorkspaceType.INTERNAL)
        val sCurrent = useCase.newSessionEntity(currentWs.path).copy(id = "s-cur", updatedAt = 10L).toDomain()
        val sRemote = useCase.newSessionEntity(wsRemote).copy(id = "s-rem", updatedAt = 500L).toDomain()
        val sExtOffline = useCase.newSessionEntity("/storage/emulated/0/Docs").copy(id = "s-ext", updatedAt = 200L).toDomain()

        val buckets = buildWorkspaceBuckets(
            workspaces = listOf(otherLocal, currentWs),
            currentWorkspace = currentWs,
            rootSessions = listOf(sCurrent, sRemote, sExtOffline),
            projectsRootPath = projectsRoot
        )

        assertEquals(4, buckets.size)
        assertEquals("alpha", buckets[0].name)
        assertTrue(buckets[0].isCurrent)
        assertEquals("beta", buckets[1].name)
        assertTrue(buckets[1].sessions.isEmpty())
        assertEquals("cloud-api", buckets[2].name)
        assertEquals(WorkspaceBucketSource.REMOTE_OR_OTHER, buckets[2].source)
        assertEquals("Docs", buckets[3].name)
        assertEquals(WorkspaceBucketSource.EXTERNAL_OFFLINE, buckets[3].source)

        assertEquals(
            WorkspaceBucketSource.LOCAL_OFFLINE,
            classifyOfflineWorkspacePath("$projectsRoot/archived", projectsRoot)
        )
    }
}
