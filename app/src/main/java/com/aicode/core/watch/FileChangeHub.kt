package com.aicode.core.watch

import android.os.FileObserver
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App 级文件变更监听中心：监听当前活动工作区与 `~/.aicode` 两个根（也可订阅任意宿主目录），
 * 变更合并成批后广播；订阅方按需声明范围与过滤规则，没人订阅的目录不持有 inotify 句柄、不轮询。
 *
 * 事件来源两层：
 * - 每目录单层 inotify（订阅声明递归时随新目录自动补挂）；
 * - 订阅级快照轮询兜底（按订阅范围递归比对 mtime/size），覆盖 PRoot 绑定目录与外部 FUSE 目录上
 *   inotify 失效的机型，也能发现「整棵目录被移动/替换」这类 inotify 事件不完整的变更。
 *
 * 过滤按订阅方生效：递归剪枝与事件投递都用订阅自己的 [WatchFilter]，同一目录可以对一个订阅可见、
 * 对另一个订阅被忽略。剪枝只看父路径段，故被剪枝目录自身的增删仍会上报（父目录列表才看得到它）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FileChangeHub @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val pathMapper: WorkspacePathMapper,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteFileWatchHub: RemoteFileWatchHub
) {
    companion object {
        private const val TAG = "FileChangeHub"

        /** 批量窗口默认长度：窗口内同路径变更合并为一条，窗口到期必发。 */
        const val DEFAULT_BATCH_WINDOW_MS = 300

        /** 单批明细上限，超出即截断（只保留「有变更」信号）。 */
        private const val MAX_BATCH_SIZE = 512

        /** 快照轮询兜底间隔。 */
        private const val POLL_INTERVAL_MS = 2000L

        /** 订阅根尚不存在时的探测间隔。 */
        private const val ROOT_WAIT_INTERVAL_MS = 2000L

        /** 单次快照最多收集的条目数，防止超大订阅每轮遍历开销失控。 */
        private const val MAX_SNAPSHOT_ENTRIES = 4096

        /** 单订阅可持有 inotify 句柄的目录数上限，防止大仓库把 inotify watch 用尽。 */
        private const val MAX_WATCHED_DIRS = 512

        /** 待处理原始事件队列上限；超出时丢弃最旧事件（有快照轮询兜底），防止文件暴增时内存无界增长。 */
        private const val MAX_PENDING_EVENTS = 4096

        /** 待广播批次队列上限；超出时丢弃最旧批次。 */
        private const val MAX_PENDING_BATCHES = 256

        /** AI 看到的工作区根路径。 */
        const val CONTAINER_ROOT = WorkspacePathMapper.CONTAINER_ROOT

        /** AI 配置目录在容器内的根路径。 */
        const val AICODE_ROOT = WorkspacePathMapper.AICODE_ROOT

        private const val IN_IGNORED = 0x00008000

        /**
         * 目录监听链路的逐目录 / 逐事件诊断打点。默认关闭：一次启动就会为每个被监听的目录
         * 打若干条，排查挂载竞态或 inotify 失效时再打开。
         */
        private const val DIAG = false

        private fun diag(message: String) {
            if (DIAG) FileLogger.d(TAG, "[diag] $message")
        }

        private val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** FileObserver 必须绑定有 Looper 的线程创建与 startWatching，故观察器起停统一走主线程。 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 宿主目录路径 → 该目录的观察器（多个订阅共享同一个）。 */
    private val dirWatches = ConcurrentHashMap<String, DirWatch>()

    /** 两个根都订阅，递归。 */
    fun watchAll(
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = merge(
        watchWorkspace(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        ),
        watchAicode(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    )

    /**
     * 订阅当前工作区（跟随工作区切换自动重建）。[containerSubPath] 是容器路径，默认整个工作区根。
     * 远程模式下工作区在服务器上、宿主没有对应目录，订阅退化为空流。
     */
    fun watchWorkspace(
        containerSubPath: String = CONTAINER_ROOT,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = workspaceRepository.current
        .map { workspaceRepository.currentPath() }
        .distinctUntilChanged()
        .flatMapLatest {
            if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
                emptyFlow()
            } else {
                watchHostDir(
                    hostDir = pathMapper.toHostFile(containerSubPath),
                    containerRootPath = containerSubPath.trimEnd('/'),
                    root = ChangeRoot.WORKSPACE,
                    recursive = recursive,
                    filter = filter,
                    fallbackPoll = fallbackPoll,
                    batchWindowMs = batchWindowMs
                )
            }
        }

    /** 订阅 AI 配置目录（`~/.aicode`）下的子路径，[containerSubPath] 为空表示该目录本身。 */
    fun watchAicode(
        containerSubPath: String = "",
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> {
        val sub = containerSubPath.trim('/')
        return watchHostDir(
            hostDir = if (sub.isEmpty()) containerInstaller.aicodeDir else File(containerInstaller.aicodeDir, sub),
            containerRootPath = if (sub.isEmpty()) AICODE_ROOT else "$AICODE_ROOT/$sub",
            root = ChangeRoot.AICODE,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    }

    /**
     * 订阅任意远端目录。远端没有宿主 [File]，由 [RemoteFileWatchHub] 按根共享轮询；
     * 返回值只含变化批次，调用方按自己的根过滤路径。
     */
    fun watchRemoteDir(rootPath: String, recursive: Boolean = false): Flow<FileChangeBatch> =
        remoteFileWatchHub.watch(rootPath, recursive)

    /**
     * 订阅任意宿主目录（如同步引擎的本地镜像目录）。[containerRootPath] 为 null 时容器路径由
     * [WorkspacePathMapper.toContainerPath] 反推。目录尚不存在时不放弃订阅——等它出现后再挂 watch，
     * 并把「目录出现」当作一次变更上报（项目级 `.aicode/skills` 这类目录经常是后建的）。
     */
    fun watchHostDir(
        hostDir: File,
        containerRootPath: String? = null,
        root: ChangeRoot = ChangeRoot.OTHER,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = flow {
        val subscription = Subscription(
            rootDir = hostDir,
            containerRootPath = containerRootPath,
            rootKind = root,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            windowMs = batchWindowMs
        )
        try {
            subscription.start()
            emitAll(subscription.batches)
        } finally {
            subscription.stop()
        }
    }

    private data class RawEvent(val hostPath: String, val kind: ChangeKind)

    /** 一个订阅：持有自己注册的目录集合、过滤规则、批量窗口与快照轮询。 */
    private inner class Subscription(
        private val rootDir: File,
        private val containerRootPath: String?,
        private val rootKind: ChangeRoot,
        private val recursive: Boolean,
        private val filter: WatchFilter,
        private val fallbackPoll: Boolean,
        private val windowMs: Int
    ) {
        private val events = Channel<RawEvent>(
            capacity = MAX_PENDING_EVENTS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        private val batchesChannel = Channel<FileChangeBatch>(
            capacity = MAX_PENDING_BATCHES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val batches: Flow<FileChangeBatch> = batchesChannel.receiveAsFlow()

        private val myDirs = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var rules: IgnoreRules = IgnoreRules.of(filter, emptyList())
        private var mergeJob: Job? = null
        private var pollJob: Job? = null
        private var attachJob: Job? = null

        fun start() {
            rules = IgnoreRules.of(
                filter,
                if (filter.followGitignore) readGitignorePatterns(rootDir) else emptyList()
            )
            mergeJob = ioScope.launch { mergeLoop() }
            if (rootDir.isDirectory) {
                attachTree()
            } else {
                awaitRoot()
            }
        }

        fun stop() {
            mergeJob?.cancel()
            mergeJob = null
            pollJob?.cancel()
            pollJob = null
            attachJob?.cancel()
            attachJob = null
            for (path in myDirs) dirWatches[path]?.unsubscribe(this)
            myDirs.clear()
            batchesChannel.close()
        }

        private fun attachTree() {
            registerTree(rootDir, reportNew = false)
            startPolling()
        }

        /** 订阅根暂不存在：等它出现再挂 watch，出现本身算一次变更。 */
        private fun awaitRoot() {
            attachJob = ioScope.launch {
                while (isActive && !rootDir.isDirectory) delay(ROOT_WAIT_INTERVAL_MS)
                if (!isActive) return@launch
                events.trySend(RawEvent(rootDir.absolutePath, ChangeKind.CREATED))
                attachTree()
            }
        }

        /** 递归注册目录；剪枝只针对父路径段，故先判后注册。[reportNew] 为真时对新挂上的目录补报其存量直接子项。 */
        private fun registerTree(dir: File, reportNew: Boolean) {
            val parts = relativePartsOf(rootDir, dir)
            if (parts.isNotEmpty() && rules.isIgnoredDir(parts)) return
            register(dir, reportNew)
            if (!recursive) return
            dir.listFiles()?.forEach { child -> if (child.isDirectory) registerTree(child, reportNew) }
        }

        private fun register(dir: File, reportNew: Boolean) {
            val path = dir.absolutePath
            if (!myDirs.add(path)) {
                diag("目录监听已登记，跳过重复注册: $path")
                return
            }
            if (dirWatches.size >= MAX_WATCHED_DIRS && !dirWatches.containsKey(path)) {
                myDirs.remove(path)
                FileLogger.w(TAG, "监听目录数达上限 $MAX_WATCHED_DIRS，停止扩展: $path")
                return
            }
            // 新出现的目录在 watch 生效前，其内条目没有任何 watcher 在场、inotify 不会汇报。
            // 补报该目录的存量直接子项来覆盖这段空洞；之后的新增仍由 inotify 汇报。初始注册不补报，
            // 否则订阅建立时会把整棵工作区当作新增全量上报。
            val onReady: (() -> Unit)? = if (reportNew) {
                { ioScope.launch { reportExistingChildren(dir) } }
            } else null
            diag("注册目录监听: $path reportNew=$reportNew")
            dirWatches.computeIfAbsent(path) { DirWatch(dir) }.subscribe(this, onReady)
        }

        /** 挂载监听后补扫子项，覆盖首次扫描与 startWatching 之间新建的目录。 */
        private fun reportExistingChildren(dir: File) {
            val children = dir.listFiles() ?: return
            diag("补报新目录子项: ${dir.absolutePath} 数量=${children.size}")
            for (child in children) {
                events.trySend(RawEvent(child.absolutePath, ChangeKind.CREATED))
                if (recursive && child.isDirectory) registerTree(child, reportNew = true)
            }
        }

        fun onWatchInvalidated(path: String) {
            if (!myDirs.remove(path)) return
            FileLogger.w(TAG, "目录监听失效，准备重新挂载: $path")
            val dir = File(path)
            if (dir.isDirectory) {
                registerTree(dir, reportNew = true)
            } else if (path == rootDir.absolutePath) {
                awaitRoot()
            }
        }

        fun onEvent(watch: DirWatch, hostPath: String, kind: ChangeKind) {
            events.trySend(RawEvent(hostPath, kind))
            // 递归订阅：新出现的目录要补挂（回调在主线程，文件系统检查切到 IO）。
            if (recursive && kind == ChangeKind.CREATED) {
                ioScope.launch {
                    val child = File(hostPath)
                    if (child.isDirectory) registerTree(child, reportNew = true)
                }
            }
        }

        /**
         * 快照轮询兜底：按订阅范围递归比对（非递归订阅只比根目录单层）。
         * 递归快照能发现 inotify 漏报的深层改动与「整棵子树被移动/替换」。
         */
        private fun startPolling() {
            if (!fallbackPoll || pollJob != null) return
            pollJob = ioScope.launch {
                var last = snapshotOfScope()
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val now = snapshotOfScope()
                    val diff = diffSnapshot(last, now)
                    last = now
                    for ((rel, kind) in diff) {
                        val hostPath =
                            if (rel.isEmpty()) rootDir.absolutePath else File(rootDir, rel).absolutePath
                        events.trySend(RawEvent(hostPath, kind))
                        if (recursive && kind == ChangeKind.CREATED) {
                            val child = File(hostPath)
                            if (child.isDirectory) registerTree(child, reportNew = true)
                        }
                    }
                }
            }
        }

        /** 订阅范围快照：键为相对订阅根的路径（根自身为空串）；剪枝目录不进入。 */
        private fun snapshotOfScope(): Map<String, String> {
            val out = HashMap<String, String>()
            if (!rootDir.isDirectory) return out
            var count = 0

            fun walk(dir: File, rel: String) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (count >= MAX_SNAPSHOT_ENTRIES) return
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    count++
                    if (child.isDirectory) {
                        out[childRel] = "d"
                        if (recursive && !rules.isIgnoredDir(relativePartsOf(rootDir, child))) {
                            walk(child, childRel)
                        }
                    } else {
                        out[childRel] = "f:${child.lastModified()}:${child.length()}"
                    }
                }
            }

            walk(rootDir, "")
            return out
        }

        private fun containerPathOf(hostPath: String): String {
            val base = containerRootPath ?: return pathMapper.toContainerPath(hostPath)
            val rel = relativePartsOf(rootDir, File(hostPath)).joinToString("/")
            return if (rel.isEmpty()) base else "$base/$rel"
        }

        private suspend fun mergeLoop() {
            while (true) {
                val first = events.receive()
                val merged = LinkedHashMap<String, ChangeKind>()
                var truncated = false

                fun accept(event: RawEvent) {
                    val existing = merged[event.hostPath]
                    if (existing == null) {
                        if (merged.size >= MAX_BATCH_SIZE) {
                            truncated = true
                            return
                        }
                        merged[event.hostPath] = event.kind
                    } else {
                        merged[event.hostPath] = mergeKind(existing, event.kind)
                    }
                }

                accept(first)
                // 固定窗口：到期必发。静默 debounce 会在编译这类持续变更期间一直不发出，把刷新吞掉。
                val deadline = System.currentTimeMillis() + windowMs
                while (true) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    val next = withTimeoutOrNull(remain) { events.receive() } ?: break
                    accept(next)
                }

                val changes = merged.map { (path, kind) ->
                    FileChange(rootKind, path, containerPathOf(path), kind)
                }
                batchesChannel.send(FileChangeBatch(changes, truncated))
            }
        }
    }

    /** 一个宿主目录的观察器，按订阅引用计数启停（多个订阅共享同一个）。 */
    private inner class DirWatch(private val dir: File) {
        private val path = dir.absolutePath
        private val subscribers: MutableSet<Subscription> =
            Collections.newSetFromMap(ConcurrentHashMap<Subscription, Boolean>())
        private val lock = Any()

        private var observer: FileObserver? = null

        fun subscribe(sub: Subscription, onReady: (() -> Unit)? = null) {
            val firstSubscriber = synchronized(lock) {
                val first = subscribers.isEmpty()
                subscribers.add(sub)
                first
            }
            // watch 已生效时立即回调；否则排到主线程 startObserver 之后，保证 onReady 晚于 startWatching。
            if (firstSubscriber) startObserver(onReady) else mainScope.launch { onReady?.invoke() }
        }

        fun unsubscribe(sub: Subscription) {
            val empty = synchronized(lock) {
                subscribers.remove(sub)
                subscribers.isEmpty()
            }
            if (empty) {
                stopObserver()
                dirWatches.remove(path, this)
            }
        }

        private fun startObserver(onReady: (() -> Unit)? = null) {
            mainScope.launch {
                if (observer != null) {
                    onReady?.invoke()
                    return@launch
                }
                @Suppress("DEPRECATION")
                val created = object : FileObserver(path, MASK) {
                    override fun onEvent(event: Int, child: String?) {
                        diag("FileObserver.onEvent path=$path event=0x${Integer.toHexString(event)} child=$child")
                        if (event and IN_IGNORED != 0) {
                            invalidate(this)
                            return
                        }
                        val name = child ?: return
                        val kind = kindOf(event) ?: return
                        dispatch(name, kind)
                    }
                }
                created.startWatching()
                observer = created
                diag("startWatching path=$path")
                // 必须在 startWatching 之后：此刻起 inotify 能捕获新增，补报只负责更早的存量，二者无缝衔接。
                onReady?.invoke()
            }
        }

        private fun invalidate(current: FileObserver) {
            if (observer !== current) return
            observer = null
            dirWatches.remove(path, this)
            val affected = synchronized(lock) {
                subscribers.toList().also { subscribers.clear() }
            }
            FileLogger.w(TAG, "目录观察器被系统移除: $path，订阅数=${affected.size}")
            for (sub in affected) sub.onWatchInvalidated(path)
        }

        private fun stopObserver() {
            val current = observer ?: return
            observer = null
            mainScope.launch { runCatching { current.stopWatching() } }
        }

        private fun dispatch(name: String, kind: ChangeKind) {
            val hostPath = File(dir, name).absolutePath
            diag("dispatch hostPath=$hostPath kind=$kind subscribers=${subscribers.size}")
            for (sub in subscribers) sub.onEvent(this, hostPath, kind)
        }

        private fun kindOf(event: Int): ChangeKind? = when {
            event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 -> ChangeKind.CREATED
            event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> ChangeKind.DELETED
            event and FileObserver.CLOSE_WRITE != 0 -> ChangeKind.MODIFIED
            else -> null
        }
    }
}