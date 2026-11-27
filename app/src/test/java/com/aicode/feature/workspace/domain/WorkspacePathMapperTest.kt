package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.FileSystemLoopException
import java.nio.file.Files

/**
 * WorkspacePathMapper：容器路径 ↔ 宿主真实路径互转。
 * 固定环境：宿主工作区=/data/ws、AI 配置目录=/data/aicode、rootfs=/data/rootfs、容器 home=/root。
 * activeProfileFlow 用 emptyFlow()，让 init 协程的 collect 永不触发，避免真实 DataStore 调用
 * 与异步 profile 切换（extraBindings 挂载分支依赖该异步更新，纯 JVM 下竞态不稳定，不在本类覆盖）。
 */
class WorkspacePathMapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesystemMapper(): WorkspacePathMapper {
        val mapper = newMapper()
        every { workspaceRepository.currentPath() } returns tmp.newFolder("workspace").absolutePath
        every { containerInstaller.aicodeDir } returns tmp.newFolder("aicode")
        every { containerInstaller.rootfsDirFor(any()) } returns tmp.newFolder("rootfs")
        return mapper
    }

    private val workspaceRepository: WorkspaceRepository = mockk()
    private val containerInstaller: ContainerInstaller = mockk()
    private val containerSettingsRepository: ContainerSettingsRepository = mockk()
    private val pathHomeResolver: PathHomeResolver = mockk()

    private val wsRoot = "/data/ws"
    private val aicodeDir = "/data/aicode"
    private val rootfsDir = "/data/rootfs"
    private val home = "/root"

    private fun newMapper(): WorkspacePathMapper {
        every { workspaceRepository.currentPath() } returns wsRoot
        every { containerInstaller.aicodeDir } returns File(aicodeDir)
        every { containerInstaller.rootfsDirFor(any()) } returns File(rootfsDir)
        every { containerSettingsRepository.activeProfileFlow } returns emptyFlow()
        every { pathHomeResolver.home() } returns home
        // expandHome 保持真实展开语义，让 toHostFile 里的 ~ 前缀与生产行为一致
        every { pathHomeResolver.expandHome(any()) } answers {
            val p = firstArg<String>()
            when {
                p == "~" -> pathHomeResolver.home()
                p.startsWith("~/") -> pathHomeResolver.home().trimEnd('/') + p.removePrefix("~")
                else -> p
            }
        }
        return WorkspacePathMapper(workspaceRepository, containerInstaller, containerSettingsRepository, pathHomeResolver)
    }

    // ---------- toHostFile：容器路径 → 宿主文件 ----------

    @Test
    fun toHostFile_workspaceRootTilde_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("~/workspace/"))
    }

    @Test
    fun toHostFile_workspaceRootExpandedForm_mapsToHostWorkspaceRoot() {
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace"))
        assertEquals(File(wsRoot), newMapper().toHostFile("$home/workspace/"))
    }

    @Test
    fun toHostFile_workspaceChild_mapsUnderHostWorkspace() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("~/workspace/src/Main.kt"))
        assertEquals(File(wsRoot, "app/build.gradle.kts"), newMapper().toHostFile("$home/workspace/app/build.gradle.kts"))
    }

    @Test
    fun toHostFile_aicodeRoot_mapsToAicodeDir() {
        assertEquals(File(aicodeDir), newMapper().toHostFile("/root/.aicode"))
    }

    @Test
    fun toHostFile_aicodeChild_mapsUnderAicodeDir() {
        assertEquals(File(aicodeDir, "skills/x/SKILL.md"), newMapper().toHostFile("/root/.aicode/skills/x/SKILL.md"))
    }

    @Test
    fun toHostFile_otherAbsolutePath_mapsToRootfs() {
        assertEquals(File(rootfsDir, "etc/hosts"), newMapper().toHostFile("/etc/hosts"))
        // /root 本身不在工作区/配置目录前缀下，走通用绝对路径规则落到 rootfs
        assertEquals(File(rootfsDir, "root"), newMapper().toHostFile("/root"))
    }

    @Test
    fun toHostFile_relativePath_hangsUnderWorkspaceRoot() {
        assertEquals(File(wsRoot, "src/Main.kt"), newMapper().toHostFile("src/Main.kt"))
    }

    @Test
    fun toHostFile_inputIsTrimmed() {
        assertEquals(File(wsRoot), newMapper().toHostFile("  ~/workspace  "))
    }

    // ---------- toContainerPath：宿主路径 → 容器路径 ----------

    @Test
    fun toContainerPath_workspaceRoot_mapsToContainerRoot() {
        assertEquals("~/workspace", newMapper().toContainerPath(wsRoot))
    }

    @Test
    fun toContainerPath_workspaceChild_mapsToContainerForm() {
        assertEquals("~/workspace/src/Main.kt", newMapper().toContainerPath("$wsRoot/src/Main.kt"))
    }

    @Test
    fun toContainerPath_expandedHomeForm_mapsToContainerForm() {
        assertEquals("~/workspace", newMapper().toContainerPath("$home/workspace"))
        assertEquals("~/workspace/a.txt", newMapper().toContainerPath("$home/workspace/a.txt"))
    }

    @Test
    fun toContainerPath_aicode_mapsToAicodeRootForm() {
        assertEquals("/root/.aicode", newMapper().toContainerPath(aicodeDir))
        assertEquals("/root/.aicode/skills/x/SKILL.md", newMapper().toContainerPath("$aicodeDir/skills/x/SKILL.md"))
    }

    @Test
    fun toContainerPath_rootfs_mapsToContainerAbsolute() {
        assertEquals("/", newMapper().toContainerPath(rootfsDir))
        assertEquals("/etc/hosts", newMapper().toContainerPath("$rootfsDir/etc/hosts"))
    }

    @Test
    fun toContainerPath_unmappedPath_unchanged() {
        assertEquals("/somewhere/else", newMapper().toContainerPath("/somewhere/else"))
    }

    @Test
    fun resolveFile_absoluteDirectoryLink_mapsTargetToWorkspace() {
        val mapper = filesystemMapper()
        val target = mapper.toHostFile("target").apply { mkdirs() }
        File(target, "probe.txt").writeText("test")
        Files.createSymbolicLink(mapper.toHostFile("link").toPath(), File("/root/workspace/target").toPath())
        assertEquals(File(target, "probe.txt"), mapper.resolveFile("~/workspace/link/probe.txt"))
        assertEquals("test", mapper.resolveFile("link/probe.txt").readText())
    }

    @Test
    fun resolveHostFile_projectPermissionsLink_mapsTargetAndMissingFile() {
        val mapper = filesystemMapper()
        val target = mapper.toHostFile("project/.aicode").apply { mkdirs() }
        Files.createSymbolicLink(mapper.toHostFile(".aicode").toPath(), File("/root/workspace/project/.aicode").toPath())
        assertEquals(File(target, "permissions.json"), mapper.resolveHostFile(mapper.toHostFile(".aicode/permissions.json")))
    }

    @Test
    fun resolveFile_relativeLinksAndParentSegments_resolveAfterLink() {
        val mapper = filesystemMapper()
        mapper.toHostFile("target/sub").mkdirs()
        Files.createSymbolicLink(mapper.toHostFile("relative").toPath(), File("target/sub").toPath())
        Files.createSymbolicLink(mapper.toHostFile("chain").toPath(), File("relative").toPath())
        assertEquals(mapper.toHostFile("target/probe.txt"), mapper.resolveFile("chain/../probe.txt"))
    }

    @Test
    fun resolveFile_absoluteLinks_mapRootfsAndGlobalConfig() {
        val mapper = filesystemMapper()
        Files.createSymbolicLink(mapper.toHostFile("system").toPath(), File("/etc").toPath())
        Files.createSymbolicLink(mapper.toHostFile("global").toPath(), File("/root/.aicode").toPath())
        assertEquals(mapper.toHostFile("/etc/hosts"), mapper.resolveFile("system/hosts"))
        assertEquals(mapper.toHostFile("/root/.aicode/permissions.json"), mapper.resolveFile("global/permissions.json"))
    }

    @Test
    fun resolveFile_noFollowFinalLink_stillResolvesParentLinks() {
        val mapper = filesystemMapper()
        mapper.toHostFile("target").mkdirs()
        Files.createSymbolicLink(mapper.toHostFile("parent").toPath(), File("/root/workspace/target").toPath())
        Files.createSymbolicLink(mapper.toHostFile("target/leaf").toPath(), File("/missing").toPath())
        assertEquals(mapper.toHostFile("target/leaf"), mapper.resolveFile("parent/leaf", followFinalLink = false))
        assertEquals(mapper.toHostFile("/missing"), mapper.resolveFile("parent/leaf"))
    }

    @Test
    fun resolveHostFile_unmappedHostPath_preservesOriginalFile() {
        val mapper = filesystemMapper()
        val file = tmp.newFile("outside.txt")
        assertEquals(file, mapper.resolveHostFile(file))
    }

    @Test
    fun resolveFile_linkCycle_throwsInsteadOfHanging() {
        val mapper = filesystemMapper()
        Files.createSymbolicLink(mapper.toHostFile("a").toPath(), File("b").toPath())
        Files.createSymbolicLink(mapper.toHostFile("b").toPath(), File("a").toPath())
        assertThrows(FileSystemLoopException::class.java) { mapper.resolveFile("a/file") }
        assertEquals(mapper.toHostFile("a"), mapper.resolveFile("a", followFinalLink = false))
    }
}
