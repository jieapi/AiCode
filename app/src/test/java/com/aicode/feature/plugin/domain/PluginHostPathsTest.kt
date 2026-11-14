package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginHostPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = mockk<FileAccessProvider>()
    private val homeResolver = mockk<PathHomeResolver>()
    private lateinit var data: File
    private lateinit var install: File
    private lateinit var workspace: File
    private lateinit var config: File
    private lateinit var paths: PluginHostPaths

    @Before
    fun setUp() {
        data = tmp.newFolder("data")
        install = tmp.newFolder("install")
        workspace = tmp.newFolder("workspace")
        config = tmp.newFolder("config")
        every { homeResolver.aicodeRoot() } returns HOST_ROOT
        every { fileAccess.toDisplayPath(any()) } answers { firstArg() }
        paths = PluginHostPaths(data, install, fileAccess, homeResolver)
    }

    private fun localFile(path: String): File? = when {
        path == HOST_ROOT -> config
        path.startsWith("$HOST_ROOT/") -> File(config, path.removePrefix("$HOST_ROOT/"))
        path == PluginHostPaths.WORKSPACE_ROOT -> workspace
        path.startsWith("${PluginHostPaths.WORKSPACE_ROOT}/") ->
            File(workspace, path.removePrefix("${PluginHostPaths.WORKSPACE_ROOT}/"))
        else -> null
    }

    private fun assertCode(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(PluginHostException::class.java) { block() }.code)
    }

    private fun assertOk(result: JsonElement) {
        assertEquals("""{"ok":true}""", result.toString())
    }

    @Test
    fun watch_resolvesDataAndPluginRootsWithoutRemoteAccess() {
        assertEquals(PluginWatchTarget.Local(data.canonicalFile), paths.watchTarget("data://", ::localFile))
        assertEquals(
            PluginWatchTarget.Local(File(install, "assets").canonicalFile),
            paths.watchTarget("plugin://assets/", ::localFile, recursive = true)
        )
        verify(exactly = 0) { fileAccess.exists(any()) }
    }

    @Test
    fun watch_preservesMappedLocalWorkspaceAndAicodeRoots() {
        assertEquals(
            PluginWatchTarget.Local(File(workspace, "src").canonicalFile),
            paths.watchTarget("workspace://src", ::localFile)
        )
        assertEquals(
            PluginWatchTarget.Local(File(config, "memory").canonicalFile),
            paths.watchTarget("aicode://memory/", ::localFile, recursive = true)
        )
    }

    @Test
    fun watch_returnsRemotePathsWhenNoLocalMappingExists() {
        assertEquals(
            PluginWatchTarget.Remote("~/workspace/src"),
            paths.watchTarget("workspace://src", { null }, recursive = true)
        )
        assertEquals(
            PluginWatchTarget.Remote("$HOST_ROOT/memory"),
            paths.watchTarget("aicode://memory", { null })
        )
        verify(exactly = 0) { fileAccess.exists(any()) }
    }

    @Test
    fun watch_supportsExistingFilesAndMissingPaths() {
        val file = File(data, "notes.md").apply { writeText("notes") }
        assertEquals(PluginWatchTarget.Local(file.canonicalFile), paths.watchTarget("data://notes.md", ::localFile))
        assertEquals(
            PluginWatchTarget.Local(File(data, "future").canonicalFile),
            paths.watchTarget("data://future", ::localFile)
        )
    }

    @Test
    fun watch_rejectsAicodeRootForBothRecursionModes() {
        for (recursive in listOf(false, true)) {
            assertCode("PATH_DENIED") { paths.watchTarget("aicode://", ::localFile, recursive) }
        }
    }

    @Test
    fun watch_enforcesEveryExistingProtectedPath() {
        for (name in PluginHostPaths.HOST_PROTECTED) {
            assertCode("READ_ONLY") { paths.watchTarget("aicode://$name", { null }) }
            assertCode("READ_ONLY") { paths.watchTarget("aicode://$name/child", ::localFile, true) }
        }
    }

    @Test
    fun watch_rejectsUnknownSchemesAndTraversal() {
        for (path in listOf("unknown://scope", "plain", "data:///tmp", "workspace://../secret", "aicode://a/../b", "data://a//b", "data://a\\b")) {
            assertCode("PATH_INVALID") { paths.watchTarget(path, ::localFile, true) }
        }
    }

    @Test
    fun watch_rejectsLocalSymlinksOutsideTheirRoot() {
        Files.createSymbolicLink(File(data, "outside").toPath(), config.toPath())
        assertCode("PATH_DENIED") { paths.watchTarget("data://outside", ::localFile, true) }
        Files.createSymbolicLink(File(config, "alias").toPath(), data.toPath())
        assertCode("PATH_DENIED") { paths.watchTarget("aicode://alias", ::localFile, true) }
    }

    @Test
    fun watch_rejectsSymlinkAliasToProtectedDirectory() {
        val plugins = File(config, "plugins").apply { mkdirs() }
        Files.createSymbolicLink(File(config, "alias").toPath(), plugins.toPath())
        assertCode("READ_ONLY") { paths.watchTarget("aicode://alias", ::localFile, true) }
    }

    @Test
    fun watch_rejectsLocalRecursiveParentContainingHostConfig() {
        val parentPaths = PluginHostPaths(tmp.root, install, fileAccess, homeResolver)
        assertCode("PATH_DENIED") { parentPaths.watchTarget("data://", ::localFile, true) }
        assertEquals(PluginWatchTarget.Local(tmp.root.canonicalFile), parentPaths.watchTarget("data://", ::localFile))
    }

    @Test
    fun watch_rejectsMappedWorkspaceAliasesOfHostConfig() {
        val mapper: (String) -> File? = { path ->
            when (path) {
                HOST_ROOT -> config
                "~/workspace" -> tmp.root
                "~/workspace/config" -> config
                "~/workspace/config/mcp.json" -> File(config, "mcp.json")
                else -> null
            }
        }
        assertCode("PATH_DENIED") { paths.watchTarget("workspace://", mapper, true) }
        assertCode("PATH_DENIED") { paths.watchTarget("workspace://config", mapper) }
        assertCode("READ_ONLY") { paths.watchTarget("workspace://config/mcp.json", mapper) }
    }

    @Test
    fun watch_rejectsRemoteWorkspaceAliasesOfHostConfig() {
        every { fileAccess.toDisplayPath(HOST_ROOT) } returns "~/workspace/.aicode"
        assertCode("PATH_DENIED") { paths.watchTarget("workspace://", { null }, true) }
        assertCode("PATH_DENIED") { paths.watchTarget("workspace://.aicode", { null }) }
        assertCode("READ_ONLY") { paths.watchTarget("workspace://.aicode/plugins", { null }, true) }
        assertEquals(
            PluginWatchTarget.Remote("~/workspace/.aicode/memory"),
            paths.watchTarget("workspace://.aicode/memory", { null }, true)
        )
    }

    @Test
    fun watch_allowsPluginOwnedInstallDirectoryWithinProtectedPlugins() {
        val ownInstall = File(config, "plugins/installed/example").apply { mkdirs() }
        val ownData = File(config, "plugins/data/example").apply { mkdirs() }
        val ownedPaths = PluginHostPaths(ownData, ownInstall, fileAccess, homeResolver)
        assertEquals(PluginWatchTarget.Local(ownInstall.canonicalFile), ownedPaths.watchTarget("plugin://", ::localFile, true))
        assertEquals(PluginWatchTarget.Local(ownData.canonicalFile), ownedPaths.watchTarget("data://", ::localFile, true))
    }

    @Test
    fun mkdir_createsLocalDirectoriesAndRejectsReadOnlyTargets() {
        assertOk(paths.mkdir("data://nested/a/b"))
        assertOk(paths.mkdir("data://nested/a/b"))
        assertTrue(File(data, "nested/a/b").isDirectory)

        assertCode("READ_ONLY") { paths.mkdir("plugin://generated") }
        assertCode("READ_ONLY") { paths.mkdir("aicode://skills/new") }
        assertCode("PATH_INVALID") { paths.mkdir("data://../escape") }

        File(data, "taken").writeText("x")
        assertCode("NOT_FOUND") { paths.mkdir("data://taken") }
    }

    @Test
    fun mkdir_createsRemoteDirectories() {
        every { fileAccess.mkdirs("$WORKSPACE/nested/dir") } returns Unit

        assertOk(paths.mkdir("workspace://nested/dir"))

        verify { fileAccess.mkdirs("$WORKSPACE/nested/dir") }
    }

    @Test
    fun append_createsAndExtendsLocalFiles() {
        assertOk(paths.append("data://logs/new.log", "first"))
        assertOk(paths.append("data://logs/new.log", "\nsecond"))

        assertEquals("first\nsecond", File(data, "logs/new.log").readText())

        assertOk(paths.append("data://edge.log", "x".repeat(PluginHostPaths.MAX_WRITE_BYTES.toInt())))
        assertEquals(PluginHostPaths.MAX_WRITE_BYTES, File(data, "edge.log").length())
    }

    @Test
    fun append_rejectsContentOrFileThatExceedsWriteLimit() {
        File(data, "big.log").writeText("x".repeat(PluginHostPaths.MAX_WRITE_BYTES.toInt() - 5))
        assertCode("TOO_LARGE") { paths.append("data://big.log", "123456") }
        assertEquals(PluginHostPaths.MAX_WRITE_BYTES - 5, File(data, "big.log").length())

        assertCode("TOO_LARGE") {
            paths.append("data://huge.log", "x".repeat(PluginHostPaths.MAX_WRITE_BYTES.toInt() + 1))
        }
        assertFalse(File(data, "huge.log").exists())

        File(data, "folder").mkdirs()
        assertCode("NOT_FOUND") { paths.append("data://folder", "tail") }
        assertCode("READ_ONLY") { paths.append("plugin://notes.log", "tail") }
    }

    @Test
    fun append_readsAndRewritesRemoteFile() {
        every { fileAccess.exists("$WORKSPACE/app.log") } returns true
        every { fileAccess.isFile("$WORKSPACE/app.log") } returns true
        every { fileAccess.fileSize("$WORKSPACE/app.log") } returns 5L
        every { fileAccess.readFile("$WORKSPACE/app.log") } returns "hello"
        every { fileAccess.parentPath("$WORKSPACE/app.log") } returns WORKSPACE
        every { fileAccess.mkdirs(WORKSPACE) } returns Unit
        every { fileAccess.writeFile("$WORKSPACE/app.log", "hello world") } returns Unit

        assertOk(paths.append("workspace://app.log", " world"))

        verify { fileAccess.writeFile("$WORKSPACE/app.log", "hello world") }
    }

    @Test
    fun binary_writeAndReadRoundTripLocalFile() {
        val payload = byteArrayOf(0, 1, 2, 127, -1)
        assertOk(paths.writeBinary("data://assets/raw.bin", Base64.getEncoder().encodeToString(payload)))

        assertArrayEquals(payload, File(data, "assets/raw.bin").readBytes())
        assertEquals(
            """{"base64":"AAECf/8=","size":5}""",
            paths.readBinary("data://assets/raw.bin").toString()
        )
    }

    @Test
    fun binary_enforceSizeLimitsAndValidateInput() {
        assertCode("PARAM_INVALID") { paths.writeBinary("data://bad.bin", "not base64!!") }
        assertCode("TOO_LARGE") {
            paths.writeBinary("data://big.bin", Base64.getEncoder().encodeToString(ByteArray(PluginHostPaths.MAX_WRITE_BYTES.toInt() + 1)))
        }
        assertCode("READ_ONLY") { paths.writeBinary("plugin://asset.bin", "AQID") }

        File(data, "big.bin").writeBytes(ByteArray(PluginHostPaths.MAX_READ_BYTES.toInt() + 1))
        assertCode("TOO_LARGE") { paths.readBinary("data://big.bin") }
        File(data, "folder.bin").mkdirs()
        assertCode("NOT_FOUND") { paths.readBinary("data://folder.bin") }
    }

    @Test
    fun binary_useRemoteProvider() {
        every { fileAccess.exists("$WORKSPACE/logo.png") } returns true
        every { fileAccess.isDirectory("$WORKSPACE/logo.png") } returns false
        every { fileAccess.fileSize("$WORKSPACE/logo.png") } returns 3L
        every { fileAccess.readBytes("$WORKSPACE/logo.png") } returns byteArrayOf(1, 2, 3)
        every { fileAccess.parentPath("$WORKSPACE/new.png") } returns WORKSPACE
        every { fileAccess.mkdirs(WORKSPACE) } returns Unit
        every { fileAccess.writeBytes("$WORKSPACE/new.png", any()) } returns Unit

        assertEquals("""{"base64":"AQID","size":3}""", paths.readBinary("workspace://logo.png").toString())
        assertOk(paths.writeBinary("workspace://new.png", "AQID"))

        verify { fileAccess.writeBytes("$WORKSPACE/new.png", any()) }
    }

    @Test
    fun rename_movesLocalFileAndRejectsUnsupportedTargets() {
        File(data, "a.txt").writeText("a")

        assertOk(paths.rename("data://a.txt", "data://docs/a.txt"))

        assertEquals("a", File(data, "docs/a.txt").readText())
        assertFalse(File(data, "a.txt").exists())

        assertCode("NOT_FOUND") { paths.rename("data://missing.txt", "data://x.txt") }
        assertCode("PATH_DENIED") { paths.rename("data://docs/a.txt", "data://docs/a.txt") }
        File(data, "b.txt").writeText("b")
        assertCode("FILE_EXISTS") { paths.rename("data://b.txt", "data://docs/a.txt") }
        assertCode("PATH_DENIED") { paths.rename("data://", "data://moved") }
        assertCode("PATH_DENIED") { paths.rename("data://docs", "data://docs/nested") }
        assertCode("READ_ONLY") { paths.rename("plugin://a.txt", "data://a.txt") }
        assertCode("UNSUPPORTED") { paths.rename("data://docs/a.txt", "workspace://a.txt") }
    }

    @Test
    fun copy_copiesLocalFilesAndDirectoriesWithOverwriteControl() {
        File(data, "src/one.txt").apply { parentFile.mkdirs(); writeText("one") }

        assertOk(paths.copy("data://src/one.txt", "data://dst/one.txt", overwrite = false))
        assertEquals("one", File(data, "dst/one.txt").readText())

        assertOk(paths.copy("data://src", "data://dst/src", overwrite = false))
        assertEquals("one", File(data, "dst/src/one.txt").readText())
        assertCode("FILE_EXISTS") { paths.copy("data://src", "data://dst/src", overwrite = false) }

        File(data, "src/two.txt").writeText("two")
        assertOk(paths.copy("data://src", "data://dst/src", overwrite = true))
        assertEquals("two", File(data, "dst/src/two.txt").readText())
        assertEquals("two", File(data, "src/two.txt").readText())

        assertCode("NOT_FOUND") { paths.copy("data://missing", "data://dst/missing", overwrite = false) }
        assertCode("PATH_DENIED") { paths.copy("data://", "data://root-copy", overwrite = false) }
        assertCode("PATH_DENIED") { paths.copy("data://src", "data://src/nested", overwrite = false) }
        assertCode("PATH_DENIED") { paths.copy("data://src/one.txt", "data://src", overwrite = true) }
        assertCode("PATH_DENIED") { paths.copy("data://src/one.txt", "data://src/one.txt", overwrite = true) }
        assertCode("READ_ONLY") { paths.copy("data://src", "plugin://src", overwrite = false) }
        assertCode("UNSUPPORTED") { paths.copy("data://src", "workspace://src", overwrite = false) }
    }

    @Test
    fun renameAndCopy_useRemoteProvider() {
        every { fileAccess.exists("$WORKSPACE/a.txt") } returns true
        every { fileAccess.parentPath("$WORKSPACE/nested/b.txt") } returns "$WORKSPACE/nested"
        every { fileAccess.mkdirs("$WORKSPACE/nested") } returns Unit
        every { fileAccess.copy("$WORKSPACE/a.txt", "$WORKSPACE/nested/b.txt", false) } returns Unit
        every { fileAccess.rename("$WORKSPACE/a.txt", "$WORKSPACE/c.txt") } returns Unit

        assertOk(paths.copy("workspace://a.txt", "workspace://nested/b.txt", overwrite = false))
        assertOk(paths.rename("workspace://a.txt", "workspace://c.txt"))

        verify { fileAccess.copy("$WORKSPACE/a.txt", "$WORKSPACE/nested/b.txt", false) }
        verify { fileAccess.rename("$WORKSPACE/a.txt", "$WORKSPACE/c.txt") }
    }

    @Test
    fun copy_mapProviderFailuresToHostCodes() {
        every { fileAccess.exists("$WORKSPACE/a.txt") } returns true
        every { fileAccess.parentPath("$WORKSPACE/b.txt") } returns WORKSPACE
        every { fileAccess.mkdirs(WORKSPACE) } returns Unit
        every { fileAccess.copy("$WORKSPACE/a.txt", "$WORKSPACE/b.txt", false) } throws
            FileAlreadyExistsException(File("$WORKSPACE/b.txt"))
        every { fileAccess.rename("$WORKSPACE/a.txt", "$WORKSPACE/b.txt") } throws IOException("boom")

        assertCode("FILE_EXISTS") { paths.copy("workspace://a.txt", "workspace://b.txt", overwrite = false) }
        assertCode("IO_ERROR") { paths.rename("workspace://a.txt", "workspace://b.txt") }
    }

    private companion object {
        const val HOST_ROOT = "/home/dev/.aicode"
        const val WORKSPACE = "~/workspace"
    }
}
