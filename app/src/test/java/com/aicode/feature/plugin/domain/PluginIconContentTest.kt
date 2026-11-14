package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.PluginFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginIconContentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(icon: String): PluginManifest {
        val result = PluginManifestParser.parse(
            PluginFixtures.manifest(extra = ",\n  \"icon\": \"$icon\"")
        )
        check(result is ManifestParseResult.Success) { "应解析成功: $result" }
        return result.manifest
    }

    private fun baseDir(): File = tmp.newFolder().also { dir ->
        File(dir, PluginFixtures.SKILL_FILE).apply {
            parentFile?.mkdirs()
            writeText(PluginFixtures.SKILL_MD)
        }
        File(dir, PluginFixtures.AGENT_FILE).apply {
            parentFile?.mkdirs()
            writeText(PluginFixtures.AGENT_MD)
        }
    }

    private fun preparePackage(icon: String? = null, bytes: ByteArray = PNG_BYTES): Pair<File, ContentLoadResult> {
        val dir = baseDir()
        if (icon != null) {
            File(dir, icon).apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
        }
        return dir to PluginContentLoader.load(dir, manifest(icon ?: "terminal"))
    }

    @Test
    fun builtinIcon_isKeptAsName() {
        val (_, result) = preparePackage()
        val content = (result as ContentLoadResult.Ok).content
        assertEquals("terminal", content.icon?.name)
        assertNull(content.icon?.file)
    }

    @Test
    fun packageImage_isResolvedToFile() {
        val (dir, result) = preparePackage(icon = "icons/logo.png")
        val content = (result as ContentLoadResult.Ok).content
        assertEquals(File(dir, "icons/logo.png").absolutePath, content.icon?.file?.absolutePath)
        assertNull(content.icon?.name)
    }

    @Test
    fun missingImage_isRejected() {
        val result = PluginContentLoader.load(baseDir(), manifest("icons/logo.png"))
        val errors = (result as ContentLoadResult.Failed).errors
        assertTrue(errors.any { it.code == PluginErrorCode.FILE_MISSING && it.detail == "icons/logo.png" })
    }

    @Test
    fun oversizedImage_isRejected() {
        val bytes = ByteArray(PluginContentLoader.MAX_ICON_BYTES + 1)
        val (_, result) = preparePackage(icon = "icons/logo.png", bytes = bytes)
        val errors = (result as ContentLoadResult.Failed).errors
        assertTrue(
            errors.any {
                it.code == PluginErrorCode.CONTENT_INVALID && it.detail == "icons/logo.png: too large"
            }
        )
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
