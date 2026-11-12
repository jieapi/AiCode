package com.aicode.feature.plugin.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHookTransformTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun beforeWithLogsOnly_isNoop() {
        val result = PluginHookTransform.parseBefore(json("""{"logs":["ok"]}"""))
        assertEquals(ToolBeforeHookResult(), result)
    }

    @Test
    fun beforeDeny_keepsReasonAndArguments() {
        val result = PluginHookTransform.parseBefore(
            json("""{"decision":"deny","reason":"  blocked  ","arguments":{"path":"a.txt"}}""")
        )
        assertTrue(result!!.denied)
        assertEquals("blocked", result.denyReason)
        assertEquals(json("""{"path":"a.txt"}"""), result.arguments)
    }

    @Test
    fun beforeRewriteWithoutDeny_isApplied() {
        val result = PluginHookTransform.parseBefore(json("""{"arguments":{"command":"pwd"}}"""))
        assertFalse(result!!.denied)
        assertEquals(json("""{"command":"pwd"}"""), result.arguments)
    }

    @Test
    fun beforeRejectsMalformedFields() {
        assertNull(PluginHookTransform.parseBefore(buildJsonObject { put("decision", JsonPrimitive(1)) }))
        assertNull(PluginHookTransform.parseBefore(json("""{"decision":"write"}""")))
        assertNull(PluginHookTransform.parseBefore(json("""{"arguments":"nope"}""")))
        assertNull(PluginHookTransform.parseBefore(json("""{"reason":1}""")))
        assertNull(PluginHookTransform.parseBefore(JsonPrimitive("nope")))
    }

    @Test
    fun afterMissingData_isNoop() {
        assertEquals(ToolAfterHookResult(), PluginHookTransform.parseAfter(json("""{"logs":["ok"]}""")))
    }

    @Test
    fun afterData_isReturnedAsIs() {
        val result = PluginHookTransform.parseAfter(json("""{"data":{"text":"redacted"}}"""))
        assertEquals(json("""{"text":"redacted"}"""), result!!.data)

        val nullData = PluginHookTransform.parseAfter(json("""{"data":null}"""))
        assertSame(JsonNull, nullData!!.data)
    }

    @Test
    fun oversizedJson_isRejected() {
        val huge = "x".repeat(PluginHookTransform.MAX_JSON_CHARS)
        assertNull(PluginHookTransform.parseBefore(JsonObject(mapOf("arguments" to JsonObject(mapOf("text" to JsonPrimitive(huge)))))))
        assertNull(PluginHookTransform.parseAfter(JsonObject(mapOf("data" to JsonPrimitive(huge)))))
    }
}
