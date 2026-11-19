package com.aicode.feature.plugin.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.aicode.core.util.FileLogger
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 二进制单次请求的响应或入站请求回复。 */
data class BinaryResult(
    val meta: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean =
        other is BinaryResult && meta == other.meta && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * meta.hashCode() + payload.contentHashCode()
}

/** [terminal] 标记成功终态；即使没有 payload，终态元数据也会交付。 */
data class BinaryStreamChunk(
    val meta: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES,
    val terminal: Boolean = false
) {
    override fun equals(other: Any?): Boolean = other is BinaryStreamChunk &&
        meta == other.meta && payload.contentEquals(other.payload) && terminal == other.terminal

    override fun hashCode(): Int = 31 * (31 * meta.hashCode() + payload.contentHashCode()) + terminal.hashCode()
}

/** 插件主动发来的二进制请求。 */
data class BinaryIncomingRequest(
    val method: String,
    val meta: JsonObject,
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean = other is BinaryIncomingRequest &&
        method == other.method && meta == other.meta && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * method.hashCode() + meta.hashCode()) + payload.contentHashCode()
}

interface BinarySocketConnection : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

fun interface BinaryConnector {
    suspend fun connect(): BinarySocketConnection
}

class LocalSocketBinaryConnector(private val hostSockFile: File) : BinaryConnector {
    override suspend fun connect(): BinarySocketConnection = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(hostSockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            object : BinarySocketConnection {
                override val inputStream: InputStream = socket.inputStream
                override val outputStream: OutputStream = socket.outputStream
                override fun close() {
                    runCatching { socket.shutdownInput() }
                    runCatching { socket.shutdownOutput() }
                    socket.close()
                }
            }
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }
}

class PluginBinaryChannel(
    private val pluginId: String,
    private val connector: BinaryConnector,
    private val ipcToken: String,
    private val onRequest: suspend (BinaryIncomingRequest) -> BinaryResult,
    private val onEvent: (method: String, meta: JsonObject, payload: ByteArray) -> Unit,
    private val onClosed: () -> Unit = {},
    private val chunkSize: Int = PluginBinaryCodec.DEFAULT_CHUNK_BYTES,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS
) : Closeable {
    constructor(
        pluginId: String,
        hostSockFile: File,
        ipcToken: String,
        onRequest: suspend (BinaryIncomingRequest) -> BinaryResult,
        onEvent: (method: String, meta: JsonObject, payload: ByteArray) -> Unit,
        onClosed: () -> Unit = {},
        chunkSize: Int = PluginBinaryCodec.DEFAULT_CHUNK_BYTES,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS
    ) : this(pluginId, LocalSocketBinaryConnector(hostSockFile), ipcToken, onRequest, onEvent,
        onClosed, chunkSize, handshakeTimeoutMs)

    private data class Packet(val frame: BinaryFrame, val ack: CompletableDeferred<Unit>? = null)
    private data class StreamItem(val chunk: BinaryStreamChunk, val cost: Int = 0)

    private class Call(val streaming: Boolean, val upload: Boolean) {
        val result = CompletableDeferred<BinaryResult>()
        val chunks = if (streaming) Channel<StreamItem>(WINDOW / MIN_COST + 1) else null
        val sendCredit = AtomicInteger(WINDOW)
        val creditChanged = Channel<Unit>(Channel.CONFLATED)
        var receiveCredit = WINDOW
        var receivedHeader = false
        var fragmented = false
        var meta = EMPTY_META
        var accumulator: ByteArrayOutputStream? = null
        var streamBytes: Long = 0
        var reserved = 0
        var done = false
        var submitted = false
        @Volatile var uploadEnding = false
    }

    private class Inbound(val method: String, val meta: JsonObject, var reserved: Int) {
        val sendCredit = AtomicInteger(WINDOW)
        val creditChanged = Channel<Unit>(Channel.CONFLATED)
        var accumulator: ByteArrayOutputStream? = null
        var job: Job? = null
        var expiry: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val streamIdCounter = AtomicInteger(1)
    private val closed = AtomicBoolean(false)
    private val lifecycle = Any()
    private val calls = ConcurrentHashMap<UInt, Call>()
    private val inbound = ConcurrentHashMap<UInt, Inbound>()
    private val inboundHandlers = AtomicInteger()
    private val queuedBytes = AtomicInteger()
    private val controlQueue = Channel<Packet>(CONTROL_QUEUE_SIZE)
    private val dataQueue = Channel<Packet>(MAX_CALLS * 2)
    private val events = Channel<BinaryFrame>(EVENT_QUEUE_SIZE)
    private val writeChunkBytes = chunkSize.coerceIn(1, PluginBinaryCodec.DEFAULT_CHUNK_BYTES)

    @Volatile private var connection: BinarySocketConnection? = null
    @Volatile private var connected = false
    @Volatile private var creditNegotiated = false

    val isConnected: Boolean get() = connected && !closed.get()
    val activeRequests: Int get() = calls.size + inbound.size

    suspend fun connect() = connectMutex.withLock {
        if (isConnected) return@withLock
        if (closed.get()) throw unavailable()
        val conn = try {
            connector.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PluginProcessException("PLUGIN_IPC_CONNECT_FAILED", "[$pluginId] 连接 IPC socket 失败: ${e.message}")
        }
        try {
            synchronized(lifecycle) {
                if (closed.get()) throw unavailable()
                connection = conn
            }
            performHandshake(conn)
            synchronized(lifecycle) {
                if (closed.get()) throw unavailable()
                connected = true
            }
        } catch (e: Throwable) {
            runCatching { conn.close() }
            if (e is CancellationException || e is PluginProcessException) throw e
            throw PluginProcessException("PLUGIN_IPC_HANDSHAKE_FAILED", "[$pluginId] IPC 握手失败: ${e.message}")
        }
        scope.launch { writeLoop(conn.outputStream) }
        scope.launch { readLoop(conn.inputStream) }
        scope.launch {
            for (frame in events) {
                try {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    val method = string(meta, "method") ?: string(meta, "name") ?: string(meta, "event") ?: "plugin.event"
                    onEvent(method, meta, frame.payload)
                } catch (e: Exception) {
                    FileLogger.w(TAG, "[$pluginId] 处理二进制事件失败", e)
                } finally {
                    release(frame.meta.size + frame.payload.size)
                }
            }
        }
    }

    suspend fun request(
        method: String,
        metaParams: JsonObject = EMPTY_META,
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): BinaryResult = exchange(method, payload.size, timeoutMs) { id, call ->
        sendRequest(id, call, method, metaParams, payload)
        call.result.await()
    }

    fun openStream(
        method: String,
        metaParams: JsonObject = EMPTY_META,
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): Flow<BinaryStreamChunk> = flow {
        exchange(method, payload.size, timeoutMs, streaming = true) { id, call ->
            sendRequest(id, call, method, metaParams, payload)
            for (item in checkNotNull(call.chunks)) {
                emit(item.chunk)
                val update = synchronized(call) {
                    call.receiveCredit += item.cost
                    item.cost > 0 && !call.done && creditNegotiated
                }
                if (update) enqueueControl(frame(BinaryFrameType.WINDOW_UPDATE, id,
                    buildJsonObject { put("bytes", item.cost) }))
            }
        }
    }

    suspend fun upload(
        method: String,
        metaParams: JsonObject,
        payload: Flow<ByteArray>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): BinaryResult = exchange(method, 0, timeoutMs, upload = true) { id, call ->
        if (!creditNegotiated) throw PluginProcessException("BINARY_UNSUPPORTED", "[$pluginId] 对端不支持流式上传")
        sendRequest(id, call, method, metaParams, BinaryFrame.EMPTY_BYTES)
        coroutineScope {
            val sender = launch {
                payload.collect { bytes ->
                    sendPayload(id, bytes) { size -> takeCredit(call, PluginBinaryCodec.streamFrameCost(0, size)) }
                }
                call.uploadEnding = true
                send(frame(BinaryFrameType.STREAM_END, id, buildJsonObject { put("ok", true) }))
            }
            try {
                call.result.await()
            } finally {
                sender.cancel()
            }
        }
    }

    private suspend fun <T : Any> exchange(
        method: String,
        payloadBytes: Int,
        timeoutMs: Long,
        streaming: Boolean = false,
        upload: Boolean = false,
        block: suspend (UInt, Call) -> T
    ): T {
        if (!streaming && !upload && payloadBytes > PluginBinaryCodec.MAX_PAYLOAD_BYTES) throw payloadTooLarge()
        val call = Call(streaming, upload)
        val id = synchronized(lifecycle) {
            if (!isConnected) throw unavailable()
            if (calls.size >= MAX_CALLS || !reserve(payloadBytes)) throw limited()
            var next: UInt
            do { next = (streamIdCounter.getAndAdd(2) and Int.MAX_VALUE).toUInt() } while (calls.containsKey(next))
            calls[next] = call
            next
        }
        try {
            return withTimeoutOrNull(timeoutMs) { block(id, call) }
                ?: throw PluginProcessException("PLUGIN_TIMEOUT", "[$pluginId] $method 超时（${timeoutMs}ms）")
        } finally {
            val cancel = synchronized(call) {
                calls.remove(id, call)
                val notify = call.submitted && !call.done
                call.done = true
                call.chunks?.cancel()
                release(call.reserved + payloadBytes)
                notify
            }
            if (cancel) cancelPeer(id)
        }
    }

    private suspend fun performHandshake(conn: BinarySocketConnection) {
        val reply = CompletableDeferred<BinaryFrame?>()
        val io = scope.launch {
            try {
                PluginBinaryCodec.writeFrame(conn.outputStream, frame(BinaryFrameType.PING, 0u,
                    buildJsonObject { put("token", ipcToken); put("window", WINDOW) }))
                reply.complete(PluginBinaryCodec.readFrame(conn.inputStream))
            } catch (e: Exception) {
                reply.completeExceptionally(e)
            }
        }
        try {
            val pong = withTimeoutOrNull(handshakeTimeoutMs) { reply.await() }
            if (pong?.type != BinaryFrameType.PONG || pong.streamId != 0u) {
                throw PluginProcessException("PLUGIN_IPC_HANDSHAKE_FAILED", "[$pluginId] IPC 未收到有效 PONG")
            }
            val meta = PluginBinaryCodec.decodeMeta(pong.meta)
            if (bool(meta, "ok") == false) {
                throw PluginProcessException("PLUGIN_IPC_AUTH_FAILED", "[$pluginId] IPC 鉴权失败")
            }
            creditNegotiated = (meta["window"] as? JsonPrimitive)?.intOrNull == WINDOW
        } finally {
            io.cancel()
        }
    }

    private suspend fun sendRequest(id: UInt, call: Call, method: String, args: JsonObject, payload: ByteArray) {
        val fragmented = payload.size > writeChunkBytes
        val req = frame(BinaryFrameType.REQ, id, buildJsonObject {
            put("method", method)
            put("args", args)
            put("stream", call.streaming)
            if (fragmented) put("streamed", true)
            if (call.upload) put("upload", true)
        }, if (fragmented) BinaryFrame.EMPTY_BYTES else payload)
        if (!isConnected) throw unavailable()
        send(req) { call.submitted = true }
        if (fragmented) {
            sendPayload(id, payload) { if (call.result.isCompleted) call.result.await() }
            send(frame(BinaryFrameType.STREAM_END, id))
        }
    }

    private suspend fun sendPayload(id: UInt, bytes: ByteArray, before: suspend (Int) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(writeChunkBytes, bytes.size - offset)
            before(size)
            send(BinaryFrame(BinaryFrameType.STREAM_DATA, id, payload = bytes.copyOfRange(offset, offset + size)))
            offset += size
        }
    }

    private suspend fun takeCredit(call: Call, cost: Int) {
        while (true) {
            val available = call.sendCredit.get()
            if (available >= cost && call.sendCredit.compareAndSet(available, available - cost)) return
            if (available < cost) call.creditChanged.receive()
        }
    }

    private suspend fun send(frame: BinaryFrame, queued: () -> Unit = {}) {
        if (frame.meta.size > PluginBinaryCodec.MAX_META_BYTES) {
            throw PluginProcessException("META_TOO_LARGE", "[$pluginId] IPC 帧元数据超出上限")
        }
        val ack = CompletableDeferred<Unit>()
        val queue = if (frame.type == BinaryFrameType.STREAM_DATA) dataQueue else controlQueue
        try {
            queue.send(Packet(frame, ack))
            queued()
            ack.await()
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            throw exited()
        } finally {
            ack.cancel()
        }
    }

    private fun enqueueControl(frame: BinaryFrame) {
        if (closed.get()) return
        if (controlQueue.trySend(Packet(frame)).isFailure) {
            FileLogger.w(TAG, "[$pluginId] IPC_LIMIT: 控制帧队列已满")
            close()
        }
    }

    private fun cancelPeer(id: UInt) = enqueueControl(frame(BinaryFrameType.STREAM_END, id,
        buildJsonObject { put("cancelled", true) }))

    private suspend fun writeLoop(output: OutputStream) {
        var packet: Packet? = null
        try {
            while (!closed.get()) {
                val next = controlQueue.tryReceive().getOrNull() ?: dataQueue.tryReceive().getOrNull()
                    ?: select {
                        controlQueue.onReceiveCatching { it.getOrNull() }
                        dataQueue.onReceiveCatching { it.getOrNull() }
                    } ?: break
                packet = next
                if (next.ack?.isCancelled == true) continue
                val watchdog = scope.launch {
                    delay(writeTimeoutMs)
                    FileLogger.w(TAG, "[$pluginId] IPC 写入超时")
                    close()
                }
                try {
                    PluginBinaryCodec.writeFrame(output, next.frame)
                    next.ack?.complete(Unit)
                } finally {
                    watchdog.cancel()
                }
                packet = null
            }
        } catch (e: Exception) {
            if (!closed.get()) FileLogger.w(TAG, "[$pluginId] IPC 写入失败: ${e.message}")
        } finally {
            packet?.ack?.completeExceptionally(exited())
            close()
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (!closed.get()) dispatch(PluginBinaryCodec.readFrame(input) ?: break)
        } catch (e: Exception) {
            if (!closed.get()) FileLogger.w(TAG, "[$pluginId] IPC 读循环异常: ${e.message}")
        } finally {
            close()
        }
    }

    private fun dispatch(frame: BinaryFrame) {
        when (frame.type) {
            BinaryFrameType.PING -> enqueueControl(BinaryFrame(BinaryFrameType.PONG, frame.streamId, frame.meta))
            BinaryFrameType.PONG -> Unit
            BinaryFrameType.EVENT -> {
                val cost = frame.meta.size + frame.payload.size
                if (!reserve(cost)) throw limited()
                if (events.trySend(frame).isFailure) {
                    release(cost)
                    throw limited()
                }
            }
            BinaryFrameType.REQ -> receiveRequest(frame)
            else -> {
                val call = calls[frame.streamId]
                if (call != null) receiveResult(frame, call) else receiveRequestBody(frame)
            }
        }
    }

    private fun receiveResult(frame: BinaryFrame, call: Call) = synchronized(call) {
        if (call.done) return@synchronized
        try {
            val meta = PluginBinaryCodec.decodeMeta(frame.meta)
            when (frame.type) {
                BinaryFrameType.ERR -> finish(call, remoteError(meta))
                BinaryFrameType.WINDOW_UPDATE -> {
                    if (!call.upload || !creditNegotiated || frame.payload.isNotEmpty()) throw protocolError()
                    val bytes = (meta["bytes"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (bytes !in 1..WINDOW || call.sendCredit.addAndGet(bytes) > WINDOW) throw limited()
                    call.creditChanged.trySend(Unit)
                }
                BinaryFrameType.RESP -> {
                    if (call.receivedHeader) throw protocolError()
                    call.receivedHeader = true
                    call.fragmented = bool(meta, "streamed") == true
                    if (call.fragmented) {
                        if (call.streaming) {
                            if (meta.keys.any { it != "streamed" } || frame.payload.isNotEmpty()) queueStream(call, frame, meta)
                        } else {
                            appendResult(call, frame)
                            call.meta = JsonObject(meta.filterKeys { it != "streamed" })
                        }
                    } else {
                        if (!call.streaming) appendResult(call, frame)
                        completeResult(call, frame, meta)
                    }
                }
                BinaryFrameType.STREAM_DATA -> {
                    if (call.streaming) queueStream(call, frame, meta)
                    else {
                        if (!call.fragmented || frame.meta.isNotEmpty()) throw protocolError()
                        appendResult(call, frame)
                    }
                }
                BinaryFrameType.STREAM_END -> {
                    val error = terminalError(meta)
                    if (error != null) finish(call, error)
                    else {
                        if (!call.streaming && !call.fragmented) throw protocolError()
                        if (!call.streaming) appendResult(call, frame)
                        completeResult(call, frame, JsonObject(call.meta + meta))
                    }
                }
                else -> throw protocolError()
            }
        } catch (e: Exception) {
            finish(call, e as? PluginProcessException ?: protocolError())
            cancelPeer(frame.streamId)
        }
    }

    private fun appendResult(call: Call, frame: BinaryFrame) {
        val current = call.accumulator?.size() ?: 0
        if (current.toLong() + frame.payload.size > PluginBinaryCodec.MAX_STREAM_BYTES) throw payloadTooLarge()
        val bytes = frame.meta.size + frame.payload.size
        if (!reserve(bytes)) throw limited()
        call.reserved += bytes
        if (frame.payload.isNotEmpty()) {
            val out = call.accumulator ?: ByteArrayOutputStream().also { call.accumulator = it }
            out.write(frame.payload)
        }
    }

    private fun queueStream(call: Call, frame: BinaryFrame, meta: JsonObject) {
        if (call.streamBytes + frame.payload.size > PluginBinaryCodec.MAX_STREAM_BYTES) throw payloadTooLarge()
        call.streamBytes += frame.payload.size
        val cost = PluginBinaryCodec.streamFrameCost(frame.meta.size, frame.payload.size)
        if (cost > call.receiveCredit) throw limited()
        call.receiveCredit -= cost
        if (checkNotNull(call.chunks).trySend(StreamItem(BinaryStreamChunk(meta, frame.payload), cost)).isFailure) {
            throw limited()
        }
    }

    private fun completeResult(call: Call, frame: BinaryFrame, meta: JsonObject) {
        if (call.upload && !call.uploadEnding) throw protocolError()
        val error = if (call.streaming) terminalError(meta) else null
        if (error != null) {
            finish(call, error)
            return
        }
        if (call.streaming) {
            if (call.streamBytes + frame.payload.size > PluginBinaryCodec.MAX_STREAM_BYTES) throw payloadTooLarge()
            if (frame.meta.size + frame.payload.size > WINDOW) throw limited()
            if (meta.isNotEmpty() || frame.payload.isNotEmpty()) {
                if (checkNotNull(call.chunks).trySend(StreamItem(BinaryStreamChunk(meta, frame.payload, terminal = true))).isFailure) {
                    throw limited()
                }
            }
            finish(call)
        } else {
            val bytes = call.accumulator?.toByteArray() ?: BinaryFrame.EMPTY_BYTES
            call.accumulator = null
            call.done = true
            call.result.complete(BinaryResult(meta, bytes))
        }
    }

    private fun finish(call: Call, error: PluginProcessException? = null) {
        call.done = true
        call.chunks?.close(error)
        if (error != null) call.result.completeExceptionally(error)
    }

    private fun receiveRequest(frame: BinaryFrame) {
        val id = frame.streamId
        if (id < 0x80000001u || inbound.containsKey(id)) {
            sendError(id, protocolError())
            return
        }
        val meta = PluginBinaryCodec.decodeMeta(frame.meta)
        val method = string(meta, "method")
        if (method.isNullOrBlank() || bool(meta, "upload") == true) {
            sendError(id, PluginProcessException(if (method.isNullOrBlank()) "METHOD_MISSING" else "BINARY_UNSUPPORTED",
                "[$pluginId] 不支持该 IPC 请求"))
            return
        }
        val bytes = frame.meta.size + frame.payload.size
        if (inbound.size >= MAX_CALLS || inboundHandlers.get() >= MAX_CALLS || !reserve(bytes)) {
            sendError(id, limited())
            return
        }
        val req = Inbound(method, meta, bytes)
        synchronized(req) {
            synchronized(lifecycle) {
                if (closed.get()) {
                    release(bytes)
                    return
                }
                inbound[id] = req
            }
            if (bool(meta, "streamed") == true) {
                req.accumulator = ByteArrayOutputStream().apply { write(frame.payload) }
                req.expiry = scope.launch {
                    delay(DEFAULT_REQUEST_TIMEOUT_MS)
                    synchronized(req) {
                        if (req.job == null && removeInbound(id, req)) {
                            sendError(id, PluginProcessException("IPC_TIMEOUT", "[$pluginId] IPC 请求体接收超时"))
                        }
                    }
                }
            } else dispatchRequest(id, req, meta, frame.payload)
        }
    }

    private fun receiveRequestBody(frame: BinaryFrame) {
        val id = frame.streamId
        val req = inbound[id] ?: return
        synchronized(req) {
            if (inbound[id] !== req) return
            if (frame.type == BinaryFrameType.WINDOW_UPDATE) {
                val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                if (!creditNegotiated || frame.payload.isNotEmpty()) throw limited()
                val bytes = (meta["bytes"] as? JsonPrimitive)?.intOrNull ?: 0
                if (bytes !in 1..WINDOW || req.sendCredit.addAndGet(bytes) > WINDOW) throw limited()
                req.creditChanged.trySend(Unit)
                return
            }
            try {
                val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                if ((frame.type == BinaryFrameType.STREAM_END && isCancelled(meta)) || frame.type == BinaryFrameType.ERR) {
                    req.job?.cancel()
                    if (req.job == null) removeInbound(id, req)
                    return
                }
                val out = req.accumulator ?: throw protocolError()
                if (frame.type != BinaryFrameType.STREAM_DATA && frame.type != BinaryFrameType.STREAM_END) throw protocolError()
                if (frame.type == BinaryFrameType.STREAM_END) terminalError(meta)?.let { throw it }
                if (frame.type == BinaryFrameType.STREAM_DATA && frame.meta.isNotEmpty()) throw protocolError()
                if (out.size().toLong() + frame.payload.size > PluginBinaryCodec.MAX_STREAM_BYTES) throw payloadTooLarge()
                val bytes = frame.meta.size + frame.payload.size
                if (!reserve(bytes)) throw limited()
                req.reserved += bytes
                out.write(frame.payload)
                if (frame.type == BinaryFrameType.STREAM_END) {
                    req.accumulator = null
                    dispatchRequest(id, req, JsonObject(req.meta + meta), out.toByteArray())
                }
            } catch (e: Exception) {
                req.job?.cancel()
                if (req.job == null) removeInbound(id, req)
                sendError(id, e as? PluginProcessException ?: protocolError())
            }
        }
    }

    private fun dispatchRequest(id: UInt, req: Inbound, meta: JsonObject, payload: ByteArray) {
        req.expiry?.cancel()
        inboundHandlers.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val sent = withTimeoutOrNull(DEFAULT_REQUEST_TIMEOUT_MS) {
                    val result = onRequest(BinaryIncomingRequest(req.method, meta, payload))
                    currentCoroutineContext().ensureActive()
                    if (result.payload.size.toLong() > PluginBinaryCodec.MAX_STREAM_BYTES) throw payloadTooLarge()
                    if (!reserve(result.payload.size)) throw limited()
                    try {
                        if (result.payload.size <= writeChunkBytes) {
                            send(frame(BinaryFrameType.RESP, id, result.meta, result.payload))
                        } else {
                            send(frame(BinaryFrameType.RESP, id, JsonObject(result.meta + ("streamed" to JsonPrimitive(true)))))
                            sendPayload(id, result.payload) { size -> takeCredit(req, PluginBinaryCodec.streamFrameCost(0, size)) }
                            send(frame(BinaryFrameType.STREAM_END, id))
                        }
                    } finally {
                        release(result.payload.size)
                    }
                    true
                }
                if (sent == null) sendError(id, PluginProcessException("IPC_TIMEOUT", "[$pluginId] IPC 入站请求超时"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(id, e as? PluginProcessException ?: PluginProcessException("HOST_ERROR", e.message.orEmpty()))
            }
        }
        req.job = job
        job.invokeOnCompletion {
            synchronized(req) { removeInbound(id, req) }
            inboundHandlers.decrementAndGet()
        }
        job.start()
    }

    private suspend fun takeCredit(req: Inbound, cost: Int) {
        while (true) {
            val available = req.sendCredit.get()
            if (available >= cost && req.sendCredit.compareAndSet(available, available - cost)) return
            if (available < cost) req.creditChanged.receive()
        }
    }

    private fun removeInbound(id: UInt, req: Inbound): Boolean {
        if (!inbound.remove(id, req)) return false
        req.expiry?.cancel()
        release(req.reserved)
        return true
    }

    private fun reserve(bytes: Int): Boolean {
        while (true) {
            val current = queuedBytes.get()
            if (bytes > BUFFER_BUDGET - current) return false
            if (queuedBytes.compareAndSet(current, current + bytes)) return true
        }
    }

    private fun release(bytes: Int) { queuedBytes.addAndGet(-bytes) }

    private fun sendError(id: UInt, error: PluginProcessException) = enqueueControl(frame(BinaryFrameType.ERR, id,
        buildJsonObject { put("code", error.code.take(128)); put("message", error.message.take(2000)) }))

    private fun remoteError(meta: JsonObject, defaultCode: String = "PLUGIN_ERROR"): PluginProcessException {
        val code = string(meta, "code") ?: defaultCode
        val safeCode = if (code in LOCAL_CODES) "PLUGIN_REMOTE_ERROR" else code
        val message = string(meta, "message") ?: "插件返回错误"
        return PluginProcessException(safeCode, "[$pluginId] $message")
    }

    private fun terminalError(meta: JsonObject): PluginProcessException? {
        val result = meta["result"] as? JsonObject
        val status = string(result ?: EMPTY_META, "status") ?: string(meta, "status")
        val type = string(result ?: EMPTY_META, "type")
        if (isCancelled(meta) || (result != null && isCancelled(result)) || status in CANCELLED_STATUSES || type in CANCELLED_STATUSES) {
            return PluginProcessException("IPC_CANCELLED", "[$pluginId] IPC 流已取消")
        }
        val error = meta["error"] ?: result?.get("error")
        if (error is JsonObject) return remoteError(error)
        if (error != null && error.toString() != "null" || bool(meta, "ok") == false ||
            result?.let { bool(it, "ok") } == false || status in FAILED_STATUSES || type in FAILED_STATUSES) {
            return remoteError(result ?: meta, "IPC_STREAM_FAILED")
        }
        return null
    }

    private fun unavailable() = PluginIpcUnavailableException("[$pluginId] 二进制 IPC 通道未连接")
    private fun exited() = PluginProcessException("PLUGIN_EXITED", "[$pluginId] 二进制 IPC 通道已关闭")
    private fun limited() = PluginProcessException("IPC_LIMIT", "[$pluginId] IPC 请求数或缓冲字节超出上限")
    private fun payloadTooLarge() = PluginProcessException("PAYLOAD_TOO_LARGE", "[$pluginId] 有限 RPC payload 超出上限")
    private fun protocolError() = PluginProcessException("PLUGIN_PROTOCOL_ERROR", "[$pluginId] IPC 帧不符合请求状态")

    override fun close() {
        synchronized(lifecycle) {
            if (!closed.compareAndSet(false, true)) return
            connected = false
        }
        val ex = exited()
        controlQueue.close()
        dataQueue.close()
        events.close()
        while (true) controlQueue.tryReceive().getOrNull()?.let { it.ack?.completeExceptionally(ex) } ?: break
        while (true) dataQueue.tryReceive().getOrNull()?.let { it.ack?.completeExceptionally(ex) } ?: break
        while (true) events.tryReceive().getOrNull()?.let { release(it.meta.size + it.payload.size) } ?: break
        calls.values.forEach { call -> synchronized(call) { if (!call.done) finish(call, ex) } }
        inbound.forEach { (id, req) ->
            synchronized(req) {
                req.job?.cancel()
                if (req.job == null) removeInbound(id, req)
            }
        }
        runCatching { connection?.close() }
        connection = null
        scope.cancel()
        runCatching { onClosed() }
    }

    private companion object {
        const val TAG = "PluginBinaryChannel"
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
        const val DEFAULT_WRITE_TIMEOUT_MS = 10_000L
        const val MAX_CALLS = 8
        const val WINDOW = PluginBinaryCodec.STREAM_WINDOW_BYTES
        const val MIN_COST = PluginBinaryCodec.MIN_STREAM_FRAME_COST
        const val BUFFER_BUDGET = 48 * 1024 * 1024
        const val CONTROL_QUEUE_SIZE = 32
        const val EVENT_QUEUE_SIZE = 8
        val EMPTY_META = JsonObject(emptyMap())
        val CANCELLED_STATUSES = setOf("cancelled", "canceled", "aborted")
        val FAILED_STATUSES = setOf("failed", "error")
        val LOCAL_CODES = setOf("PLUGIN_IPC_UNAVAILABLE", "PLUGIN_IPC_NOT_CONNECTED", "PLUGIN_IPC_CLOSED", "PLUGIN_EXITED", "PLUGIN_TIMEOUT")

        fun bool(meta: JsonObject, key: String) = (meta[key] as? JsonPrimitive)?.booleanOrNull
        fun string(meta: JsonObject, key: String) = (meta[key] as? JsonPrimitive)?.contentOrNull
        fun isCancelled(meta: JsonObject) = bool(meta, "cancelled") == true || bool(meta, "cancel") == true
        fun frame(type: BinaryFrameType, id: UInt, meta: JsonObject = EMPTY_META, payload: ByteArray = BinaryFrame.EMPTY_BYTES) =
            BinaryFrame(type, id, PluginBinaryCodec.encodeMeta(meta), payload)
    }
}
