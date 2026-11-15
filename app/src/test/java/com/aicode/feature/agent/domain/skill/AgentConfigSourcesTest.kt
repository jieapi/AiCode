package com.aicode.feature.agent.domain.skill

import com.aicode.feature.agent.domain.subagent.GlobalDirectoryAgentSource
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.AgentConfigSnapshot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentConfigSourcesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun skillIndexIsSharedAndLoadingReadsOnlySelectedFile() {
        val directory = temp.newFolder("selected")
        val file = File(directory, "SKILL.md").apply { writeText("---\nname: selected\n---\nnew body") }
        val files = (0 until 300).associate { index ->
            "${temp.root}/skill-$index/SKILL.md" to "---\nname: skill-$index\n---\nbody $index"
        } + (file.path to "---\nname: selected\n---\nold body")
        val reads = mutableListOf<String>()
        val snapshot = AgentConfigSnapshot(temp.root.path, "revision-1", files)
        val provider = provider(snapshot, reads)
        val source = GlobalDirectorySkillSource(provider)

        val first = source.listSkills()
        assertEquals(301, first.size)
        assertSame(first, source.listSkills())
        assertEquals(emptyList<String>(), reads)
        assertEquals("new body", source.loadInstructions("SELECTED"))
        assertEquals(listOf(file.path), reads)
    }

    @Test
    fun agentLookupUsesIndexAndReadsOnlySelectedDefinition() {
        val file = temp.newFile("selected.md").apply { writeText("---\nname: selected\n---\nnew prompt") }
        val files = (0 until 300).associate { index ->
            "${temp.root}/agent-$index.md" to "---\nname: agent-$index\n---\nprompt $index"
        } + (file.path to "---\nname: selected\n---\nold prompt")
        val reads = mutableListOf<String>()
        val source = GlobalDirectoryAgentSource(provider(AgentConfigSnapshot(temp.root.path, "r1", files), reads))

        assertEquals(301, source.listDefinitions().size)
        assertEquals("new prompt", source.find("SELECTED")?.prompt)
        assertEquals(listOf(file.path), reads)
    }

    @Test
    fun firstRemoteSnapshotFailureDoesNotBecomeEmptySkillList() {
        val provider = object : FileAccessProvider by TestFileAccessProvider() {
            override fun agentConfigSnapshot(path: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot =
                throw IOException("offline")
        }
        assertThrows(IOException::class.java) { SkillDirectoryScanner.scan(provider, "~/.aicode/skills") }
    }

    @Test
    fun changingSnapshotRevisionReparsesTheIndex() {
        val path = "${temp.root}/one/SKILL.md"
        val before = AgentConfigSnapshot(temp.root.path, "before", mapOf(path to "---\nname: before\n---\nbody"))
        val after = before.copy(revision = "after", files = mapOf(path to "---\nname: after\n---\nbody"))
        assertEquals("before", SkillDirectoryScanner.fromSnapshot(before).single().name)
        assertEquals("after", SkillDirectoryScanner.fromSnapshot(after).single().name)
    }

    private fun provider(snapshot: AgentConfigSnapshot, reads: MutableList<String>): FileAccessProvider =
        object : FileAccessProvider by TestFileAccessProvider() {
            override fun agentConfigSnapshot(path: String, kind: AgentConfigKind, forceRefresh: Boolean) = snapshot
            override fun cachedAgentConfigSnapshot(path: String, kind: AgentConfigKind) = snapshot
            override fun readFile(path: String): String {
                reads += path
                return File(path).readText()
            }
            override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
                throw AssertionError("unexpected per-directory traversal")
        }
}
