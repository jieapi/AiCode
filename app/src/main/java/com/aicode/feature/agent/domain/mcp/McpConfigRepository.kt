package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** MCP server 的配置作用域：全局（跨项目共享）或项目级（仅当前工作区生效）。 */
enum class McpScope { GLOBAL, PROJECT }

/** 一个生效的 MCP 条目：配置 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class McpServerEntry(
    val server: McpServerConfig,
    val scope: McpScope
)

/**
 * MCP 配置持久化，支持全局 + 项目级两级：
 * - 全局：`filesDir/aicode/mcp.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/mcp.json`（随工作区走，可 git 追踪）。
 *
 * 生效配置 = 全局 + 项目合并，**项目级优先**，同名时项目项覆盖全局项。
 * 并发模式：Mutex 保护文件 IO + MutableStateFlow 缓存，项目级按工作区路径各自缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class McpConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    private companion object {
        const val TAG = "McpConfigRepository"
        const val CONFIG_FILE = "mcp.json"
        const val DEFAULT_JSON = """{"mcpServers":{}}"""
        /** 配置文件轮询间隔：外部直接编辑后约 2s 内刷新。 */
        const val WATCH_POLL_MS = 2000L
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val PRETTY_JSON = Json { prettyPrint = true }
    }

    /** 全局配置文件：`filesDir/aicode/mcp.json`。 */
    private val globalFile: File
        get() = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/mcp.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), CONFIG_FILE)

    private val globalState = MutableStateFlow<String?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑配置文件后，刷新缓存并广播给 McpManager 重连 ──

    /** 配置被外部修改（内容与缓存不一致）时广播一次。 */
    private val _externalChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val externalChanges: SharedFlow<Unit> = _externalChanges.asSharedFlow()

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 文件 mtime+size 快照，用于低成本检测外部修改。 */
    private data class FileStamp(val mtime: Long, val size: Long) {
        companion object {
            fun of(file: File): FileStamp? = runCatching {
                FileStamp(file.lastModified(), file.length())
            }.getOrNull()?.takeIf { it.mtime > 0L }
        }
    }

    @Volatile
    private var globalStamp: FileStamp? = null
    @Volatile
    private var globalWatchingInitialized = false
    private val projectStamps = ConcurrentHashMap<String, FileStamp?>()
    private val initializedProjectPaths = ConcurrentHashMap.newKeySet<String>()

    /**
     * 启动配置文件监听：2s 轮询 mtime，外部修改后刷新缓存并广播 [externalChanges]。
     * App 常驻期间一直运行；内部写入会同步 stamp，不会误触发。幂等可重复调。
     */
    fun startWatching() {
        watchScope.launch {
            while (true) {
                delay(WATCH_POLL_MS)
                runCatching {
                    checkGlobalChanged()
                    checkProjectChanged()
                }
            }
        }
    }

    private suspend fun checkGlobalChanged() {
        val stamp = FileStamp.of(globalFile)
        if (!globalWatchingInitialized) {
            globalStamp = stamp
            globalWatchingInitialized = true
            return
        }
        if (globalStamp == stamp) return
        val content = if (stamp == null) DEFAULT_JSON else load(globalFile)
        globalStamp = stamp
        if (content != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = content
            _externalChanges.tryEmit(Unit)
            FileLogger.i(TAG, "检测到全局 MCP 配置变化，已刷新")
        }
    }

    private suspend fun checkProjectChanged() {
        val path = workspaceRepository.currentPath()
        val file = projectFileForPath(path)
        val stamp = FileStamp.of(file)
        if (!initializedProjectPaths.contains(path)) {
            projectStamps[path] = stamp
            initializedProjectPaths.add(path)
            return
        }
        if (projectStamps[path] == stamp) return
        val content = if (stamp == null) DEFAULT_JSON else load(file)
        projectStamps[path] = stamp
        val state = getProjectState(path)
        if (content != (state.value ?: DEFAULT_JSON)) {
            state.value = content
            _externalChanges.tryEmit(Unit)
            FileLogger.i(TAG, "检测到项目 MCP 配置变化，已刷新")
        }
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<String?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = load(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = load(projectFileForPath(workspacePath))
        }
    }

    private suspend fun load(file: File): String = withContext(Dispatchers.IO) {
        // 只读加载：文件不存在时返回默认配置，不主动创建目录/文件，
        // 避免在 projectsRoot 下误建 .aicode 目录被工作区扫描器当成工作区。
        if (file.isFile) {
            return@withContext runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败，回退默认配置: ${it.message}")
                DEFAULT_JSON
            }
        }
        DEFAULT_JSON
    }

    private fun writeFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** 全局 MCP 配置流。 */
    val globalServersFlow: Flow<List<McpServerConfig>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull().map { parse(it) })
    }

    /**
     * 当前项目生效的 MCP 条目流（全局 + 项目合并，项目优先覆盖同名），
     * 跟随当前工作区切换自动重载对应项目配置。
     */
    val effectiveEntriesFlow: Flow<List<McpServerEntry>> =
        workspaceRepository.current.flatMapLatest {
            val path = workspaceRepository.currentPath()
            ensureGlobalLoaded()
            ensureProjectLoaded(path)
            combine(globalState, getProjectState(path)) { g, p ->
                merge(parse(g ?: DEFAULT_JSON), parse(p ?: DEFAULT_JSON))
            }
        }

    suspend fun getGlobalServers(): List<McpServerConfig> {
        ensureGlobalLoaded()
        return parse(globalState.value ?: DEFAULT_JSON)
    }

    suspend fun getProjectServers(): List<McpServerConfig> {
        val path = workspaceRepository.currentPath()
        ensureProjectLoaded(path)
        return parse(getProjectState(path).value ?: DEFAULT_JSON)
    }

    suspend fun setGlobalServers(servers: List<McpServerConfig>) {
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(globalFile, json) }
            globalState.value = json
            globalStamp = FileStamp.of(globalFile)
        }
    }

    suspend fun setProjectServers(servers: List<McpServerConfig>) {
        val path = workspaceRepository.currentPath()
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(projectFileForPath(path), json) }
            getProjectState(path).value = json
            projectStamps[path] = FileStamp.of(projectFileForPath(path))
        }
    }

    /** 当前项目生效的合并配置（项目优先覆盖同名），供 [McpManager] 连接使用。 */
    suspend fun getEffectiveServers(): List<McpServerConfig> =
        getEffectiveEntries().map { it.server }

    /** 当前项目生效的合并条目（含来源作用域），供设置页列表标注使用。 */
    suspend fun getEffectiveEntries(): List<McpServerEntry> {
        val path = workspaceRepository.currentPath()
        ensureGlobalLoaded()
        ensureProjectLoaded(path)
        return merge(
            parse(globalState.value ?: DEFAULT_JSON),
            parse(getProjectState(path).value ?: DEFAULT_JSON)
        )
    }

    fun serialize(servers: List<McpServerConfig>): String {
        val serversObj = buildJsonObject {
            servers.forEach { server ->
                putJsonObject(server.name) {
                    if (server.isStdio) {
                        put("command", server.command)
                        if (server.args.isNotEmpty()) {
                            putJsonArray("args") { server.args.forEach { add(it) } }
                        }
                        if (server.env.isNotEmpty()) {
                            putJsonObject("env") {
                                server.env.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    } else {
                        put("url", server.url ?: "")
                        if (server.headers.isNotEmpty()) {
                            putJsonObject("headers") {
                                server.headers.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    }
                    put("enabled", server.enabled)
                    if (server.disabledTools.isNotEmpty()) {
                        putJsonArray("disabledTools") { server.disabledTools.forEach { add(it) } }
                    }
                }
            }
        }
        val root = buildJsonObject { put("mcpServers", serversObj) }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    fun parse(raw: String): List<McpServerConfig> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(TAG, "MCP 配置 JSON 解析失败: ${it.message}")
            return emptyList()
        }
        val servers = (root["mcpServers"] as? JsonObject) ?: return emptyList()

        return servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

            val command = (obj["command"] as? JsonPrimitive)?.contentOrNull
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull

            val disabledTools = (obj["disabledTools"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.toSet() ?: emptySet()

            when {
                !command.isNullOrBlank() -> {
                    val args = (obj["args"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: emptyList()
                    val env = (obj["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        command = command,
                        args = args,
                        env = env,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                !url.isNullOrBlank() -> {
                    val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        url = url,
                        headers = headers,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                else -> {
                    // 既无 url 也无 command，无法识别，跳过。
                    FileLogger.i(TAG, "跳过无法识别的 MCP server（缺 url/command）: $name")
                    null
                }
            }
        }
    }

    /** 合并全局与项目配置：全局按序在前，项目项覆盖同名，顺序 = 全局序 + 项目新增项。 */
    private fun merge(
        global: List<McpServerConfig>,
        project: List<McpServerConfig>
    ): List<McpServerEntry> {
        val byName = LinkedHashMap<String, McpServerEntry>()
        global.forEach { byName[it.name] = McpServerEntry(it, McpScope.GLOBAL) }
        project.forEach { byName[it.name] = McpServerEntry(it, McpScope.PROJECT) }
        return byName.values.toList()
    }
}