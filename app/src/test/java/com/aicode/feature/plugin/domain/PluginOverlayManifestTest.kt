package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOverlayManifestTest {

    private fun manifest(overlay: String): String = """
        {
          "manifestVersion": 1,
          "id": "demo.overlay",
          "name": "Demo Overlay",
          "version": "1.0.0",
          "publisher": "demo",
          "repository": "https://github.com/example/demo-overlay",
          "description": "demo",
          "license": "MIT",
          "engines": {"pluginApi": "^1.0.0"},
          "contributes": {"overlay": $overlay}
        }
    """.trimIndent()

    @Test
    fun overlayContribution_isParsed() {
        val result = PluginManifestParser.parse(
            manifest(
                """
                {
                  "purpose": "桌面宠物",
                  "surfaces": [
                    {"id": "pet", "page": "pages/pet.html", "width": 220, "height": 260}
                  ]
                }
                """.trimIndent()
            )
        )
        assertTrue(result is ManifestParseResult.Success)
        val overlay = (result as ManifestParseResult.Success).manifest.overlay
        assertEquals("桌面宠物", overlay?.purpose)
        assertEquals(1, overlay?.surfaces?.size)
        assertEquals("pet", overlay?.surfaces?.first()?.id)
        assertEquals(220, overlay?.surfaces?.first()?.width)
    }

    @Test
    fun overlayRejectsUnknownField() {
        val result = PluginManifestParser.parse(
            manifest("""{"purpose": "x", "windowType": "fullscreen"}""")
        )
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue(
            (result as ManifestParseResult.Failure).errors.any { it.code == PluginErrorCode.UNSUPPORTED_FIELD }
        )
    }

    @Test
    fun overlayRejectsOutOfRangeSurface() {
        val result = PluginManifestParser.parse(
            manifest(
                """{"purpose": "x", "surfaces": [{"id": "pet", "page": "pet.html", "width": 4, "height": 260}]}"""
            )
        )
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue(
            (result as ManifestParseResult.Failure).errors.any { it.code == PluginErrorCode.FIELD_INVALID }
        )
    }

    @Test
    fun overlaySnapDefaultsToNone() {
        val result = PluginManifestParser.parse(
            manifest("""{"purpose": "x", "surfaces": [{"id": "pet", "page": "pet.html"}]}""")
        )
        val surface = (result as ManifestParseResult.Success).manifest.overlay?.surfaces?.first()
        assertEquals(PluginOverlaySnap.NONE, surface?.snap)
    }

    @Test
    fun overlaySnapAcceptsDeclaredAxes() {
        val result = PluginManifestParser.parse(
            manifest("""{"purpose": "x", "surfaces": [{"id": "pet", "page": "pet.html", "snap": "both"}]}""")
        )
        val surface = (result as ManifestParseResult.Success).manifest.overlay?.surfaces?.first()
        assertEquals(PluginOverlaySnap.BOTH, surface?.snap)
    }

    @Test
    fun overlayRejectsUnknownSnapValue() {
        val result = PluginManifestParser.parse(
            manifest("""{"purpose": "x", "surfaces": [{"id": "pet", "page": "pet.html", "snap": "diagonal"}]}""")
        )
        assertTrue(result is ManifestParseResult.Failure)
        assertTrue(
            (result as ManifestParseResult.Failure).errors.any { it.code == PluginErrorCode.FIELD_INVALID }
        )
    }

    @Test
    fun overlayRequiresPurpose() {
        val result = PluginManifestParser.parse(manifest("""{"surfaces": []}"""))
        assertTrue(result is ManifestParseResult.Failure)
    }
}
