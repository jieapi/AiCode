package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingSignatureErrorTest {

    private val sourceIds = List(30) { "m$it" }

    @Test
    fun parses_message_position_and_maps_to_source_id() {
        val body = """{"error":{"type":"invalid_request_error","message":"messages.19.content.0: Invalid `signature` in `thinking` block (request_id: chatcmpl-x)"}}"""

        val error = thinkingSignatureError(body, sourceIds)

        assertNotNull(error)
        assertEquals("m19", error!!.fromMessageId)
    }

    @Test
    fun falls_back_to_strip_all_when_upstream_masks_position() {
        val body = """{"error":{"message":"***.***.content.0: Invalid `signature` in `thinking` block"}}"""

        val error = thinkingSignatureError(body, sourceIds)

        assertNotNull(error)
        assertNull(error!!.fromMessageId)
    }

    @Test
    fun ignores_other_400_bodies() {
        val body = """{"error":{"message":"thinking.type.disabled is not supported for this model"}}"""

        assertNull(thinkingSignatureError(body, sourceIds))
    }

    @Test
    fun falls_back_when_index_out_of_range() {
        val body = """messages.999.content.0: Invalid `signature` in `thinking` block"""

        val error = thinkingSignatureError(body, sourceIds)

        assertNotNull(error)
        assertNull(error!!.fromMessageId)
    }
}