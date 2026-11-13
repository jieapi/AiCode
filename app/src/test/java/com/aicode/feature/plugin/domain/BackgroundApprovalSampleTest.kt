package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 后台审批示例：overlay、运行时与 review hook 必须能被解析、校验和加载。 */
class BackgroundApprovalSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/background-approval")
            val candidate = File(current, "plugin-samples/background-approval")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/background-approval")
    }

    @Test
    fun backgroundApproval_loadsOverlayAndReviewHook() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("demo.background-approval", manifest.id)
        assertNotNull(manifest.overlay)
        assertEquals(1, manifest.overlay?.surfaces?.size)
        assertEquals(48, manifest.overlay?.surfaces?.single()?.width)
        assertEquals(48, manifest.overlay?.surfaces?.single()?.height)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertEquals("ball", content.overlay?.surfaces?.first()?.localId)
        assertEquals("pages/ball.html", content.overlay?.surfaces?.single()?.pageFile)
        assertNotNull("应带运行时", content.runtime)
        assertTrue(content.hooks.any { it.event == PluginHookEvents.APP_STARTED })
        assertTrue(content.hooks.any {
            it.event == PluginHookEvents.AGENT_PERMISSION_REVIEW && it.mode == PluginHookModes.REVIEW
        })
    }
}
