package com.aicode.feature.workspace.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.WorkspaceDiscoveryMode
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.plugin.runtime.PluginExecutionBackendAdapter
import com.aicode.feature.plugin.runtime.PluginWorkspaceRegistry
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.UriPathResolver
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workspaceDataStore by preferencesDataStore(
    name = "workspace_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 管理 App 内的"工作区/项目"。
 *
 * **本地模式**：所有项目放在内部私有目录 `filesDir/projects/<name>` 下——ext4 真实路径，
 * [java.io.File] 工具与 PRoot 容器挂载都能直接使用，无需运行时存储权限，且支持 symlink。
 *
 * **远程模式**：工作区 = 远程 SSH 服务器上 `remoteWorkspacePath` 下的子文件夹。
 * 列表/新建/删除通过 SFTP 操作远程目录，[Workspace.path] 为远程绝对路径。
 *
 * 当前选中的工作区名持久化在 DataStore 中，重启后保留（本地/远程共用同一份名字）。
 */
@Singleton
class WorkspaceRepository internal constructor(
    private val context: Context,
    private val executionBackendRegistry: ExecutionBackendRegistry,
    private val pluginWorkspaceRegistry: PluginWorkspaceRegistry,
    private val remoteSshConnection: RemoteSshConnection,
    private val pathHomeResolver: PathHomeResolver,
    private val sessionUseCase: SessionUseCase,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val credentialInjectSettings: CredentialInjectSettingsRepository,
    private val credentialRepository: CredentialRepository,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        executionBackendRegistry: ExecutionBackendRegistry,
        pluginWorkspaceRegistry: PluginWorkspaceRegistry,
        remoteSshConnection: RemoteSshConnection,
        pathHomeResolver: PathHomeResolver,
        sessionUseCase: SessionUseCase,
        generalSettingsRepository: GeneralSettingsRepository,
        credentialInjectSettings: CredentialInjectSettingsRepository,
        credentialRepository: CredentialRepository
    ) : this(
        context = context,
        executionBackendRegistry = executionBackendRegistry,
        pluginWorkspaceRegistry = pluginWorkspaceRegistry,
        remoteSshConnection = remoteSshConnection,
        pathHomeResolver = pathHomeResolver,
        sessionUseCase = sessionUseCase,
        generalSettingsRepository = generalSettingsRepository,
        credentialInjectSettings = credentialInjectSettings,
        credentialRepository = credentialRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    @Volatile
    private var initialized = false

    private val refreshMutex = Mutex()
    /** 校验失败时给 UI 的提示文案（如目录不可写/无法解析），消费后置 null。 */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** UI 消费添加失败提示后调用，清除待展示的文案。 */
    fun consumeAddError() {
        _addError.value = null
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val DEFAULT_WORKSPACE = "default"
        private val json = Json { ignoreUnknownKeys = true }

        /** 外部工作区记录 → 工作区列表；[isDir] 判定目录当前是否存在，决定 available。 */
        internal fun mapExternalWorkspaces(
            records: List<ExternalWorkspaceRecord>,
            isDir: (String) -> Boolean
        ): List<Workspace> = records.map { rec ->
            Workspace(
                name = rec.name,
                path = rec.path,
                type = WorkspaceType.EXTERNAL_LOCAL,
                available = isDir(rec.path)
            )
        }

        /** 生成不与现有工作区重名的目录名，冲突时追加 " (n)" 后缀。 */
        internal fun uniqueName(base: String, existing: Set<String>): String {
            if (base !in existing) return base
            var i = 2
            while ("$base ($i)" in existing) i++
            return "$base ($i)"
        }

        /** 合并主动扫描与插件注册工作区：按 [Workspace.id] 去重，注册项优先覆盖同 ID 扫描项。 */
        internal fun mergeWorkspaces(
            scanned: List<Workspace>,
            registered: List<Workspace>
        ): List<Workspace> {
            if (registered.isEmpty()) return scanned
            if (scanned.isEmpty()) return registered
            val merged = LinkedHashMap<String, Workspace>(scanned.size + registered.size)
            scanned.forEach { ws -> merged[ws.id] = ws }
            registered.forEach { ws -> merged[ws.id] = ws }
            return merged.values.toList()
        }
    }

    private val currentNameKey = stringPreferencesKey("current_workspace_name")

    /**
     * 当前工作区名/ID 按执行后端分开存：内置本地沿用原 key，内置远程按服务器（host:port）单独存，
     * 插件后端按 `backend.id` 单独存，避免切换后端后互相覆盖。
     */
    private fun currentNameKeyForMode(
        backend: ExecutionBackend = activeBackend()
    ): androidx.datastore.preferences.core.Preferences.Key<String> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> currentNameKey
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> {
            val cfg = remoteSshConnection.config
            val suffix = if (cfg != null) "${cfg.host}:${cfg.port}" else ""
            stringPreferencesKey("current_workspace_name_remote_$suffix")
        }
        else -> stringPreferencesKey("current_workspace_id_${backend.id}")
    }
    private val externalWarningDismissedKey = booleanPreferencesKey("external_workspace_warning_dismissed")
    val externalWarningDismissed = context.workspaceDataStore.data
        .map { it[externalWarningDismissedKey] ?: false }

    suspend fun setExternalWarningDismissed(dismissed: Boolean) {
        context.workspaceDataStore.edit { it[externalWarningDismissedKey] = dismissed }
    }

    /** 外部本地工作区列表（用户所选设备目录），JSON 序列化持久化，重启保留。 */
    private val externalWorkspacesKey = stringPreferencesKey("external_workspaces")

    /**
     * 所有项目的父目录，固定用内部 filesDir（app 私有 ext4）。
     *
     * 必须是 ext4：外部私有目录（getExternalFilesDir）落在 emulated/FUSE 存储，内核拒绝
     * symlink()，npm/pnpm/yarn/git 建软链时会 `EACCES symlink` 而失败。filesDir 是 ext4，
     * symlink 原生可用，所有工具链零配置即可跑。对外可见性由 DocumentsProvider 暴露，不依赖物理位置。
     */
    private val projectsRoot: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /** 移除外部本地工作区时是否一并删除其聊天记录（供删除确认文案判断）。 */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        generalSettingsRepository.deleteExternalWorkspaceSessionsFlow

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    /** 远程工作区初始化失败（根路径/默认工作区创建失败）的提示文案；UI 消费后置 null。 */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    /** UI 消费错误提示后调用，清除待展示的文案。 */
    fun consumeInitError() {
        _initError.value = null
    }

    init {
        scope.launch {
            pluginWorkspaceRegistry.workspacesByBackend
                .drop(1)
                .collect {
                    handlePluginWorkspacesChanged()
                }
        }
    }

    /** 扫描并恢复上次选中的工作区；首次启动按后端发现模式决定是否创建默认工作区。应在 App/ViewModel 启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val backend = activeBackend()
        if (isBuiltinRemoteSsh(backend)) {
            waitForConnection()
            ensureRemoteWorkspaceRoot()
        }
        // 清掉上一个模式/后端遗留的当前工作区：它不属于本后端的列表，留着会让下面的可达性校验
        // 误判为“当前工作区失效”而回退，并把本后端已保存的选择覆盖掉。
        _current.value = null
        initialized = true
        refreshWorkspaces(backend)

        if (_workspaces.value.none { it.available }) {
            when (backend.capabilities.workspaceDiscovery) {
                WorkspaceDiscoveryMode.REGISTER -> {
                    // REGISTER 模式下列表暂时为空时等待插件主动推送，不强行创建本地 fallback 目录
                }
                WorkspaceDiscoveryMode.SCAN, WorkspaceDiscoveryMode.HYBRID -> {
                    when {
                        isBuiltinLocal(backend) -> createLocalFallbackWorkspace()
                        isBuiltinRemoteSsh(backend) -> {
                            val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                            val created = createWorkspace(fallbackName)
                            refreshWorkspaces(backend)
                            if (created == null && remoteSshConnection.isConnected()) {
                                _initError.value = context.getString(R.string.workspace_remote_default_create_failed)
                            }
                        }
                        else -> {
                            val adapter = backend as? PluginExecutionBackendAdapter
                            if (adapter != null && adapter.definition.handlers.workspaceCreate != null) {
                                val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                                val created = runCatching { adapter.createWorkspace(fallbackName) }.getOrNull()
                                if (created != null) {
                                    refreshWorkspaces(backend)
                                }
                            }
                        }
                    }
                }
            }
        }

        val target = restoreTargetWorkspace(backend)
        _current.value = target
        val location = when {
            isBuiltinLocal(backend) -> projectsRoot.absolutePath
            isBuiltinRemoteSsh(backend) -> remoteSshConnection.config?.remoteWorkspacePath ?: ""
            else -> backend.displayName
        }
        FileLogger.i(TAG, "工作区初始化完成，后端: ${backend.id}，当前: ${target?.name}，根目录: $location")
        onWorkspaceActivated(backend, target)
    }

    private suspend fun restoreTargetWorkspace(backend: ExecutionBackend): Workspace? {
        val savedKey = context.workspaceDataStore.data.first()[currentNameKeyForMode(backend)]
        val saved = savedKey?.let { key ->
            _workspaces.value.firstOrNull { it.id == key || it.name == key }
        }
        val target = saved?.takeIf { it.available }
            ?: _workspaces.value.firstOrNull { it.available }
        if (saved != null && !saved.available) {
            _initError.value = target?.let {
                context.getString(R.string.workspace_unavailable_fallback, saved.name, it.name)
            } ?: context.getString(R.string.workspace_unavailable_current, saved.name)
            FileLogger.w(TAG, "上次工作区不可用，回退: ${saved.name} -> ${target?.name}")
        }
        return target
    }

    private suspend fun onWorkspaceActivated(backend: ExecutionBackend, target: Workspace?) {
        if (target == null) return
        when {
            isBuiltinRemoteSsh(backend) -> {
                remoteSshConnection.updateWorkspaceSymlink(target.path)
                syncRemoteCredentials(target.path)
            }
            !isBuiltinLocal(backend) -> {
                (backend as? PluginExecutionBackendAdapter)?.let { adapter ->
                    runCatching { adapter.selectWorkspace(target) }
                        .onFailure { FileLogger.w(TAG, "通知插件切换工作区失败: ${backend.id}", it) }
                }
            }
        }
    }

    private suspend fun syncRemoteCredentials(workspacePath: String) {
        val config = remoteSshConnection.config ?: return
        val key = credentialInjectSettings.serverKey(config.host, config.port)
        if (!credentialInjectSettings.isAutoInjectEnabled(key)) return
        remoteSshConnection.uploadGitCredentialConfig(credentialRepository.getAll().first(), workspacePath)
    }

    private fun activeBackend(): ExecutionBackend = executionBackendRegistry.currentBackend()

    private fun isBuiltinLocal(backend: ExecutionBackend = activeBackend()): Boolean =
        backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID

    private fun isBuiltinRemoteSsh(backend: ExecutionBackend = activeBackend()): Boolean =
        backend.id == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID

    private fun persistedValueFor(backend: ExecutionBackend, workspace: Workspace): String =
        if (isBuiltinLocal(backend) || isBuiltinRemoteSsh(backend)) workspace.name else workspace.id

    /** 远程模式下等待 SSH 连接就绪（最多 5 秒），避免启动时序竞争。 */
    /** 远程模式下挂起等待 SSH 连接就绪（CONNECTED）；连接失败（FAILED）则提前返回，保持空工作区。 */
    private suspend fun waitForConnection() {
        val state = remoteSshConnection.connectionState.first {
            it == ConnectionState.CONNECTED || it == ConnectionState.FAILED
        }
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    /** 远程模式：确保 remoteWorkspacePath 存在（用户填写的路径可能尚不存在），不存在则 mkdir -p 创建。
     *  @return 是否成功；连接不可用时不提示直接返回 false。 */
    private suspend fun ensureRemoteWorkspaceRoot(): Boolean {
        val cfg = remoteSshConnection.config ?: return false
        if (!remoteSshConnection.isConnected()) return false
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        if (wsRoot.isEmpty()) return false
        val exit = execRemoteExit("mkdir -p ${shellQuote(wsRoot)}")
        if (exit != 0) {
            FileLogger.w(TAG, "创建远程工作区根目录失败: $wsRoot (exit=$exit)")
            _initError.value = context.getString(R.string.workspace_remote_root_create_failed)
            return false
        }
        return true
    }

    /** 重新扫描工作区可用性（打开面板、拔插存储后调用），失联项置灰并在必要时回退当前工作区。 */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        refreshWorkspaces(activeBackend())
    }

    private suspend fun handlePluginWorkspacesChanged() {
        val backend = activeBackend()
        val discovery = backend.capabilities.workspaceDiscovery
        if (discovery != WorkspaceDiscoveryMode.REGISTER && discovery != WorkspaceDiscoveryMode.HYBRID) {
            return
        }
        refreshMutex.withLock {
            _workspaces.value = discoverWorkspaces(backend)
            if (_current.value != null) {
                ensureCurrentReachable(backend)
            } else if (initialized) {
                val target = restoreTargetWorkspace(backend)
                if (target != null) {
                    _current.value = target
                    onWorkspaceActivated(backend, target)
                }
            }
        }
    }

    /** 重新读取工作区目录列表。按后端发现模式（SCAN / REGISTER / HYBRID）获取并校验当前选中项。 */
    private suspend fun refreshWorkspaces(backend: ExecutionBackend = activeBackend()) {
        refreshMutex.withLock {
            _workspaces.value = discoverWorkspaces(backend)
            ensureCurrentReachable(backend)
        }
    }

    private suspend fun discoverWorkspaces(backend: ExecutionBackend): List<Workspace> {
        return when (backend.capabilities.workspaceDiscovery) {
            WorkspaceDiscoveryMode.SCAN -> scanWorkspacesFor(backend)
            WorkspaceDiscoveryMode.REGISTER -> pluginWorkspaceRegistry.workspacesFor(backend.id)
            WorkspaceDiscoveryMode.HYBRID -> {
                val scanned = scanWorkspacesFor(backend)
                val registered = pluginWorkspaceRegistry.workspacesFor(backend.id)
                mergeWorkspaces(scanned, registered)
            }
        }
    }

    private suspend fun scanWorkspacesFor(backend: ExecutionBackend): List<Workspace> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> refreshLocalWorkspaces()
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> refreshRemoteWorkspaces()
        else -> (backend as? PluginExecutionBackendAdapter)?.let { adapter ->
            runCatching { adapter.listWorkspaces() }
                .onFailure { FileLogger.w(TAG, "插件工作区扫描失败: ${backend.id}", it) }
                .getOrDefault(emptyList())
        }.orEmpty()
    }

    /**
     * 刷新后校验当前工作区：已被移除或不可用（目录被移动/删除）时回退到第一个可用工作区，
     * 并在实际发生切换时通过 [_initError] 提示用户。
     */
    private suspend fun ensureCurrentReachable(backend: ExecutionBackend = activeBackend()) {
        val current = _current.value ?: return
        val matched = _workspaces.value.firstOrNull { it.id == current.id || it.name == current.name }
        if (matched != null && matched.available) {
            if (matched != current) _current.value = matched
            return
        }
        var fallback = _workspaces.value.firstOrNull { it.available }
        if (fallback == null && isBuiltinLocal(backend)) {
            fallback = createLocalFallbackWorkspaceLocked()
        }
        _current.value = fallback
        context.workspaceDataStore.edit { prefs ->
            val key = currentNameKeyForMode(backend)
            if (fallback != null) {
                prefs[key] = persistedValueFor(backend, fallback)
            } else {
                prefs.remove(key)
            }
        }
        if (fallback != null && fallback.id != current.id && fallback.name != current.name) {
            _initError.value = context.getString(R.string.workspace_unavailable_fallback, current.name, fallback.name)
        }
        if (fallback != null) {
            onWorkspaceActivated(backend, fallback)
        }
        FileLogger.w(TAG, "当前工作区不可用，回退: ${current.name} -> ${fallback?.name}")
    }

    private suspend fun createLocalFallbackWorkspace(): Workspace? = refreshMutex.withLock {
        createLocalFallbackWorkspaceLocked()
    }

    private suspend fun createLocalFallbackWorkspaceLocked(): Workspace? {
        val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
        val fallbackDir = File(projectsRoot, fallbackName)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) return null
        _workspaces.value = refreshLocalWorkspaces()
        return _workspaces.value.firstOrNull { it.name == fallbackName && it.available }
    }

    private suspend fun refreshLocalWorkspaces(): List<Workspace> {
        val internal = projectsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Workspace(name = it.name, path = it.absolutePath) }
            ?: emptyList()
        // 外部本地工作区：失联（目录被移动/删除）时保留记录并标记不可用，目录恢复后自动重新可用
        val external = mapExternalWorkspaces(readExternalWorkspaces()) { File(it).isDirectory }
        return internal + external
    }

    /** exec 列出 remoteWorkspacePath 下的子目录作为工作区（不用 SFTP，避免 sshj Buffer bug）。 */
    private suspend fun refreshRemoteWorkspaces(): List<Workspace> {
        val cfg = remoteSshConnection.config ?: run {
            FileLogger.w(TAG, "远程工作区列表失败：SSH 未配置")
            return emptyList()
        }
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        return runCatching {
            // 目录名可含空格，不能交给 xargs/basename 按空白分词（会把 "new api" 拆成两条），
            // 用 glob 逐项原样输出目录名。
            val output = execRemote(
                "cd ${shellQuote(wsRoot)} 2>/dev/null || exit 0; " +
                    "for d in */; do [ -d \"\$d\" ] && printf '%s\\n' \"\${d%/}\"; done"
            )
            if (output.isBlank()) emptyList()
            else output.lines().filter { it.isNotBlank() }
                .sortedBy { it.lowercase() }
                .map {
                    Workspace(
                        name = it,
                        path = "$wsRoot/$it",
                        type = WorkspaceType.REMOTE,
                        backendId = ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
                    )
                }
        }.getOrElse {
            FileLogger.w(TAG, "远程工作区列表失败: $wsRoot", it)
            emptyList()
        }
    }

    /** 同步执行远程命令并返回 stdout（供工作区列表/新建/删除用）。 */
    private suspend fun execRemote(command: String): String =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                val output = java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }

    /** 同步执行远程命令并返回退出码。 */
    private suspend fun execRemoteExit(command: String): Int =
        withContext(Dispatchers.IO) {
            val session = remoteSshConnection.startExecSession(command)
            try {
                java.io.BufferedReader(java.io.InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                -1
            }
        }

    /** 切换当前工作区并持久化（支持按 id 或 name 匹配）。 */
    suspend fun selectWorkspace(nameOrId: String) = withContext(Dispatchers.IO) {
        val backend = activeBackend()
        val target = _workspaces.value.firstOrNull {
            (it.id == nameOrId || it.name == nameOrId) && it.available
        } ?: return@withContext
        _current.value = target
        context.workspaceDataStore.edit {
            it[currentNameKeyForMode(backend)] = persistedValueFor(backend, target)
        }
        FileLogger.i(TAG, "切换工作区: ${target.name} (${target.id})")
        onWorkspaceActivated(backend, target)
    }

    /**
     * 新建工作区目录。名称会被清洗为安全的文件夹名。
     * 本地模式 mkdirs projectsRoot/name；远程模式 mkdirs remoteWorkspacePath/name；插件后端委托给 `workspaceCreate`。
     * @return 创建成功的 [Workspace]；名称非法或已存在返回 null。
     */
    suspend fun createWorkspace(rawName: String): Workspace? = withContext(Dispatchers.IO) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'")
            return@withContext null
        }
        val backend = activeBackend()
        when {
            isBuiltinLocal(backend) -> {
                val dir = File(projectsRoot, name)
                if (dir.exists() || _workspaces.value.any { it.name == name }) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return@withContext null
                }
                if (!dir.mkdirs()) {
                    FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}")
                    return@withContext null
                }
                refreshWorkspaces(backend)
                FileLogger.i(TAG, "新建工作区: $name")
                Workspace(name = name, path = dir.absolutePath)
            }
            isBuiltinRemoteSsh(backend) -> {
                val cfg = remoteSshConnection.config ?: return@withContext null
                val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
                val remotePath = "$wsRoot/$name"
                runCatching {
                    if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                        FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                        return@withContext null
                    }
                    execRemoteExit("mkdir -p ${shellQuote(remotePath)}")
                }.getOrElse {
                    FileLogger.e(TAG, "远程新建工作区失败: $remotePath", it)
                    return@withContext null
                }
                refreshWorkspaces(backend)
                FileLogger.i(TAG, "新建工作区(远程): $name")
                Workspace(
                    name = name,
                    path = remotePath,
                    type = WorkspaceType.REMOTE,
                    backendId = ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID
                )
            }
            else -> {
                val adapter = backend as? PluginExecutionBackendAdapter ?: return@withContext null
                val created = runCatching { adapter.createWorkspace(name) }
                    .onFailure { FileLogger.e(TAG, "插件新建工作区失败: ${backend.id} / $name", it) }
                    .getOrNull()
                    ?: return@withContext null
                if (backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.REGISTER) {
                    pluginWorkspaceRegistry.upsert(backend.id, listOf(created))
                }
                refreshWorkspaces(backend)
                FileLogger.i(TAG, "新建工作区(插件): ${created.name} (${backend.id})")
                created
            }
        }
    }

    /**
     * 注册用户所选设备目录为外部本地工作区（双向直读该目录，不复制）。
     * 校验：能解析为真实路径、是目录、可写。与现有工作区同名时自动加后缀。
     * @return 注册成功的 [Workspace]；校验失败返回 null（同时通过 [addError] 给出提示文案）。
     */
    suspend fun addExternalWorkspace(uri: Uri): Workspace? = withContext(Dispatchers.IO) {
        val uriFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val path = UriPathResolver.toFilePath(context, uri)
        if (path == null) {
            _addError.value = context.getString(R.string.workspace_external_unresolvable)
            FileLogger.w(TAG, "添加本地工作区失败：无法解析 uri")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        // 实际读写走 targetSdk 28 的 legacy storage + java.io.File；SAF grant 仅记录目录选择授权，不是文件后端。
        if (path.contains(':')) {
            _addError.value = context.getString(R.string.workspace_external_unsupported_path)
            FileLogger.w(TAG, "添加本地工作区失败：路径包含不支持的冒号 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val dir = File(path)
        val existing = readExternalWorkspaces()
        if (existing.any { it.path == path }) {
            _addError.value = context.getString(R.string.workspace_external_exists)
            FileLogger.w(TAG, "添加本地工作区失败：目录已是工作区 $path")
            return@withContext null
        }
        if (!dir.isDirectory) {
            _addError.value = context.getString(R.string.workspace_external_invalid)
            FileLogger.w(TAG, "添加本地工作区失败：不是目录 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val writable = runCatching {
            val probe = File.createTempFile(".aicode_write_probe_", ".tmp", dir)
            try {
                probe.writeText("ok")
                if (!probe.delete()) error("probe delete failed")
                true
            } catch (e: Exception) {
                runCatching { probe.delete() }
                throw e
            }
        }.getOrDefault(false)
        if (!writable) {
            _addError.value = context.getString(R.string.workspace_external_unwritable)
            FileLogger.w(TAG, "添加本地工作区失败：目录不可写 $path")
            releaseUriGrant(uri, uriFlags)
            return@withContext null
        }
        val allNames = (_workspaces.value.map { it.name } + existing.map { it.name }).toSet()
        val baseName = sanitize(dir.name).ifBlank { DEFAULT_WORKSPACE }
        val name = uniqueName(baseName, allNames)
        writeExternalWorkspaces(existing + ExternalWorkspaceRecord(name, path, uri.toString()))
        refreshWorkspaces()
        val created = _workspaces.value.firstOrNull { it.path == path }
        FileLogger.i(TAG, "添加本地工作区: $name ($path)")
        created
    }

    private fun releaseUriGrant(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** 删除工作区。内部/远程工作区连同文件与会话记录删除；外部本地工作区只解除关联，不删除物理目录，
     *  其会话记录是否一并删除由「偏好设置」中的开关决定。
     *  若删的是当前工作区，则自动切到剩余的第一个。 */
    suspend fun deleteWorkspace(nameOrId: String) = withContext(Dispatchers.IO) {
        val backend = activeBackend()
        val target = _workspaces.value.firstOrNull { it.id == nameOrId || it.name == nameOrId }
        when {
            isBuiltinLocal(backend) && target?.type == WorkspaceType.EXTERNAL_LOCAL -> {
                val records = readExternalWorkspaces()
                val removed = records.firstOrNull { it.name == target.name }
                writeExternalWorkspaces(records.filterNot { it.name == target.name })
                removed?.let { record ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(record.uri),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                if (generalSettingsRepository.deleteExternalWorkspaceSessions()) {
                    sessionUseCase.deleteSessionsByWorkspace(target.path)
                }
                FileLogger.i(TAG, "移除外部工作区关联: ${target.name}")
            }
            isBuiltinLocal(backend) -> {
                val dirName = target?.name ?: nameOrId
                File(projectsRoot, dirName).deleteRecursively()
                target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
            }
            isBuiltinRemoteSsh(backend) -> {
                val dirName = target?.name ?: nameOrId
                val cfg = remoteSshConnection.config
                if (cfg != null) {
                    val remotePath = "${pathHomeResolver.expandHome(cfg.remoteWorkspacePath).trimEnd('/')}/$dirName"
                    runCatching { execRemoteExit("rm -rf ${shellQuote(remotePath)}") }
                        .onFailure { FileLogger.e(TAG, "远程删除工作区失败: $remotePath", it) }
                }
                target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
            }
            else -> {
                val adapter = backend as? PluginExecutionBackendAdapter
                val targetId = target?.id ?: nameOrId
                val deleted = if (adapter != null) {
                    runCatching { adapter.deleteWorkspace(targetId) }
                        .onFailure { FileLogger.e(TAG, "插件删除工作区失败: ${backend.id} / $targetId", it) }
                        .getOrDefault(false)
                } else {
                    false
                }
                if (deleted) {
                    if (backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.REGISTER ||
                        backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.HYBRID
                    ) {
                        pluginWorkspaceRegistry.remove(backend.id, listOf(targetId))
                    }
                    target?.let { sessionUseCase.deleteSessionsByWorkspace(it.path) }
                }
            }
        }
        refreshWorkspaces(backend)
        FileLogger.i(TAG, "删除工作区: $nameOrId")
    }

    /** 外部本地工作区持久化记录。 */
    @Serializable
    internal data class ExternalWorkspaceRecord(
        val name: String,
        val path: String,
        val uri: String
    )

    private suspend fun readExternalWorkspaces(): List<ExternalWorkspaceRecord> {
        val raw = context.workspaceDataStore.data.first()[externalWorkspacesKey] ?: return emptyList()
        return runCatching { json.decodeFromString<List<ExternalWorkspaceRecord>>(raw) }
            .getOrElse {
                FileLogger.w(TAG, "外部工作区记录解析失败，已忽略", it)
                emptyList()
            }
    }

    private suspend fun writeExternalWorkspaces(list: List<ExternalWorkspaceRecord>) {
        context.workspaceDataStore.edit { it[externalWorkspacesKey] = json.encodeToString(list) }
    }

    /** 单引号转义，保证 shell 命令安全。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前工作区的路径，供 projectRoot / 命令执行目录使用。
     * 本地模式返回宿主工作区绝对路径；远程模式返回选中工作区的远程绝对路径（命令 cd 到此）。
     * 无选中时本地回退到项目根目录，远程回退到配置的 remoteWorkspacePath，插件后端返回空串。 */
    fun currentPath(): String {
        val backend = activeBackend()
        return when {
            isBuiltinLocal(backend) -> _current.value?.path ?: projectsRoot.absolutePath
            isBuiltinRemoteSsh(backend) -> _current.value?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath
                    ?.let { pathHomeResolver.expandHome(it) }
                    ?.takeIf { it.isNotBlank() }
                ?: "/"
            else -> _current.value?.path ?: ""
        }
    }

    /** 仅保留字母数字、下划线、连字符、点和空格，去掉路径分隔符等危险字符。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
