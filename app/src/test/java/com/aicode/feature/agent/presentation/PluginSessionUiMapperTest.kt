package com.aicode.feature.agent.presentation

import com.aicode.feature.plugin.domain.session.PluginMessageBlock
import com.aicode.feature.plugin.domain.session.PluginSessionMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginSessionUiMapperTest {
    @Test
    fun toolIdentityIsStableAcrossHistoryAndLiveEvents() {
        val message = PluginSessionMessage(
            id = "m42", role = "tool", createdAt = 1L, status = "done",
            blocks = listOf(PluginMessageBlock.Tool("call-1", "Bash", "success", "pwd", "/project"))
        )
        val mapped = PluginSessionUiMapper.toUiMessages(message).single()
        assertEquals("remote-tool-call-1", mapped.id)
        assertEquals("/project", mapped.content)
        assertEquals(MessageRole.TOOL, mapped.role)
    }
}
