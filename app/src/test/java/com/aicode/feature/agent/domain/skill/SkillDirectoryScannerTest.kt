package com.aicode.feature.agent.domain.skill

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDirectoryScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    @Test
    fun scan_parsesSkillWithFrontmatter() {
        val skillDir = tempFolder.newFolder("my-skill")
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: my-skill
            description: 我的技能
            ---
            技能正文
            """.trimIndent()
        )

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("my-skill", skills[0].name)
        assertEquals("我的技能", skills[0].description)
        assertTrue(skills[0].instructions.contains("技能正文"))
    }

    @Test
    fun scan_fallsBackToClaudeMd() {
        val skillDir = tempFolder.newFolder("legacy-skill")
        File(skillDir, "CLAUDE.md").writeText("---\nname: legacy-skill\n---\n正文")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("legacy-skill", skills[0].name)
    }

    @Test
    fun scan_skipsInvalidDirectories() {
        val emptyDir = tempFolder.newFolder("no-instructions")
        File(emptyDir, "readme.txt").writeText("不是技能")

        assertTrue(SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath).isEmpty())
    }

    @Test
    fun scan_nestedDirectories() {
        val nested = tempFolder.newFolder("repo", "skills", "nested-skill")
        File(nested, "SKILL.md").writeText("---\nname: nested-skill\n---\n正文")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
        assertEquals("nested-skill", skills[0].name)
    }

    @Test
    fun scan_sortedByName() {
        tempFolder.newFolder("b-skill").let { File(it, "SKILL.md").writeText("---\nname: b-skill\n---\n") }
        tempFolder.newFolder("a-skill").let { File(it, "SKILL.md").writeText("---\nname: a-skill\n---\n") }

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(listOf("a-skill", "b-skill"), skills.map { it.name })
    }

    @Test
    fun scan_missingRootReturnsEmpty() {
        assertTrue(SkillDirectoryScanner.scan(provider, File(tempFolder.root, "not-exists").absolutePath).isEmpty())
    }

    @Test
    fun scan_sameDirectoryWithBothFilesParsedOnce() {
        val skillDir = tempFolder.newFolder("dual-skill")
        File(skillDir, "SKILL.md").writeText("---\nname: dual-skill\n---\nSKILL 正文")
        File(skillDir, "CLAUDE.md").writeText("---\nname: dual-skill\n---\nCLAUDE 正文")

        val skills = SkillDirectoryScanner.scan(provider, tempFolder.root.absolutePath)

        assertEquals(1, skills.size)
    }
}
