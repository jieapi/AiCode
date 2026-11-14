package com.aicode.feature.plugin.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginHostPathTest {

    @Test
    fun workspaceFile_becomesWorkspaceScheme() {
        assertEquals(
            "workspace://docs-site/scripts/export-ai-docs.mjs",
            pluginHostPath("~/workspace/docs-site/scripts/export-ai-docs.mjs", "~/workspace")
        )
    }

    @Test
    fun existingScheme_isKept() {
        assertEquals(
            "workspace://a/b.kt",
            pluginHostPath("workspace://a/b.kt", "~/workspace")
        )
    }

    @Test
    fun pathOutsideWorkspace_isKept() {
        assertEquals("/etc/hosts", pluginHostPath("/etc/hosts", "~/workspace"))
    }

    @Test
    fun workspaceRootItself_isKept() {
        assertEquals("~/workspace", pluginHostPath("~/workspace", "~/workspace"))
    }
}
