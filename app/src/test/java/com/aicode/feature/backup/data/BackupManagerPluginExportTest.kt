package com.aicode.feature.backup.data

import com.aicode.feature.plugin.runtime.PluginBackupArchiveService
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupManagerPluginExportTest {
    private val pluginArchive = mockk<PluginBackupArchiveService>()
    private val tar = mockk<TarArchiveOutputStream>(relaxed = true)
    private val manager = BackupManagerImpl(
        context = mockk(),
        aiProviderDao = mockk(),
        remoteConnectionDao = mockk(),
        chatSessionDao = mockk(),
        agentMessageDao = mockk(),
        todoItemDao = mockk(),
        mcpConfigRepository = mockk(),
        mcpManager = mockk(),
        permissionRulesRepository = mockk(),
        themeSettingsRepository = mockk(),
        keepaliveSettingsRepository = mockk(),
        liveNotificationSettingsRepository = mockk(),
        approvalNotificationSettingsRepository = mockk(),
        screenOnSettingsRepository = mockk(),
        agentSoundSettingsRepository = mockk(),
        generalSettingsRepository = mockk(),
        logSettingsRepository = mockk(),
        visionModelSettingsRepository = mockk(),
        compactionModelSettingsRepository = mockk(),
        syncSettingsRepository = mockk(),
        workspaceRepository = mockk(),
        pluginArchive = pluginArchive
    )

    private fun writeSections(vararg sections: PluginBackupArchiveService.Section) {
        val method = BackupManagerImpl::class.java.getDeclaredMethod(
            "writePluginSections", TarArchiveOutputStream::class.java, List::class.java
        )
        method.isAccessible = true
        method.invoke(manager, tar, sections.toList())
    }

    @Test
    fun export_limitsEveryReadAndStopsReadingWhenSectionIsFull() {
        val section = PluginBackupArchiveService.Section("demo.backup", "/export")
        val files = (1..9).map { "$it.bin" }
        every { pluginArchive.listExportFiles(section) } returns files
        every { pluginArchive.isValidSectionPath(any()) } returns true
        val bytes = ByteArray(FILE_LIMIT.toInt())
        every { pluginArchive.readExportFile(section, any(), FILE_LIMIT) } returns bytes

        writeSections(section)

        verify(exactly = 8) { pluginArchive.readExportFile(section, any(), FILE_LIMIT) }
        verify(exactly = 0) { pluginArchive.readExportFile(section, "9.bin", any()) }
        verify(exactly = 8) { tar.putArchiveEntry(any<TarArchiveEntry>()) }
    }

    @Test
    fun export_remainingBudgetBoundsReadsAndOversizedFileDoesNotBlockSmallerFile() {
        val section = PluginBackupArchiveService.Section("demo.backup", "/export")
        val files = (1..8).map { "$it.bin" } + listOf("oversized.bin", "fits.bin")
        every { pluginArchive.listExportFiles(section) } returns files
        every { pluginArchive.isValidSectionPath(any()) } returns true
        val full = ByteArray(FILE_LIMIT.toInt())
        every { pluginArchive.readExportFile(section, any(), FILE_LIMIT) } returns full
        every { pluginArchive.readExportFile(section, "8.bin", FILE_LIMIT) } returns ByteArray(FILE_LIMIT.toInt() - 3)
        every { pluginArchive.readExportFile(section, "oversized.bin", 3) } throws FileReadTooLargeException(3)
        every { pluginArchive.readExportFile(section, "fits.bin", 3) } returns byteArrayOf(1, 2, 3)

        writeSections(section)

        verify(exactly = 1) { pluginArchive.readExportFile(section, "oversized.bin", 3) }
        verify(exactly = 1) { pluginArchive.readExportFile(section, "fits.bin", 3) }
        verify(exactly = 9) { tar.putArchiveEntry(any<TarArchiveEntry>()) }
    }

    @Test
    fun export_singleFileLimitAndTotalBudgetApplyPerSection() {
        val sections = listOf(
            PluginBackupArchiveService.Section("first.plugin", "/first"),
            PluginBackupArchiveService.Section("second.plugin", "/second")
        )
        val limits = mutableListOf<Long>()
        for (section in sections) {
            every { pluginArchive.listExportFiles(section) } returns listOf("huge.bin", "small.bin")
            every { pluginArchive.readExportFile(section, "huge.bin", FILE_LIMIT) } throws FileReadTooLargeException(FILE_LIMIT)
            every { pluginArchive.readExportFile(section, "small.bin", FILE_LIMIT) } answers {
                limits += thirdArg<Long>()
                byteArrayOf(1)
            }
        }
        every { pluginArchive.isValidSectionPath(any()) } returns true

        writeSections(*sections.toTypedArray())

        assertEquals(listOf(FILE_LIMIT, FILE_LIMIT), limits)
        verify(exactly = 2) { tar.putArchiveEntry(any<TarArchiveEntry>()) }
    }

    private companion object {
        const val FILE_LIMIT = 8L * 1024 * 1024
    }
}
