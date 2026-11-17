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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
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
import kotlinx.serialization.json.put

/** 二进制单次请求的响应或入站请求回复。 */
data class BinaryResult(
    val meta: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryResult) return false
        return meta == other.meta && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * meta.hashCode() + payload.contentHashCode()
}

/** 二进制流式响应的单个数据块。 */
data class BinaryStreamChunk(
    val meta: JsonObject = JsonObject(emptyMap()),
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryStreamChunk) return false
        return meta == other.meta && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * meta.hashCode() + payload.contentHashCode()
}

/** 插件主动发来的二进制请求。 */
data class BinaryIncomingRequest(
    val method: String,
    val meta: JsonObject,
    val payload: ByteArray = BinaryFrame.EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryIncomingRequest) return false
        return method == other.method && meta == other.meta && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + meta.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/** 抽象底层二进制双向流连接，便于测试环境替换 [LocalSocket]。 */
interface BinarySocketConnection : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

/** 二进制通道连接器工厂。 */
fun interface BinaryConnector {
    suspend fun connect(): BinarySocketConnection
}

/** 默认生产连接器：通过 Android [LocalSocket] 连接宿主机侧 Unix Domain Socket 文件。 */
class LocalSocketBinaryConnector(
    private val hostSockFile: File
) : BinaryConnector {
    override suspend fun connect(): BinarySocketConnection = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(hostSockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
            )
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

/**
 * 基于 Unix Domain Socket 的二进制多路复用双向传输通道。
 *
 * 特点：
 * - 建连后立即进行首帧 `PING({"token": ipcToken})` -> `PONG` 鉴权握手；
 * - 双向多路复用（宿主奇数 streamId），支持单帧与超过 [chunkSize] 的 `STREAM_DATA` + `STREAM_END` 分片；
 * - 独立控制队列与分片队列交错写出，确保大流量分片不会阻塞控制请求、心跳与取消通知；
 * - 支持 `openStream` 流式消费及协程取消时自动向对端发送 `STREAM_END` 取消通知。
 */
class PluginBinaryChannel(
    private val pluginId: String,
    private val connector: BinaryConnector,
    private val ipcToken: String,
    private val onRequest: suspend (BinaryIncomingRequest) -> BinaryResult,
    private val onEvent: (method: String, meta: JsonObject, payload: ByteArray) -> Unit,
    private val onClosed: () -> Unit = {},
    private val chunkSize: Int = PluginBinaryCodec.DEFAULT_CHUNK_BYTES,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS
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
    ) : this(
        pluginId = pluginId,
        connector = LocalSocketBinaryConnector(hostSockFile),
        ipcToken = ipcToken,
        onRequest = onRequest,
        onEvent = onEvent,
        onClosed = onClosed,
        chunkSize = chunkSize,
        handshakeTimeoutMs = handshakeTimeoutMs
    )

    private class OutboundPacket(
        val frame: BinaryFrame,
        val ack: CompletableDeferred<Unit>? = null
    )

    private class PendingRequestState {
        val deferred = CompletableDeferred<BinaryResult>()
        var headerMeta: JsonObject = JsonObject(emptyMap())
        var buffer: ByteArrayOutputStream? = null
        var accumulatedBytes: Int = 0
    }

    private class InboundAccumulator(
        val method: String,
        val headerMeta: JsonObject
    ) {
        val buffer = ByteArrayOutputStream()
        var accumulatedBytes: Int = 0
    }

    private sealed interface StreamSignal {
        data class Data(val chunk: BinaryStreamChunk) : StreamSignal
        data class End(val chunk: BinaryStreamChunk) : StreamSignal
        data class Error(val error: PluginProcessException) : StreamSignal
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val streamIdCounter = AtomicInteger(1)
    private val closedFlag = AtomicBoolean(false)
    private val closeNotified = AtomicBoolean(false)

    private val controlQueue = Channel<OutboundPacket>(Channel.UNLIMITED)
    private val dataQueue = Channel<OutboundPacket>(Channel.UNLIMITED)

    private val pendingRequests = ConcurrentHashMap<UInt, PendingRequestState>()
    private val pendingStreams = ConcurrentHashMap<UInt, Channel<StreamSignal>>()
    private val inboundRequests = ConcurrentHashMap<UInt, InboundAccumulator>()
    private val activeInboundJobs = ConcurrentHashMap<UInt, Job>()

    @Volatile private var connection: BinarySocketConnection? = null
    @Volatile private var connectedFlag = false

    val isConnected: Boolean
        get() = connectedFlag && !closedFlag.get()

    /** 建立连接并完成 `PING` -> `PONG` 首帧鉴权握手。 */
    suspend fun connect() = connectMutex.withLock {
        if (closedFlag.get()) {
            throw PluginProcessException("PLUGIN_IPC_CLOSED", "[$pluginId] 二进制 IPC 通道已关闭")
        }
        if (connectedFlag) return@withLock

        val conn = try {
            connector.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PluginProcessException("PLUGIN_IPC_CONNECT_FAILED", "[$pluginId] 连接 IPC socket 失败: ${e.message}")
        }

        try {
            performHandshake(conn)
        } catch (e: Throwable) {
            runCatching { conn.close() }
            if (e is CancellationException) throw e
            if (e is PluginProcessException) throw e
            throw PluginProcessException("PLUGIN_IPC_HANDSHAKE_FAILED", "[$pluginId] IPC 握手失败: ${e.message}")
        }

        connection = conn
        connectedFlag = true
        scope.launch { writeLoop(conn.outputStream) }
        scope.launch { readLoop(conn.inputStream) }
    }

    /** 发送请求并等待完整响应，大 payload 自动按 [chunkSize] 分片发送并支持分片响应聚合。 */
    suspend fun request(
        method: String,
        metaParams: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): BinaryResult {
        ensureConnected()
        val streamId = nextStreamId()
        val state = PendingRequestState()
        pendingRequests[streamId] = state

        try {
            sendOutboundRequest(
                streamId = streamId,
                method = method,
                metaParams = metaParams,
                payload = payload,
                isStreamResponse = false
            )

            val result = withTimeoutOrNull(timeoutMs) { state.deferred.await() }
            if (result == null) {
                tryEnqueueCancelEnd(streamId)
                throw PluginProcessException("PLUGIN_TIMEOUT", "[$pluginId] $method 超时（${timeoutMs}ms）")
            }
            return result
        } finally {
            pendingRequests.remove(streamId)
        }
    }

    /** 发起流式请求，持续产出服务端发回的 [BinaryStreamChunk]，协程取消时发送 `STREAM_END` 通知对端。 */
    fun openStream(
        method: String,
        metaParams: JsonObject = JsonObject(emptyMap()),
        payload: ByteArray = BinaryFrame.EMPTY_BYTES
    ): Flow<BinaryStreamChunk> = channelFlow {
        ensureConnected()
        val streamId = nextStreamId()
        val signalChannel = Channel<StreamSignal>(Channel.UNLIMITED)
        pendingStreams[streamId] = signalChannel
        var completedNormally = false

        try {
            sendOutboundRequest(
                streamId = streamId,
                method = method,
                metaParams = metaParams,
                payload = payload,
                isStreamResponse = true
            )

            for (signal in signalChannel) {
                when (signal) {
                    is StreamSignal.Data -> send(signal.chunk)
                    is StreamSignal.End -> {
                        if (signal.chunk.payload.isNotEmpty()) {
                            send(signal.chunk)
                        }
                        completedNormally = true
                        break
                    }
                    is StreamSignal.Error -> {
                        completedNormally = true
                        throw signal.error
                    }
                }
            }
            if (!completedNormally) {
                throw PluginProcessException("PLUGIN_EXITED", "[$pluginId] 二进制 IPC 流意外结束")
            }
        } finally {
            pendingStreams.remove(streamId)
            signalChannel.close()
            if (!completedNormally && isConnected) {
                tryEnqueueCancelEnd(streamId)
            }
        }
    }

    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        connectedFlag = false
        controlQueue.close()
        dataQueue.close()
        drainQueuesWithError(PluginProcessException("PLUGIN_IPC_CLOSED", "[$pluginId] 二进制 IPC 通道已关闭"))
        runCatching { connection?.close() }
        connection = null
        failAllPending("二进制 IPC 通道已关闭")
        activeInboundJobs.values.toList().forEach { it.cancel() }
        activeInboundJobs.clear()
        scope.cancel()
        if (closeNotified.compareAndSet(false, true)) {
            runCatching { onClosed() }
        }
    }

    private suspend fun performHandshake(conn: BinarySocketConnection) {
        val pingMeta = buildJsonObject { put("token", ipcToken) }
        val pingFrame = BinaryFrame(
            type = BinaryFrameType.PING,
            streamId = CONTROL_STREAM_ID,
            meta = PluginBinaryCodec.encodeMeta(pingMeta)
        )
        val replyDeferred = CompletableDeferred<BinaryFrame?>()
        val handshakeJob = scope.launch {
            try {
                PluginBinaryCodec.writeFrame(conn.outputStream, pingFrame)
                val frame = PluginBinaryCodec.readFrame(conn.inputStream)
                replyDeferred.complete(frame)
            } catch (e: Throwable) {
                replyDeferred.completeExceptionally(e)
            }
        }
        val reply = try {
            withTimeoutOrNull(handshakeTimeoutMs) {
                replyDeferred.await()
            }
        } catch (e: CancellationException) {
            runCatching { conn.close() }
            handshakeJob.cancel()
            throw e
        } catch (e: Throwable) {
            runCatching { conn.close() }
            handshakeJob.cancel()
            throw PluginProcessException(
                "PLUGIN_IPC_HANDSHAKE_FAILED",
                "[$pluginId] IPC 握手失败: ${e.message}"
            )
        }
        if (reply == null) {
            runCatching { conn.close() }
            handshakeJob.cancel()
            throw PluginProcessException(
                "PLUGIN_IPC_HANDSHAKE_FAILED",
                "[$pluginId] IPC 握手超时（${handshakeTimeoutMs}ms）或连接已关闭"
            )
        }

        if (reply.type == BinaryFrameType.ERR) {
            throw parseErrorFrame(reply.meta)
        }
        if (reply.type != BinaryFrameType.PONG) {
            throw PluginProcessException(
                "PLUGIN_IPC_HANDSHAKE_FAILED",
                "[$pluginId] IPC 握手失败：期望 PONG，实际收到 ${reply.type}"
            )
        }
        val pongMeta = runCatching { PluginBinaryCodec.decodeMeta(reply.meta) }.getOrElse {
            throw PluginProcessException("PLUGIN_IPC_HANDSHAKE_FAILED", "[$pluginId] PONG meta 非法: ${it.message}")
        }
        val ok = (pongMeta["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok == false) {
            val msg = (pongMeta["message"] as? JsonPrimitive)?.contentOrNull ?: "token 鉴权失败"
            throw PluginProcessException("PLUGIN_IPC_AUTH_FAILED", "[$pluginId] $msg")
        }
    }

    private fun ensureConnected() {
        if (closedFlag.get() || !connectedFlag) {
            throw PluginProcessException("PLUGIN_IPC_NOT_CONNECTED", "[$pluginId] 二进制 IPC 通道未连接")
        }
    }

    private fun nextStreamId(): UInt {
        while (true) {
            val id = streamIdCounter.getAndAdd(2)
            if (id != 0) return id.toUInt()
        }
    }

    private suspend fun sendOutboundRequest(
        streamId: UInt,
        method: String,
        metaParams: JsonObject,
        payload: ByteArray,
        isStreamResponse: Boolean
    ) {
        val chunkedPayload = payload.size > chunkSize
        val reqMeta = buildJsonObject {
            put("method", method)
            put("args", metaParams)
            if (isStreamResponse) put("stream", true)
            if (chunkedPayload) put("streamed", true)
        }
        val metaBytes = PluginBinaryCodec.encodeMeta(reqMeta)

        if (!chunkedPayload) {
            sendControlAndAwait(BinaryFrame(BinaryFrameType.REQ, streamId, metaBytes, payload))
            return
        }

        sendControlAndAwait(BinaryFrame(BinaryFrameType.REQ, streamId, metaBytes, BinaryFrame.EMPTY_BYTES))
        sendChunkedPayload(streamId, payload)
    }

    private suspend fun sendChunkedPayload(streamId: UInt, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val slice = payload.copyOfRange(offset, end)
            sendDataAndAwait(BinaryFrame(BinaryFrameType.STREAM_DATA, streamId, BinaryFrame.EMPTY_BYTES, slice))
            offset = end
        }
        sendControlAndAwait(BinaryFrame(BinaryFrameType.STREAM_END, streamId))
    }

    private suspend fun sendControlAndAwait(frame: BinaryFrame) {
        sendAndAwait(controlQueue, frame)
    }

    private suspend fun sendDataAndAwait(frame: BinaryFrame) {
        sendAndAwait(dataQueue, frame)
    }

    private suspend fun sendAndAwait(queue: Channel<OutboundPacket>, frame: BinaryFrame) {
        if (closedFlag.get()) {
            throw PluginProcessException("PLUGIN_IPC_CLOSED", "[$pluginId] 二进制 IPC 通道已关闭")
        }
        val ack = CompletableDeferred<Unit>()
        val result = queue.trySend(OutboundPacket(frame, ack))
        if (result.isFailure) {
            throw PluginProcessException("PLUGIN_IPC_CLOSED", "[$pluginId] 二进制 IPC 写入队列已关闭")
        }
        try {
            ack.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginProcessException) {
            throw e
        } catch (e: Throwable) {
            throw PluginProcessException("PLUGIN_WRITE_FAILED", "[$pluginId] 写入 IPC 帧失败: ${e.message}")
        }
    }

    private fun tryEnqueueCancelEnd(streamId: UInt) {
        if (closedFlag.get()) return
        val meta = PluginBinaryCodec.encodeMeta(buildJsonObject { put("cancelled", true) })
        controlQueue.trySend(
            OutboundPacket(BinaryFrame(BinaryFrameType.STREAM_END, streamId, meta, BinaryFrame.EMPTY_BYTES))
        )
    }

    private suspend fun writeLoop(output: OutputStream) {
        try {
            while (currentCoroutineContext().isActive && !closedFlag.get()) {
                val packet = controlQueue.tryReceive().getOrNull()
                    ?: dataQueue.tryReceive().getOrNull()
                    ?: select<OutboundPacket?> {
                        controlQueue.onReceiveCatching { it.getOrNull() }
                        dataQueue.onReceiveCatching { it.getOrNull() }
                    }
                    ?: break

                try {
                    PluginBinaryCodec.writeFrame(output, packet.frame)
                    packet.ack?.complete(Unit)
                } catch (e: Throwable) {
                    packet.ack?.completeExceptionally(e)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            // 协程取消属正常关闭路径
        } catch (e: Throwable) {
            if (!closedFlag.get()) {
                FileLogger.w(TAG, "[$pluginId] 二进制 IPC 写循环异常结束: ${e.message}")
                close()
            }
        } finally {
            drainQueuesWithError(PluginProcessException("PLUGIN_WRITE_FAILED", "[$pluginId] 二进制 IPC 写循环已停止"))
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (!closedFlag.get()) {
                val frame = PluginBinaryCodec.readFrame(input) ?: break
                handleIncomingFrame(frame)
            }
        } catch (e: Exception) {
            if (!closedFlag.get()) {
                FileLogger.w(TAG, "[$pluginId] 二进制 IPC 读循环异常结束: ${e.message}")
            }
        } finally {
            close()
        }
    }

    private fun handleIncomingFrame(frame: BinaryFrame) {
        when (frame.type) {
            BinaryFrameType.PING -> {
                controlQueue.trySend(OutboundPacket(BinaryFrame(BinaryFrameType.PONG, frame.streamId, frame.meta)))
            }
            BinaryFrameType.PONG -> Unit
            BinaryFrameType.EVENT -> handleIncomingEvent(frame)
            BinaryFrameType.REQ -> handleIncomingReq(frame)
            BinaryFrameType.RESP -> handleIncomingResp(frame)
            BinaryFrameType.ERR -> handleIncomingErr(frame)
            BinaryFrameType.STREAM_DATA -> handleIncomingStreamData(frame)
            BinaryFrameType.STREAM_END -> handleIncomingStreamEnd(frame)
        }
    }

    private fun handleIncomingEvent(frame: BinaryFrame) {
        val meta = runCatching { PluginBinaryCodec.decodeMeta(frame.meta) }.getOrElse {
            FileLogger.w(TAG, "[$pluginId] EVENT meta 非法: ${it.message}")
            return
        }
        val method = (meta["method"] as? JsonPrimitive)?.contentOrNull
            ?: (meta["event"] as? JsonPrimitive)?.contentOrNull
            ?: "plugin.event"
        runCatching { onEvent(method, meta, frame.payload) }
            .onFailure { FileLogger.w(TAG, "[$pluginId] 处理二进制事件 $method 失败", it) }
    }

    private fun handleIncomingReq(frame: BinaryFrame) {
        val meta = runCatching { PluginBinaryCodec.decodeMeta(frame.meta) }.getOrElse {
            sendErrorReply(frame.streamId, "PARAM_INVALID", "请求 meta 非法: ${it.message}")
            return
        }
        val method = (meta["method"] as? JsonPrimitive)?.contentOrNull
        if (method.isNullOrBlank()) {
            sendErrorReply(frame.streamId, "METHOD_MISSING", "请求缺少 method")
            return
        }
        val isStreamed = (meta["streamed"] as? JsonPrimitive)?.booleanOrNull == true
        if (isStreamed) {
            val acc = InboundAccumulator(method, meta)
            if (frame.payload.isNotEmpty()) {
                acc.buffer.write(frame.payload)
                acc.accumulatedBytes = frame.payload.size
            }
            inboundRequests[frame.streamId] = acc
        } else {
            dispatchInboundRequest(frame.streamId, BinaryIncomingRequest(method, meta, frame.payload))
        }
    }

    private fun handleIncomingResp(frame: BinaryFrame) {
        val meta = runCatching { PluginBinaryCodec.decodeMeta(frame.meta) }.getOrElse {
            val err = PluginProcessException("PLUGIN_PROTOCOL_ERROR", "[$pluginId] 响应 meta 非法: ${it.message}")
            pendingRequests.remove(frame.streamId)?.deferred?.completeExceptionally(err)
            pendingStreams.remove(frame.streamId)?.let { ch ->
                ch.trySend(StreamSignal.Error(err))
                ch.close()
            }
            return
        }
        val isStreamed = (meta["streamed"] as? JsonPrimitive)?.booleanOrNull == true

        pendingRequests[frame.streamId]?.let { state ->
            if (isStreamed) {
                state.headerMeta = meta
                if (frame.payload.isNotEmpty()) {
                    val out = state.buffer ?: ByteArrayOutputStream().also { state.buffer = it }
                    out.write(frame.payload)
                    state.accumulatedBytes += frame.payload.size
                }
            } else {
                pendingRequests.remove(frame.streamId)?.deferred?.complete(BinaryResult(meta, frame.payload))
            }
            return
        }

        pendingStreams[frame.streamId]?.let { ch ->
            if (isStreamed) {
                if (frame.payload.isNotEmpty()) {
                    ch.trySend(StreamSignal.Data(BinaryStreamChunk(meta, frame.payload)))
                }
            } else {
                if (frame.payload.isNotEmpty() || meta.isNotEmpty()) {
                    ch.trySend(StreamSignal.Data(BinaryStreamChunk(meta, frame.payload)))
                }
                pendingStreams.remove(frame.streamId)
                ch.trySend(StreamSignal.End(BinaryStreamChunk()))
                ch.close()
            }
            return
        }

        FileLogger.w(TAG, "[$pluginId] 收到无匹配请求的 RESP streamId=${frame.streamId}")
    }

    private fun handleIncomingErr(frame: BinaryFrame) {
        val err = parseErrorFrame(frame.meta)
        pendingRequests.remove(frame.streamId)?.let { state ->
            state.deferred.completeExceptionally(err)
            return
        }
        pendingStreams.remove(frame.streamId)?.let { ch ->
            ch.trySend(StreamSignal.Error(err))
            ch.close()
            return
        }
        inboundRequests.remove(frame.streamId)
        activeInboundJobs.remove(frame.streamId)?.cancel()
    }

    private fun handleIncomingStreamData(frame: BinaryFrame) {
        val meta = runCatching { PluginBinaryCodec.decodeMeta(frame.meta) }.getOrElse {
            val err = PluginProcessException("PLUGIN_PROTOCOL_ERROR", "[$pluginId] 流数据 meta 非法: ${it.message}")
            pendingRequests.remove(frame.streamId)?.deferred?.completeExceptionally(err)
            pendingStreams.remove(frame.streamId)?.let { ch ->
                ch.trySend(StreamSignal.Error(err))
                ch.close()
            }
            return
        }

        pendingStreams[frame.streamId]?.let { ch ->
            ch.trySend(StreamSignal.Data(BinaryStreamChunk(meta, frame.payload)))
            return
        }

        pendingRequests[frame.streamId]?.let { state ->
            val nextTotal = state.accumulatedBytes.toLong() + frame.payload.size
            if (nextTotal > PluginBinaryCodec.MAX_PAYLOAD_BYTES) {
                pendingRequests.remove(frame.streamId)
                state.deferred.completeExceptionally(
                    PluginProcessException(
                        "PLUGIN_PAYLOAD_TOO_LARGE",
                        "[$pluginId] 响应流大小超过上限 ${PluginBinaryCodec.MAX_PAYLOAD_BYTES} 字节"
                    )
                )
                tryEnqueueCancelEnd(frame.streamId)
                return
            }
            val out = state.buffer ?: ByteArrayOutputStream().also { state.buffer = it }
            if (frame.payload.isNotEmpty()) {
                out.write(frame.payload)
                state.accumulatedBytes = nextTotal.toInt()
            }
            return
        }

        inboundRequests[frame.streamId]?.let { acc ->
            val nextTotal = acc.accumulatedBytes.toLong() + frame.payload.size
            if (nextTotal > PluginBinaryCodec.MAX_PAYLOAD_BYTES) {
                inboundRequests.remove(frame.streamId)
                sendErrorReply(
                    frame.streamId,
                    "PAYLOAD_TOO_LARGE",
                    "请求流大小超过上限 ${PluginBinaryCodec.MAX_PAYLOAD_BYTES} 字节"
                )
                return
            }
            if (frame.payload.isNotEmpty()) {
                acc.buffer.write(frame.payload)
                acc.accumulatedBytes = nextTotal.toInt()
            }
        }
    }

    private fun handleIncomingStreamEnd(frame: BinaryFrame) {
        val endMeta = runCatching { PluginBinaryCodec.decodeMeta(frame.meta) }.getOrElse { JsonObject(emptyMap()) }

        pendingStreams.remove(frame.streamId)?.let { ch ->
            ch.trySend(StreamSignal.End(BinaryStreamChunk(endMeta, frame.payload)))
            ch.close()
            return
        }

        pendingRequests.remove(frame.streamId)?.let { state ->
            val nextTotal = state.accumulatedBytes.toLong() + frame.payload.size
            if (nextTotal > PluginBinaryCodec.MAX_PAYLOAD_BYTES) {
                state.deferred.completeExceptionally(
                    PluginProcessException(
                        "PLUGIN_PAYLOAD_TOO_LARGE",
                        "[$pluginId] 响应流大小超过上限 ${PluginBinaryCodec.MAX_PAYLOAD_BYTES} 字节"
                    )
                )
                return
            }
            if (frame.payload.isNotEmpty()) {
                val out = state.buffer ?: ByteArrayOutputStream().also { state.buffer = it }
                out.write(frame.payload)
            }
            val mergedMeta = mergeMeta(state.headerMeta, endMeta)
            val payload = state.buffer?.toByteArray() ?: BinaryFrame.EMPTY_BYTES
            state.deferred.complete(BinaryResult(mergedMeta, payload))
            return
        }

        inboundRequests.remove(frame.streamId)?.let { acc ->
            if ((endMeta["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
                return
            }
            val nextTotal = acc.accumulatedBytes.toLong() + frame.payload.size
            if (nextTotal > PluginBinaryCodec.MAX_PAYLOAD_BYTES) {
                sendErrorReply(
                    frame.streamId,
                    "PAYLOAD_TOO_LARGE",
                    "请求流大小超过上限 ${PluginBinaryCodec.MAX_PAYLOAD_BYTES} 字节"
                )
                return
            }
            if (frame.payload.isNotEmpty()) {
                acc.buffer.write(frame.payload)
            }
            val mergedMeta = mergeMeta(acc.headerMeta, endMeta)
            dispatchInboundRequest(
                frame.streamId,
                BinaryIncomingRequest(acc.method, mergedMeta, acc.buffer.toByteArray())
            )
            return
        }

        if ((endMeta["cancelled"] as? JsonPrimitive)?.booleanOrNull == true) {
            activeInboundJobs.remove(frame.streamId)?.cancel()
        }
    }

    private fun dispatchInboundRequest(streamId: UInt, req: BinaryIncomingRequest) {
        lateinit var job: Job
        job = scope.launch {
            try {
                val reply = try {
                    Result.success(onRequest(req))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                reply.onSuccess { result ->
                    runCatching { sendInboundSuccess(streamId, result) }
                        .onFailure { FileLogger.w(TAG, "[$pluginId] 回写二进制响应 ${req.method} 失败", it) }
                }.onFailure { error ->
                    val code = (error as? PluginProcessException)?.code ?: "HOST_ERROR"
                    sendErrorReply(streamId, code, error.message ?: error.javaClass.simpleName)
                }
            } finally {
                activeInboundJobs.remove(streamId, job)
            }
        }
        activeInboundJobs[streamId] = job
    }

    private suspend fun sendInboundSuccess(streamId: UInt, result: BinaryResult) {
        if (result.payload.size <= chunkSize) {
            val metaBytes = PluginBinaryCodec.encodeMeta(result.meta)
            sendControlAndAwait(BinaryFrame(BinaryFrameType.RESP, streamId, metaBytes, result.payload))
            return
        }
        val headerMeta = buildJsonObject {
            result.meta.forEach { (k, v) -> put(k, v) }
            put("streamed", true)
        }
        sendControlAndAwait(
            BinaryFrame(
                type = BinaryFrameType.RESP,
                streamId = streamId,
                meta = PluginBinaryCodec.encodeMeta(headerMeta),
                payload = BinaryFrame.EMPTY_BYTES
            )
        )
        sendChunkedPayload(streamId, result.payload)
    }

    private fun sendErrorReply(streamId: UInt, code: String, message: String) {
        if (closedFlag.get()) return
        val meta = PluginBinaryCodec.encodeMeta(buildJsonObject {
            put("code", code)
            put("message", message)
        })
        controlQueue.trySend(
            OutboundPacket(BinaryFrame(BinaryFrameType.ERR, streamId, meta, BinaryFrame.EMPTY_BYTES))
        )
    }

    private fun parseErrorFrame(metaBytes: ByteArray): PluginProcessException {
        val meta = runCatching { PluginBinaryCodec.decodeMeta(metaBytes) }.getOrDefault(JsonObject(emptyMap()))
        return parseErrorFrame(meta)
    }

    private fun parseErrorFrame(meta: JsonObject): PluginProcessException {
        val code = (meta["code"] as? JsonPrimitive)?.contentOrNull ?: "PLUGIN_ERROR"
        val message = (meta["message"] as? JsonPrimitive)?.contentOrNull ?: "插件返回错误"
        return PluginProcessException(code, "[$pluginId] $message")
    }

    private fun mergeMeta(base: JsonObject, overlay: JsonObject): JsonObject {
        if (overlay.isEmpty()) return base
        if (base.isEmpty()) return overlay
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            overlay.forEach { (k, v) -> put(k, v) }
        }
    }

    private fun drainQueuesWithError(error: Throwable) {
        while (true) {
            val pkt = controlQueue.tryReceive().getOrNull() ?: break
            pkt.ack?.completeExceptionally(error)
        }
        while (true) {
            val pkt = dataQueue.tryReceive().getOrNull() ?: break
            pkt.ack?.completeExceptionally(error)
        }
    }

    private fun failAllPending(reason: String) {
        val ex = PluginProcessException("PLUGIN_EXITED", "[$pluginId] $reason")
        pendingRequests.keys.toList().forEach { id ->
            pendingRequests.remove(id)?.deferred?.completeExceptionally(ex)
        }
        pendingStreams.keys.toList().forEach { id ->
            pendingStreams.remove(id)?.let { ch ->
                ch.trySend(StreamSignal.Error(ex))
                ch.close()
            }
        }
        inboundRequests.clear()
    }

    private companion object {
        const val TAG = "PluginBinaryChannel"
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
        val CONTROL_STREAM_ID: UInt = 0u
    }
}
