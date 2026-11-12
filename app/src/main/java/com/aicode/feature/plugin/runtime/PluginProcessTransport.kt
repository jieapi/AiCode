package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 插件进程调用失败；[code] 是宿主与插件都可判断的稳定错误码。 */
class PluginProcessException(val code: String, override val message: String) : Exception(message)

/**
 * 插件进程的 NDJSON JSON-RPC 通道。
 *
 * 宿主与插件都按行读写 JSON：带 `id` 与 `method` 的是请求，带 `id` 无 `method` 的是响应，
 * 无 `id` 带 `method` 的是通知。插件主动发来的请求交给 [onRequest] 处理（host API），
 * 通知交给 [onNotification]（插件事件），两者都带 `method` 与 `params`。
 */
class PluginProcessTransport(
    private val pluginId: String,
    private val engine: LinuxContainerEngine,
    private val profile: ContainerProfile,
    private val workingDir: String,
    private val program: String,
    private val programArgs: List<String>,
    private val extraEnv: Map<String, String>,
    private val onRequest: suspend (method: String, params: JsonObject) -> JsonElement,
    private val onNotification: (method: String, params: JsonObject) -> Unit,
    private val onExit: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    /** 发送请求并等待插件返回的 `result`；超时会结束进程，避免死循环拖住调用方。 */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        ensureStarted()
        val id = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        FileLogger.d(TAG, "→ [$pluginId] $method id=$id")
        try {
            writeLine(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
        } catch (e: Exception) {
            pending.remove(id)
            throw PluginProcessException("PLUGIN_WRITE_FAILED", "[$pluginId] 写入 stdin 失败: ${e.message}")
        }

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) {
            pending.remove(id)
            close()
            throw PluginProcessException("PLUGIN_TIMEOUT", "[$pluginId] $method 超时（${timeoutMs}ms）")
        }
        return result
    }

    fun close() {
        if (closed) return
        closed = true
        FileLogger.i(TAG, "[$pluginId] 关闭插件进程")
        scope.cancel()
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
        failAllPending("插件进程已关闭")
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        if (process != null) return@withLock
        if (closed) throw PluginProcessException("PLUGIN_CLOSED", "[$pluginId] 运行时已关闭")
        FileLogger.i(TAG, "[$pluginId] 启动插件进程: $program ${programArgs.joinToString(" ")}")
        val p = try {
            engine.startStdioProcess(
                program = program,
                programArgs = programArgs,
                projectPath = null,
                extraEnv = extraEnv,
                profile = profile,
                workingDir = workingDir
            )
        } catch (e: Exception) {
            throw PluginProcessException("PLUGIN_START_FAILED", "[$pluginId] 启动进程失败: ${e.message}")
        }
        process = p
        writer = p.outputStream.bufferedWriter()
        scope.launch { readLoop(p) }
        scope.launch { drainStderr(p) }
    }

    private suspend fun writeLine(obj: JsonObject) = writeMutex.withLock {
        val w = writer ?: throw PluginProcessException("PLUGIN_NOT_STARTED", "[$pluginId] 进程未启动")
        withContext(Dispatchers.IO) {
            w.write(obj.toString())
            w.write("\n")
            w.flush()
        }
    }

    private fun readLoop(p: Process) {
        val reader = p.inputStream.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (obj == null) {
                    FileLogger.i(TAG, "[$pluginId] 跳过非 JSON 行: $line")
                    continue
                }
                val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
                val idElement = obj["id"]
                when {
                    method != null && idElement != null -> handlePeerRequest(idElement, method, obj)
                    method != null -> handleNotification(method, obj)
                    idElement != null -> handleResponse(idElement, obj)
                    else -> FileLogger.d(TAG, "[$pluginId] 忽略无 id 无 method 的消息")
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "[$pluginId] stdout 读循环异常结束: ${e.message}")
        } finally {
            FileLogger.i(TAG, "[$pluginId] stdout 已结束")
            logExitCode(p)
            failAllPending("插件进程 stdout 已关闭")
            onExit()
        }
    }

    private fun handlePeerRequest(idElement: JsonElement, method: String, obj: JsonObject) {
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val reply = runCatching { onRequest(method, params) }
            reply.onSuccess { result ->
                runCatching {
                    writeLine(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", idElement)
                        put("result", result)
                    })
                }.onFailure { FileLogger.w(TAG, "[$pluginId] 回写 $method 响应失败", it) }
            }.onFailure { error ->
                val code = (error as? PluginProcessException)?.code ?: "HOST_ERROR"
                runCatching {
                    writeLine(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", idElement)
                        put("error", buildJsonObject {
                            put("code", code)
                            put("message", error.message ?: error.javaClass.simpleName)
                        })
                    })
                }.onFailure { FileLogger.w(TAG, "[$pluginId] 回写 $method 错误失败", it) }
            }
        }
    }

    private fun handleNotification(method: String, obj: JsonObject) {
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        runCatching { onNotification(method, params) }
            .onFailure { FileLogger.w(TAG, "[$pluginId] 处理通知 $method 失败", it) }
    }

    private fun handleResponse(idElement: JsonElement, obj: JsonObject) {
        val id = (idElement as? JsonPrimitive)?.longOrNull ?: return
        val deferred = pending.remove(id) ?: run {
            FileLogger.w(TAG, "[$pluginId] 收到无匹配请求的响应 id=$id")
            return
        }
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.contentOrNull ?: "PLUGIN_ERROR"
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "插件返回错误"
            deferred.completeExceptionally(PluginProcessException(code, "[$pluginId] $message"))
            return
        }
        deferred.complete(obj["result"] ?: JsonNull)
    }

    private fun drainStderr(p: Process) {
        runCatching {
            p.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) FileLogger.i(TAG, "[$pluginId] stderr: $line")
            }
        }
    }

    private fun logExitCode(p: Process) {
        val exited = runCatching { p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) return
        val code = runCatching { p.exitValue() }.getOrNull() ?: return
        if (code == 0) FileLogger.i(TAG, "[$pluginId] 进程退出 exitCode=0")
        else FileLogger.w(TAG, "[$pluginId] 进程退出 exitCode=$code")
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(PluginProcessException("PLUGIN_EXITED", "[$pluginId] $reason"))
        }
    }

    private companion object {
        const val TAG = "PluginProcess"
        const val EXIT_WAIT_MS = 2_000L
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}
