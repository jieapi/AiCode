package com.aicode.feature.workspace.domain

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteAgentConfigProtocolTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun encoded(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun manifest(vararg paths: Pair<String, String>): ByteArray = buildString {
        append("AICODE_CONFIG_1 M\n")
        paths.forEach { (path, body) ->
            append("F\t${encoded(path)}\t${RemoteAgentConfigProtocol.sha256(body.toByteArray(Charsets.UTF_8))}\t${body.toByteArray(Charsets.UTF_8).size}\n")
        }
        append("END\n")
    }.toByteArray(Charsets.UTF_8)

    @Test
    fun manifestSelectsOneInstructionPerDirectoryAndUsesDeterministicCaseTieBreak() {
        val result = RemoteAgentConfigProtocol.manifest(
            manifest("space 中文\nend/CLAUDE.md" to "fallback", "space 中文\nend/skill.MD" to "preferred", "other/ClAuDe.Md" to "legacy", "other/CLAUDE.md" to "first"),
            AgentConfigKind.SKILLS
        )
        assertEquals(setOf("space 中文\nend/skill.MD", "other/CLAUDE.md"), result.keys)
    }

    @Test
    fun emptyManifestIsACompleteSnapshotButEmptyOutputIsAnError() {
        assertTrue(RemoteAgentConfigProtocol.manifest(manifest(), AgentConfigKind.AGENTS).isEmpty())
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(ByteArray(0), AgentConfigKind.AGENTS) }
        assertThrows(IOException::class.java) {
            RemoteAgentConfigProtocol.manifest("AICODE_CONFIG_1 M\n".toByteArray(), AgentConfigKind.AGENTS)
        }
    }

    @Test
    fun malformedPathsHashSizesAndFramingAreRejected() {
        val hash = RemoteAgentConfigProtocol.sha256(ByteArray(0))
        listOf("../x.md", "/x.md", "a//SKILL.md", "a/./SKILL.md", "a/../SKILL.md", "a\u0000/SKILL.md").forEach { path ->
            assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(manifest(path to ""), AgentConfigKind.SKILLS) }
        }
        listOf(
            "AICODE_CONFIG_1 M\nF\t!!!!\t$hash\t0\nEND\n",
            "AICODE_CONFIG_1 M\nF\t${encoded("x.md")}\tbad\t0\nEND\n",
            "AICODE_CONFIG_1 M\nF\t${encoded("x.md")}\t$hash\t-1\nEND\n",
            "AICODE_CONFIG_1 M\nF\t${encoded("x.md")}\t$hash\t01\nEND\n",
            "AICODE_CONFIG_1 M\nF\t${encoded("x.md")}\t$hash\t${RemoteAgentConfigProtocol.MAX_FILE_BYTES + 1}\nEND\n",
            "AICODE_CONFIG_1 M\nEND\ntrailing\n",
            "AICODE_CONFIG_1 M\nEND",
            "AICODE_CONFIG_1 M\nEND\nEND\n"
        ).forEach { wire ->
            assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(wire.toByteArray(), AgentConfigKind.AGENTS) }
        }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(manifest("x.md" to "", "x.md" to ""), AgentConfigKind.AGENTS) }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(manifest("nested/x.md" to ""), AgentConfigKind.AGENTS) }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(manifest("a/b/c/d/SKILL.md" to ""), AgentConfigKind.SKILLS) }
    }

    @Test
    fun bodyHashSizeCompletenessAndCanonicalBase64AreVerified() {
        val expected = RemoteAgentConfigProtocol.manifest(manifest("空 格\n.md" to "正文\n", "empty.md" to ""), AgentConfigKind.AGENTS)
        val good = "AICODE_CONFIG_1 B\nB\t${encoded("空 格\n.md")}\t${encoded("正文\n")}\nB\t${encoded("empty.md")}\t\nEND\n"
        assertEquals(mapOf("空 格\n.md" to "正文\n", "empty.md" to ""), RemoteAgentConfigProtocol.bodies(good.toByteArray(), expected))
        listOf(
            good.replace(encoded("正文\n"), encoded("改变\n")),
            good.replace("B\t${encoded("empty.md")}\t\n", ""),
            good.replace(encoded("empty.md"), encoded("other.md")),
            good.replace("\nEND\n", "\nB\t${encoded("empty.md")}\t\nEND\n"),
            good.replace(encoded("正文\n"), "AA"),
            good.replace(encoded("正文\n"), "!!!!")
        ).forEach { wire -> assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.bodies(wire.toByteArray(), expected) } }
    }

    @Test
    fun fileCountAndInvalidUtf8HaveHardLimits() {
        val wire = manifest(*(0..RemoteAgentConfigProtocol.MAX_FILES).map { "$it.md" to "" }.toTypedArray())
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(wire, AgentConfigKind.AGENTS) }
        val oversizedTotal = buildString {
            append("AICODE_CONFIG_1 M\n")
            repeat(5) { append("F\t${encoded("$it.md")}\t${"0".repeat(64)}\t${RemoteAgentConfigProtocol.MAX_FILE_BYTES}\n") }
            append("END\n")
        }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(oversizedTotal.toByteArray(), AgentConfigKind.AGENTS) }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.strictText(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.readLimited("1234".byteInputStream(), 3) }
    }

    @Test
    fun shellRoundTripPreservesQuotesChineseWhitespaceAndNewlinesAndHonorsDepth() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = File(temporary.root, "root ' 中文\n ").apply { mkdirs() }
        val relative = "group ' 中文\n /SKILL.md"
        File(root, relative).apply { parentFile!!.mkdirs(); writeText("正文\n\n") }
        File(root, "group ' 中文\n /claude.MD").writeText("fallback")
        File(root, "a/b/c/SKILL.md").apply { parentFile!!.mkdirs(); writeText("depth4") }
        File(root, "a/b/c/d/SKILL.md").apply { parentFile!!.mkdirs(); writeText("depth5") }
        val listed = runShell(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.SKILLS))
        assertEquals(0, listed.first)
        val fingerprints = RemoteAgentConfigProtocol.manifest(listed.second, AgentConfigKind.SKILLS)
        assertEquals(setOf(relative, "a/b/c/SKILL.md"), fingerprints.keys)
        val read = runShell(RemoteAgentConfigProtocol.bodiesCommand(root.path), RemoteAgentConfigProtocol.request(fingerprints.keys))
        assertEquals(0, read.first)
        assertEquals(mapOf(relative to "正文\n\n", "a/b/c/SKILL.md" to "depth4"), RemoteAgentConfigProtocol.bodies(read.second, fingerprints))
    }

    @Test
    fun shellAgentsAreTopLevelAndMissingDirectoryIsEmptyButNonDirectoryFails() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = temporary.newFolder()
        File(root, "AGENT.Md").writeText("top")
        File(root, "nested/other.md").apply { parentFile!!.mkdirs(); writeText("nested") }
        java.nio.file.Files.createSymbolicLink(File(root, "linked.MD").toPath(), File(root, "AGENT.Md").toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "linked-directory.md").toPath(), File(root, "nested").toPath())
        java.nio.file.Files.createSymbolicLink(File(root, "broken.md").toPath(), File(root, "absent.md").toPath())
        val listed = runShell(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.AGENTS))
        assertEquals(0, listed.first)
        assertEquals(setOf("AGENT.Md", "linked.MD"), RemoteAgentConfigProtocol.manifest(listed.second, AgentConfigKind.AGENTS).keys)
        val absent = runShell(RemoteAgentConfigProtocol.manifestCommand(File(root, "missing/child").path, AgentConfigKind.AGENTS))
        assertEquals(0, absent.first)
        assertTrue(RemoteAgentConfigProtocol.manifest(absent.second, AgentConfigKind.AGENTS).isEmpty())
        assertFalse(runShell(RemoteAgentConfigProtocol.manifestCommand(File(root, "AGENT.Md").path, AgentConfigKind.AGENTS)).first == 0)
        assertFalse(runShell(RemoteAgentConfigProtocol.bodiesCommand(root.path), "END\nEXTRA\n".toByteArray()).first == 0)
        assertFalse(runShell(RemoteAgentConfigProtocol.bodiesCommand(root.path), "".toByteArray()).first == 0)
    }

    private fun runShell(command: String, input: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val stdout = pool.submit<ByteArray> { process.inputStream.use { it.readBytes() } }
            val stderr = pool.submit<ByteArray> { process.errorStream.use { it.readBytes() } }
            process.outputStream.use { it.write(input) }
            assertTrue("Shell protocol timed out", process.waitFor(10, TimeUnit.SECONDS))
            stderr.get(2, TimeUnit.SECONDS)
            return process.exitValue() to stdout.get(2, TimeUnit.SECONDS)
        } finally {
            process.destroyForcibly()
            pool.shutdownNow()
        }
    }
}
