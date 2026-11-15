package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIndexParserTest {

    private fun index(vararg plugins: String): ByteArray =
        """{"schema_version":1,"plugins":[${plugins.joinToString(",")}]}""".toByteArray()

    private fun plugin(
        version: String? = null,
        tags: List<String>? = null,
        name: String = "\"Sample Plugin\"",
        description: String = "\"Sample\""
    ): String = buildString {
        append("{")
        append("\"id\":\"author-name.sample-plugin\",")
        append("\"name\":$name,")
        append("\"description\":$description,")
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
        assertEquals("Sample Plugin", result.plugins[0].name.resolve("en"))
        assertEquals("Sample", result.plugins[0].description.resolve("zh-CN"))
        assertNull(result.plugins[0].version)
        assertTrue(result.plugins[0].tags.isEmpty())
    }

    @Test
    fun parsesLocalizedTextAndResolvesByLanguageTag() {
        val result = PluginIndexParser.parse(
            index(
                plugin(
                    name = """{"default":"Sample Plugin","zh-CN":"示例插件"}""",
                    description = """{"default":"Sample","zh_CN":"示例"}"""
                )
            )
        )
        val plugin = result.plugins[0]
        assertEquals("示例插件", plugin.name.resolve("zh-CN"))
        assertEquals("Sample Plugin", plugin.name.resolve("en"))
        assertEquals("示例", plugin.description.resolve("zh-CN"))
    }

    @Test
    fun rejectsInvalidLanguageTag() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":"示例","bad tag":"x"}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsDuplicateNormalizedLanguageTags() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":"甲","zh_cn":"乙"}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsBlankLocalizedText() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"default":"   "}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsNonStringLocalizedValue() {
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = """{"zh-CN":1}""")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsOverlongLocalizedName() {
        val longName = "a".repeat(121)
        val error = runCatching {
            PluginIndexParser.parse(index(plugin(name = "\"$longName\"")))
        }.exceptionOrNull()
        assertTrue(error is PluginIndexException)
    }

    @Test
    fun rejectsNewlineInNameButAllowsItInDescription() {
        val nameError = runCatching {
            PluginIndexParser.parse(index(plugin(name = "\"Line\\nBreak\"")))
        }.exceptionOrNull()
        assertTrue(nameError is PluginIndexException)

        val result = PluginIndexParser.parse(index(plugin(description = "\"Line\\nBreak\"")))
        assertEquals("Line\nBreak", result.plugins[0].description.resolve("en"))
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
