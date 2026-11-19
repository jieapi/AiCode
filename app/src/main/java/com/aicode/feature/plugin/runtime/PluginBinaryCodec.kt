package com.aicode.feature.plugin.runtime

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 二进制多路复用帧类型。 */
enum class BinaryFrameType(val code: Byte) {
    REQ(1),
    RESP(2),
    ERR(3),
    STREAM_DATA(4),
    STREAM_END(5),
    EVENT(6),
    PING(7),
    PONG(8),
    WINDOW_UPDATE(9);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Byte): BinaryFrameType =
            byCode[code] ?: throw IOException("不支持的二进制帧类型: $code")
    }
}

/** 单帧二进制协议数据单元。 */
data class BinaryFrame(
    val type: BinaryFrameType,
    val streamId: UInt,
    val meta: ByteArray = EMPTY_BYTES,
    val payload: ByteArray = EMPTY_BYTES
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryFrame) return false
        return type == other.type &&
            streamId == other.streamId &&
            meta.contentEquals(other.meta) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId.hashCode()
        result = 31 * result + meta.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        val EMPTY_BYTES = ByteArray(0)
    }
}

/**
 * 宿主与插件进程间 Unix Domain Socket 二进制帧编解码器。
 *
 * 16 字节大端帧头格式：
 * - magic: Short = 0x4143 ('A','C')
 * - version: Byte = 1
 * - type: Byte (1..9)
 * - streamId: UInt (4B)
 * - metaLen: UInt (4B)
 * - payloadLen: UInt (4B)
 * 后接 `meta`（UTF-8 JSON 对象字节）与 `payload`（原始字节）。
 */
object PluginBinaryCodec {
    const val MAGIC: Short = 0x4143
    const val VERSION: Byte = 1
    const val HEADER_BYTES: Int = 16
    const val MAX_META_BYTES: Int = 1 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
    const val MAX_STREAM_BYTES: Long = 64L * 1024 * 1024
    const val DEFAULT_CHUNK_BYTES: Int = 64 * 1024
    const val STREAM_WINDOW_BYTES: Int = 256 * 1024
    const val MIN_STREAM_FRAME_COST: Int = 4 * 1024

    fun streamFrameCost(metaBytes: Int, payloadBytes: Int): Int =
        maxOf(MIN_STREAM_FRAME_COST, HEADER_BYTES + metaBytes + payloadBytes)

    private const val SMALL_FRAME_COALESCE_BYTES = 4 * 1024
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * 从 [input] 读取完整的一帧。流在帧边界正常结束时返回 `null`；
     * 半截 EOF、非法 magic、不支持的 version/type 或长度超限均抛出 [IOException]。
     */
    fun readFrame(input: InputStream): BinaryFrame? {
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < HEADER_BYTES) {
            val read = input.read(header, offset, HEADER_BYTES - offset)
            if (read == -1) {
                if (offset == 0) return null
                throw EOFException("读取二进制帧头时流提前结束（已读 $offset/$HEADER_BYTES 字节）")
            }
            if (read == 0) continue
            offset += read
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.short
        if (magic != MAGIC) {
            throw IOException("非法的二进制帧魔数: 0x%04x".format(magic.toInt() and 0xFFFF))
        }
        val version = buf.get()
        if (version != VERSION) {
            throw IOException("不支持的二进制协议版本: $version")
        }
        val type = BinaryFrameType.fromCode(buf.get())
        val streamId = buf.int.toUInt()
        val metaLen = buf.int.toUInt()
        val payloadLen = buf.int.toUInt()

        if (metaLen.toLong() > MAX_META_BYTES) {
            throw IOException("meta 长度超过上限: $metaLen > $MAX_META_BYTES")
        }
        if (payloadLen.toLong() > MAX_PAYLOAD_BYTES) {
            throw IOException("payload 长度超过上限: $payloadLen > $MAX_PAYLOAD_BYTES")
        }

        val meta = if (metaLen == 0u) {
            BinaryFrame.EMPTY_BYTES
        } else {
            ByteArray(metaLen.toInt()).also { readFully(input, it) }
        }
        val payload = if (payloadLen == 0u) {
            BinaryFrame.EMPTY_BYTES
        } else {
            ByteArray(payloadLen.toInt()).also { readFully(input, it) }
        }
        return BinaryFrame(type = type, streamId = streamId, meta = meta, payload = payload)
    }

    /** 将 [frame] 编码并写入 [output]，写入后刷新输出流。 */
    fun writeFrame(output: OutputStream, frame: BinaryFrame) {
        if (frame.meta.size > MAX_META_BYTES) {
            throw IOException("meta 长度超过上限: ${frame.meta.size} > $MAX_META_BYTES")
        }
        if (frame.payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("payload 长度超过上限: ${frame.payload.size} > $MAX_PAYLOAD_BYTES")
        }

        val bodyBytes = frame.meta.size + frame.payload.size
        if (bodyBytes <= SMALL_FRAME_COALESCE_BYTES) {
            val combined = ByteArray(HEADER_BYTES + bodyBytes)
            val buf = ByteBuffer.wrap(combined).order(ByteOrder.BIG_ENDIAN)
            writeHeader(buf, frame)
            if (frame.meta.isNotEmpty()) {
                System.arraycopy(frame.meta, 0, combined, HEADER_BYTES, frame.meta.size)
            }
            if (frame.payload.isNotEmpty()) {
                System.arraycopy(frame.payload, 0, combined, HEADER_BYTES + frame.meta.size, frame.payload.size)
            }
            output.write(combined)
        } else {
            val header = ByteArray(HEADER_BYTES)
            writeHeader(ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN), frame)
            output.write(header)
            if (frame.meta.isNotEmpty()) output.write(frame.meta)
            if (frame.payload.isNotEmpty()) output.write(frame.payload)
        }
        output.flush()
    }

    fun encodeMeta(meta: JsonObject): ByteArray =
        if (meta.isEmpty()) BinaryFrame.EMPTY_BYTES else meta.toString().toByteArray(Charsets.UTF_8)

    fun decodeMeta(bytes: ByteArray): JsonObject {
        if (bytes.isEmpty()) return JsonObject(emptyMap())
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = try {
            JSON.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IOException("二进制帧 meta 不是合法 JSON: ${e.message}", e)
        }
        return parsed as? JsonObject
            ?: throw IOException("二进制帧 meta 必须是 JSON 对象")
    }

    private fun writeHeader(buf: ByteBuffer, frame: BinaryFrame) {
        buf.putShort(MAGIC)
        buf.put(VERSION)
        buf.put(frame.type.code)
        buf.putInt(frame.streamId.toInt())
        buf.putInt(frame.meta.size)
        buf.putInt(frame.payload.size)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read == -1) {
                throw EOFException("读取二进制帧体时流提前结束（已读 $offset/${target.size} 字节）")
            }
            if (read == 0) continue
            offset += read
        }
    }
}
