package com.aicode.feature.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 终端输出按任意字节块到达，这里只测不依赖 Android 与终端实现的增量解码。 */
class PluginTerminalOutputTest {

    @Test
    fun chineseCharacterSplitAcrossChunks_isRejoined() {
        val text = "done: 中文 ok"
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 在「中」的三个字节中间切开：第一块以不完整的序列结尾
        val split = bytes.indexOfFirst { it == 0xE4.toByte() } + 2
        val decoder = Utf8StreamDecoder()

        val first = decoder.decode(bytes.copyOfRange(0, split))
        val second = decoder.decode(bytes.copyOfRange(split, bytes.size))

        assertEquals("done: ", first)
        assertEquals("中文 ok", second)
        assertEquals(text, first + second + decoder.flush())
    }

    @Test
    fun fourByteCharacterSplitAcrossChunks_isRejoined() {
        val bytes = "a😀b".toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()

        val out = decoder.decode(bytes.copyOfRange(0, 2)) +
            decoder.decode(bytes.copyOfRange(2, bytes.size)) +
            decoder.flush()

        assertEquals("a😀b", out)
    }

    @Test
    fun incompleteTrailingSequence_flushesAsReplacement() {
        val decoder = Utf8StreamDecoder()
        val bytes = "ok".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE4.toByte(), 0xB8.toByte())

        assertEquals("ok", decoder.decode(bytes))
        assertEquals("\uFFFD", decoder.flush())
        assertEquals("", decoder.flush())
    }
}
