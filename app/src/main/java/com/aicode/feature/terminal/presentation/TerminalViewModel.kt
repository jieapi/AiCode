package com.aicode.feature.terminal.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private companion object { const val TAG = "TerminalViewModel" }

    private fun isRemote() = modeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    /** 当前是否远程 SSH 模式（UI 据此隐藏本地容器专属入口，如环境工具）。 */
    val isRemoteMode: Boolean get() = isRemote()

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

    val tabs get() = if (isRemote()) remoteManager.tabs else localManager.tabs
    val activeTabId get() = if (isRemote()) remoteManager.activeTabId else localManager.activeTabId
    val revision get() = if (isRemote()) remoteManager.revision else localManager.revision

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
        prepare()
    }

    /** 进入终端页：确保至少有一个标签（首次会解压容器或连 SSH）。 */
    fun prepare() {
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            try {
                if (isRemote()) remoteManager.ensureInitialTab() else localManager.ensureInitialTab()
                _prepareState.value = PrepareState.Ready
            } catch (e: Exception) {
                FileLogger.e(TAG, "终端准备失败", e)
                _prepareState.value = PrepareState.Error(e.message ?: context.getString(R.string.terminal_prepare_error_unknown))
            }
        }
    }

    fun newTab() {
        if (_creatingTab.value) return
        _creatingTab.value = true
        viewModelScope.launch {
            try {
                if (isRemote()) remoteManager.createInteractiveTab() else localManager.createInteractiveTab()
            } catch (e: Exception) {
                FileLogger.e(TAG, "新建标签失败", e)
            } finally {
                _creatingTab.value = false
            }
        }
    }

    fun activate(id: String) = if (isRemote()) remoteManager.activate(id) else localManager.activate(id)

    fun closeTab(id: String) = if (isRemote()) remoteManager.closeTab(id) else localManager.closeTab(id)

    /** 关闭所有终端标签（切换工作区前调用）。 */
    fun closeAllTabs() {
        (if (isRemote()) remoteManager.tabs else localManager.tabs).value.map { it.id }.forEach { closeTab(it) }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun write(text: String) = if (isRemote()) remoteManager.writeToActive(text) else localManager.writeToActive(text)

    /** 向当前活动标签写入原始字节（发送控制字符，如 Ctrl-C=0x03、Ctrl-D=0x04）。 */
    fun writeBytes(vararg bytes: Int) = if (isRemote()) remoteManager.writeBytesToActive(*bytes) else localManager.writeBytesToActive(*bytes)

    // 注意：故意不在 onCleared 里销毁会话——会话归 Singleton 管理器所有，需常驻后台。
}
