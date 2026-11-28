package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 项目已启用工具项条目（保存在 `<workspace>/.aicode/tools.json` 中）。
 */
@Serializable
data class ProjectToolItem(
    val toolKey: String,
    val pluginId: String,
    val localId: String,
    val title: String,
    val description: String = "",
    val icon: String = "play",
    val category: String = "通用"
)

@Serializable
internal data class ProjectToolsFile(
    val tools: List<ProjectToolItem> = emptyList()
)

/**
 * 当前项目的工具箱管理器：负责读取、持久化 `<workspace>/.aicode/tools.json`，
 * 并与已安装的插件做动态对账。
 */
@Singleton
class ProjectToolsRepository @Inject constructor(
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val pluginManager: PluginManager,
    private val workspaceRepository: WorkspaceRepository
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tools = MutableStateFlow<List<ProjectToolItem>>(emptyList())
    val tools: StateFlow<List<ProjectToolItem>> = _tools.asStateFlow()

    init {
        scope.launch { reload() }
        // 插件启停、安装卸载或更新后自动对账。安装记录读完之前 plugins 为空只代表「还没加载」，
        // 此时对账会把用户配置全部清掉，必须等 isLoaded 之后再对账。
        scope.launch {
            pluginManager.plugins
                .combine(pluginManager.isLoaded) { _, loaded -> loaded }
                .collect { loaded -> if (loaded) reload() }
        }
        // 切换工作区后 tools.json 换文件，必须跟着重读
        scope.launch {
            workspaceRepository.current
                .map { it?.path }
                .distinctUntilChanged()
                .collect { reload() }
        }
    }

    private fun configFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    /** 重新加载当前工作区的工具配置。 */
    suspend fun reload() = withContext(Dispatchers.IO) {
        val file = configFile()
        val loaded = runCatching {
            if (file.isFile) {
                json.decodeFromString<ProjectToolsFile>(file.readText()).tools
            } else {
                emptyList()
            }
        }.getOrElse {
            FileLogger.w(TAG, "读取 tools.json 失败: ${it.message}")
            emptyList()
        }
        // 插件停用、卸载或更新后不再提供的工具，直接从项目配置里删掉，抽屉里不能留下失效项。
        val activeKeys = if (pluginManager.isLoaded.value) {
            pluginManager.activeWorkspaceTools().mapTo(HashSet()) { it.key }
        } else {
            null
        }
        val tools = if (activeKeys == null) loaded else loaded.filter { it.toolKey in activeKeys }
        if (tools.size != loaded.size) write(tools)
        _tools.value = tools
    }

    /** 向当前项目添加一个工具。 */
    suspend fun addTool(tool: PluginWorkspaceTool, languageTag: String = "zh-CN") = withContext(Dispatchers.IO) {
        val current = _tools.value.toMutableList()
        if (current.none { it.toolKey == tool.key }) {
            current.add(
                ProjectToolItem(
                    toolKey = tool.key,
                    pluginId = tool.pluginId,
                    localId = tool.localId,
                    title = tool.title.resolve(languageTag),
                    description = tool.description.resolve(languageTag),
                    icon = tool.icon,
                    category = tool.category
                )
            )
            save(current)
        }
    }

    /** 从当前项目移除工具。 */
    suspend fun removeTool(toolKey: String) = withContext(Dispatchers.IO) {
        val current = _tools.value.filterNot { it.toolKey == toolKey }
        save(current)
    }

    private fun save(items: List<ProjectToolItem>) {
        if (write(items)) _tools.value = items
    }

    private fun write(items: List<ProjectToolItem>): Boolean = runCatching {
        val file = configFile()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ProjectToolsFile(items)))
    }.onFailure {
        FileLogger.e(TAG, "写入 tools.json 失败", it)
    }.isSuccess

    companion object {
        private const val TAG = "ProjectToolsRepo"
        private const val CONFIG_FILE = "tools.json"
    }
}
