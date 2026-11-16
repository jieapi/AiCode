package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginProviderDefinition
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProviderServiceTest {

    private val pluginId = "demo.provider"
    private val providerId = "plugin:demo.provider:echo"
    private val stored = mutableListOf<AIProviderConfig>()

    private val modelMetadataService = mockk<ModelMetadataService>(relaxed = true) {
        coEvery { resolve(any(), any(), any()) } answers {
            ModelMetadata(id = thirdArg<String>(), contextTokens = 128_000)
        }
    }
    private val customModelMetadataStore = mockk<CustomModelMetadataStore>(relaxed = true)

    private val defaultModelSettingsRepository = mockk<DefaultModelSettingsRepository>(relaxed = true) {
        coEvery { getDefaultProviderId() } returns ""
        coEvery { getDefaultModel() } returns ""
    }
    private val visionModelSettingsRepository = mockk<VisionModelSettingsRepository>(relaxed = true) {
        coEvery { getVisionProviderId() } returns ""
        coEvery { getVisionModel() } returns ""
    }
    private val imageGenModelSettingsRepository = mockk<ImageGenModelSettingsRepository>(relaxed = true) {
        coEvery { getImageGenProviderId() } returns ""
        coEvery { getImageGenModel() } returns ""
    }
    private val compactionModelSettingsRepository = mockk<CompactionModelSettingsRepository>(relaxed = true) {
        coEvery { getCompactionProviderId() } returns ""
        coEvery { getCompactionModel() } returns ""
    }
    private val titleModelSettingsRepository = mockk<TitleModelSettingsRepository>(relaxed = true) {
        coEvery { getTitleProviderId() } returns ""
        coEvery { getTitleModel() } returns ""
    }
    private val subagentTierSettingsRepository = mockk<SubagentTierSettingsRepository>(relaxed = true) {
        coEvery { snapshot() } returns emptyMap()
    }

    private val providerRepository = mockk<AIProviderRepository>(relaxed = true) {
        coEvery { getProviderById(any()) } answers {
            stored.firstOrNull { it.id == firstArg<String>() }
        }
        coEvery { updateModels(any(), any()) } answers {
            val id = firstArg<String>()
            val models = secondArg<List<String>>()
            val index = stored.indexOfFirst { it.id == id }
            if (index >= 0) stored[index] = stored[index].copy(models = models)
        }
        coEvery { setSelectedModel(any(), any()) } answers {
            val id = firstArg<String>()
            val model = secondArg<String>()
            val index = stored.indexOfFirst { it.id == id }
            if (index >= 0) stored[index] = stored[index].copy(selectedModel = model)
        }
        coEvery { setProviderApiKey(any(), any()) } answers {
            val id = firstArg<String>()
            val apiKey = secondArg<String>()
            val index = stored.indexOfFirst { it.id == id }
            if (index >= 0) stored[index] = stored[index].copy(apiKey = apiKey)
        }
    }

    private fun plugin(
        active: Boolean = true,
        providers: List<PluginProviderDefinition> = listOf(provider())
    ): InstalledPlugin = mockk {
        every { id } returns pluginId
        every { isActive } returns active
        every { content } returns PluginContent(providers = providers)
    }

    private fun provider(localId: String = "echo") = PluginProviderDefinition(
        pluginId = pluginId,
        localId = localId,
        name = LocalizedText.single("Demo $localId"),
        description = LocalizedText.single(""),
        format = "openai",
        baseUrl = "http://127.0.0.1:18080/v1",
        pluginVersion = "1.0.0"
    )

    private fun serviceFor(plugin: InstalledPlugin = plugin()): PluginProviderService {
        val pluginManager = mockk<PluginManager>(relaxed = true) {
            every { plugins } returns MutableStateFlow(listOf(plugin))
            every { ensureLoaded() } just Runs
        }
        return PluginProviderService(
            pluginManager = pluginManager,
            providerRepository = providerRepository,
            modelMetadataService = modelMetadataService,
            customModelMetadataStore = customModelMetadataStore,
            defaultModelSettingsRepository = defaultModelSettingsRepository,
            visionModelSettingsRepository = visionModelSettingsRepository,
            imageGenModelSettingsRepository = imageGenModelSettingsRepository,
            compactionModelSettingsRepository = compactionModelSettingsRepository,
            titleModelSettingsRepository = titleModelSettingsRepository,
            subagentTierSettingsRepository = subagentTierSettingsRepository
        )
    }

    private fun config(
        models: List<String> = emptyList(),
        selectedModel: String = ""
    ) = AIProviderConfig(
        id = providerId,
        name = "Demo echo",
        type = ProviderType.OPENAI,
        apiKey = PluginProviderRegistrar.PLACEHOLDER_API_KEY,
        baseUrl = "http://127.0.0.1:18080/v1",
        defaultModel = "",
        models = models,
        selectedModel = selectedModel
    )

    private fun providerParams(id: String = "echo") = buildJsonObject { put("providerId", id) }

    @Test
    fun listModels_returnsConfiguredModelList() = runTest {
        stored += config(models = listOf("echo", "echo-mini"), selectedModel = "echo")

        val result = serviceFor().listModels(pluginId, providerParams()) as JsonObject

        assertEquals(providerId, result.getValue("providerId").jsonPrimitive.content)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("echo"), JsonPrimitive("echo-mini"))),
            result["models"]
        )
        assertEquals("echo", result.getValue("selectedModel").jsonPrimitive.content)
    }

    @Test
    fun addModel_appendsModelAndSelectsFirstWhenEmpty() = runTest {
        stored += config()

        val result = serviceFor().addModel(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("model", "echo-mini")
            }
        ) as JsonObject

        assertEquals(listOf("echo-mini"), stored.single().models)
        assertEquals("echo-mini", stored.single().selectedModel)
        assertEquals("echo-mini", result.getValue("selectedModel").jsonPrimitive.content)
    }

    @Test
    fun addModel_keepsExistingSelection() = runTest {
        stored += config(models = listOf("echo"), selectedModel = "echo")

        serviceFor().addModel(
            pluginId,
            buildJsonObject {
                put("providerId", providerId)
                put("model", "echo-mini")
            }
        )

        assertEquals(listOf("echo", "echo-mini"), stored.single().models)
        assertEquals("echo", stored.single().selectedModel)
    }

    @Test
    fun addModel_canSetContextTokens() = runTest {
        stored += config()

        serviceFor().addModel(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("model", "echo-mini")
                put("contextTokens", 32_000)
            }
        )

        coVerify {
            customModelMetadataStore.put(
                providerId,
                "echo-mini",
                match { it.contextTokens == 32_000 }
            )
        }
    }

    @Test
    fun setModelContext_updatesOnlyContextTokens() = runTest {
        stored += config(models = listOf("echo"), selectedModel = "echo")

        val result = serviceFor().setModelContext(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("model", "echo")
                put("contextTokens", 64_000)
            }
        ) as JsonObject

        assertEquals(64_000, result.getValue("contextTokens").jsonPrimitive.content.toInt())
        coVerify {
            customModelMetadataStore.put(
                providerId,
                "echo",
                match { it.contextTokens == 64_000 }
            )
        }
    }

    @Test
    fun setModelContext_rejectsUnknownModel() = runTest {
        stored += config(models = listOf("echo"), selectedModel = "echo")
        val failure = runCatching {
            serviceFor().setModelContext(
                pluginId,
                buildJsonObject {
                    put("providerId", "echo")
                    put("model", "missing")
                    put("contextTokens", 64_000)
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is PluginHostException)
        assertEquals("MODEL_NOT_AVAILABLE", (failure as PluginHostException).code)
    }

    @Test
    fun deleteModel_removesModelAndFallsBackSelection() = runTest {
        stored += config(models = listOf("echo", "echo-mini"), selectedModel = "echo-mini")

        val result = serviceFor().deleteModel(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("model", "echo-mini")
            }
        ) as JsonObject

        assertEquals(listOf("echo"), stored.single().models)
        assertEquals("echo", stored.single().selectedModel)
        assertEquals(true, result.getValue("removed").jsonPrimitive.content.toBoolean())
        coVerify { customModelMetadataStore.remove(providerId, "echo-mini") }
    }

    @Test
    fun deleteModel_clearsDefaultModelBinding() = runTest {
        stored += config(models = listOf("echo"), selectedModel = "echo")
        coEvery { defaultModelSettingsRepository.getDefaultProviderId() } returns providerId
        coEvery { defaultModelSettingsRepository.getDefaultModel() } returns "echo"

        serviceFor().deleteModel(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("model", "echo")
            }
        )

        coVerify(exactly = 1) { defaultModelSettingsRepository.clear() }
    }

    @Test
    fun setApiKey_updatesOnlyTheOwnedProviderKey() = runTest {
        stored += config(models = listOf("echo"), selectedModel = "echo")

        val result = serviceFor().setApiKey(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("apiKey", " local-token \n")
            }
        ) as JsonObject

        assertEquals("local-token", stored.single().apiKey)
        assertEquals(listOf("echo"), stored.single().models)
        assertEquals("echo", stored.single().selectedModel)
        assertTrue(result.getValue("hasKey").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun setApiKey_withEmptyValueClearsKey() = runTest {
        stored += config()

        val result = serviceFor().setApiKey(
            pluginId,
            buildJsonObject {
                put("providerId", "echo")
                put("apiKey", "")
            }
        ) as JsonObject

        assertEquals("", stored.single().apiKey)
        assertFalse(result.getValue("hasKey").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun otherPluginProviderIsTreatedAsNotFound() = runTest {
        stored += config()
        val failure = runCatching {
            serviceFor().addModel(
                pluginId,
                buildJsonObject {
                    put("providerId", "plugin:other.plugin:echo")
                    put("model", "echo")
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is PluginHostException)
        assertEquals("PROVIDER_NOT_FOUND", (failure as PluginHostException).code)
    }

    @Test
    fun undeclaredLocalProviderIsTreatedAsNotFound() = runTest {
        stored += config()
        val failure = runCatching {
            serviceFor().setApiKey(
                pluginId,
                buildJsonObject {
                    put("providerId", "other")
                    put("apiKey", "token")
                }
            )
        }.exceptionOrNull()

        assertTrue(failure is PluginHostException)
        assertEquals("PROVIDER_NOT_FOUND", (failure as PluginHostException).code)
    }

    @Test
    fun inactivePluginCannotModifyProviders() = runTest {
        stored += config()
        val failure = runCatching {
            serviceFor(plugin(active = false)).listModels(pluginId, providerParams())
        }.exceptionOrNull()

        assertTrue(failure is PluginHostException)
        assertEquals("PLUGIN_INACTIVE", (failure as PluginHostException).code)
    }
}
