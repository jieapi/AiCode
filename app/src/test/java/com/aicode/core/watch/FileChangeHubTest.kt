package com.aicode.core.watch

import android.os.FileObserver
import com.aicode.feature.agent.domain.container.BuiltinLocalProotBackend
import com.aicode.feature.agent.domain.container.BuiltinRemoteSshBackend
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    application = android.app.Application::class,
    shadows = [FileChangeHubTest.RecordingFileObserver::class]
)
@OptIn(ExperimentalCoroutinesApi::class)
class FileChangeHubTest {

    private val workspaceRepository = mockk<WorkspaceRepository>()
    private val containerInstaller = mockk<ContainerInstaller>()
    private val pathMapper = mockk<WorkspacePathMapper>()
    private val executionModeHolder = mockk<ExecutionModeHolder>()
    private val remoteFileWatchHub = mockk<RemoteFileWatchHub>()
    private lateinit var mainDispatcher: TestDispatcher

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        RecordingFileObserver.reset()
    }

    @After
    fun tearDown() {
        RecordingFileObserver.reset()
        Dispatchers.resetMain()
    }

    private fun hub(
        mode: ExecutionMode = ExecutionMode.REMOTE_SSH,
        workspace: String = WORKSPACE,
        ioDispatcher: CoroutineDispatcher = mainDispatcher,
        observerDispatcher: CoroutineDispatcher = mainDispatcher
    ): FileChangeHub {
        every { executionModeHolder.currentMode() } returns mode
        every { executionModeHolder.capabilities() } returns when (mode) {
            ExecutionMode.LOCAL_PROOT -> BuiltinLocalProotBackend.CAPABILITIES
            ExecutionMode.REMOTE_SSH -> BuiltinRemoteSshBackend.CAPABILITIES
        }
        every { workspaceRepository.currentPath() } returns workspace
        return FileChangeHub(
            workspaceRepository,
            containerInstaller,
            pathMapper,
            executionModeHolder,
            remoteFileWatchHub,
            ioDispatcher,
            observerDispatcher
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

    @Test
    fun stopBeforeQueuedObserverStart_doesNotStartOrPoll() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val root = directory("/watch/root")
        val fileChangeHub = hub(observerDispatcher = StandardTestDispatcher(observerScheduler))
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(root, filter = LOCAL_FILTER).collect()
        }

        collector.cancelAndJoin()
        observerScheduler.runCurrent()
        advanceTimeBy(6000)
        runCurrent()

        assertTrue(RecordingFileObserver.started.isEmpty())
        assertTrue(RecordingFileObserver.active.isEmpty())
        verify(exactly = 0) { root.listFiles() }
    }

    @Test
    fun cancelledMissingRoot_doesNotAttachWhenDirectoryAppears() = runTest {
        val root = directory("/watch/later")
        every { root.isDirectory } returns false
        val fileChangeHub = hub()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(root, recursive = true, filter = LOCAL_FILTER).collect()
        }
        runCurrent()

        collector.cancelAndJoin()
        every { root.isDirectory } returns true
        advanceTimeBy(6000)
        runCurrent()

        assertTrue(RecordingFileObserver.started.isEmpty())
        verify(exactly = 0) { root.listFiles() }
    }

    @Test
    fun rootInvalidatedWhileInitialAttachmentRuns_canWaitForItToAppearAgain() = runTest {
        val realRoot = File(temporaryFolder.root, "returning-root")
        val root = directory(realRoot.absolutePath)
        every { root.isDirectory } answers { realRoot.isDirectory }
        val fileChangeHub = hub(observerDispatcher = UnconfinedTestDispatcher(testScheduler))
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(
                root,
                containerRootPath = "/container",
                recursive = true,
                filter = LOCAL_FILTER,
                fallbackPoll = false,
                batchWindowMs = 0
            ).collect()
        }
        runCurrent()
        assertTrue(realRoot.mkdir())
        RecordingFileObserver.beforeStart = { path ->
            if (path == realRoot.absolutePath && RecordingFileObserver.started.size == 1) {
                assertTrue(realRoot.delete())
                RecordingFileObserver.active.getValue(path).onEvent(IN_IGNORED, null)
                testScheduler.runCurrent()
            }
        }

        advanceTimeBy(2000)
        runCurrent()
        assertEquals(1, RecordingFileObserver.started.size)
        assertTrue(realRoot.mkdir())
        advanceTimeBy(2000)
        runCurrent()

        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.active.size)
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun cancellationDuringInitialTraversal_stopsBeforeVisitingChildren() = runTest {
        val child = directory("/watch/root/child")
        val root = directory("/watch/root", child)
        val fileChangeHub = hub()
        val collector = backgroundScope.launch(start = CoroutineStart.LAZY) {
            fileChangeHub.watchHostDir(root, recursive = true, filter = LOCAL_FILTER).collect()
        }
        every { root.listFiles() } answers {
            collector.cancel()
            arrayOf(child)
        }

        collector.start()
        runCurrent()
        collector.join()
        advanceTimeBy(6000)
        runCurrent()

        verify(exactly = 0) { child.isDirectory }
        assertTrue(RecordingFileObserver.started.isEmpty())
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun stopCancelsAlreadyRunningPolling() = runTest {
        val root = directory("/watch/root")
        val fileChangeHub = hub()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(root, filter = LOCAL_FILTER).collect()
        }
        runCurrent()
        verify(exactly = 1) { root.listFiles() }

        collector.cancelAndJoin()
        advanceTimeBy(6000)
        runCurrent()

        verify(exactly = 1) { root.listFiles() }
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun queuedRecursiveAttach_isCancelledWithSubscription() = runTest {
        val realRoot = temporaryFolder.newFolder("root")
        val child = File(realRoot, "child")
        assertTrue(child.mkdir())
        val root = directory(realRoot.absolutePath)
        val fileChangeHub = hub()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(
                root,
                containerRootPath = "/container",
                recursive = true,
                filter = LOCAL_FILTER,
                fallbackPoll = false
            ).collect()
        }
        runCurrent()
        val observer = RecordingFileObserver.active.getValue(root.absolutePath)

        observer.onEvent(FileObserver.CREATE, "child")
        collector.cancelAndJoin()
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()

        assertEquals(listOf(root.absolutePath), RecordingFileObserver.started)
        assertEquals(listOf(root.absolutePath), RecordingFileObserver.stopped)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun queuedExistingChildrenReport_isCancelledBeforeScanning() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val realRoot = temporaryFolder.newFolder("root")
        val root = directory(realRoot.absolutePath)
        val fileChangeHub = hub(observerDispatcher = StandardTestDispatcher(observerScheduler))
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(
                root,
                containerRootPath = "/container",
                recursive = true,
                filter = LOCAL_FILTER,
                fallbackPoll = false
            ).collect()
        }
        runCurrent()
        observerScheduler.runCurrent()
        val observer = RecordingFileObserver.active.getValue(root.absolutePath)
        val child = File(realRoot, "child")
        RecordingFileObserver.beforeStart = {
            if (it == root.absolutePath) assertTrue(child.mkdir())
        }

        observer.onEvent(IN_IGNORED, null)
        runCurrent()
        observerScheduler.runCurrent()
        collector.cancelAndJoin()
        runCurrent()
        observerScheduler.runCurrent()

        assertEquals(listOf(root.absolutePath, root.absolutePath), RecordingFileObserver.started)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun sharedDirectory_oneCollectorLeavingKeepsObserverAndEvents() = runTest {
        val root = directory("/watch/shared")
        val fileChangeHub = hub()
        val flow = fileChangeHub.watchHostDir(
            root,
            containerRootPath = "/container",
            filter = LOCAL_FILTER,
            fallbackPoll = false,
            batchWindowMs = 0
        )
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }
        val received = mutableListOf<FileChangeBatch>()
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.toList(received)
        }
        runCurrent()
        val observer = RecordingFileObserver.active.getValue(root.absolutePath)

        first.cancelAndJoin()
        runCurrent()
        observer.onEvent(FileObserver.CLOSE_WRITE, "notes.md")
        runCurrent()

        assertEquals(listOf(root.absolutePath), RecordingFileObserver.started)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        assertEquals(
            listOf(
                FileChangeBatch(
                    listOf(
                        FileChange(
                            ChangeRoot.OTHER,
                            "/watch/shared/notes.md",
                            "/container/notes.md",
                            ChangeKind.MODIFIED
                        )
                    )
                )
            ),
            received
        )
        second.cancelAndJoin()
        runCurrent()
        assertEquals(listOf(root.absolutePath), RecordingFileObserver.stopped)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun replacementSubscription_ignoresQueuedStartOfOldWatch() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val root = directory("/watch/replaced")
        val fileChangeHub = hub(observerDispatcher = StandardTestDispatcher(observerScheduler))
        val flow = fileChangeHub.watchHostDir(root, filter = LOCAL_FILTER, fallbackPoll = false)
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }
        first.cancelAndJoin()
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }

        observerScheduler.runCurrent()

        assertEquals(listOf(root.absolutePath), RecordingFileObserver.started)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        second.cancelAndJoin()
        observerScheduler.runCurrent()
        assertEquals(listOf(root.absolutePath), RecordingFileObserver.stopped)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun queuedStopOfOldWatch_doesNotStopReplacementObserver() = runTest {
        val observerScheduler = TestCoroutineScheduler()
        val root = directory("/watch/replaced")
        val fileChangeHub = hub(observerDispatcher = StandardTestDispatcher(observerScheduler))
        val flow = fileChangeHub.watchHostDir(root, filter = LOCAL_FILTER, fallbackPoll = false)
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }
        observerScheduler.runCurrent()
        val oldObserver = RecordingFileObserver.active.getValue(root.absolutePath)
        first.cancelAndJoin()
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }

        observerScheduler.runCurrent()
        oldObserver.onEvent(IN_IGNORED, null)
        runCurrent()
        observerScheduler.runCurrent()

        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertEquals(1, RecordingFileObserver.active.size)
        second.cancelAndJoin()
        observerScheduler.runCurrent()
        assertEquals(2, RecordingFileObserver.stopped.size)
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun replacementStartBeforeQueuedStop_waitsForOldObserverToFinishStopping() = runTest {
        val dispatcher = QueuedObserverDispatcher()
        val root = directory("/watch/replaced")
        val fileChangeHub = hub(observerDispatcher = dispatcher)
        val flow = fileChangeHub.watchHostDir(root, filter = LOCAL_FILTER, fallbackPoll = false)
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }
        dispatcher.runNext()
        first.cancelAndJoin()
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect() }

        dispatcher.runLast()
        assertEquals(1, RecordingFileObserver.started.size)
        assertTrue(RecordingFileObserver.stopped.isEmpty())
        dispatcher.runNext()
        dispatcher.runNext()

        assertEquals(2, RecordingFileObserver.started.size)
        assertEquals(1, RecordingFileObserver.stopped.size)
        assertEquals(1, RecordingFileObserver.active.size)
        second.cancelAndJoin()
        dispatcher.runNext()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    @Test
    fun watchLimitStillTraversesToExistingSharedDirectory() = runTest {
        val shared = directory("/watch/root/overflow/shared")
        val overflow = directory("/watch/root/overflow", shared)
        val children = (0 until 510).map { directory("/watch/root/dir$it") } + overflow
        val root = directory("/watch/root", *children.toTypedArray())
        val fileChangeHub = hub()
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(shared, filter = LOCAL_FILTER, fallbackPoll = false).collect()
        }
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fileChangeHub.watchHostDir(
                root,
                recursive = true,
                filter = LOCAL_FILTER,
                fallbackPoll = false
            ).collect()
        }
        runCurrent()
        assertEquals(512, RecordingFileObserver.active.size)
        verify(exactly = 1) { overflow.listFiles() }
        first.cancelAndJoin()
        runCurrent()

        assertTrue(RecordingFileObserver.active.containsKey(shared.absolutePath))
        second.cancelAndJoin()
        runCurrent()
        assertTrue(RecordingFileObserver.active.isEmpty())
    }

    private fun directory(path: String, vararg children: File): File = mockk<File>().also { dir ->
        every { dir.absolutePath } returns path
        every { dir.path } returns path
        every { dir.isDirectory } returns true
        every { dir.listFiles() } returns arrayOf(*children)
    }

    private class QueuedObserverDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() = tasks.removeFirst().run()

        fun runLast() = tasks.removeLast().run()
    }

    @Implements(FileObserver::class)
    class RecordingFileObserver {
        @RealObject
        private lateinit var realObserver: FileObserver
        private lateinit var path: String

        @Implementation
        fun __constructor__(path: String, mask: Int) {
            this.path = path
        }

        @Implementation
        fun startWatching() {
            started += path
            active[path] = realObserver
            beforeStart?.invoke(path)
        }

        @Implementation
        fun stopWatching() {
            stopped += path
            active.remove(path)
        }

        companion object {
            val started = CopyOnWriteArrayList<String>()
            val stopped = CopyOnWriteArrayList<String>()
            val active = ConcurrentHashMap<String, FileObserver>()
            var beforeStart: ((String) -> Unit)? = null

            fun reset() {
                started.clear()
                stopped.clear()
                active.clear()
                beforeStart = null
            }
        }
    }

    private companion object {
        const val IN_IGNORED = 0x00008000
        val LOCAL_FILTER = WatchFilter(followGitignore = false)
        const val WORKSPACE = "/srv/workspace/myproj"
        const val SIBLING_WORKSPACE = "/srv/workspace/myproj-other"
    }
}
