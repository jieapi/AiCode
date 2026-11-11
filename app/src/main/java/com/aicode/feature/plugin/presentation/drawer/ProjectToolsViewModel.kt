package com.aicode.feature.plugin.presentation.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.domain.ToolOutputFormatter
import com.aicode.feature.plugin.domain.ToolRunPhase
import com.aicode.feature.plugin.domain.ToolRunState
import com.aicode.feature.settings.domain.model.ActionSubmitElement
import com.aicode.feature.settings.domain.model.AdaptiveCardElement
import com.aicode.feature.settings.domain.model.CardElement
import com.aicode.feature.settings.domain.model.ColumnElement
import com.aicode.feature.settings.domain.model.ColumnSetElement
import com.aicode.feature.settings.domain.model.ContainerElement
import com.aicode.feature.settings.domain.model.FlowRowElement
import com.aicode.feature.settings.domain.model.GridElement
import com.aicode.feature.settings.domain.model.RowElement
import com.aicode.feature.settings.domain.model.ScrollRowElement
import com.aicode.feature.settings.domain.model.TabElement
import com.aicode.feature.settings.domain.model.TabSetElement
import com.aicode.feature.settings.domain.parser.AdaptiveCardParser
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectToolItem
import com.aicode.feature.workspace.domain.ProjectToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProjectToolsViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val projectToolsRepository: ProjectToolsRepository,
    private val pluginManager: PluginManager,
    private val terminalSessionProvider: TerminalSessionProvider
) : ViewModel() {

    /** 当前工作区绝对路径 */
    val currentWorkspacePath: String get() = workspaceRepository.currentPath()

    /** 当前工作区已启用的工具配置列表 */
    val projectTools: StateFlow<List<ProjectToolItem>> = projectToolsRepository.tools

    private val _runState = MutableStateFlow(ToolRunState())
    /** 当前工具的运行状态与日志，供详情页运行面板观察。 */
    val runState: StateFlow<ToolRunState> = _runState.asStateFlow()

    private var outputJob: Job? = null
    private var stopRequested = false
    private var runToken = 0

    /** 所有已安装并启用的插件所贡献的工作区工具列表 */
    val activePluginTools: StateFlow<List<PluginWorkspaceTool>> = pluginManager.plugins.combine(
        MutableStateFlow(Unit)
    ) { _, _ ->
        pluginManager.activeWorkspaceTools()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 所有插件贡献的全部工具列表（用于全部工具库展示添加） */
    val allPluginTools: StateFlow<List<PluginWorkspaceTool>> = pluginManager.plugins.combine(
        MutableStateFlow(Unit)
    ) { _, _ ->
        pluginManager.allWorkspaceTools()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前正在查看/配置的工具 */
    private val _selectedTool = MutableStateFlow<PluginWorkspaceTool?>(null)
    val selectedTool: StateFlow<PluginWorkspaceTool?> = _selectedTool.asStateFlow()

    private val _selectedToolItem = MutableStateFlow<ProjectToolItem?>(null)
    val selectedToolItem: StateFlow<ProjectToolItem?> = _selectedToolItem.asStateFlow()

    fun selectTool(tool: PluginWorkspaceTool, item: ProjectToolItem? = null) {
        _selectedTool.value = tool
        _selectedToolItem.value = item
    }

    fun selectToolByKey(key: String) {
        val tool = allPluginTools.value.firstOrNull { it.key == key }
        val item = projectTools.value.firstOrNull { it.toolKey == key }
        _selectedTool.value = tool
        _selectedToolItem.value = item
    }

    /** 将工具添加到当前项目 */
    fun addToolToProject(tool: PluginWorkspaceTool, languageTag: String = "zh-CN") {
        viewModelScope.launch {
            projectToolsRepository.addTool(tool, languageTag)
        }
    }

    /** 从当前项目移除工具 */
    fun removeToolFromProject(toolKey: String) {
        viewModelScope.launch {
            projectToolsRepository.removeTool(toolKey)
        }
    }

    /**
     * 执行工具：根据当前参数插值命令模板，拉起终端后台标签执行，
     * 并把输出、退出码与停止操作接到 [runState]。
     */
    fun executeTool(
        tool: PluginWorkspaceTool,
        params: Map<String, Any>,
        templateOverride: String? = null,
        onSuccess: ((tabId: String) -> Unit)? = null
    ) {
        if (_runState.value.phase == ToolRunPhase.RUNNING) return
        viewModelScope.launch {
            val submitAction = findSubmit(tool.cardRoot?.body.orEmpty())
            val template = templateOverride ?: submitAction?.commandTemplate ?: "echo 'No action configured for ${tool.localId}'"
            val title = submitAction?.terminalTitle ?: tool.title.resolve("default")

            // 1. 保存用户配置的参数
            projectToolsRepository.updateParams(tool.key, params)

            // 2. 命令插值
            val command = AdaptiveCardParser.interpolate(template, params)
            FileLogger.i("ProjectToolsVM", "执行插件工具[${tool.key}]: $command")

            // 3. 在终端启动共享后台标签并执行（保活：命令结束后标签仍可继续输入）
            val tabId = try {
                terminalSessionProvider.startBackgroundCommand(
                    command = command,
                    title = title,
                    notify = false
                )
            } catch (e: Exception) {
                FileLogger.e("ProjectToolsVM", "启动插件工具命令失败", e)
                _runState.value = ToolRunState(phase = ToolRunPhase.FAILED, command = command)
                return@launch
            }

            onSuccess?.invoke(tabId)
            startOutputCollection(command, tabId)
        }
    }

    /** 停止当前运行：先发 Ctrl-C，超时仍未结束再关闭标签。 */
    fun stopCurrentRun() {
        val current = _runState.value
        val tabId = current.tabId ?: return
        if (current.phase != ToolRunPhase.RUNNING) return
        stopRequested = true
        _runState.value = current.copy(phase = ToolRunPhase.CANCELLED)
        terminalSessionProvider.writeBytesToTab(tabId, 0x03)
        val token = runToken
        viewModelScope.launch {
            delay(STOP_TIMEOUT_MS)
            if (token == runToken && stopRequested && _runState.value.exitCode == null) {
                terminalSessionProvider.closeTab(tabId)
                outputJob?.cancel()
            }
        }
    }

    private fun startOutputCollection(command: String, tabId: String) {
        outputJob?.cancel()
        stopRequested = false
        val token = ++runToken
        _runState.value = ToolRunState(phase = ToolRunPhase.RUNNING, command = command, tabId = tabId)

        val flow = terminalSessionProvider.tabOutput(tabId)
        if (flow == null) {
            _runState.value = _runState.value.copy(phase = ToolRunPhase.CANCELLED)
            return
        }

        outputJob = viewModelScope.launch(Dispatchers.Default) {
            val formatter = ToolOutputFormatter()
            try {
                flow.collect { bytes ->
                    formatter.feed(bytes)
                    publishProgress(token, formatter, tabId, command)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e("ProjectToolsVM", "读取插件工具输出失败", e)
            }
            formatter.finish()
            if (token != runToken) return@launch
            val exit = formatter.exitCode
            val phase = when {
                stopRequested -> ToolRunPhase.CANCELLED
                exit == null -> ToolRunPhase.CANCELLED
                exit == 0 -> ToolRunPhase.SUCCESS
                else -> ToolRunPhase.FAILED
            }
            _runState.value = _runState.value.copy(
                phase = phase,
                exitCode = exit,
                lines = formatter.snapshot()
            )
        }
    }

    private fun publishProgress(
        token: Int,
        formatter: ToolOutputFormatter,
        tabId: String,
        command: String
    ) {
        if (token != runToken) return
        val exit = formatter.exitCode
        if (exit == null) {
            _runState.value = _runState.value.copy(lines = formatter.snapshot())
            return
        }
        _runState.value = ToolRunState(
            phase = when {
                stopRequested -> ToolRunPhase.CANCELLED
                exit == 0 -> ToolRunPhase.SUCCESS
                else -> ToolRunPhase.FAILED
            },
            lines = formatter.snapshot(),
            exitCode = exit,
            tabId = tabId,
            command = command
        )
        // 命令已结束，标签随后进入保活 shell，后续输出属于用户交互，不再写入运行日志。
        outputJob?.cancel()
    }

    /** 页面里的 submit 可能包在 Card/TabSet/Container 里，快速运行不能只看 body 直接子级。 */
    private fun findSubmit(elements: List<AdaptiveCardElement>): ActionSubmitElement? {
        elements.forEach { element ->
            when (element) {
                is ActionSubmitElement -> return element
                is ColumnSetElement -> findSubmit(element.columns.flatMap { it.items })?.let { return it }
                is ColumnElement -> findSubmit(element.items)?.let { return it }
                is ContainerElement -> findSubmit(element.items)?.let { return it }
                is RowElement -> findSubmit(element.items)?.let { return it }
                is FlowRowElement -> findSubmit(element.items)?.let { return it }
                is ScrollRowElement -> findSubmit(element.items)?.let { return it }
                is CardElement -> findSubmit(element.items)?.let { return it }
                is GridElement -> findSubmit(element.items)?.let { return it }
                is TabElement -> findSubmit(element.items)?.let { return it }
                is TabSetElement -> findSubmit(element.tabs.flatMap { it.items })?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
