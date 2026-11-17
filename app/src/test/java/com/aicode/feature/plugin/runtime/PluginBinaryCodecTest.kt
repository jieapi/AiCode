package com.aicode.feature.plugin.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBinaryCodecTest {

    @Test
    fun writeAndReadFrame_roundTripsAllFrameTypesAndHeaderLayout() {
        val out = ByteArrayOutputStream()
        val frames = BinaryFrameType.entries.mapIndexed { idx, type ->
            val streamId = if (idx == 0) 0x8000_0001u else (idx + 1).toUInt()
            val meta = PluginBinaryCodec.encodeMeta(buildJsonObject {
                put("type", type.name)
                put("seq", idx)
            })
            val payload = ByteArray(idx * 17 + 5) { ((it + idx) and 0xFF).toByte() }
            BinaryFrame(type = type, streamId = streamId, meta = meta, payload = payload)
        }

        frames.forEach { PluginBinaryCodec.writeFrame(out, it) }

        val raw = out.toByteArray()
        // 验证第一帧 16 字节大端帧头格式
        val firstBuf = ByteBuffer.wrap(raw, 0, PluginBinaryCodec.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        assertEquals(PluginBinaryCodec.MAGIC, firstBuf.short)
        assertEquals(PluginBinaryCodec.VERSION, firstBuf.get())
        assertEquals(BinaryFrameType.REQ.code, firstBuf.get())
        assertEquals(0x8000_0001u, firstBuf.int.toUInt())
        assertEquals(frames[0].meta.size.toUInt(), firstBuf.int.toUInt())
        assertEquals(frames[0].payload.size.toUInt(), firstBuf.int.toUInt())

        val input = TrickleInputStream(raw, maxRead = 3)
        frames.forEach { expected ->
            val decoded = PluginBinaryCodec.readFrame(input)
            assertEquals(expected, decoded)
        }
        assertNull(PluginBinaryCodec.readFrame(input))
    }

    @Test
    fun readFrame_returnsNullOnCleanEof() {
        assertNull(PluginBinaryCodec.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = EOFException::class)
    fun readFrame_throwsOnTruncatedHeader() {
        val partialHeader = byteArrayOf(0x41, 0x43, 0x01, 0x01, 0x00)
        PluginBinaryCodec.readFrame(ByteArrayInputStream(partialHeader))
    }

    @Test(expected = EOFException::class)
    fun readFrame_throwsOnTruncatedBody() {
        val out = ByteArrayOutputStream()
        val frame = BinaryFrame(
            type = BinaryFrameType.STREAM_DATA,
            streamId = 7u,
            meta = "{}".toByteArray(),
            payload = ByteArray(128) { 0x2A }
        )
        PluginBinaryCodec.writeFrame(out, frame)
        val truncated = out.toByteArray().copyOf(out.size() - 10)
        PluginBinaryCodec.readFrame(ByteArrayInputStream(truncated))
    }

    @Test(expected = IOException::class)
    fun readFrame_rejectsInvalidMagic() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x1234.toShort())
            .put(PluginBinaryCodec.VERSION)
            .put(BinaryFrameType.PING.code)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        PluginBinaryCodec.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = IOException::class)
    fun readFrame_rejectsUnsupportedVersion() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(PluginBinaryCodec.MAGIC)
            .put(2)
            .put(BinaryFrameType.PING.code)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        PluginBinaryCodec.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = IOException::class)
    fun readFrame_rejectsUnknownFrameType() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(PluginBinaryCodec.MAGIC)
            .put(PluginBinaryCodec.VERSION)
            .put(99)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        PluginBinaryCodec.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = IOException::class)
    fun readFrame_rejectsMetaExceedingLimitWithoutAllocating() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(PluginBinaryCodec.MAGIC)
            .put(PluginBinaryCodec.VERSION)
            .put(BinaryFrameType.REQ.code)
            .putInt(1)
            .putInt(PluginBinaryCodec.MAX_META_BYTES + 1)
            .putInt(0)
            .array()
        PluginBinaryCodec.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = IOException::class)
    fun readFrame_rejectsUnsignedLargePayloadLength() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putShort(PluginBinaryCodec.MAGIC)
            .put(PluginBinaryCodec.VERSION)
            .put(BinaryFrameType.STREAM_DATA.code)
            .putInt(1)
            .putInt(0)
            .putInt(0x8000_0001.toInt())
            .array()
        PluginBinaryCodec.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = IOException::class)
    fun writeFrame_rejectsOversizedPayload() {
        val frame = BinaryFrame(
            type = BinaryFrameType.STREAM_DATA,
            streamId = 1u,
            payload = ByteArray(PluginBinaryCodec.MAX_PAYLOAD_BYTES + 1)
        )
        PluginBinaryCodec.writeFrame(ByteArrayOutputStream(), frame)
    }

    @Test
    fun writeAndReadFrame_handlesLargeUnCoalescedFrame() {
        val payload = ByteArray(128 * 1024) { (it * 31).toByte() }
        val meta = PluginBinaryCodec.encodeMeta(buildJsonObject { put("large", true) })
        val frame = BinaryFrame(BinaryFrameType.RESP, 42u, meta, payload)

        val out = ByteArrayOutputStream()
        PluginBinaryCodec.writeFrame(out, frame)
        val readBack = PluginBinaryCodec.readFrame(ByteArrayInputStream(out.toByteArray()))

        assertEquals(BinaryFrameType.RESP, readBack?.type)
        assertEquals(42u, readBack?.streamId)
        assertArrayEquals(meta, readBack?.meta)
        assertArrayEquals(payload, readBack?.payload)
    }

    @Test
    fun decodeMeta_parsesJsonObjectAndRejectsNonObject() {
        assertTrue(PluginBinaryCodec.decodeMeta(ByteArray(0)).isEmpty())
        val parsed = PluginBinaryCodec.decodeMeta("""{"ok":true,"msg":"hi"}""".toByteArray())
        assertEquals(JsonPrimitive("hi"), parsed["msg"])

        val nonObjResult = runCatching { PluginBinaryCodec.decodeMeta("[1,2,3]".toByteArray()) }
        assertTrue(nonObjResult.exceptionOrNull() is IOException)
    }

    private class TrickleInputStream(
        private val bytes: ByteArray,
        private val maxRead: Int
    ) : InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val count = minOf(len, maxRead, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, count)
            pos += count
            return count
        }
    }
}
