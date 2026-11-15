package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginModelAccessDefinition
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginModelServiceTest {

    private val pluginId = "demo.team"
    private val providerId = "provider-1"
    private val modelId = "model-1"

    private fun metadata(model: String = modelId): ModelMetadata = ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 100_000,
        inputTokens = 90_000,
        outputTokens = 4_096,
        supportsTools = true,
        supportsCustomTemperature = true,
        modelType = ModelMetadata.ModelType.CHAT,
        source = ModelMetadata.Source.MODELS_DEV
    )

    private fun config(
        id: String = providerId,
        models: List<String> = listOf(modelId)
    ): AIProviderConfig = AIProviderConfig(
        id = id,
        name = "Provider",
        type = ProviderType.OPENAI,
        apiKey = "secret-key",
        baseUrl = "https://example.com/v1",
        defaultModel = modelId,
        models = models,
        selectedModel = modelId
    )

    private fun installedPlugin(declared: Boolean = true): InstalledPlugin = InstalledPlugin(
        record = PluginRecord(
            id = pluginId,
            version = "1.0.0",
            enabled = true,
            installedAt = 1L,
            origin = "local",
            archiveSha256 = "",
            manifestSha256 = ""
        ),
        status = com.aicode.feature.plugin.domain.PluginStatus.OK,
        manifest = mockk<PluginManifest>(),
        content = PluginContent(
            modelAccess = if (declared) PluginModelAccessDefinition(pluginId, "team chat") else null
        )
    )

    private fun service(
        declared: Boolean = true,
        provider: AIProvider = mockk(relaxed = true),
        providerConfig: AIProviderConfig = config()
    ): PluginModelService {
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.plugins } returns MutableStateFlow(listOf(installedPlugin(declared)))

        val repository = mockk<AIProviderRepository>(relaxed = true)
        coEvery { repository.getProviderById(any()) } returns providerConfig
        every { repository.getAllProviders() } returns flowOf(listOf(providerConfig))

        val metadataService = mockk<ModelMetadataService>(relaxed = true)
        coEvery { metadataService.resolve(any(), any(), any()) } returns metadata(providerConfig.selectedModel)
        coEvery { metadataService.resolveAll(any(), any(), any()) } answers {
            val ids = thirdArg<List<String>>()
            ids.associateWith { metadata(it) }
        }

        val factory = mockk<ProviderInstanceFactory>(relaxed = true)
        coEvery { factory.create(any(), any()) } returns provider

        val defaultModel = mockk<DefaultModelSettingsRepository>(relaxed = true)
        coEvery { defaultModel.getDefaultProviderId() } returns providerId

        return PluginModelService(
            pluginManager = pluginManager,
            providerRepository = repository,
            providerFactory = factory,
            modelMetadataService = metadataService,
            defaultModelSettingsRepository = defaultModel
        )
    }

    private fun createHandle(service: PluginModelService): String = runBlocking {
        val created = service.create(pluginId, buildJsonObject {
            put("providerId", providerId)
            put("model", modelId)
            put("systemPrompt", "You are a team member")
        }).jsonObject
        created.getValue("handle").jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun startStream(service: PluginModelService, handleId: String, text: String = "hi"): String =
        runBlocking {
            val started = service.start(pluginId, buildJsonObject {
                put("handleId", handleId)
                put("messages", JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    }
                )))
            }, "process").jsonObject
            started.getValue("streamId").jsonPrimitive.content
        }

    @Test
    fun listProviders_doesNotExposeSecrets() = runBlocking {
        val service = service()
        val result = service.listProviders(pluginId).jsonObject
        val provider = result.getValue("providers").jsonArray.single().jsonObject
        assertEquals(providerId, provider.getValue("id").jsonPrimitive.content)
        assertEquals("openai", provider.getValue("type").jsonPrimitive.content)
        assertFalse(provider.containsKey("apiKey"))
        assertFalse(provider.containsKey("baseUrl"))
        assertFalse(provider.containsKey("customHeaders"))
    }

    @Test
    fun modelAccessMustBeDeclared() = runBlocking {
        val service = service(declared = false)
        val error = runCatching { service.listProviders(pluginId) }.exceptionOrNull()
        assertTrue(error is PluginHostException)
        assertEquals("MODEL_ACCESS_NOT_DECLARED", (error as PluginHostException).code)
    }

    @Test
    fun createRejectsModelOutsideProviderList() = runBlocking {
        val service = service()
        val error = runCatching {
            service.create(pluginId, buildJsonObject {
                put("providerId", providerId)
                put("model", "unknown-model")
            })
        }.exceptionOrNull()
        assertTrue(error is PluginHostException)
        assertEquals("MODEL_NOT_AVAILABLE", (error as PluginHostException).code)
    }

    @Test
    fun createRejectsOwnPluginProvider() = runBlocking {
        val ownProvider = "plugin:$pluginId:gateway"
        val service = service(providerConfig = config(id = ownProvider))
        val error = runCatching {
            service.create(pluginId, buildJsonObject {
                put("providerId", ownProvider)
                put("model", modelId)
            })
        }.exceptionOrNull()
        assertTrue(error is PluginHostException)
        assertEquals("SELF_PROVIDER_FORBIDDEN", (error as PluginHostException).code)
    }

    @Test
    fun streamReturnsTextAndFinal() = runBlocking {
        val provider = mockk<AIProvider>(relaxed = true)
        every { provider.completeStream(any(), any(), any(), any()) } returns flowOf(
            AIStreamChunk.TextDelta("hello "),
            AIStreamChunk.TextDelta("team"),
            AIStreamChunk.Final(
                AIResponse(
                    content = "hello team",
                    stopReason = "stop",
                    inputTokens = 12,
                    outputTokens = 3
                )
            )
        )
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val streamId = startStream(service, handleId)

        var cursor = 0
        var text = ""
        var final: JsonObject? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val result = service.read(pluginId, buildJsonObject {
                put("streamId", streamId)
                put("cursor", cursor)
                put("waitMs", 500)
            }).jsonObject
            cursor = result.getValue("cursor").jsonPrimitive.int
            result.getValue("events").jsonArray.forEach { element ->
                val event = element.jsonObject
                if (event.getValue("type").jsonPrimitive.content == "text") {
                    text += event.getValue("text").jsonPrimitive.content
                }
            }
            if (result.getValue("done").jsonPrimitive.boolean) {
                assertEquals("success", result.getValue("status").jsonPrimitive.content)
                final = result.getValue("final").jsonObject
                break
            }
        }

        assertEquals("hello team", text)
        assertEquals("hello team", final?.getValue("content")?.jsonPrimitive?.content)
        assertEquals(12, final?.getValue("usage")?.jsonObject?.getValue("input")?.jsonPrimitive?.int)
    }

    @Test
    fun cancelMarksStreamCancelled() = runBlocking {
        val provider = mockk<AIProvider>(relaxed = true)
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.TextDelta("partial"))
            kotlinx.coroutines.delay(30_000)
            emit(AIStreamChunk.Final(AIResponse(content = "done")))
        }
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val streamId = startStream(service, handleId)

        // 先读到第一段文本，确保请求已经真正开始。
        val first = service.read(pluginId, buildJsonObject {
            put("streamId", streamId)
            put("cursor", 0)
            put("waitMs", 2_000)
        }).jsonObject
        assertTrue(first.getValue("events").jsonArray.isNotEmpty())

        service.cancel(pluginId, buildJsonObject { put("streamId", streamId) })

        val after = service.read(pluginId, buildJsonObject {
            put("streamId", streamId)
            put("cursor", first.getValue("cursor").jsonPrimitive.int)
            put("waitMs", 1_000)
        }).jsonObject
        assertTrue(after.getValue("done").jsonPrimitive.boolean)
        assertEquals("cancelled", after.getValue("status").jsonPrimitive.content)
    }
}
