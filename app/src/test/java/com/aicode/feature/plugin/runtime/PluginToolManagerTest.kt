package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.overlay.PluginOverlayManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.Test

class PluginToolManagerTest {

    private val runtime = mockk<PluginRuntimeManager>(relaxed = true)
    private val terminalRunner = mockk<PluginTerminalRunner>(relaxed = true)
    private val modelService = mockk<PluginModelService>(relaxed = true)
    private val overlayManager = mockk<PluginOverlayManager>(relaxed = true)
    private val manager = PluginToolManager(
        context = mockk<Context>(),
        pluginManager = mockk<PluginManager>(),
        toolRegistry = ToolRegistry(),
        runtime = runtime,
        terminalRunner = terminalRunner,
        modelService = modelService,
        overlayManager = overlayManager
    )
    private val syncMethod = PluginToolManager::class.java.getDeclaredMethod(
        "sync",
        List::class.java
    ).apply { isAccessible = true }
    private val record = PluginRecord(
        id = "demo.overlay",
        version = "1.0.0",
        enabled = true,
        installedAt = 1_000L,
        origin = "local",
        archiveSha256 = "archive-sha256",
        manifestSha256 = "manifest-sha256"
    )

    private fun plugin(record: PluginRecord = this.record, withRuntime: Boolean = true) = InstalledPlugin(
        record = record,
        status = PluginStatus.OK,
        manifest = mockk<PluginManifest> {
            every { name } returns LocalizedText.single(record.id)
        },
        content = PluginContent(runtime = if (withRuntime) mockk<PluginRuntime>() else null)
    )

    private fun sync(vararg plugins: InstalledPlugin) {
        syncMethod.invoke(manager, plugins.toList())
    }

    @Test
    fun sync_versionUpdateClosesRuntimeBeforeReloadingOverlays() {
        val updated = record.copy(version = "1.1.0")
        sync(plugin())

        sync(plugin(updated))
        sync(plugin(updated))

        verifySequence {
            runtime.closeOtherVersions(record.id, "1.1.0")
            overlayManager.reloadPlugin(record.id)
        }
        verify { terminalRunner wasNot Called }
    }

    @Test
    fun sync_sameVersionReinstallReloadsWhenInstalledAtChanges() {
        sync(plugin())

        sync(plugin(record.copy(installedAt = 2_000L)))

        verifySequence {
            runtime.closePlugin(record.id)
            overlayManager.reloadPlugin(record.id)
        }
    }

    @Test
    fun sync_sameVersionRepackReloadsWhenArchiveChanges() {
        sync(plugin())

        sync(plugin(record.copy(archiveSha256 = "repacked-archive-sha256")))

        verifySequence {
            runtime.closePlugin(record.id)
            overlayManager.reloadPlugin(record.id)
        }
    }

    @Test
    fun sync_newInstallationDoesNotReload() {
        sync()

        sync(plugin())

        verify {
            runtime wasNot Called
            terminalRunner wasNot Called
            overlayManager wasNot Called
        }
    }

    @Test
    fun sync_refreshWithSameRevisionDoesNotReload() {
        sync(plugin())

        sync(plugin())

        verify {
            runtime wasNot Called
            terminalRunner wasNot Called
            overlayManager wasNot Called
        }
    }

    @Test
    fun sync_dependencyStateChangeDoesNotReload() {
        sync(plugin())

        sync(plugin(record.copy(dependenciesReady = true, dependenciesHash = "dependencies-sha256")))

        verify {
            runtime wasNot Called
            terminalRunner wasNot Called
            overlayManager wasNot Called
        }
    }

    @Test
    fun sync_otherPluginUpdateLeavesUnchangedPluginAlone() {
        val other = record.copy(id = "other.overlay")
        sync(plugin(), plugin(other))

        sync(plugin(), plugin(other.copy(version = "1.1.0")))

        verifySequence {
            runtime.closeOtherVersions(other.id, "1.1.0")
            overlayManager.reloadPlugin(other.id)
        }
        verify { terminalRunner wasNot Called }
    }

    @Test
    fun sync_disablingPluginClosesRuntimeTerminalAndOverlays() {
        sync(plugin())

        sync(plugin(record.copy(enabled = false)))
        sync(plugin(record.copy(enabled = false)))

        verifySequence {
            runtime.closePlugin(record.id)
            terminalRunner.closePlugin(record.id)
            overlayManager.closePlugin(record.id)
        }
    }

    @Test
    fun sync_uninstallingPluginClosesRuntimeTerminalAndOverlays() {
        sync(plugin())

        sync()
        sync()

        verifySequence {
            runtime.closePlugin(record.id)
            terminalRunner.closePlugin(record.id)
            overlayManager.closePlugin(record.id)
        }
    }

    @Test
    fun sync_overlayOnlyPluginReloadsOnRevisionChange() {
        sync(plugin(withRuntime = false))

        sync(plugin(record.copy(archiveSha256 = "repacked-archive-sha256"), withRuntime = false))

        verifySequence {
            runtime.closePlugin(record.id)
            overlayManager.reloadPlugin(record.id)
        }
    }

    @Test
    fun sync_disablingOverlayOnlyPluginClosesOverlays() {
        sync(plugin(withRuntime = false))

        sync(plugin(record.copy(enabled = false), withRuntime = false))

        verifySequence {
            runtime.closePlugin(record.id)
            terminalRunner.closePlugin(record.id)
            overlayManager.closePlugin(record.id)
        }
    }
}
