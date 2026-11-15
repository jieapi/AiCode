package com.aicode.feature.plugin.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginHostPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fileAccess = mockk<FileAccessProvider>(relaxed = true)
    private val homeResolver = mockk<PathHomeResolver>()
    private lateinit var paths: PluginHostPaths

    @Before
    fun setUp() {
        every { homeResolver.home() } returns HOME
        every { homeResolver.aicodeRoot() } returns AICODE
        every { homeResolver.expandHome(any()) } answers {
            val path = firstArg<String>()
            when {
                path == "~" -> HOME
                path.startsWith("~/") -> HOME + path.removePrefix("~")
                else -> path
            }
        }
        every { fileAccess.resolvePath(any(), any()) } answers {
            File(firstArg<String>()).toPath().normalize().toString()
        }
        paths = PluginHostPaths(fileAccess, homeResolver, { WORKSPACE }, PLUGIN_ID)
    }

    private fun assertCode(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(PluginHostException::class.java) { block() }.code)
    }

    private fun assertOk(result: JsonElement) {
        assertEquals("""{"ok":true}""", result.toString())
    }

    private fun stubFile(path: String, size: Long = 0, directory: Boolean = false) {
        every { fileAccess.exists(path) } returns true
        every { fileAccess.isDirectory(path) } returns directory
        every { fileAccess.isFile(path) } returns !directory
        every { fileAccess.fileSize(path) } returns size
    }

    private fun stubParent(path: String, parent: String) {
        every { fileAccess.parentPath(path) } returns parent
        every { fileAccess.mkdirs(parent) } returns Unit
    }

    @Test
    fun dataSchemeMapsToPluginDataDirectory() {
        stubFile("$DATA/a.txt", size = 3)
        every { fileAccess.readFile("$DATA/a.txt") } returns "abc"
        assertEquals("""{"content":"abc","size":3}""", paths.read("data://a.txt").toString())

        stubParent("$DATA/nested/b.txt", "$DATA/nested")
        every { fileAccess.writeFile("$DATA/nested/b.txt", "b") } returns Unit
        assertOk(paths.write("data://nested/b.txt", "b"))

        assertEquals(PluginWatchTarget.Remote("$DATA/records"), paths.watchTarget("data://records", { null }))
        assertEquals("$DATA", PluginHostPaths.dataRoot(homeResolver, PLUGIN_ID))
    }

    @Test
    fun dataSchemeRejectsTraversalAndRootMutation() {
        for (path in listOf("data://../escape", "data://a/../b", "data://a//b", "data://a/./b")) {
            assertCode("PATH_INVALID") { paths.read(path) }
        }
        assertCode("PATH_DENIED") { paths.delete("data://") }
        assertCode("PATH_DENIED") { paths.write("data://", "x") }
    }

    @Test
    fun read_usesAbsolutePathsDirectly() {
        stubFile("$WORKSPACE/a.txt", size = 3)
        every { fileAccess.readFile("$WORKSPACE/a.txt") } returns "abc"

        assertEquals("""{"content":"abc","size":3}""", paths.read("$WORKSPACE/a.txt").toString())
        verify { fileAccess.readFile("$WORKSPACE/a.txt") }
    }

    @Test
    fun read_expandsHomeAndResolvesRelativePathsToWorkspace() {
        stubFile("$HOME/notes.md", size = 1)
        every { fileAccess.readFile("$HOME/notes.md") } returns "n"
        stubFile("$WORKSPACE/src/main.kt", size = 2)
        every { fileAccess.readFile("$WORKSPACE/src/main.kt") } returns "mk"

        assertEquals("""{"content":"n","size":1}""", paths.read("~/notes.md").toString())
        assertEquals("""{"content":"mk","size":2}""", paths.read("src/main.kt").toString())
    }

    @Test
    fun read_rejectsInvalidPaths() {
        for (path in listOf("", "   ", "a\\b", "$HOME/a\u0000b", "/" + "x".repeat(PluginHostPaths.MAX_PATH_LENGTH))) {
            assertCode("PATH_INVALID") { paths.read(path) }
        }
    }

    @Test
    fun read_reportsMissingFilesDirectoriesAndOversize() {
        every { fileAccess.exists("/missing") } returns false
        assertCode("NOT_FOUND") { paths.read("/missing") }

        stubFile("/dir", directory = true)
        assertCode("NOT_FOUND") { paths.read("/dir") }

        stubFile("/big", size = PluginHostPaths.MAX_READ_BYTES + 1)
        assertCode("TOO_LARGE") { paths.read("/big") }
    }

    @Test
    fun write_createsParentsAndRejectsOversizeOrRoots() {
        stubParent("$WORKSPACE/nested/a.txt", "$WORKSPACE/nested")
        every { fileAccess.writeFile("$WORKSPACE/nested/a.txt", "hi") } returns Unit
        assertOk(paths.write("nested/a.txt", "hi"))

        assertCode("TOO_LARGE") { paths.write("big.txt", "x".repeat(PluginHostPaths.MAX_WRITE_BYTES.toInt() + 1)) }
        for (root in PROTECTED_ROOTS) {
            assertCode("PATH_DENIED") { paths.write(root, "x") }
        }
    }

    @Test
    fun mkdir_rejectsRootsAndMissingParentsAreCreatedByProvider() {
        every { fileAccess.mkdirs("$WORKSPACE/a/b") } returns Unit
        assertOk(paths.mkdir("a/b"))

        assertCode("PATH_DENIED") { paths.mkdir("/") }
        for (root in PROTECTED_ROOTS) {
            assertCode("PATH_DENIED") { paths.mkdir(root) }
        }
    }

    @Test
    fun append_extendsExistingFilesAndEnforcesLimits() {
        stubFile("$WORKSPACE/log.txt", size = 5)
        every { fileAccess.readFile("$WORKSPACE/log.txt") } returns "hello"
        stubParent("$WORKSPACE/log.txt", WORKSPACE)
        every { fileAccess.writeFile("$WORKSPACE/log.txt", "hello world") } returns Unit

        assertOk(paths.append("$WORKSPACE/log.txt", " world"))
        verify { fileAccess.writeFile("$WORKSPACE/log.txt", "hello world") }
    }

    @Test
    fun append_createsMissingFilesAndRejectsDirectoriesAndOversize() {
        every { fileAccess.exists("$WORKSPACE/new.log") } returns false
        stubParent("$WORKSPACE/new.log", WORKSPACE)
        every { fileAccess.writeFile("$WORKSPACE/new.log", "first") } returns Unit
        assertOk(paths.append("$WORKSPACE/new.log", "first"))

        stubFile("$WORKSPACE/folder", directory = true)
        assertCode("NOT_FOUND") { paths.append("$WORKSPACE/folder", "x") }

        stubFile("$WORKSPACE/big.log", size = PluginHostPaths.MAX_WRITE_BYTES - 5)
        assertCode("TOO_LARGE") { paths.append("$WORKSPACE/big.log", "123456") }
        assertCode("TOO_LARGE") {
            paths.append("$WORKSPACE/huge.log", "x".repeat(PluginHostPaths.MAX_WRITE_BYTES.toInt() + 1))
        }
    }

    @Test
    fun binary_readAndWriteUseBase64() {
        stubFile("$WORKSPACE/logo.png", size = 3)
        every { fileAccess.readBytes("$WORKSPACE/logo.png") } returns byteArrayOf(1, 2, 3)
        assertEquals(
            """{"base64":"AQID","size":3}""",
            paths.readBinary("$WORKSPACE/logo.png").toString()
        )

        stubParent("$WORKSPACE/new.bin", WORKSPACE)
        every { fileAccess.writeBytes("$WORKSPACE/new.bin", any()) } returns Unit
        assertOk(paths.writeBinary("$WORKSPACE/new.bin", "AQID"))
        verify { fileAccess.writeBytes("$WORKSPACE/new.bin", match { it.contentEquals(byteArrayOf(1, 2, 3)) }) }
    }

    @Test
    fun binary_validatesInputAndLimits() {
        assertCode("PARAM_INVALID") { paths.writeBinary("$WORKSPACE/bad.bin", "not base64!!") }
        assertCode("TOO_LARGE") {
            paths.writeBinary(
                "$WORKSPACE/big.bin",
                java.util.Base64.getEncoder().encodeToString(ByteArray(PluginHostPaths.MAX_WRITE_BYTES.toInt() + 1))
            )
        }

        stubFile("$WORKSPACE/big.bin", size = PluginHostPaths.MAX_READ_BYTES + 1)
        assertCode("TOO_LARGE") { paths.readBinary("$WORKSPACE/big.bin") }

        stubFile("$WORKSPACE/folder.bin", directory = true)
        assertCode("NOT_FOUND") { paths.readBinary("$WORKSPACE/folder.bin") }
    }

    @Test
    fun renameAndCopyRejectRootsSamePathAndNestedTargets() {
        for (call in listOf<() -> Unit>(
            { paths.rename("/", "/moved") },
            { paths.rename("/a", "/") },
            { paths.rename("/a", "/a") },
            { paths.copy("/", "/root-copy", false) },
            { paths.copy("/a", "/a", true) }
        )) {
            assertCode("PATH_DENIED", call)
        }

        every { fileAccess.isDirectory("$WORKSPACE/dir") } returns true
        assertCode("PATH_DENIED") { paths.copy("$WORKSPACE/dir", "$WORKSPACE/dir/nested", false) }
        assertCode("PATH_DENIED") { paths.rename("$WORKSPACE/dir", "$WORKSPACE/dir/nested") }
    }

    @Test
    fun renameAndCopyDelegateToProvider() {
        every { fileAccess.rename("$WORKSPACE/a.txt", "$WORKSPACE/b.txt") } returns Unit
        every { fileAccess.copy("$WORKSPACE/a.txt", "$WORKSPACE/c.txt", false) } returns Unit

        assertOk(paths.rename("$WORKSPACE/a.txt", "$WORKSPACE/b.txt"))
        assertOk(paths.copy("$WORKSPACE/a.txt", "$WORKSPACE/c.txt", overwrite = false))

        verify { fileAccess.rename("$WORKSPACE/a.txt", "$WORKSPACE/b.txt") }
        verify { fileAccess.copy("$WORKSPACE/a.txt", "$WORKSPACE/c.txt", false) }
    }

    @Test
    fun list_sortsTruncatesAndRejectsFiles() {
        every { fileAccess.isDirectory("$WORKSPACE/dir") } returns true
        every { fileAccess.listFiles("$WORKSPACE/dir") } returns listOf(
            FileEntry("b.txt", isDirectory = false, size = 2, lastModified = 0),
            FileEntry("a", isDirectory = true, size = 0, lastModified = 0)
        )
        assertEquals(
            """{"entries":[{"name":"a","isDir":true,"size":0},{"name":"b.txt","isDir":false,"size":2}]}""",
            paths.list("$WORKSPACE/dir").toString()
        )

        val many = (1..PluginHostPaths.MAX_LIST_ENTRIES + 10).map {
            FileEntry("f$it", isDirectory = false, size = 0, lastModified = 0)
        }
        every { fileAccess.isDirectory("/many") } returns true
        every { fileAccess.listFiles("/many") } returns many
        val entries = Json.parseToJsonElement(paths.list("/many").toString())
            .jsonObject["entries"]!!.jsonArray
        assertEquals(PluginHostPaths.MAX_LIST_ENTRIES, entries.size)

        every { fileAccess.isDirectory("$WORKSPACE/file.txt") } returns false
        assertCode("NOT_FOUND") { paths.list("$WORKSPACE/file.txt") }
    }

    @Test
    fun delete_rejectsRoots() {
        every { fileAccess.deleteRecursively("$WORKSPACE/tmp") } returns Unit
        assertOk(paths.delete("$WORKSPACE/tmp"))
        for (root in PROTECTED_ROOTS) {
            assertCode("PATH_DENIED") { paths.delete(root) }
        }
    }

    @Test
    fun stat_reportsExistenceAndKind() {
        stubFile("$WORKSPACE/a.txt", size = 7)
        assertEquals("""{"exists":true,"isDir":false,"size":7}""", paths.stat("$WORKSPACE/a.txt").toString())

        every { fileAccess.exists("/missing") } returns false
        assertEquals("""{"exists":false,"isDir":false,"size":0}""", paths.stat("/missing").toString())
    }

    @Test
    fun watch_returnsLocalOrRemoteTargets() {
        val local = tmp.newFolder("local")
        assertEquals(PluginWatchTarget.Local(local.canonicalFile), paths.watchTarget("/watched", { local }))
        assertEquals(PluginWatchTarget.Remote("$WORKSPACE/src"), paths.watchTarget("$WORKSPACE/src", { null }))
        assertEquals(PluginWatchTarget.Remote("$WORKSPACE/src"), paths.watchTarget("src", { null }, recursive = true))
    }

    @Test
    fun watch_rejectsWholeFilesystemHomeAndConfigRoots() {
        for (recursive in listOf(false, true)) {
            assertCode("PATH_DENIED") { paths.watchTarget("/", { null }, recursive) }
            assertCode("PATH_DENIED") { paths.watchTarget(HOME, { null }, recursive) }
            assertCode("PATH_DENIED") { paths.watchTarget(AICODE, { null }, recursive) }
            assertCode("PATH_DENIED") { paths.watchTarget("~", { null }, recursive) }
        }
        assertCode("PATH_DENIED") { paths.watchTarget("/root", { null }, recursive = true) }
    }

    @Test
    fun watch_allowsScopesUnderHomeAndConfig() {
        assertEquals(
            PluginWatchTarget.Remote("$WORKSPACE/src"),
            paths.watchTarget("$WORKSPACE/src", { null }, recursive = true)
        )
        assertEquals(
            PluginWatchTarget.Remote("$AICODE/memory"),
            paths.watchTarget("$AICODE/memory", { null }, recursive = true)
        )
    }

    @Test
    fun providerFailuresAreMappedToStableCodes() {
        stubFile("$WORKSPACE/gone.txt", size = 1)
        every { fileAccess.readFile("$WORKSPACE/gone.txt") } throws NoSuchFileException("$WORKSPACE/gone.txt")
        assertCode("NOT_FOUND") { paths.read("$WORKSPACE/gone.txt") }

        stubParent("$WORKSPACE/exists.txt", WORKSPACE)
        every { fileAccess.writeFile("$WORKSPACE/exists.txt", "x") } throws
            FileAlreadyExistsException("$WORKSPACE/exists.txt")
        assertCode("FILE_EXISTS") { paths.write("$WORKSPACE/exists.txt", "x") }

        stubParent("$WORKSPACE/broken.txt", WORKSPACE)
        every { fileAccess.writeFile("$WORKSPACE/broken.txt", "x") } throws IOException("boom")
        assertCode("IO_ERROR") { paths.write("$WORKSPACE/broken.txt", "x") }
    }

    private fun useBackendPathResolution() {
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        every { workspace.currentPath() } returns File(tmp.root, "workspace").apply { mkdirs() }.path
        every { installer.aicodeDir } returns File(tmp.root, "aicode").apply { mkdirs() }
        every { installer.rootfsDirFor(any()) } returns File(tmp.root, "rootfs").apply { mkdirs() }
        every { settings.activeProfileIdFlow } returns emptyFlow()
        val access = LocalFileAccess(WorkspacePathMapper(workspace, installer, settings, homeResolver))
        every { fileAccess.resolvePath(any(), any()) } answers {
            access.resolvePath(firstArg(), secondArg())
        }
    }

    @Test
    fun mutation_rejectsBackendRootAliases() {
        useBackendPathResolution()
        val aliases = listOf(".", "./", "$AICODE/.", "/root//workspace", "$WORKSPACE/sub/..", "$WORKSPACE/../.aicode") +
            PROTECTED_ROOTS.flatMap { listOf("$it/.", "$it/child/..") }
        for (alias in aliases) {
            assertCode("PATH_DENIED") { paths.delete(alias) }
            assertCode("PATH_DENIED") { paths.write(alias, "x") }
            assertCode("PATH_DENIED") { paths.rename(alias, "$WORKSPACE/moved") }
            assertCode("PATH_DENIED") { paths.rename("$WORKSPACE/file", alias) }
            assertCode("PATH_DENIED") { paths.copy("$WORKSPACE/file", alias, true) }
        }
        verify(exactly = 0) { fileAccess.deleteRecursively(any()) }
        verify(exactly = 0) { fileAccess.rename(any(), any()) }
    }

    @Test
    fun mutation_resolvesLinkBeforeParentAndPreservesFinalLinkOperations() {
        useBackendPathResolution()
        val workspace = File(tmp.root, "workspace")
        File(tmp.root, "aicode/plugins").mkdirs()
        Files.createSymbolicLink(File(workspace, "config").toPath(), File("$AICODE/plugins").toPath())
        every { fileAccess.isDirectory("$WORKSPACE/config") } returns true
        assertCode("PATH_DENIED") { paths.delete("config/..") }
        assertCode("PATH_DENIED") { paths.delete("config/.") }
        assertCode("PATH_DENIED") { paths.write("config", "x") }
        assertOk(paths.delete("config"))
        assertOk(paths.rename("config", "config-renamed"))
        verify { fileAccess.deleteRecursively("$WORKSPACE/config") }
        verify { fileAccess.rename("$WORKSPACE/config", "$WORKSPACE/config-renamed") }

        Files.createSymbolicLink(File(workspace, "elsewhere").toPath(), File("/tmp/child").toPath())
        assertOk(paths.delete("elsewhere/.."))
        verify { fileAccess.deleteRecursively("$WORKSPACE/elsewhere/..") }
    }

    @Test
    fun watch_rejectsAliasesAndLinksToBroadScopes() {
        useBackendPathResolution()
        val workspace = File(tmp.root, "workspace")
        Files.createSymbolicLink(File(workspace, "home").toPath(), File(HOME).toPath())
        Files.createSymbolicLink(File(workspace, "config").toPath(), File("$AICODE/plugins").toPath())
        for (recursive in listOf(false, true)) {
            for (alias in listOf("//", "$HOME/.", "$AICODE/.", "home", "config/..")) {
                assertCode("PATH_DENIED") { paths.watchTarget(alias, { null }, recursive) }
            }
        }
        assertEquals(
            PluginWatchTarget.Remote("$WORKSPACE/config"),
            paths.watchTarget("config", { null }, recursive = true)
        )
    }

    @Test
    fun copyAndRename_rejectBackendNestedAliases() {
        useBackendPathResolution()
        val workspace = File(tmp.root, "workspace")
        File(workspace, "dir").mkdirs()
        Files.createSymbolicLink(File(workspace, "alias").toPath(), File("$WORKSPACE/dir").toPath())
        every { fileAccess.isDirectory("$WORKSPACE/dir") } returns true
        every { fileAccess.isDirectory("$WORKSPACE/alias") } returns true
        every { fileAccess.isDirectory("$WORKSPACE/alias/child") } returns true
        assertCode("PATH_DENIED") { paths.copy("dir", "alias/child", true) }
        assertCode("PATH_DENIED") { paths.rename("dir", "alias/child") }
        assertCode("PATH_DENIED") { paths.copy("alias", "dir/child", true) }
        assertCode("PATH_DENIED") { paths.copy("dir", "dir/../dir", true) }
        assertCode("PATH_DENIED") { paths.copy("alias/child", "dir", true) }
    }

    private companion object {
        const val HOME = "/root"
        const val AICODE = "/root/.aicode"
        const val WORKSPACE = "/root/workspace"
        const val PLUGIN_ID = "demo.test"
        const val DATA = "$AICODE/plugins/data/$PLUGIN_ID"
        val PROTECTED_ROOTS = listOf(
            "/",
            HOME,
            AICODE,
            WORKSPACE,
            DATA,
            "$AICODE/plugins",
            "$AICODE/plugins/installed",
            "$AICODE/plugins/staging",
            "$AICODE/plugins/data",
            "$AICODE/plugin-dev"
        )
    }
}
