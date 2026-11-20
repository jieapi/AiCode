package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.plugin.overlay.PluginOverlayException
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件运行时的宿主门面：按插件版本管理容器内进程，把工具/hook 调用转成 JSON-RPC。
 *
 * 进程在本地容器中运行（SSH 模式下同样在手机端），cwd 是插件自己的安装目录，
 * 因此可以直接读取包内文件、`node_modules` 或虚拟环境。插件主动发来的 host API
 * 请求复用 [PluginHostPaths]，与页面桥的文件限制保持一致。
 */
@Singleton
class PluginRuntimeManager @Inject constructor(
    private val pluginManager: PluginManager,
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val homeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val eventBus: PluginEventBus,
    private val workflowEventBus: PluginWorkflowEventBus,
    private val overlayManager: PluginOverlayManager,
    private val hostApiDispatcher: PluginHostApiDispatcher
) {

    private class Entry(
        val pluginId: String,
        val key: String,
        val transport: PluginProcessTransport,
        var lastUsed: Long,
        var resident: Boolean
    )

    private val processes = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        overlayManager.setEventSink { pluginId, overlayId, payload ->
            scope.launch {
                val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
                    ?: return@launch
                val runtime = plugin.content.runtime ?: return@launch
                runCatching {
                    invoke(
                        pluginId = pluginId,
                        version = plugin.record.version,
                        runtime = runtime,
                        handler = "overlay.message",
                        args = buildJsonObject {
                            put("overlayId", overlayId)
                            put("payload", payload)
                        },
                        method = "ui.invoke",
                        timeoutMs = OVERLAY_MESSAGE_TIMEOUT_MS
                    )
                }.onFailure {
                    FileLogger.w(TAG, "overlay message failed: $pluginId", it)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                pruneIdle()
            }
        }
    }

    suspend fun invoke(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject,
        method: String = "tool.invoke",
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): JsonElement {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        if (!isActive(pluginId)) {
            synchronized(processes) { processes.remove(entry.key, entry) }
            entry.transport.close()
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
        entry.lastUsed = System.currentTimeMillis()
        return try {
            entry.transport.request(
                method = method,
                params = buildJsonObject {
                    put("handler", handler)
                    put("args", args)
                },
                timeoutMs = timeoutMs
            )
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_EXITED" || (e.code == "PLUGIN_TIMEOUT" && !entry.resident)) {
                synchronized(processes) { processes.remove(entry.key, entry) }
                entry.transport.close()
            }
            throw e
        }
    }

    /**
     * 通过二进制 IPC 通道调用插件 handler；若二进制通道未建连或不可用，自动回退到 NDJSON，
     * 并在必要时把二进制 [payload] 编码为 `payloadBase64` 随 `args` 传入。
     */
    suspend fun invokeBinary(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        method: String = BACKEND_INVOKE_METHOD,
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): BinaryResult {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        if (!isActive(pluginId)) {
            synchronized(processes) { processes.remove(entry.key, entry) }
            entry.transport.close()
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
        entry.lastUsed = System.currentTimeMillis()
        val metaParams = buildJsonObject {
            put("handler", handler)
            put("args", args)
        }
        return try {
            entry.transport.requestBinary(
                method = method,
                metaParams = metaParams,
                payload = payload,
                timeoutMs = timeoutMs
            )
        } catch (e: PluginProcessException) {
            if (e is PluginIpcUnavailableException) {
                val fallbackArgs = if (payload.isEmpty() || args.containsKey("payloadBase64")) {
                    args
                } else {
                    buildJsonObject {
                        args.forEach { (k, v) -> put(k, v) }
                        put("payloadBase64", Base64.getEncoder().encodeToString(payload))
                    }
                }
                val fallbackParams = buildJsonObject {
                    put("handler", handler)
                    put("args", fallbackArgs)
                }
                val result = try {
                    entry.transport.request(
                        method = method,
                        params = fallbackParams,
                        timeoutMs = timeoutMs
                    )
                } catch (fallbackEx: PluginProcessException) {
                    if (fallbackEx.code == "PLUGIN_EXITED" ||
                        (fallbackEx.code == "PLUGIN_TIMEOUT" && !entry.resident)
                    ) {
                        synchronized(processes) { processes.remove(entry.key, entry) }
                        entry.transport.close()
                    }
                    throw fallbackEx
                }
                decodeJsonToBinaryResult(result)
            } else {
                if (e.code == "PLUGIN_EXITED" || (e.code == "PLUGIN_TIMEOUT" && !entry.resident)) {
                    synchronized(processes) { processes.remove(entry.key, entry) }
                    entry.transport.close()
                }
                throw e
            }
        }
    }

    /** 通过 JSON-RPC 调用插件工作流 handler（`method = "workflow.invoke"`）。 */
    suspend fun invokeWorkflow(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = WORKFLOW_TIMEOUT_MS
    ): JsonElement = invoke(
        pluginId = pluginId,
        version = version,
        runtime = runtime,
        handler = handler,
        args = args,
        method = WORKFLOW_INVOKE_METHOD,
        timeoutMs = timeoutMs
    )

    /** 通过二进制 IPC 通道开启插件工作流流式调用（`method = "workflow.invoke"`）。 */
    fun openWorkflowStream(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject = JsonObject(emptyMap())
    ): Flow<BinaryStreamChunk> = openBinaryStream(
        pluginId = pluginId,
        version = version,
        runtime = runtime,
        handler = handler,
        args = args,
        method = WORKFLOW_INVOKE_METHOD,
        timeoutMs = WORKFLOW_TIMEOUT_MS
    )

    suspend fun uploadBinary(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject,
        payload: Flow<ByteArray>,
        method: String = BACKEND_INVOKE_METHOD,
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): BinaryResult {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        requireActive(pluginId)
        entry.lastUsed = System.currentTimeMillis()
        return entry.transport.uploadBinary(
            method = method,
            metaParams = buildJsonObject {
                put("handler", handler)
                put("args", args)
            },
            payload = payload,
            timeoutMs = timeoutMs
        )
    }

    /** 通过二进制 IPC 通道开启流式调用（默认 `method = "backend.invoke"`）。 */
    fun openBinaryStream(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        method: String = BACKEND_INVOKE_METHOD,
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): Flow<BinaryStreamChunk> = flow {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        if (!isActive(pluginId)) {
            synchronized(processes) { processes.remove(entry.key, entry) }
            entry.transport.close()
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
        entry.lastUsed = System.currentTimeMillis()
        val metaParams = buildJsonObject {
            put("handler", handler)
            put("args", args)
        }
        try {
            emitAll(
                entry.transport.openBinaryStream(
                    method = method,
                    metaParams = metaParams,
                    payload = payload,
                    timeoutMs = timeoutMs
                )
            )
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_EXITED" || (e.code == "PLUGIN_TIMEOUT" && !entry.resident)) {
                synchronized(processes) { processes.remove(entry.key, entry) }
                entry.transport.close()
            }
            throw e
        }
    }

    private fun decodeJsonToBinaryResult(element: JsonElement): BinaryResult {
        val obj = element as? JsonObject
            ?: return BinaryResult(meta = buildJsonObject { put("result", element) })
        val base64 = (obj["base64"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: (obj["payloadBase64"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val decoded = if (base64 != null) {
            runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
        } else {
            null
        }
        return if (decoded != null) {
            BinaryResult(meta = obj, payload = decoded)
        } else {
            BinaryResult(meta = obj)
        }
    }

    /** 插件版本替换后关闭旧版本进程，避免 resident 旧进程永远不回收。 */
    fun closeOtherVersions(pluginId: String, currentVersion: String) {
        val keepKey = "$pluginId@$currentVersion"
        synchronized(processes) {
            processes.entries
                .filter { (key, entry) -> entry.pluginId == pluginId && key != keepKey }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    /** 插件停用、卸载或版本替换时关闭它的进程。 */
    fun closePlugin(pluginId: String) {
        synchronized(processes) {
            processes.entries
                .filter { (_, entry) -> entry.pluginId == pluginId }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    private suspend fun getOrCreate(pluginId: String, version: String, runtime: PluginRuntime): Entry {
        val key = "$pluginId@$version"
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", it)
        }
        val dataDir = dataDirectory(pluginId)
        val wsRoot = workspaceRoot()
        // 上面的挂起调用期间可能已有并发调用创建了同一版本的进程。
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        var createdEntry: Entry? = null
        val ipc = createIpcConfig(pluginId)
        val extraEnv = buildMap {
            put("AICODE_PLUGIN_ID", pluginId)
            put("AICODE_PLUGIN_VERSION", version)
            put("AICODE_DATA_DIR", dataDir)
            put("AICODE_WORKSPACE", wsRoot)
            if (ipc != null) {
                put("AICODE_IPC_SOCKET", ipc.containerSockPath)
                put("AICODE_IPC_TOKEN", ipc.token)
            }
        }
        val transport = PluginProcessTransport(
            pluginId = pluginId,
            engine = engine,
            profile = profile,
            workingDir = containerDir(runtime.installDir),
            program = runtime.command.firstOrNull()
                ?: throw PluginProcessException("PLUGIN_COMMAND_MISSING", "[$pluginId] 运行时没有启动命令"),
            programArgs = runtime.command.drop(1),
            extraEnv = extraEnv,
            onRequest = { method, params -> hostRequest(pluginId, runtime, method, params) },
            onNotification = { method, params -> handleNotification(pluginId, method, params) },
            onExit = {
                createdEntry?.let { entry ->
                    synchronized(processes) { processes.remove(key, entry) }
                }
            },
            closeOnTimeout = !runtime.resident,
            ipcSocketFile = ipc?.hostSockFile,
            ipcToken = ipc?.token
        )
        val entry = Entry(pluginId, key, transport, System.currentTimeMillis(), runtime.resident)
        createdEntry = entry
        val existing = synchronized(processes) { processes.putIfAbsent(key, entry) }
        if (existing != null) {
            transport.close()
            return existing
        }
        return entry
    }

    private suspend fun hostRequest(
        pluginId: String,
        runtime: PluginRuntime,
        method: String,
        params: JsonObject
    ): JsonElement {
        return hostApiDispatcher.dispatch(
            pluginId = pluginId,
            method = method,
            params = params,
            origin = PluginHostApiOrigin.PROCESS,
            workspaceRoot = ::workspaceRoot,
            modelOrigin = PROCESS_ORIGIN
        )
    }

    private fun isActive(pluginId: String): Boolean {
        pluginManager.ensureLoaded()
        return pluginManager.plugins.value.any { it.id == pluginId && it.isActive }
    }

    private fun requireActive(pluginId: String) {
        if (!isActive(pluginId)) {
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
    }

    private fun handleNotification(pluginId: String, method: String, params: JsonObject) {
        if (workflowEventBus.publishNotification(pluginId, method, params)) {
            return
        }
        if (method != "plugin.event") {
            FileLogger.d(TAG, "[$pluginId] 忽略插件通知: $method")
            return
        }
        eventBus.publish(pluginId, params)
    }

    /** 本地默认容器；SSH 模式下插件进程仍跑在手机端，与 MCP stdio 一致。 */
    private suspend fun runtimeProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 宿主文件路径映射到容器内路径：插件目录由 `.aicode` 绑定挂载到 `/root/.aicode`。 */
    private fun containerDir(file: File): String = installer.containerPathFor(file)

    internal suspend fun dataDirectory(pluginId: String): String = withContext(Dispatchers.IO) {
        val directory = File(installer.aicodeDir, "plugins/data/$pluginId")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw PluginProcessException("STORAGE_FAILED", "cannot create plugin data directory")
        }
        installer.containerPathFor(directory)
    }

    /** 当前工作区在插件可见路径空间里的根：远程用远端绝对路径，本地容器用 `$HOME/workspace`。 */
    private fun workspaceRoot(): String {
        val backend = executionModeHolder.currentBackend()
        val currentPath = workspaceRepository.currentPath()
        return when {
            backend.capabilities.isRemoteEnvironment ->
                currentPath.ifBlank { backend.workspaceRootDisplay(currentPath) }
            backend.id == ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID ->
                homeResolver.home().trimEnd('/') + "/workspace"
            else ->
                backend.workspaceRootDisplay(currentPath).ifBlank {
                    homeResolver.home().trimEnd('/') + "/workspace"
                }
        }
    }

    private fun pruneIdle() {
        val now = System.currentTimeMillis()
        synchronized(processes) {
            processes.entries
                .filter { !it.value.resident && !it.value.transport.isBusy && now - it.value.lastUsed > IDLE_TIMEOUT_MS }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    internal fun createIpcConfig(pluginId: String): IpcConfig? {
        val ipcDir = File(installer.aicodeDir, IPC_DIR).apply { mkdirs() }
        val sockNameBytes = ByteArray(4).also { ipcRandom.nextBytes(it) }
        val sockName = sockNameBytes.joinToString("") { "%02x".format(it) } + ".sock"
        val hostSockFile = File(ipcDir, sockName)
        if (hostSockFile.absolutePath.toByteArray(Charsets.UTF_8).size > MAX_UNIX_SOCKET_PATH_BYTES) {
            FileLogger.w(
                TAG,
                "[$pluginId] IPC socket 路径超过 $MAX_UNIX_SOCKET_PATH_BYTES 字节，回退 NDJSON: ${hostSockFile.absolutePath}"
            )
            return null
        }
        val tokenBytes = ByteArray(IPC_TOKEN_BYTES).also { ipcRandom.nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        return IpcConfig(
            hostSockFile = hostSockFile,
            containerSockPath = installer.containerPathFor(hostSockFile),
            token = token
        )
    }

    internal data class IpcConfig(
        val hostSockFile: File,
        val containerSockPath: String,
        val token: String
    )

    private companion object {
        const val TAG = "PluginRuntime"
        const val TOOL_TIMEOUT_MS = 120_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
        const val OVERLAY_MESSAGE_TIMEOUT_MS = 30_000L
        const val PROCESS_ORIGIN = "process"
        const val BACKEND_INVOKE_METHOD = "backend.invoke"
        const val WORKFLOW_INVOKE_METHOD = "workflow.invoke"
        const val WORKFLOW_TIMEOUT_MS = 30 * 60_000L
        const val IPC_DIR = "ipc"
        const val MAX_UNIX_SOCKET_PATH_BYTES = 100
        const val IPC_TOKEN_BYTES = 16
        val ipcRandom = SecureRandom()
    }
}
