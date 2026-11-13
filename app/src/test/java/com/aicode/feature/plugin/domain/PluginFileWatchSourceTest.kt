package com.aicode.feature.plugin.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.WatchFilter
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class PluginFileWatchSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hub = mockk<FileChangeHub>()
    private val fileAccess = mockk<FileAccessProvider>()
    private val remote = linkedMapOf<String, Stamp>()
    private var offline = false
    private var failedDirectory: String? = null

    private data class Stamp(val directory: Boolean = false, val size: Long = 1, val modified: Long = 1000)

    @Before
    fun setUp() {
        every { fileAccess.exists(any()) } answers {
            if (offline) throw IOException("disconnected")
            remote.containsKey(firstArg())
        }
        every { fileAccess.isDirectory(any()) } answers { remote[firstArg()]?.directory == true }
        every { fileAccess.fileSize(any()) } answers { remote[firstArg()]?.size ?: 0L }
        every { fileAccess.lastModified(any()) } answers { remote[firstArg()]?.modified ?: 0L }
        every { fileAccess.listFiles(any()) } answers {
            val directory = firstArg<String>()
            if (directory == failedDirectory) throw IOException("scan interrupted")
            remote.mapNotNull { (path, stamp) ->
                if (path.substringBeforeLast('/', "") != directory) null
                else FileEntry(path.substringAfterLast('/'), stamp.directory, stamp.size, stamp.modified)
            }
        }
    }

    private fun source(dispatcher: CoroutineDispatcher) = PluginFileWatchSource(hub, fileAccess, dispatcher)

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
        verify(exactly = 0) { fileAccess.exists(any()) }
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
    fun remote_firstSuccessfulScanCalibratesAfterInitialConnectionFailure() = runTest {
        remote[ROOT] = Stamp(directory = true)
        offline = true
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).toList(batches)
        }
        runCurrent()
        assertTrue(batches.isEmpty())
        offline = false
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(FileChangeBatch(emptyList())), batches)
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(1, batches.size)
        job.cancelAndJoin()
    }

    @Test
    fun remote_pollingStartsOnlyOnCollectAndReportsCreateModifyDelete() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/changed.md"] = Stamp()
        remote["$ROOT/deleted.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val flow = source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false)
        verify(exactly = 0) { fileAccess.exists(any()) }
        val job = backgroundScope.launch { flow.filter { it.changes.isNotEmpty() }.toList(batches) }
        runCurrent()
        assertTrue(batches.isEmpty())

        remote["$ROOT/changed.md"] = Stamp(modified = 2000)
        remote.remove("$ROOT/deleted.md")
        remote["$ROOT/created.md"] = Stamp()
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(
            mapOf("$ROOT/changed.md" to ChangeKind.MODIFIED, "$ROOT/deleted.md" to ChangeKind.DELETED, "$ROOT/created.md" to ChangeKind.CREATED),
            batches.single().changes.associate { it.containerPath to it.kind }
        )
        assertTrue(batches.single().changes.all { it.hostPath == it.containerPath && it.root == ChangeRoot.OTHER })
        job.cancelAndJoin()
    }

    @Test
    fun remote_recursiveScopeFindsNestedChangesAndNonRecursiveScopeDoesNotTraverse() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/sub"] = Stamp(directory = true)
        remote["$ROOT/sub/notes.md"] = Stamp()
        val shallow = mutableListOf<FileChangeBatch>()
        val recursive = mutableListOf<FileChangeBatch>()
        val watcher = source(StandardTestDispatcher(testScheduler))
        val shallowJob = backgroundScope.launch { watcher.watch(PluginWatchTarget.Remote(ROOT), false).filter { it.changes.isNotEmpty() }.toList(shallow) }
        val recursiveJob = backgroundScope.launch { watcher.watch(PluginWatchTarget.Remote(ROOT), true).filter { it.changes.isNotEmpty() }.toList(recursive) }
        runCurrent()

        remote["$ROOT/sub/notes.md"] = Stamp(size = 9)
        advanceTimeBy(2000)
        runCurrent()

        assertTrue(shallow.isEmpty())
        assertEquals("$ROOT/sub/notes.md", recursive.single().changes.single().containerPath)
        assertEquals(ChangeKind.MODIFIED, recursive.single().changes.single().kind)
        shallowJob.cancelAndJoin()
        recursiveJob.cancelAndJoin()
    }

    @Test
    fun remote_disconnectionKeepsBaselineAndComparesAfterRecovery() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/old.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        offline = true
        advanceTimeBy(4000)
        runCurrent()
        assertTrue(batches.isEmpty())

        remote.remove("$ROOT/old.md")
        remote["$ROOT/new.md"] = Stamp()
        offline = false
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(
            mapOf("$ROOT/old.md" to ChangeKind.DELETED, "$ROOT/new.md" to ChangeKind.CREATED),
            batches.single().changes.associate { it.containerPath to it.kind }
        )
        job.cancelAndJoin()
    }

    @Test
    fun remote_partialRecursiveScanFailureDoesNotReplaceBaseline() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/sub"] = Stamp(directory = true)
        remote["$ROOT/sub/notes.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), true).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        failedDirectory = "$ROOT/sub"
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(batches.isEmpty())
        failedDirectory = null
        remote["$ROOT/sub/notes.md"] = Stamp(modified = 2000)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(ChangeKind.MODIFIED, batches.single().changes.single().kind)
        job.cancelAndJoin()
    }

    @Test
    fun remote_rootAppearanceAndDisappearanceAreReported() = runTest {
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        remote[ROOT] = Stamp(directory = true)
        advanceTimeBy(2000)
        runCurrent()
        remote.clear()
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(listOf(ChangeKind.CREATED, ChangeKind.DELETED), batches.map { it.changes.single().kind })
        assertTrue(batches.all { it.changes.single().containerPath == ROOT })
        job.cancelAndJoin()
    }

    @Test
    fun remote_fileMetadataModificationAndDeletionAreReported() = runTest {
        remote[ROOT] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), true).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        remote[ROOT] = Stamp(size = 5)
        advanceTimeBy(2000)
        runCurrent()
        remote.clear()
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(listOf(ChangeKind.MODIFIED, ChangeKind.DELETED), batches.map { it.changes.single().kind })
        verify(exactly = 0) { fileAccess.listFiles(any()) }
        job.cancelAndJoin()
    }

    @Test
    fun remote_cancelStopsPolling() = runTest {
        remote[ROOT] = Stamp(directory = true)
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).collect()
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 1) { fileAccess.exists(ROOT) }
    }

    @Test
    fun remote_cancellationExceptionIsNotRetried() = runTest {
        every { fileAccess.exists(ROOT) } throws CancellationException("cancelled")
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).collect()
        }
        runCurrent()
        assertTrue(job.isCancelled)
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 1) { fileAccess.exists(ROOT) }
    }

    @Test
    fun remote_metadataOnlyWatchDoesNotReadFileContents() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/notes.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        advanceTimeBy(4000)
        runCurrent()
        assertTrue(batches.isEmpty())
        verify(exactly = 0) { fileAccess.readFile(any()) }
        verify(exactly = 0) { fileAccess.fingerprint(any()) }
        job.cancelAndJoin()
    }

    @Test
    fun remote_invalidChildNamesCannotEscapeScope() = runTest {
        every { fileAccess.listFiles(ROOT) } returns listOf(
            FileEntry("../secret", false, 1, 1000),
            FileEntry(".", true, 0, 1000),
            FileEntry("bad\\path", true, 0, 1000)
        )
        remote[ROOT] = Stamp(directory = true)
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), true).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(batches.isEmpty())
        verify(exactly = 2) { fileAccess.listFiles(ROOT) }
        verify(exactly = 0) { fileAccess.listFiles(match { it != ROOT }) }
        job.cancelAndJoin()
    }

    @Test
    fun remote_recursiveDepthIsBounded() = runTest {
        remote[ROOT] = Stamp(directory = true)
        var path = ROOT
        repeat(12) {
            path += "/nested"
            remote[path] = Stamp(directory = true)
        }
        var failure: Throwable? = null
        source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), true)
            .catch { failure = it }.collect()

        assertEquals("TOO_LARGE", (failure as PluginHostException).code)
        verify(exactly = 8) { fileAccess.listFiles(any()) }
    }

    @Test
    fun remote_entryCountIsBounded() = runTest {
        remote[ROOT] = Stamp(directory = true)
        repeat(5000) { remote["$ROOT/$it.md"] = Stamp() }
        var failure: Throwable? = null
        source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false)
            .catch { failure = it }.collect()

        assertEquals("TOO_LARGE", (failure as PluginHostException).code)
    }

    @Test
    fun remote_largeBatchIsExplicitlyTruncated() = runTest {
        remote[ROOT] = Stamp(directory = true)
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            source(StandardTestDispatcher(testScheduler)).watch(PluginWatchTarget.Remote(ROOT), false).filter { it.changes.isNotEmpty() }.toList(batches)
        }
        runCurrent()
        repeat(600) { remote["$ROOT/$it.md"] = Stamp() }
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(512, batches.single().changes.size)
        assertTrue(batches.single().truncated)
        job.cancelAndJoin()
    }

    private companion object {
        const val ROOT = "~/workspace/notes"
    }
}
