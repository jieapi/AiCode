package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
import com.aicode.feature.plugin.data.PluginInstallTransaction
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.data.PluginRegistryStore
import com.aicode.feature.plugin.runtime.PluginDependencyPreparer
import com.aicode.feature.plugin.runtime.PluginDependencyResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var registryFile: File
    private lateinit var manager: PluginManager
    private var now = 1_000L

    private val skillName = "community.demo-kit:notes"
    private val agentName = "community.demo-kit:builder"

    @Before
    fun setUp() {
        root = File(tmp.root, "plugins")
        registryFile = File(tmp.root, "filesDir/plugin-registry.json")
        manager = newManager()
    }

    private fun newManager(preparer: PluginDependencyPreparer? = null) = PluginManager(
        root, PluginApi.VERSION, { now++ }, registryFile, dependencyPreparer = preparer
    )

    private suspend fun previewOf(bytes: ByteArray = PluginFixtures.validPackage()): PluginPreview {
        val result = manager.preview(ByteArrayInputStream(bytes))
        check(result is PluginPreviewResult.Ready) { "预览应成功: $result" }
        return result.preview
    }

    private suspend fun installValid(): PluginPreview {
        val preview = previewOf()
        val result = manager.install(preview.token)
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
    fun install_activatesAllDeclaredContributions() = runTest {
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
        assertTrue("新装默认启用", plugin.record.enabled)
        assertTrue(manager.revision > before)
    }

    @Test
    fun install_withUnknownTokenIsExpired() = runTest {
        val result = manager.install("no-such-token")
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, result.error.code)
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
    fun unknownPluginOperations_returnFalse() = runTest {
        assertFalse(manager.setEnabled("community.ghost", true))
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
        assertTrue(newManager().also { it.ensureLoaded() }.plugins.value.isEmpty())
    }

    @Test
    fun legacyRoot_isMigratedOnLoad() = runTest {
        val legacy = File(tmp.root, "filesDir/plugins")
        val old = PluginManager(legacy, PluginApi.VERSION, { now++ })
        val preview = old.preview(ByteArrayInputStream(PluginFixtures.validPackage()))
        check(preview is PluginPreviewResult.Ready) { "旧目录预览应成功: $preview" }
        check(old.install(preview.preview.token) is PluginInstallResult.Installed)
        assertTrue(File(legacy, "registry.json").isFile)

        val newRoot = File(tmp.root, "aicode/plugins")
        val registry = File(tmp.root, "filesDir/plugin-registry.json")
        val migrated = PluginManager(newRoot, PluginApi.VERSION, { now++ }, registry, legacy)
        migrated.ensureLoaded()

        assertEquals(PluginStatus.OK, migrated.plugins.value.single().status)
        assertTrue(File(newRoot, "installed/community.demo-kit/plugin.json").isFile)
        assertTrue(registry.isFile)
        assertFalse(File(newRoot, "registry.json").exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun state_survivesRestart() = runTest {
        installValid()
        manager.setEnabled("community.demo-kit", true)

        val restarted = newManager()
        assertEquals(skillName, restarted.activeSkills().single().name)
        val plugin = restarted.plugins.value.single()
        assertEquals("local", plugin.record.origin)
        assertEquals("1.0.0", plugin.record.version)
    }

    @Test
    fun reinstall_replacesPackageAndKeepsDeclaredContributionsActive() = runTest {
        installValid()
        val data = File(manager.dataDirFor(PluginFixtures.PLUGIN_ID), "keep.txt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("existing data")
        }
        val v2 = PluginFixtures.validPackage(
            manifest = PluginFixtures.manifest(version = "1.1.0"),
            skill = PluginFixtures.SKILL_MD.replace("Use this skill", "Updated skill")
        )
        val preview = previewOf(v2)
        assertEquals("1.0.0", preview.installedVersion)

        assertTrue(manager.install(preview.token) is PluginInstallResult.Installed)
        val plugin = manager.plugins.value.single()
        assertEquals("1.1.0", plugin.record.version)
        assertTrue(plugin.isActive)
        assertTrue(manager.activeSkills().single().instructions.startsWith("Updated skill"))
        assertEquals("existing data", data.readText())
        assertFalse("替换后不应遗留备份目录", File(root, "installed/community.demo-kit.replaced").exists())
    }

    @Test
    fun replace_keepsDisabledStateAndRecordsNewOrigin() = runTest {
        installValid()
        manager.setEnabled("community.demo-kit", false)

        val preview = previewOf(
            PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0"))
        )
        val origin = PluginInstallOrigin(
            kind = PluginInstallOrigin.KIND_INDEX,
            sourceId = "community.demo-index",
            sourceName = "Demo Index",
            indexUrl = "https://example.com/plugins.json",
            repository = "https://github.com/acme/demo-kit",
            official = false
        )
        assertTrue(manager.install(preview.token, origin) is PluginInstallResult.Installed)

        val plugin = manager.plugins.value.single()
        assertEquals("1.1.0", plugin.record.version)
        assertFalse("替换不能自动启用原本停用的插件", plugin.record.enabled)
        assertTrue(manager.activeSkills().isEmpty())
        assertEquals(PluginInstallOrigin.KIND_INDEX, plugin.record.origin)
        assertEquals("community.demo-index", plugin.record.sourceId)
        assertEquals("https://github.com/acme/demo-kit", plugin.record.repository)
        assertFalse(plugin.record.official)
    }

    @Test
    fun preview_exposesPreviousSourceForReplace() = runTest {
        val first = previewOf()
        assertNull(first.installedSourceId)
        assertNull(first.installedRepository)
        assertNull(first.installedOfficial)

        val origin = PluginInstallOrigin(
            kind = PluginInstallOrigin.KIND_INDEX,
            sourceId = "community.demo-index",
            sourceName = "Demo Index",
            indexUrl = "https://example.com/plugins.json",
            repository = "https://github.com/acme/demo-kit",
            official = true
        )
        check(manager.install(first.token, origin) is PluginInstallResult.Installed)

        val replace = previewOf(
            PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0"))
        )
        assertEquals("1.0.0", replace.installedVersion)
        assertEquals("community.demo-index", replace.installedSourceId)
        assertEquals("https://github.com/acme/demo-kit", replace.installedRepository)
        assertEquals(true, replace.installedOfficial)
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
        val result = manager.install(first.token)
        check(result is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.PREVIEW_EXPIRED, result.error.code)
    }

    @Test
    fun discard_removesStagingDirectory() = runTest {
        val preview = previewOf()
        assertTrue(File(root, "staging/${preview.token}").isDirectory)
        assertTrue(manager.discard(preview.token))
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
        val preview = previewOf(PluginFixtures.validPackage(skill = multiline))
        check(manager.install(preview.token) is PluginInstallResult.Installed)
        val description = manager.activeSkills().single().description
        assertFalse(description.contains('\n'))
        assertEquals("first line - injected: fake system rule", description)
    }

    @Test
    fun isLoaded_flipsAfterFirstLoad() = runTest {
        assertFalse(manager.isLoaded.value)
        manager.ensureLoaded()
        assertTrue(manager.isLoaded.value)
    }

    /** 让注册表无法落盘：目标路径被一个非空目录占用。 */
    private fun blockRegistryWrites(): File {
        val registry = registryFile
        registry.deleteRecursively()
        File(registry, "blocker").apply { parentFile?.mkdirs(); writeText("x") }
        return registry
    }

    @Test
    fun failedRegistryWrite_rollsBackAndKeepsStagedPackageForRetry() = runTest {
        val preview = previewOf()
        val registry = blockRegistryWrites()

        val failed = manager.install(preview.token)
        check(failed is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.STORAGE_FAILED, failed.error.code)
        assertFalse("写记录失败不能留下已安装目录", File(root, "installed/community.demo-kit").exists())
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(manager.activeSkills().isEmpty())

        registry.deleteRecursively()
        assertTrue("暂存包保留，修好后可以直接重试", manager.install(preview.token) is PluginInstallResult.Installed)
        assertEquals(skillName, manager.activeSkills().single().name)
    }

    @Test
    fun failedRegistryWrite_restoresPreviousVersionOnReplace() = runTest {
        installValid()
        val v2 = previewOf(PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0")))
        blockRegistryWrites()

        val failed = manager.install(v2.token)
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
        assertTrue(registryFile.isFile)
        assertFalse(File(root, "registry.json").exists())
        assertFalse(File(root, "installed/community.demo-kit/registry.json").exists())
        assertFalse(File(registryFile.parentFile, "${registryFile.name}.tmp").exists())
        assertFalse(File(registryFile.parentFile, "${registryFile.name}.install").exists())
    }

    @Test
    fun corruptRegistry_isBackedUpAndPreservesInstalledDirectories() = runTest {
        installValid()
        registryFile.writeText("{ not json")
        val restarted = newManager().also { it.ensureLoaded() }
        assertTrue(restarted.plugins.value.isEmpty())
        assertTrue(requireNotNull(registryFile.parentFile).listFiles().orEmpty().any {
            it.name.startsWith("${registryFile.name}.corrupt-")
        })
        // registry 解析失败不能触发 sweep；目录留待用户恢复或重新导入
        assertTrue(File(root, "installed/community.demo-kit").isDirectory)

        // 下一次启动时 registry 文件已不存在，但损坏备份仍在，也不能 sweep
        val restartedAgain = newManager().also { it.ensureLoaded() }
        assertTrue(File(root, "installed/community.demo-kit").isDirectory)
    }

    private fun runtimePackage(script: String = "export const value = 'old';"): ByteArray = PluginFixtures.zip(
        "plugin.json" to PluginFixtures.manifest(extra = """,
            "runtime": {
              "type": "javascript", "entry": "main.js",
              "dependencies": {"tool": "npm", "file": "package.json"}
            }
        """.trimIndent()),
        PluginFixtures.SKILL_FILE to PluginFixtures.SKILL_MD,
        PluginFixtures.AGENT_FILE to PluginFixtures.AGENT_MD,
        "main.js" to script,
        "package.json" to "{\"dependencies\":{}}"
    )

    private fun externalTarget(): File = tmp.newFolder().apply {
        File(this, "keep.txt").writeText("external content")
    }

    private fun link(path: File, target: File) {
        requireNotNull(path.parentFile).mkdirs()
        Files.createSymbolicLink(path.toPath(), target.toPath())
    }

    @Test
    fun uninstall_nestedLinks_preservesExternalTargetsAndRemovesDanglingLinks() = runTest {
        installValid()
        val external = externalTarget()
        link(File(root, "installed/community.demo-kit/node_modules/external"), external)
        link(File(manager.dataDirFor(PluginFixtures.PLUGIN_ID), "external"), external)
        link(File(manager.dataDirFor(PluginFixtures.PLUGIN_ID), "dangling"), File(tmp.root, "missing"))

        assertTrue(manager.uninstall(PluginFixtures.PLUGIN_ID))

        assertEquals("external content", File(external, "keep.txt").readText())
        assertFalse(Files.exists(manager.dataDirFor(PluginFixtures.PLUGIN_ID).toPath(), NOFOLLOW_LINKS))
        assertFalse(File(root, "installed/community.demo-kit").exists())
    }

    @Test
    fun uninstall_directoryLinks_removesOnlyLinks() = runTest {
        installValid()
        val installed = File(root, "installed/community.demo-kit")
        val externalPackage = File(tmp.root, "external-package")
        Files.move(installed.toPath(), externalPackage.toPath())
        val externalData = externalTarget()
        link(installed, externalPackage)
        val data = manager.dataDirFor(PluginFixtures.PLUGIN_ID)
        link(data, externalData)

        assertTrue(manager.uninstall(PluginFixtures.PLUGIN_ID))

        assertTrue(File(externalPackage, "plugin.json").isFile)
        assertEquals("external content", File(externalData, "keep.txt").readText())
        assertFalse(Files.exists(installed.toPath(), NOFOLLOW_LINKS))
        assertFalse(Files.exists(data.toPath(), NOFOLLOW_LINKS))
    }

    @Test
    fun uninstall_cleanupFailure_reportsFalseButKeepsPublishedStateConsistentWithRegistry() = runTest {
        installValid()
        val external = externalTarget()
        val data = File(external, PluginFixtures.PLUGIN_ID).apply { mkdirs() }
        File(data, "keep.txt").writeText("plugin data")
        link(File(root, "data"), external)

        assertFalse(manager.uninstall(PluginFixtures.PLUGIN_ID))

        assertEquals("plugin data", File(data, "keep.txt").readText())
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(PluginRegistryStore(registryFile).load().isEmpty())
    }

    @Test
    fun reinstallAfterFailedUninstall_requiresResidualDataCleanup() = runTest {
        installValid()
        val external = externalTarget()
        val residualData = File(external, PluginFixtures.PLUGIN_ID).apply { mkdirs() }
        File(residualData, "secret.txt").writeText("old data")
        val dataRoot = File(root, "data")
        link(dataRoot, external)
        assertFalse(manager.uninstall(PluginFixtures.PLUGIN_ID))
        val replacement = previewOf()

        val blocked = manager.install(replacement.token)

        check(blocked is PluginInstallResult.Failed)
        assertEquals(PluginErrorCode.STORAGE_FAILED, blocked.error.code)
        assertTrue(manager.plugins.value.isEmpty())
        assertEquals("old data", File(residualData, "secret.txt").readText())
        Files.delete(dataRoot.toPath())
        Files.move(external.toPath(), dataRoot.toPath())
        assertTrue(manager.install(replacement.token) is PluginInstallResult.Installed)
        assertFalse(manager.dataDirFor(PluginFixtures.PLUGIN_ID).exists())
    }

    @Test
    fun newInstall_cleansResidualDataLinksBeforeRegisteringPackage() = runTest {
        val external = externalTarget()
        val data = manager.dataDirFor(PluginFixtures.PLUGIN_ID)
        link(File(data, "external"), external)
        File(data, "secret.txt").writeText("old data")
        val preview = previewOf()

        assertTrue(manager.install(preview.token) is PluginInstallResult.Installed)

        assertFalse(data.exists())
        assertEquals("external content", File(external, "keep.txt").readText())
    }

    @Test
    fun startup_cleanupLinksInStagingBackupsAndOrphans_preservesExternalContents() = runTest {
        installValid()
        val external = externalTarget()
        val staging = File(root, "staging")
        assertTrue(staging.delete())
        link(staging, external)
        val orphan = File(root, "installed/community.orphan")
        val nested = File(root, "installed/community.nested/external")
        val backup = File(root, "installed/community.demo-kit.replaced")
        val dangling = File(root, "installed/community.dangling")
        link(orphan, external)
        link(nested, external)
        link(backup, external)
        link(dangling, File(tmp.root, "missing"))

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertEquals("external content", File(external, "keep.txt").readText())
        listOf(staging, orphan, requireNotNull(nested.parentFile), backup, dangling).forEach {
            assertFalse(Files.exists(it.toPath(), NOFOLLOW_LINKS))
        }
    }

    @Test
    fun discard_stagingDirectoryLink_preservesExternalPackage() = runTest {
        val preview = previewOf()
        val staging = File(root, "staging/${preview.token}")
        val external = File(tmp.root, "external-staging")
        Files.move(staging.toPath(), external.toPath())
        link(staging, external)

        assertTrue(manager.discard(preview.token))

        assertTrue(File(external, "plugin.json").isFile)
        assertFalse(Files.exists(staging.toPath(), NOFOLLOW_LINKS))
        assertTrue(manager.install(preview.token) is PluginInstallResult.Failed)
    }

    @Test
    fun failedStagingCleanup_reportsFailureAndKeepsPreviewForRetry() = runTest {
        val preview = previewOf()
        val staging = File(root, "staging")
        val external = File(tmp.root, "external-staging-root")
        Files.move(staging.toPath(), external.toPath())
        link(staging, external)

        assertFalse(manager.discard(preview.token))
        val next = manager.preview(ByteArrayInputStream(PluginFixtures.validPackage()))
        assertTrue(PluginErrorCode.STORAGE_FAILED in errorCodes(next))
        assertTrue(File(external, "${preview.token}/plugin.json").isFile)

        Files.delete(staging.toPath())
        Files.move(external.toPath(), staging.toPath())
        assertTrue(manager.install(preview.token) is PluginInstallResult.Installed)
    }

    @Test
    fun replacingPackage_cleansOldDependenciesWithoutFollowingLinks() = runTest {
        installValid()
        val external = externalTarget()
        link(File(root, "installed/community.demo-kit/node_modules/external"), external)
        val replacement = previewOf(PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0")))

        assertTrue(manager.install(replacement.token) is PluginInstallResult.Installed)

        assertEquals("external content", File(external, "keep.txt").readText())
        assertFalse(File(root, "installed/community.demo-kit.replaced").exists())
        assertEquals("1.1.0", newManager().also { it.ensureLoaded() }.plugins.value.single().record.version)
    }

    @Test
    fun dependenciesReady_isPersistedAndSurvivesRestart() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = newManager(preparer)
        val preview = previewOf(runtimePackage())
        assertTrue(manager.install(preview.token) is PluginInstallResult.Installed)
        val hash = PluginPackageReader.sha256Hex(File(root, "installed/community.demo-kit/package.json").readBytes())
        coEvery { preparer.prepare(any()) } returns PluginDependencyResult.Ready(hash)

        assertEquals(PluginDependencyResult.Ready(hash), manager.prepareDependencies(PluginFixtures.PLUGIN_ID))

        val persisted = PluginRegistryStore(registryFile).load().single()
        assertTrue(persisted.dependenciesReady)
        assertEquals(hash, persisted.dependenciesHash)
        val restarted = newManager().also { it.ensureLoaded() }.plugins.value.single().record
        assertTrue(restarted.dependenciesReady)
        assertEquals(hash, restarted.dependenciesHash)
    }

    @Test
    fun dependencyFailure_clearsPersistedReadyState() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = newManager(preparer)
        val preview = previewOf(runtimePackage())
        check(manager.install(preview.token) is PluginInstallResult.Installed)
        val hash = PluginPackageReader.sha256Hex(File(root, "installed/community.demo-kit/package.json").readBytes())
        coEvery { preparer.prepare(any()) } returns PluginDependencyResult.Ready(hash)
        manager.prepareDependencies(PluginFixtures.PLUGIN_ID)
        coEvery { preparer.prepare(any()) } returns PluginDependencyResult.Failed("failed")

        assertTrue(manager.prepareDependencies(PluginFixtures.PLUGIN_ID) is PluginDependencyResult.Failed)

        val record = newManager().also { it.ensureLoaded() }.plugins.value.single().record
        assertFalse(record.dependenciesReady)
        assertNull(record.dependenciesHash)
    }

    @Test
    fun dependencyStateWriteFailure_doesNotReportReadyOrPublishUnsavedState() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = newManager(preparer)
        val preview = previewOf(runtimePackage())
        check(manager.install(preview.token) is PluginInstallResult.Installed)
        coEvery { preparer.prepare(any()) } returns PluginDependencyResult.Ready("hash")
        blockRegistryWrites()

        assertTrue(manager.prepareDependencies(PluginFixtures.PLUGIN_ID) is PluginDependencyResult.Failed)

        assertFalse(manager.plugins.value.single().record.dependenciesReady)
        assertNull(manager.plugins.value.single().record.dependenciesHash)
    }

    @Test
    fun oldDependencyResult_doesNotMarkSameManifestReplacementReady() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = newManager(preparer)
        val first = previewOf(runtimePackage())
        check(manager.install(first.token) is PluginInstallResult.Installed)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { preparer.prepare(any()) } coAnswers {
            started.complete(Unit)
            release.await()
            PluginDependencyResult.Ready("old-hash")
        }
        val preparation = async { manager.prepareDependencies(PluginFixtures.PLUGIN_ID) }
        started.await()
        val replacement = previewOf(runtimePackage("export const value = 'new';"))
        check(manager.install(replacement.token) is PluginInstallResult.Installed)
        val newRecord = manager.plugins.value.single().record
        release.complete(Unit)

        assertTrue(preparation.await() is PluginDependencyResult.Failed)
        assertEquals(newRecord, manager.plugins.value.single().record)
        val restarted = newManager().also { it.ensureLoaded() }.plugins.value.single().record
        assertFalse(restarted.dependenciesReady)
        assertNull(restarted.dependenciesHash)
    }

    @Test
    fun oldDependencyFailure_doesNotClearIdenticalReinstallReadyState() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = PluginManager(root, PluginApi.VERSION, { 1_000L }, registryFile, dependencyPreparer = preparer)
        val bytes = runtimePackage()
        val first = previewOf(bytes)
        check(manager.install(first.token) is PluginInstallResult.Installed)
        val oldRecord = manager.plugins.value.single().record
        val hash = PluginPackageReader.sha256Hex(File(root, "installed/community.demo-kit/package.json").readBytes())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { preparer.prepare(any()) } coAnswers {
            started.complete(Unit)
            release.await()
            PluginDependencyResult.Failed("old failure")
        }
        val preparation = async { manager.prepareDependencies(PluginFixtures.PLUGIN_ID) }
        started.await()
        val replacement = previewOf(bytes)
        check(manager.install(replacement.token) is PluginInstallResult.Installed)
        val newRecord = manager.plugins.value.single().record
        assertEquals(oldRecord.installedAt, newRecord.installedAt)
        assertEquals(oldRecord.archiveSha256, newRecord.archiveSha256)
        assertFalse(oldRecord.installRevision == newRecord.installRevision)
        coEvery { preparer.prepare(any()) } returns PluginDependencyResult.Ready(hash)
        assertTrue(manager.prepareDependencies(PluginFixtures.PLUGIN_ID) is PluginDependencyResult.Ready)
        release.complete(Unit)

        assertTrue(preparation.await() is PluginDependencyResult.Failed)
        val restarted = newManager().also { it.ensureLoaded() }.plugins.value.single().record
        assertTrue(restarted.dependenciesReady)
        assertEquals(hash, restarted.dependenciesHash)
        assertEquals(newRecord.installRevision, restarted.installRevision)
    }

    @Test
    fun oldDependencyResult_afterUninstallDoesNotReintroduceRecord() = runTest {
        val preparer = mockk<PluginDependencyPreparer>()
        manager = newManager(preparer)
        val first = previewOf(runtimePackage())
        check(manager.install(first.token) is PluginInstallResult.Installed)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { preparer.prepare(any()) } coAnswers {
            started.complete(Unit)
            release.await()
            PluginDependencyResult.Ready("old-hash")
        }
        val preparation = async { manager.prepareDependencies(PluginFixtures.PLUGIN_ID) }
        started.await()
        assertTrue(manager.uninstall(PluginFixtures.PLUGIN_ID))
        release.complete(Unit)

        assertTrue(preparation.await() is PluginDependencyResult.Failed)
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(PluginRegistryStore(registryFile).load().isEmpty())
    }

    private suspend fun checkSameManifestRecovery(committed: Boolean) {
        val oldScript = "export const value = 'old';"
        val first = previewOf(runtimePackage(oldScript))
        check(manager.install(first.token) is PluginInstallResult.Installed)
        val oldRecord = manager.plugins.value.single().record
        val newScript = "export const value = 'new';"
        val bytes = runtimePackage(newScript)
        val next = previewOf(bytes)
        val target = File(root, "installed/community.demo-kit")
        val backup = File(root, "installed/community.demo-kit.replaced")
        val staging = File(root, "staging/${next.token}")
        assertEquals(File(target, "plugin.json").readText(), File(staging, "plugin.json").readText())
        val newRecord = oldRecord.copy(
            archiveSha256 = PluginPackageReader.sha256Hex(bytes),
            installRevision = "replacement-revision"
        )
        val store = PluginRegistryStore(registryFile)
        store.saveInstallTransaction(PluginInstallTransaction(oldRecord.id, newRecord.installRevision!!, true))
        assertTrue(File(registryFile.parentFile, "${registryFile.name}.install").isFile)
        assertFalse(File(root, "registry.json.install").exists())
        Files.move(target.toPath(), backup.toPath())
        Files.move(staging.toPath(), target.toPath())
        if (committed) store.save(listOf(newRecord))

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertEquals(if (committed) newRecord else oldRecord, store.load().single())
        assertEquals(if (committed) newScript else oldScript, File(target, "main.js").readText())
        assertFalse(backup.exists())
        assertNull(store.loadInstallTransaction())
        assertEquals(PluginStatus.OK, newManager().also { it.ensureLoaded() }.plugins.value.single().status)
    }

    @Test
    fun sameManifestReplacement_beforeRegistryCommit_restoresOldScript() = runTest {
        checkSameManifestRecovery(committed = false)
    }

    @Test
    fun sameManifestReplacement_afterRegistryCommit_keepsNewScript() = runTest {
        checkSameManifestRecovery(committed = true)
    }

    @Test
    fun transactionBeforeFirstMove_keepsCurrentInstallation() = runTest {
        installValid()
        val store = PluginRegistryStore(registryFile)
        store.saveInstallTransaction(PluginInstallTransaction(PluginFixtures.PLUGIN_ID, "not-committed", true))

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertEquals(skillName, restarted.activeSkills().single().name)
        assertNull(store.loadInstallTransaction())
    }

    @Test
    fun interruptedNewInstall_beforeRegistryCommit_removesUncommittedPackage() = runTest {
        val preview = previewOf()
        val staging = File(root, "staging/${preview.token}")
        val target = File(root, "installed/community.demo-kit")
        requireNotNull(target.parentFile).mkdirs()
        val store = PluginRegistryStore(registryFile)
        store.saveInstallTransaction(PluginInstallTransaction(PluginFixtures.PLUGIN_ID, "not-committed", false))
        Files.move(staging.toPath(), target.toPath())

        val restarted = newManager().also { it.ensureLoaded() }

        assertTrue(restarted.plugins.value.isEmpty())
        assertFalse(target.exists())
        assertNull(store.loadInstallTransaction())
    }

    @Test
    fun interruptedNewInstall_afterRegistryCommit_preservesPackage() = runTest {
        installValid()
        val record = manager.plugins.value.single().record
        val store = PluginRegistryStore(registryFile)
        store.saveInstallTransaction(PluginInstallTransaction(record.id, record.installRevision!!, false))

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertEquals(record.installRevision, restarted.plugins.value.single().record.installRevision)
        assertNull(store.loadInstallTransaction())
    }

    @Test
    fun committedInstall_cleanupFailure_doesNotRollbackAndIsRetriedOnRestart() = runTest {
        installValid()
        val replacement = previewOf(PluginFixtures.validPackage(manifest = PluginFixtures.manifest(version = "1.1.0")))
        val failingStore = spyk(PluginRegistryStore(registryFile))
        every { failingStore.clearInstallTransaction() } throws IOException("cleanup failed")
        PluginManager::class.java.getDeclaredField("store").apply {
            isAccessible = true
            set(manager, failingStore)
        }

        assertTrue(manager.install(replacement.token) is PluginInstallResult.Installed)
        assertEquals("1.1.0", manager.plugins.value.single().record.version)
        assertTrue(File(root, "installed/community.demo-kit/plugin.json").readText().contains("1.1.0"))
        verify(exactly = 1) { failingStore.clearInstallTransaction() }
        assertTrue(File(registryFile.parentFile, "${registryFile.name}.install").isFile)

        val store = PluginRegistryStore(registryFile)
        assertEquals("1.1.0", store.load().single().version)
        assertEquals(store.load().single().installRevision, store.loadInstallTransaction()!!.revision)
        val restarted = newManager().also { it.ensureLoaded() }
        assertEquals("1.1.0", restarted.plugins.value.single().record.version)
        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertNull(store.loadInstallTransaction())
    }

    @Test
    fun unreadableTransaction_preservesBackupsAndOrphansForRetry() = runTest {
        installValid()
        val backup = File(root, "installed/community.demo-kit.replaced")
        val orphan = File(root, "installed/community.orphan").apply { mkdirs() }
        Files.move(File(root, "installed/community.demo-kit").toPath(), backup.toPath())
        val transaction = File(registryFile.parentFile, "${registryFile.name}.install")
        transaction.writeText("{ invalid")

        val restarted = newManager().also { it.ensureLoaded() }
        restarted.refresh()

        assertTrue(backup.isDirectory)
        assertTrue(orphan.isDirectory)
        assertTrue(transaction.isFile)
    }

    @Test
    fun legacyRegistryWithoutRevision_loadsAndCanBeReinstalled() = runTest {
        installValid()
        val record = manager.plugins.value.single().record
        registryFile.writeText("""
            {"schema":4,"plugins":[{
              "id":"${record.id}","version":"${record.version}","enabled":true,
              "installedAt":${record.installedAt},"origin":"local",
              "archiveSha256":"${record.archiveSha256}","manifestSha256":"${record.manifestSha256}"
            }]}
        """.trimIndent())
        manager = newManager().also { it.ensureLoaded() }
        assertEquals(PluginStatus.OK, manager.plugins.value.single().status)
        assertNull(manager.plugins.value.single().record.installRevision)

        val next = previewOf()
        assertTrue(manager.install(next.token) is PluginInstallResult.Installed)

        assertTrue(manager.plugins.value.single().record.installRevision != null)
        assertEquals(PluginStatus.OK, newManager().also { it.ensureLoaded() }.plugins.value.single().status)
    }

    @Test
    fun legacySameManifestReplacement_withoutTransaction_prefersBackup() = runTest {
        installValid()
        val installed = File(root, "installed/community.demo-kit")
        val backup = File(root, "installed/community.demo-kit.replaced")
        Files.move(installed.toPath(), backup.toPath())
        backup.copyRecursively(installed)
        File(installed, PluginFixtures.SKILL_FILE).writeText(PluginFixtures.SKILL_MD.replace("Use this skill", "Changed skill"))

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals("Use this skill to take notes.", restarted.activeSkills().single().instructions)
        assertFalse(backup.exists())
    }

    @Test
    fun interruptedUpgrade_afterMovingOldBeforeMovingNew_restoresBackup() = runTest {
        installValid()
        val installed = File(root, "installed/community.demo-kit")
        val backup = File(root, "installed/community.demo-kit.replaced")
        val store = PluginRegistryStore(registryFile)
        store.saveInstallTransaction(PluginInstallTransaction(PluginFixtures.PLUGIN_ID, "not-committed", true))
        Files.move(installed.toPath(), backup.toPath())

        val restarted = newManager().also { it.ensureLoaded() }

        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertTrue(installed.isDirectory)
        assertFalse(backup.exists())
        assertNull(store.loadInstallTransaction())
    }

    @Test
    fun interruptedInstall_restoresReplacedDirectory() = runTest {
        installValid()
        val installed = File(root, "installed/community.demo-kit")
        val backup = File(root, "installed/community.demo-kit.replaced")
        assertTrue(installed.renameTo(backup))

        val restarted = newManager().also { it.ensureLoaded() }
        assertEquals(PluginStatus.OK, restarted.plugins.value.single().status)
        assertTrue(installed.isDirectory)
        assertFalse(backup.exists())
    }
}
