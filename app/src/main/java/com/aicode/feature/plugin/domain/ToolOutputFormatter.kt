package com.aicode.feature.plugin.domain

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 插件工具运行日志的流式格式化：增量解码 UTF-8、剥离 ANSI 控制序列、处理回车与退格，
 * 只保留最后 [maxLines] 行，并识别宿主在命令结束时打印的退出标记。
 *
 * 终端原始输出含颜色、光标移动与 `\r` 进度条，直接展示会是乱码；这里只做展示所需的最小清洗，
 * 完整终端语义仍由终端标签负责。
 */
class ToolOutputFormatter(private val maxLines: Int = DEFAULT_MAX_LINES) {

    private enum class ParseState { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** 上一块末尾不完整的多字节字符，等下一块补齐后再解码。 */
    private var pending = ByteArray(0)

    private val lines = ArrayDeque<String>()
    private val currentLine = StringBuilder()
    private var carriageReturnPending = false
    private var state = ParseState.TEXT

    /** 已识别到的命令退出码；为 null 表示命令仍在运行或没有退出标记。 */
    var exitCode: Int? = null
        private set

    /** 喂入一块终端原始输出。 */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() * 2 + 8)
        val result = decoder.decode(input, output, false)
        pending = if (result.isUnderflow) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            ByteArray(0)
        }
        output.flip()
        consume(output)
    }

    /** 结束输入：提交尚未换行的最后一行。 */
    fun finish() {
        if (currentLine.isNotEmpty()) {
            commitLine()
        } else {
            currentLine.setLength(0)
        }
    }

    /** 当前可见日志快照，包含尚未换行的最后一行（`\r` 刷新的进度）。 */
    fun snapshot(): List<String> {
        val result = lines.toMutableList()
        val tail = currentLine.toString()
        if (tail.isNotEmpty()) {
            result.add(tail)
            while (result.size > maxLines) result.removeAt(0)
        }
        return result
    }

    private fun consume(buffer: CharBuffer) {
        while (buffer.hasRemaining()) {
            val c = buffer.get()
            when (state) {
                ParseState.TEXT -> onText(c)
                ParseState.ESCAPE -> state = when (c) {
                    '[' -> ParseState.CSI
                    ']' -> ParseState.OSC
                    else -> ParseState.TEXT
                }

                ParseState.CSI -> if (c in '\u0040'..'\u007E') state = ParseState.TEXT
                ParseState.OSC -> when (c) {
                    '\u0007' -> state = ParseState.TEXT
                    '\u001B' -> state = ParseState.OSC_ESCAPE
                }

                ParseState.OSC_ESCAPE -> state = ParseState.TEXT
            }
        }
    }

    private fun onText(c: Char) {
        if (exitCode != null) return // 命令已结束，后续输出是 shell 提示符
        when {
            c == '\u001B' -> state = ParseState.ESCAPE
            c == '\n' -> {
                carriageReturnPending = false
                commitLine()
            }

            c == '\r' -> carriageReturnPending = true
            c == '\b' -> if (currentLine.isNotEmpty()) currentLine.deleteCharAt(currentLine.length - 1)
            c == '\t' -> appendChar(c)
            c.code < 0x20 -> Unit
            else -> appendChar(c)
        }
    }

    private fun appendChar(c: Char) {
        if (carriageReturnPending) {
            currentLine.setLength(0)
            carriageReturnPending = false
        }
        currentLine.append(c)
    }

    private fun commitLine() {
        val line = currentLine.toString()
        currentLine.setLength(0)
        val match = EXIT_MARKER.matchEntire(line.trim())
        if (match != null) {
            exitCode = match.groupValues[1].toIntOrNull()
            return
        }
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_LINES = 500
        private val EXIT_MARKER = Regex("""^\[command exited: (\d+)]$""")
    }
}
