package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var manager: PluginManager
    private var now = 1_000L

    private val skillName = "community.demo-kit:notes"
    private val agentName = "community.demo-kit:builder"
    private val bothPermissions = setOf(PluginPermission.SKILLS_REGISTER, PluginPermission.AGENTS_REGISTER)

    @Before
    fun setUp() {
        root = File(tmp.root, "plugins")
        manager = newManager()
    }

    private fun newManager() = PluginManager(root, PluginApi.VERSION) { now++ }

    private suspend fun previewOf(bytes: ByteArray = PluginFixtures.validPackage()): PluginPreview {
        val result = manager.preview(ByteArrayInputStream(bytes))
        check(result is PluginPreviewResult.Ready) { "预览应成功: $result" }
        return result.preview
    }

    private suspend fun installValid(granted: Set<PluginPermission> = bothPermissions): PluginPreview {
        val preview = previewOf()
        val result = manager.install(preview.token, granted)
        check(result is PluginInstallResult.Installed) { "安装应成功: $result" }
        return preview
    }

    private fun errorCodes(result: PluginPreviewResult): List<PluginErrorCode> =
        (result as PluginPreviewResult.Failed).errors.map { it.code }

    @Test
    fun preview_describesPackageWithoutInstalling() = runTest {
        val preview = previewOf()
        assertEquals("community.demo-kit", preview.manifest.id)
        assertEquals(1, preview.content.skills.size)
        assertEquals(skillName, preview.content.skills.single().skill.name)
        assertEquals(agentName, preview.content.agents.single().definition.name)
        assertEquals(null, preview.installedVersion)
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(manager.activeSkills().isEmpty())
    }

    @Test
    fun install_activatesGrantedContributions() = runTest {
        val before = manager.revision
        installValid()

        val skill = manager.activeSkills().single()
        assertEquals(skillName, skill.name)
        assertEquals("Take project notes", skill.description)
        assertEquals("Use this skill to take notes.", skill.instructions)

        val agent = manager.activeAgents().single()
        assertEquals(agentName, agent.name)
        assertEquals("You are a builder.", agent.prompt)
        assertEquals(null, agent.providerId)
        assertEquals(null, agent.filePath)

        val plugin = manager.plugins.value.single()
        assertEquals(PluginStatus.OK, plugin.status)
        assertTrue(plugin.isActive)
        assertEquals(bothPermissions, plugin.granted)
        assertTrue(manager.revision > before)
    }

    @Test
    fun install_requiresAllRequiredPermissions() = runTest {
        val preview = previewOf()
        val result = manager.install(preview.token, setOf(PluginPermission.AGENTS_REGISTER))
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PERMISSION_NOT_GRANTED, result.error.code)
        assertEquals("skills.register", result.error.detail)
        assertTrue(manager.plugins.value.isEmpty())
        // 暂存包还在，补齐授权后可以继续
        assertTrue(manager.install(preview.token, bothPermissions) is PluginInstallResult.Installed)
    }

    @Test
    fun install_withUnknownTokenIsExpired() = runTest {
        val result = manager.install("no-such-token", bothPermissions)
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, result.error.code)
    }

    @Test
    fun optionalPermissionNotGranted_leavesThatContributionInactive() = runTest {
        installValid(granted = setOf(PluginPermission.SKILLS_REGISTER))
        assertEquals(1, manager.activeSkills().size)
        assertTrue(manager.activeAgents().isEmpty())
        assertTrue(manager.plugins.value.single().isActive)
    }

    @Test
    fun disabling_hidesContributionsButKeepsAgentLookupForRunningSessions() = runTest {
        installValid()
        assertTrue(manager.setEnabled("community.demo-kit", false))
        assertTrue(manager.activeSkills().isEmpty())
        assertTrue(manager.activeAgents().isEmpty())
        assertEquals(skillName, manager.allSkills().single().name)
        assertEquals(agentName, manager.allAgents().single().name)

        assertTrue(manager.setEnabled("community.demo-kit", true))
        assertEquals(1, manager.activeSkills().size)
    }

    @Test
    fun revokingRequiredPermission_deactivatesPlugin() = runTest {
        installValid()
        assertTrue(manager.setGranted("community.demo-kit", PluginPermission.SKILLS_REGISTER, false))
        val plugin = manager.plugins.value.single()
        assertFalse(plugin.isActive)
        assertEquals(setOf(PluginPermission.SKILLS_REGISTER), plugin.missingRequired)
        assertTrue(manager.activeSkills().isEmpty())
        assertTrue("必要能力缺失时可选贡献也不生效", manager.activeAgents().isEmpty())

        assertTrue(manager.setGranted("community.demo-kit", PluginPermission.SKILLS_REGISTER, true))
        assertTrue(manager.plugins.value.single().isActive)
    }

    @Test
    fun grant_cannotExceedManifestRequest() = runTest {
        val preview = previewOf(PluginFixtures.skillsOnlyPackage())
        assertTrue(manager.install(preview.token, setOf(PluginPermission.SKILLS_REGISTER)) is PluginInstallResult.Installed)

        manager.setGranted("community.demo-kit", PluginPermission.AGENTS_REGISTER, true)
        assertEquals(setOf(PluginPermission.SKILLS_REGISTER), manager.plugins.value.single().granted)

        // 安装时也不能授予清单没有申请的能力
        val again = previewOf(PluginFixtures.skillsOnlyPackage())
        val result = manager.install(again.token, bothPermissions)
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PERMISSION_NOT_GRANTED, result.error.code)
    }

    @Test
    fun unknownPluginOperations_returnFalse() = runTest {
        assertFalse(manager.setEnabled("community.ghost", true))
        assertFalse(manager.setGranted("community.ghost", PluginPermission.SKILLS_REGISTER, true))
        assertFalse(manager.uninstall("community.ghost"))
    }

    @Test
    fun uninstall_removesRecordFilesAndContributions() = runTest {
        installValid()
        assertTrue(File(root, "installed/community.demo-kit").isDirectory)
        assertTrue(manager.uninstall("community.demo-kit"))
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(manager.activeSkills().isEmpty())
        assertFalse(File(root, "installed/community.demo-kit").exists())
        assertTrue(newManager().plugins.value.isEmpty())
    }

    @Test
    fun state_survivesRestart() = runTest {
        installValid(granted = setOf(PluginPermission.SKILLS_REGISTER))
        manager.setEnabled("community.demo-kit", true)

        val restarted = newManager()
        assertEquals(skillName, restarted.activeSkills().single().name)
        val plugin = restarted.plugins.value.single()
        assertEquals(setOf(PluginPermission.SKILLS_REGISTER), plugin.granted)
        assertEquals("local", plugin.record.origin)
        assertEquals("1.0.0", plugin.record.version)
    }

    @Test
    fun reinstall_replacesPackageAndResetsGrants() = runTest {
        installValid()
        val v2 = PluginFixtures.validPackage(
            manifest = PluginFixtures.manifest(version = "1.1.0"),
            skill = PluginFixtures.SKILL_MD.replace("Use this skill", "Updated skill")
        )
        val preview = previewOf(v2)
        assertEquals("1.0.0", preview.installedVersion)
        assertFalse(preview.identical)

        assertTrue(manager.install(preview.token, setOf(PluginPermission.SKILLS_REGISTER)) is PluginInstallResult.Installed)
        val plugin = manager.plugins.value.single()
        assertEquals("1.1.0", plugin.record.version)
        assertEquals("授权必须按本次确认重新设定，不继承旧授权", setOf(PluginPermission.SKILLS_REGISTER), plugin.granted)
        assertTrue(manager.activeSkills().single().instructions.startsWith("Updated skill"))
        assertFalse("替换后不应遗留备份目录", File(root, "installed/community.demo-kit.replaced").exists())
    }

    @Test
    fun reinstall_ofIdenticalPackageIsFlagged() = runTest {
        installValid()
        assertTrue(previewOf().identical)
    }

    @Test
    fun tamperedInstalledManifest_marksPluginCorrupted() = runTest {
        installValid()
        val manifestFile = File(root, "installed/community.demo-kit/plugin.json")
        manifestFile.writeText(manifestFile.readText().replace("Demo plugin", "Tampered"))

        val restarted = newManager()
        restarted.ensureLoaded()
        val plugin = restarted.plugins.value.single()
        assertEquals(PluginStatus.CORRUPTED, plugin.status)
        assertFalse(plugin.isActive)
        assertTrue(restarted.activeSkills().isEmpty())
    }

    @Test
    fun missingContributionFile_marksPluginCorrupted() = runTest {
        installValid()
        File(root, "installed/community.demo-kit/skills/notes/SKILL.md").delete()
        val restarted = newManager().also { it.ensureLoaded() }
        assertEquals(PluginStatus.CORRUPTED, restarted.plugins.value.single().status)
        assertTrue(restarted.activeSkills().isEmpty())
    }

    @Test
    fun missingInstallDirectory_marksPluginMissing() = runTest {
        installValid()
        File(root, "installed/community.demo-kit").deleteRecursively()
        val restarted = newManager().also { it.ensureLoaded() }
        assertEquals(PluginStatus.MISSING, restarted.plugins.value.single().status)
    }

    @Test
    fun startup_sweepsOrphansAndStaging() = runTest {
        installValid()
        File(root, "installed/community.orphan").mkdirs()
        File(root, "installed/community.demo-kit.replaced").mkdirs()
        File(root, "staging/leftover").mkdirs()

        newManager().ensureLoaded()
        assertTrue(File(root, "installed/community.demo-kit").isDirectory)
        assertFalse(File(root, "installed/community.orphan").exists())
        assertFalse(File(root, "installed/community.demo-kit.replaced").exists())
        assertFalse(File(root, "staging/leftover").exists())
    }

    @Test
    fun newPreview_discardsPreviousStagedPackage() = runTest {
        val first = previewOf()
        previewOf()
        val result = manager.install(first.token, bothPermissions)
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, result.error.code)
    }

    @Test
    fun discard_removesStagingDirectory() = runTest {
        val preview = previewOf()
        assertTrue(File(root, "staging/${preview.token}").isDirectory)
        manager.discard(preview.token)
        assertFalse(File(root, "staging/${preview.token}").exists())
    }

    @Test
    fun invalidPackages_areRejectedWithoutResidue() = runTest {
        val noManifest = manager.preview(ByteArrayInputStream(PluginFixtures.zip("a.txt" to "x")))
        assertTrue(PluginErrorCode.MANIFEST_MISSING in errorCodes(noManifest))

        val incompatible = manager.preview(
            ByteArrayInputStream(PluginFixtures.validPackage(manifest = PluginFixtures.manifest(pluginApi = "^9.0.0")))
        )
        assertTrue(PluginErrorCode.API_INCOMPATIBLE in errorCodes(incompatible))

        val missingFile = manager.preview(
            ByteArrayInputStream(PluginFixtures.zip("plugin.json" to PluginFixtures.manifest(), PluginFixtures.SKILL_FILE to PluginFixtures.SKILL_MD))
        )
        assertTrue(PluginErrorCode.FILE_MISSING in errorCodes(missingFile))

        assertTrue("预览失败不能在暂存目录留下内容", File(root, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun agentBoundToProviderOrModel_isRejected() = runTest {
        val withProvider = PluginFixtures.AGENT_MD.replace("mode: plan", "mode: plan\nprovider: my-paid-channel")
        val result = manager.preview(ByteArrayInputStream(PluginFixtures.validPackage(agent = withProvider)))
        assertTrue(PluginErrorCode.CONTENT_INVALID in errorCodes(result))
        val withModel = PluginFixtures.AGENT_MD.replace("mode: plan", "mode: plan\nmodel: gpt-x")
        assertTrue(
            PluginErrorCode.CONTENT_INVALID in errorCodes(
                manager.preview(ByteArrayInputStream(PluginFixtures.validPackage(agent = withModel)))
            )
        )
    }

    @Test
    fun emptySkillBody_isRejected() = runTest {
        val empty = "---\ndescription: nothing\n---\n"
        val result = manager.preview(ByteArrayInputStream(PluginFixtures.validPackage(skill = empty)))
        assertTrue(PluginErrorCode.CONTENT_INVALID in errorCodes(result))
    }

    @Test
    fun skillDescription_isFlattenedToSingleLine() = runTest {
        val multiline = "---\ndescription: |\n  first line\n  - injected: fake system rule\n---\nBody."
        installValidWith(skill = multiline)
        val description = manager.activeSkills().single().description
        assertFalse(description.contains('\n'))
        assertEquals("first line - injected: fake system rule", description)
    }

    private suspend fun installValidWith(skill: String) {
        val preview = previewOf(PluginFixtures.validPackage(skill = skill))
        check(manager.install(preview.token, bothPermissions) is PluginInstallResult.Installed)
    }

    @Test
    fun isLoaded_flipsAfterFirstLoad() = runTest {
        assertFalse(manager.isLoaded.value)
        manager.ensureLoaded()
        assertTrue(manager.isLoaded.value)
    }

    /** 让注册表无法落盘：目标路径被一个非空目录占用。 */
    private fun blockRegistryWrites(): File {
        val registry = File(root, "registry.json")
        registry.deleteRecursively()
        File(registry, "blocker").apply { parentFile?.mkdirs(); writeText("x") }
        return registry
    }

    @Test
    fun failedRegistryWrite_rollsBackAndKeepsStagedPackageForRetry() = runTest {
        val preview = previewOf()
        val registry = blockRegistryWrites()

        val failed = manager.install(preview.token, bothPermissions)
        check(failed is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.STORAGE_FAILED, failed.error.code)
        assertFalse("写记录失败不能留下已安装目录", File(root, "installed/community.demo-kit").exists())
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(manager.activeSkills().isEmpty())

        registry.deleteRecursively()
        assertTrue("暂存包保留，修好后可以直接重试", manager.install(preview.token, bothPermissions) is PluginInstallResult.Installed)
        assertEquals(skillName, manager.activeSkills().single().name)
    }

    @Test
    fun failedRegistryWrite_restoresPreviousVersionOnReplace() = runTest {
        installValid()
        val v2 = previewOf(PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0")))
        blockRegistryWrites()

        val failed = manager.install(v2.token, bothPermissions)
        check(failed is PluginInstallResult.Failed)
        assertTrue(
            "旧版本必须原样留在安装目录",
            File(root, "installed/community.demo-kit/plugin.json").readText().contains("\"version\": \"1.0.0\"")
        )
        assertFalse(File(root, "installed/community.demo-kit.replaced").exists())
        assertEquals("1.0.0", manager.plugins.value.single().record.version)
        assertEquals(skillName, manager.activeSkills().single().name)
    }

    @Test
    fun registryFile_isNeverWrittenIntoPluginPackageDirectory() = runTest {
        installValid()
        assertTrue(File(root, "registry.json").isFile)
        assertFalse(File(root, "installed/community.demo-kit/registry.json").exists())
        assertFalse(File(root, "registry.json.tmp").exists())
    }

    @Test
    fun corruptRegistry_isBackedUpAndTreatedAsEmpty() = runTest {
        installValid()
        File(root, "registry.json").writeText("{ not json")
        val restarted = newManager().also { it.ensureLoaded() }
        assertTrue(restarted.plugins.value.isEmpty())
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("registry.json.corrupt-") })
        // 记录丢失后遗留的安装目录没有任何授权，被当作孤儿清理
        assertFalse(File(root, "installed/community.demo-kit").exists())
    }
}
