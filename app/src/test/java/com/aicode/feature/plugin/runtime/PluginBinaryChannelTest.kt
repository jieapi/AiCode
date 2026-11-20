package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.Called
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginBinaryChannelTest {

    @Test
    fun handshake_succeedsWithValidTokenAndRejectsInvalidResponses() = runBlocking {
        val sockPath = Files.createTempDirectory("ipc-hs-").resolve("test.sock").toFile()
        val expectedToken = "0123456789abcdef0123456789abcdef"

        // 1. 正常握手
        val serverOk = UnixTestServer(sockPath) { frame, writeFrame ->
            assertEquals(BinaryFrameType.PING, frame.type)
            val meta = PluginBinaryCodec.decodeMeta(frame.meta)
            assertEquals(expectedToken, meta["token"]?.jsonPrimitive?.content)
            writeFrame(
                BinaryFrame(
                    BinaryFrameType.PONG,
                    frame.streamId,
                    PluginBinaryCodec.encodeMeta(buildJsonObject { put("ok", true) })
                )
            )
        }
        serverOk.use {
            val channel = createChannel(sockPath, expectedToken)
            channel.connect()
            assertTrue(channel.isConnected)
            channel.close()
            assertFalse(channel.isConnected)
        }

        // 2. 服务端返回鉴权失败 ERR
        val sockErr = Files.createTempDirectory("ipc-err-").resolve("test.sock").toFile()
        UnixTestServer(sockErr) { frame, writeFrame ->
            writeFrame(
                BinaryFrame(
                    BinaryFrameType.ERR,
                    frame.streamId,
                    PluginBinaryCodec.encodeMeta(buildJsonObject {
                        put("code", "UNAUTHORIZED")
                        put("message", "bad token")
                    })
                )
            )
        }.use {
            val channel = createChannel(sockErr, "wrong-token")
            val ex = runCatching { channel.connect() }.exceptionOrNull() as? PluginProcessException
            assertEquals("PLUGIN_IPC_HANDSHAKE_FAILED", ex?.code)
            channel.close()
        }

        // 3. 握手超时
        val sockTimeout = Files.createTempDirectory("ipc-to-").resolve("test.sock").toFile()
        UnixTestServer(sockTimeout) { _, _ ->
            // 故意不回复 PONG
            Thread.sleep(500)
        }.use {
            val channel = PluginBinaryChannel(
                pluginId = "test-plugin",
                connector = unixConnector(sockTimeout),
                ipcToken = expectedToken,
                onRequest = { BinaryResult() },
                onEvent = { _, _, _ -> },
                handshakeTimeoutMs = 150L
            )
            val ex = runCatching { channel.connect() }.exceptionOrNull() as? PluginProcessException
            assertEquals("PLUGIN_IPC_HANDSHAKE_FAILED", ex?.code)
            channel.close()
        }
    }

    @Test
    fun multiplexing_large4MiBPayloadInterleavesWithFastControlRequests() = runBlocking {
        val sockPath = Files.createTempDirectory("ipc-mux-").resolve("test.sock").toFile()
        val token = "abcdef0123456789abcdef0123456789"
        val receivedLabels = CopyOnWriteArrayList<String>()
        val firstChunkArrived = CountDownLatch(1)
        val inboundBuffers = ConcurrentHashMap<UInt, ByteArrayOutputStream>()
        val inboundMethods = ConcurrentHashMap<UInt, String>()

        val server = UnixTestServer(sockPath) { frame, writeFrame ->
            when (frame.type) {
                BinaryFrameType.PING -> {
                    receivedLabels.add("PING")
                    writeFrame(BinaryFrame(BinaryFrameType.PONG, frame.streamId))
                }
                BinaryFrameType.REQ -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    val method = meta["method"]!!.jsonPrimitive.content
                    receivedLabels.add("REQ:$method")
                    val streamed = meta["streamed"]?.jsonPrimitive?.booleanOrNull == true
                    if (streamed) {
                        inboundMethods[frame.streamId] = method
                        inboundBuffers[frame.streamId] = ByteArrayOutputStream().apply {
                            if (frame.payload.isNotEmpty()) write(frame.payload)
                        }
                    } else {
                        // 快速控制请求立即响应
                        writeFrame(
                            BinaryFrame(
                                BinaryFrameType.RESP,
                                frame.streamId,
                                PluginBinaryCodec.encodeMeta(buildJsonObject {
                                    put("method", method)
                                    put("fast", true)
                                }),
                                frame.payload
                            )
                        )
                    }
                }
                BinaryFrameType.STREAM_DATA -> {
                    receivedLabels.add("STREAM_DATA")
                    firstChunkArrived.countDown()
                    inboundBuffers[frame.streamId]?.write(frame.payload)
                    Thread.sleep(2)
                }
                BinaryFrameType.STREAM_END -> {
                    receivedLabels.add("STREAM_END")
                    val buf = inboundBuffers.remove(frame.streamId)
                    val method = inboundMethods.remove(frame.streamId)
                    if (buf != null && method != null) {
                        val allBytes = buf.toByteArray()
                        writeFrame(
                            BinaryFrame(
                                BinaryFrameType.RESP,
                                frame.streamId,
                                PluginBinaryCodec.encodeMeta(buildJsonObject {
                                    put("method", method)
                                    put("size", allBytes.size)
                                    put("checksum", allBytes.fold(0) { acc, b -> acc * 31 + b.toInt() })
                                })
                            )
                        )
                    }
                }
                else -> Unit
            }
        }

        server.use {
            val channel = createChannel(sockPath, token)
            channel.connect()
            try {
                // 4.5 MiB 超过 4 MiB 的测试负载（72 个 64 KiB 分片）
                val largeSize = 4 * 1024 * 1024 + 512 * 1024
                val largePayload = ByteArray(largeSize) { ((it * 13 + 7) and 0xFF).toByte() }
                val expectedChecksum = largePayload.fold(0) { acc, b -> acc * 31 + b.toInt() }

                val largeDeferred = async(Dispatchers.IO) {
                    channel.request(
                        method = "upload.large",
                        metaParams = buildJsonObject { put("name", "blob.bin") },
                        payload = largePayload,
                        timeoutMs = 15_000L
                    )
                }

                // 等到服务端已经开始接收大流分片后，再并发发出 5 个控制请求
                assertTrue(firstChunkArrived.await(3, TimeUnit.SECONDS))
                val controlResults = (1..5).map { idx ->
                    async(Dispatchers.IO) {
                        channel.request(
                            method = "control.ping",
                            metaParams = buildJsonObject { put("seq", idx) },
                            payload = "ping-$idx".toByteArray(),
                            timeoutMs = 5_000L
                        )
                    }
                }.awaitAll()

                controlResults.forEachIndexed { idx, res ->
                    assertEquals("control.ping", res.meta["method"]?.jsonPrimitive?.content)
                    assertEquals("ping-${idx + 1}", res.payload.toString(Charsets.UTF_8))
                }

                val largeResult = largeDeferred.await()
                assertEquals("upload.large", largeResult.meta["method"]?.jsonPrimitive?.content)
                assertEquals(largeSize, largeResult.meta["size"]?.jsonPrimitive?.int)
                assertEquals(expectedChecksum, largeResult.meta["checksum"]?.jsonPrimitive?.int)

                // 确认发生了分片且控制请求穿插在 STREAM_DATA 分片之间写入（未等大流全部写完）
                assertTrue(receivedLabels.count { it == "STREAM_DATA" } >= 72)
                val firstControlIdx = receivedLabels.indexOf("REQ:control.ping")
                val lastDataIdx = receivedLabels.lastIndexOf("STREAM_DATA")
                assertTrue(
                    "预期控制帧穿插在分片之间写出: firstControlIdx=$firstControlIdx, lastDataIdx=$lastDataIdx",
                    firstControlIdx in 1 until lastDataIdx
                )
            } finally {
                channel.close()
            }
        }
    }

    @Test
    fun openStream_emitsChunksPropagatesErrorAndSendsCancelOnEarlyUnsubscribe() = runBlocking {
        val sockPath = Files.createTempDirectory("ipc-stream-").resolve("test.sock").toFile()
        val token = "11112222333344445555666677778888"
        val cancelLatch = CountDownLatch(1)
        val cancelledStreamId = CompletableDeferred<UInt>()

        val server = UnixTestServer(sockPath) { frame, writeFrame ->
            when (frame.type) {
                BinaryFrameType.PING -> writeFrame(BinaryFrame(BinaryFrameType.PONG, frame.streamId))
                BinaryFrameType.REQ -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    val method = meta["method"]!!.jsonPrimitive.content
                    when (method) {
                        "stream.ok" -> {
                            repeat(4) { i ->
                                writeFrame(
                                    BinaryFrame(
                                        BinaryFrameType.STREAM_DATA,
                                        frame.streamId,
                                        PluginBinaryCodec.encodeMeta(buildJsonObject { put("idx", i) }),
                                        "chunk-$i".toByteArray()
                                    )
                                )
                            }
                            writeFrame(BinaryFrame(BinaryFrameType.STREAM_END, frame.streamId))
                        }
                        "stream.err" -> {
                            writeFrame(
                                BinaryFrame(
                                    BinaryFrameType.STREAM_DATA,
                                    frame.streamId,
                                    payload = "part-0".toByteArray()
                                )
                            )
                            writeFrame(
                                BinaryFrame(
                                    BinaryFrameType.ERR,
                                    frame.streamId,
                                    PluginBinaryCodec.encodeMeta(buildJsonObject {
                                        put("code", "STREAM_BOOM")
                                        put("message", "broken midway")
                                    })
                                )
                            )
                        }
                        "stream.infinite" -> {
                            // 持续推流直到收到取消通知
                            var i = 0
                            while (cancelLatch.count > 0 && i < 50) {
                                writeFrame(
                                    BinaryFrame(
                                        BinaryFrameType.STREAM_DATA,
                                        frame.streamId,
                                        payload = "tick-$i".toByteArray()
                                    )
                                )
                                i++
                                Thread.sleep(10)
                            }
                        }
                    }
                }
                BinaryFrameType.STREAM_END -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    if (meta["cancelled"]?.jsonPrimitive?.booleanOrNull == true) {
                        cancelledStreamId.complete(frame.streamId)
                        cancelLatch.countDown()
                    }
                }
                else -> Unit
            }
        }

        server.use {
            val channel = createChannel(sockPath, token)
            channel.connect()
            try {
                // 1. 正常流完成
                val chunks = channel.openStream("stream.ok").toList()
                assertEquals(4, chunks.size)
                assertEquals("chunk-0", chunks[0].payload.toString(Charsets.UTF_8))
                assertEquals(3, chunks[3].meta["idx"]?.jsonPrimitive?.int)

                // 2. 流式错误传播
                val errEx = runCatching {
                    channel.openStream("stream.err").toList()
                }.exceptionOrNull() as? PluginProcessException
                assertEquals("STREAM_BOOM", errEx?.code)

                // 3. 协程提前取消（take(2)）向对端发送 STREAM_END(cancelled=true)
                val partial = channel.openStream("stream.infinite").take(2).toList()
                assertEquals(2, partial.size)
                assertTrue("预期对端收到 STREAM_END 取消帧", cancelLatch.await(3, TimeUnit.SECONDS))
                assertTrue(withTimeout(1000) { cancelledStreamId.await() } > 0u)
            } finally {
                channel.close()
            }
        }
    }

    @Test
    fun bidirectional_inboundLargeResponseChunkingErrorAndEvent() = runBlocking {
        val sockPath = Files.createTempDirectory("ipc-bidir-").resolve("test.sock").toFile()
        val token = "9999aaaabbbbccccddddeeeeffff0000"
        val receivedEvents = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val largeReply = ByteArray(4 * 1024 * 1024 + 123_456) { ((it * 7) and 0xFF).toByte() }
        val clientReassembled = CompletableDeferred<ByteArray>()
        val clientErrorCode = CompletableDeferred<String>()
        val eventLatch = CountDownLatch(1)

        val clientOutLock = Any()
        val serverWriteRef = AtomicReference<((BinaryFrame) -> Unit)?>(null)
        val respBuffers = ConcurrentHashMap<UInt, ByteArrayOutputStream>()

        val server = UnixTestServer(sockPath) { frame, writeFrame ->
            serverWriteRef.set { f -> synchronized(clientOutLock) { writeFrame(f) } }
            when (frame.type) {
                BinaryFrameType.PING -> {
                    writeFrame(BinaryFrame(BinaryFrameType.PONG, frame.streamId, PluginBinaryCodec.encodeMeta(buildJsonObject {
                        put("ok", true)
                        put("window", PluginBinaryCodec.STREAM_WINDOW_BYTES)
                    })))
                }
                BinaryFrameType.RESP -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    val streamed = meta["streamed"]?.jsonPrimitive?.booleanOrNull == true
                    if (streamed) {
                        respBuffers[frame.streamId] = ByteArrayOutputStream().apply {
                            if (frame.payload.isNotEmpty()) write(frame.payload)
                        }
                    } else {
                        clientReassembled.complete(frame.payload)
                    }
                }
                BinaryFrameType.STREAM_DATA -> {
                    respBuffers[frame.streamId]?.write(frame.payload)
                    writeFrame(BinaryFrame(
                        BinaryFrameType.WINDOW_UPDATE,
                        frame.streamId,
                        PluginBinaryCodec.encodeMeta(buildJsonObject {
                            put("bytes", frame.payload.size)
                        })
                    ))
                }
                BinaryFrameType.STREAM_END -> {
                    val buf = respBuffers.remove(frame.streamId)
                    if (buf != null) {
                        clientReassembled.complete(buf.toByteArray())
                    }
                }
                BinaryFrameType.ERR -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    clientErrorCode.complete(meta["code"]!!.jsonPrimitive.content)
                }
                else -> Unit
            }
        }

        server.use {
            val channel = PluginBinaryChannel(
                pluginId = "test-plugin",
                connector = unixConnector(sockPath),
                ipcToken = token,
                onRequest = { req ->
                    when (req.method) {
                        "host.bigRead" -> BinaryResult(
                            meta = buildJsonObject { put("ok", true) },
                            payload = largeReply
                        )
                        "host.fail" -> throw PluginProcessException("HOST_DENIED", "not allowed")
                        else -> BinaryResult()
                    }
                },
                onEvent = { method, meta, _ ->
                    receivedEvents.add(method to meta)
                    eventLatch.countDown()
                }
            )
            channel.connect()
            try {
                val sendFromPeer = serverWriteRef.get() ?: error("serverWrite not initialized")

                // 插件发送 EVENT
                sendFromPeer(
                    BinaryFrame(
                        BinaryFrameType.EVENT,
                        0u,
                        PluginBinaryCodec.encodeMeta(buildJsonObject {
                            put("method", "plugin.event")
                            put("args", buildJsonObject { put("topic", "ready") })
                        })
                    )
                )
                assertTrue(eventLatch.await(3, TimeUnit.SECONDS))
                assertEquals("plugin.event", receivedEvents[0].first)

                // 插件发送 REQ（大响应 > 4 MiB 分片返回）
                sendFromPeer(
                    BinaryFrame(
                        BinaryFrameType.REQ,
                        0x80000001u,
                        PluginBinaryCodec.encodeMeta(buildJsonObject { put("method", "host.bigRead") })
                    )
                )
                val reassembled = withTimeout(10_000) { clientReassembled.await() }
                assertArrayEquals(largeReply, reassembled)

                // 插件发送 REQ 触发宿主错误
                sendFromPeer(
                    BinaryFrame(
                        BinaryFrameType.REQ,
                        0x80000003u,
                        PluginBinaryCodec.encodeMeta(buildJsonObject { put("method", "host.fail") })
                    )
                )
                assertEquals("HOST_DENIED", withTimeout(3_000) { clientErrorCode.await() })
            } finally {
                channel.close()
            }
        }
    }

    @Test
    fun processTransport_upgradesOnIpcReadyFallsBackBeforeAndDeletesSockOnClose() = runBlocking {
        val tempDir = Files.createTempDirectory("ipc-transport-").toFile()
        val sockFile = File(tempDir, "a1b2c3d4.sock")
        val token = "00112233445566778899aabbccddeeff"

        val fakeProcess = FakeStdioProcess()
        val engine = mockk<LinuxContainerEngine>()
        every {
            engine.startStdioProcess(any(), any(), any(), any(), any(), any())
        } returns fakeProcess

        val binaryMethodsSeen = CopyOnWriteArrayList<String>()
        val server = UnixTestServer(sockFile) { frame, writeFrame ->
            when (frame.type) {
                BinaryFrameType.PING -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    assertEquals(token, meta["token"]?.jsonPrimitive?.content)
                    writeFrame(BinaryFrame(BinaryFrameType.PONG, frame.streamId))
                }
                BinaryFrameType.REQ -> {
                    val meta = PluginBinaryCodec.decodeMeta(frame.meta)
                    val method = meta["method"]!!.jsonPrimitive.content
                    binaryMethodsSeen.add(method)
                    when (method) {
                        "tool.invoke" -> writeFrame(
                            BinaryFrame(
                                BinaryFrameType.RESP,
                                frame.streamId,
                                PluginBinaryCodec.encodeMeta(buildJsonObject {
                                    put("result", buildJsonObject { put("via", "binary") })
                                })
                            )
                        )
                        "blob.echo" -> writeFrame(
                            BinaryFrame(
                                BinaryFrameType.RESP,
                                frame.streamId,
                                PluginBinaryCodec.encodeMeta(buildJsonObject { put("ok", true) }),
                                frame.payload
                            )
                        )
                        "stream.nums" -> {
                            repeat(2) { i ->
                                writeFrame(
                                    BinaryFrame(
                                        BinaryFrameType.STREAM_DATA,
                                        frame.streamId,
                                        payload = "n$i".toByteArray()
                                    )
                                )
                            }
                            writeFrame(BinaryFrame(BinaryFrameType.STREAM_END, frame.streamId))
                        }
                    }
                }
                else -> Unit
            }
        }

        val transport = PluginProcessTransport(
            pluginId = "test-plugin",
            engine = engine,
            profile = ContainerProfile.BUILTIN_ALPINE,
            workingDir = "/root/.aicode/plugins/test",
            program = "node",
            programArgs = listOf("index.js"),
            extraEnv = emptyMap(),
            onRequest = { _, _ -> JsonPrimitive("ok") },
            onNotification = { _, _ -> },
            onExit = {},
            ipcSocketFile = sockFile,
            ipcToken = token,
            binaryConnectorFactory = { file -> unixConnector(file) }
        )

        server.use {
            // 1. 建连前常规 request 走 NDJSON
            val ndjsonJob = async(Dispatchers.IO) {
                transport.request("tool.invoke", buildJsonObject { put("handler", "h1") }, timeoutMs = 5_000L)
            }
            val stdinReqLine = withTimeout(3_000) { fakeProcess.readStdinLine() }
            assertTrue(stdinReqLine.contains("\"method\":\"tool.invoke\""))
            fakeProcess.emitStdoutLine("""{"jsonrpc":"2.0","id":1,"result":{"via":"ndjson"}}""")
            val ndjsonRes = ndjsonJob.await() as JsonObject
            assertEquals("ndjson", ndjsonRes["via"]?.jsonPrimitive?.content)
            assertFalse(transport.isBinaryConnected)

            // 2. 插件发送 ipc.ready 通知，宿主完成握手升级
            fakeProcess.emitStdoutLine("""{"jsonrpc":"2.0","method":"ipc.ready","params":{}}""")
            val deadline = System.currentTimeMillis() + 3_000L
            while (!transport.isBinaryConnected && System.currentTimeMillis() < deadline) {
                delay(10)
            }
            assertTrue("预期 transport 升级为二进制连接", transport.isBinaryConnected)

            // 3. 升级后常规 request 优先走二进制通道
            val binRes = transport.request(
                "tool.invoke",
                buildJsonObject { put("handler", "h2") },
                timeoutMs = 5_000L
            ) as JsonObject
            assertEquals("binary", binRes["via"]?.jsonPrimitive?.content)
            assertTrue(binaryMethodsSeen.contains("tool.invoke"))

            // 4. 暴露的 requestBinary 和 openBinaryStream 可正常使用
            val rawOut = transport.requestBinary(
                method = "blob.echo",
                payload = byteArrayOf(1, 2, 3, 4),
                timeoutMs = 5_000L
            )
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), rawOut.payload)

            val streamed = transport.openBinaryStream("stream.nums").toList()
            assertEquals(listOf("n0", "n1"), streamed.map { it.payload.toString(Charsets.UTF_8) })

            // 5. close() 关闭二进制通道并删除临时 .sock 文件
            assertTrue(sockFile.exists())
            transport.close()
            assertFalse(transport.isBinaryConnected)
            assertFalse(sockFile.exists())
        }
    }

    @Test
    fun processTransport_keepsNdjsonWhenBinaryHandshakeFails() = runBlocking {
        val tempDir = Files.createTempDirectory("ipc-fallback-").toFile()
        val sockFile = File(tempDir, "fail.sock")
        val fakeProcess = FakeStdioProcess()
        val engine = mockk<LinuxContainerEngine>()
        every {
            engine.startStdioProcess(any(), any(), any(), any(), any(), any())
        } returns fakeProcess

        val transport = PluginProcessTransport(
            pluginId = "test-plugin",
            engine = engine,
            profile = ContainerProfile.BUILTIN_ALPINE,
            workingDir = "/root/.aicode/plugins/test",
            program = "node",
            programArgs = listOf("index.js"),
            extraEnv = emptyMap(),
            onRequest = { _, _ -> JsonPrimitive("ok") },
            onNotification = { _, _ -> },
            onExit = {},
            ipcSocketFile = sockFile,
            ipcToken = "00112233445566778899aabbccddeeff",
            binaryConnectorFactory = {
                BinaryConnector { throw java.io.IOException("connection refused") }
            }
        )

        try {
            val initJob = async(Dispatchers.IO) {
                transport.request("tool.invoke", buildJsonObject { put("handler", "h0") }, timeoutMs = 5_000L)
            }
            withTimeout(3_000) { fakeProcess.readStdinLine() }
            fakeProcess.emitStdoutLine("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""")
            initJob.await()

            // ipc.ready 后连接失败，不应崩溃也不应关闭进程，继续保持 NDJSON
            fakeProcess.emitStdoutLine("""{"jsonrpc":"2.0","method":"ipc.ready","params":{}}""")
            delay(100)
            assertFalse(transport.isBinaryConnected)

            val nextJob = async(Dispatchers.IO) {
                transport.request("tool.invoke", buildJsonObject { put("handler", "h1") }, timeoutMs = 5_000L)
            }
            val line2 = withTimeout(3_000) { fakeProcess.readStdinLine() }
            assertTrue(line2.contains("\"id\":2"))
            fakeProcess.emitStdoutLine("""{"jsonrpc":"2.0","id":2,"result":{"via":"ndjson-fallback"}}""")
            val res2 = nextJob.await() as JsonObject
            assertEquals("ndjson-fallback", res2["via"]?.jsonPrimitive?.content)
        } finally {
            transport.close()
        }
    }

    @Test
    fun runtimeManager_createIpcConfig_generatesShortSockPathAnd32HexToken() {
        val shortRoot = Files.createTempDirectory("ai-").toFile()
        val installerShort = mockk<ContainerInstaller>()
        every { installerShort.aicodeDir } returns shortRoot
        every { installerShort.containerPathFor(any()) } answers {
            val f = firstArg<File>()
            "/root/.aicode/ipc/${f.name}"
        }
        val managerShort = createRuntimeManager(installerShort)
        val cfg = managerShort.createIpcConfig("demo")
        assertTrue(cfg != null)
        assertTrue(cfg!!.hostSockFile.parentFile == File(shortRoot, "ipc"))
        assertTrue(cfg.hostSockFile.parentFile?.exists() == true)
        assertTrue(cfg.hostSockFile.name.endsWith(".sock"))
        assertTrue(cfg.hostSockFile.absolutePath.toByteArray(Charsets.UTF_8).size <= 100)
        assertEquals("/root/.aicode/ipc/${cfg.hostSockFile.name}", cfg.containerSockPath)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(cfg.token))

        // 路径超长 (>100 字节) 时自动禁用二进制 IPC
        val longDir = File(shortRoot, "x".repeat(90)).apply { mkdirs() }
        val installerLong = mockk<ContainerInstaller>()
        every { installerLong.aicodeDir } returns longDir
        val managerLong = createRuntimeManager(installerLong)
        assertEquals(null, managerLong.createIpcConfig("demo"))
    }

    @Test
    fun runtimeDataDirectory_isLocalAndDoesNotCallTheActiveBackend() = runBlocking {
        val root = Files.createTempDirectory("plugin-data-").toFile()
        val installer = mockk<ContainerInstaller>()
        every { installer.aicodeDir } returns root
        every { installer.containerPathFor(any()) } answers {
            "/root/.aicode/" + firstArg<File>().relativeTo(root).invariantSeparatorsPath
        }
        val manager = createRuntimeManager(installer)

        assertEquals("/root/.aicode/plugins/data/demo.cloud", manager.dataDirectory("demo.cloud"))
        assertTrue(File(root, "plugins/data/demo.cloud").isDirectory)
        assertEquals("/root/.aicode/plugins/data/demo.cloud", manager.dataDirectory("demo.cloud"))
    }

    private fun createRuntimeManager(installer: ContainerInstaller): PluginRuntimeManager =
        PluginRuntimeManager(
            pluginManager = mockk(relaxed = true),
            installer = installer,
            engine = mockk(relaxed = true),
            containerSettingsRepository = mockk(relaxed = true),
            homeResolver = mockk(relaxed = true),
            workspaceRepository = mockk(relaxed = true),
            executionModeHolder = mockk(relaxed = true),
            eventBus = mockk(relaxed = true),
            workflowEventBus = mockk(relaxed = true),
            overlayManager = mockk(relaxed = true),
            hostApiDispatcher = mockk(relaxed = true)
        )

    private fun createChannel(sockPath: File, token: String): PluginBinaryChannel =
        PluginBinaryChannel(
            pluginId = "test-plugin",
            connector = unixConnector(sockPath),
            ipcToken = token,
            onRequest = { BinaryResult() },
            onEvent = { _, _, _ -> }
        )

    private fun unixConnector(sockPath: File): BinaryConnector = BinaryConnector {
        val ch = openUnixSocketChannel()
        ch.configureBlocking(true)
        ch.connect(unixSocketAddress(sockPath.toPath()))
        SocketChannelConnection(ch)
    }

    private companion object {
        private val unixProtocolFamily: ProtocolFamily by lazy {
            @Suppress("UNCHECKED_CAST")
            val enumClass = Class.forName("java.net.StandardProtocolFamily") as Class<out Enum<*>>
            java.lang.Enum.valueOf(enumClass, "UNIX") as ProtocolFamily
        }

        private val unixAddressOfMethod by lazy {
            Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", Path::class.java)
        }

        private val serverOpenWithFamilyMethod by lazy {
            ServerSocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
        }

        private val clientOpenWithFamilyMethod by lazy {
            SocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
        }

        fun unixSocketAddress(path: Path): SocketAddress =
            unixAddressOfMethod.invoke(null, path) as SocketAddress

        fun openUnixServerSocketChannel(): ServerSocketChannel =
            serverOpenWithFamilyMethod.invoke(null, unixProtocolFamily) as ServerSocketChannel

        fun openUnixSocketChannel(): SocketChannel =
            clientOpenWithFamilyMethod.invoke(null, unixProtocolFamily) as SocketChannel
    }

    private class SocketChannelConnection(
        private val channel: SocketChannel
    ) : BinarySocketConnection {
        override val inputStream: InputStream = object : InputStream() {
            override fun read(): Int {
                val single = ByteArray(1)
                val n = read(single, 0, 1)
                return if (n == -1) -1 else single[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val buf = ByteBuffer.wrap(b, off, len)
                return channel.read(buf)
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                val buf = ByteBuffer.wrap(b, off, len)
                while (buf.hasRemaining()) {
                    channel.write(buf)
                }
            }
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    private class UnixTestServer(
        private val sockFile: File,
        private val onFrame: (frame: BinaryFrame, writeFrame: (BinaryFrame) -> Unit) -> Unit
    ) : AutoCloseable {
        private val serverChannel: ServerSocketChannel
        private val acceptThread: Thread
        @Volatile private var stopped = false
        private val clientSockets = CopyOnWriteArrayList<SocketChannel>()

        init {
            sockFile.parentFile?.mkdirs()
            sockFile.delete()
            serverChannel = openUnixServerSocketChannel()
            serverChannel.bind(unixSocketAddress(sockFile.toPath()))
            acceptThread = Thread {
                try {
                    while (!stopped) {
                        val client = serverChannel.accept()
                        clientSockets.add(client)
                        Thread {
                            serveClient(client)
                        }.apply { isDaemon = true }.start()
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun serveClient(client: SocketChannel) {
            val conn = SocketChannelConnection(client)
            val writeLock = Any()
            val writer: (BinaryFrame) -> Unit = { frame ->
                synchronized(writeLock) {
                    PluginBinaryCodec.writeFrame(conn.outputStream, frame)
                }
            }
            try {
                while (!stopped) {
                    val frame = PluginBinaryCodec.readFrame(conn.inputStream) ?: break
                    onFrame(frame, writer)
                }
            } catch (_: Exception) {
            } finally {
                conn.close()
            }
        }

        override fun close() {
            stopped = true
            runCatching { serverChannel.close() }
            clientSockets.forEach { runCatching { it.close() } }
            runCatching { sockFile.delete() }
        }
    }

    private class FakeStdioProcess : Process() {
        private val stdinOut = PipedOutputStream()
        private val stdinIn = PipedInputStream(stdinOut, 64 * 1024)
        private val stdoutOut = PipedOutputStream()
        private val stdoutIn = PipedInputStream(stdoutOut, 64 * 1024)
        private val stderrIn = ByteArrayInputStream(ByteArray(0))
        private val stdinReader = stdinIn.bufferedReader()
        @Volatile private var destroyed = false

        override fun getOutputStream(): OutputStream = stdinOut
        override fun getInputStream(): InputStream = stdoutIn
        override fun getErrorStream(): InputStream = stderrIn

        fun readStdinLine(): String = stdinReader.readLine() ?: ""

        fun emitStdoutLine(line: String) {
            stdoutOut.write((line + "\n").toByteArray(Charsets.UTF_8))
            stdoutOut.flush()
        }

        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            runCatching { stdinOut.close() }
            runCatching { stdoutOut.close() }
        }
    }
}
