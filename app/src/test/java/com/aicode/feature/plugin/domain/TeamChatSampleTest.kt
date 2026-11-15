package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 团队群聊示例：声明模型访问，并用页面桥的模型句柄完成多模型轮流请求。 */
class TeamChatSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/team-chat")
            val candidate = File(current, "plugin-samples/team-chat")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/team-chat")
    }

    @Test
    fun teamChat_declaresModelAccessAndUsesModelHandles() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("demo.team-chat", manifest.id)
        assertEquals("在团队群聊中轮流调用多个模型", manifest.modelAccess?.purpose)
        assertEquals(1, manifest.uiEntries.size)
        val entry = manifest.uiEntries.single()
        assertEquals(PluginUiSlots.SETTINGS_AI, entry.slot)
        assertEquals("pages/team.html", entry.page)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertEquals("pages/team.html", content.uiEntries.single().page?.pageFile)
        assertEquals("在团队群聊中轮流调用多个模型", content.modelAccess?.purpose)
        assertNull("示例应只用页面桥，不需要 runtime", content.runtime)

        val page = File(dir, "pages/team.html").readText()
        assertTrue("页面应加载供应商", page.contains("aicode.model.listProviders"))
        assertTrue("页面应创建句柄", page.contains("aicode.model.create"))
        assertTrue("页面应流式请求", page.contains("aicode.model.stream"))
        assertTrue("页面应关闭句柄", page.contains("aicode.model.close"))
    }
}
