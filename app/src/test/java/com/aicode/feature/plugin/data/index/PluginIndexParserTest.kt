package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexParserTest {

    private fun index(vararg plugins: String): ByteArray =
        """{"schema_version":1,"plugins":[${plugins.joinToString(",")}]}""".toByteArray()

    private fun plugin(version: String? = null, tags: List<String>? = null): String = buildString {
        append("{")
        append("\"id\":\"author-name.sample-plugin\",")
        append("\"name\":\"Sample Plugin\",")
        append("\"description\":\"Sample\",")
        append("\"author\":\"author-name\",")
        append("\"repository\":\"https://github.com/author-name/sample-plugin\"")
        version?.let { append(",\"version\":\"$it\"") }
        tags?.let { append(",\"tags\":[${it.joinToString(",") { tag -> "\"$tag\"" }}]") }
        append("}")
    }

    @Test
    fun parsesMinimalPluginWithoutOptionalFields() {
        val result = PluginIndexParser.parse(index(plugin()))
        assertEquals(1, result.plugins.size)
        assertNull(result.plugins[0].version)
        assertTrue(result.plugins[0].tags.isEmpty())
    }

    @Test
    fun rejectsVersionStartingWithV() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(version = "v1.0.0")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsUnknownTag() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(tags = listOf("unknown"))))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsDuplicatePluginIds() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(), plugin()))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun officialPluginIsDeterminedByRepositoryOwner() {
        assertTrue(PluginOfficialRepositories.isOfficial("https://github.com/jieapi/aicode"))
        assertTrue(PluginOfficialRepositories.isOfficial("https://github.com/jieapi/aicode-plugin"))
        assertFalse(PluginOfficialRepositories.isOfficial("https://github.com/other/aicode-plugin"))
    }
}
