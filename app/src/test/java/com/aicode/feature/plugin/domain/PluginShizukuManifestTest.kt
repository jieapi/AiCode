package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginShizukuManifestTest {

    private fun manifest(shizuku: String): String = """
        {
          "manifestVersion": 1,
          "id": "demo.shizuku",
          "name": "Demo Shizuku",
          "version": "1.0.0",
          "publisher": "demo",
          "repository": "https://github.com/example/demo-shizuku",
          "description": "demo",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "contributes": {"shizuku": $shizuku}
        }
    """.trimIndent()

    @Test
    fun shizukuContribution_isParsed() {
        val result = PluginManifestParser.parse(manifest("""{"purpose": "切换免打扰"}"""))
        assertTrue(result is ManifestParseResult.Success)
        val declaration = (result as ManifestParseResult.Success).manifest.shizuku
        assertEquals("切换免打扰", declaration?.purpose)
        assertEquals(1, result.manifest.contributionCount)
    }

    @Test
    fun shizukuRejectsUnknownField() {
        val result = PluginManifestParser.parse(
            manifest("""{"purpose": "x", "commands": ["pm"]}""")
        )
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue(
            (result as ManifestParseResult.Failure).errors.any { it.code == PluginErrorCode.UNSUPPORTED_FIELD }
        )
    }

    @Test
    fun shizukuRequiresPurpose() {
        val result = PluginManifestParser.parse(manifest("""{}"""))
        assertTrue(result is ManifestParseResult.Failure)
    }
}
