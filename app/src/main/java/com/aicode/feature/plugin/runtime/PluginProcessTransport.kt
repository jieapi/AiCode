package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
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
    private val onExit: () -> Unit,
    /** 为 false 时常驻进程在单次请求超时后保留，避免一个慢 handler 拖垮后续调用。 */
    private val closeOnTimeout: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    /** 发送请求并等待插件返回的 `result`；超时默认结束进程，常驻进程可保留（见 [closeOnTimeout]）。 */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        ensureStarted()
        val id = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        FileLogger.d(TAG, "→ [$pluginId] $method id=$id")
        try {
            try {
                writeLine(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PluginProcessException("PLUGIN_WRITE_FAILED", "[$pluginId] 写入 stdin 失败: ${e.message}")
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                if (closeOnTimeout) close()
                throw PluginProcessException("PLUGIN_TIMEOUT", "[$pluginId] $method 超时（${timeoutMs}ms）")
            }
            return result
        } finally {
            pending.remove(id)
        }
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
        val reader = BoundedLineReader(p.inputStream.buffered(), MAX_STDOUT_LINE_BYTES)
        try {
            while (true) {
                val read = reader.readLine()
                if (read is BoundedLineReader.Line.Eof) break
                if (read is BoundedLineReader.Line.Overlong) {
                    FileLogger.w(
                        TAG,
                        "[$pluginId] stdout 单行超过 ${MAX_STDOUT_LINE_BYTES} 字节上限，已丢弃 ${read.bytes} 字节"
                    )
                    continue
                }
                val line = (read as BoundedLineReader.Line.Text).text
                if (line.isBlank()) continue
                val obj = runCatching { JSON.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (obj == null) {
                    FileLogger.i(TAG, "[$pluginId] 跳过非 JSON 行: ${line.take(LOG_PREVIEW_CHARS)}")
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
            val reply = try {
                Result.success(onRequest(method, params))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
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

    /** stderr 只做诊断：单行有上限，日志按时间窗限流，避免插件刷屏拖垮日志 IO。 */
    private fun drainStderr(p: Process) {
        val reader = BoundedLineReader(p.errorStream.buffered(), MAX_STDERR_LINE_BYTES)
        var lastLogMs = Long.MIN_VALUE
        var suppressed = 0
        try {
            while (true) {
                val read = reader.readLine()
                if (read is BoundedLineReader.Line.Eof) break
                if (read is BoundedLineReader.Line.Overlong) {
                    suppressed++
                    continue
                }
                val line = (read as BoundedLineReader.Line.Text).text
                if (line.isBlank()) continue
                val now = System.nanoTime() / 1_000_000
                if (lastLogMs != Long.MIN_VALUE && now - lastLogMs < STDERR_LOG_INTERVAL_MS) {
                    suppressed++
                    continue
                }
                lastLogMs = now
                val omitted = if (suppressed > 0) "（省略 $suppressed 行）" else ""
                FileLogger.i(TAG, "[$pluginId] stderr: ${line.take(LOG_PREVIEW_CHARS)}$omitted")
                suppressed = 0
            }
        } catch (e: Exception) {
            // 进程退出时流会被关闭，属于正常结束路径
        } finally {
            if (suppressed > 0) FileLogger.i(TAG, "[$pluginId] stderr 省略了 $suppressed 行输出")
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

    /**
     * 按字节读一行并限制长度：正常行原样返回，超过 [maxBytes] 的行整行丢弃，
     * 只回报丢弃的字节数，避免恶意或失控的插件输出占满堆与日志。
     */
    private class BoundedLineReader(private val input: InputStream, private val maxBytes: Int) {

        sealed interface Line {
            data class Text(val text: String) : Line
            data class Overlong(val bytes: Long) : Line
            data object Eof : Line
        }

        private val buffer = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))

        fun readLine(): Line {
            buffer.reset()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    return if (buffer.size() == 0) Line.Eof else Line.Text(decode())
                }
                if (b == '\n'.code) return Line.Text(decode())
                if (buffer.size() >= maxBytes) {
                    var discarded = buffer.size().toLong() + 1
                    while (true) {
                        val next = input.read()
                        if (next == -1) return Line.Overlong(discarded)
                        if (next == '\n'.code) return Line.Overlong(discarded)
                        discarded++
                    }
                }
                buffer.write(b)
            }
        }

        private fun decode(): String = String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')

        private companion object {
            const val INITIAL_BUFFER_BYTES = 8 * 1024
        }
    }

    private companion object {
        const val TAG = "PluginProcess"
        const val EXIT_WAIT_MS = 2_000L

        /** stdout 单行字节上限；正常 JSON-RPC 结果远小于该值。 */
        const val MAX_STDOUT_LINE_BYTES = 4 * 1024 * 1024

        /** stderr 单行字节上限；超长行整行丢弃并计入限流统计。 */
        const val MAX_STDERR_LINE_BYTES = 64 * 1024

        /** stderr 日志最短间隔：插件密集输出时最多每 200ms 记一条。 */
        const val STDERR_LOG_INTERVAL_MS = 200L

        /** 单条输出日志保留的字符数，避免非 JSON 行与 stderr 刷屏。 */
        const val LOG_PREVIEW_CHARS = 300

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}
