package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolOutputFormatterTest {

    private fun format(vararg chunks: String): ToolOutputFormatter {
        val formatter = ToolOutputFormatter()
        chunks.forEach { formatter.feed(it.toByteArray()) }
        formatter.finish()
        return formatter
    }

    @Test
    fun `strips ansi color sequences`() {
        val formatter = format("\u001B[31mred\u001B[0m line\n")
        assertEquals(listOf("red line"), formatter.snapshot())
    }

    @Test
    fun `carriage return replaces current line`() {
        val formatter = format("10%\r50%\r100%\n")
        assertEquals(listOf("100%"), formatter.snapshot())
    }

    @Test
    fun `handles crlf without blank lines`() {
        val formatter = format("a\r\nb\r\n")
        assertEquals(listOf("a", "b"), formatter.snapshot())
    }

    @Test
    fun `backspace removes previous char`() {
        val formatter = format("abc\b\n")
        assertEquals(listOf("ab"), formatter.snapshot())
    }

    @Test
    fun `detects exit marker and excludes it from logs`() {
        val formatter = format("done\n[command exited: 3]\n")
        assertEquals(3, formatter.exitCode)
        assertEquals(listOf("done"), formatter.snapshot())
    }

    @Test
    fun `decodes utf8 split across chunks`() {
        val text = "构建完成\n".toByteArray()
        val formatter = ToolOutputFormatter()
        formatter.feed(text.copyOfRange(0, 1))
        formatter.feed(text.copyOfRange(1, text.size))
        formatter.finish()
        assertEquals(listOf("构建完成"), formatter.snapshot())
    }

    @Test
    fun `drops osc title sequences`() {
        val formatter = format("\u001B]0;title\u0007text\n")
        assertEquals(listOf("text"), formatter.snapshot())
    }

    @Test
    fun `keeps only last max lines`() {
        val formatter = ToolOutputFormatter(maxLines = 3)
        (1..5).forEach { formatter.feed("line$it\n".toByteArray()) }
        formatter.finish()
        assertEquals(listOf("line3", "line4", "line5"), formatter.snapshot())
    }

    @Test
    fun `unfinished line shows in snapshot`() {
        val formatter = ToolOutputFormatter()
        formatter.feed("progress 42%".toByteArray())
        assertEquals(listOf("progress 42%"), formatter.snapshot())
        assertNull(formatter.exitCode)
    }
}
