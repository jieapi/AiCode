package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * 管理插件创建的共享终端标签。
 *
 * 标签归属创建它的插件，只能由该插件轮询、停止或关闭；[TerminalSessionProvider.listTabs]
 * 不返回插件标签，所以 AI 的 terminal 工具和用户终端页默认都看不到它们。输出由
 * `tabOutput` 的原始字节流累积到有界缓冲，插件通过偏移轮询增量，避免页面桥长连接。
 */
@Singleton
class PluginTerminalRunner @Inject constructor(
    private val provider: TerminalSessionProvider,
    private val pluginManager: PluginManager
) {

    private class Run(val pluginId: String, val tabId: String) {
        val buffer = StringBuilder()
        var totalChars: Long = 0
        var cancelled = false
        var collector: Job? = null

        fun append(bytes: ByteArray) {
            val text = bytes.toString(Charsets.UTF_8)
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
        val tabId = withContext(Dispatchers.Main) {
            provider.startPluginCommand(command, title, pluginId)
        }
        val run = Run(pluginId, tabId)
        runs[tabId] = run
        run.collector = scope.launch {
            provider.tabOutput(tabId)?.collect { bytes -> run.append(bytes) }
        }
        return tabId
    }

    /** 从 [offset] 读取新增输出；输出缓冲超出上限时 [PluginTerminalPoll.truncated] 为 true。 */
    suspend fun poll(pluginId: String, tabId: String, offset: Long): PluginTerminalPoll {
        val run = owned(pluginId, tabId)
        val status = withContext(Dispatchers.Main) { provider.tabStatus(tabId) }
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
        withContext(Dispatchers.Main) { provider.writeBytesToTab(tabId, 0x03) }
        val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
            var running = true
            while (running) {
                val status = withContext(Dispatchers.Main) { provider.tabStatus(tabId) }
                running = status?.running == true
                if (running) delay(100)
            }
            true
        } ?: false
        if (!stopped) {
            withContext(Dispatchers.Main) { provider.closeTab(tabId) }
        }
        return true
    }

    /** 关闭标签并释放输出收集；标签已结束也可以调用。 */
    suspend fun close(pluginId: String, tabId: String): Boolean {
        val run = owned(pluginId, tabId)
        runs.remove(tabId)
        run.collector?.cancel()
        return withContext(Dispatchers.Main) { provider.closeTab(tabId) }
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
                    withContext(Dispatchers.Main) { provider.closeTab(tabId) }
                }
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
    }
}
