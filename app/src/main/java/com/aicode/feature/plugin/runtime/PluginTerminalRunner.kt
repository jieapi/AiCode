package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.agent.domain.container.ContainerNotInitializedException
import com.aicode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 插件终端调用失败；[code] 是稳定的错误码，页面桥与进程 API 原样转交。 */
class PluginTerminalException(val code: String, override val message: String) : Exception(message)

/** 一次插件终端轮询的结果；[offset] 是下一次轮询应传入的偏移。 */
data class PluginTerminalPoll(
    val output: String,
    val offset: Long,
    val state: String,
    val exitCode: Int?,
    val truncated: Boolean
)

/**
 * 有状态的 UTF-8 流式解码。
 *
 * 终端按字节块回调，一个多字节字符可能被拆到相邻两块；[decode] 会把块尾不完整的序列留到
 * 下一块补齐，[flush] 在输出流结束时把残留字节按替换字符吐出。只由单一线程按顺序调用。
 */
internal class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)
    private var finished = false

    fun decode(bytes: ByteArray): String {
        if (finished) return ""
        val input = ByteBuffer.wrap(if (pending.isEmpty()) bytes else pending + bytes)
        pending = ByteArray(0)
        if (!input.hasRemaining()) return ""

        val out = StringBuilder(input.remaining())
        val chars = CharBuffer.allocate(CHARS_CHUNK)
        while (true) {
            val result = decoder.decode(input, chars, false)
            chars.flip()
            out.append(chars)
            chars.clear()
            if (result.isOverflow()) continue
            if (input.hasRemaining()) {
                pending = ByteArray(input.remaining()).also { input.get(it) }
            }
            return out.toString()
        }
    }

    fun flush(): String {
        if (finished) return ""
        finished = true

        val out = StringBuilder()
        val chars = CharBuffer.allocate(CHARS_CHUNK)
        val input = ByteBuffer.wrap(pending)
        pending = ByteArray(0)
        while (true) {
            val result = decoder.decode(input, chars, true)
            chars.flip()
            out.append(chars)
            chars.clear()
            if (!result.isOverflow()) break
        }
        while (true) {
            val result = decoder.flush(chars)
            chars.flip()
            out.append(chars)
            chars.clear()
            if (!result.isOverflow()) break
        }
        decoder.reset()
        return out.toString()
    }

    private companion object {
        const val CHARS_CHUNK = 1024
    }
}

/**
 * 管理插件创建的共享终端标签。
 *
 * 标签归属创建它的插件，只能由该插件轮询、写入、列出、停止或关闭；[TerminalSessionProvider.listTabs]
 * 不返回插件标签，所以 AI 的 terminal 工具和用户终端页默认都看不到它们。输出由
 * `tabOutput` 的原始字节流累积到有界缓冲，插件通过偏移轮询增量，避免页面桥长连接。
 */
@Singleton
class PluginTerminalRunner @Inject constructor(
    private val provider: TerminalSessionProvider,
    private val pluginManager: PluginManager
) {

    private class Run(
        val pluginId: String,
        val provider: TerminalSessionProvider,
        val tabId: String,
        val title: String,
        val command: String
    ) {
        val buffer = StringBuilder()
        var totalChars: Long = 0
        @Volatile var cancelled = false
        val input = Mutex()
        var collector: Job? = null

        /** 只在收集协程内调用，块与块之间保留不完整的 UTF-8 序列。 */
        private val decoder = Utf8StreamDecoder()

        fun append(bytes: ByteArray) {
            appendText(decoder.decode(bytes))
        }

        /** 输出流结束或关闭时调用，吐出解码器里残留的替换字符。 */
        fun flush() {
            appendText(decoder.flush())
        }

        private fun appendText(text: String) {
            if (text.isEmpty()) return
            synchronized(this) {
                buffer.append(text)
                totalChars += text.length
                if (buffer.length > MAX_BUFFER_CHARS) {
                    buffer.delete(0, buffer.length - MAX_BUFFER_CHARS)
                }
            }
        }
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 新建插件命令标签；命令结束后标签保留为已结束，由插件调用 [close]。 */
    suspend fun start(pluginId: String, command: String, title: String?): String {
        requireActive(pluginId)
        if (command.isBlank()) throw PluginTerminalException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginTerminalException("PARAM_INVALID", "command too long")
        }
        val tabId = try {
            provider.startPluginCommand(command, title, pluginId)
        } catch (e: ContainerNotInitializedException) {
            throw PluginTerminalException("CONTAINER_NOT_INITIALIZED", e.message.orEmpty())
        }
        val run = Run(pluginId, provider, tabId, title ?: tabId, command)
        runs[tabId] = run
        run.collector = scope.launch {
            try {
                run.provider.tabOutput(run.tabId)?.collect { bytes -> run.append(bytes) }
            } finally {
                run.flush()
            }
        }
        return tabId
    }

    /** 从 [offset] 读取新增输出；输出缓冲超出上限时 [PluginTerminalPoll.truncated] 为 true。 */
    suspend fun poll(pluginId: String, tabId: String, offset: Long): PluginTerminalPoll {
        val run = owned(pluginId, tabId)
        val status = withContext(Dispatchers.IO) { run.provider.tabStatus(run.tabId) }
        val from = offset.coerceAtLeast(0)
        var dropped = 0L
        var output = ""
        var next = 0L
        synchronized(run) {
            dropped = (run.totalChars - run.buffer.length).coerceAtLeast(0)
            val start = (from - dropped).coerceIn(0, run.buffer.length.toLong()).toInt()
            output = run.buffer.substring(start)
            next = run.totalChars
        }
        val state = when {
            run.cancelled -> "cancelled"
            status?.running == true -> "running"
            else -> "finished"
        }
        return PluginTerminalPoll(
            output = output,
            offset = next,
            state = state,
            exitCode = status?.exitCode,
            truncated = from < dropped
        )
    }

    /** 发送 Ctrl-C；超时仍未退出则关闭标签。返回是否已发出停止。 */
    suspend fun stop(pluginId: String, tabId: String): Boolean {
        val run = owned(pluginId, tabId)
        run.cancelled = true
        run.input.withLock {
            withContext(Dispatchers.IO) { run.provider.writeBytesToTab(run.tabId, 0x03) }
        }
        val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            var running = true
            while (running) {
                val status = withContext(Dispatchers.IO) { run.provider.tabStatus(run.tabId) }
                running = status?.running == true
                if (running) delay(100)
            }
            true
        } ?: false
        if (!stopped) {
            closeBoundTab(run)
        }
        return true
    }

    /** 向自己的标签发送输入；[appendNewline] 为 true 时由终端补回车。 */
    suspend fun write(pluginId: String, tabId: String, text: String, appendNewline: Boolean = true): Boolean {
        val run = owned(pluginId, tabId)
        if (text.isEmpty()) throw PluginTerminalException("PARAM_INVALID", "empty input")
        if (text.length > MAX_INPUT_CHARS) throw PluginTerminalException("PARAM_INVALID", "input too long")
        return run.input.withLock {
            withContext(Dispatchers.IO) { run.provider.sendInput(run.tabId, text, appendNewline) }
        }
    }

    /** 列出当前插件自己创建的标签及其运行状态。 */
    suspend fun list(pluginId: String): JsonElement {
        val mine = runs.values.filter { it.pluginId == pluginId }.sortedBy { it.tabId }
        val statuses = withContext(Dispatchers.IO) {
            mine.associate { it.tabId to it.provider.tabStatus(it.tabId) }
        }
        val entries = mine.map { run ->
            val status = statuses[run.tabId]
            val state = when {
                run.cancelled -> "cancelled"
                status?.running == true -> "running"
                else -> "finished"
            }
            buildJsonObject {
                put("tabId", run.tabId)
                put("title", run.title)
                put("command", run.command)
                put("state", state)
            }
        }
        return JsonArray(entries)
    }

    /** 关闭标签并释放输出收集；标签已结束也可以调用。 */
    suspend fun close(pluginId: String, tabId: String): Boolean {
        val run = owned(pluginId, tabId)
        runs.remove(tabId)
        run.collector?.cancel()
        closeBoundTab(run)
        return true
    }

    /** 插件停用或卸载时关闭它创建的全部标签。 */
    fun closePlugin(pluginId: String) {
        runs.values
            .filter { it.pluginId == pluginId }
            .map { it.tabId }
            .forEach { tabId ->
                val run = runs.remove(tabId) ?: return@forEach
                run.collector?.cancel()
                scope.launch {
                    closeBoundTab(run)
                }
            }
    }

    private suspend fun closeBoundTab(run: Run): Boolean = run.input.withLock {
        val boundProvider = run.provider
        if (boundProvider is DelegatingTerminalSessionProvider) {
            boundProvider.closeTabAsync(run.tabId)
        } else {
            withContext(Dispatchers.IO) { boundProvider.closeTab(run.tabId) }
        }
    }

    private fun owned(pluginId: String, tabId: String): Run {
        val run = runs[tabId]
            ?: throw PluginTerminalException("TAB_NOT_FOUND", "unknown terminal tab: $tabId")
        if (run.pluginId != pluginId) {
            throw PluginTerminalException("TAB_FORBIDDEN", "terminal tab belongs to another plugin")
        }
        return run
    }

    private suspend fun requireActive(pluginId: String) {
        pluginManager.ensureLoaded()
        val active = pluginManager.plugins.value.any { it.id == pluginId && it.isActive }
        if (!active) throw PluginTerminalException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private companion object {
        /** 每个插件标签保留的输出上限；超出部分丢弃最旧内容并标记 truncated。 */
        const val MAX_BUFFER_CHARS = 128 * 1024
        const val STOP_TIMEOUT_MS = 2_000L

        /** 单次写入的字符上限。 */
        const val MAX_INPUT_CHARS = 4000
    }
}
