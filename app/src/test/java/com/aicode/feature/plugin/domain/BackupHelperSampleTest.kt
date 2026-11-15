package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 备份助手：备份事件与 backup.data 数据选项必须能被解析、校验和加载。 */
class BackupHelperSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/backup-helper")
            val candidate = File(current, "plugin-samples/backup-helper")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/backup-helper")
    }

    @Test
    fun backupHelper_loadsEventsAndDataSelector() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("jieapi.backup-helper", manifest.id)
        assertEquals("icons/icon.png", manifest.icon)
        assertEquals("备份记忆、技能、MCP 配置与子代理，并在还原后恢复这些数据", manifest.backup?.purpose)
        assertEquals(
            setOf(PluginHookEvents.BACKUP_EXPORT_BEFORE, PluginHookEvents.BACKUP_IMPORT_FINISHED),
            manifest.hooks.map { it.event }.toSet()
        )
        assertEquals(1, manifest.uiEntries.size)
        val entry = manifest.uiEntries.single()
        assertEquals(PluginUiSlots.BACKUP_DATA, entry.slot)
        assertEquals(PluginUiEntryKind.DATA, entry.kind)
        assertNull(entry.page)
        assertNull(entry.handler)
        assertNull(manifest.configPage)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertNotNull("应带备份事件声明", content.backup)
        assertNotNull("应带运行时", content.runtime)
        assertNotNull("应带包内图标", content.icon?.file)
        assertTrue(content.hooks.all { it.mode == PluginHookModes.OBSERVE })
        assertEquals(PluginUiEntryKind.DATA, content.uiEntries.single().kind)

        val runtime = File(dir, "dist/main.js").readText()
        assertTrue("导出事件应写入插件暂存目录", runtime.contains("hooks.onExportBefore"))
        assertTrue("导入事件应从插件区段恢复", runtime.contains("hooks.onImport"))
        assertTrue("运行时应使用二进制读写", runtime.contains("host.fs.writeBinary"))
        assertTrue("运行时应写入区段清单", runtime.contains("manifest.json"))
    }
}
