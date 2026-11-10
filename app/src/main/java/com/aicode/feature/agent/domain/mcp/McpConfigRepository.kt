package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "McpConfigRepository"
        const val CONFIG_FILE = "mcp.json"
        const val DEFAULT_JSON = """{"mcpServers":{}}"""
        /** 项目级配置目录名，容器内即 `~/workspace/.aicode`。 */
        const val AICODE_DIR_NAME = ".aicode"
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

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 配置被外部修改（内容与缓存不一致）时广播一次。订阅驱动：只在有订阅者（McpManager）期间
     * 才由 [FileChangeHub] 监听两个配置文件所在目录，无人订阅时零开销。
     */
    val externalChanges: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
    ).mapNotNull { batch ->
        val globalPath = globalFile.absolutePath
        val projectPath = projectFileForPath(workspaceRepository.currentPath()).absolutePath
        val touched = batch.changes.any { it.hostPath == globalPath || it.hostPath == projectPath }
        // 同目录下其它文件的变更（如 skills.json）不触发重连；内容没真变（如 touch）也不触发。
        if (!touched) null else if (reloadFromDisk()) Unit else null
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * 外部变更后按磁盘现状刷新缓存，内容确实变化时返回 true。项目级只处理当前工作区；
     * 其缓存尚未加载时不广播——首次加载由 [ensureProjectLoaded] 完成，工作区切换不算外部变更。
     */
    private suspend fun reloadFromDisk(): Boolean {
        var changed = false
        val globalContent = load(globalFile)
        if (globalContent != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = globalContent
            changed = true
            FileLogger.i(TAG, "检测到全局 MCP 配置变化，已刷新")
        }
        val path = workspaceRepository.currentPath()
        val state = getProjectState(path)
        if (state.value != null) {
            val content = load(projectFileForPath(path))
            if (content != state.value) {
                state.value = content
                changed = true
                FileLogger.i(TAG, "检测到项目 MCP 配置变化，已刷新")
            }
        }
        return changed
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
        }
    }

    suspend fun setProjectServers(servers: List<McpServerConfig>) {
        val path = workspaceRepository.currentPath()
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(projectFileForPath(path), json) }
            getProjectState(path).value = json
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
                        server.auth?.let { auth ->
                            putJsonObject("auth") {
                                put("type", auth.type)
                                auth.clientId?.takeIf { it.isNotBlank() }?.let { put("clientId", it) }
                                auth.authServer?.takeIf { it.isNotBlank() }?.let { put("authServer", it) }
                                auth.scopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
                                    putJsonArray("scopes") { scopes.forEach { add(it) } }
                                }
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

    fun parse(raw: String): List<McpServerConfig> = McpConfigParser.parse(raw, TAG)

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