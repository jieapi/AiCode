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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
    val savedParams: Map<String, JsonElement> = emptyMap()
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
        // 当插件启停或安装卸载时，自动对账更新
        scope.launch {
            pluginManager.plugins.collect { reload() }
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
                item.copy(savedParams = params.mapValues { (_, value) -> value.toJsonElement() })
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

/** 把持久化的 JSON 值还原成 UI 表单可直接持有的类型。 */
fun Map<String, JsonElement>.toUiValues(): Map<String, Any> =
    mapValues { (_, value) -> value.toUiValue() }

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun JsonElement.toUiValue(): Any = when (this) {
    is JsonNull -> ""
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        longOrNull != null -> longOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> contentOrNull ?: ""
    }

    is JsonArray -> map { it.toUiValue() }
    is JsonObject -> entries.associate { (key, value) -> key to value.toUiValue() }
}
