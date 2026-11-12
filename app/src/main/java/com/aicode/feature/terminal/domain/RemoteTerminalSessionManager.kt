package com.aicode.feature.terminal.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.tool.explorer.shellQuote
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val OUTPUT_CHANNEL_CAPACITY = 64
private const val EXIT_MARKER_PREFIX = "[command exited: "
private const val EXIT_MARKER_POLL_MS = 1_000L
private const val EXIT_MARKER_GRACE_MS = 1_500L

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    /** 每个标签的原始输出有界队列，由 [tabOutput] 暴露给日志消费端。 */
    private val outputChannels = ConcurrentHashMap<String, Channel<ByteArray>>()

    // 后台命令退出兜底监控（见 monitorBackgroundExit），随单例生命周期常驻。
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 仅当当前模式是 REMOTE_SSH 且已连接时才可使用。 */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** 终端页进入时调用：没有用户标签则建一个交互 shell。幂等；不会把插件标签设为当前。 */
    suspend fun ensureInitialTab() {
        val firstUserTab = _tabs.value.firstOrNull { it.ownerPluginId == null }
        if (firstUserTab == null) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = firstUserTab.id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(
            command, isBackground = true, notify = notify, title = title,
            sourceSessionId = sourceSessionId, exitOnFinish = notify
        )
        if (notify) monitorBackgroundExit(id)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /** 插件命令标签：执行完命令后退出 shell，标签保留为 Finished，由插件关闭。 */
    override suspend fun startPluginCommand(command: String, title: String?, pluginId: String): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(
            command, isBackground = true, notify = false, title = title,
            sourceSessionId = null, ownerPluginId = pluginId, exitOnFinish = true
        )
        monitorBackgroundExit(id)
        FileLogger.i(TAG, "插件命令标签 $id（$pluginId）: $command")
        return id
    }

    override fun tabStatus(id: String): TerminalTabStatus? {
        val tab = tab(id) ?: return null
        return when (val state = tab.runState) {
            is RunState.Running -> TerminalTabStatus(running = true, exitCode = null)
            is RunState.Finished -> TerminalTabStatus(running = false, exitCode = state.exitCode)
        }
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?,
        ownerPluginId: String? = null,
        exitOnFinish: Boolean = false
    ): String {
        val id = nextId()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = withContext(Dispatchers.IO) {
            connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
        }
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        val outputChannel = Channel<ByteArray>(OUTPUT_CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST)
        outputChannels.remove(id)?.close()
        outputChannels[id] = outputChannel
        termSession.setRawOutputListener { _, data, length ->
            outputChannel.trySend(data.copyOf(length))
        }
        // shell 登录后默认在 home，先 cd 到当前工作区，与命令执行链路（RemoteSshEngine.buildCdCommand）保持一致：
        // 优先 ~/workspace 符号链接，失败回退到真实工作区路径。
        val wsPath = workspaceRepository.currentPath()
        if (wsPath.isNotBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd ${shellQuote(wsPath.trimEnd('/'))} 2>/dev/null\n")
        }
        if (command != null) {
            // notify=true：命令后打印退出标记并 exit，让交互 shell 结束 → channel 关闭 →
            // onSessionFinished 触发；标记同时供 monitorBackgroundExit 解析真实退出码
            //（SSH shell 不透传退出码，waitForExit 恒返回 0）。
            // notify=false：exec /bin/sh 保活，标签可复用。
            val init = if (notify || exitOnFinish) {
                "$command; ec=\$?; echo \"$EXIT_MARKER_PREFIX\$ec]\"; exit \$ec"
            } else {
                "$command; ec=\$?; echo \"$EXIT_MARKER_PREFIX\$ec]\"; exec /bin/sh"
            }
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            ownerPluginId = ownerPluginId,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun tabOutput(id: String): Flow<ByteArray>? = outputChannels[id]?.receiveAsFlow()

    override fun listTabs(): List<TabInfo> = _tabs.value
        .filter { it.ownerPluginId == null }
        .map {
            TabInfo(
                id = it.id,
                title = it.title,
                isBackground = it.isBackground,
                running = it.runState is RunState.Running,
                command = it.command,
                ownerPluginId = it.ownerPluginId
            )
        }

    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        outputChannels.remove(id)?.close()
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()
        FileLogger.i(TAG, "关闭远程终端标签 $id")
        return true
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            tab.session.write(bytes, 0, bytes.size)
        }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * 远程后台命令的退出兜底：交互式 shell channel 不保证在命令结束时及时关闭，单靠
     * onSessionFinished 可能永远收不到；轮询输出中的退出标记强制收尾（与本地实现对齐）。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            while (true) {
                val current = tab(tabId) ?: return@launch
                if (current.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    val tab2 = tab(tabId) ?: return@launch
                    if (tab2.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        tab2.runState = RunState.Finished(exitCode)
                        outputChannels.remove(tabId)?.close()
                        bumpRevision()
                        FileLogger.i(TAG, "兜底：远程标签 $tabId 检测到退出标记，强制收尾 exit=$exitCode")
                        if (tab2.notifyOnExit && !tab2.finishedNotified) {
                            tab2.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    tab2.id, tab2.title, tab2.command, exitCode, tab2.sourceSessionId,
                                    tailOutput = getTabOutput(tab2.id)?.takeTailLines(TAIL_LINES)
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

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                val exitCode = extractExitCode(getTabOutput(target.id) ?: "") ?: 0
                target.runState = RunState.Finished(exitCode)
                outputChannels.remove(target.id)?.close()
                bumpRevision()
                if (target.notifyOnExit && !target.finishedNotified) {
                    target.finishedNotified = true
                    _tabFinishedEvents.tryEmit(
                        TabFinishedEvent(
                            target.id, target.title, target.command, exitCode, target.sourceSessionId,
                            tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                        )
                    )
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            if (text.isNotEmpty()) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                session?.write(bytes, 0, bytes.size)
            }
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
