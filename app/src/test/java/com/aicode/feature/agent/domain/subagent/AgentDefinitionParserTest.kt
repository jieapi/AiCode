package com.aicode.feature.agent.domain.subagent

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDefinitionParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    private fun parseFile(file: File): AgentDefinition? = AgentDefinitionParser.parse(provider, file.absolutePath)

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_fullFrontmatter() {
        val file = write(
            "researcher.md",
            """
            ---
            name: researcher
            description: 只读调研
            provider: DeepSeek
            model: deepseek-reasoner
            reasoningEffort: high
            tools: [readFile, search]
            disallowedTools: [Bash]
            inject: [base, projectRules]
            ---
            你是调研专家。
            """.trimIndent()
        )

        val def = parseFile(file)!!

        assertEquals("researcher", def.name)
        assertEquals("只读调研", def.description)
        assertEquals("DeepSeek", def.providerId)
        assertEquals("deepseek-reasoner", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(listOf("Bash"), def.disallowedTools)
        assertEquals(setOf(InjectPart.BASE, InjectPart.PROJECT_RULES), def.inject)
        assertEquals("你是调研专家。", def.prompt)
    }

    @Test
    fun parse_nameFallsBackToFileName() {
        val def = parseFile(write("coder.md", "---\ndescription: 写代码\n---\n正文"))!!

        assertEquals("coder", def.name)
    }

    @Test
    fun parse_defaultsWhenInjectOmitted() {
        val def = parseFile(write("a.md", "---\nname: a\n---\n正文"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
        assertTrue(def.allowedTools.isEmpty())
        assertTrue(def.disallowedTools.isEmpty())
        assertNull(def.providerId)
        assertNull(def.model)
        assertNull(def.reasoningEffort)
    }

    @Test
    fun parse_injectNoneMeansNoInjection() {
        val def = parseFile(write("a.md", "---\nname: a\ninject: [none]\n---\n正文"))!!

        assertTrue(def.inject.isEmpty())
    }

    /** 全部取值非法时回退默认，避免写错一个词就丢掉全部上下文。 */
    @Test
    fun parse_invalidInjectFallsBackToDefault() {
        val def = parseFile(write("a.md", "---\nname: a\ninject: [bogus]\n---\n正文"))!!

        assertEquals(AgentDefinition.DEFAULT_INJECT, def.inject)
    }

    @Test
    fun parse_toolsAcceptsCommaSeparatedString() {
        val def = parseFile(
            write("a.md", "---\nname: a\ntools: readFile, search\n---\n正文")
        )!!

        assertEquals(listOf("readFile", "search"), def.allowedTools)
    }

    @Test
    fun parse_invalidReasoningEffortIgnored() {
        val def = parseFile(
            write("a.md", "---\nname: a\nreasoningEffort: turbo\n---\n正文")
        )!!

        assertNull(def.reasoningEffort)
    }

    @Test
    fun parse_blankPromptReturnsNull() {
        assertNull(parseFile(write("a.md", "---\nname: a\n---\n   \n")))
    }

    @Test
    fun parse_noFrontmatterTreatsWholeFileAsPrompt() {
        val def = parseFile(write("plain.md", "只有正文"))!!

        assertEquals("plain", def.name)
        assertEquals("只有正文", def.prompt)
    }

    @Test
    fun scan_onlyTopLevelMarkdownSortedByName() {
        write("b.md", "---\nname: b\n---\n正文")
        write("a.md", "---\nname: a\n---\n正文")
        write("notes.txt", "不是定义")
        File(tempFolder.root, "nested").mkdirs()
        File(File(tempFolder.root, "nested"), "c.md").writeText("---\nname: c\n---\n正文")

        val defs = AgentDefinitionDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(listOf("a", "b"), defs.map { it.name })
    }

    @Test
    fun scan_missingRootReturnsEmpty() {
        assertTrue(AgentDefinitionDirectoryScanner.scan(provider, File(tempFolder.root, "not-exists").absolutePath).isEmpty())
    }

    /**
     * 设置页存盘走 serialize，再读回来必须与存进去的完全一致；
     * 描述里的冒号、井号与引号是 YAML 重灾区，专门放进测数据。
     */
    @Test
    fun serialize_roundTripsThroughParse() {
        val text = AgentDefinitionParser.serialize(
            name = "researcher",
            description = "只读调研：先 search 定位，再 readFile 核实 #1 优先，别改文件",
            providerId = "deepseek",
            model = "deepseek-reasoner",
            reasoningEffort = "high",
            allowedTools = listOf("readFile", "search"),
            disallowedTools = listOf("Bash"),
            inject = setOf(InjectPart.BASE, InjectPart.PROJECT_RULES),
            prompt = "你是调研专家。\n\n结论要带 文件:行号 引用。"
        )

        val def = parseFile(write("researcher.md", text))!!

        assertEquals("researcher", def.name)
        assertEquals("只读调研：先 search 定位，再 readFile 核实 #1 优先，别改文件", def.description)
        assertEquals("deepseek", def.providerId)
        assertEquals("deepseek-reasoner", def.model)
        assertEquals("high", def.reasoningEffort)
        assertEquals(listOf("readFile", "search"), def.allowedTools)
        assertEquals(listOf("Bash"), def.disallowedTools)
        assertEquals(setOf(InjectPart.BASE, InjectPart.PROJECT_RULES), def.inject)
        assertEquals("你是调研专家。\n\n结论要带 文件:行号 引用。", def.prompt)
    }

    /** 空注入集必须写成 [none]：省略会被解成默认注入项，恰好与用户的选择相反。 */
    @Test
    fun serialize_emptyInjectStaysEmptyAfterParse() {
        val text = AgentDefinitionParser.serialize(
            name = "bare",
            description = "",
            providerId = null,
            model = null,
            reasoningEffort = null,
            allowedTools = emptyList(),
            disallowedTools = emptyList(),
            inject = emptySet(),
            prompt = "只用自己的提示词。"
        )

        val def = parseFile(write("bare.md", text))!!

        assertTrue(def.inject.isEmpty())
        assertNull(def.providerId)
        assertNull(def.model)
        assertNull(def.reasoningEffort)
        assertTrue(def.allowedTools.isEmpty())
    }

    @Test
    fun isValidName_rejectsPathSeparatorsAndEmpty() {
        assertTrue(AgentDefinitionRepository.isValidName("researcher"))
        assertTrue(AgentDefinitionRepository.isValidName("代码审查"))
        assertFalse(AgentDefinitionRepository.isValidName(""))
        assertFalse(AgentDefinitionRepository.isValidName("  "))
        assertFalse(AgentDefinitionRepository.isValidName(".."))
        assertFalse(AgentDefinitionRepository.isValidName("a/b"))
        assertFalse(AgentDefinitionRepository.isValidName("a:b"))
        assertFalse(AgentDefinitionRepository.isValidName("a".repeat(41)))
    }
}
