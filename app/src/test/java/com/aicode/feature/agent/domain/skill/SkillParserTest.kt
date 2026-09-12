package com.aicode.feature.agent.domain.skill

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private fun parseSerialized(
        name: String,
        description: String,
        requiredTools: List<String> = emptyList(),
        instructions: String
    ): Skill {
        val dir = java.nio.file.Files.createTempDirectory("skill-parser-test").toFile()
        return try {
            File(dir, "SKILL.md").writeText(
                SkillParser.serialize(
                    name = name,
                    description = description,
                    requiredTools = requiredTools,
                    instructions = instructions
                )
            )
            val parsed = SkillParser.parse(TestFileAccessProvider(), dir.absolutePath)
            assertNotNull(parsed)
            parsed!!
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serialize_roundTripsThroughParse() {
        val skill = parseSerialized(
            name = "pdf-report",
            description = "生成 PDF 报告时使用",
            requiredTools = listOf("Bash", "writeFile"),
            instructions = "## 步骤\n\n1. 读模板\n2. 渲染"
        )

        assertEquals("pdf-report", skill.name)
        assertEquals("生成 PDF 报告时使用", skill.description)
        assertEquals(listOf("Bash", "writeFile"), skill.requiredTools)
        assertEquals("## 步骤\n\n1. 读模板\n2. 渲染", skill.instructions)
    }

    @Test
    fun serialize_keepsYamlValidWithSpecialChars() {
        val skill = parseSerialized(
            name = "tricky",
            description = "用于: 处理 \"引号\" 与 # 井号 的场景",
            instructions = "正文"
        )

        assertEquals("用于: 处理 \"引号\" 与 # 井号 的场景", skill.description)
        assertEquals("正文", skill.instructions)
    }

    @Test
    fun serialize_flattensMultilineDescription() {
        val skill = parseSerialized(
            name = "multi",
            description = "第一行\n第二行",
            instructions = "正文"
        )

        assertEquals("第一行 第二行", skill.description)
    }

    @Test
    fun serialize_omitsRequiredToolsWhenEmpty() {
        val text = SkillParser.serialize(
            name = "plain",
            description = "d",
            requiredTools = emptyList(),
            instructions = "正文"
        )

        assertTrue(!text.contains("required_tools"))
    }
}
