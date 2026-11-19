package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.BackendCapabilities
import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.ExecutionBackendRegistry
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginModelAccessDefinition
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

@OptIn(ExperimentalCoroutinesApi::class)
class PluginModelServiceTest {

    private val pluginId = "demo.team"
    private val providerId = "provider-1"
    private val modelId = "model-1"

    private class CapturedRequest(
        val systemPrompt: String,
        val messages: List<AgentMessage>,
        val tools: List<AgentTool>
    )

    private fun metadata(
        model: String = modelId,
        contextTokens: Int = 100_000,
        inputTokens: Int? = 90_000,
        outputTokens: Int? = 4_096
    ): ModelMetadata = ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
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

    private fun installedPlugin(
        declared: Boolean = true,
        enabled: Boolean = true,
        version: String = "1.0.0",
        installRevision: String? = null,
        installedAt: Long = 1L,
        archiveSha256: String = ""
    ): InstalledPlugin = InstalledPlugin(
        record = PluginRecord(
            id = pluginId,
            version = version,
            enabled = enabled,
            installedAt = installedAt,
            origin = "local",
            archiveSha256 = archiveSha256,
            manifestSha256 = "",
            installRevision = installRevision
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
        providerConfig: AIProviderConfig = config(),
        modelMetadata: ModelMetadata = metadata(),
        toolRegistry: ToolRegistry = ToolRegistry(),
        fileAccess: FileAccessProvider = mockk(relaxed = true),
        plugins: MutableStateFlow<List<InstalledPlugin>> = MutableStateFlow(listOf(installedPlugin(declared))),
        providerFactory: ProviderInstanceFactory? = null,
        modelMetadataService: ModelMetadataService? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ): PluginModelService {
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.plugins } returns plugins

        val repository = mockk<AIProviderRepository>(relaxed = true)
        coEvery { repository.getProviderById(any()) } returns providerConfig
        every { repository.getAllProviders() } returns flowOf(listOf(providerConfig))

        val metadataService = modelMetadataService ?: mockk<ModelMetadataService>(relaxed = true).also {
            coEvery { it.resolve(any(), any(), any()) } returns modelMetadata
            coEvery { it.resolveAll(any(), any(), any()) } answers {
                val ids = thirdArg<List<String>>()
                ids.associateWith { metadata(it) }
            }
        }

        val factory = providerFactory ?: mockk<ProviderInstanceFactory>(relaxed = true).also {
            coEvery { it.create(any(), any()) } returns provider
        }

        val defaultModel = mockk<DefaultModelSettingsRepository>(relaxed = true)
        coEvery { defaultModel.getDefaultProviderId() } returns providerId

        val homeResolver = mockk<PathHomeResolver>()
        every { homeResolver.home() } returns "/root"
        every { homeResolver.aicodeRoot() } returns "/root/.aicode"
        every { homeResolver.expandHome(any()) } answers { firstArg<String>() }

        val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaceRepository.currentPath() } returns "$WORKSPACE"

        val executionModeHolder = mockk<ExecutionModeHolder>(relaxed = true)
        val localBackend = mockk<ExecutionBackend>(relaxed = true) {
            every { id } returns ExecutionBackendRegistry.BUILTIN_LOCAL_PROOT_ID
            every { capabilities } returns BackendCapabilities(isRemoteEnvironment = false)
            every { workspaceRootDisplay(any()) } returns "~/workspace"
        }
        every { executionModeHolder.currentMode() } returns ExecutionMode.LOCAL_PROOT
        every { executionModeHolder.currentBackend() } returns localBackend

        return PluginModelService(
            pluginManager = pluginManager,
            providerRepository = repository,
            providerFactory = factory,
            modelMetadataService = metadataService,
            defaultModelSettingsRepository = defaultModel,
            toolRegistry = toolRegistry,
            fileAccess = fileAccess,
            homeResolver = homeResolver,
            workspaceRepository = workspaceRepository,
            executionModeHolder = executionModeHolder,
            scope = scope
        )
    }

    private suspend fun createHandle(service: PluginModelService): String {
        val created = service.create(pluginId, buildJsonObject {
            put("providerId", providerId)
            put("model", modelId)
            put("systemPrompt", "You are a team member")
        }).jsonObject
        return created.getValue("handle").jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun capturingProvider(
        response: AIResponse = AIResponse(content = "ok")
    ): Pair<AIProvider, CompletableDeferred<CapturedRequest>> {
        val provider = mockk<AIProvider>(relaxed = true)
        val captured = CompletableDeferred<CapturedRequest>()
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            captured.complete(
                CapturedRequest(
                    systemPrompt = firstArg<String>(),
                    messages = secondArg<List<AgentMessage>>(),
                    tools = thirdArg<List<AgentTool>>()
                )
            )
            flowOf(AIStreamChunk.Final(response))
        }
        return provider to captured
    }

    private suspend fun startCapturing(
        service: PluginModelService,
        handleId: String,
        messages: JsonArray,
        tools: JsonArray? = null,
        captured: CompletableDeferred<CapturedRequest>
    ): CapturedRequest {
        service.start(pluginId, buildJsonObject {
            put("handleId", handleId)
            put("messages", messages)
            tools?.let { put("tools", it) }
        }, "process")
        return withTimeout(5_000) { captured.await() }
    }

    private suspend fun startError(
        service: PluginModelService,
        handleId: String,
        messages: JsonArray,
        tools: JsonArray? = null
    ): PluginHostException? = runCatching {
        service.start(pluginId, buildJsonObject {
            put("handleId", handleId)
            put("messages", messages)
            tools?.let { put("tools", it) }
        }, "process")
    }.exceptionOrNull() as? PluginHostException

    private suspend fun assertStartCode(
        service: PluginModelService,
        handleId: String,
        messages: JsonArray,
        code: String,
        tools: JsonArray? = null
    ) {
        val error = startError(service, handleId, messages, tools)
        assertTrue("expected PluginHostException but was $error", error is PluginHostException)
        assertEquals(code, error?.code)
    }

    private suspend fun readUntilDone(service: PluginModelService, streamId: String): JsonObject {
        var cursor = 0
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val result = service.read(pluginId, buildJsonObject {
                put("streamId", streamId)
                put("cursor", cursor)
                put("waitMs", 500)
            }).jsonObject
            cursor = result.getValue("cursor").jsonPrimitive.int
            if (result.getValue("done").jsonPrimitive.boolean) return result
        }
        error("stream did not finish in time")
    }

    private suspend fun startStream(
        service: PluginModelService,
        handleId: String,
        messages: JsonArray = JsonArray(listOf(userMessage("hi")))
    ): String = service.start(pluginId, buildJsonObject {
        put("handleId", handleId)
        put("messages", messages)
    }, "process").jsonObject.getValue("streamId").jsonPrimitive.content

    private suspend fun readBatch(
        service: PluginModelService,
        streamId: String,
        cursor: Int,
        maxEvents: Int = 500
    ): JsonObject = service.read(pluginId, buildJsonObject {
        put("streamId", streamId)
        put("cursor", cursor)
        put("maxEvents", maxEvents)
        put("waitMs", 0)
    }).jsonObject

    private fun assertPendingBatch(result: JsonObject) {
        assertFalse(result.getValue("done").jsonPrimitive.boolean)
        assertFalse(result.containsKey("status"))
        assertFalse(result.containsKey("final"))
        assertFalse(result.containsKey("error"))
    }

    private fun userMessage(content: String = "hi", images: JsonArray? = null): JsonObject =
        buildJsonObject {
            put("role", "user")
            put("content", content)
            images?.let { put("images", it) }
        }

    private fun assistantMessage(
        content: String = "",
        toolCalls: JsonArray? = null,
        reasoning: String? = null,
        providerState: JsonObject? = null
    ): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", content)
        toolCalls?.let { put("toolCalls", it) }
        reasoning?.let { put("reasoning", it) }
        providerState?.let { put("providerState", it) }
    }

    private fun toolMessage(
        toolCallId: String,
        name: String,
        content: String,
        images: JsonArray? = null
    ): JsonObject = buildJsonObject {
        put("role", "tool")
        put("toolCallId", toolCallId)
        put("name", name)
        put("content", content)
        images?.let { put("images", it) }
    }

    private fun toolCallJson(
        id: String,
        name: String,
        arguments: JsonObject = JsonObject(emptyMap())
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("arguments", arguments)
    }

    private fun imageJson(
        mimeType: String = "image/png",
        path: String? = null,
        base64: String? = null
    ): JsonObject = buildJsonObject {
        put("mimeType", mimeType)
        path?.let { put("path", it) }
        base64?.let { put("base64", it) }
    }

    private fun appTool(name: String): JsonObject = buildJsonObject {
        put("type", "app")
        put("name", name)
    }

    private fun customTool(
        name: String,
        description: String? = null,
        inputSchema: JsonElement? = null
    ): JsonObject = buildJsonObject {
        put("type", "custom")
        put("name", name)
        description?.let { put("description", it) }
        inputSchema?.let { put("inputSchema", it) }
    }

    private fun fakeTool(toolName: String, toolDescription: String = "fake tool"): AgentTool =
        object : AgentTool() {
            override val name = toolName
            override val description = toolDescription
            override val parameters: Map<String, ToolParameter> = emptyMap()
            override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
                ToolResult.Success(JsonPrimitive("ok"))
        }

    private fun stubFile(fileAccess: FileAccessProvider, path: String, bytes: ByteArray) {
        every { fileAccess.exists(path) } returns true
        every { fileAccess.isDirectory(path) } returns false
        every { fileAccess.fileSize(path) } returns bytes.size.toLong()
        every { fileAccess.readBytes(path) } returns bytes
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
    fun streamDefersEveryTerminalStatusUntilAllEventBatchesAreDrained() = runTest {
        for (status in listOf("success", "error", "cancelled")) {
            val provider = mockk<AIProvider>(relaxed = true)
            every { provider.completeStream(any(), any(), any(), any()) } returns flow {
                repeat(5) { emit(AIStreamChunk.ToolCallDeclared("tool-$it")) }
                when (status) {
                    "success" -> emit(AIStreamChunk.Final(AIResponse(content = "ok")))
                    "error" -> throw IllegalStateException("failed")
                    else -> throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }
            val service = service(provider = provider, scope = backgroundScope)
            val streamId = startStream(service, createHandle(service))
            val initial = readBatch(service, streamId, 0)
            assertPendingBatch(initial)
            assertTrue(initial.getValue("events").jsonArray.isEmpty())
            testScheduler.runCurrent()

            val names = mutableListOf<String>()
            var cursor = 0
            for (batch in 0..2) {
                val result = readBatch(service, streamId, cursor, maxEvents = 2)
                val events = result.getValue("events").jsonArray
                names += events.map { it.jsonObject.getValue("name").jsonPrimitive.content }
                cursor += events.size
                assertEquals(cursor, result.getValue("cursor").jsonPrimitive.int)
                if (batch < 2) {
                    assertPendingBatch(result)
                } else {
                    assertTrue(result.getValue("done").jsonPrimitive.boolean)
                    assertEquals(status, result.getValue("status").jsonPrimitive.content)
                    assertEquals(status == "success", result.containsKey("final"))
                    assertEquals(status == "error", result.containsKey("error"))
                }
                if (batch == 0) {
                    for (invalidCursor in listOf(0, 6)) {
                        val error = runCatching {
                            readBatch(service, streamId, invalidCursor)
                        }.exceptionOrNull()
                        assertTrue(error is PluginHostException)
                        assertEquals("CURSOR_INVALID", (error as PluginHostException).code)
                    }
                }
            }
            assertEquals((0..4).map { "tool-$it" }, names)
            assertEquals(5, cursor)
            val repeated = readBatch(service, streamId, cursor)
            assertTrue(repeated.getValue("done").jsonPrimitive.boolean)
            assertTrue(repeated.getValue("events").jsonArray.isEmpty())
            assertEquals(cursor, repeated.getValue("cursor").jsonPrimitive.int)
            assertEquals(status, repeated.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun streamDefersDoneUntilSizeLimitedBatchesAreDrained() = runTest {
        val text = "x".repeat(16 * 1024)
        val provider = mockk<AIProvider>(relaxed = true)
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            repeat(32) { emit(AIStreamChunk.TextDelta(text)) }
            emit(AIStreamChunk.Final(AIResponse(content = "ok")))
        }
        val service = service(provider = provider, scope = backgroundScope)
        val streamId = startStream(service, createHandle(service))
        testScheduler.runCurrent()

        val received = StringBuilder()
        var cursor = 0
        for (batch in 0..2) {
            val result = readBatch(service, streamId, cursor)
            val events = result.getValue("events").jsonArray
            assertTrue(events.isNotEmpty())
            assertTrue(events.sumOf { it.toString().toByteArray(Charsets.UTF_8).size } <= 256 * 1024)
            events.forEach { received.append(it.jsonObject.getValue("text").jsonPrimitive.content) }
            cursor += events.size
            assertEquals(cursor, result.getValue("cursor").jsonPrimitive.int)
            if (batch < 2) {
                assertPendingBatch(result)
            } else {
                assertTrue(result.getValue("done").jsonPrimitive.boolean)
                assertEquals("success", result.getValue("status").jsonPrimitive.content)
                assertEquals("ok", result.getValue("final").jsonObject.getValue("content").jsonPrimitive.content)
            }
        }
        assertEquals(32, cursor)
        assertEquals(text.repeat(32), received.toString())
    }

    @Test
    fun createRejectsPluginClosedOrReplacedDuringMetadataPreflight() = runTest {
        for (closePlugin in listOf(false, true)) {
            val plugins = MutableStateFlow(listOf(installedPlugin()))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val metadataService = mockk<ModelMetadataService>()
            coEvery { metadataService.resolve(any(), any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                metadata()
            }
            val service = service(
                plugins = plugins,
                modelMetadataService = metadataService,
                scope = backgroundScope
            )
            val creating = async { runCatching { createHandle(service) }.exceptionOrNull() }
            testScheduler.runCurrent()
            assertTrue(entered.isCompleted)

            if (closePlugin) service.closePlugin(pluginId)
            else plugins.value = listOf(installedPlugin(installRevision = "new-install"))
            release.complete(Unit)
            testScheduler.runCurrent()

            val error = creating.await()
            assertTrue(error is PluginHostException)
            assertEquals("PLUGIN_INACTIVE", (error as PluginHostException).code)
            assertTrue(createHandle(service).isNotBlank())
        }
    }

    @Test
    fun startRejectsHandleClosedDuringMetadataPreflight() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val metadataService = mockk<ModelMetadataService>(relaxed = true)
        coEvery { metadataService.resolve(any(), any(), any()) } returns metadata()
        val service = service(
            provider = provider,
            modelMetadataService = metadataService,
            scope = backgroundScope
        )
        val handleId = createHandle(service)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { metadataService.resolve(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            metadata()
        }
        val starting = async {
            startError(service, handleId, JsonArray(listOf(userMessage())))
        }
        testScheduler.runCurrent()
        assertTrue(entered.isCompleted)

        service.close(pluginId, buildJsonObject { put("handleId", handleId) })
        release.complete(Unit)
        testScheduler.runCurrent()

        assertEquals("HANDLE_NOT_FOUND", starting.await()?.code)
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test
    fun startRejectsPluginClosedDuringProviderPreflight() = runTest {
        val provider = mockk<AIProvider>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = mockk<ProviderInstanceFactory>()
        coEvery { factory.create(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            provider
        }
        val service = service(providerFactory = factory, scope = backgroundScope)
        val handleId = createHandle(service)
        val starting = async {
            startError(service, handleId, JsonArray(listOf(userMessage())))
        }
        testScheduler.runCurrent()
        assertTrue(entered.isCompleted)

        service.closePlugin(pluginId)
        release.complete(Unit)
        testScheduler.runCurrent()

        assertEquals("HANDLE_NOT_FOUND", starting.await()?.code)
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test
    fun startRechecksInactiveOrReplacedPluginAfterPreflight() = runTest {
        for (replacement in listOf(
            installedPlugin(enabled = false),
            installedPlugin(version = "2.0.0"),
            installedPlugin(installRevision = "new-install"),
            installedPlugin(installedAt = 2L),
            installedPlugin(archiveSha256 = "new-archive")
        )) {
            val plugins = MutableStateFlow(listOf(installedPlugin()))
            val provider = mockk<AIProvider>(relaxed = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val factory = mockk<ProviderInstanceFactory>()
            coEvery { factory.create(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                provider
            }
            val service = service(plugins = plugins, providerFactory = factory, scope = backgroundScope)
            val handleId = createHandle(service)
            val starting = async {
                startError(service, handleId, JsonArray(listOf(userMessage())))
            }
            testScheduler.runCurrent()
            assertTrue(entered.isCompleted)

            plugins.value = listOf(replacement)
            release.complete(Unit)
            testScheduler.runCurrent()

            val code = if (replacement.isActive) "HANDLE_NOT_FOUND" else "PLUGIN_INACTIVE"
            assertEquals(code, starting.await()?.code)
            verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
        }
    }

    @Test
    fun sameVersionReinstallInvalidatesExistingHandle() = runTest {
        val plugins = MutableStateFlow(listOf(installedPlugin(installRevision = "old-install")))
        val provider = mockk<AIProvider>(relaxed = true)
        val service = service(provider = provider, plugins = plugins, scope = backgroundScope)
        val handleId = createHandle(service)

        plugins.value = listOf(installedPlugin(installRevision = "new-install"))

        assertStartCode(service, handleId, JsonArray(listOf(userMessage())), "HANDLE_NOT_FOUND")
        testScheduler.runCurrent()
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test
    fun closeDuringStreamDispatchCannotMissJobCancellation() = runTest {
        for (closePlugin in listOf(false, true)) {
            var onDispatch: (() -> Unit)? = null
            val delegate = StandardTestDispatcher(testScheduler)
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    val callback = onDispatch
                    onDispatch = null
                    callback?.invoke()
                    delegate.dispatch(context, block)
                }
            }
            val provider = mockk<AIProvider>(relaxed = true)
            every { provider.completeStream(any(), any(), any(), any()) } returns
                flowOf(AIStreamChunk.Final(AIResponse(content = "ok")))
            val service = service(
                provider = provider,
                scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
            )
            val handleId = createHandle(service)
            var closed = false
            onDispatch = {
                if (closePlugin) service.closePlugin(pluginId)
                else service.close(pluginId, buildJsonObject { put("handleId", handleId) })
                closed = true
            }
            val streamId = startStream(service, handleId)
            testScheduler.runCurrent()
            assertTrue(closed)

            val result = readBatch(service, streamId, 0)
            assertTrue(result.getValue("done").jsonPrimitive.boolean)
            assertEquals("cancelled", result.getValue("status").jsonPrimitive.content)
            assertTrue(result.getValue("events").jsonArray.isEmpty())
            verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
        }
    }

    @Test
    fun closingRunningStreamsCancelsProviderAndDrainsBufferedEvents() = runTest {
        for (close in listOf("cancel", "handle", "plugin", "origin")) {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val provider = mockk<AIProvider>(relaxed = true)
            every { provider.completeStream(any(), any(), any(), any()) } returns flow {
                try {
                    repeat(3) { emit(AIStreamChunk.ToolCallDeclared("tool-$it")) }
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val service = service(provider = provider, scope = backgroundScope)
            val handleId = createHandle(service)
            val streamId = startStream(service, handleId)
            testScheduler.runCurrent()
            assertTrue(entered.isCompleted)

            when (close) {
                "cancel" -> service.cancel(pluginId, buildJsonObject { put("streamId", streamId) })
                "handle" -> service.close(pluginId, buildJsonObject { put("handleId", handleId) })
                "plugin" -> service.closePlugin(pluginId)
                else -> service.closeOrigin("process")
            }
            testScheduler.runCurrent()
            assertTrue(cancelled.isCompleted)

            for (cursor in 0..2) {
                val result = readBatch(service, streamId, cursor, maxEvents = 1)
                val event = result.getValue("events").jsonArray.single().jsonObject
                assertEquals("tool-$cursor", event.getValue("name").jsonPrimitive.content)
                assertEquals(cursor + 1, result.getValue("cursor").jsonPrimitive.int)
                if (cursor < 2) assertPendingBatch(result)
                else {
                    assertTrue(result.getValue("done").jsonPrimitive.boolean)
                    assertEquals("cancelled", result.getValue("status").jsonPrimitive.content)
                }
            }
        }
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

    @Test
    fun appToolFromRegistryIsPassedToProvider() = runBlocking {
        val registry = ToolRegistry()
        val tool = fakeTool("readFile")
        registry.register("readFile", tool)
        val (provider, captured) = capturingProvider()
        val service = service(provider = provider, toolRegistry = registry)
        val handleId = createHandle(service)

        val request = startCapturing(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(userMessage())),
            tools = JsonArray(listOf(appTool("readFile"))),
            captured = captured
        )

        assertEquals(listOf("readFile"), request.tools.map { it.name })
        assertEquals("fake tool", request.tools.single().description)
    }

    @Test
    fun pluginNamespacedAppToolIsRejected() = runBlocking {
        val registry = ToolRegistry()
        registry.register("plugin__demo__echo", fakeTool("plugin__demo__echo"))
        val service = service(toolRegistry = registry)
        val handleId = createHandle(service)

        assertStartCode(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(userMessage())),
            code = "TOOL_NOT_ALLOWED",
            tools = JsonArray(listOf(appTool("plugin__demo__echo")))
        )
    }

    @Test
    fun unknownAppToolIsRejected() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        assertStartCode(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(userMessage())),
            code = "TOOL_NOT_FOUND",
            tools = JsonArray(listOf(appTool("missingTool")))
        )
    }

    @Test
    fun customToolSchemaIsPassedToProvider() = runBlocking {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "搜索词")
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        }
        val (provider, captured) = capturingProvider()
        val service = service(provider = provider)
        val handleId = createHandle(service)

        val request = startCapturing(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(userMessage())),
            tools = JsonArray(listOf(customTool("lookup_docs", "查询文档", schema))),
            captured = captured
        )

        val tool = request.tools.single()
        assertEquals("lookup_docs", tool.name)
        assertEquals("查询文档", tool.description)
        assertEquals(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "搜索词")
                ),
                "required" to listOf("query")
            ),
            tool.toJsonSchema()
        )
        val error = runCatching { tool.execute(emptyMap()) }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
    }

    @Test
    fun customToolValidationRejectsBadDefinitions() = runBlocking {
        val service = service()
        val handleId = createHandle(service)
        val messages = JsonArray(listOf(userMessage()))

        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            customTool("bad name")
        )))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            customTool("same"),
            customTool("same")
        )))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            customTool("long_description", "x".repeat(601))
        )))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            customTool("bad_schema", inputSchema = JsonPrimitive("not an object"))
        )))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            customTool(
                "oversized_schema",
                inputSchema = buildJsonObject { put("description", "x".repeat(65 * 1024)) }
            )
        )))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(
            (1..5).map { index ->
                customTool(
                    "schema_$index",
                    inputSchema = buildJsonObject { put("description", "x".repeat(60 * 1024)) }
                )
            }
        ))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(
            (1..65).map { index -> customTool("tool_$index") }
        ))
        assertStartCode(service, handleId, messages, "PARAM_INVALID", JsonArray(listOf(
            buildJsonObject {
                put("type", "unknown")
                put("name", "whatever")
            }
        )))
    }

    @Test
    fun toolsMustBeAnArray() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        val error = runCatching {
            service.start(pluginId, buildJsonObject {
                put("handleId", handleId)
                put("messages", JsonArray(listOf(userMessage())))
                put("tools", "not-an-array")
            }, "process")
        }.exceptionOrNull()

        assertTrue(error is PluginHostException)
        assertEquals("PARAM_INVALID", (error as PluginHostException).code)
    }

    @Test
    fun toolCallsAndToolMessagesAreMapped() = runBlocking {
        val (provider, captured) = capturingProvider()
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val thinkingBlocks = """[{"type":"thinking","thinking":"plan"}]"""

        val request = startCapturing(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(
                userMessage("查一下"),
                assistantMessage(
                    content = "",
                    toolCalls = JsonArray(listOf(
                        toolCallJson(
                            id = "call_1",
                            name = "lookup",
                            arguments = buildJsonObject { put("query", "kotlin") }
                        )
                    )),
                    reasoning = "先查文档",
                    providerState = buildJsonObject {
                        put("signature", "sig-1")
                        put("thinkingBlocksJson", thinkingBlocks)
                    }
                ),
                toolMessage(
                    toolCallId = "call_1",
                    name = "lookup",
                    content = "结果正文",
                    images = JsonArray(listOf(
                        imageJson(mimeType = "image/webp", base64 = "AQID")
                    ))
                )
            )),
            captured = captured
        )

        val assistant = request.messages[1] as AgentMessage.AssistantMessage
        assertEquals("call_1", assistant.toolCalls.single().id)
        assertEquals("lookup", assistant.toolCalls.single().name)
        assertEquals("kotlin", assistant.toolCalls.single().arguments.getValue("query").jsonPrimitive.content)
        assertEquals("先查文档", assistant.reasoning)
        assertEquals("sig-1", assistant.signature)
        assertEquals(thinkingBlocks, assistant.thinkingBlocksJson)

        val toolResult = request.messages[2] as AgentMessage.ToolResultMessage
        assertEquals("call_1", toolResult.id)
        assertEquals("lookup", toolResult.toolName)
        assertEquals("结果正文", toolResult.result)
        assertEquals("image/webp", toolResult.images.single().mimeType)
        assertEquals("AQID", toolResult.images.single().base64Data)
    }

    @Test
    fun orphanToolMessageIsRejected() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        assertStartCode(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(
                userMessage("hi"),
                toolMessage("call_missing", "lookup", "结果")
            )),
            code = "PARAM_INVALID"
        )
    }

    @Test
    fun toolCallsValidationRejectsBadEntries() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        val tooMany = JsonArray((1..33).map { index -> toolCallJson("call_$index", "lookup") })
        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(content = "ok", toolCalls = tooMany)
        )), "PARAM_INVALID")

        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(
                content = "ok",
                toolCalls = JsonArray(listOf(toolCallJson("", "lookup")))
            )
        )), "PARAM_INVALID")

        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(
                content = "ok",
                toolCalls = JsonArray(listOf(toolCallJson("call_1", "")))
            )
        )), "PARAM_INVALID")

        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(
                content = "ok",
                toolCalls = JsonArray(listOf(
                    buildJsonObject {
                        put("id", "call_1")
                        put("name", "lookup")
                        put("arguments", "not-an-object")
                    }
                ))
            )
        )), "PARAM_INVALID")

        val bigArguments = buildJsonObject { put("payload", "x".repeat(40 * 1024)) }
        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(
                content = "ok",
                toolCalls = JsonArray(listOf(
                    toolCallJson("call_1", "lookup", bigArguments),
                    toolCallJson("call_2", "lookup", bigArguments)
                ))
            )
        )), "PARAM_INVALID")
    }

    @Test
    fun reasoningAndProviderStateLimitsAreEnforced() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(content = "ok", reasoning = "x".repeat(64 * 1024 + 1))
        )), "PARAM_INVALID")

        assertStartCode(service, handleId, JsonArray(listOf(
            assistantMessage(
                content = "ok",
                providerState = buildJsonObject {
                    put("signature", "s".repeat(200 * 1024))
                    put("thinkingBlocksJson", "t".repeat(60 * 1024))
                }
            )
        )), "PARAM_INVALID")
    }

    @Test
    fun messageTextLimitsAreEnforced() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage("x".repeat(128 * 1024 + 1))
        )), "PARAM_INVALID")

        assertStartCode(service, handleId, JsonArray(
            (1..5).map { userMessage("x".repeat(110 * 1024)) }
        ), "PARAM_INVALID")
    }

    @Test
    fun imageFromPathIsLoadedWithPriority() = runBlocking {
        val fileAccess = mockk<FileAccessProvider>(relaxed = true)
        stubFile(fileAccess, "$WORKSPACE/pic.png", byteArrayOf(1, 2, 3))
        val (provider, captured) = capturingProvider()
        val service = service(provider = provider, fileAccess = fileAccess)
        val handleId = createHandle(service)

        val request = startCapturing(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(
                userMessage(
                    content = "",
                    images = JsonArray(listOf(
                        imageJson(mimeType = "image/png", path = "pic.png", base64 = "AAAA")
                    ))
                )
            )),
            captured = captured
        )

        val image = (request.messages.single() as AgentMessage.UserMessage).images.single()
        assertEquals("image/png", image.mimeType)
        assertEquals("AQID", image.base64Data)
        assertEquals("pic.png", image.path)
    }

    @Test
    fun imageFromBase64IsAccepted() = runBlocking {
        val (provider, captured) = capturingProvider()
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))

        val request = startCapturing(
            service = service,
            handleId = handleId,
            messages = JsonArray(listOf(
                userMessage(
                    content = "",
                    images = JsonArray(listOf(
                        imageJson(mimeType = "image/jpeg", base64 = encoded)
                    ))
                )
            )),
            captured = captured
        )

        val image = (request.messages.single() as AgentMessage.UserMessage).images.single()
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(encoded, image.base64Data)
    }

    @Test
    fun unsupportedImageTypeIsRejected() = runBlocking {
        val service = service()
        val handleId = createHandle(service)

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray(listOf(imageJson(mimeType = "image/bmp", base64 = "AQID")))
            )
        )), "IMAGE_TYPE_UNSUPPORTED")
    }

    @Test
    fun imageLimitsAreEnforced() = runBlocking {
        val fileAccess = mockk<FileAccessProvider>(relaxed = true)
        every { fileAccess.exists("$WORKSPACE/huge.png") } returns true
        every { fileAccess.isDirectory("$WORKSPACE/huge.png") } returns false
        every { fileAccess.fileSize("$WORKSPACE/huge.png") } returns 8L * 1024 * 1024 + 1
        val bigImage = ByteArray(6 * 1024 * 1024)
        stubFile(fileAccess, "$WORKSPACE/big.png", bigImage)
        val service = service(fileAccess = fileAccess)
        val handleId = createHandle(service)
        val tiny = Base64.getEncoder().encodeToString(byteArrayOf(1))

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray((1..5).map { imageJson(base64 = tiny) })
            )
        )), "IMAGE_TOO_LARGE")

        assertStartCode(service, handleId, JsonArray(
            (1..3).map {
                userMessage(
                    content = "",
                    images = JsonArray((1..3).map { imageJson(base64 = tiny) })
                )
            }
        ), "IMAGE_TOO_LARGE")

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray(listOf(imageJson(path = "huge.png")))
            )
        )), "IMAGE_TOO_LARGE")

        val oversizedInline = Base64.getEncoder().encodeToString(ByteArray(1024 * 1024 + 1))
        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray(listOf(imageJson(base64 = oversizedInline)))
            )
        )), "IMAGE_TOO_LARGE")

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray((1..3).map { imageJson(path = "big.png") })
            )
        )), "IMAGE_TOO_LARGE")
    }

    @Test
    fun inlineImageTotalIsCapped() = runBlocking {
        val service = service()
        val handleId = createHandle(service)
        val chunk = Base64.getEncoder().encodeToString(ByteArray(700 * 1024))

        assertStartCode(service, handleId, JsonArray(listOf(
            userMessage(
                content = "",
                images = JsonArray((1..3).map { imageJson(base64 = chunk) })
            )
        )), "IMAGE_TOO_LARGE")
    }

    @Test
    fun finalIncludesToolCallsProviderStateAndImages() = runBlocking {
        val provider = mockk<AIProvider>(relaxed = true)
        every { provider.completeStream(any(), any(), any(), any()) } returns flowOf(
            AIStreamChunk.Final(
                AIResponse(
                    content = "done",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            name = "lookup",
                            arguments = mapOf("query" to JsonPrimitive("kotlin"))
                        )
                    ),
                    signature = "sig-1",
                    thinkingBlocksJson = """[{"type":"thinking"}]""",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "QUJD"))
                )
            )
        )
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val streamId = startStream(service, handleId)

        val result = readUntilDone(service, streamId)
        assertEquals("success", result.getValue("status").jsonPrimitive.content)
        val final = result.getValue("final").jsonObject

        val call = final.getValue("toolCalls").jsonArray.single().jsonObject
        assertEquals("call_1", call.getValue("id").jsonPrimitive.content)
        assertEquals("lookup", call.getValue("name").jsonPrimitive.content)

        val providerState = final.getValue("providerState").jsonObject
        assertEquals("sig-1", providerState.getValue("signature").jsonPrimitive.content)
        assertEquals(
            """[{"type":"thinking"}]""",
            providerState.getValue("thinkingBlocksJson").jsonPrimitive.content
        )

        val image = final.getValue("images").jsonArray.single().jsonObject
        assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
        assertEquals("QUJD", image.getValue("base64").jsonPrimitive.content)
        assertEquals(1, final.getValue("imageCount").jsonPrimitive.int)
        assertFalse(final.containsKey("imagesTruncated"))
    }

    @Test
    fun finalDropsOversizedImages() = runBlocking {
        val provider = mockk<AIProvider>(relaxed = true)
        every { provider.completeStream(any(), any(), any(), any()) } returns flowOf(
            AIStreamChunk.Final(
                AIResponse(
                    content = "done",
                    images = listOf(
                        AgentImage(mimeType = "image/png", base64Data = "QUJD"),
                        AgentImage(mimeType = "image/png", base64Data = "A".repeat(2 * 1024 * 1024 + 1))
                    )
                )
            )
        )
        val service = service(provider = provider)
        val handleId = createHandle(service)
        val streamId = startStream(service, handleId)

        val final = readUntilDone(service, streamId).getValue("final").jsonObject

        assertEquals(1, final.getValue("images").jsonArray.size)
        assertTrue(final.getValue("imagesTruncated").jsonPrimitive.boolean)
        assertEquals(2, final.getValue("imageCount").jsonPrimitive.int)
    }

    @Test
    fun toolsCountTowardContextEstimate() = runBlocking {
        val (provider, captured) = capturingProvider()
        val service = service(
            provider = provider,
            modelMetadata = metadata(contextTokens = 2_000, inputTokens = null, outputTokens = null)
        )
        val handleId = createHandle(service)
        val messages = JsonArray(listOf(userMessage("hi")))

        startCapturing(service, handleId, messages, captured = captured)

        assertStartCode(
            service = service,
            handleId = handleId,
            messages = messages,
            code = "CONTEXT_TOO_LARGE",
            tools = JsonArray(listOf(
                customTool(
                    "huge_schema",
                    inputSchema = buildJsonObject { put("description", "x".repeat(16 * 1024)) }
                )
            ))
        )
    }

    private companion object {
        const val WORKSPACE = "/root/workspace"
    }
}
