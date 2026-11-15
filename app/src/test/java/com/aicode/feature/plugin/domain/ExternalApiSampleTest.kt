package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 确保仓库里的 demo.external-api 示例能被真实清单解析与内容加载接受。 */
class ExternalApiSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/external-api")
            val candidate = File(current, "plugin-samples/external-api")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/external-api")
    }

    @Test
    fun externalApi_loadsManifestAndContent() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("demo.external-api", manifest.id)
        assertEquals(1, manifest.tools.size)
        assertEquals("whoami", manifest.tools.single().id)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertEquals(1, content.tools.size)
        assertNotNull("示例应带配置页", content.configPage)
        assertNotNull("示例应带运行时", content.runtime)
    }
}
