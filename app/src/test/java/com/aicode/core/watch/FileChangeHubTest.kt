package com.aicode.core.watch

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileChangeHubTest {

    private val workspaceRepository = mockk<WorkspaceRepository>()
    private val containerInstaller = mockk<ContainerInstaller>()
    private val pathMapper = mockk<WorkspacePathMapper>()
    private val executionModeHolder = mockk<ExecutionModeHolder>()
    private val remoteFileWatchHub = mockk<RemoteFileWatchHub>()

    /** FileChangeHub 在构造时绑定主线程观察器，单测需先提供 Main。 */
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun hub(
        mode: ExecutionMode = ExecutionMode.REMOTE_SSH,
        workspace: String = WORKSPACE
    ): FileChangeHub {
        every { executionModeHolder.currentMode() } returns mode
        every { workspaceRepository.currentPath() } returns workspace
        return FileChangeHub(
            workspaceRepository,
            containerInstaller,
            pathMapper,
            executionModeHolder,
            remoteFileWatchHub
        )
    }

    private fun batch(vararg paths: String, truncated: Boolean = false) = FileChangeBatch(
        paths.map { path -> FileChange(ChangeRoot.OTHER, path, path, ChangeKind.MODIFIED) },
        truncated
    )

    @Test
    fun workspaceSubpaths_shareWorkspaceRootWatchAndFilterOutsideEvents() = runTest {
        every { remoteFileWatchHub.watch(WORKSPACE, true) } returns flowOf(
            batch(
                "$WORKSPACE/alpha/notes.md",
                "$WORKSPACE/beta/notes.md",
                "$WORKSPACE/other.md",
                SIBLING_WORKSPACE + "/alpha/notes.md"
            )
        )
        val fileChangeHub = hub()

        val alpha = fileChangeHub.watchRemoteDir("$WORKSPACE/alpha/", recursive = true).toList()
        val beta = fileChangeHub.watchRemoteDir("$WORKSPACE/beta", recursive = true).toList()

        assertEquals(listOf(batch("$WORKSPACE/alpha/notes.md")), alpha)
        assertEquals(listOf(batch("$WORKSPACE/beta/notes.md")), beta)
        verify(exactly = 2) { remoteFileWatchHub.watch(WORKSPACE, true) }
        verify(exactly = 0) { remoteFileWatchHub.watch("$WORKSPACE/alpha/", true) }
    }

    @Test
    fun workspaceRootWatch_keepsWorkspaceInsideOnly() = runTest {
        every { remoteFileWatchHub.watch(WORKSPACE, true) } returns flowOf(
            batch(
                WORKSPACE,
                "$WORKSPACE/src/Main.kt",
                SIBLING_WORKSPACE,
                "$SIBLING_WORKSPACE/src/Main.kt",
                "/var/log/app.log"
            )
        )

        val batches = hub().watchRemoteDir(WORKSPACE, recursive = true).toList()

        assertEquals(listOf(batch(WORKSPACE, "$WORKSPACE/src/Main.kt")), batches)
        verify(exactly = 1) { remoteFileWatchHub.watch(WORKSPACE, true) }
    }

    @Test
    fun pathOutsideWorkspace_watchesItsOwnRoot() = runTest {
        every { remoteFileWatchHub.watch("/var/data", false) } returns flowOf(batch("/var/data/a.txt"))

        val batches = hub().watchRemoteDir("/var/data", recursive = false).toList()

        assertEquals(listOf(batch("/var/data/a.txt")), batches)
        verify(exactly = 1) { remoteFileWatchHub.watch("/var/data", false) }
        verify(exactly = 0) { remoteFileWatchHub.watch(WORKSPACE, true) }
    }

    @Test
    fun nonRecursiveSubpath_keepsOnlyDirectChildren() = runTest {
        every { remoteFileWatchHub.watch(WORKSPACE, true) } returns flowOf(
            batch(
                "$WORKSPACE/alpha",
                "$WORKSPACE/alpha/notes.md",
                "$WORKSPACE/alpha/sub/deep.md",
                "$WORKSPACE/alpha2/notes.md"
            )
        )

        val batches = hub().watchRemoteDir("$WORKSPACE/alpha", recursive = false).toList()

        assertEquals(listOf(batch("$WORKSPACE/alpha", "$WORKSPACE/alpha/notes.md")), batches)
        verify(exactly = 1) { remoteFileWatchHub.watch(WORKSPACE, true) }
    }

    @Test
    fun fullyFilteredBatch_dropsEmptyEventsButKeepsTruncatedSignal() = runTest {
        every { remoteFileWatchHub.watch(WORKSPACE, true) } returns flowOf(
            FileChangeBatch(emptyList()),
            batch("$WORKSPACE/other/x.md", truncated = true),
            batch("$WORKSPACE/other/x.md")
        )

        val batches = hub().watchRemoteDir("$WORKSPACE/alpha", recursive = true).toList()

        assertEquals(
            listOf(
                FileChangeBatch(emptyList()),
                FileChangeBatch(emptyList(), truncated = true)
            ),
            batches
        )
    }

    @Test
    fun localMode_watchesExactRoot() = runTest {
        every { remoteFileWatchHub.watch("$WORKSPACE/alpha", true) } returns flowOf(
            batch("$WORKSPACE/alpha/notes.md")
        )

        val batches = hub(mode = ExecutionMode.LOCAL_PROOT)
            .watchRemoteDir("$WORKSPACE/alpha", recursive = true)
            .toList()

        assertEquals(listOf(batch("$WORKSPACE/alpha/notes.md")), batches)
        verify(exactly = 1) { remoteFileWatchHub.watch("$WORKSPACE/alpha", true) }
    }

    private companion object {
        const val WORKSPACE = "/srv/workspace/myproj"
        const val SIBLING_WORKSPACE = "/srv/workspace/myproj-other"
    }
}
