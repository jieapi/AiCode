package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val eventBus: PluginEventBus,
    private val notifier: PluginNotifier
) {

    private class Entry(
        val pluginId: String,
        val key: String,
        val transport: PluginProcessTransport,
        var lastUsed: Long
    )

    private val processes = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
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
        val entry = getOrCreate(pluginId, version, runtime)
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
            if (e.code == "PLUGIN_EXITED" || e.code == "PLUGIN_TIMEOUT") {
                synchronized(processes) { processes.remove(entry.key) }
                entry.transport.close()
            }
            throw e
        }
    }

    /** 插件停用、卸载或版本替换时关闭它的进程。 */
    fun closePlugin(pluginId: String) {
        synchronized(processes) {
            processes.entries
                .filter { (_, entry) -> entry.pluginId == pluginId }
                .forEach { (key, entry) ->
                    processes.remove(key)
                    entry.transport.close()
                }
        }
    }

    private suspend fun getOrCreate(pluginId: String, version: String, runtime: PluginRuntime): Entry {
        val key = "$pluginId@$version"
        processes[key]?.let { return it }
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", it)
        }
        val transport = PluginProcessTransport(
            pluginId = pluginId,
            engine = engine,
            profile = profile,
            workingDir = containerDir(runtime.installDir),
            program = runtime.command.firstOrNull()
                ?: throw PluginProcessException("PLUGIN_COMMAND_MISSING", "[$pluginId] 运行时没有启动命令"),
            programArgs = runtime.command.drop(1),
            extraEnv = mapOf(
                "AICODE_PLUGIN_ID" to pluginId,
                "AICODE_PLUGIN_VERSION" to version,
                "AICODE_DATA_DIR" to containerDir(pluginManager.dataDirFor(pluginId))
            ),
            onRequest = { method, params -> hostRequest(pluginId, runtime, method, params) },
            onNotification = { method, params -> handleNotification(pluginId, method, params) },
            onExit = {
                synchronized(processes) { processes.remove(key) }
            }
        )
        val entry = Entry(pluginId, key, transport, System.currentTimeMillis())
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
        val paths = PluginHostPaths(
            dataDir = pluginManager.dataDirFor(pluginId),
            installDir = runtime.installDir,
            fileAccess = fileAccess,
            homeResolver = homeResolver
        )
        return when (method) {
            "host.fs.read" -> paths.read(PluginHostPaths.requireString(params, "path"))
            "host.fs.write" -> paths.write(
                PluginHostPaths.requireString(params, "path"),
                PluginHostPaths.requireString(params, "content")
            )
            "host.fs.list" -> paths.list(PluginHostPaths.requireString(params, "path"))
            "host.fs.delete" -> paths.delete(PluginHostPaths.requireString(params, "path"))
            "host.fs.stat" -> paths.stat(PluginHostPaths.requireString(params, "path"))
            "host.bash" -> bash(params)
            "host.notify" -> notifier.notify(pluginId, params)
            else -> throw PluginProcessException("METHOD_UNKNOWN", "unknown host method: $method")
        }
    }

    private suspend fun bash(params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        if (command.isBlank()) throw PluginProcessException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginProcessException("PARAM_INVALID", "command too long")
        }
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceIn(1L, PluginHostPaths.MAX_BASH_TIMEOUT_SECONDS) * 1000L
        val result = commandEngine.runCommandSyncWithExit(command, workspaceRepository.currentPath(), timeoutMs)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.outputTruncated)
        }
    }

    private fun handleNotification(pluginId: String, method: String, params: JsonObject) {
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

    private fun pruneIdle() {
        val now = System.currentTimeMillis()
        synchronized(processes) {
            processes.entries
                .filter { now - it.value.lastUsed > IDLE_TIMEOUT_MS }
                .forEach { (key, entry) ->
                    processes.remove(key)
                    entry.transport.close()
                }
        }
    }

    private companion object {
        const val TAG = "PluginRuntime"
        const val TOOL_TIMEOUT_MS = 120_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
    }
}
