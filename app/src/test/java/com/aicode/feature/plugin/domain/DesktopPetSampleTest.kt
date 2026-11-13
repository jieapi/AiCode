package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 桌面宠物示例：overlay 声明与浮层页面必须能被解析、校验和加载。 */
class DesktopPetSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/desktop-pet")
            val candidate = File(current, "plugin-samples/desktop-pet")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/desktop-pet")
    }

    @Test
    fun desktopPet_loadsOverlaySurface() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("demo.desktop-pet", manifest.id)
        assertNotNull(manifest.overlay)
        assertEquals(1, manifest.overlay?.surfaces?.size)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertNotNull(content.overlay)
        assertEquals("pet", content.overlay?.surfaces?.first()?.localId)
        assertNotNull("应带运行时而自启动 hook", content.runtime)
        assertTrue(content.hooks.any { it.event == PluginHookEvents.APP_STARTED })
        assertNotNull("应带配置页", content.configPage)
    }
}
