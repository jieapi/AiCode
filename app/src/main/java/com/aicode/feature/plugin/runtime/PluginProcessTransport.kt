package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
open class PluginProcessException(val code: String, override val message: String) : Exception(message)

/** 仅表示本地确定尚未将请求写入对端的 IPC 未就绪状态，可安全回退到 NDJSON。 */
class PluginIpcUnavailableException(message: String) : PluginProcessException("PLUGIN_IPC_UNAVAILABLE", message)

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
    private val closeOnTimeout: Boolean = true,
    private val ipcSocketFile: File? = null,
    private val ipcToken: String? = null,
    private val binaryConnectorFactory: ((File) -> BinaryConnector)? = null,
    private val onBinaryRequest: (suspend (BinaryIncomingRequest) -> BinaryResult)? = null,
    private val onBinaryEvent: ((method: String, meta: JsonObject, payload: ByteArray) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val ipcMutex = Mutex()
    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var binaryChannel: PluginBinaryChannel? = null
    @Volatile private var closed = false

    val isBinaryConnected: Boolean
        get() = binaryChannel?.isConnected == true

    val activeRequests: Int
        get() = binaryChannel?.activeRequests ?: pending.size

    val isBusy: Boolean
        get() = activeRequests > 0

    /** 发送请求并等待插件返回的 `result`；优先走二进制 IPC 通道，未建连时无缝走 NDJSON；超时默认结束进程，常驻进程可保留（见 [closeOnTimeout]）。 */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        ensureStarted()
        val channel = binaryChannel
        if (channel != null && channel.isConnected) {
            try {
                val result = channel.request(method = method, metaParams = params, timeoutMs = timeoutMs)
                return decodeBinaryResultToJson(result)
            } catch (e: PluginIpcUnavailableException) {
                if (closed) throw e
                FileLogger.w(TAG, "[$pluginId] 二进制 IPC 未就绪，回退 NDJSON: ${e.message}")
            } catch (e: PluginProcessException) {
                if (e.code == "PLUGIN_TIMEOUT" && closeOnTimeout) close()
                throw e
            }
        }
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

    /** 通过二进制 IPC 通道发送带原始字节 payload 的请求。 */
    suspend fun requestBinary(
        method: String,
        metaParams: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = DEFAULT_BINARY_TIMEOUT_MS
    ): BinaryResult {
        ensureStarted()
        val channel = binaryChannel?.takeIf { it.isConnected }
            ?: throw PluginIpcUnavailableException("[$pluginId] 二进制 IPC 通道未就绪")
        return try {
            channel.request(method = method, metaParams = metaParams, payload = payload, timeoutMs = timeoutMs)
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_TIMEOUT" && closeOnTimeout) close()
            throw e
        }
    }

    /** 通过二进制 IPC 通道开启流式请求，接收插件侧持续推送的数据块。 */
    fun openBinaryStream(
        method: String,
        metaParams: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = DEFAULT_BINARY_TIMEOUT_MS
    ): Flow<BinaryStreamChunk> = flow {
        ensureStarted()
        val channel = binaryChannel?.takeIf { it.isConnected }
            ?: throw PluginIpcUnavailableException("[$pluginId] 二进制 IPC 通道未就绪")
        emitAll(channel.openStream(method = method, metaParams = metaParams, payload = payload, timeoutMs = timeoutMs))
    }

    suspend fun uploadBinary(
        method: String,
        metaParams: JsonObject,
        payload: Flow<ByteArray>,
        timeoutMs: Long = DEFAULT_BINARY_TIMEOUT_MS
    ): BinaryResult {
        ensureStarted()
        val channel = binaryChannel?.takeIf { it.isConnected }
            ?: throw PluginIpcUnavailableException("[$pluginId] 二进制 IPC 通道未就绪")
        return try {
            channel.upload(method = method, metaParams = metaParams, payload = payload, timeoutMs = timeoutMs)
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_TIMEOUT" && closeOnTimeout) close()
            throw e
        }
    }

    fun close() {
        if (closed) return
        closed = true
        FileLogger.i(TAG, "[$pluginId] 关闭插件进程")
        scope.cancel()
        runCatching { binaryChannel?.close() }
        binaryChannel = null
        runCatching { ipcSocketFile?.delete() }
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
        val reader = BoundedLineReader(p.inputStream, MAX_STDOUT_LINE_BYTES)
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
            runCatching { binaryChannel?.close() }
            binaryChannel = null
            runCatching { ipcSocketFile?.delete() }
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
        if (method == IPC_READY_METHOD) {
            scope.launch { connectBinaryChannel() }
            return
        }
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        runCatching { onNotification(method, params) }
            .onFailure { FileLogger.w(TAG, "[$pluginId] 处理通知 $method 失败", it) }
    }

    private suspend fun connectBinaryChannel() = ipcMutex.withLock {
        if (closed || binaryChannel?.isConnected == true) return@withLock
        val sockFile = ipcSocketFile ?: return@withLock
        val token = ipcToken?.takeIf { it.isNotEmpty() } ?: return@withLock
        val connector = binaryConnectorFactory?.invoke(sockFile) ?: LocalSocketBinaryConnector(sockFile)
        lateinit var channel: PluginBinaryChannel
        channel = PluginBinaryChannel(
            pluginId = pluginId,
            connector = connector,
            ipcToken = token,
            onRequest = { req ->
                onBinaryRequest?.invoke(req) ?: throw PluginProcessException(
                    "BINARY_UNSUPPORTED", "[$pluginId] 未绑定二进制 host 请求: ${req.method}"
                )
            },
            onEvent = { eventMethod, meta, payload ->
                onBinaryEvent?.invoke(eventMethod, meta, payload)
                    ?: throw PluginProcessException(
                        "BINARY_UNSUPPORTED", "[$pluginId] 未绑定二进制事件: $eventMethod"
                    )
            },
            onClosed = {
                if (binaryChannel === channel) {
                    binaryChannel = null
                }
            }
        )
        try {
            channel.connect()
            if (closed) {
                channel.close()
                return@withLock
            }
            binaryChannel = channel
            FileLogger.i(TAG, "[$pluginId] 二进制 IPC 通道已就绪")
        } catch (e: CancellationException) {
            runCatching { channel.close() }
            throw e
        } catch (e: Exception) {
            runCatching { channel.close() }
            FileLogger.w(TAG, "[$pluginId] 二进制 IPC 建连/握手失败，保持 NDJSON 通道: ${e.message}")
        }
    }

    private suspend fun handleBinaryPeerRequest(req: BinaryIncomingRequest): BinaryResult {
        val params = (req.meta["args"] as? JsonObject)
            ?: (req.meta["params"] as? JsonObject)
            ?: req.meta
        val result = onRequest(req.method, params)
        val wrapped = buildJsonObject { put("result", result) }
        val wrappedBytes = wrapped.toString().toByteArray(Charsets.UTF_8)
        return if (wrappedBytes.size <= PluginBinaryCodec.DEFAULT_CHUNK_BYTES) {
            BinaryResult(meta = wrapped, payload = BinaryFrame.EMPTY_BYTES)
        } else {
            BinaryResult(
                meta = buildJsonObject { put("encoding", "json") },
                payload = result.toString().toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun decodeBinaryResultToJson(result: BinaryResult): JsonElement {
        if (result.meta.containsKey("result")) {
            return result.meta["result"] ?: JsonNull
        }
        if (result.payload.isNotEmpty()) {
            val text = result.payload.toString(Charsets.UTF_8)
            return runCatching { JSON.parseToJsonElement(text) }
                .getOrElse { JsonPrimitive(text) }
        }
        return if (result.meta.isEmpty()) JsonNull else result.meta
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
        val reader = BoundedLineReader(p.errorStream, MAX_STDERR_LINE_BYTES)
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
     * 按字节读一行并限制长度：使用 8 KiB 块缓冲区批量读取并扫描 `\n`，
     * 正常行原样返回，超过 [maxBytes] 的行整行丢弃并回报丢弃的字节数，
     * 避免恶意或失控的插件输出占满堆与日志。
     */
    internal class BoundedLineReader(private val input: InputStream, private val maxBytes: Int) {

        sealed interface Line {
            data class Text(val text: String) : Line
            data class Overlong(val bytes: Long) : Line
            data object Eof : Line
        }

        private val chunk = ByteArray(CHUNK_BYTES)
        private var chunkPos = 0
        private var chunkLen = 0
        private val buffer = ByteArrayOutputStream(minOf(maxBytes, CHUNK_BYTES))

        fun readLine(): Line {
            buffer.reset()
            while (true) {
                if (chunkPos >= chunkLen) {
                    val read = input.read(chunk)
                    if (read == -1) {
                        chunkPos = 0
                        chunkLen = 0
                        return if (buffer.size() == 0) Line.Eof else Line.Text(decode())
                    }
                    if (read == 0) continue
                    chunkPos = 0
                    chunkLen = read
                }
                val start = chunkPos
                var newlineIndex = -1
                for (i in start until chunkLen) {
                    if (chunk[i] == NEWLINE_BYTE) {
                        newlineIndex = i
                        break
                    }
                }
                if (newlineIndex >= 0) {
                    val segmentLen = newlineIndex - start
                    chunkPos = newlineIndex + 1
                    val totalBytes = buffer.size().toLong() + segmentLen
                    if (totalBytes > maxBytes) {
                        return Line.Overlong(totalBytes)
                    }
                    if (buffer.size() == 0) {
                        return Line.Text(String(chunk, start, segmentLen, Charsets.UTF_8).trimEnd('\r'))
                    }
                    if (segmentLen > 0) buffer.write(chunk, start, segmentLen)
                    return Line.Text(decode())
                }
                val segmentLen = chunkLen - start
                chunkPos = chunkLen
                val totalBytes = buffer.size().toLong() + segmentLen
                if (totalBytes > maxBytes) {
                    return discardUntilNewline(totalBytes)
                }
                if (segmentLen > 0) buffer.write(chunk, start, segmentLen)
            }
        }

        private fun discardUntilNewline(initialDiscarded: Long): Line.Overlong {
            var discarded = initialDiscarded
            while (true) {
                val read = input.read(chunk)
                if (read == -1) {
                    chunkPos = 0
                    chunkLen = 0
                    return Line.Overlong(discarded)
                }
                if (read == 0) continue
                for (i in 0 until read) {
                    if (chunk[i] == NEWLINE_BYTE) {
                        discarded += i
                        chunkPos = i + 1
                        chunkLen = read
                        return Line.Overlong(discarded)
                    }
                }
                discarded += read
            }
        }

        private fun decode(): String = String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')

        private companion object {
            const val CHUNK_BYTES = 8 * 1024
            const val NEWLINE_BYTE = '\n'.code.toByte()
        }
    }

    private companion object {
        const val TAG = "PluginProcess"
        const val EXIT_WAIT_MS = 2_000L
        const val DEFAULT_BINARY_TIMEOUT_MS = 120_000L
        const val IPC_READY_METHOD = "ipc.ready"

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
