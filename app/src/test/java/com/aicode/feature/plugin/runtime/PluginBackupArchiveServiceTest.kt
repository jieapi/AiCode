package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.data.PluginBackupGrant
import com.aicode.feature.plugin.data.PluginBackupPermissionRepository
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginBackupDefinition
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHook
import com.aicode.feature.plugin.domain.PluginHookEvents
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class PluginBackupArchiveServiceTest {

    private val manager = mockk<PluginManager>()
    private val runtime = mockk<PluginRuntimeManager>()
    private val permissionRepository = mockk<PluginBackupPermissionRepository>()
    private val fileAccess = mockk<FileAccessProvider>()
    private val homeResolver = mockk<PathHomeResolver>()

    private val service = PluginBackupArchiveService(
        pluginManager = manager,
        runtime = runtime,
        permissionRepository = permissionRepository,
        fileAccess = fileAccess,
        homeResolver = homeResolver
    )

    private val runtimeRef = PluginRuntime(
        pluginId = PLUGIN_ID,
        type = "javascript",
        installDir = File("/tmp/$PLUGIN_ID"),
        entryFile = File("/tmp/$PLUGIN_ID/main.js"),
        command = listOf("node", "main.js")
    )

    private val hooks = listOf(
        PluginHook(
            pluginId = PLUGIN_ID,
            localId = "export",
            event = PluginHookEvents.BACKUP_EXPORT_BEFORE,
            handler = "hooks.onExportBefore",
            runtime = runtimeRef
        ),
        PluginHook(
            pluginId = PLUGIN_ID,
            localId = "import",
            event = PluginHookEvents.BACKUP_IMPORT_FINISHED,
            handler = "hooks.onImport",
            runtime = runtimeRef
        )
    )

    private val plugin = InstalledPlugin(
        record = PluginRecord(
            id = PLUGIN_ID,
            version = "1.0.0",
            enabled = true,
            installedAt = 1_000L,
            origin = "local",
            archiveSha256 = "archive-sha256",
            manifestSha256 = "manifest-sha256"
        ),
        status = PluginStatus.OK,
        manifest = mockk<PluginManifest>(relaxed = true),
        content = PluginContent(
            runtime = runtimeRef,
            backup = PluginBackupDefinition(PLUGIN_ID, "额外备份"),
            hooks = hooks
        )
    )

    private fun stub(granted: Boolean) {
        every { manager.ensureLoaded() } just Runs
        every { manager.plugins } returns MutableStateFlow(listOf(plugin))
        every { permissionRepository.grants } returns MutableStateFlow(
            if (granted) mapOf(PLUGIN_ID to PluginBackupGrant(1_000L)) else emptyMap()
        )
        coEvery { permissionRepository.isGranted(PLUGIN_ID) } returns granted
        every { homeResolver.aicodeRoot() } returns "/root/.aicode"
        every { fileAccess.deleteRecursively(any()) } just Runs
        every { fileAccess.mkdirs(any()) } just Runs
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) } returns JsonNull
    }

    @Test
    fun prepareExport_invokesOnlySelectedGrantedPlugin() = runBlocking {
        stub(granted = true)
        val sections = service.prepareExport(setOf(PLUGIN_ID))
        assertEquals(1, sections.size)
        verify {
            fileAccess.mkdirs("/root/.aicode/plugins/data/$PLUGIN_ID/backup-export")
        }
        coVerify {
            runtime.invoke(
                pluginId = PLUGIN_ID,
                version = "1.0.0",
                runtime = runtimeRef,
                handler = "hooks.onExportBefore",
                args = match { it["dir"]?.jsonPrimitive?.content == "data://backup-export" },
                method = "hook.invoke",
                timeoutMs = 60_000L
            )
        }
    }

    @Test
    fun prepareExport_skipsUnselectedOrUnauthorizedPlugins() = runBlocking {
        stub(granted = false)
        assertEquals(0, service.prepareExport(setOf(PLUGIN_ID)).size)
        stub(granted = true)
        assertEquals(0, service.prepareExport(emptySet()).size)
        coVerify(exactly = 0) { runtime.invoke(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun importTarget_requiresGrantAndCreatesDirectory() = runBlocking {
        stub(granted = false)
        assertNull(service.importTarget(PLUGIN_ID))
        stub(granted = true)
        assertEquals(
            "/root/.aicode/plugins/data/$PLUGIN_ID/backup-import",
            service.importTarget(PLUGIN_ID)
        )
    }

    @Test
    fun finishImport_invokesHookWithStatsAndCleansDirectory() = runBlocking {
        stub(granted = true)
        every { fileAccess.listFilesRecursive("/root/.aicode/plugins/data/$PLUGIN_ID/backup-import", 8) } returns
            listOf("manifest.json")
        val stats = buildJsonObject { put("mcpServers", 1) }

        service.finishImport(setOf(PLUGIN_ID), stats)

        coVerify {
            runtime.invoke(
                pluginId = PLUGIN_ID,
                version = "1.0.0",
                runtime = runtimeRef,
                handler = "hooks.onImport",
                args = match {
                    it["dir"]?.jsonPrimitive?.content == "data://backup-import" &&
                        it["files"]?.jsonPrimitive?.content == "1"
                },
                method = "hook.invoke",
                timeoutMs = 60_000L
            )
        }
        verify { fileAccess.deleteRecursively("/root/.aicode/plugins/data/$PLUGIN_ID/backup-import") }
    }

    @Test
    fun readExportFile_usesCallerLimitInsteadOfUnboundedRead() {
        val directory = "/root/.aicode/plugins/data/$PLUGIN_ID/backup-export"
        val section = PluginBackupArchiveService.Section(PLUGIN_ID, directory)
        every { fileAccess.readBytes("$directory/small.bin", 3L) } returns byteArrayOf(1, 2, 3)
        assertEquals(listOf<Byte>(1, 2, 3), service.readExportFile(section, "small.bin", 3).toList())
        verify(exactly = 0) { fileAccess.readBytes(any()) }
    }

    @Test
    fun readExportFile_propagatesBoundedReadFailure() {
        val section = PluginBackupArchiveService.Section(PLUGIN_ID, "/export")
        every { fileAccess.readBytes("/export/large.bin", 8L) } throws FileReadTooLargeException(8)
        assertThrows(FileReadTooLargeException::class.java) {
            service.readExportFile(section, "large.bin", 8)
        }
        verify(exactly = 0) { fileAccess.readBytes(any()) }
    }

    private companion object {
        const val PLUGIN_ID = "demo.backup"
    }
}
