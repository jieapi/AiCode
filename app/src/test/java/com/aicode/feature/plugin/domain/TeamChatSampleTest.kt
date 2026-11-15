package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 团队群聊示例：声明模型访问，用页面桥的模型句柄完成多模型轮流请求，并演示消息合并、工具循环、审批与图片输入。 */
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
        assertEquals("1.2.0", manifest.version.toString())
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
        assertTrue("页面应合并相邻同角色消息", page.contains("function buildMessages"))
        assertTrue("页面应按角色判断是否合并", page.contains("last.role === item.role"))
        assertTrue("页面应声明自定义工具 team_vote", page.contains("name: 'team_vote'"))
        assertTrue("页面应引用宿主工具 writeFile", page.contains("type: 'app', name: 'writeFile'"))
        assertTrue("页面应代执行宿主工具", page.contains("aicode.tools.execute"))
        assertTrue("页面应轮询待审批请求", page.contains("aicode.tools.pending"))
        assertTrue("页面应回传审批结果", page.contains("aicode.tools.resolve"))
        assertTrue("页面应把工具调用与 providerState 回传模型", page.contains("providerState") && page.contains("toolCalls"))
        assertTrue("页面应支持图片输入", page.contains("FileReader") && page.contains("images"))
        assertTrue("页面应显示模型输出图片", page.contains("result.final.images"))
    }
}
