package com.aicode.feature.plugin.domain

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    private companion object {
        const val HOST_ROOT = "/home/dev/.aicode"
    }
}
