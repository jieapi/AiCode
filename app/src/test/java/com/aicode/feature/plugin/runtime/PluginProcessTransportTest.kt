package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.runtime.PluginProcessTransport.BoundedLineReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginProcessTransportTest {

    @Test
    fun readLine_readsNormalCrLfEmptyAndTrailingEofLines() {
        val input = ByteArrayInputStream("first\r\n\nsecond\r\r\nlast".toByteArray(Charsets.UTF_8))
        val reader = BoundedLineReader(input, maxBytes = 64)

        assertEquals(BoundedLineReader.Line.Text("first"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text(""), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("second"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("last"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Eof, reader.readLine())
    }

    @Test
    fun readLine_handlesExactLimitAndMultiChunkUtf8Lines() {
        val exact = "a".repeat(10_000)
        val utf8 = "你好世界-".repeat(2_000)
        val payload = "$exact\n$utf8\ntail\n".toByteArray(Charsets.UTF_8)
        val trickled = TrickleInputStream(payload, maxRead = 7)
        val reader = BoundedLineReader(trickled, maxBytes = maxOf(exact.length, utf8.toByteArray(Charsets.UTF_8).size))

        assertEquals(BoundedLineReader.Line.Text(exact), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text(utf8), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("tail"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Eof, reader.readLine())
    }

    @Test
    fun readLine_discardsOverlongLinesAcrossChunksAndRecoversNextLine() {
        val overlong1 = "x".repeat(20_000)
        val overlong2 = "y".repeat(256)
        val payload = "$overlong1\nok1\n$overlong2".toByteArray(Charsets.UTF_8)
        val reader = BoundedLineReader(ByteArrayInputStream(payload), maxBytes = 128)

        assertEquals(BoundedLineReader.Line.Overlong(20_000L), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("ok1"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Overlong(256L), reader.readLine())
        assertEquals(BoundedLineReader.Line.Eof, reader.readLine())
    }

    @Test
    fun readLine_discardsLineExceedingLimitWithinSingleChunk() {
        val payload = "12345\n6789\nok\n".toByteArray(Charsets.UTF_8)
        val reader = BoundedLineReader(ByteArrayInputStream(payload), maxBytes = 4)

        assertEquals(BoundedLineReader.Line.Overlong(5L), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("6789"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Text("ok"), reader.readLine())
        assertEquals(BoundedLineReader.Line.Eof, reader.readLine())
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
