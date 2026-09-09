package com.aicode.feature.agent.domain.command

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令注册表：菜单排序（all）、输入框实时过滤（filterByPrefix）、
 * 发送时完全匹配（findExact）。
 */
class SlashCommandRegistryTest {

    private fun mockHandler(trigger: String): SlashCommandHandler {
        val handler = mockk<SlashCommandHandler>()
        every { handler.trigger } returns trigger
        every { handler.matches(any()) } returns false
        return handler
    }

    // 乱序构造，验证 all 内部按 trigger 排序，而非依赖注入顺序
    private val status = mockHandler("/status")
    private val compress = mockHandler("/compress")
    private val help = mockHandler("/help")

    private fun registry(vararg handlers: SlashCommandHandler) =
        SlashCommandRegistry(handlers.toSet())

    // ---- all ----

    @Test
    fun all_sortedByTrigger_regardlessOfSetOrder() {
        val registry = registry(help, status, compress)

        assertEquals(
            listOf("/compress", "/help", "/status"),
            registry.all.map { it.trigger }
        )
    }

    @Test
    fun all_singleHandler_returnsIt() {
        val registry = registry(compress)

        assertEquals(listOf(compress), registry.all)
    }

    // ---- filterByPrefix ----

    @Test
    fun filterByPrefix_slash_returnsAll() {
        val registry = registry(help, status, compress)

        assertEquals(registry.all, registry.filterByPrefix("/"))
    }

    @Test
    fun filterByPrefix_partialPrefix_filtersByTrigger() {
        val registry = registry(help, status, compress)

        assertEquals(listOf(compress), registry.filterByPrefix("/c"))
        assertEquals(listOf(status), registry.filterByPrefix("/st"))
        assertEquals(listOf(help), registry.filterByPrefix("/he"))
    }

    @Test
    fun filterByPrefix_noMatch_returnsEmpty() {
        val registry = registry(help, status, compress)

        assertTrue(registry.filterByPrefix("/xyz").isEmpty())
    }

    @Test
    fun filterByPrefix_emptyInput_returnsAll() {
        // 空串是任意 trigger 的前缀（startsWith("") 恒真），实现返回全部；
        // 调用方保证 input 以 '/' 开头，空串属未定义输入，这里锁定实现现状。
        val registry = registry(help, status, compress)

        assertEquals(registry.all, registry.filterByPrefix(""))
    }

    // ---- findExact ----

    @Test
    fun findExact_matches_returnsHandler() {
        every { compress.matches(any()) } returns true
        val registry = registry(help, status, compress)

        assertSame(compress, registry.findExact("/compress"))
    }

    @Test
    fun findExact_noMatch_returnsNull() {
        // mockHandler 里 matches 默认 stub 为 false，模拟全部未命中
        val registry = registry(help, status, compress)

        assertNull(registry.findExact("/compress"))
    }

    @Test
    fun findExact_multipleMatch_returnsFirstByTriggerOrder() {
        // 两个 handler 都声称命中时，应取按 trigger 排序靠前的（/compress < /status）
        every { compress.matches(any()) } returns true
        every { status.matches(any()) } returns true
        val registry = registry(status, compress)

        assertSame(compress, registry.findExact("whatever"))
    }
}