package com.aicode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.terminal.presentation.component.AppTerminalSessionClient
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内常驻的终端会话池：所有 [TerminalSession] 的唯一所有者。
 *
 * 之所以放在 [Singleton] 而非 ViewModel：终端要「常驻后台」，离开终端页/切到聊天页都不能杀会话。
 * ViewModel 绑定在导航路由上、出栈即 onCleared，会连带 finishIfRunning 杀掉 proot——故把会话
 * 所有权上移到本管理器，ViewModel 退化为只读观察层。只要 App 进程还活着，会话就一直在跑。
 *
 * 每个标签有稳定且对 AI 友好的唯一 id（`term-N`）。AI 可凭 id：
 *  - [startBackgroundCommand] 把 `npm run dev` 之类挂后台并拿到 id；
 *  - [sendInput] 按 id 持续发命令；
 *  - [writeBytesToTab] 按 id 发送控制字符（如 Ctrl-C=0x03）；
 *  - [closeTab] 按 id 关闭并销毁会话；
 *  - [getTabOutput] 按 id 读终端内容（emulator 屏幕缓冲）。
 *
 * 所有可变状态读写都在主线程（UI 事件、AI 工作流派发到主线程的调用），不额外加锁。
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {
    private companion object {
        const val TAG = "TerminalSessionManager"
        const val TRANSCRIPT_ROWS = 2000
        // 无视图挂载时用于「就地启动」会话的默认终端尺寸；视图挂载后会按真实尺寸 resize。
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        /** 命令打印 `[command exited: N]` 后等待正常 onFinished 回调的缓冲（毫秒）。 */
        const val EXIT_MARKER_GRACE_MS = 1_500L
        /** 完成兜底监控轮询屏幕缓冲的间隔（毫秒）。旧值 200ms 每轮都会构建 2000 行 transcript 字符串，
         *  长命令（构建/安装）期间持续占 CPU；间隔放宽到 1s 并配合「输出无增长跳过扫描」后开销可忽略。 */
        const val EXIT_MARKER_POLL_MS = 1_000L
        /** 匹配命令退出标记 `[command exited: N]` 的定位前缀（配合手工解析退出码，免全量正则扫描）。 */
        const val EXIT_MARKER_PREFIX = "[command exited: "
        /** 交互标签创建后多久仍无任何输出就 dump 容器诊断（proot 没起来/卡住时的主动取证）。 */
        const val SILENT_DIAGNOSTIC_DELAY_MS = 10_000L
    }

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** 触发任一标签输出/状态变化时自增，供 Compose 重组拉取最新屏幕内容。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 是否有后台终端标签仍在运行（用于判断 KeepaliveService 能否停）。 */
    fun hasBackgroundTabs(): Boolean =
        _tabs.value.any { it.isBackground && it.runState is RunState.Running }

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /**
     * 新建一个交互 shell 标签并设为当前。返回新标签 id。
     *
     * 首次会触发 rootfs/proot 解压（幂等）；失败抛异常由调用方处理。
     */
    suspend fun createInteractiveTab(): String {
        FileLogger.i(TAG, "新建交互终端标签：开始准备容器")
        ensureContainer()
        val id = nextId()
        // -w 已把 cwd 设为 /root/workspace，cd 仅作兜底；裸 sh/bash 在 tty 上自动进交互模式，
        // 靠 ENV=/etc/profile 加载登录环境；exec 让 shell 取代外层 sh -c 成为前台交互 shell。
        // 首次进入终端时先跑初始化菜单（脚本自行判断已完成/已跳过则秒退），所有容器一致；
        // 用 `;` 分隔保证脚本任何失败都不阻塞进入 shell。
        val shellCommand = "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
            "[ -f /root/.aicode/provision.sh ] && sh /root/.aicode/provision.sh; " +
            "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '; " +
            "alias ls='ls --color=auto' 2>/dev/null; alias grep='grep --color=auto' 2>/dev/null; alias ll='ls -la --color=auto' 2>/dev/null; " +
            "exec ${containerEngine.defaultShell()}"
        FileLogger.i(TAG, "交互 shell 命令（$id）：$shellCommand")
        val (session, client) = try {
            buildSession(shellCommand)
        } catch (e: Exception) {
            FileLogger.e(TAG, "创建交互终端会话失败（$id）", e)
            containerEngine.logContainerDiagnostics("创建交互终端会话失败")
            throw e
        }
        addTab(
            TerminalTab(
                id = id,
                title = id,
                session = session,
                isBackground = false,
                command = null,
                client = client,
                runState = RunState.Running
            )
        )
        _activeTabId.value = id
        FileLogger.i(TAG, "新建交互终端标签 $id：pid=${session.pid} 运行中=${session.isRunning}")
        scheduleSilentSessionDiagnostic(id)
        return id
    }

    /**
     * 静默会话诊断：交互标签创建后若迟迟没有任何输出，说明 proot 可能没起来或卡在内核态
     * （用户报「解压完成后终端一片空白、卡住」正是这种形态）。主动 dump 一次容器状态，
     * 让日志里留下可定位的证据，而不是只能看到「新建标签」后再无下文。
     */
    private fun scheduleSilentSessionDiagnostic(tabId: String) {
        monitorScope.launch {
            delay(SILENT_DIAGNOSTIC_DELAY_MS)
            val tab = tab(tabId) ?: return@launch
            if (tab.runState !is RunState.Running) return@launch
            if (!getTabOutput(tabId).isNullOrBlank()) return@launch
            FileLogger.w(TAG, "终端标签 $tabId 启动 ${SILENT_DIAGNOSTIC_DELAY_MS}ms 后仍无任何输出，dump 容器状态")
            containerEngine.logContainerDiagnostics("终端标签 $tabId 静默")
        }
    }

    /**
     * 供 AI 预留接口：把一条命令挂后台跑（如 `npm run dev`），返回唯一 tabId。
     *
     * 命令跑完后 `exec /bin/sh` 保活，使该标签仍是一个可继续输入的会话（dev server 退出后也能复用），
     * 且输出全程留在 emulator 缓冲里，用户切过去或 AI 用 [getTabOutput] 都能看到累计输出。
     */
    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        ensureContainer()
        val id = nextId()
        // 用 `ec=$?` 捕获命令真实退出码后再 echo，否则 echo 本身恒为 0 会覆盖进程退出码，导致
        // onFinished 回调里的 exitStatus 永远是 0、与屏幕上 echo 出来的码对不上。
        // notify=true：echo 后 `exit $ec` 让 shell 以命令真实退出码结束 → proot 透传 → 回调 exitStatus
        //   正确，且 shell 自然退出稳定触发 MSG_PROCESS_EXITED；否则脚本末尾是 echo，进程退出码被
        //   污染成 0，而某些情况下进程不干净退出还会导致回调不触发。
        // notify=false：echo 后 `exec ${shell}` 保活，标签可复用（dev server 退出后也能继续输入）；
        //   此时进程不退出、不回调，退出码无意义，符合 dev server 场景设计。
        val afterCommand = if (notify) "; exit \$ec" else "; exec ${containerEngine.defaultShell()}"
        val shellCommand = "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
            "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        val (session, client) = buildSession(shellCommand)
        addTab(
            TerminalTab(
                id = id,
                title = title ?: id,
                session = session,
                isBackground = true,
                command = command,
                notifyOnExit = notify,
                sourceSessionId = sourceSessionId,
                client = client,
                runState = RunState.Running
            )
        )
        // 后台命令不抢占当前标签焦点：仅当没有活动标签时才设为当前。
        if (_activeTabId.value == null) _activeTabId.value = id

        if (notify) monitorBackgroundExit(id)

        startKeepaliveService()
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /** 按 id 向标签发送输入并回车执行（AI 持续发命令的入口）。返回是否命中标签且仍活跃。 */
    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始文本，不自动追加回车。 */
    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { writeToSession(it.session, text) }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    /**
     * 按 id 读取终端内容（emulator 屏幕缓冲的完整 transcript），供 AI 拉取。
     * 返回 null 表示无此标签。
     */
    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    /** 列出全部标签的摘要（id/标题/是否后台/运行状态/命令），供 AI 选目标。 */
    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    /** 切换当前标签。 */
    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** 关闭并销毁标签（用户主动关 / AI close）。从列表移除并杀会话。 */
    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()

        if (tab.isBackground && remaining.none { it.isBackground && it.runState is RunState.Running }) {
            stopKeepaliveService()
        }
        FileLogger.i(TAG, "关闭终端标签 $id")
        return true
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    private suspend fun ensureContainer() {
        val startedAt = System.currentTimeMillis()
        containerEngine.ensureInstalled()
        if (!containerEngine.isContainerInstalled()) {
            throw IllegalStateException("容器未安装（缺少 rootfs/proot）")
        }
        FileLogger.i(TAG, "容器就绪（耗时 ${System.currentTimeMillis() - startedAt}ms）")
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /**
     * 完成兜底监控：termux 的会话结束依赖 PTY 读到 EOF，而 proot 宿主进程在 Android 上可能
     * 概率性不退出（即使其 bash 已执行 exit，proot 仍攥着 /dev/pts 不释放），导致 onFinished
     * 永不回调、notify=true 的任务不触发通知。bash 在真正退出前必打印 `[command exited: N]`，
     * 以此作为命令结束的可靠信号：监控到后短缓冲（给正常回调留时间），仍 Running 则强制收尾。
     */
    /** 在输出文本的末尾行中定位退出标记 `[command exited: N]` 并解析退出码；未找到返回 null。
     *  要求退出标记必须作为独立行出现（以换行开头或位于文本起始），且从末尾向后定位，避免 PTY 命令行回显误判。 */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    // 输出无增长时跳过扫描：仅比较长度，省去每轮全量定位退出标记。
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // 缓冲后再查：正常 onFinished 回调若已触发，状态不再 Running，此处直接退出。
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        current.runState = RunState.Finished(exitCode)
                        bumpRevision()
                        FileLogger.i(TAG, "兜底：标签 $tabId 检测到退出标记，强制收尾 exit=$exitCode")
                        if (current.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
                            stopKeepaliveService()
                        }
                        if (current.notifyOnExit && !current.finishedNotified) {
                            current.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                                )
                            )
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun writeToSession(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * 构造一个进入容器的 PTY 会话，并接好输出/结束回调。
     *
     * client 的 viewProvider/onFinished 都以 session 为键回查 [_tabs]：会话与标签一一对应，
     * 故无需把 tab 引用提前注入 client（避免「构造 client 时 tab 还不存在」的先有鸡先有蛋）。
     */
    private fun buildSession(shellCommand: String): Pair<TerminalSession, AppTerminalSessionClient> {
        val workspace = workspaceRepository.currentPath()
        val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
        FileLogger.i(
            TAG,
            "PTY 启动：workspace=$workspace 可执行=${invocation.executable}" +
                "（存在=${java.io.File(invocation.executable).exists()}）argv=${invocation.argv.joinToString(" ")}"
        )
        FileLogger.d(TAG, "PTY 环境变量键：${invocation.env.keys.joinToString(",")}")
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    target.runState = RunState.Finished(finished.exitStatus)
                    bumpRevision()
                    FileLogger.i(TAG, "终端标签 ${target.id} 会话结束 exit=${finished.exitStatus}")
                    if (target.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
                        stopKeepaliveService()
                    }
                    if (target.notifyOnExit && !target.finishedNotified) {
                        target.finishedNotified = true
                        _tabFinishedEvents.tryEmit(
                            TabFinishedEvent(
                                target.id, target.title, target.command, finished.exitStatus, target.sourceSessionId,
                                tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                            )
                        )
                    }
                }
            }
        )
        session = TerminalSession(
            invocation.executable,
            appContext.filesDir.absolutePath,
            invocation.argv.toTypedArray(),
            invocation.ptyEnvArray,
            TRANSCRIPT_ROWS,
            client
        )
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        FileLogger.i(TAG, "PTY 会话已创建：pid=${session.pid} 运行中=${session.isRunning}")
        return session to client
    }

    private fun startKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_START_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已启动")
    }

    private fun stopKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_STOP_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已停止")
    }
}
