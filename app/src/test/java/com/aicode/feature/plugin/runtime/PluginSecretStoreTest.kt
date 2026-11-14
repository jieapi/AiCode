package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginId = "demo.secret"

    private fun plugin(active: Boolean = true): InstalledPlugin = mockk {
        every { id } returns pluginId
        every { isActive } returns active
    }

    private fun store(active: Boolean = true): PluginSecretStore {
        val manager = mockk<PluginManager>(relaxed = true)
        every { manager.plugins } returns MutableStateFlow(listOf(plugin(active)))
        val cipher = object : PluginSecretCipher {
            override fun encrypt(plain: String): String = "enc:$plain"
            override fun decrypt(value: String): String = value.removePrefix("enc:")
        }
        return PluginSecretStore(File(tmp.root, "plugin-secrets"), manager, cipher)
    }

    @Test
    fun setGetListDeleteRoundTrip() = runTest {
        val store = store()

        store.set(pluginId, "token", "secret-value")
        assertEquals("secret-value", store.get(pluginId, "token").jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("token"),
            store.list(pluginId).jsonObject["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        )

        store.delete(pluginId, "token")
        assertEquals("", store.get(pluginId, "token").jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(store.list(pluginId).jsonObject["keys"]!!.jsonArray.isEmpty())
    }

    @Test
    fun rejectsInvalidKeyAndOversizedValue() = runTest {
        val store = store()

        val invalidKey = runCatching { store.set(pluginId, "bad key", "x") }.exceptionOrNull()
        assertEquals("PARAM_INVALID", (invalidKey as PluginHostException).code)

        val oversized = runCatching { store.set(pluginId, "token", "x".repeat(64 * 1024 + 1)) }
            .exceptionOrNull()
        assertEquals("TOO_LARGE", (oversized as PluginHostException).code)
    }

    @Test
    fun rejectsInactivePlugin() = runTest {
        val error = runCatching { store(active = false).get(pluginId, "token") }.exceptionOrNull()

        assertEquals("PLUGIN_INACTIVE", (error as PluginHostException).code)
    }

    @Test
    fun corruptedFileIsTreatedAsEmpty() = runTest {
        File(tmp.root, "plugin-secrets").mkdirs()
        File(tmp.root, "plugin-secrets/$pluginId.json").writeText("not json")

        assertTrue(store().list(pluginId).jsonObject["keys"]!!.jsonArray.isEmpty())
    }
}
