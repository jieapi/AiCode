package com.aicode.feature.settings.presentation

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LogLevel
import com.aicode.core.util.TombstoneParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 日志查看器顶部/底部数据源分类。 */
enum class LogSourceTab {
    APP,
    AI,
    CRASH
}

/** 前端查看时的等级过滤（不影响后台实际落盘等级）。 */
enum class LogLevelFilter {
    ALL,
    ERROR,
    WARN_PLUS,
    INFO_PLUS
}

/** 关键字搜索模式：过滤（只保留匹配行）或定位（保留上下文并在匹配项间跳转）。 */
enum class LogSearchMode {
    FILTER,
    FIND
}

/** 日志文件列表项元数据，供切换弹窗展示名称、大小与更新时间。 */
data class LogFileItem(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val subLabel: String? = null
)

/**
 * AI 会话日志的轻量级索引块：初次打开文件时只扫描头部与字节区间，不把大段请求/响应体驻留内存。
 * 只有用户展开某张卡片或查看详情时，才按 [byteRanges] 从文件按需读取正文。
 */
data class AiLogBlock(
    val seq: Int,
    val requestTime: String,
    val providerModel: String,
    val methodUrl: String,
    val responseHeader: String? = null,
    val responseTime: String? = null,
    val isError: Boolean = false,
    val isStream: Boolean = false,
    val byteRanges: List<LongRange> = emptyList(),
    val approxBytes: Long = 0L
)

/** 某次 AI 调用展开后按需加载的正文详情。 */
data class AiLogDetail(
    val requestBody: String,
    val responseBody: String?,
    val rawText: String
)

/** 应用日志分页尾读结果。 */
data class TailLinesResult(
    val visibleLines: List<String>,
    val totalLines: Int,
    val hasMoreEarlier: Boolean
)

data class LogViewerUiState(
    val source: LogSourceTab = LogSourceTab.APP,
    val files: List<LogFileItem> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val filterTag: String? = null,
    val levelFilter: LogLevelFilter = LogLevelFilter.ALL,
    val maxVisibleLines: Int = INITIAL_LOG_LINES,
    val maxVisibleAiBlocks: Int = INITIAL_AI_BLOCKS,
    val content: String = "",
    val aiBlocks: List<AiLogBlock> = emptyList(),
    val aiBlockDetails: Map<Int, AiLogDetail> = emptyMap(),
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val hasMoreEarlier: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
) {
    companion object {
        const val INITIAL_LOG_LINES = 800
        const val LOG_LINES_PAGE_STEP = 1000
        const val INITIAL_AI_BLOCKS = 25
        const val AI_BLOCKS_PAGE_STEP = 25
    }
}

/**
 * 解析 [FileLogger] 与 [AILogger] 文本、尾读大文件、字节索引懒加载及分享打包工具。
 */
internal object LogViewerHelper {

    private val STANDARD_LINE_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (VERBOSE|DEBUG|INFO|WARN|ERROR)(?: \[([^\]]*)\])? (.*)$"""
    )

    private val AI_REQUEST_HEADER = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+REQUEST #(\d+)\s+\[([^\]]+)\]$"""
    )

    private val AI_RESPONSE_HEADER = Regex(
        """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(RESPONSE|ERROR) #(\d+)\s+\[([^\]]+)\]$"""
    )

    private val AI_SEPARATOR = "=".repeat(78)

    /**
     * 读取应用日志尾部 [maxLines] 行：
     * - 无过滤条件时使用 [RandomAccessFile] 从文件尾倒序按块读取，不把整个文件拆成 `List<String>`；
     * - 有过滤条件时流式逐行过滤，只用环形缓冲保留最后 [maxLines] 条，大幅降低内存峰值。
     */
    fun readFilteredAppLogTail(
        file: File,
        mcpServerName: String?,
        levelFilter: LogLevelFilter,
        tagFilter: String?,
        maxLines: Int
    ): TailLinesResult {
        val needFilter = !mcpServerName.isNullOrBlank() ||
            levelFilter != LogLevelFilter.ALL ||
            !tagFilter.isNullOrBlank()
        if (!needFilter) {
            return readTailLinesFast(file, maxLines)
        }

        val ring = ArrayDeque<String>(maxLines + 1)
        var totalMatched = 0
        var keepContinuation = false
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 32 * 1024).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val match = STANDARD_LINE_REGEX.matchEntire(line)
                if (match != null) {
                    val level = runCatching { LogLevel.valueOf(match.groupValues[2]) }.getOrNull()
                    val tag = match.groupValues[3]
                    val passLevel = when (levelFilter) {
                        LogLevelFilter.ALL -> true
                        LogLevelFilter.ERROR -> level == LogLevel.ERROR
                        LogLevelFilter.WARN_PLUS -> level == LogLevel.WARN || level == LogLevel.ERROR
                        LogLevelFilter.INFO_PLUS -> level == LogLevel.INFO || level == LogLevel.WARN || level == LogLevel.ERROR
                    }
                    val passTag = tagFilter.isNullOrBlank() || tag.equals(tagFilter, ignoreCase = true)
                    val passMcp = mcpServerName.isNullOrBlank() || (
                        tag.startsWith("Mcp") &&
                            (line.contains("[$mcpServerName]") || line.contains("mcp__${mcpServerName}__"))
                        )
                    keepContinuation = passLevel && passTag && passMcp
                    if (keepContinuation) {
                        totalMatched++
                        if (ring.size >= maxLines) ring.removeFirst()
                        ring.addLast(line)
                    }
                } else if (keepContinuation) {
                    totalMatched++
                    if (ring.size >= maxLines) ring.removeFirst()
                    ring.addLast(line)
                }
            }
        }
        return TailLinesResult(
            visibleLines = ring.toList(),
            totalLines = totalMatched,
            hasMoreEarlier = totalMatched > ring.size
        )
    }

    /** 倒序按块从文件尾寻找最后 [maxLines] 行，同时低内存统计总行数。 */
    private fun readTailLinesFast(file: File, maxLines: Int): TailLinesResult {
        val len = file.length()
        if (len == 0L) return TailLinesResult(emptyList(), 0, false)

        // 快速读取尾部所需字节
        var tailStartPos = 0L
        var linesFoundInTail = 0
        var totalNewlines = 0
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(16 * 1024)
            var pos = len
            var stopTailScan = false
            while (pos > 0L && !stopTailScan) {
                val readSize = minOf(buf.size.toLong(), pos).toInt()
                pos -= readSize
                raf.seek(pos)
                raf.readFully(buf, 0, readSize)
                for (i in readSize - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        // 忽略文件末尾最后一个换行符
                        if (pos + i < len - 1) {
                            linesFoundInTail++
                            if (linesFoundInTail >= maxLines) {
                                tailStartPos = pos + i + 1
                                stopTailScan = true
                                break
                            }
                        }
                    }
                }
            }

            // 仅在必要时统计总行数（纯 byte 扫描，无 String 分配）
            if (tailStartPos == 0L) {
                // 已经扫到文件头，不必再扫一遍
                val tailBytes = ByteArray(len.toInt())
                raf.seek(0L)
                raf.readFully(tailBytes)
                val lines = String(tailBytes, Charsets.UTF_8).trimEnd('\n').split('\n')
                return TailLinesResult(lines, lines.size, false)
            } else {
                // 从头到 tailStartPos 统计 '\n' 数量，计算准确总行数
                raf.seek(0L)
                var remaining = tailStartPos
                while (remaining > 0L) {
                    val chunk = minOf(buf.size.toLong(), remaining).toInt()
                    val n = raf.read(buf, 0, chunk)
                    if (n <= 0) break
                    for (i in 0 until n) {
                        if (buf[i] == '\n'.code.toByte()) totalNewlines++
                    }
                    remaining -= n
                }
                val tailLen = (len - tailStartPos).toInt()
                val tailBytes = ByteArray(tailLen)
                raf.seek(tailStartPos)
                raf.readFully(tailBytes)
                val tailLines = String(tailBytes, Charsets.UTF_8).trimEnd('\n').split('\n')
                val totalLines = totalNewlines + tailLines.size
                return TailLinesResult(
                    visibleLines = tailLines,
                    totalLines = totalLines,
                    hasMoreEarlier = totalLines > tailLines.size
                )
            }
        }
    }

    /**
     * 高速索引 [AILogger] 会话文件：仅解析头部行并记录每段字节区间 `[startByte, endByte]`，
     * 跳过大段 JSON / SSE 正文，保证 20MB 文件也能毫秒级打开。
     */
    fun indexAiLogFile(file: File): List<AiLogBlock> {
        if (!file.exists() || file.length() == 0L) return emptyList()

        class MutableIndex(val seq: Int) {
            var requestTime: String = ""
            var providerModel: String = ""
            var methodUrl: String = ""
            var responseHeader: String? = null
            var responseTime: String? = null
            var isError: Boolean = false
            var isStream: Boolean = false
            val ranges = ArrayList<LongRange>(2)
            var currentRunStart: Long = -1L
            var currentRunEnd: Long = -1L
            var expectMethodUrl: Boolean = false

            fun appendSpan(start: Long, end: Long) {
                if (currentRunStart < 0L) {
                    currentRunStart = start
                    currentRunEnd = end
                } else if (start == currentRunEnd) {
                    currentRunEnd = end
                } else {
                    ranges.add(currentRunStart until currentRunEnd)
                    currentRunStart = start
                    currentRunEnd = end
                }
            }

            fun flushSpan() {
                if (currentRunStart >= 0L && currentRunEnd > currentRunStart) {
                    ranges.add(currentRunStart until currentRunEnd)
                }
                currentRunStart = -1L
                currentRunEnd = -1L
            }
        }

        val blocks = LinkedHashMap<Int, MutableIndex>()
        var activeBlock: MutableIndex? = null

        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            val lineBytes = ByteArrayOutputStream(256)
            var offset = 0L
            var lineStart = 0L

            fun processLine(bytes: ByteArray, endOffset: Long) {
                val lineLen = bytes.size
                // 快速预筛：只解码分隔线、时间戳头部行以及紧跟 REQUEST 后的 methodUrl 行
                val looksLikeSeparator = lineLen >= 78 && bytes[0] == '='.code.toByte()
                val looksLikeTimestamp = lineLen >= 25 &&
                    bytes[0] == '2'.code.toByte() &&
                    bytes[1] == '0'.code.toByte() &&
                    bytes[4] == '-'.code.toByte() &&
                    bytes[10] == ' '.code.toByte()

                if (looksLikeSeparator) {
                    val line = String(bytes, Charsets.UTF_8).trimEnd('\r')
                    if (line == AI_SEPARATOR) {
                        activeBlock?.expectMethodUrl = false
                        activeBlock?.flushSpan()
                        activeBlock = null
                        return
                    }
                }

                if (looksLikeTimestamp) {
                    val line = String(bytes, Charsets.UTF_8).trimEnd('\r')
                    val reqMatch = AI_REQUEST_HEADER.matchEntire(line)
                    if (reqMatch != null) {
                        activeBlock?.flushSpan()
                        val seq = reqMatch.groupValues[2].toIntOrNull() ?: 0
                        val idx = blocks.getOrPut(seq) { MutableIndex(seq) }
                        idx.requestTime = reqMatch.groupValues[1]
                        idx.providerModel = reqMatch.groupValues[3]
                        idx.expectMethodUrl = true
                        idx.appendSpan(lineStart, endOffset)
                        activeBlock = idx
                        return
                    }
                    val respMatch = AI_RESPONSE_HEADER.matchEntire(line)
                    if (respMatch != null) {
                        activeBlock?.flushSpan()
                        val kind = respMatch.groupValues[2]
                        val seq = respMatch.groupValues[3].toIntOrNull() ?: 0
                        val idx = blocks.getOrPut(seq) { MutableIndex(seq) }
                        idx.responseTime = respMatch.groupValues[1]
                        idx.responseHeader = respMatch.groupValues[4]
                        idx.isError = kind == "ERROR"
                        idx.isStream = respMatch.groupValues[4].contains("/ stream")
                        if (idx.providerModel.isEmpty()) {
                            idx.providerModel = respMatch.groupValues[4]
                        }
                        idx.expectMethodUrl = false
                        idx.appendSpan(lineStart, endOffset)
                        activeBlock = idx
                        return
                    }
                }

                val cur = activeBlock
                if (cur != null) {
                    if (cur.expectMethodUrl) {
                        val line = String(bytes, Charsets.UTF_8).trimEnd('\r')
                        if (line == "--- request body ---") {
                            cur.expectMethodUrl = false
                        } else if (line.isNotBlank()) {
                            cur.methodUrl = line
                            cur.expectMethodUrl = false
                        }
                    }
                    cur.appendSpan(lineStart, endOffset)
                }
            }

            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (lineBytes.size() > 0) {
                        processLine(lineBytes.toByteArray(), offset)
                    }
                    break
                }
                offset++
                if (b == '\n'.code) {
                    processLine(lineBytes.toByteArray(), offset)
                    lineBytes.reset()
                    lineStart = offset
                } else {
                    // 头部行与分隔线都不超过 2KB，单行超长时只需继续跳过直到换行，不必把几十 KB SSE 放进 ByteArrayOutputStream
                    if (lineBytes.size() < 2048) {
                        lineBytes.write(b)
                    }
                }
            }
        }

        blocks.values.forEach { it.flushSpan() }

        return blocks.values.map { b ->
            val approx = b.ranges.sumOf { (it.last - it.first + 1).coerceAtLeast(0L) }
            AiLogBlock(
                seq = b.seq,
                requestTime = b.requestTime.ifEmpty { b.responseTime.orEmpty() },
                providerModel = b.providerModel,
                methodUrl = b.methodUrl,
                responseHeader = b.responseHeader,
                responseTime = b.responseTime,
                isError = b.isError,
                isStream = b.isStream,
                byteRanges = b.ranges,
                approxBytes = approx
            )
        }
    }

    /** 按 [AiLogBlock.byteRanges] 从文件精确读取单次交互正文。 */
    fun readAiBlockDetail(file: File, block: AiLogBlock): AiLogDetail {
        if (!file.exists() || block.byteRanges.isEmpty()) {
            return AiLogDetail("", null, "")
        }
        val raw = buildString {
            RandomAccessFile(file, "r").use { raf ->
                for (range in block.byteRanges) {
                    val len = (range.last - range.first + 1).coerceAtLeast(0L)
                    if (len <= 0L) continue
                    // 单块保护上限 2MB，避免极端单轮 SSE 撑爆视图
                    val capped = minOf(len, 2L * 1024 * 1024).toInt()
                    val buf = ByteArray(capped)
                    raf.seek(range.first)
                    raf.readFully(buf)
                    append(String(buf, Charsets.UTF_8))
                    if (len > capped) append("\n… (内容超过 2MB 已截断，可通过右上角分享查看完整文件)\n")
                }
            }
        }.trimEnd()

        return parseSingleAiBlockText(raw)
    }

    /** 解析单个调用块的原文为请求体与响应体。 */
    internal fun parseSingleAiBlockText(raw: String): AiLogDetail {
        val requestLines = StringBuilder()
        var responseLines: StringBuilder? = null
        var inRequestBody = false
        var inResponseSection = false

        for (line in raw.lineSequence()) {
            if (AI_REQUEST_HEADER.matches(line)) {
                inRequestBody = false
                inResponseSection = false
                continue
            }
            if (AI_RESPONSE_HEADER.matches(line)) {
                inRequestBody = false
                inResponseSection = true
                if (responseLines == null) responseLines = StringBuilder()
                continue
            }
            if (inResponseSection) {
                val target = responseLines ?: StringBuilder().also { responseLines = it }
                if (line != "--- response body ---" && line != "--- raw SSE ---") {
                    if (target.isNotEmpty()) target.append('\n')
                    target.append(line)
                }
            } else {
                if (line == "--- request body ---") {
                    inRequestBody = true
                } else if (inRequestBody) {
                    if (requestLines.isNotEmpty()) requestLines.append('\n')
                    requestLines.append(line)
                }
            }
        }
        return AiLogDetail(
            requestBody = requestLines.toString().trimEnd(),
            responseBody = responseLines?.toString()?.trimEnd(),
            rawText = raw
        )
    }

    /** 把崩溃追踪文件转为可读文本；`.pb` tombstone 经 [TombstoneParser] 解析。 */
    fun readCrashTraceText(file: File): String {
        if (!file.exists()) return ""
        if (file.name.endsWith(".pb", ignoreCase = true)) {
            val bytes = file.readBytes()
            return TombstoneParser.parse(bytes)
                ?: extractPrintableSummary(bytes)
                ?: "(无法解析 tombstone: ${file.name}, ${bytes.size} bytes)"
        }
        return file.readText(Charsets.UTF_8)
    }

    private fun extractPrintableSummary(bytes: ByteArray): String? {
        val sb = StringBuilder()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= 4) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(cur)
            }
            cur.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) cur.append(c.toChar()) else flush()
        }
        flush()
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 把单个日志文件复制到 `cacheDir/shared-logs/` 并生成系统分享 Intent。 */
    fun buildShareSingleFileIntent(context: Context, file: File, isCrashPb: Boolean): Intent {
        val shareDir = prepareShareDir(context)
        val outFile = if (isCrashPb) {
            val txtName = file.name.removeSuffix(".pb") + ".txt"
            File(shareDir, txtName).apply { writeText(readCrashTraceText(file), Charsets.UTF_8) }
        } else {
            File(shareDir, file.name).apply { file.copyTo(this, overwrite = true) }
        }
        return createShareIntent(context, outFile, "text/plain", outFile.name)
    }

    /** 把当前所有应用日志、AI 会话日志与崩溃追踪打包成一个 zip，并生成分享 Intent。 */
    fun buildShareAllZipIntent(context: Context): Intent? {
        val appLogs = FileLogger.listLogFiles()
        val crashLogs = FileLogger.listCrashTraceFiles()
        val aiLogs = AILogger.listLogFiles()
        if (appLogs.isEmpty() && crashLogs.isEmpty() && aiLogs.isEmpty()) return null

        val shareDir = prepareShareDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(shareDir, "aicode-logs-$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            appLogs.forEach { writeZipEntry(zos, "app/${it.name}", it) }
            aiLogs.forEach { writeZipEntry(zos, "ai/${it.name}", it) }
            crashLogs.forEach { file ->
                writeZipEntry(zos, "crash/${file.name}", file)
                if (file.name.endsWith(".pb", ignoreCase = true)) {
                    val parsed = runCatching { readCrashTraceText(file) }.getOrNull()
                    if (!parsed.isNullOrBlank()) {
                        val txtEntry = ZipEntry("crash/${file.name.removeSuffix(".pb")}.txt")
                        zos.putNextEntry(txtEntry)
                        zos.write(parsed.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }
            }
        }
        return createShareIntent(context, zipFile, "application/zip", zipFile.name)
    }

    private fun prepareShareDir(context: Context): File {
        val dir = File(context.cacheDir, "shared-logs").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
        dir.listFiles()?.forEach { old ->
            if (old.lastModified() < cutoff) runCatching { old.delete() }
        }
        return dir
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists() || !file.isFile) return
        runCatching {
            zos.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(file)).use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
        }
    }

    private fun createShareIntent(context: Context, file: File, mimeType: String, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
