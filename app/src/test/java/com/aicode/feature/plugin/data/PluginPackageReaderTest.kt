package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.PluginFixtures
import com.aicode.feature.plugin.domain.PluginErrorCode
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPackageReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun target() = File(tmp.root, "staging/t1")

    private fun read(bytes: ByteArray, reader: PluginPackageReader = PluginPackageReader()) =
        reader.read(ByteArrayInputStream(bytes), target())

    private fun assertFailed(result: PackageReadResult, code: PluginErrorCode) {
        check(result is PackageReadResult.Failed) { "应失败: $result" }
        assertEquals(code, result.error.code)
        assertFalse("失败后暂存目录必须清理", target().exists())
        assertFalse("失败后临时压缩包必须清理", File(tmp.root, "staging/t1.zip").exists())
    }

    @Test
    fun validPackage_isExtractedWithDigests() {
        val bytes = PluginFixtures.validPackage()
        val result = read(bytes)
        check(result is PackageReadResult.Ok) { "应成功: $result" }
        val pkg = result.pkg
        assertTrue(File(pkg.dir, "skills/notes/SKILL.md").isFile)
        assertTrue(File(pkg.dir, "agents/builder.md").isFile)
        assertEquals(PluginFixtures.manifest(), pkg.manifestText)
        assertEquals(PluginPackageReader.sha256Hex(PluginFixtures.manifest().toByteArray()), pkg.manifestSha256)
        assertEquals(PluginPackageReader.sha256Hex(bytes), pkg.archiveSha256)
        assertFalse("临时压缩包必须删除", File(tmp.root, "staging/t1.zip").exists())
    }

    @Test
    fun missingManifest_isRejected() {
        assertFailed(read(PluginFixtures.zip("readme.md" to "x")), PluginErrorCode.MANIFEST_MISSING)
    }

    @Test
    fun manifestMustBeAtRoot() {
        assertFailed(read(PluginFixtures.zip("demo/plugin.json" to "{}")), PluginErrorCode.MANIFEST_MISSING)
    }

    @Test
    fun garbageAndEmptyInput_areUnreadable() {
        assertFailed(read("this is not a zip".toByteArray()), PluginErrorCode.ARCHIVE_UNREADABLE)
        assertFailed(read(ByteArray(0)), PluginErrorCode.ARCHIVE_UNREADABLE)
    }

    @Test
    fun unsafePaths_areRejected() {
        listOf("../evil.txt", "/abs.txt", "a/../../evil.txt", "a\\b.txt", "a//b.txt", "./x.txt").forEach { name ->
            val bytes = PluginFixtures.zip("plugin.json" to "{}", name to "x")
            assertFailed(read(bytes), PluginErrorCode.ARCHIVE_UNSAFE_PATH)
        }
        assertFalse("越界文件不能写到暂存目录之外", File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "staging/evil.txt").exists())
    }

    @Test
    fun caseInsensitiveDuplicates_areRejected() {
        val bytes = PluginFixtures.zip("plugin.json" to "{}", "Skills/A.md" to "1", "skills/a.md" to "2")
        assertFailed(read(bytes), PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY)
    }

    @Test
    fun tooManyEntries_areRejected() {
        val entries = (1..6).map { "f$it.txt" to "x" }.toTypedArray()
        val reader = PluginPackageReader(PluginPackageReader.Limits(maxEntries = 5))
        assertFailed(read(PluginFixtures.zip("plugin.json" to "{}", *entries), reader), PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES)
    }

    @Test
    fun oversizedEntry_isRejectedWhileStreaming() {
        val reader = PluginPackageReader(PluginPackageReader.Limits(maxEntryBytes = 100))
        val bytes = PluginFixtures.zip("plugin.json" to "{}", "big.txt" to "a".repeat(101))
        assertFailed(read(bytes, reader), PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE)
    }

    @Test
    fun totalUncompressedSize_isLimited() {
        val reader = PluginPackageReader(PluginPackageReader.Limits(maxEntryBytes = 100, maxTotalBytes = 150))
        val bytes = PluginFixtures.zip(
            "plugin.json" to "{}",
            "a.txt" to "a".repeat(80),
            "b.txt" to "b".repeat(80)
        )
        assertFailed(read(bytes, reader), PluginErrorCode.ARCHIVE_TOO_LARGE)
    }

    @Test
    fun oversizedArchive_isRejected() {
        val reader = PluginPackageReader(PluginPackageReader.Limits(maxArchiveBytes = 64))
        assertFailed(read(PluginFixtures.validPackage(), reader), PluginErrorCode.ARCHIVE_TOO_LARGE)
    }

    @Test
    fun oversizedManifest_isRejected() {
        val huge = "{\"a\":\"" + "x".repeat(300 * 1024) + "\"}"
        assertFailed(read(PluginFixtures.zip("plugin.json" to huge)), PluginErrorCode.MANIFEST_TOO_LARGE)
    }

    @Test
    fun zipBomb_isRejected() {
        // 高压缩比条目：压缩后很小，解压后远超单条目限额
        val reader = PluginPackageReader(PluginPackageReader.Limits(maxEntryBytes = 1024 * 1024))
        val bytes = PluginFixtures.zipBytes(
            listOf("plugin.json" to "{}".toByteArray(), "bomb.bin" to ByteArray(8 * 1024 * 1024))
        )
        assertTrue("压缩后应远小于解压量", bytes.size < 100 * 1024)
        assertFailed(read(bytes, reader), PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE)
    }
}
