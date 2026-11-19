package com.aicode.feature.agent.domain.container

import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Lazy
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内置本地 PRoot 容器后端（`id = "builtin:local-proot"`）。
 *
 * 使用 [Lazy] 延迟解析具体引擎与文件/终端提供方，避免与 `ExecutionModeHolder` / `WorkspaceRepository`
 * 形成 Hilt 单例初始化环。
 */
@Singleton
class BuiltinLocalProotBackend @Inject constructor(
    private val localEngine: Lazy<LinuxContainerEngine>,
    private val localFileAccess: Lazy<LocalFileAccess>,
    private val localTerminalManager: Lazy<TerminalSessionManager>
) : ExecutionBackend {

    constructor(
        localEngine: LinuxContainerEngine,
        localFileAccess: LocalFileAccess,
        localTerminalManager: TerminalSessionManager
    ) : this(
        Lazy { localEngine },
        Lazy { localFileAccess },
        Lazy { localTerminalManager }
    )

    override val id: String = ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID
    override val displayName: String = "本地容器（PRoot）"
    override val capabilities: BackendCapabilities = CAPABILITIES

    override fun environmentDescription(): String = displayName

    override fun environmentCacheKey(): String = "local"

    override fun workspaceRootDisplay(workspacePath: String): String =
        if (workspacePath.isNotBlank()) "~/workspace" else ""

    override fun projectConfigKey(workspacePath: String): String =
        hashProjectKey(workspacePath, workspacePath)

    override val commandEngine: CommandEngine
        get() = localEngine.get()

    override val fileAccess: FileAccessProvider
        get() = localFileAccess.get()

    override val terminalSessionProvider: TerminalSessionProvider
        get() = localTerminalManager.get()

    companion object {
        val CAPABILITIES = BackendCapabilities(
            filesystem = true,
            command = true,
            terminal = true,
            fileWatch = true,
            portForward = false,
            isRemoteEnvironment = false,
            workspaceDiscovery = WorkspaceDiscoveryMode.SCAN,
            projectConfigLocation = ProjectConfigLocation.WORKSPACE_DIR
        )
    }
}

/**
 * 内置远程 SSH 服务器后端（`id = "builtin:remote-ssh"`）。
 */
@Singleton
class BuiltinRemoteSshBackend @Inject constructor(
    private val remoteEngine: Lazy<RemoteSshEngine>,
    private val remoteFileAccess: Lazy<RemoteSftpFileAccess>,
    private val remoteTerminalManager: Lazy<RemoteTerminalSessionManager>,
    private val remoteSshConnection: RemoteSshConnection
) : ExecutionBackend {

    constructor(
        remoteEngine: RemoteSshEngine,
        remoteFileAccess: RemoteSftpFileAccess,
        remoteTerminalManager: RemoteTerminalSessionManager,
        remoteSshConnection: RemoteSshConnection
    ) : this(
        Lazy { remoteEngine },
        Lazy { remoteFileAccess },
        Lazy { remoteTerminalManager },
        remoteSshConnection
    )

    override val id: String = ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
    override val displayName: String = "远程 SSH 服务器"
    override val capabilities: BackendCapabilities = CAPABILITIES

    override fun environmentDescription(): String = displayName

    override fun environmentCacheKey(): String {
        val config = remoteSshConnection.config ?: return "remote:disconnected"
        return listOf(
            config.host,
            config.port.toString(),
            config.username,
            remoteSshConnection.remoteHome.orEmpty(),
            config.remoteWorkspacePath
        ).joinToString("\u0000")
    }

    override fun workspaceRootDisplay(workspacePath: String): String =
        if (workspacePath.isNotBlank()) "~/workspace" else ""

    override fun projectConfigKey(workspacePath: String): String {
        val cfg = remoteSshConnection.config
        val identity = if (cfg != null) "${cfg.host}:${cfg.port}:$workspacePath" else workspacePath
        return hashProjectKey(workspacePath, identity)
    }

    override val commandEngine: CommandEngine
        get() = remoteEngine.get()

    override val fileAccess: FileAccessProvider
        get() = remoteFileAccess.get()

    override val terminalSessionProvider: TerminalSessionProvider
        get() = remoteTerminalManager.get()

    companion object {
        val CAPABILITIES = BackendCapabilities(
            filesystem = true,
            command = true,
            terminal = true,
            fileWatch = false,
            portForward = true,
            isRemoteEnvironment = true,
            workspaceDiscovery = WorkspaceDiscoveryMode.SCAN,
            projectConfigLocation = ProjectConfigLocation.APP_PRIVATE_DIR
        )
    }
}

/**
 * 全局执行后端注册表：维护内置与插件动态注册的 [ExecutionBackend] 集合及当前激活后端。
 *
 * 切换未知或已卸载的后端 ID 时安全回退到 [BUILTIN_LOCAL_PROOT_ID]。
 */
@Singleton
class ExecutionBackendRegistry @Inject constructor(
    private val localProotBackend: BuiltinLocalProotBackend,
    private val remoteSshBackend: BuiltinRemoteSshBackend
) {
    private val lock = Any()
    private val registered = LinkedHashMap<String, ExecutionBackend>().apply {
        put(localProotBackend.id, localProotBackend)
        put(remoteSshBackend.id, remoteSshBackend)
    }

    private val _backends = MutableStateFlow(registered.values.toList())
    val backends: StateFlow<List<ExecutionBackend>> = _backends.asStateFlow()

    private val _activeBackendId = MutableStateFlow(BUILTIN_LOCAL_PROOT_ID)
    val activeBackendId: StateFlow<String> = _activeBackendId.asStateFlow()

    private val _activeBackend = MutableStateFlow<ExecutionBackend>(localProotBackend)
    val activeBackend: StateFlow<ExecutionBackend> = _activeBackend.asStateFlow()

    fun currentBackend(): ExecutionBackend = _activeBackend.value

    /**
     * 切换当前激活后端；找不到 [id] 时回退到 [BUILTIN_LOCAL_PROOT_ID] 并返回 false。
     */
    fun setActiveBackend(id: String): Boolean = synchronized(lock) {
        val target = registered[id]
        if (target != null) {
            _activeBackendId.value = target.id
            _activeBackend.value = target
            true
        } else {
            _activeBackendId.value = BUILTIN_LOCAL_PROOT_ID
            _activeBackend.value = localProotBackend
            false
        }
    }

    fun updateBuiltinInstance(backendId: String, identity: String) = synchronized(lock) {
        val base = when (backendId) {
            BUILTIN_LOCAL_PROOT_ID -> localProotBackend
            BUILTIN_REMOTE_SSH_ID -> remoteSshBackend
            else -> return@synchronized
        }
        val instanceId = "$backendId:$identity"
        if (registered[backendId]?.instanceId == instanceId) return@synchronized
        registered[backendId] = object : ExecutionBackend by base {
            override val instanceId: String = instanceId
            override fun environmentCacheKey(): String = instanceId
            override fun projectConfigKey(workspacePath: String): String =
                hashProjectKey(workspacePath, "$instanceId\u0000$workspacePath")
        }
        publishLocked()
    }

    /** 根据 ID 查找已注册后端。 */
    fun find(id: String): ExecutionBackend? = synchronized(lock) { registered[id] }

    /** 注册或更新一个后端（内置后端 ID 不可被插件覆盖）。 */
    fun register(backend: ExecutionBackend) = synchronized(lock) {
        if (backend.id in BUILTIN_IDS &&
            backend !== localProotBackend &&
            backend !== remoteSshBackend
        ) {
            return@synchronized
        }
        registered[backend.id] = backend
        publishLocked()
    }

    /** 注销指定非内置后端；若当前正激活该后端则回退到本地 PRoot。 */
    fun unregister(id: String) = synchronized(lock) {
        if (id in BUILTIN_IDS) return@synchronized
        if (registered.remove(id) != null) {
            publishLocked()
        }
    }

    /** 对齐指定插件声明的全部执行后端（移除不再声明的，注册/更新当前声明的）。 */
    fun syncPluginBackends(pluginId: String, pluginBackends: List<ExecutionBackend>) = synchronized(lock) {
        val prefix = "$pluginId:"
        val wantedIds = pluginBackends.map { it.id }.toSet()
        registered.keys.toList()
            .filter { it.startsWith(prefix) && it !in wantedIds }
            .forEach { registered.remove(it) }
        pluginBackends.forEach { backend ->
            if (backend.id.startsWith(prefix) && backend.id !in BUILTIN_IDS) {
                registered[backend.id] = backend
            }
        }
        publishLocked()
    }

    /** 移除指定插件注册的全部执行后端；若当前激活项属于该插件则回退到本地 PRoot。 */
    fun unregisterPlugin(pluginId: String) = synchronized(lock) {
        val prefix = "$pluginId:"
        val removed = registered.keys.toList()
            .filter { it.startsWith(prefix) }
            .count { registered.remove(it) != null }
        if (removed > 0) {
            publishLocked()
        }
    }

    /** 当前已注册插件后端涉及的插件 ID 集合。 */
    fun registeredPluginIds(): Set<String> = synchronized(lock) {
        registered.keys
            .filter { it !in BUILTIN_IDS && ':' in it }
            .map { it.substringBefore(':') }
            .toSet()
    }

    private fun publishLocked() {
        _backends.value = registered.values.toList()
        val currentId = _activeBackendId.value
        val active = registered[currentId] ?: localProotBackend.also {
            _activeBackendId.value = it.id
        }
        _activeBackend.value = active
    }

    companion object {
        const val BUILTIN_LOCAL_PROOT_ID = "builtin:local-proot"
        const val BUILTIN_REMOTE_SSH_ID = "builtin:remote-ssh"
        private val BUILTIN_IDS = setOf(BUILTIN_LOCAL_PROOT_ID, BUILTIN_REMOTE_SSH_ID)
    }
}

internal fun hashProjectKey(workspacePath: String, identity: String): String {
    val name = workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "default" }
    val digest = MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8))
    val hash = BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
    return "$name-$hash"
}
