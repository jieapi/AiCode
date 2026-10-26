package com.aicode.feature.agent.domain.skill

import com.aicode.feature.plugin.domain.PluginSkillSource
import com.aicode.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRepositoryTest {

    private val provider = TestFileAccessProvider()
    private val globalSource = spyk(GlobalDirectorySkillSource(provider)).apply {
        every { listSkills() } returns emptyList()
    }
    private val projectSource = spyk(ProjectDirectorySkillSource(provider)).apply {
        every { listSkills() } returns emptyList()
    }
    private val config = mockk<SkillConfigRepository>().apply {
        every { disabledNames() } returns emptySet()
    }
    private val pluginSource = mockk<PluginSkillSource>().apply {
        every { listSkills() } returns emptyList()
        every { loadInstructions(any()) } returns null
    }
    private val repository = SkillRepository(globalSource, projectSource, config, provider, pluginSource)

    private fun skill(name: String) = Skill(
        name = name,
        description = "desc of $name",
        instructions = "body of $name"
    )

    @Test
    fun loadInstructions_projectOverridesSameNameWithDifferentBody() {
        val global = skill("same").copy(instructions = "global instructions")
        val project = skill("same").copy(instructions = "project instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(project)

        val listed = repository.listSkills().single()
        assertEquals(project, listed)
        assertEquals("project instructions", repository.loadInstructions(listed.name))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_projectOverridesCaseInsensitiveName() {
        val global = skill("MixedCase").copy(instructions = "global instructions")
        val project = skill("mixedcase").copy(instructions = "project instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(project)

        assertEquals(listOf(project), repository.listSkills())
        assertEquals("project instructions", repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_fallsBackToGlobalWhenProjectHasNoMatch() {
        val global = skill("GlobalOnly").copy(instructions = "global instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(skill("other"))

        assertEquals("global instructions", repository.loadInstructions("GLOBALONLY"))
        verify(exactly = 1) { projectSource.loadInstructions("GLOBALONLY") }
        verify(exactly = 1) { globalSource.loadInstructions("GLOBALONLY") }
    }

    @Test
    fun loadInstructions_returnsNullWhenNeitherSourceHasMatch() {
        every { globalSource.listSkills() } returns listOf(skill("global"))
        every { projectSource.listSkills() } returns listOf(skill("project"))

        assertNull(repository.loadInstructions("missing"))
        verify(exactly = 1) { projectSource.loadInstructions("missing") }
        verify(exactly = 1) { globalSource.loadInstructions("missing") }
    }

    @Test
    fun loadInstructions_disabledNameBlocksBothSourcesCaseInsensitively() {
        every { globalSource.listSkills() } returns listOf(skill("MixedCase"))
        every { projectSource.listSkills() } returns listOf(skill("mixedcase"))
        every { config.disabledNames() } returns setOf("mixedcase")

        assertTrue(repository.listSkills().isEmpty())
        assertNull(repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { projectSource.loadInstructions(any()) }
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun mergeAll_combinesGlobalAndProject() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("a"), skill("b")),
            project = listOf(skill("c"))
        )

        assertEquals(listOf("a", "b", "c"), merged.map { it.skill.name })
        assertTrue(merged.all { it.scope == SkillScope.GLOBAL || it.scope == SkillScope.PROJECT })
        assertEquals(
            listOf(SkillScope.GLOBAL, SkillScope.GLOBAL, SkillScope.PROJECT),
            merged.map { it.scope }
        )
    }

    @Test
    fun mergeAll_projectOverridesSameName() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("same")),
            project = listOf(skill("same"))
        )

        assertEquals(1, merged.size)
        assertEquals("same", merged[0].skill.name)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_caseInsensitiveDedup() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("MixedCase")),
            project = listOf(skill("mixedcase"))
        )

        assertEquals(1, merged.size)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_sortedByName() {
        val merged = SkillRepository.mergeAll(
            global = listOf(skill("zeta"), skill("alpha")),
            project = listOf(skill("middle"))
        )

        assertEquals(listOf("alpha", "middle", "zeta"), merged.map { it.skill.name })
    }

    @Test
    fun filterDisabled_removesDisabledSkills() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("keep"), skill("drop")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("drop"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun filterDisabled_caseInsensitive() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("Keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("keep"))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun filterDisabled_unknownNamesIgnored() {
        val entries = SkillRepository.mergeAll(
            global = listOf(skill("keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("not-exists"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun safeDeleteSkillDir_deletesOnlySkillDirectories() {
        val temp = java.nio.file.Files.createTempDirectory("skills-test").toFile()
        try {
            val skillDir = File(temp, "my-skill")
            skillDir.mkdirs()
            File(skillDir, "SKILL.md").writeText("---\nname: my-skill\n---\n正文")
            File(skillDir, "run.py").writeText("print(1)")

            // 含 SKILL.md 的目录可删
            assertTrue(SkillRepository.safeDeleteSkillDir(provider, skillDir.absolutePath))
            assertTrue(!skillDir.exists())

            // 不含指令文件的目录拒绝删除
            val plainDir = File(temp, "not-a-skill")
            plainDir.mkdirs()
            File(plainDir, "readme.txt").writeText("x")
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, plainDir.absolutePath))
            assertTrue(plainDir.exists())

            // 不存在的目录返回 false
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, File(temp, "ghost").absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun listSkills_appendsPluginSkillsAfterUserSkillsSortedByName() {
        every { globalSource.listSkills() } returns listOf(skill("zeta"))
        every { pluginSource.listSkills() } returns listOf(skill("b.kit:two"), skill("a.kit:one"))

        assertEquals(listOf("zeta", "a.kit:one", "b.kit:two"), repository.listSkills().map { it.name })
    }

    @Test
    fun listSkills_userSkillWinsOverSameNamedPluginSkill() {
        val own = skill("Same").copy(instructions = "mine")
        every { globalSource.listSkills() } returns listOf(own)
        every { pluginSource.listSkills() } returns listOf(skill("same").copy(instructions = "plugin"))

        assertEquals(listOf(own), repository.listSkills())
    }

    @Test
    fun listSkills_disabledNamesAlsoHidePluginSkills() {
        every { pluginSource.listSkills() } returns listOf(skill("a.kit:one"), skill("a.kit:two"))
        every { config.disabledNames() } returns setOf("a.kit:one")

        assertEquals(listOf("a.kit:two"), repository.listSkills().map { it.name })
        assertNull(repository.loadInstructions("a.kit:one"))
    }

    @Test
    fun loadInstructions_fallsBackToPluginSource() {
        every { pluginSource.loadInstructions("a.kit:one") } returns "plugin body"

        assertEquals("plugin body", repository.loadInstructions("a.kit:one"))
    }

    @Test
    fun listAllSkills_neverContainsPluginSkills() {
        every { pluginSource.listSkills() } returns listOf(skill("a.kit:one"))

        assertTrue(repository.listAllSkills().isEmpty())
    }

    @Test
    fun isValidName_rejectsPathTraversalAndSeparators() {
        assertTrue(SkillRepository.isValidName("pdf-report"))
        assertTrue(SkillRepository.isValidName("中文技能名"))
        assertTrue(!SkillRepository.isValidName(""))
        assertTrue(!SkillRepository.isValidName("   "))
        assertTrue(!SkillRepository.isValidName("."))
        assertTrue(!SkillRepository.isValidName(".."))
        assertTrue(!SkillRepository.isValidName("a/b"))
        assertTrue(!SkillRepository.isValidName("a\\b"))
        assertTrue(!SkillRepository.isValidName("a:b"))
        assertTrue(!SkillRepository.isValidName("a\u0000b"))
        assertTrue(!SkillRepository.isValidName("x".repeat(65)))
    }

    @Test
    fun instructionFile_prefersSkillMdThenClaudeMd() {
        val temp = java.nio.file.Files.createTempDirectory("skills-instruction-test").toFile()
        try {
            val both = File(temp, "both").apply { mkdirs() }
            File(both, "CLAUDE.md").writeText("x")
            File(both, "skill.md").writeText("x")
            assertEquals("skill.md", SkillRepository.instructionFile(provider, both.absolutePath)?.substringAfterLast('/'))

            val claudeOnly = File(temp, "claude-only").apply { mkdirs() }
            File(claudeOnly, "CLAUDE.md").writeText("x")
            assertEquals("CLAUDE.md", SkillRepository.instructionFile(provider, claudeOnly.absolutePath)?.substringAfterLast('/'))

            val empty = File(temp, "empty").apply { mkdirs() }
            assertEquals(null, SkillRepository.instructionFile(provider, empty.absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }
}
