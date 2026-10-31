package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val category: String = "通用",
    val savedParams: Map<String, String> = emptyMap()
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
    private val pluginManager: PluginManager
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tools = MutableStateFlow<List<ProjectToolItem>>(emptyList())
    val tools: StateFlow<List<ProjectToolItem>> = _tools.asStateFlow()

    init {
        scope.launch { reload() }
        // 当插件启停或安装卸载时，自动对账更新
        scope.launch {
            pluginManager.plugins.collect { reload() }
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
        _tools.value = loaded
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

    /** 保存工具配置的参数。 */
    suspend fun updateParams(toolKey: String, params: Map<String, Any>) = withContext(Dispatchers.IO) {
        val current = _tools.value.map { item ->
            if (item.toolKey == toolKey) {
                val strParams = params.mapValues { (_, v) ->
                    if (v is List<*>) v.filterNotNull().joinToString(",") else v.toString()
                }
                item.copy(savedParams = strParams)
            } else item
        }
        save(current)
    }

    private fun save(items: List<ProjectToolItem>) {
        runCatching {
            val file = configFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ProjectToolsFile(items)))
            _tools.value = items
        }.onFailure {
            FileLogger.e(TAG, "写入 tools.json 失败", it)
        }
    }

    companion object {
        private const val TAG = "ProjectToolsRepo"
        private const val CONFIG_FILE = "tools.json"
    }
}
