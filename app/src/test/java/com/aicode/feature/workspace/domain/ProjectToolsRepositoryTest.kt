package com.aicode.feature.workspace.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectToolsRepositoryTest {

    @Test
    fun `saved params restore primitive and list types`() {
        val saved = mapOf(
            "text" to JsonPrimitive("hello"),
            "flag" to JsonPrimitive(true),
            "count" to JsonPrimitive(3),
            "list" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        )

        val ui = saved.toUiValues()

        assertEquals("hello", ui["text"])
        assertEquals(true, ui["flag"])
        assertEquals(3L, ui["count"])
        assertEquals(listOf("a", "b"), ui["list"])
    }

    @Test
    fun `json null becomes empty string`() {
        val ui = mapOf("missing" to JsonNull).toUiValues()

        assertEquals("", ui["missing"])
    }
}
