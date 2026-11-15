package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PermissionRulesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: PermissionRulesRepository
    private lateinit var workspace: WorkspaceRepository
    private lateinit var projectRoot: ProjectAicodeRoot
    private lateinit var workspaceDir: File
    private lateinit var target: File
    private lateinit var link: File

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        val context: Context = mockk()
        workspace = mockk()
        val installer: ContainerInstaller = mockk()
        val settings: ContainerSettingsRepository = mockk()
        val home: PathHomeResolver = mockk()
        projectRoot = mockk()
        val changes: FileChangeHub = mockk()
        val filesDir = tmp.newFolder("files")
        workspaceDir = tmp.newFolder("workspace")
        target = File(workspaceDir, "project/.aicode").apply { mkdirs() }
        link = File(workspaceDir, ".aicode")
        Files.createSymbolicLink(link.toPath(), File("/root/workspace/project/.aicode").toPath())
        every { context.filesDir } returns filesDir
        every { workspace.currentPath() } returns workspaceDir.absolutePath
        every { workspace.current } returns MutableStateFlow(null)
        every { installer.aicodeDir } returns File(filesDir, "aicode")
        every { installer.rootfsDirFor(any()) } returns File(filesDir, "rootfs")
        every { settings.activeProfileIdFlow } returns emptyFlow()
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers { firstArg<String>().replaceFirst("~/", "/root/") }
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns link
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        repository = PermissionRulesRepository(context, workspace, projectRoot, changes, mapper)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun loadEffectiveRules_absoluteProjectLink_loadsExistingRules() = runBlocking {
        File(target, "permissions.json").writeText("""{"permissions":{"allow":["readFile"],"deny":["writeFile"]}}""")
        assertEquals(
            listOf(
                PermissionRule("readFile", "*"),
                PermissionRule("writeFile", "*", PermissionDecision.DENY)
            ),
            repository.loadEffectiveForCurrentProject()
        )
    }

    @Test
    fun explicitProjectRules_keepCapturedTargetAfterWorkspaceAndEnvironmentChange() = runBlocking {
        val existing = PermissionRule("readFile", "*")
        File(target, "permissions.json").writeText("""{"permissions":{"allow":["readFile"]}}""")
        val project = repository.captureProject(workspaceDir.absolutePath)
        val otherWorkspace = tmp.newFolder("other-workspace")
        val otherConfig = tmp.newFolder("other-server-config")
        every { workspace.currentPath() } returns otherWorkspace.absolutePath
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns otherConfig
        every { projectRoot.forPath(otherWorkspace.absolutePath) } returns otherConfig

        assertEquals(listOf(existing), repository.loadEffectiveForProject(project))
        val remembered = PermissionRule("writeFile", "*")
        repository.addToProject(project, remembered)

        assertEquals(listOf(existing, remembered), repository.loadEffectiveForProject(project))
        assertTrue(File(target, "permissions.json").readText().contains("writeFile"))
        assertTrue(!File(otherConfig, "permissions.json").exists())
        assertTrue(repository.loadEffectiveForCurrentProject().isEmpty())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun explicitProjectRules_separateCachesForSamePathOnDifferentServers() = runBlocking {
        val original = repository.captureProject(workspaceDir.absolutePath)
        val otherConfig = tmp.newFolder("other-server-config")
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns otherConfig
        val other = repository.captureProject(workspaceDir.absolutePath)
        val originalRule = PermissionRule("readFile", "*")
        val otherRule = PermissionRule("writeFile", "*")

        repository.addToProject(original, originalRule)
        repository.addToProject(other, otherRule)

        assertEquals(listOf(originalRule), repository.loadEffectiveForProject(original))
        assertEquals(listOf(otherRule), repository.loadEffectiveForProject(other))
        assertEquals(listOf(otherRule), repository.loadEffectiveForCurrentProject())
        every { projectRoot.forPath(workspaceDir.absolutePath) } returns link
        assertEquals(listOf(originalRule), repository.loadEffectiveForCurrentProject())
    }

    @Test
    fun addRule_absoluteProjectLink_createsFileAndPreservesLink() = runBlocking {
        val rule = PermissionRule("readFile", "*")
        repository.add(PermissionScope.PROJECT, rule)
        val saved = File(target, "permissions.json")
        assertTrue(saved.isFile)
        assertTrue(saved.readText().contains("readFile"))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(listOf(rule), repository.loadEffectiveForCurrentProject())
    }
}
