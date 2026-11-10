package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthException
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, NEEDS_AUTH, FAILED, DISABLED }
}

// reloadMutex 串行化重连，避免设置页连点导致并发注册/反注册竞态。
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val oauthManager: McpOAuthManager,
    private val pluginManager: PluginManager
) {
    private companion object {
        const val TAG = "McpManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val activeClients = mutableMapOf<String, McpClient>()
    private val registeredToolNames = mutableSetOf<String>()

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    fun start() {
        // 跟随当前工作区切换自动重载（首帧立即发射当前值，等价启动即重连；
        // 项目切换时合并配置与 stdio 工具的项目路径都会变化，需要重建连接）。
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内自动重载，使新增/删除/启停即时生效。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到 MCP 配置文件外部变更，自动重载")
                reload()
            }
        }
        // 插件安装/启用/更新/卸载时，同步重连插件贡献的 MCP server。
        scope.launch {
            pluginManager.plugins.collect { reload() }
        }
        // 容器 profile 切换：stdio server 的进程跑在旧容器的 rootfs 上，必须重建才能用新容器；
        // HTTP server 不依赖容器，不受影响。drop(1) 跳过启动首帧（reload 已处理）。
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        // 默认容器变化：远程模式下 stdio server 运行在默认容器上，同样需要重建。
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = effectiveServers()
        FileLogger.i(TAG, "重新加载 MCP 配置，共 ${servers.size} 个 server")

        teardown()

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // 先把所有 server 置为「连接中/禁用」，UI 立即有反馈。
        _statuses.value = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // 并行连接所有启用的 server；各自独立失败。
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg ->
                async { connectOne(cfg) }
            }.awaitAll()
        }

        // 合并禁用项与连接结果，保持原始顺序。
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
    }

    private suspend fun connectOne(cfg: McpServerConfig): McpServerStatus {
        val t0 = System.currentTimeMillis()
        return try {
            FileLogger.i(TAG, "[${cfg.name}] 开始连接（${if (cfg.isStdio) "stdio" else "HTTP"}）")
            if (!cfg.isStdio && cfg.auth != null) {
                oauthManager.prepare(cfg)
            }
            val transport = if (cfg.isStdio) {
                // stdio server 跑在「运行时容器」上：本地模式用当前容器，远程 SSH 模式用默认容器。
                // 容器未就绪不自动初始化，直接失败并引导去终端页完成初始化。
                val runtimeProfile = resolveMcpRuntimeProfile()
                containerEngine.notReadyHintFor(runtimeProfile)?.let {
                    throw IllegalStateException(it)
                }
                StdioTransport(
                    serverName = cfg.name,
                    engine = containerEngine,
                    program = cfg.command!!,
                    programArgs = cfg.args,
                    projectPath = workspaceRepository.currentPath(),
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile
                )
            } else {
                StreamableHttpTransport(
                    endpoint = cfg.url.orEmpty(),
                    client = okHttpClient,
                    extraHeaders = cfg.headers,
                    authorizationProvider = { oauthManager.cachedToken(cfg.name) }
                )
            }
            val client = McpClient(serverName = cfg.name, transport = transport)
            client.connect()

            val tools = client.tools.map { McpTool(client, it) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(activeClients) {
                activeClients[cfg.name] = client
                enabledTools.forEach { tool ->
                    toolRegistry.register(tool.name, tool)
                    registeredToolNames.add(tool.name)
                }
            }
            FileLogger.i(TAG, "[${cfg.name}] 连接成功，注册 ${enabledTools.size}/${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: McpAuthRequiredException) {
            FileLogger.i(TAG, "[${cfg.name}] 需要 OAuth 授权（${System.currentTimeMillis() - t0}ms）")
            McpServerStatus(cfg.name, McpServerStatus.State.NEEDS_AUTH)
        } catch (e: CancellationException) {
            // reload 被新的配置/工作区变更取消是正常流程，不当连接失败上报。
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "[${cfg.name}] 连接失败（${System.currentTimeMillis() - t0}ms）", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: emptyList()
        }
    }

    /**
     * 仅重连单个 server（保存/启用开关/编辑刷新用），其他 server 的连接与已注册工具不受影响。
     * 配置中不存在该 name 时静默返回。
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val cfg = effectiveServers().firstOrNull { it.name == name } ?: return@withLock
        teardownServer(name)
        if (_statuses.value.none { it.name == name }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name == name) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(cfg)
    }

    /**
     * 仅重连当前未连接的 server（已连接的跳过），新会话时兜底：manageMcp 新增/删除只改配置不重连，
     * 提示「下次会话生效」；本方法让新会话真正连上未连接的 server，不打断已连接的工具。
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val servers = effectiveServers().filter { it.enabled }
        for (cfg in servers) {
            val connected = synchronized(activeClients) { activeClients.containsKey(cfg.name) }
            if (connected) continue
            reconnectOne(cfg)
        }
    }

    /**
     * 异步兜底重连未连接的 server（不阻塞调用方）。新会话创建不应等待 MCP 就绪：
     * stdio server 首跑可能长时间卡在下载依赖/握手超时，同步等待会让「新建会话」看起来卡死。
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig) {
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(cfg) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** 解析 stdio server 的运行时容器：本地模式用当前 profile，远程 SSH 模式用默认容器。 */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** 当前激活 profile：按 id 从配置列表解析，找不到回退内置 Alpine。 */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 默认容器：设置的 id 且为本地 PRoot 模式；找不到/被删/是远程则回退内置 Alpine。 */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 容器相关配置变化后重建所有 stdio server（HTTP 不依赖容器，不动）。
     * 旧进程钉在旧容器的 rootfs 上，必须 teardown 后才能用新容器拉起；单个 server 失败不影响其它。
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        val servers = effectiveServers().filter { it.enabled && it.isStdio }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "容器配置变化，重建 ${servers.size} 个 stdio MCP server")
        for (cfg in servers) {
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /** 删除 server 时仅断开其连接并反注册其工具，不影响其他 server。 */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        teardownServer(name)
        _statuses.value = _statuses.value.filterNot { it.name == name }
    }

    /**
     * 批量删除：一次持锁断开多个 server（列表多选批量删除）。
     * 逐条调用 [removeServer] 会反复抢 reloadMutex，服务端较多时串行等待明显。
     */
    suspend fun removeServers(names: Collection<String>) = reloadMutex.withLock {
        if (names.isEmpty()) return@withLock
        names.forEach { teardownServer(it) }
        _statuses.value = _statuses.value.filterNot { it.name in names }
    }

    /** 触发某个 server 的 OAuth 授权；成功后重连使其工具可用。 */
    suspend fun authorize(name: String): Result<Unit> {
        val cfg = effectiveServers().firstOrNull { it.name == name }
            ?: return Result.failure(McpOAuthException("未找到 MCP server: $name"))
        return runCatching {
            oauthManager.authorize(cfg)
            reloadServer(name)
        }
    }

    /** 退出登录：清除该 server 的令牌并重连（将再次变为需要授权）。 */
    suspend fun logout(name: String) {
        val cfg = effectiveServers().firstOrNull { it.name == name } ?: return
        oauthManager.logout(cfg)
        reloadServer(name)
    }

    /** 取消某个 server 正在进行的 OAuth 授权。 */
    fun cancelAuthorize(name: String) {
        oauthManager.cancel(name)
    }

    /** 用户配置 + 生效插件贡献；同名时用户配置优先，插件 server 使用 `plugin__` 命名空间。 */
    private suspend fun effectiveServers(): List<McpServerConfig> {
        val userServers = configRepository.getEffectiveServers()
        val userNames = userServers.map { it.name }.toSet()
        val pluginServers = pluginManager.activeMcpServers().map { it.config }
        val conflicts = pluginServers.filter { it.name in userNames }
        if (conflicts.isNotEmpty()) {
            FileLogger.w(TAG, "插件 MCP server 与用户配置同名，跳过插件项: ${conflicts.joinToString { it.name }}")
        }
        return userServers + pluginServers.filter { it.name !in userNames }
    }

    private fun teardownServer(name: String) {
        synchronized(activeClients) {
            val client = activeClients.remove(name) ?: return
            runCatching { client.close() }
            client.tools.forEach { tool ->
                toolRegistry.unregister(tool.name)
                registeredToolNames.remove(tool.name)
            }
        }
    }

    private fun teardown() {
        synchronized(activeClients) {
            registeredToolNames.forEach { toolRegistry.unregister(it) }
            registeredToolNames.clear()
            activeClients.values.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
    }
}
