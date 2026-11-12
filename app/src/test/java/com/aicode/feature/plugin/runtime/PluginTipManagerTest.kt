package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTipManagerTest {

    private fun plugin(id: String): InstalledPlugin {
        val instance = mockk<InstalledPlugin>(relaxed = true)
        every { instance.id } returns id
        every { instance.isActive } returns true
        every { instance.manifest } returns mockk { every { name } returns LocalizedText.single("Demo") }
        every { instance.content } returns PluginContent(runtime = mockk(relaxed = true))
        return instance
    }

    private fun manager(vararg pluginIds: String): PluginTipManager {
        val plugins = mockk<PluginManager>(relaxed = true)
        every { plugins.plugins } returns MutableStateFlow(pluginIds.map { plugin(it) })
        every { plugins.ensureLoaded() } just Runs
        val runtime = mockk<PluginRuntimeManager>(relaxed = true)
        return PluginTipManager(plugins, object : dagger.Lazy<PluginRuntimeManager> {
            override fun get(): PluginRuntimeManager = runtime
        })
    }

    private fun tip(text: String, action: Boolean = false): JsonObject = buildJsonObject {
        put("text", text)
        if (action) {
            putJsonObject("action") {
                put("label", "打开")
                put("handler", "tips.open")
            }
        }
    }

    @Test
    fun show_queuesTipWithPluginName() {
        val manager = manager("demo.tip")

        manager.show("demo.tip", tip("hello"))

        val queued = manager.tips.value.single()
        assertEquals("Demo", queued.pluginName)
        assertEquals("hello", queued.text)
        assertTrue(queued.id.startsWith("tip-"))
    }

    @Test
    fun show_fromSamePluginReplacesOlderTip() {
        val manager = manager("demo.tip")

        manager.show("demo.tip", tip("first"))
        manager.show("demo.tip", tip("second"))

        assertEquals(listOf("second"), manager.tips.value.map { it.text })
    }

    @Test
    fun show_rejectsWhenQueueFull() {
        val manager = manager("a.one", "b.two", "c.three", "d.four")
        manager.show("a.one", tip("1"))
        manager.show("b.two", tip("2"))
        manager.show("c.three", tip("3"))

        val error = runCatching { manager.show("d.four", tip("4")) }.exceptionOrNull()

        assertEquals("TIP_QUEUE_FULL", (error as PluginTipException).code)
    }

    @Test
    fun show_parsesAction() {
        val manager = manager("demo.tip")

        manager.show("demo.tip", tip("hello", action = true))

        val queued = manager.tips.value.single()
        assertEquals("打开", queued.actionLabel)
        assertEquals("tips.open", queued.actionHandler)
    }

    @Test
    fun dismiss_removesTip() {
        val manager = manager("demo.tip")
        val id = (manager.show("demo.tip", tip("hello"))["tipId"] as JsonPrimitive).content

        manager.dismiss(id)

        assertTrue(manager.tips.value.isEmpty())
    }
}
