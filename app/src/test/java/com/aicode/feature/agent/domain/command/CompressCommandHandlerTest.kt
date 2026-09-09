package com.aicode.feature.agent.domain.command

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /compress 命令：菜单元数据、默认 matches 实现与执行行为。
 */
class CompressCommandHandlerTest {

    private val handler = CompressCommandHandler()

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("/compress", handler.trigger)
        assertEquals("压缩上下文", handler.label)
        assertEquals("手动触发当前会话的上下文压缩", handler.description)
    }

    @Test
    fun matches_exactTriggerOnly() {
        assertTrue(handler.matches("/compress"))
        assertTrue("首尾空白会被 trim", handler.matches("  /compress  "))
        assertFalse(handler.matches("/compressx"))
        assertFalse(handler.matches("/status"))
    }

    @Test
    fun execute_compactsCurrentSession() {
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context)

        verify { context.compactCurrentSession() }
    }
}