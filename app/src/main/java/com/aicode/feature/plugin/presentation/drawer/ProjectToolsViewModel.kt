package com.aicode.feature.plugin.presentation.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectToolItem
import com.aicode.feature.workspace.domain.ProjectToolsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ProjectToolsViewModel @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val projectToolsRepository: ProjectToolsRepository,
    private val pluginManager: PluginManager
) : ViewModel() {

    /** 当前工作区绝对路径 */
    val currentWorkspacePath: String get() = workspaceRepository.currentPath()

    /** 当前工作区已启用的工具配置列表 */
    val projectTools: StateFlow<List<ProjectToolItem>> = projectToolsRepository.tools

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

    /** 当前正在查看的工具 */
    private val _selectedTool = MutableStateFlow<PluginWorkspaceTool?>(null)
    val selectedTool: StateFlow<PluginWorkspaceTool?> = _selectedTool.asStateFlow()

    fun selectTool(tool: PluginWorkspaceTool) {
        _selectedTool.value = tool
    }

    /**
     * 按 key 还原详情页的工具。
     *
     * 从右抽屉进入时 [selectTool] 已经设好了同一个工具；这里必须保留，不能拿未订阅的
     * [allPluginTools]（其 value 可能还是初始空列表）把它覆盖成 null。需要按键还原时
     * 直接向 PluginManager 取当前启用工具，避免依赖 StateFlow 的订阅时机。
     */
    suspend fun selectToolByKey(key: String) {
        if (_selectedTool.value?.key == key) return
        val tool = withContext(Dispatchers.IO) {
            pluginManager.activeWorkspaceTools().firstOrNull { it.key == key }
        }
        _selectedTool.value = tool
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
}
