package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginProviderDefinition
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProviderRegistrarTest {

    private val pluginId = "demo.provider"
    private val stored = mutableListOf<AIProviderConfig>()

    private val providerRepository = mockk<AIProviderRepository>(relaxed = true) {
        every { getAllProviders() } answers { flowOf(stored.toList()) }
        coEvery { saveProvider(any()) } answers { stored += firstArg<AIProviderConfig>() }
        coEvery { deleteProviders(any()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            stored.removeAll { it.id in ids }
        }
    }

    private fun provider(localId: String, baseUrl: String = "http://127.0.0.1:18080/v1") =
        PluginProviderDefinition(
            pluginId = pluginId,
            localId = localId,
            name = LocalizedText.single("Demo $localId"),
            description = LocalizedText.single(""),
            format = "openai",
            baseUrl = baseUrl,
            pluginVersion = "1.0.0"
        )

    private fun plugin(
        active: Boolean = true,
        providers: List<PluginProviderDefinition> = emptyList()
    ): InstalledPlugin = mockk {
        every { id } returns pluginId
        every { isActive } returns active
        every { content } returns PluginContent(providers = providers)
    }

    private fun registrarFor(plugin: InstalledPlugin): PluginProviderRegistrar {
        val pluginManager = mockk<PluginManager>(relaxed = true) {
            every { plugins } returns MutableStateFlow(listOf(plugin))
            every { ensureLoaded() } just Runs
        }
        return PluginProviderRegistrar(pluginManager, providerRepository)
    }

    @Test
    fun sync_registersDeclaredProvidersAsWritableProviders() = runTest {
        val registrar = registrarFor(plugin(providers = listOf(provider("echo"))))

        registrar.sync(pluginId)

        val saved = stored.single()
        assertEquals("plugin:demo.provider:echo", saved.id)
        assertEquals("Demo echo", saved.name)
        assertEquals(ProviderType.OPENAI, saved.type)
        assertEquals("http://127.0.0.1:18080/v1", saved.baseUrl)
        assertEquals(PluginProviderRegistrar.PLACEHOLDER_API_KEY, saved.apiKey)
        assertTrue(saved.models.isEmpty())
        assertTrue(saved.isEnabled)
    }

    @Test
    fun sync_keepsExistingProviderEdits() = runTest {
        stored += AIProviderConfig(
            id = "plugin:demo.provider:echo",
            name = "我改过的名字",
            type = ProviderType.OPENAI,
            apiKey = "secret",
            baseUrl = "http://127.0.0.1:9999/v1",
            defaultModel = "echo",
            models = listOf("echo"),
            selectedModel = "echo"
        )
        val registrar = registrarFor(plugin(providers = listOf(provider("echo"))))

        registrar.sync(pluginId)

        assertEquals("我改过的名字", stored.single().name)
        assertEquals("http://127.0.0.1:9999/v1", stored.single().baseUrl)
        assertEquals(listOf("echo"), stored.single().models)
    }

    @Test
    fun sync_removesProvidersNoLongerDeclared() = runTest {
        stored += AIProviderConfig(
            id = "plugin:demo.provider:old",
            name = "Old",
            type = ProviderType.OPENAI,
            apiKey = "plugin",
            baseUrl = "http://127.0.0.1:1/v1",
            defaultModel = ""
        )
        val registrar = registrarFor(plugin(providers = listOf(provider("echo"))))

        registrar.sync(pluginId)

        assertEquals(setOf("plugin:demo.provider:echo"), stored.map { it.id }.toSet())
    }

    @Test
    fun sync_removesEverythingWhenPluginInactive() = runTest {
        stored += listOf(
            providerConfig("plugin:demo.provider:echo", "Echo", "http://127.0.0.1:1/v1"),
            providerConfig("other.plugin:echo", "Other", "http://127.0.0.1:2/v1")
        )
        val registrar = registrarFor(plugin(active = false, providers = listOf(provider("echo"))))

        registrar.sync(pluginId)

        assertEquals(listOf("other.plugin:echo"), stored.map { it.id })
    }

    @Test
    fun removeAll_deletesOnlyOwningPluginProviders() = runTest {
        stored += listOf(
            providerConfig("plugin:demo.provider:echo", "Echo", "http://127.0.0.1:1/v1"),
            providerConfig("plugin:demo.provider:chat", "Chat", "http://127.0.0.1:1/v1"),
            providerConfig("plugin:other.plugin:echo", "Other", "http://127.0.0.1:2/v1")
        )
        val registrar = registrarFor(plugin(active = false))

        registrar.removeAll(pluginId)

        assertEquals(listOf("plugin:other.plugin:echo"), stored.map { it.id })
        coVerify(exactly = 1) { providerRepository.deleteProviders(any()) }
    }

    private fun providerConfig(id: String, name: String, baseUrl: String) = AIProviderConfig(
        id = id,
        name = name,
        type = ProviderType.OPENAI,
        apiKey = "plugin",
        baseUrl = baseUrl,
        defaultModel = ""
    )

    @Test
    fun ids_splitPluginAndLocalId() {
        assertEquals("demo.provider", PluginProviderIds.pluginIdOf("plugin:demo.provider:echo"))
        assertEquals("echo", PluginProviderIds.localIdOf("plugin:demo.provider:echo"))
        assertTrue(PluginProviderIds.isPluginId("plugin:demo.provider:echo"))
        assertNull(PluginProviderIds.pluginIdOf("demo.provider:echo"))
        assertNull(PluginProviderIds.pluginIdOf("plugin:demo.provider"))
        assertNull(PluginProviderIds.localIdOf("plugin:demo.provider:"))
    }
}
