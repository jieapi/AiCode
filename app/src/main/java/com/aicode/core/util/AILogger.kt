package com.aicode.core.util

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 提供商「完整请求 / 响应」日志：**每个会话(sessionId)一个文件**，逐次详细落盘每一次
 * 调用的 URL、请求体(body) 与响应(response)，便于在没有抓包工具时离线诊断模型交互问题。
 *
 * 与 [FileLogger]（按天分文件的通用应用日志）相互独立：本类按「会话」维度归档，体量更大、
 * 内容更全（含完整对话历史、工具定义、原始 SSE 流），因此单独成文件、单独清理。
 *
 * 文件落在外部私有目录 `getExternalFilesDir/ai-logs/session-<id>.log`（不可用时回退内部
 * `filesDir/ai-logs/`）。所有写入串行化到单线程后台执行，不阻塞调用方协程。
 * 请求体不含 API Key（密钥在 HTTP 头，本类只记录 URL 与 body），可安全留存。
 *
 * 不做长度截断：请求/响应体在写入线程里序列化后直接写盘，不构造整段大字符串；原始 SSE 分批
 * 落盘——因此长历史 / 大附件也不会 OOM。单个会话文件超过 20MB 时按份滚动归档（保留最近
 * [MAX_ARCHIVE_FILES] 份，命名 `session-<id>.log.1`、`.2`…），不清空历史。超大 base64 媒体（图片等）
 * 在写入前按 **JSON 结构**（字段名 + 值的形态）脱敏为占位符，避免单行几 MB 撑爆日志文件。
 *
 * 使用前需在 [android.app.Application.onCreate] 调用一次 [init]。
 */
/**
 * 失败异常可实现的接口：携带上游返回的**原始错误响应体**，供会话日志完整记录。
 * 定义在 core 层，[AILogger] 只按此接口取原文，不反向依赖 feature 层的具体异常类型。
 */
interface UpstreamErrorBodyCarrier {
    /** 上游错误响应体原文；为空表示无响应体可记。 */
    val upstreamErrorBody: String?
}

object AILogger {

    private const val TAG = "AILogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // 单会话文件上限 20MB（每轮重发完整历史，增长快）
    /** 单个会话滚动归档保留的份数（不含当前文件）；超出即丢弃最旧一份。 */
    private const val MAX_ARCHIVE_FILES = 3
    /** 原始 SSE 分批落盘的缓冲阈值（字符）：累积到该量即落盘，避免整段响应驻留内存。 */
    private const val SSE_FLUSH_CHARS = 64 * 1024
    /** 字段名提示为 base64 承载时，值超过该长度即视为媒体数据。 */
    private const val BASE64_MIN_CHARS = 256
    /** 兜底：任意位置连续 base64 字符超过该长度即视为媒体数据（不管字段叫什么）。 */
    private const val BASE64_RUN_CHARS = 1024

    /** 承载 base64 的常见字段名（Anthropic `source.data`、附件 `base64Data`、OpenAI `image_url.url` 等）。 */
    private val BASE64_FIELD_NAMES = setOf("data", "base64Data", "image_data", "url")

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-logger").apply { isDaemon = true }
    }
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    // 与 Retrofit 的 GsonConverter 行为对齐（默认字段名、忽略 null），额外开启缩进便于阅读，
    // 关掉 HTML 转义避免把 prompt 里的 < > & 转成实体、影响可读性。
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var logDir: File? = null

    /**
     * 最低记录等级阈值：与 [FileLogger] 同步，仅在 [LogLevel.DEBUG] 及以下（VERBOSE / DEBUG）
     * 时落盘完整 AI 请求/响应/SSE；INFO 及以上跳过写入，零磁盘与序列化开销。
     */
    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE
        private set

    /** 每会话的调用计数：用于把同一次交互的 REQUEST / RESPONSE 配上同一序号。 */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** 同步全局日志等级（由 `AIEditorApp` 在启动与设置变更时调用）。 */
    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    /** 是否允许记录 AI 请求日志：仅 DEBUG / VERBOSE 开启，INFO 及以上关闭。 */
    fun isLoggingEnabled(): Boolean = minLevel.ordinal <= LogLevel.DEBUG.ordinal

    /** 初始化日志目录。重复调用安全。 */
    fun init(context: Context) {
        if (logDir != null) return
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (minLevel == LogLevel.VERBOSE) {
            minLevel = if (debuggable) LogLevel.VERBOSE else LogLevel.INFO
        }
        // 优先外部私有目录，便于（root 或 adb 下）取出；不可用时回退内部存储。
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "ai-logs").apply { mkdirs() }
        logDir = dir
        ioExecutor.execute { cleanupOldLogs(dir) }
        FileLogger.i(TAG, "AILogger 初始化完成，AI 会话日志目录: ${dir.absolutePath}")
    }

    /**
     * 记录一次请求的 URL 与请求体，并把本会话计数 +1（作为本次交互的序号）。
     *
     * @return 本次分配的序号 `n`，调用方必须把它原样回传给对应的 [logResponse] /
     *   [logError] / [beginRawSse]，否则同会话内并发请求（如标题生成与主请求并行）
     *   会让 REQUEST 与 RESPONSE/ERROR 的编号错配——响应晚到时读到的是最新计数器值。
     */
    fun logRequest(sessionId: String?, provider: String, model: String, method: String, url: String, body: Any?): Int {
        if (!isLoggingEnabled()) return 0
        val n = counter(sessionId).incrementAndGet()
        appendToSession(sessionId) { w ->
            w.write("\n")
            w.write("=".repeat(78))
            w.write("\n")
            w.write("${now()}  REQUEST #$n   [$provider / $model]\n")
            w.write("$method $url\n")
            w.write("--- request body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
        return n
    }

    /** 记录一次非流式响应对象（用 Gson 序列化为 JSON）。[seq] 必须来自对应 [logRequest] 的返回值。 */
    fun logResponse(sessionId: String?, provider: String, body: Any?, seq: Int) {
        if (!isLoggingEnabled() || seq <= 0) return
        appendToSession(sessionId) { w ->
            w.write("${now()}  RESPONSE #$seq   [$provider]\n")
            w.write("--- response body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
    }

    /**
     * 记录一次请求失败（取消不算失败，不应走到这里）。[seq] 必须来自对应 [logRequest] 的返回值。
     *
     * 若 [throwable] 实现 [UpstreamErrorBodyCarrier] 且带原始错误响应体，会在异常信息之后
     * 追加 `--- error response body ---` 段落记录完整原文（脱敏后），不截断。
     */
    fun logError(sessionId: String?, provider: String, throwable: Throwable, seq: Int) {
        if (!isLoggingEnabled() || seq <= 0) return
        appendToSession(sessionId) { w ->
            w.write("${now()}  ERROR #$seq   [$provider]\n")
            w.write("${throwable.javaClass.name}: ${throwable.message ?: ""}\n")
            val rawBody = (throwable as? UpstreamErrorBodyCarrier)?.upstreamErrorBody
            if (!rawBody.isNullOrBlank()) {
                w.write("--- error response body ---\n")
                w.write(redactString(rawBody, null))
                w.write("\n")
            }
        }
    }

    /**
     * 开启一次流式响应的原始 SSE 日志句柄：调用方每收到一行就 [RawSseLog.append]，本轮结束
     * （成功 / 失败 / 取消）时在 `finally` 调用 [RawSseLog.finish]。内容分批落盘，不整段驻留内存。
     *
     * @param seq 必须来自对应 [logRequest] 的返回值。
     */
    fun beginRawSse(sessionId: String?, provider: String, seq: Int): RawSseLog =
        RawSseLog(sessionId, provider, seq, enabled = isLoggingEnabled() && seq > 0)

    /** 一次流式响应的原始 SSE 日志缓冲句柄，累积到阈值即分批落盘。 */
    class RawSseLog internal constructor(
        private val sessionId: String?,
        private val provider: String,
        private val seq: Int,
        private val enabled: Boolean
    ) {
        private val buffer = StringBuilder()
        private var headerWritten = false

        fun append(line: String) {
            if (!enabled) return
            buffer.append(redactString(line, null)).append('\n')
            if (buffer.length >= SSE_FLUSH_CHARS) flush()
        }

        fun finish() {
            if (!enabled) return
            if (buffer.isEmpty() && !headerWritten) {
                headerWritten = true
                writeChunk(header() + "(空响应)\n")
            } else {
                flush()
            }
        }

        private fun flush() {
            if (buffer.isEmpty() && headerWritten) return
            val chunk = buffer.toString()
            buffer.setLength(0)
            val prefix = if (!headerWritten) {
                headerWritten = true
                header()
            } else ""
            writeChunk(prefix + chunk)
        }

        private fun header(): String =
            "${now()}  RESPONSE #$seq   [$provider / stream]\n--- raw SSE ---\n"

        private fun writeChunk(text: String) {
            appendToSession(sessionId) { w -> w.write(text) }
        }
    }

    private fun counter(sessionId: String?): AtomicInteger =
        counters.getOrPut(sessionId ?: "unknown") { AtomicInteger(0) }

    /** AI 会话日志目录（未初始化时为 null）。供导出与诊断界面读取。 */
    fun logDirectory(): File? = logDir

    /** 返回当前所有会话日志文件，按文件名排序，供占用统计与清理使用。 */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** 删除指定名称的单个会话日志文件（排到 [ioExecutor] 避免与追加写冲突）。 */
    fun deleteLogFile(fileName: String): Boolean {
        val dir = logDir ?: return false
        if (fileName.contains('/') || fileName.contains('\\') || !fileName.startsWith("session-")) return false
        val target = File(dir, fileName)
        val deleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                if (target.exists() && target.isFile) {
                    deleted.set(target.delete())
                }
                if (fileName.endsWith(".log")) {
                    val id = fileName.removePrefix("session-").removeSuffix(".log")
                    counters.remove(id)
                }
            }.onFailure { Log.e(TAG, "删除 AI 会话日志失败: $fileName", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return deleted.get()
    }

    /**
     * 删除全部会话日志，返回释放的字节数。
     *
     * 排到 [ioExecutor] 上执行，避免与排队中的追加写交错（本类每次写入都是 append 后即关，不持有句柄）。
     * 同时重置调用序号，清空后新日志从 #1 开始。
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
                counters.clear()
            }.onFailure { Log.e(TAG, "清空 AI 会话日志失败", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun now(): String = timestampFormat.format(java.time.Instant.now())

    /** 把 body 脱敏后流式写入 [w]：对象走 Gson 树序列化（不构造整段字符串）。 */
    private fun writeBody(w: Writer, body: Any?) {
        when (body) {
            null -> w.write("null")
            is String -> w.write(redactString(body, null))
            else -> {
                val tree = runCatching { gson.toJsonTree(body) }.getOrNull()
                if (tree == null) {
                    w.write(redactString(body.toString(), null))
                } else {
                    gson.toJson(redactInPlace(tree), w)
                }
            }
        }
    }

    /** 供测试：把 body 按日志脱敏规则序列化为 JSON 文本（不写盘）。 */
    internal fun redactToJson(body: Any?): String =
        java.io.StringWriter().also { writeBody(it, body) }.toString()

    /** 递归遍历 JSON 树，把媒体 base64 就地替换为占位符（树由 [GsonBuilder] 新生成，可安全修改）。 */
    private fun redactInPlace(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            element.keySet().toList().forEach { key ->
                val value = element.get(key)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.add(key, JsonPrimitive(redactString(value.asString, key)))
                }
            }
            element
        }
        is JsonArray -> {
            for (i in 0 until element.size()) {
                val value = element.get(i)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.set(i, JsonPrimitive(redactString(value.asString, null)))
                }
            }
            element
        }
        else -> element
    }

    /**
     * 单个字符串值的脱敏：把内联 data URL、按字段名判定的裸 base64、以及任意位置超长 base64 串
     * 替换为占位符。只在单个值内匹配，不跨字段，避免全文正则那种「换个格式就漏」的脆弱。
     */
    private fun redactString(value: String, key: String?): String {
        if (value.isEmpty()) return value
        var out = DATA_URL_REGEX.replace(value) { m -> "[base64 omitted: ${m.value.length} chars]" }
        if (key != null && key in BASE64_FIELD_NAMES && out.length >= BASE64_MIN_CHARS && out.all { it.isBase64Char() }) {
            return "[base64 omitted: ${out.length} chars]"
        }
        out = LONG_BASE64_RUN_REGEX.replace(out) { m -> "[base64 omitted: ${m.value.length} chars]" }
        return out
    }

    private fun Char.isBase64Char(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '+' || this == '/' || this == '=' || this == '_' || this == '-'

    /** 在后台单线程上打开会话日志文件（追加模式）执行写入块；文件超上限则先滚动归档。 */
    private fun appendToSession(sessionId: String?, block: (Writer) -> Unit) {
        val dir = logDir ?: return // 未初始化则直接丢弃，避免在无目录时报错刷屏
        val safeId = (sessionId ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "session-$safeId.log")
                if (file.length() > MAX_FILE_BYTES) rotate(file)
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { w ->
                    block(w)
                    w.flush()
                }
            }.onFailure { Log.e(TAG, "写入 AI 会话日志失败", it) }
        }
    }

    /**
     * 会话日志滚动归档：`session-<id>.log` → `.1` → `.2` → …，超过 [MAX_ARCHIVE_FILES] 份丢弃最旧。
     * 在 [appendToSession] 的写入线程上调用，与追加写同线程，无并发。
     */
    private fun rotate(file: File) {
        val dir = file.parentFile ?: return
        File(dir, "${file.name}.$MAX_ARCHIVE_FILES").delete()
        for (i in MAX_ARCHIVE_FILES - 1 downTo 1) {
            val src = File(dir, "${file.name}.$i")
            if (src.exists()) src.renameTo(File(dir, "${file.name}.${i + 1}"))
        }
        file.renameTo(File(dir, "${file.name}.1"))
    }

    /** 删除超过 [MAX_AGE_DAYS] 天未更新的会话日志文件。 */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /** 内联 base64 data URL（`data:<mime>;base64,...`，mime 可为空/任意）。 */
    private val DATA_URL_REGEX = Regex("data:[A-Za-z0-9.+/-]*;base64,[A-Za-z0-9+/=_-]{$BASE64_MIN_CHARS,}")
    /** 兜底：任意位置 ≥ [BASE64_RUN_CHARS] 的连续 base64 字符。 */
    private val LONG_BASE64_RUN_REGEX = Regex("[A-Za-z0-9+/=_-]{$BASE64_RUN_CHARS,}")
}
