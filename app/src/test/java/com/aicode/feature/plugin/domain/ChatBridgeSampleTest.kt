package com.aicode.feature.plugin.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 对话桥示例：设置页、代审核与页面接口必须能被解析、校验和加载。 */
class ChatBridgeSampleTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val current = dir ?: return File("plugin-samples/chat-bridge")
            val candidate = File(current, "plugin-samples/chat-bridge")
            if (candidate.isDirectory) return candidate
            dir = current.parentFile
        }
        return File("plugin-samples/chat-bridge")
    }

    @Test
    fun chatBridge_loadsSettingsPageAndRunFinishedHook() {
        val dir = sampleDir()
        assumeTrue("示例目录不在当前工作区", dir.isDirectory)

        val parsed = PluginManifestParser.parse(File(dir, "plugin.json").readText())
        assertTrue("清单应解析成功：$parsed", parsed is ManifestParseResult.Success)
        val manifest = (parsed as ManifestParseResult.Success).manifest
        assertEquals("demo.chat-bridge", manifest.id)
        assertEquals(2, manifest.uiEntries.size)
        val settingsEntry = manifest.uiEntries.single { it.slot == PluginUiSlots.SETTINGS_AI }
        assertEquals("pages/chat.html", settingsEntry.page)
        val actionEntry = manifest.uiEntries.single { it.slot == PluginUiSlots.CHAT_SESSION_MENU }
        assertEquals("actions.notifySession", actionEntry.handler)

        val loaded = PluginContentLoader.load(dir, manifest)
        assertTrue("内容应加载成功：$loaded", loaded is ContentLoadResult.Ok)
        val content = (loaded as ContentLoadResult.Ok).content
        assertEquals("pages/chat.html", content.uiEntries.single { it.page != null }.page?.pageFile)
        assertTrue(content.uiEntries.any { it.handler == "actions.notifySession" })
        assertNotNull("应带运行时", content.runtime)
        assertTrue(content.hooks.any {
            it.event == PluginHookEvents.AGENT_PERMISSION_REVIEW && it.mode == PluginHookModes.REVIEW
        })

        val page = File(dir, "pages/chat.html").readText()
        assertTrue("页面应调用会话 API", page.contains("aicode.chat.sendMessage"))
        assertTrue("页面应处理完成事件", page.contains("chat.run.finished"))
        assertTrue("页面应处理代审核请求", page.contains("chat.permission.requested"))
        assertTrue("页面应回传审批决定", page.contains("ui.permissionDecision"))
    }
}
