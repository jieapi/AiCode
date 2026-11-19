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
import com.aicode.feature.workspace.domain.model.WorkspaceRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.combine
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import java.util.UUID
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
 * 当前选择与内置工作区 ID 按后端实例持久化；路径仅用于定位目录。
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

    private class BackendOperation(
        val backend: ExecutionBackend,
        val instanceId: String,
        val generation: Long
    ) {
        val mutex = Mutex()
        var initialized = false
    }

    private val operationLock = Any()
    private var nextGeneration = 0L
    @Volatile
    private var currentOperation = activeBackend().let { BackendOperation(it, it.instanceId, 0L) }

    private fun operation(): BackendOperation = currentOperation

    private suspend fun checkCurrent(operation: BackendOperation) {
        currentCoroutineContext().ensureActive()
        requireCurrent(operation)
    }

    private fun requireCurrent(operation: BackendOperation) {
        val backend = activeBackend()
        if (currentOperation !== operation || operation.generation != currentOperation.generation ||
            backend !== operation.backend || backend.instanceId != operation.instanceId
        ) {
            throw CancellationException("Workspace backend changed")
        }
    }

    private suspend fun publish(operation: BackendOperation, update: () -> Unit) {
        currentCoroutineContext().ensureActive()
        synchronized(operationLock) {
            requireCurrent(operation)
            update()
        }
    }

    private suspend fun <T> backendCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "Workspace backend operation failed", e)
        null
    }
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

    private fun currentWorkspaceKey(operation: BackendOperation) =
        stringPreferencesKey("current_workspace_ref_${operation.instanceId}")
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
                    try {
                        handlePluginWorkspacesChanged()
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
        }
    }

    /** 扫描并恢复上次选中的工作区；首次启动按后端发现模式决定是否创建默认工作区。应在 App/ViewModel 启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val operation = synchronized(operationLock) {
            val backend = activeBackend()
            BackendOperation(backend, backend.instanceId, ++nextGeneration).also {
                currentOperation = it
                _current.value = null
                _workspaces.value = emptyList()
                _initError.value = null
            }
        }
        val backend = operation.backend
        if (isBuiltinRemoteSsh(backend)) waitForConnection(operation)
        refreshAndRestore(operation)
        val target = current.value?.takeIf { it.instanceId == operation.instanceId }
        val location = when {
            isBuiltinLocal(backend) -> projectsRoot.absolutePath
            isBuiltinRemoteSsh(backend) -> remoteSshConnection.config?.remoteWorkspacePath.orEmpty()
            else -> backend.displayName
        }
        FileLogger.i(TAG, "工作区初始化完成，后端: ${backend.id}，当前: ${target?.name}，根目录: $location")
    }

    private suspend fun refreshAndRestore(operation: BackendOperation) {
        val backend = operation.backend
        checkCurrent(operation)
        if (isBuiltinRemoteSsh(backend)) ensureRemoteWorkspaceRoot(operation)
        refreshWorkspaces(operation)

        if (_workspaces.value.none { it.available }) {
            when (backend.capabilities.workspaceDiscovery) {
                WorkspaceDiscoveryMode.REGISTER -> {
                    // REGISTER 模式下列表暂时为空时等待插件主动推送，不强行创建本地 fallback 目录
                }
                WorkspaceDiscoveryMode.SCAN, WorkspaceDiscoveryMode.HYBRID -> {
                    when {
                        isBuiltinLocal(backend) -> createLocalFallbackWorkspace(operation)
                        isBuiltinRemoteSsh(backend) -> {
                            val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                            checkCurrent(operation)
                            val created = createWorkspace(operation, fallbackName)
                            if (created == null && remoteSshConnection.isConnected()) {
                                publish(operation) {
                                    _initError.value = context.getString(R.string.workspace_remote_default_create_failed)
                                }
                            }
                        }
                        else -> {
                            val adapter = backend as? PluginExecutionBackendAdapter
                            if (adapter != null && adapter.definition.handlers.workspaceCreate != null) {
                                val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
                                val created = backendCall { adapter.createWorkspace(fallbackName) }
                                checkCurrent(operation)
                                if (created != null) {
                                    refreshWorkspaces(operation)
                                }
                            }
                        }
                    }
                }
            }
        }

        operation.mutex.withLock {
            checkCurrent(operation)
            if (_current.value == null) restoreAndActivate(operation)
            operation.initialized = true
        }
    }

    private suspend fun restoreAndActivate(operation: BackendOperation) {
        val target = restoreTargetWorkspace(operation)
        onWorkspaceActivated(operation, target)
        publish(operation) { _current.value = target }
    }

    private suspend fun restoreTargetWorkspace(operation: BackendOperation): Workspace? {
        val savedKey = context.workspaceDataStore.data.first()[currentWorkspaceKey(operation)]
        checkCurrent(operation)
        val saved = savedKey?.let { key ->
            _workspaces.value.firstOrNull { it.id == key && it.instanceId == operation.instanceId }
        }
        val target = saved?.takeIf { it.available }
            ?: _workspaces.value.firstOrNull { it.available }
        if (saved != null && !saved.available) {
            publish(operation) {
                _initError.value = target?.let {
                    context.getString(R.string.workspace_unavailable_fallback, saved.name, it.name)
                } ?: context.getString(R.string.workspace_unavailable_current, saved.name)
            }
            FileLogger.w(TAG, "上次工作区不可用，回退: ${saved.name} -> ${target?.name}")
        }
        return target
    }

    private suspend fun onWorkspaceActivated(operation: BackendOperation, target: Workspace?) {
        checkCurrent(operation)
        val backend = operation.backend
        if (target == null || target.instanceId != operation.instanceId) return
        when {
            isBuiltinRemoteSsh(backend) -> {
                remoteSshConnection.updateWorkspaceSymlink(target.path)
                checkCurrent(operation)
                syncRemoteCredentials(operation, target.path)
            }
            !isBuiltinLocal(backend) -> {
                (backend as? PluginExecutionBackendAdapter)?.let { adapter ->
                    backendCall { adapter.selectWorkspace(target) }
                    checkCurrent(operation)
                }
            }
        }
    }

    private suspend fun syncRemoteCredentials(operation: BackendOperation, workspacePath: String) {
        val config = remoteSshConnection.config ?: return
        val key = credentialInjectSettings.serverKey(config.host, config.port)
        if (!credentialInjectSettings.isAutoInjectEnabled(key)) return
        val credentials = credentialRepository.getAll().first()
        checkCurrent(operation)
        remoteSshConnection.uploadGitCredentialConfig(credentials, workspacePath)
    }

    private fun activeBackend(): ExecutionBackend = executionBackendRegistry.currentBackend()

    private fun isBuiltinLocal(backend: ExecutionBackend = activeBackend()): Boolean =
        backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID

    private fun isBuiltinRemoteSsh(backend: ExecutionBackend = activeBackend()): Boolean =
        backend.id == ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID

    private suspend fun waitForConnection(operation: BackendOperation) {
        val state = withTimeoutOrNull(5_000) {
            combine(remoteSshConnection.connectionState, executionBackendRegistry.activeBackend) { state, backend ->
                if (backend !== operation.backend || backend.instanceId != operation.instanceId) {
                    throw CancellationException("Workspace backend changed")
                }
                state
            }.first { it == ConnectionState.CONNECTED || it == ConnectionState.FAILED }
        }
        checkCurrent(operation)
        if (state == ConnectionState.FAILED) {
            FileLogger.w(TAG, "SSH 连接失败，工作区保持空")
        }
    }

    /** 远程模式：确保 remoteWorkspacePath 存在（用户填写的路径可能尚不存在），不存在则 mkdir -p 创建。
     *  @return 是否成功；连接不可用时不提示直接返回 false。 */
    private suspend fun ensureRemoteWorkspaceRoot(operation: BackendOperation): Boolean {
        val cfg = remoteSshConnection.config ?: return false
        if (!remoteSshConnection.isConnected()) return false
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        if (wsRoot.isEmpty()) return false
        val exit = execRemoteExit("mkdir -p ${shellQuote(wsRoot)}")
        checkCurrent(operation)
        if (exit != 0) {
            FileLogger.w(TAG, "创建远程工作区根目录失败: $wsRoot (exit=$exit)")
            publish(operation) { _initError.value = context.getString(R.string.workspace_remote_root_create_failed) }
            return false
        }
        return true
    }

    /** 重新扫描工作区可用性（打开面板、拔插存储后调用），失联项置灰并在必要时回退当前工作区。 */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        val operation = operation()
        val initialized = operation.mutex.withLock { operation.initialized }
        if (initialized) refreshAndRestore(operation)
    }

    private suspend fun handlePluginWorkspacesChanged() {
        val operation = operation()
        val backend = operation.backend
        val discovery = backend.capabilities.workspaceDiscovery
        if (discovery != WorkspaceDiscoveryMode.REGISTER && discovery != WorkspaceDiscoveryMode.HYBRID) {
            return
        }
        operation.mutex.withLock {
            val discovered = discoverWorkspaces(operation)
            publish(operation) { _workspaces.value = discovered }
            if (_current.value != null) {
                ensureCurrentReachable(operation)
            } else if (operation.initialized) {
                restoreAndActivate(operation)
            }
        }
    }

    /** 重新读取工作区目录列表。按后端发现模式（SCAN / REGISTER / HYBRID）获取并校验当前选中项。 */
    private suspend fun refreshWorkspaces(operation: BackendOperation) {
        operation.mutex.withLock {
            checkCurrent(operation)
            val discovered = discoverWorkspaces(operation)
            publish(operation) { _workspaces.value = discovered }
            ensureCurrentReachable(operation)
        }
    }

    private suspend fun discoverWorkspaces(operation: BackendOperation): List<Workspace> {
        checkCurrent(operation)
        val backend = operation.backend
        val discovered = when (backend.capabilities.workspaceDiscovery) {
            WorkspaceDiscoveryMode.SCAN -> scanWorkspacesFor(backend)
            WorkspaceDiscoveryMode.REGISTER -> pluginWorkspaceRegistry.workspacesFor(backend.id)
            WorkspaceDiscoveryMode.HYBRID -> {
                val scanned = scanWorkspacesFor(backend)
                val registered = pluginWorkspaceRegistry.workspacesFor(backend.id)
                mergeWorkspaces(scanned, registered)
            }
        }
        checkCurrent(operation)
        val bound = if (isBuiltinLocal(backend) || isBuiltinRemoteSsh(backend)) {
            bindBuiltinWorkspaceIds(operation, discovered)
        } else discovered.filter { it.id.isNotBlank() }.distinctBy { it.id }
        return bound.map { it.copy(backendId = backend.id, instanceId = operation.instanceId) }
    }

    private fun builtinLocator(workspace: Workspace) = "${workspace.type.name}:${workspace.name}"

    private suspend fun bindBuiltinWorkspaceIds(operation: BackendOperation, workspaces: List<Workspace>): List<Workspace> {
        val key = stringPreferencesKey("workspace_ids_${operation.instanceId}")
        var ids = emptyMap<String, String>()
        context.workspaceDataStore.edit { prefs ->
            checkCurrent(operation)
            val stored = prefs[key]?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
            ids = stored + workspaces.associate {
                val locator = builtinLocator(it)
                locator to (stored[locator] ?: UUID.randomUUID().toString())
            }
            prefs[key] = json.encodeToString(ids)
        }
        checkCurrent(operation)
        return workspaces.map { it.copy(id = ids.getValue(builtinLocator(it))) }
    }

    private suspend fun forgetBuiltinWorkspaceId(operation: BackendOperation, target: Workspace) {
        val key = stringPreferencesKey("workspace_ids_${operation.instanceId}")
        context.workspaceDataStore.edit { prefs ->
            checkCurrent(operation)
            val stored = prefs[key]?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
            prefs[key] = json.encodeToString(stored - builtinLocator(target))
        }
    }

    private suspend fun scanWorkspacesFor(backend: ExecutionBackend): List<Workspace> = when (backend.id) {
        ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID -> refreshLocalWorkspaces()
        ExecutionBackendRegistry.BUILTIN_REMOTE_SSH_ID -> refreshRemoteWorkspaces()
        else -> (backend as? PluginExecutionBackendAdapter)?.let { adapter ->
            backendCall { adapter.listWorkspaces() }.orEmpty()
        }.orEmpty()
    }

    /**
     * 刷新后校验当前工作区：已被移除或不可用（目录被移动/删除）时回退到第一个可用工作区，
     * 并在实际发生切换时通过 [_initError] 提示用户。
     */
    private suspend fun ensureCurrentReachable(operation: BackendOperation) {
        checkCurrent(operation)
        val backend = operation.backend
        val current = _current.value?.takeIf { it.instanceId == operation.instanceId } ?: return
        val matched = _workspaces.value.firstOrNull { it.ref == current.ref }
        if (matched != null && matched.available) {
            if (matched != current) publish(operation) { _current.value = matched }
            return
        }
        var fallback = _workspaces.value.firstOrNull { it.available }
        if (fallback == null && isBuiltinLocal(backend)) {
            fallback = createLocalFallbackWorkspaceLocked(operation)
        }
        publish(operation) { _current.value = fallback }
        context.workspaceDataStore.edit { prefs ->
            checkCurrent(operation)
            val key = currentWorkspaceKey(operation)
            if (fallback != null) {
                prefs[key] = fallback.id
            } else {
                prefs.remove(key)
            }
        }
        checkCurrent(operation)
        if (fallback != null && fallback.ref != current.ref) {
            publish(operation) {
                _initError.value = context.getString(R.string.workspace_unavailable_fallback, current.name, fallback.name)
            }
        }
        if (fallback != null) {
            onWorkspaceActivated(operation, fallback)
        }
        FileLogger.w(TAG, "当前工作区不可用，回退: ${current.name} -> ${fallback?.name}")
    }

    private suspend fun createLocalFallbackWorkspace(operation: BackendOperation): Workspace? = operation.mutex.withLock {
        createLocalFallbackWorkspaceLocked(operation)
    }

    private suspend fun createLocalFallbackWorkspaceLocked(operation: BackendOperation): Workspace? {
        checkCurrent(operation)
        val fallbackName = uniqueName(DEFAULT_WORKSPACE, _workspaces.value.map { it.name }.toSet())
        val fallbackDir = File(projectsRoot, fallbackName)
        if (!fallbackDir.isDirectory && !fallbackDir.mkdirs()) return null
        val discovered = discoverWorkspaces(operation)
        publish(operation) { _workspaces.value = discovered }
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
        val cfg = remoteSshConnection.config ?: return emptyList()
        if (!remoteSshConnection.isConnected()) return emptyList()
        val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
        return runCatching {
            // 目录名可含空格，不能交给 xargs/basename 按空白分词（会把 "new api" 拆成两条），
            // 用 glob 逐项原样输出目录名。
            val output = execRemote(
                "cd ${shellQuote(wsRoot)} 2>/dev/null || exit 0; " +
                    "for d in */; do [ -d \"\$d\" ] && printf '%s\\0' \"\${d%/}\"; done"
            )
            if (output.isEmpty()) emptyList()
            else output.split('\u0000').filter { it.isNotEmpty() }
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

    @Deprecated("Pass WorkspaceRef to keep backend instance identity")
    suspend fun selectWorkspace(id: String) = selectWorkspace(WorkspaceRef(activeBackend().instanceId, id))

    suspend fun selectWorkspace(ref: WorkspaceRef) {
        val operation = operation()
        if (ref.backendInstanceId != operation.instanceId) return
        withContext(Dispatchers.IO) {
            operation.mutex.withLock {
                checkCurrent(operation)
                val target = _workspaces.value.firstOrNull { it.ref == ref && it.available } ?: return@withLock
                onWorkspaceActivated(operation, target)
                context.workspaceDataStore.edit {
                    checkCurrent(operation)
                    it[currentWorkspaceKey(operation)] = target.id
                }
                publish(operation) { _current.value = target }
                FileLogger.i(TAG, "切换工作区: ${target.name} (${target.id})")
            }
        }
    }

    /**
     * 新建工作区目录。名称会被清洗为安全的文件夹名。
     * 本地模式 mkdirs projectsRoot/name；远程模式 mkdirs remoteWorkspacePath/name；插件后端委托给 `workspaceCreate`。
     * @return 创建成功的 [Workspace]；名称非法或已存在返回 null。
     */
    suspend fun createWorkspace(rawName: String): Workspace? {
        val operation = operation()
        val created = withContext(Dispatchers.IO) { createWorkspace(operation, rawName) }
        checkCurrent(operation)
        return created
    }

    private suspend fun createWorkspace(operation: BackendOperation, rawName: String): Workspace? {
        val backend = operation.backend
        checkCurrent(operation)
        val name = sanitize(rawName)
        if (name.isEmpty() || name == "." || name == "..") {
            FileLogger.w(TAG, "新建工作区失败：名称非法 '$rawName'")
            return null
        }
        return when {
            isBuiltinLocal(backend) -> {
                val dir = File(projectsRoot, name)
                if (dir.exists() || _workspaces.value.any { it.name == name }) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return null
                }
                if (!dir.mkdirs()) {
                    FileLogger.e(TAG, "新建工作区失败：无法创建目录 ${dir.absolutePath}")
                    return null
                }
                refreshWorkspaces(operation)
                FileLogger.i(TAG, "新建工作区: $name")
                checkCurrent(operation)
                _workspaces.value.firstOrNull { it.path == dir.absolutePath && it.type == WorkspaceType.INTERNAL }
            }
            isBuiltinRemoteSsh(backend) -> {
                val cfg = remoteSshConnection.config ?: return null
                if (!remoteSshConnection.isConnected()) return null
                val wsRoot = pathHomeResolver.expandHome(cfg.remoteWorkspacePath.trimEnd('/'))
                val remotePath = "$wsRoot/$name"
                checkCurrent(operation)
                if (execRemoteExit("test -d ${shellQuote(remotePath)}") == 0) {
                    FileLogger.w(TAG, "新建工作区失败：已存在 '$name'")
                    return null
                }
                checkCurrent(operation)
                if (execRemoteExit("mkdir -p ${shellQuote(remotePath)}") != 0) return null
                refreshWorkspaces(operation)
                FileLogger.i(TAG, "新建工作区(远程): $name")
                checkCurrent(operation)
                _workspaces.value.firstOrNull { it.path == remotePath }
            }
            else -> {
                val adapter = backend as? PluginExecutionBackendAdapter ?: return null
                val created = backendCall { adapter.createWorkspace(name) }?.takeIf { it.id.isNotBlank() } ?: return null
                checkCurrent(operation)
                if (backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.REGISTER) {
                    pluginWorkspaceRegistry.upsert(backend.id, listOf(created))
                }
                refreshWorkspaces(operation)
                FileLogger.i(TAG, "新建工作区(插件): ${created.name} (${backend.id})")
                checkCurrent(operation)
                created.copy(backendId = backend.id, instanceId = operation.instanceId)
            }
        }
    }

    /**
     * 注册用户所选设备目录为外部本地工作区（双向直读该目录，不复制）。
     * 校验：能解析为真实路径、是目录、可写。与现有工作区同名时自动加后缀。
     * @return 注册成功的 [Workspace]；校验失败返回 null（同时通过 [addError] 给出提示文案）。
     */
    suspend fun addExternalWorkspace(uri: Uri): Workspace? = withContext(Dispatchers.IO) {
        val operation = operation()
        if (!isBuiltinLocal(operation.backend)) return@withContext null
        checkCurrent(operation)
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
        checkCurrent(operation)
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
        checkCurrent(operation)
        writeExternalWorkspaces(operation, existing + ExternalWorkspaceRecord(name, path, uri.toString()))
        refreshWorkspaces(operation)
        checkCurrent(operation)
        val created = _workspaces.value.firstOrNull { it.path == path && it.type == WorkspaceType.EXTERNAL_LOCAL }
        FileLogger.i(TAG, "添加本地工作区: $name ($path)")
        created
    }

    private fun releaseUriGrant(uri: Uri, flags: Int) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    /** 删除工作区。内部/远程工作区连同文件与会话记录删除；外部本地工作区只解除关联，不删除物理目录，
     *  其会话记录是否一并删除由「偏好设置」中的开关决定。
     *  若删的是当前工作区，则自动切到剩余的第一个。 */
    @Deprecated("Pass WorkspaceRef to keep backend instance identity")
    suspend fun deleteWorkspace(id: String) = deleteWorkspace(WorkspaceRef(activeBackend().instanceId, id))

    suspend fun deleteWorkspace(ref: WorkspaceRef) = withContext(Dispatchers.IO) {
        val operation = operation()
        val backend = operation.backend
        val target = _workspaces.value.firstOrNull { it.ref == ref && it.canDelete } ?: return@withContext
        if (ref.backendInstanceId != operation.instanceId) return@withContext
        checkCurrent(operation)
        when {
            isBuiltinLocal(backend) && target.type == WorkspaceType.EXTERNAL_LOCAL -> {
                val records = readExternalWorkspaces()
                checkCurrent(operation)
                val removed = records.firstOrNull { it.path == target.path } ?: return@withContext
                writeExternalWorkspaces(operation, records.filterNot { it.path == target.path })
                releaseUriGrant(
                    Uri.parse(removed.uri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                if (generalSettingsRepository.deleteExternalWorkspaceSessions()) {
                    checkCurrent(operation)
                    sessionUseCase.deleteSessionsByWorkspace(target.ref)
                }
                forgetBuiltinWorkspaceId(operation, target)
                FileLogger.i(TAG, "移除外部工作区关联: ${target.name}")
            }
            isBuiltinLocal(backend) -> {
                val dir = File(target.path).toPath()
                if (dir.parent != projectsRoot.toPath() || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return@withContext
                if (backendCall {
                        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
                        true
                    } != true
                ) return@withContext
                checkCurrent(operation)
                sessionUseCase.deleteSessionsByWorkspace(target.ref)
                forgetBuiltinWorkspaceId(operation, target)
            }
            isBuiltinRemoteSsh(backend) -> {
                val cfg = remoteSshConnection.config ?: return@withContext
                val root = pathHomeResolver.expandHome(cfg.remoteWorkspacePath).trimEnd('/')
                if (target.path.substringBeforeLast('/') != root || target.path.substringAfterLast('/') in setOf("", ".", "..")) {
                    return@withContext
                }
                checkCurrent(operation)
                if (execRemoteExit("rm -rf -- ${shellQuote(target.path)}") != 0) return@withContext
                checkCurrent(operation)
                sessionUseCase.deleteSessionsByWorkspace(target.ref)
                forgetBuiltinWorkspaceId(operation, target)
            }
            else -> {
                val adapter = backend as? PluginExecutionBackendAdapter ?: return@withContext
                val targetId = target.id
                val deleted = backendCall { adapter.deleteWorkspace(targetId) } == true
                checkCurrent(operation)
                if (deleted) {
                    if (backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.REGISTER ||
                        backend.capabilities.workspaceDiscovery == WorkspaceDiscoveryMode.HYBRID
                    ) {
                        pluginWorkspaceRegistry.remove(backend.id, listOf(targetId))
                    }
                    sessionUseCase.deleteSessionsByWorkspace(target.ref)
                } else return@withContext
            }
        }
        checkCurrent(operation)
        refreshWorkspaces(operation)
        FileLogger.i(TAG, "删除工作区: ${target.id}")
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

    private suspend fun writeExternalWorkspaces(operation: BackendOperation, list: List<ExternalWorkspaceRecord>) {
        context.workspaceDataStore.edit {
            checkCurrent(operation)
            it[externalWorkspacesKey] = json.encodeToString(list)
        }
    }

    /** 单引号转义，保证 shell 命令安全。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前工作区的路径，供 projectRoot / 命令执行目录使用。
     * 本地模式返回宿主工作区绝对路径；远程模式返回选中工作区的远程绝对路径（命令 cd 到此）。
     * 无选中时本地回退到项目根目录，远程回退到配置的 remoteWorkspacePath，插件后端返回空串。 */
    fun currentPath(): String {
        val backend = activeBackend()
        val current = _current.value?.takeIf { it.instanceId == backend.instanceId }
        return when {
            isBuiltinLocal(backend) -> current?.path ?: projectsRoot.absolutePath
            isBuiltinRemoteSsh(backend) -> current?.path
                ?: remoteSshConnection.config?.remoteWorkspacePath
                    ?.let { pathHomeResolver.expandHome(it) }
                    ?.takeIf { it.isNotBlank() }
                ?: "/"
            else -> current?.path ?: ""
        }
    }

    /** 仅保留字母数字、下划线、连字符、点和空格，去掉路径分隔符等危险字符。 */
    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 ._\\u4e00-\\u9fa5-]"), "")
            .trim()
            .take(64)
}
