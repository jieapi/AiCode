package com.aicode.feature.agent.domain.command

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /status 命令：菜单元数据、默认 matches 实现与执行行为。
 */
class StatusCommandHandlerTest {

    private val handler = StatusCommandHandler()

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("/status", handler.trigger)
        assertEquals("会话状态", handler.label)
        assertEquals("查看当前会话的 token 用量、模型、模式等信息", handler.description)
    }

    @Test
    fun matches_exactTriggerOnly() {
        assertTrue(handler.matches("/status"))
        assertTrue("首尾空白会被 trim", handler.matches("  /status  "))
        assertFalse(handler.matches("/statusx"))
        assertFalse(handler.matches("/compress"))
    }

    @Test
    fun execute_showsSessionStatus() {
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context)

        verify { context.showSessionStatus() }
    }
}