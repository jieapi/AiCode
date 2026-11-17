package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatefulAgentWorkflowTest {
    private val tools = mockk<ToolRegistry>()
    private val providers = mockk<AIProviderRepository>()
    private val factory = mockk<ProviderInstanceFactory>()
    private val prompts = mockk<SystemPromptProvider>()
    private val compactor = mockk<ContextCompactor>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val defaultSettings = mockk<DefaultModelSettingsRepository>()
    private val provider = mockk<AIProvider>(relaxed = true)
    private val context = AgentContext(null, null, "/workspace", null)
    private val workflow = StatefulAgentWorkflow(
        toolRegistry = tools,
        aiProviderRepository = providers,
        providerFactory = factory,
        promptProvider = prompts,
        permissionManager = mockk(relaxed = true),
        policyEngine = mockk(relaxed = true),
        contextCompactor = compactor,
        planApprovalManager = mockk(relaxed = true),
        toolOutputStore = mockk(relaxed = true),
        modelMetadataService = metadata,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(relaxed = true),
        defaultModelSettingsRepository = defaultSettings,
        sessionUseCase = mockk(relaxed = true),
        messagePersistenceUseCase = mockk(relaxed = true),
        checkpointManager = mockk(relaxed = true),
        llmCallRecordDao = mockk(relaxed = true),
        agentNotificationCenter = mockk(relaxed = true),
        eventInjector = mockk(relaxed = true),
        fileAccess = mockk(relaxed = true),
        pluginHookDispatcher = mockk(relaxed = true),
        appContext = mockk(relaxed = true)
    )

    @Before
    fun prepare() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1_000L
        every { tools.getTool(any()) } returns null
        every { prompts.build(any()) } returns "system"
        every { provider.providerId } returns "provider"
        every { provider.model } returns "model"
        val config = AIProviderConfig(
            id = "provider", name = "Provider", type = ProviderType.OPENAI,
            apiKey = "test-key", baseUrl = "", defaultModel = "model", models = listOf("model")
        )
        coEvery { defaultSettings.getDefaultProviderId() } returns config.id
        coEvery { defaultSettings.getDefaultModel() } returns config.defaultModel
        coEvery { providers.getProviderById(config.id) } returns config
        coEvery { factory.create(any(), any()) } returns provider
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "model", contextTokens = 128_000)
        coEvery {
            compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CompactionResult(firstArg(), compacted = false) }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private suspend fun collectEvents(): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        workflow.executeEvents("request", context, emptyList()).collect { event ->
            events.add(event)
            when (event) {
                is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                is AgentEvent.ToolCallFinished -> event.persisted?.complete(Unit)
                else -> Unit
            }
        }
        return events
    }

    @Test
    fun toolCycleKeepsStreamAndPersistedIdsAndCreatesNewIdForNextCall() = runTest {
        val requests = mutableListOf<List<AgentMessage>>()
        val toolCall = ToolCall("call", "readFile", emptyMap())
        every { provider.completeStream(any(), capture(requests), any(), any()) } returnsMany listOf(
            flowOf(
                AIStreamChunk.ReasoningDelta("thinking"),
                AIStreamChunk.TextDelta("checking"),
                AIStreamChunk.ToolCallDeclared(toolCall.name),
                AIStreamChunk.Final(AIResponse("checking", toolCalls = listOf(toolCall)))
            ),
            flowOf(
                AIStreamChunk.ReasoningDelta("done thinking"),
                AIStreamChunk.TextDelta("done"),
                AIStreamChunk.Final(AIResponse("done"))
            )
        )

        val events = collectEvents()
        val responses = events.filterIsInstance<AgentEvent.AssistantText>()
        val textDeltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
        val reasoningDeltas = events.filterIsInstance<AgentEvent.ReasoningDelta>()

        assertEquals(2, responses.size)
        assertEquals(responses.map { it.messageId }, textDeltas.map { it.messageId })
        assertEquals(responses.map { it.messageId }, reasoningDeltas.map { it.messageId })
        assertTrue(responses.all { it.messageId.isNotBlank() })
        assertNotEquals(responses[0].messageId, responses[1].messageId)
        assertEquals(listOf(toolCall), responses[0].toolCalls)
        val replayed = requests[1].filterIsInstance<AgentMessage.AssistantMessage>().single()
        assertEquals(responses[0].messageId, replayed.id)
        assertEquals("thinking", replayed.reasoning)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    @Test
    fun retryAndKeySwitchKeepResponseIdWhileResettingAccumulatedContent() = runTest {
        every { provider.completeStream(any(), any(), any(), any()) } returns flowOf(
            AIStreamChunk.ReasoningDelta("old thinking"),
            AIStreamChunk.TextDelta("old text"),
            AIStreamChunk.Retrying(1, 3, RetryErrorInfo(RetryErrorKind.UNKNOWN)),
            AIStreamChunk.ReasoningDelta("retry thinking"),
            AIStreamChunk.TextDelta("retry text"),
            AIStreamChunk.KeySwitched(2, 2),
            AIStreamChunk.ReasoningDelta("final thinking"),
            AIStreamChunk.TextDelta("final text"),
            AIStreamChunk.Final(AIResponse("final text"))
        )

        val events = collectEvents()
        val response = events.filterIsInstance<AgentEvent.AssistantText>().single()
        val textDeltas = events.filterIsInstance<AgentEvent.AssistantDelta>()
        val reasoningDeltas = events.filterIsInstance<AgentEvent.ReasoningDelta>()

        assertEquals(listOf("old text", "retry text", "final text"), textDeltas.map { it.accumulated })
        assertEquals(listOf("old thinking", "retry thinking", "final thinking"), reasoningDeltas.map { it.accumulated })
        assertTrue(textDeltas.all { it.messageId == response.messageId })
        assertTrue(reasoningDeltas.all { it.messageId == response.messageId })
        assertEquals("final text", response.content)
        assertEquals("final thinking", response.reasoning)
        assertEquals(1, events.filterIsInstance<AgentEvent.Retrying>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.KeySwitched>().size)
    }

    @Test
    fun interruptedResponsePersistsPartialTextAndReasoningWithStreamId() = runTest {
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.ReasoningDelta("partial thinking"))
            emit(AIStreamChunk.TextDelta("partial text"))
            throw IllegalStateException("connection lost")
        }

        val events = collectEvents()
        val response = events.filterIsInstance<AgentEvent.AssistantText>().single()

        assertEquals("partial text", response.content)
        assertEquals("partial thinking", response.reasoning)
        assertEquals(response.messageId, events.filterIsInstance<AgentEvent.AssistantDelta>().single().messageId)
        assertEquals(response.messageId, events.filterIsInstance<AgentEvent.ReasoningDelta>().single().messageId)
        assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
    }

    @Test
    fun accumulatorDoesNotMaterializeTextOrReasoningWithinTheThrottleWindow() = runTest {
        var now = 1_000L
        every { SystemClock.elapsedRealtime() } answers { now }
        var materializations = 0
        val events = mutableListOf<AgentEvent>()
        val stream = StatefulAgentWorkflow.StreamAccumulator(
            callStartElapsed = now,
            responseMessageId = "response",
            materialize = { materializations++; it.toString() }
        ) { events.add(it) }

        stream.onChunk(AIStreamChunk.TextDelta("a"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        assertEquals(2, materializations)
        repeat(59) {
            now++
            stream.onChunk(AIStreamChunk.TextDelta("b"))
            stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
        }
        assertEquals(2, materializations)
        assertEquals(2, events.size)

        now = 1_060
        stream.onChunk(AIStreamChunk.TextDelta("c"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("t"))
        assertEquals(4, materializations)
        assertEquals("a" + "b".repeat(59) + "c", events.filterIsInstance<AgentEvent.AssistantDelta>().last().accumulated)
        assertEquals("r" + "s".repeat(59) + "t", events.filterIsInstance<AgentEvent.ReasoningDelta>().last().accumulated)
        stream.flush()
        assertEquals(4, materializations)
    }

    @Test
    fun finalFlushMaterializesOnlyDirtyBuffersInTextThenReasoningOrder() = runTest {
        var now = 1_000L
        every { SystemClock.elapsedRealtime() } answers { now }
        var materializations = 0
        val events = mutableListOf<AgentEvent>()
        val stream = StatefulAgentWorkflow.StreamAccumulator(
            now, "response", materialize = { materializations++; it.toString() }
        ) { events.add(it) }
        stream.onChunk(AIStreamChunk.TextDelta("a"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("r"))
        now++
        stream.onChunk(AIStreamChunk.ReasoningDelta("s"))
        stream.onChunk(AIStreamChunk.TextDelta("b"))
        stream.onChunk(AIStreamChunk.Final(AIResponse("ab")))
        assertEquals(2, materializations)
        stream.flush()
        assertEquals(4, materializations)
        assertEquals(AgentEvent.AssistantDelta("ab", "response"), events[2])
        assertEquals(AgentEvent.ReasoningDelta("rs", "response"), events[3])
        stream.flush()
        assertEquals(4, materializations)
        assertEquals("ab", stream.finalResponse?.content)
    }

    @Test
    fun retryAndKeySwitchDiscardDirtyBuffersWithoutMaterializingThem() = runTest {
        var now = 1_000L
        every { SystemClock.elapsedRealtime() } answers { now }
        var materializations = 0
        val events = mutableListOf<AgentEvent>()
        val stream = StatefulAgentWorkflow.StreamAccumulator(
            now, "response", materialize = { materializations++; it.toString() }
        ) { events.add(it) }
        stream.onChunk(AIStreamChunk.TextDelta("old"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("old reasoning"))
        now++
        stream.onChunk(AIStreamChunk.TextDelta(" discarded"))
        stream.onChunk(AIStreamChunk.ReasoningDelta(" discarded"))
        stream.onChunk(AIStreamChunk.Retrying(1, 3, RetryErrorInfo(RetryErrorKind.UNKNOWN)))
        stream.flush()
        assertEquals(2, materializations)
        assertTrue(stream.text.isEmpty())
        assertTrue(stream.reasoning.isEmpty())
        stream.onChunk(AIStreamChunk.TextDelta("retry"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("retry reasoning"))
        now++
        stream.onChunk(AIStreamChunk.TextDelta(" discarded"))
        stream.onChunk(AIStreamChunk.ReasoningDelta(" discarded"))
        stream.onChunk(AIStreamChunk.KeySwitched(2, 2))
        stream.flush()
        assertEquals(4, materializations)
        stream.onChunk(AIStreamChunk.TextDelta("final"))
        stream.onChunk(AIStreamChunk.ReasoningDelta("final reasoning"))
        stream.flush()
        assertEquals(6, materializations)
        assertEquals(listOf("old", "retry", "final"), events.filterIsInstance<AgentEvent.AssistantDelta>().map { it.accumulated })
        assertEquals(listOf("old reasoning", "retry reasoning", "final reasoning"), events.filterIsInstance<AgentEvent.ReasoningDelta>().map { it.accumulated })
        assertEquals(1, stream.retryAttempts)
    }

    @Test
    fun interruptedResponseUsesDirtyWindowTextAndReasoningForPartialPersistence() = runTest {
        var now = 1_000L
        every { SystemClock.elapsedRealtime() } answers { now }
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.ReasoningDelta("partial thinking"))
            emit(AIStreamChunk.TextDelta("partial text"))
            now++
            emit(AIStreamChunk.ReasoningDelta(" tail"))
            emit(AIStreamChunk.TextDelta(" tail"))
            throw IllegalStateException("connection lost")
        }
        val events = collectEvents()
        val partial = events.filterIsInstance<AgentEvent.AssistantText>().single()
        assertEquals("partial text tail", partial.content)
        assertEquals("partial thinking tail", partial.reasoning)
        assertEquals("partial text", events.filterIsInstance<AgentEvent.AssistantDelta>().single().accumulated)
        assertEquals("partial thinking", events.filterIsInstance<AgentEvent.ReasoningDelta>().single().accumulated)
    }

    @Test
    fun interruptedReasoningOnlyResponsePersistsWithStreamId() = runTest {
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.ReasoningDelta("partial thinking"))
            throw IllegalStateException("connection lost")
        }

        val events = collectEvents()
        val response = events.filterIsInstance<AgentEvent.AssistantText>().single()

        assertEquals("", response.content)
        assertEquals("partial thinking", response.reasoning)
        assertEquals(response.messageId, events.filterIsInstance<AgentEvent.ReasoningDelta>().single().messageId)
        assertTrue(events.none { it is AgentEvent.AssistantDelta })
        assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
    }
}
