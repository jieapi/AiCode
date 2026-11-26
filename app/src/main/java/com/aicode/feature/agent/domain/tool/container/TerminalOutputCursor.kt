package com.aicode.feature.agent.domain.tool.container

import javax.inject.Inject
import javax.inject.Singleton

/**
 * `terminal` 工具的输出去重游标：按标签记录已返回给 AI 的文本，下次读取只返回新增部分。
 *
 * 终端标签的屏幕缓冲会保留整段 transcript，而 read/send 每次都取全量回放给模型，
 * 长驻标签（dev server、构建日志）会把同一段历史反复灌进上下文。游标让每次结果只含新增内容。
 *
 * 单例是因为 [TerminalSessionTool] 由 ToolRegistry 注入且没有统一生命周期，
 * 状态放在工具对象里会在重建后丢失。
 */
@Singleton
class TerminalOutputCursor @Inject constructor() {

    private val seen = HashMap<String, String>()

    /**
     * 返回 [current] 相对上次已返回内容的新增部分，并把游标推进到 [current]。
     * 首次读到该标签时返回全量——此前没有向 AI 返回过任何内容。
     */
    @Synchronized
    fun advance(tabId: String, current: String): String {
        val previous = seen.put(tabId, current)
        return if (previous == null) current else outputDelta(previous, current)
    }

    /** 只推进游标、不作为「新增」返回（start 已把整段初始输出放进结果）。 */
    @Synchronized
    fun mark(tabId: String, current: String) {
        seen[tabId] = current
    }

    @Synchronized
    fun clear(tabId: String) {
        seen.remove(tabId)
    }

    /** 丢弃已不存在的标签，避免长期运行后按标签累积的文本一直占内存。 */
    @Synchronized
    fun retainOnly(tabIds: Set<String>) {
        seen.keys.retainAll(tabIds)
    }
}

/**
 * [current] 相对 [previous] 的新增后缀。
 *
 * 终端 transcript 是滚动窗口：新输出追加在后面，但超过缓冲行数后最旧的行会被丢弃，
 * 因此不能简单按长度截取，而是找 previous 的后缀与 current 前缀的最长重叠。
 * 无重叠（清屏、切换 alt-screen、内容被截断）时返回整段。
 */
internal fun outputDelta(previous: String, current: String): String {
    if (current.isEmpty() || current == previous) return ""
    if (current.startsWith(previous)) return current.substring(previous.length)

    // 先比首字符再逐字符比对，避免为每个候选长度分配子串（旧实现对长 transcript 是 O(n^2)）。
    val maxOverlap = minOf(previous.length, current.length)
    val first = current[0]
    for (length in maxOverlap downTo 1) {
        val start = previous.length - length
        if (previous[start] != first) continue
        if (regionMatches(previous, start, current, length)) {
            return current.substring(length)
        }
    }
    return current
}

private fun regionMatches(a: String, aOffset: Int, b: String, length: Int): Boolean {
    for (i in 1 until length) {
        if (a[aOffset + i] != b[i]) return false
    }
    return true
}
