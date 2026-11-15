package com.aicode.feature.plugin.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.RemoteWatchException
import com.aicode.core.watch.WatchFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PluginFileWatchSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hub = mockk<FileChangeHub>()

    private fun source(dispatcher: CoroutineDispatcher) = PluginFileWatchSource(hub, dispatcher)

    private fun batch(vararg changes: Pair<File, ChangeKind>, truncated: Boolean = false) = FileChangeBatch(
        changes.map { (file, kind) -> FileChange(ChangeRoot.OTHER, file.path, file.path, kind) },
        truncated
    )

    private fun stubHub(directory: File, recursive: Boolean, vararg batches: FileChangeBatch) {
        every {
            hub.watchHostDir(
                hostDir = directory,
                recursive = recursive,
                filter = WatchFilter(followGitignore = false)
            )
        } returns flowOf(*batches)
    }

    @Test
    fun localDirectory_reusesHubWithGitignoreDisabled() = runTest {
        val directory = tmp.newFolder("local").canonicalFile
        val change = batch(File(directory, "ignored.log") to ChangeKind.CREATED)
        stubHub(directory, recursive = true, change)
        val watcher = source(StandardTestDispatcher(testScheduler))
        val events = watcher.watch(PluginWatchTarget.Local(directory), recursive = true)
        verify(exactly = 0) { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) }

        assertEquals(listOf(change), events.toList())
        verify(exactly = 1) {
            hub.watchHostDir(
                hostDir = directory,
                recursive = true,
                filter = WatchFilter(followGitignore = false)
            )
        }
    }

    @Test
    fun localFile_watchesParentAndFiltersExactlyTheRequestedFile() = runTest {
        val file = tmp.newFile("notes.md").canonicalFile
        val parent = requireNotNull(file.parentFile)
        val unrelated = File(parent, "other.md")
        val event = batch(unrelated to ChangeKind.MODIFIED, file to ChangeKind.MODIFIED)
        stubHub(parent, recursive = false, event)

        val batches = source(StandardTestDispatcher(testScheduler))
            .watch(PluginWatchTarget.Local(file), recursive = true).toList()

        assertEquals(listOf(batch(file to ChangeKind.MODIFIED)), batches)
    }

    @Test
    fun localMissingPath_isPassedToHubAsDirectory() = runTest {
        val future = File(tmp.root, "future").canonicalFile
        val event = batch(future to ChangeKind.CREATED)
        stubHub(future, recursive = false, event)

        assertEquals(
            listOf(event),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Local(future), false).toList()
        )
    }

    @Test
    fun localDirectory_filtersOutsideAndNonRecursiveNestedEvents() = runTest {
        val root = tmp.newFolder("watched").canonicalFile
        val direct = File(root, "notes.md")
        val nested = File(root, "sub/hidden.md")
        stubHub(root, recursive = false, batch(direct to ChangeKind.MODIFIED, nested to ChangeKind.MODIFIED, tmp.root to ChangeKind.MODIFIED))

        assertEquals(
            listOf(batch(direct to ChangeKind.MODIFIED)),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Local(root), false).toList()
        )
    }

    @Test
    fun localDirectory_dropsSymlinkEventsOutsideScope() = runTest {
        val root = tmp.newFolder("watched").canonicalFile
        val outside = tmp.newFolder("outside")
        val link = File(root, "alias")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        stubHub(root, recursive = true, batch(File(link, "secret") to ChangeKind.MODIFIED, truncated = true))

        assertEquals(
            listOf(FileChangeBatch(emptyList(), truncated = true)),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Local(root), true).toList()
        )
    }

    @Test
    fun localDirectoryAndFile_preserveTruncatedSignalWithoutExposingFilteredPaths() = runTest {
        val root = tmp.newFolder("watched").canonicalFile
        val file = File(root, "notes.md").apply { writeText("notes") }
        stubHub(root, recursive = true, FileChangeBatch(emptyList(), truncated = true))
        assertEquals(
            listOf(FileChangeBatch(emptyList(), truncated = true)),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Local(root), true).toList()
        )
        stubHub(root, recursive = false, batch(File(root, "other.md") to ChangeKind.MODIFIED, truncated = true))
        assertEquals(
            listOf(FileChangeBatch(emptyList(), truncated = true)),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Local(file), false).toList()
        )
    }

    @Test
    fun remote_delegatesToHub() = runTest {
        val change = FileChangeBatch(
            listOf(FileChange(ChangeRoot.OTHER, ROOT, ROOT, ChangeKind.MODIFIED))
        )
        every { hub.watchRemoteDir(ROOT, false) } returns flowOf(change)

        assertEquals(
            listOf(change),
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).toList()
        )
        verify(exactly = 1) { hub.watchRemoteDir(ROOT, false) }
    }

    @Test
    fun remote_limitErrorMapsToHostError() = runTest {
        every { hub.watchRemoteDir(ROOT, true) } returns flow {
            throw RemoteWatchException("TOO_LARGE", "too large")
        }

        val error = runCatching {
            source(StandardTestDispatcher(testScheduler))
                .watch(PluginWatchTarget.Remote(ROOT), true)
                .toList()
        }.exceptionOrNull()

        assertEquals("TOO_LARGE", (error as PluginHostException).code)
    }

    private companion object {
        const val ROOT = "~/workspace/notes"
    }
}
