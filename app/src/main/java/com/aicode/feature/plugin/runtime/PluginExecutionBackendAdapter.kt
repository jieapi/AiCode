package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS
import com.aicode.feature.agent.domain.container.ProjectConfigLocation
import com.aicode.feature.agent.domain.container.WorkspaceDiscoveryMode
import com.aicode.feature.agent.domain.container.hashProjectKey
import com.aicode.feature.plugin.domain.PluginBackendConfigLocations
import com.aicode.feature.plugin.domain.PluginBackendDiscoveryModes
import com.aicode.feature.plugin.domain.PluginExecutionBackendDefinition
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TabInfo
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTabStatus
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.domain.sha256Fingerprint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 将插件声明的 [PluginExecutionBackendDefinition] 适配为宿主统一的 [ExecutionBackend]。
 *
 * 所有能力调用优先走 [PluginRuntimeManager.invokeBinary] / [PluginRuntimeManager.openBinaryStream]
 * （method = `"backend.invoke"`），并在二进制 IPC 通道不可用时自动回退到 NDJSON。
 */
class PluginExecutionBackendAdapter(
    val definition: PluginExecutionBackendDefinition,
    private val runtimeManager: PluginRuntimeManager,
    languageTag: String = Locale.getDefault().toLanguageTag()
) : ExecutionBackend {

    override val id: String = definition.key
    override val displayName: String = definition.name.resolve(languageTag)
    override val capabilities: BackendCapabilities = BackendCapabilities(
        filesystem = definition.capabilities.filesystem,
        command = definition.capabilities.command,
        terminal = definition.capabilities.terminal,
        fileWatch = definition.capabilities.fileWatch,
        portForward = definition.capabilities.portForward,
        isRemoteEnvironment = definition.capabilities.isRemoteEnvironment,
        workspaceDiscovery = when (definition.capabilities.workspaceDiscovery.lowercase(Locale.ROOT)) {
            PluginBackendDiscoveryModes.REGISTER -> WorkspaceDiscoveryMode.REGISTER
            PluginBackendDiscoveryModes.HYBRID -> WorkspaceDiscoveryMode.HYBRID
            else -> WorkspaceDiscoveryMode.SCAN
        },
        projectConfigLocation = when (definition.capabilities.projectConfigLocation) {
            PluginBackendConfigLocations.APP_PRIVATE_DIR -> ProjectConfigLocation.APP_PRIVATE_DIR
            else -> ProjectConfigLocation.WORKSPACE_DIR
        }
    )

    override val commandEngine: CommandEngine = PluginCommandEngine()
    override val fileAccess: FileAccessProvider = PluginFileAccessProvider()
    override val terminalSessionProvider: TerminalSessionProvider? =
        if (capabilities.terminal && definition.handlers.terminal != null) {
            PluginTerminalSessionProvider()
        } else {
            null
        }

    override fun environmentDescription(): String =
        "$displayName（插件：${definition.pluginId}）"

    override fun environmentCacheKey(): String =
        "plugin:${definition.pluginId}:${definition.localId}:${definition.pluginVersion}"

    override fun workspaceRootDisplay(workspacePath: String): String =
        workspacePath.trim()

    override fun projectConfigKey(workspacePath: String): String =
        hashProjectKey(workspacePath, "${definition.key}:$workspacePath")

    /** 若插件声明了 `workspaceList` handler，向插件拉取其工作区列表。 */
    suspend fun listWorkspaces(): List<Workspace> {
        val handler = definition.handlers.workspaceList ?: return emptyList()
        val obj = callJson(
            handler = handler,
            args = buildJsonObject { put("backendId", id) }
        )
        val arr = (obj["workspaces"] ?: obj["items"]) as? JsonArray ?: return emptyList()
        val defaultType = if (capabilities.isRemoteEnvironment) WorkspaceType.REMOTE else WorkspaceType.INTERNAL
        return arr.mapNotNull { el -> parseWorkspace(el as? JsonObject ?: return@mapNotNull null, defaultType) }
    }

    /** 若插件声明了 `workspaceCreate` handler，请求插件新建工作区。 */
    suspend fun createWorkspace(name: String, parentId: String? = null): Workspace? {
        val handler = definition.handlers.workspaceCreate ?: return null
        val obj = callJson(
            handler = handler,
            args = buildJsonObject {
                put("backendId", id)
                put("name", name)
                if (parentId != null) put("parentId", parentId)
            }
        )
        val wsObj = obj["workspace"] as? JsonObject ?: obj
        val defaultType = if (capabilities.isRemoteEnvironment) WorkspaceType.REMOTE else WorkspaceType.INTERNAL
        return parseWorkspace(wsObj, defaultType)
    }

    /** 若插件声明了 `workspaceDelete` handler，请求插件删除工作区。 */
    suspend fun deleteWorkspace(workspaceId: String): Boolean {
        val handler = definition.handlers.workspaceDelete ?: return false
        val obj = callJson(
            handler = handler,
            args = buildJsonObject {
                put("backendId", id)
                put("workspaceId", workspaceId)
            }
        )
        return obj["ok"]?.booleanVal() ?: true
    }

    /** 若插件声明了 `workspaceSelect` handler，通知插件当前激活的工作区切换。 */
    suspend fun selectWorkspace(workspace: Workspace): Boolean {
        val handler = definition.handlers.workspaceSelect ?: return true
        val obj = callJson(
            handler = handler,
            args = buildJsonObject {
                put("backendId", id)
                put("workspaceId", workspace.id)
                put("name", workspace.name)
                put("path", workspace.path)
            }
        )
        return obj["ok"]?.booleanVal() ?: true
    }

    private fun parseWorkspace(obj: JsonObject, defaultType: WorkspaceType): Workspace? {
        val name = obj["name"]?.stringVal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val path = obj["path"]?.stringVal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val wsId = obj["id"]?.stringVal()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PluginHostException("WORKSPACE_INVALID", "workspace.id is required")
        val available = obj["available"]?.booleanVal() ?: true
        val group = obj["group"]?.stringVal()?.trim().orEmpty()
        val caps = when (val raw = obj["capabilities"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw PluginHostException("PARAM_INVALID", "workspace capabilities must be an object")
        }
        fun capability(key: String): Boolean {
            val raw = caps[key] ?: return true
            return raw.booleanVal()
                ?: throw PluginHostException("PARAM_INVALID", "workspace capabilities.$key must be a boolean")
        }
        val canCreateChild = capability("createChild")
        val canDelete = capability("delete")
        capability("git")
        return Workspace(
            name = name,
            path = path,
            type = defaultType,
            available = available,
            id = wsId,
            backendId = id,
            group = group,
            canCreateChild = canCreateChild,
            canDelete = canDelete
        )
    }

    private suspend fun callBinary(
        handler: String,
        args: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS
    ): BinaryResult = runtimeManager.invokeBinary(
        pluginId = definition.pluginId,
        version = definition.pluginVersion,
        runtime = definition.runtime,
        handler = handler,
        args = args,
        payload = payload,
        method = BACKEND_INVOKE_METHOD,
        timeoutMs = timeoutMs
    )

    private suspend fun callJson(
        handler: String,
        args: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS
    ): JsonObject = unwrapMeta(callBinary(handler, args, payload, timeoutMs))

    private fun unwrapMeta(result: BinaryResult): JsonObject {
        val inner = result.meta["result"] as? JsonObject
        if (inner != null) return inner
        if (result.meta.isEmpty() && result.payload.isNotEmpty()) {
            val parsed = runCatching {
                JSON.parseToJsonElement(result.payload.toString(Charsets.UTF_8)) as? JsonObject
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return result.meta
    }

    private fun extractBytes(result: BinaryResult): ByteArray {
        if (result.payload.isNotEmpty()) return result.payload
        val meta = unwrapMeta(result)
        val b64 = meta["base64"]?.stringVal() ?: meta["payloadBase64"]?.stringVal()
        if (b64 != null) {
            return Base64.getDecoder().decode(b64)
        }
        val content = meta["content"]?.stringVal()
        if (content != null) return content.toByteArray(Charsets.UTF_8)
        return BinaryFrame.EMPTY_BYTES
    }

    /**
     * 基于插件 `exec` / `execStream` / `execHealth` handlers 的 [CommandEngine] 实现。
     */
    internal inner class PluginCommandEngine : CommandEngine {

        private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
        override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

        @Volatile
        private var healthy: Boolean = definition.handlers.execHealth == null && capabilities.command

        @Volatile
        private var provisionedCached: Boolean = true

        @Volatile
        private var shellCached: String = "/bin/sh"

        override fun runCommandStream(
            command: String,
            projectPath: String?,
            timeoutMs: Long
        ): Flow<CommandEvent> = flow {
            if (!capabilities.command) {
                emit(CommandEvent.Line("当前执行后端不支持命令执行"))
                emit(CommandEvent.Exit(null))
                return@flow
            }
            val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
            val streamHandler = definition.handlers.execStream
            if (streamHandler == null) {
                val result = runCommandSyncWithExit(command, projectPath, effectiveTimeout)
                if (result.output.isNotEmpty()) {
                    result.output.lines().forEach { emit(CommandEvent.Line(it)) }
                }
                emit(CommandEvent.Exit(result.exitCode))
                return@flow
            }

            var exitEmitted = false
            try {
                val args = execArgs(command, projectPath, effectiveTimeout, unbounded = false)
                runtimeManager.openBinaryStream(
                    pluginId = definition.pluginId,
                    version = definition.pluginVersion,
                    runtime = definition.runtime,
                    handler = streamHandler,
                    args = args,
                    method = BACKEND_INVOKE_METHOD
                ).collect { chunk ->
                    val type = chunk.meta["type"]?.stringVal()
                    when {
                        type == "exit" || chunk.meta.containsKey("exitCode") -> {
                            exitEmitted = true
                            emit(CommandEvent.Exit(chunk.meta["exitCode"]?.intVal()))
                        }
                        else -> {
                            val text = chunk.meta["text"]?.stringVal()
                                ?: if (chunk.payload.isNotEmpty()) chunk.payload.toString(Charsets.UTF_8) else null
                            if (!text.isNullOrEmpty()) {
                                text.split('\n').forEach { line ->
                                    if (line.isNotEmpty()) emit(CommandEvent.Line(line.trimEnd('\r')))
                                }
                            }
                        }
                    }
                }
                healthy = true
                if (!exitEmitted) emit(CommandEvent.Exit(0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginProcessException) {
                if (e.code in BINARY_FALLBACK_CODES) {
                    val result = runCommandSyncWithExit(command, projectPath, effectiveTimeout)
                    if (result.output.isNotEmpty()) {
                        result.output.lines().forEach { emit(CommandEvent.Line(it)) }
                    }
                    emit(CommandEvent.Exit(result.exitCode))
                } else {
                    FileLogger.w(TAG, "[$id] 流式命令执行异常: ${e.message}", e)
                    emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                    emit(CommandEvent.Exit(null))
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "[$id] 流式命令执行异常: ${e.message}", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        }

        override suspend fun runCommandSync(
            command: String,
            projectPath: String?,
            timeoutMs: Long
        ): String = runCommandSyncWithExit(command, projectPath, timeoutMs).output

        override suspend fun runCommandSyncWithExit(
            command: String,
            projectPath: String?,
            timeoutMs: Long
        ): CommandResult = execSync(command, projectPath, timeoutMs, unbounded = false)

        override suspend fun runCommandSyncUnbounded(
            command: String,
            projectPath: String?,
            timeoutMs: Long
        ): CommandResult = execSync(command, projectPath, timeoutMs, unbounded = true)

        override suspend fun runCommandSyncIfReady(
            command: String,
            projectPath: String?,
            timeoutMs: Long
        ): CommandResult? {
            if (!capabilities.command || !isContainerInstalled()) return null
            return runCatching { execSync(command, projectPath, timeoutMs, unbounded = false) }.getOrNull()
        }

        override fun isContainerInstalled(): Boolean = healthy

        override fun isProvisioned(): Boolean = provisionedCached

        override fun defaultShell(): String = shellCached

        override fun notReadyHint(): String? =
            if (capabilities.command && definition.handlers.exec != null) null
            else "当前执行后端不支持命令执行"

        override suspend fun ensureInstalled() {
            if (!capabilities.command) {
                val msg = "当前执行后端不支持命令执行"
                _initProgress.value = ContainerInitState.Failed(msg)
                throw IllegalStateException(msg)
            }
            val healthHandler = definition.handlers.execHealth
            if (healthHandler == null) {
                healthy = true
                _initProgress.value = ContainerInitState.Ready
                return
            }
            _initProgress.value = ContainerInitState.InstallingPackages(line = "正在检查后端环境…")
            try {
                val obj = callJson(
                    handler = healthHandler,
                    args = buildJsonObject { put("backendId", id) },
                    timeoutMs = HEALTH_TIMEOUT_MS
                )
                val ready = obj["ready"]?.booleanVal()
                    ?: obj["healthy"]?.booleanVal()
                    ?: obj["ok"]?.booleanVal()
                    ?: true
                provisionedCached = obj["provisioned"]?.booleanVal() ?: true
                obj["shell"]?.stringVal()?.takeIf { it.isNotBlank() }?.let { shellCached = it }
                if (ready) {
                    healthy = true
                    _initProgress.value = ContainerInitState.Ready
                } else {
                    healthy = false
                    val reason = obj["message"]?.stringVal()?.takeIf { it.isNotBlank() }
                        ?: obj["reason"]?.stringVal()?.takeIf { it.isNotBlank() }
                        ?: "执行后端未就绪"
                    _initProgress.value = ContainerInitState.Failed(reason)
                    throw IllegalStateException(reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                healthy = false
                val msg = e.message ?: "后端健康检查失败"
                _initProgress.value = ContainerInitState.Failed(msg)
                throw e
            }
        }

        private suspend fun execSync(
            command: String,
            projectPath: String?,
            timeoutMs: Long,
            unbounded: Boolean
        ): CommandResult = withContext(Dispatchers.IO) {
            val handler = definition.handlers.exec
            if (!capabilities.command || handler == null) {
                return@withContext CommandResult("当前执行后端不支持命令执行", null)
            }
            val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
            try {
                val obj = callJson(
                    handler = handler,
                    args = execArgs(command, projectPath, effectiveTimeout, unbounded),
                    timeoutMs = effectiveTimeout + RPC_OVERHEAD_MS
                )
                healthy = true
                val rawOutput = obj["output"]?.stringVal() ?: ""
                val exitCode = obj["exitCode"]?.intVal()
                val truncatedFlag = obj["truncated"]?.booleanVal() ?: false
                if (unbounded && rawOutput.length > MAX_UNBOUNDED_CHARS) {
                    CommandResult(rawOutput.take(MAX_UNBOUNDED_CHARS), exitCode, outputTruncated = true)
                } else {
                    CommandResult(rawOutput, exitCode, outputTruncated = truncatedFlag)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "[$id] 命令执行失败: ${e.message}", e)
                CommandResult("Error: ${e.message}", null)
            }
        }

        private fun execArgs(
            command: String,
            projectPath: String?,
            timeoutMs: Long,
            unbounded: Boolean
        ): JsonObject = buildJsonObject {
            put("backendId", id)
            put("command", command)
            if (projectPath != null) put("projectPath", projectPath)
            put("timeoutMs", timeoutMs)
            if (unbounded) put("unbounded", true)
        }
    }

    /**
     * 基于插件 `fs` handler 的 [FileAccessProvider] 实现。
     */
    internal inner class PluginFileAccessProvider : FileAccessProvider {

        private fun fsHandler(): String {
            if (!capabilities.filesystem) {
                throw IOException("当前执行后端不支持文件系统操作: $id")
            }
            return definition.handlers.fs
                ?: throw IOException("执行后端缺少 fs handler: $id")
        }

        private fun fsCall(
            op: String,
            path: String,
            payload: ByteArray = BinaryFrame.EMPTY_BYTES,
            extra: JsonObject = JsonObject(emptyMap()),
            maxBytesForError: Long? = null
        ): BinaryResult = runBlocking(Dispatchers.IO) {
            val handler = fsHandler()
            val args = buildJsonObject {
                put("backendId", id)
                put("op", op)
                put("path", path)
                extra.forEach { (k, v) -> put(k, v) }
            }
            try {
                callBinary(handler = handler, args = args, payload = payload)
            } catch (e: PluginProcessException) {
                throw mapFsError(path, e, maxBytesForError)
            }
        }

        private fun statMeta(path: String): JsonObject =
            unwrapMeta(fsCall(op = "stat", path = path))

        override fun readFile(path: String): String {
            val result = fsCall(op = "readFile", path = path)
            if (result.payload.isNotEmpty()) {
                return result.payload.toString(Charsets.UTF_8)
            }
            val meta = unwrapMeta(result)
            meta["content"]?.stringVal()?.let { return it }
            meta["base64"]?.stringVal()?.let { b64 ->
                return Base64.getDecoder().decode(b64).toString(Charsets.UTF_8)
            }
            return ""
        }

        override fun readLines(path: String): Sequence<String> = sequence {
            val temporary = File.createTempFile("aicode_plugin_lines_", ".tmp").apply { deleteOnExit() }
            try {
                runBlocking(Dispatchers.IO) {
                    runtimeManager.openBinaryStream(
                        pluginId = definition.pluginId,
                        version = definition.pluginVersion,
                        runtime = definition.runtime,
                        handler = fsHandler(),
                        args = buildJsonObject {
                            put("backendId", id)
                            put("op", "readStream")
                            put("path", path)
                        },
                        method = BACKEND_INVOKE_METHOD
                    ).collect { chunk ->
                        if (chunk.payload.isNotEmpty()) temporary.appendBytes(chunk.payload)
                    }
                }
                temporary.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        yield(line)
                    }
                }
            } finally {
                runCatching { temporary.delete() }
            }
        }

        override fun writeFile(
            path: String,
            content: String,
            overwrite: Boolean,
            encoding: Charset
        ) {
            val bytes = content.toByteArray(encoding)
            fsCall(
                op = "writeFile",
                path = path,
                payload = bytes,
                extra = buildJsonObject {
                    put("overwrite", overwrite)
                    put("encoding", encoding.name())
                }
            )
        }

        override fun exists(path: String): Boolean =
            statMeta(path)["exists"]?.booleanVal() ?: false

        override fun isDirectory(path: String): Boolean {
            val meta = statMeta(path)
            val exists = meta["exists"]?.booleanVal() ?: true
            return exists && (meta["isDirectory"]?.booleanVal() ?: meta["isDir"]?.booleanVal() ?: false)
        }

        override fun isFile(path: String): Boolean {
            val meta = statMeta(path)
            val exists = meta["exists"]?.booleanVal() ?: true
            return exists && (meta["isFile"]?.booleanVal() ?: false)
        }

        override fun fileSize(path: String): Long =
            statMeta(path)["size"]?.longVal() ?: 0L

        override fun lastModified(path: String): Long =
            statMeta(path)["lastModified"]?.longVal()
                ?: statMeta(path)["mtime"]?.longVal()
                ?: 0L

        override fun fingerprint(path: String): String? {
            return try {
                val meta = unwrapMeta(fsCall(op = "fingerprint", path = path))
                if (meta["unsupported"]?.booleanVal() == true) {
                    computeFingerprintFallback(path)
                } else {
                    meta["fingerprint"]?.stringVal()?.takeIf { it.isNotBlank() }
                        ?: if (meta["exists"]?.booleanVal() == false) null else computeFingerprintFallback(path)
                }
            } catch (_: NoSuchFileException) {
                null
            } catch (e: IOException) {
                computeFingerprintFallback(path)
            }
        }

        private fun computeFingerprintFallback(path: String): String? {
            if (!isFile(path)) return null
            val bytes = readBytes(path, MAX_FINGERPRINT_BYTES)
            return sha256Fingerprint(bytes)
        }

        override fun permissions(path: String): String =
            unwrapMeta(fsCall(op = "permissions", path = path))["permissions"]?.stringVal() ?: "---"

        override fun listFiles(path: String): List<FileEntry> {
            val meta = unwrapMeta(fsCall(op = "listFiles", path = path))
            val entries = meta["entries"] as? JsonArray ?: return emptyList()
            return entries.mapNotNull { el ->
                val item = el as? JsonObject ?: return@mapNotNull null
                val name = item["name"]?.stringVal() ?: return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = item["isDirectory"]?.booleanVal() ?: item["isDir"]?.booleanVal() ?: false,
                    size = item["size"]?.longVal() ?: 0L,
                    lastModified = item["lastModified"]?.longVal() ?: item["mtime"]?.longVal() ?: 0L,
                    localFile = null,
                    permissions = item["permissions"]?.stringVal() ?: "---"
                )
            }
        }

        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
            val result = runCatching {
                unwrapMeta(
                    fsCall(
                        op = "listFilesRecursive",
                        path = path,
                        extra = buildJsonObject { put("maxDepth", maxDepth) }
                    )
                )
            }.getOrNull()
            val array = (result?.get("paths") ?: result?.get("files")) as? JsonArray
            if (array != null && result?.get("unsupported")?.booleanVal() != true) {
                return array.mapNotNull { it.stringVal() }
            }
            if (!isDirectory(path)) return emptyList()
            val collected = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                if (depth > maxDepth) return
                val entries = runCatching { listFiles(dir) }.getOrDefault(emptyList())
                for (entry in entries) {
                    val childRel = if (rel.isEmpty()) entry.name else "$rel/${entry.name}"
                    val childPath = dir.trimEnd('/') + "/" + entry.name
                    if (entry.isDirectory) {
                        if (depth < maxDepth) walk(childPath, childRel, depth + 1)
                    } else {
                        collected += childRel
                    }
                }
            }
            walk(path, "", 1)
            return collected
        }

        override fun readBytes(path: String): ByteArray {
            val result = fsCall(op = "readBytes", path = path)
            return extractBytes(result)
        }

        override fun readBytes(path: String, maxBytes: Long): ByteArray {
            val result = fsCall(
                op = "readBytes",
                path = path,
                extra = buildJsonObject { put("maxBytes", maxBytes) },
                maxBytesForError = maxBytes
            )
            val bytes = extractBytes(result)
            if (bytes.size.toLong() > maxBytes) {
                throw FileReadTooLargeException(maxBytes)
            }
            return bytes
        }

        override fun resolvePath(path: String, followFinalLink: Boolean): String {
            val meta = unwrapMeta(
                fsCall(
                    op = "resolvePath",
                    path = path,
                    extra = buildJsonObject { put("followFinalLink", followFinalLink) }
                )
            )
            return meta["path"]?.stringVal() ?: path
        }

        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            fsCall(
                op = "writeBytes",
                path = path,
                payload = bytes,
                extra = buildJsonObject { put("overwrite", overwrite) }
            )
        }

        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
            val bytes = input.asFlow()
            val result = runBlocking(Dispatchers.IO) {
                runtimeManager.uploadBinary(
                    pluginId = definition.pluginId,
                    version = definition.pluginVersion,
                    runtime = definition.runtime,
                    handler = fsHandler(),
                    args = buildJsonObject {
                        put("backendId", id)
                        put("op", "writeStream")
                        put("path", path)
                        put("overwrite", overwrite)
                    },
                    payload = bytes,
                    method = BACKEND_INVOKE_METHOD
                )
            }
            val meta = unwrapMeta(result)
            return meta["bytesWritten"]?.longVal() ?: result.payload.size.toLong()
        }

        override fun copyToLocal(path: String): File {
            val temporary = File.createTempFile("aicode_plugin_", ".copy").apply { deleteOnExit() }
            try {
                runBlocking(Dispatchers.IO) {
                    runtimeManager.openBinaryStream(
                        pluginId = definition.pluginId,
                        version = definition.pluginVersion,
                        runtime = definition.runtime,
                        handler = fsHandler(),
                        args = buildJsonObject {
                            put("backendId", id)
                            put("op", "readStream")
                            put("path", path)
                        },
                        method = BACKEND_INVOKE_METHOD
                    ).collect { chunk ->
                        if (chunk.payload.isNotEmpty()) temporary.appendBytes(chunk.payload)
                    }
                }
            } catch (e: Exception) {
                runCatching { temporary.delete() }
                throw e
            }
            return temporary
        }

        private fun InputStream.asFlow(): Flow<ByteArray> = flow {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (count > 0) emit(buffer.copyOf(count))
            }
        }

        override fun delete(path: String) {
            fsCall(op = "delete", path = path)
        }

        override fun deleteRecursively(path: String) {
            fsCall(op = "deleteRecursively", path = path)
        }

        override fun rename(path: String, newPath: String) {
            fsCall(
                op = "rename",
                path = path,
                extra = buildJsonObject { put("newPath", newPath) }
            )
        }

        override fun copy(path: String, newPath: String, overwrite: Boolean) {
            fsCall(
                op = "copy",
                path = path,
                extra = buildJsonObject {
                    put("newPath", newPath)
                    put("overwrite", overwrite)
                }
            )
        }

        override fun move(path: String, newPath: String, overwrite: Boolean) {
            fsCall(
                op = "move",
                path = path,
                extra = buildJsonObject {
                    put("newPath", newPath)
                    put("overwrite", overwrite)
                }
            )
        }

        override fun mkdirs(path: String) {
            fsCall(op = "mkdirs", path = path)
        }

        override fun parentPath(path: String): String? {
            val trimmed = path.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed == "/") return null
            val idx = trimmed.lastIndexOf('/')
            if (idx < 0) return null
            return if (idx == 0) "/" else trimmed.substring(0, idx)
        }

        override fun toDisplayPath(path: String): String = path

        private fun mapFsError(
            path: String,
            e: PluginProcessException,
            maxBytes: Long?
        ): IOException = when (e.code) {
            "NOT_FOUND", "ENOENT" -> NoSuchFileException(path)
            "FILE_EXISTS", "EEXIST" -> FileAlreadyExistsException(path)
            "TOO_LARGE" -> FileReadTooLargeException(maxBytes ?: Long.MAX_VALUE)
            else -> IOException(e.message, e)
        }
    }

    /**
     * 基于插件 `terminal` handler 的 [TerminalSessionProvider] 实现。
     */
    internal inner class PluginTerminalSessionProvider : TerminalSessionProvider {

        private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
        override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

        private fun terminalHandler(): String =
            definition.handlers.terminal
                ?: throw IllegalStateException("执行后端缺少 terminal handler: $id")

        override suspend fun startBackgroundCommand(
            command: String,
            title: String?,
            notify: Boolean,
            sourceSessionId: String?
        ): String {
            val meta = callJson(
                handler = terminalHandler(),
                args = buildJsonObject {
                    put("backendId", id)
                    put("op", "start")
                    put("command", command)
                    if (title != null) put("title", title)
                    put("notify", notify)
                    if (sourceSessionId != null) put("sourceSessionId", sourceSessionId)
                }
            )
            return meta["tabId"]?.stringVal()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("插件 terminal.start 未返回有效的 tabId")
        }

        override suspend fun startPluginCommand(
            command: String,
            title: String?,
            pluginId: String
        ): String {
            val meta = callJson(
                handler = terminalHandler(),
                args = buildJsonObject {
                    put("backendId", id)
                    put("op", "start")
                    put("command", command)
                    if (title != null) put("title", title)
                    put("ownerPluginId", pluginId)
                }
            )
            return meta["tabId"]?.stringVal()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("插件 terminal.start 未返回有效的 tabId")
        }

        override fun tabStatus(id: String): TerminalTabStatus? = runBlocking(Dispatchers.IO) {
            runCatching {
                val meta = callJson(
                    handler = terminalHandler(),
                    args = buildJsonObject {
                        put("backendId", this@PluginExecutionBackendAdapter.id)
                        put("op", "status")
                        put("tabId", id)
                    }
                )
                if (meta["exists"]?.booleanVal() == false) return@runCatching null
                val running = meta["running"]?.booleanVal() ?: return@runCatching null
                val exitCode = meta["exitCode"]?.intVal()
                TerminalTabStatus(running = running, exitCode = exitCode)
            }.getOrNull()
        }

        override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
            runBlocking(Dispatchers.IO) {
                runCatching {
                    val meta = callJson(
                        handler = terminalHandler(),
                        args = buildJsonObject {
                            put("backendId", this@PluginExecutionBackendAdapter.id)
                            put("op", "send")
                            put("tabId", id)
                            put("input", input)
                            put("appendNewline", appendNewline)
                        }
                    )
                    meta["ok"]?.booleanVal() ?: true
                }.getOrDefault(false)
            }

        override fun writeToTab(id: String, text: String): Boolean =
            runBlocking(Dispatchers.IO) {
                runCatching {
                    val meta = callJson(
                        handler = terminalHandler(),
                        args = buildJsonObject {
                            put("backendId", this@PluginExecutionBackendAdapter.id)
                            put("op", "write")
                            put("tabId", id)
                            put("text", text)
                        }
                    )
                    meta["ok"]?.booleanVal() ?: true
                }.getOrDefault(false)
            }

        override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
            runBlocking(Dispatchers.IO) {
                runCatching {
                    val raw = ByteArray(bytes.size) { bytes[it].toByte() }
                    val meta = callJson(
                        handler = terminalHandler(),
                        args = buildJsonObject {
                            put("backendId", this@PluginExecutionBackendAdapter.id)
                            put("op", "writeBytes")
                            put("tabId", id)
                        },
                        payload = raw
                    )
                    meta["ok"]?.booleanVal() ?: true
                }.getOrDefault(false)
            }

        override fun getTabOutput(id: String): String? = runBlocking(Dispatchers.IO) {
            runCatching {
                val result = callBinary(
                    handler = terminalHandler(),
                    args = buildJsonObject {
                        put("backendId", this@PluginExecutionBackendAdapter.id)
                        put("op", "read")
                        put("tabId", id)
                    }
                )
                val meta = unwrapMeta(result)
                if (meta["exists"]?.booleanVal() == false) return@runCatching null
                if (result.payload.isNotEmpty()) {
                    result.payload.toString(Charsets.UTF_8)
                } else {
                    meta["output"]?.stringVal()
                }
            }.getOrNull()
        }

        override fun tabOutput(id: String): Flow<ByteArray>? {
            if (tabStatus(id) == null) return null
            val handler = terminalHandler()
            return flow {
                try {
                    runtimeManager.openBinaryStream(
                        pluginId = definition.pluginId,
                        version = definition.pluginVersion,
                        runtime = definition.runtime,
                        handler = handler,
                        args = buildJsonObject {
                            put("backendId", this@PluginExecutionBackendAdapter.id)
                            put("op", "stream")
                            put("tabId", id)
                        },
                        method = BACKEND_INVOKE_METHOD
                    ).collect { chunk ->
                        if (chunk.payload.isNotEmpty()) {
                            emit(chunk.payload)
                        } else {
                            chunk.meta["text"]?.stringVal()?.takeIf { it.isNotEmpty() }?.let {
                                emit(it.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PluginProcessException) {
                    if (e.code in BINARY_FALLBACK_CODES) {
                        getTabOutput(id)?.takeIf { it.isNotEmpty() }?.let {
                            emit(it.toByteArray(Charsets.UTF_8))
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

        override fun listTabs(): List<TabInfo> = runBlocking(Dispatchers.IO) {
            runCatching {
                val meta = callJson(
                    handler = terminalHandler(),
                    args = buildJsonObject {
                        put("backendId", this@PluginExecutionBackendAdapter.id)
                        put("op", "list")
                    }
                )
                val arr = meta["tabs"] as? JsonArray ?: return@runCatching emptyList()
                arr.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val ownerPluginId = item["ownerPluginId"]?.stringVal()
                    if (ownerPluginId != null) return@mapNotNull null
                    val tabId = item["id"]?.stringVal() ?: return@mapNotNull null
                    TabInfo(
                        id = tabId,
                        title = item["title"]?.stringVal() ?: tabId,
                        isBackground = item["isBackground"]?.booleanVal() ?: true,
                        running = item["running"]?.booleanVal() ?: false,
                        command = item["command"]?.stringVal(),
                        ownerPluginId = null
                    )
                }
            }.getOrDefault(emptyList())
        }

        override fun closeTab(id: String): Boolean = runBlocking(Dispatchers.IO) {
            runCatching {
                val meta = callJson(
                    handler = terminalHandler(),
                    args = buildJsonObject {
                        put("backendId", this@PluginExecutionBackendAdapter.id)
                        put("op", "close")
                        put("tabId", id)
                    }
                )
                meta["ok"]?.booleanVal() ?: true
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val TAG = "PluginExecBackend"
        const val BACKEND_INVOKE_METHOD = "backend.invoke"
        const val HEALTH_TIMEOUT_MS = 15_000L
        const val RPC_OVERHEAD_MS = 5_000L
        const val MAX_FINGERPRINT_BYTES = 8L * 1024 * 1024
        val BINARY_FALLBACK_CODES = setOf(
            "PLUGIN_IPC_UNAVAILABLE",
            "PLUGIN_IPC_NOT_CONNECTED",
            "PLUGIN_IPC_CLOSED"
        )
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun JsonElement.stringVal(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun JsonElement.booleanVal(): Boolean? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        fun JsonElement.intVal(): Int? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        fun JsonElement.longVal(): Long? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
