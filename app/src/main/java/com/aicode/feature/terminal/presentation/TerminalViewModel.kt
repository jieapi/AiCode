package com.aicode.feature.terminal.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.RunState
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTab
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import com.termux.terminal.SessionBackend
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import javax.inject.Inject

private class PluginTerminalMirrorOwner(
    val backendInstanceId: String,
    val provider: TerminalSessionProvider
) {
    private val hostByRemote = mutableMapOf<String, String>()

    fun matches(backend: ExecutionBackend): Boolean =
        backend.instanceId == backendInstanceId && backend.terminalSessionProvider === provider

    fun hostHandle(remoteTabId: String): String = hostByRemote.getOrPut(remoteTabId) {
        UUID.randomUUID().toString()
    }

}

/**
 * 终端页的薄观察层。
 *
 * 会话的所有权在 [TerminalSessionManager]（Singleton），本 ViewModel 只转发 UI 操作并暴露其状态流，
 * **不持有也不销毁任何会话**——这正是「常驻后台」的关键：离开终端页导致本 VM onCleared 时，
 * 会话仍由管理器持有、继续在后台运行，下次回到终端页直接复用。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localManager: TerminalSessionManager,
    private val remoteManager: RemoteTerminalSessionManager,
    private val modeHolder: ExecutionModeHolder,
    private val containerEngine: LinuxContainerEngine,
    private val terminalSettingsRepository: TerminalSettingsRepository
) : ViewModel() {

    private companion object {
        const val TAG = "TerminalViewModel"
        const val PLUGIN_TRANSCRIPT_ROWS = 2000
        const val PLUGIN_DEFAULT_COLUMNS = 80
        const val PLUGIN_DEFAULT_ROWS = 24
        const val PLUGIN_PIPE_BUFFER_BYTES = 64 * 1024
    }

    private fun currentBackend(): ExecutionBackend = modeHolder.currentBackend()

    private fun isRemoteSsh() = currentBackend().id == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID

    private fun isLocalProot() = currentBackend().id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID

    private var pluginMirrorOwner: PluginTerminalMirrorOwner? = null
    private var pluginMirrorGeneration = 0L
    private var prepareJob: Job? = null
    private var createJob: Job? = null

    private fun activePluginOwner(): PluginTerminalMirrorOwner? =
        pluginMirrorOwner?.takeIf { it.matches(currentBackend()) }

    private fun ownsMirror(owner: PluginTerminalMirrorOwner): Boolean =
        pluginMirrorOwner === owner && owner.matches(currentBackend())

    /** 当前是否远程环境（UI 据此隐藏本地容器专属入口，如环境工具）。 */
    val isRemoteMode: Boolean get() = currentBackend().capabilities.isRemoteEnvironment

    /** 容器准备阶段的整体状态：仅用于首个标签创建前的 Loading/Error 提示。 */
    sealed interface PrepareState {
        data object Loading : PrepareState
        data object Ready : PrepareState
        data class Error(val message: String) : PrepareState
    }

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Loading)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    /** 正在新建标签：SSH 模式下建立 shell channel 需等服务器响应，UI 据此显示等待动画并拦截重复点击。 */
    private val _creatingTab = MutableStateFlow(false)
    val creatingTab: StateFlow<Boolean> = _creatingTab.asStateFlow()

    /** 容器初始化实时进度（解压/部署/装包），Loading 阶段用它展示细粒度文案。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _pluginTabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    private val _pluginActiveTabId = MutableStateFlow<String?>(null)
    private val _pluginRevision = MutableStateFlow(0)

    private fun tabsFlowFor(backend: ExecutionBackend): StateFlow<List<TerminalTab>> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> remoteManager.tabs
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> localManager.tabs
        else -> _pluginTabs
    }

    private fun activeTabIdFlowFor(backend: ExecutionBackend): StateFlow<String?> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> remoteManager.activeTabId
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> localManager.activeTabId
        else -> _pluginActiveTabId
    }

    private fun revisionFlowFor(backend: ExecutionBackend): StateFlow<Int> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> remoteManager.revision
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> localManager.revision
        else -> _pluginRevision
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val tabs: StateFlow<List<TerminalTab>> = modeHolder.activeBackend
        .flatMapLatest { backend ->
            tabsFlowFor(backend).map { list ->
                if (backend.id == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID ||
                    backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID ||
                    pluginMirrorOwner?.matches(backend) == true
                ) list else emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, tabsFlowFor(currentBackend()).value)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeTabId: StateFlow<String?> = modeHolder.activeBackend
        .flatMapLatest { backend ->
            activeTabIdFlowFor(backend).map { id ->
                if (backend.id == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID ||
                    backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID ||
                    pluginMirrorOwner?.matches(backend) == true
                ) id else null
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, activeTabIdFlowFor(currentBackend()).value)

    @OptIn(ExperimentalCoroutinesApi::class)
    val revision: StateFlow<Int> = modeHolder.activeBackend
        .flatMapLatest { revisionFlowFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, revisionFlowFor(currentBackend()).value)

    /** 额外按键行驱动的虚拟修饰键，供 TerminalView 读取。 */
    val modifiers = TerminalKeyModifiers()

    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalSettings())

    fun setTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    fun setShowPluginTabs(enabled: Boolean) {
        viewModelScope.launch { terminalSettingsRepository.setShowPluginTabs(enabled) }
    }

    init {
        viewModelScope.launch {
            modeHolder.activeBackend.collect { backend ->
                prepareJob?.cancel()
                createJob?.cancel()
                _creatingTab.value = false
                clearPluginMirrors()
                pluginMirrorGeneration++
                if (backend.id != ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID &&
                    backend.id != ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
                ) {
                    backend.terminalSessionProvider?.let {
                        pluginMirrorOwner = PluginTerminalMirrorOwner(backend.instanceId, it)
                    }
                }
                prepare()
            }
        }
    }

    /** 进入终端页：确保至少有一个标签（首次会解压容器或连 SSH；插件后端则同步或提示不支持）。 */
    fun prepare() {
        prepareJob?.cancel()
        val backend = currentBackend()
        val owner = activePluginOwner()
        val generation = pluginMirrorGeneration
        prepareJob = viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            try {
                when (backend.id) {
                    ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> {
                        remoteManager.ensureInitialTab()
                        _prepareState.value = PrepareState.Ready
                    }
                    ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> {
                        localManager.ensureInitialTab()
                        _prepareState.value = PrepareState.Ready
                    }
                    else -> {
                        val provider = backend.terminalSessionProvider
                        if (provider == null) {
                            _prepareState.value = PrepareState.Error(
                                context.getString(R.string.terminal_backend_unsupported, backend.displayName)
                            )
                        } else {
                            if (owner == null || generation != pluginMirrorGeneration) return@launch
                            syncPluginTabs(owner)
                            if (ownsMirror(owner)) _prepareState.value = PrepareState.Ready
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "终端准备失败", e)
                _prepareState.value = PrepareState.Error(e.message ?: context.getString(R.string.terminal_prepare_error_unknown))
            }
        }
    }

    fun newTab() {
        if (_creatingTab.value) return
        _creatingTab.value = true
        val backend = currentBackend()
        val owner = activePluginOwner()
        val generation = pluginMirrorGeneration
        createJob = viewModelScope.launch {
            try {
                when (backend.id) {
                    ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> remoteManager.createInteractiveTab()
                    ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> localManager.createInteractiveTab()
                    else -> {
                        if (owner != null && generation == pluginMirrorGeneration) {
                            val remoteId = withContext(Dispatchers.IO) {
                                owner.provider.startBackgroundCommand("", title = backend.displayName, notify = false)
                            }
                            if (generation == pluginMirrorGeneration) {
                                syncPluginTabs(owner, preferredActiveRemoteId = remoteId)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "新建标签失败", e)
            } finally {
                _creatingTab.value = false
            }
        }
    }

    fun activate(id: String) {
        when {
            isRemoteSsh() -> remoteManager.activate(id)
            isLocalProot() -> localManager.activate(id)
            else -> if (activePluginOwner() != null && _pluginTabs.value.any { it.id == id }) {
                _pluginActiveTabId.value = id
            }
        }
    }

    fun closeTab(id: String) {
        when {
            isRemoteSsh() -> remoteManager.closeTab(id)
            isLocalProot() -> localManager.closeTab(id)
            else -> {
                val owner = activePluginOwner() ?: return
                val target = _pluginTabs.value.firstOrNull { it.id == id } ?: return
                val remoteTabId = (target.client as PluginSessionClient).remoteTabId
                target.view = null
                runCatching { target.session.finishIfRunning() }
                val remaining = _pluginTabs.value.filterNot { it.id == id }
                _pluginTabs.value = remaining
                if (_pluginActiveTabId.value == id) {
                    _pluginActiveTabId.value = remaining.firstOrNull()?.id
                }
                _pluginRevision.value++
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { owner.provider.closeTab(remoteTabId) }
                }
            }
        }
    }

    /** 关闭所有终端标签（切换工作区前调用）。 */
    fun closeAllTabs() {
        when {
            isRemoteSsh() -> remoteManager.tabs.value.map { it.id }.forEach { remoteManager.closeTab(it) }
            isLocalProot() -> localManager.tabs.value.map { it.id }.forEach { localManager.closeTab(it) }
            else -> _pluginTabs.value.map { it.id }.forEach { closeTab(it) }
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun write(text: String) {
        when {
            isRemoteSsh() -> remoteManager.writeToActive(text)
            isLocalProot() -> localManager.writeToActive(text)
            else -> {
                if (activePluginOwner() == null) return
                val active = _pluginTabs.value.firstOrNull { it.id == _pluginActiveTabId.value } ?: return
                val bytes = text.toByteArray(Charsets.UTF_8)
                active.session.write(bytes, 0, bytes.size)
            }
        }
    }

    /** 向当前活动标签写入原始字节（发送控制字符，如 Ctrl-C=0x03、Ctrl-D=0x04）。 */
    fun writeBytes(vararg bytes: Int) {
        when {
            isRemoteSsh() -> remoteManager.writeBytesToActive(*bytes)
            isLocalProot() -> localManager.writeBytesToActive(*bytes)
            else -> {
                if (activePluginOwner() == null) return
                val active = _pluginTabs.value.firstOrNull { it.id == _pluginActiveTabId.value } ?: return
                val arr = ByteArray(bytes.size) { bytes[it].toByte() }
                active.session.write(arr, 0, arr.size)
            }
        }
    }

    private suspend fun syncPluginTabs(
        owner: PluginTerminalMirrorOwner,
        preferredActiveRemoteId: String? = null
    ) {
        if (!ownsMirror(owner)) return
        val provider = owner.provider
        val remoteInfos = withContext(Dispatchers.IO) { provider.listTabs() }
        if (!ownsMirror(owner)) return
        val existingByRemoteId = _pluginTabs.value.associateBy { (it.client as PluginSessionClient).remoteTabId }
        val remoteIds = remoteInfos.map { it.id }.toSet()
        existingByRemoteId.filterKeys { it !in remoteIds }.values.forEach { oldTab ->
            oldTab.view = null
            runCatching { oldTab.session.finishIfRunning() }
        }
        val nextTabs = remoteInfos.map { info ->
            val hostId = owner.hostHandle(info.id)
            existingByRemoteId[info.id]?.also { tab ->
                tab.title = info.title
                tab.runState = if (info.running) RunState.Running else RunState.Finished(0)
            } ?: buildPluginTab(owner, hostId, info.id, info.title, info.isBackground, info.command, info.running, info.ownerPluginId)
        }
        _pluginTabs.value = nextTabs
        _pluginActiveTabId.value = nextTabs.firstOrNull {
            (it.client as PluginSessionClient).remoteTabId == preferredActiveRemoteId
        }?.id ?: _pluginActiveTabId.value?.takeIf { active -> nextTabs.any { it.id == active } }
            ?: nextTabs.firstOrNull { it.ownerPluginId == null }?.id ?: nextTabs.firstOrNull()?.id
        _pluginRevision.value++
    }

    private fun buildPluginTab(
        owner: PluginTerminalMirrorOwner,
        hostTabId: String,
        remoteTabId: String,
        title: String,
        isBackground: Boolean,
        command: String?,
        running: Boolean,
        ownerPluginId: String?
    ): TerminalTab {
        val sessionClient = PluginSessionClient(owner, remoteTabId)
        val backend = PluginTabSessionBackend(owner.provider, remoteTabId, viewModelScope)
        val session = TerminalSession(PLUGIN_TRANSCRIPT_ROWS, sessionClient, backend)
        session.updateSize(PLUGIN_DEFAULT_COLUMNS, PLUGIN_DEFAULT_ROWS)
        return TerminalTab(
            id = hostTabId,
            title = title,
            session = session,
            isBackground = isBackground,
            command = command,
            ownerPluginId = ownerPluginId,
            client = sessionClient,
            runState = if (running) RunState.Running else RunState.Finished(0)
        )
    }

    private fun clearPluginMirrors() {
        pluginMirrorGeneration++
        pluginMirrorOwner = null
        val oldTabs = _pluginTabs.value
        _pluginTabs.value = emptyList()
        _pluginActiveTabId.value = null
        oldTabs.forEach { tab ->
            tab.view = null
            runCatching { tab.session.finishIfRunning() }
        }
        _pluginRevision.value++
    }

    override fun onCleared() {
        clearPluginMirrors()
        super.onCleared()
    }

    private inner class PluginTabSessionBackend(
        private val provider: TerminalSessionProvider,
        private val tabId: String,
        scope: CoroutineScope
    ) : SessionBackend {
        private val pipeIn = PipedInputStream(PLUGIN_PIPE_BUFFER_BYTES)
        private val pipeOut = PipedOutputStream(pipeIn)
        @Volatile private var closed = false
        private val streamJob: Job = scope.launch(Dispatchers.IO) {
            try {
                val flow = provider.tabOutput(tabId)
                if (flow == null) {
                    val snapshot = provider.getTabOutput(tabId)
                    if (!snapshot.isNullOrEmpty()) {
                        pipeOut.write(snapshot.toByteArray(Charsets.UTF_8))
                        pipeOut.flush()
                    }
                } else {
                    flow.collect { chunk ->
                        if (closed) return@collect
                        if (chunk.isNotEmpty()) {
                            pipeOut.write(chunk)
                            pipeOut.flush()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) FileLogger.w(TAG, "插件终端输出流读取异常: $tabId", e)
            } finally {
                runCatching { pipeOut.close() }
            }
        }

        // TerminalSession 调用此流时已在单一 writer 线程；不再把 RPC 转回 Main。
        private val outputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed || len <= 0) return
                val bytes = IntArray(len) { b[off + it].toInt() and 0xFF }
                provider.writeBytesToTab(tabId, *bytes)
            }
        }

        override fun getInputStream(): InputStream = pipeIn

        override fun getOutputStream(): OutputStream = outputStream

        override fun resize(columns: Int, rows: Int) {
            if (!resizeUnsupportedReported) {
                resizeUnsupportedReported = true
                FileLogger.w(TAG, "Plugin terminal resize unsupported: TerminalSessionProvider has no resize API")
            }
        }

        private var resizeUnsupportedReported = false

        override fun waitForExit(): Int {
            while (!closed && streamJob.isActive) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return if (closed) 0 else provider.tabStatus(tabId)?.exitCode ?: 0
        }

        override fun close() {
            closed = true
            streamJob.cancel()
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
        }
    }

    private inner class PluginSessionClient(
        private val owner: PluginTerminalMirrorOwner,
        val remoteTabId: String
    ) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (!ownsMirror(owner)) return
            _pluginTabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
            _pluginRevision.value++
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (!ownsMirror(owner)) return
            _pluginTabs.value.firstOrNull { it.session === finishedSession }?.let { tab ->
                tab.runState = RunState.Finished(0)
                _pluginRevision.value++
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

    // 注意：故意不在 onCleared 里销毁内置单例会话——会话归 Singleton 管理器所有，需常驻后台。
}
