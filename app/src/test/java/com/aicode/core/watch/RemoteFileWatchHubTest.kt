package com.aicode.core.watch

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFileWatchHubTest {

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

    private fun hub(dispatcher: CoroutineDispatcher) = RemoteFileWatchHub(fileAccess, dispatcher, 2000L)

    @Test
    fun firstSuccessfulScanCalibratesAfterInitialConnectionFailure() = runTest {
        remote[ROOT] = Stamp(directory = true)
        offline = true
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).toList(batches)
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
    fun pollingStartsOnlyOnCollectAndReportsCreateModifyDelete() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/changed.md"] = Stamp()
        remote["$ROOT/deleted.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val flow = hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false)
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
    fun recursiveScopeFindsNestedChangesAndNonRecursiveScopeDoesNotTraverse() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/sub"] = Stamp(directory = true)
        remote["$ROOT/sub/notes.md"] = Stamp()
        val shallow = mutableListOf<FileChangeBatch>()
        val recursive = mutableListOf<FileChangeBatch>()
        val watcher = hub(StandardTestDispatcher(testScheduler))
        val shallowJob = backgroundScope.launch { watcher.watch(ROOT, false).filter { it.changes.isNotEmpty() }.toList(shallow) }
        val recursiveJob = backgroundScope.launch { watcher.watch(ROOT, true).filter { it.changes.isNotEmpty() }.toList(recursive) }
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
    fun disconnectionKeepsBaselineAndComparesAfterRecovery() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/old.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun partialRecursiveScanFailureDoesNotReplaceBaseline() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/sub"] = Stamp(directory = true)
        remote["$ROOT/sub/notes.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, true).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun rootAppearanceAndDisappearanceAreReported() = runTest {
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun fileMetadataModificationAndDeletionAreReported() = runTest {
        remote[ROOT] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, true).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun cancelStopsPolling() = runTest {
        remote[ROOT] = Stamp(directory = true)
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).collect()
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 1) { fileAccess.exists(ROOT) }
    }

    @Test
    fun cancellationExceptionIsNotRetried() = runTest {
        every { fileAccess.exists(ROOT) } throws CancellationException("cancelled")
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).collect()
        }
        runCurrent()
        assertTrue(job.isCancelled)
        advanceTimeBy(6000)
        runCurrent()
        verify(exactly = 1) { fileAccess.exists(ROOT) }
    }

    @Test
    fun metadataOnlyWatchDoesNotReadFileContents() = runTest {
        remote[ROOT] = Stamp(directory = true)
        remote["$ROOT/notes.md"] = Stamp()
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun invalidChildNamesCannotEscapeScope() = runTest {
        every { fileAccess.listFiles(ROOT) } returns listOf(
            FileEntry("../secret", false, 1, 1000),
            FileEntry(".", true, 0, 1000),
            FileEntry("bad\\path", true, 0, 1000)
        )
        remote[ROOT] = Stamp(directory = true)
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, true).filter { it.changes.isNotEmpty() }.toList(batches)
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
    fun recursiveDepthIsBounded() = runTest {
        remote[ROOT] = Stamp(directory = true)
        var path = ROOT
        repeat(12) {
            path += "/nested"
            remote[path] = Stamp(directory = true)
        }
        var failure: Throwable? = null
        hub(StandardTestDispatcher(testScheduler)).watch(ROOT, true)
            .catch { failure = it }.collect()

        assertEquals("TOO_LARGE", (failure as RemoteWatchException).code)
        verify(exactly = 8) { fileAccess.listFiles(any()) }
    }

    @Test
    fun entryCountIsBounded() = runTest {
        remote[ROOT] = Stamp(directory = true)
        repeat(5000) { remote["$ROOT/$it.md"] = Stamp() }
        var failure: Throwable? = null
        hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false)
            .catch { failure = it }.collect()

        assertEquals("TOO_LARGE", (failure as RemoteWatchException).code)
    }

    @Test
    fun largeBatchIsExplicitlyTruncated() = runTest {
        remote[ROOT] = Stamp(directory = true)
        val batches = mutableListOf<FileChangeBatch>()
        val job = backgroundScope.launch {
            hub(StandardTestDispatcher(testScheduler)).watch(ROOT, false).filter { it.changes.isNotEmpty() }.toList(batches)
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
