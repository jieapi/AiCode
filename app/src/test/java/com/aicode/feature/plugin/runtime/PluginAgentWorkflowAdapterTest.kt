package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.workflow.AgentWorkflowRegistry
import com.aicode.feature.agent.domain.workflow.SessionRef
import com.aicode.feature.agent.domain.workflow.TurnPersistence
import com.aicode.feature.agent.domain.workflow.WorkflowTurnContext
import com.aicode.feature.agent.domain.workflow.WorkflowNotFoundException
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.domain.PluginWorkflowCapabilitiesRef
import com.aicode.feature.plugin.domain.PluginWorkflowDefinition
import com.aicode.feature.plugin.domain.PluginWorkflowHandlersRef
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginAgentWorkflowAdapterTest {

    private val runtime = PluginRuntime(
        pluginId = "demo.codex",
        type = "javascript",
        installDir = File("/tmp/demo-codex"),
        entryFile = File("/tmp/demo-codex/index.js"),
        command = listOf("node", "index.js"),
        resident = true
    )

    private fun definition(
        compact: String? = "wf.compact",
        generateTitle: String? = "wf.title"
    ) = PluginWorkflowDefinition(
        pluginId = "demo.codex",
        localId = "codex",
        title = LocalizedText.single("Codex"),
        description = LocalizedText.single("Codex CLI workflow"),
        capabilities = PluginWorkflowCapabilitiesRef(
            streaming = true,
            tools = true,
            compaction = compact != null,
            titleGeneration = generateTitle != null
        ),
        handlers = PluginWorkflowHandlersRef(
            startTurn = "wf.startTurn",
            cancelTurn = "wf.cancelTurn",
            compact = compact,
            generateTitle = generateTitle
        ),
        runtime = runtime,
        pluginVersion = "1.0.0"
    )

    private fun pluginContext(workflowState: String? = """{"threadId":"thr-1"}""") = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/ws/demo",
        language = null,
        history = listOf(AgentMessage.UserMessage(id = "u0", content = "prev")),
        sessionId = "sess-1",
        mode = AgentMode.BUILD,
        turn = WorkflowTurnContext(
            runId = "run-1",
            sessionRef = SessionRef.External("test", "sess-1"),
            providerId = "prov-1",
            model = "gpt-5",
            workflowState = workflowState,
            permissionMode = AgentMode.BUILD,
            persistence = TurnPersistence.Noop
        )
    )

    private fun sampleSession(workflowState: String? = """{"threadId":"thr-1"}""") = ChatSessionEntity(
        id = "sess-1",
        title = "Test",
        createdAt = 1L,
        updatedAt = 2L,
        workspacePath = "/ws/demo",
        providerId = "prov-1",
        model = "gpt-5",
        workflowId = "demo.codex:codex",
        workflowState = workflowState
    )

    @Test
    fun executeEvents_mapsBinaryStreamEventsAndPersistsWorkflowState() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val eventBus = PluginWorkflowEventBus()
        val sessionUseCase = mockk<SessionUseCase>()

        coEvery { sessionUseCase.getSessionById("sess-1") } returns sampleSession()
        coEvery { sessionUseCase.updateWorkflowState("sess-1", any()) } just Runs

        var capturedArgs: JsonObject? = null
        every {
            runtimeManager.openWorkflowStream("demo.codex", "1.0.0", runtime, "wf.startTurn", any())
        } answers {
            capturedArgs = arg(4)
            flowOf(
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "reasoning.delta")
                    put("text", "step1 ")
                    put("messageId", "m-1")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "assistant.delta")
                    put("text", "Hello ")
                    put("messageId", "m-1")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "assistant.delta")
                    put("text", "World")
                    put("messageId", "m-1")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "tool.preparing")
                    put("toolName", "readFile")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "tool.started")
                    put("callId", "c-1")
                    put("toolName", "readFile")
                    put("arguments", buildJsonObject { put("path", "README.md") })
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "tool.progress")
                    put("callId", "c-1")
                    put("chunk", "line1\n")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "tool.progress")
                    put("callId", "c-1")
                    put("chunk", "line2")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "tool.finished")
                    put("callId", "c-1")
                    put("toolName", "readFile")
                    put("status", "success")
                    put("result", "ok")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "retrying")
                    put("attempt", 2)
                    put("maxRetries", 3)
                    put("statusCode", 429)
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "keySwitched")
                    put("index", 2)
                    put("total", 4)
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "contextUsage")
                    put("inputTokens", 1200)
                    put("contextLimit", 8000)
                    put("estimated", true)
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "compaction.started")
                    put("estimatedTokens", 7000)
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "compaction.finished")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "compaction.failed")
                    put("message", "no model")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "modeChanged")
                    put("mode", "plan")
                    put("reason", "planning")
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "assistant.message")
                    put("messageId", "m-1")
                    put("content", "Hello World")
                    put("reasoning", "step1 ")
                    put("workflowState", buildJsonObject { put("threadId", "thr-2") })
                    put("toolCalls", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "c-1")
                            put("name", "readFile")
                            put("arguments", buildJsonObject { put("path", "README.md") })
                        })
                    })
                }),
                BinaryStreamChunk(meta = buildJsonObject {
                    put("type", "completed")
                    put("workflowState", buildJsonObject { put("threadId", "thr-3") })
                })
            )
        }

        val adapter = PluginAgentWorkflowAdapter(definition(), runtimeManager, eventBus)
        val events = adapter.executeEvents(
            userRequest = "check readme",
            context = pluginContext(),
            tools = emptyList()
        ).toList()

        assertEquals("sess-1", (capturedArgs?.get("sessionId") as? JsonPrimitive)?.contentOrNull)
        assertEquals("check readme", (capturedArgs?.get("userRequest") as? JsonPrimitive)?.contentOrNull)
        assertEquals("build", (capturedArgs?.get("mode") as? JsonPrimitive)?.contentOrNull)
        assertEquals("thr-1", ((capturedArgs?.get("workflowState") as? JsonObject)?.get("threadId") as? JsonPrimitive)?.contentOrNull)
        assertEquals(1, (capturedArgs?.get("recentMessages") as? JsonArray)?.size)

        assertEquals(AgentEvent.ReasoningDelta("step1 ", "m-1"), events[0])
        assertEquals(AgentEvent.AssistantDelta("Hello ", "m-1"), events[1])
        assertEquals(AgentEvent.AssistantDelta("Hello World", "m-1"), events[2])
        assertEquals(AgentEvent.ToolCallPreparing("readFile"), events[3])
        assertTrue(events[4] is AgentEvent.ToolCallStarted)
        assertEquals(AgentEvent.ToolCallProgress("c-1", "readFile", "line1\n"), events[5])
        assertEquals(AgentEvent.ToolCallProgress("c-1", "readFile", "line1\nline2"), events[6])
        assertEquals(
            AgentEvent.ToolCallFinished("c-1", "readFile", "ok", isError = false),
            events[7]
        )
        val retrying = events[8] as AgentEvent.Retrying
        assertEquals(2, retrying.attempt)
        assertEquals(3, retrying.maxRetries)
        assertEquals(RetryErrorKind.RATE_LIMIT, retrying.error.kind)
        assertEquals(429, retrying.error.statusCode)
        assertEquals(AgentEvent.KeySwitched(2, 4), events[9])
        assertEquals(AgentEvent.ContextUsage(1200, 8000, true), events[10])
        assertEquals(AgentEvent.CompactionStarted(7000), events[11])
        assertEquals(AgentEvent.CompactionFinished, events[12])
        assertEquals(AgentEvent.CompactionFailed("no model"), events[13])
        assertEquals(AgentEvent.ModeChanged(AgentMode.PLAN, "planning"), events[14])
        assertTrue(events[15] is AgentEvent.WorkflowStateChanged)
        val assistantMsg = events[16] as AgentEvent.AssistantText
        assertEquals("Hello World", assistantMsg.content)
        assertEquals("m-1", assistantMsg.messageId)
        assertEquals(1, assistantMsg.toolCalls.size)
        assertTrue(events[17] is AgentEvent.WorkflowStateChanged)
        assertEquals(AgentEvent.Completed, events[18])

        assertTrue(events.any { it is AgentEvent.WorkflowStateChanged })
    }

    @Test
    fun executeEvents_fallsBackToEventBusWhenBinaryChannelUnavailable() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val eventBus = PluginWorkflowEventBus()
        val sessionUseCase = mockk<SessionUseCase>()

        coEvery { sessionUseCase.getSessionById("sess-1") } returns sampleSession(null)
        coEvery { sessionUseCase.updateWorkflowState("sess-1", any()) } just Runs

        every {
            runtimeManager.openWorkflowStream("demo.codex", "1.0.0", runtime, "wf.startTurn", any())
        } returns flow {
            throw PluginProcessException("PLUGIN_IPC_UNAVAILABLE", "no ipc")
        }

        coEvery {
            runtimeManager.invokeWorkflow("demo.codex", "1.0.0", runtime, "wf.startTurn", any(), any())
        } answers {
            val args = arg<JsonObject>(4)
            val turnId = (args["turnId"] as JsonPrimitive).content
            // 模拟通过 plugin.event（name = "workflow.event"）推送事件，以及直接 workflow.event 通知
            eventBus.publishNotification(
                pluginId = "demo.codex",
                method = "plugin.event",
                params = buildJsonObject {
                    put("name", "workflow.event")
                    put("payload", buildJsonObject {
                        put("turnId", turnId)
                        put("type", "assistant.delta")
                        put("text", "via-ndjson")
                        put("messageId", "msg-nd")
                    })
                }
            )
            eventBus.publishNotification(
                pluginId = "demo.codex",
                method = "workflow.event",
                params = buildJsonObject {
                    put("turnId", turnId)
                    put("type", "completed")
                    put("workflowState", "cursor-99")
                }
            )
            buildJsonObject { put("accepted", true) }
        }

        val adapter = PluginAgentWorkflowAdapter(definition(), runtimeManager, eventBus)
        val events = adapter.executeEvents(
            userRequest = "hi",
            context = AgentContext(null, null, "/ws/demo", null, sessionId = "sess-1"),
            tools = emptyList()
        ).toList()

        assertEquals(
            listOf(
                AgentEvent.AssistantDelta("via-ndjson", "msg-nd"),
                AgentEvent.WorkflowStateChanged("cursor-99"),
                AgentEvent.Completed
            ),
            events
        )
        assertEquals(AgentEvent.Completed, events.last())
    }

    @Test
    fun executeEvents_invokesCancelTurnOnCancellation() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val eventBus = PluginWorkflowEventBus()
        val sessionUseCase = mockk<SessionUseCase>()

        coEvery { sessionUseCase.getSessionById("sess-1") } returns sampleSession(null)
        val started = CompletableDeferred<Unit>()
        var cancelledTurnId: String? = null

        every {
            runtimeManager.openWorkflowStream("demo.codex", "1.0.0", runtime, "wf.startTurn", any())
        } returns flow {
            emit(BinaryStreamChunk(meta = buildJsonObject {
                put("type", "assistant.delta")
                put("text", "partial")
            }))
            started.complete(Unit)
            delay(60_000L)
        }

        coEvery {
            runtimeManager.invokeWorkflow("demo.codex", "1.0.0", runtime, "wf.cancelTurn", any(), any())
        } answers {
            val args = arg<JsonObject>(4)
            cancelledTurnId = (args["turnId"] as JsonPrimitive).content
            buildJsonObject { put("ok", true) }
        }

        val adapter = PluginAgentWorkflowAdapter(definition(), runtimeManager, eventBus)
        val received = mutableListOf<AgentEvent>()
        val job = launch {
            try {
                adapter.executeEvents(
                    userRequest = "long run",
                    context = pluginContext(null),
                    tools = emptyList()
                ).collect { received += it }
            } catch (_: CancellationException) {
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertEquals(1, received.size)
        assertTrue(received[0] is AgentEvent.AssistantDelta)
        assertTrue(!cancelledTurnId.isNullOrBlank())
        coVerify(exactly = 1) {
            runtimeManager.invokeWorkflow("demo.codex", "1.0.0", runtime, "wf.cancelTurn", any(), any())
        }
    }

    @Test
    fun compactTitleCommitAndRegistrar_workAsExpected() = runTest {
        val runtimeManager = mockk<PluginRuntimeManager>()
        val eventBus = PluginWorkflowEventBus()
        val sessionUseCase = mockk<SessionUseCase>()
        coEvery { sessionUseCase.getSessionById("sess-1") } returns sampleSession()
        coEvery { sessionUseCase.updateWorkflowState("sess-1", any()) } just Runs
        coEvery {
            runtimeManager.invokeWorkflow("demo.codex", "1.0.0", runtime, "wf.compact", any(), any())
        } returns buildJsonObject {
            put("compacted", true)
            put("workflowState", "compacted-state")
        }
        coEvery {
            runtimeManager.invokeWorkflow("demo.codex", "1.0.0", runtime, "wf.title", any(), any())
        } returns buildJsonObject { put("title", "  Generated Title  ") }

        val fullAdapter = PluginAgentWorkflowAdapter(definition(), runtimeManager, eventBus)
        val compactEvents = mutableListOf<AgentEvent>()
        assertTrue(fullAdapter.compactSession(pluginContext()) { compactEvents += it })
        assertEquals(
            listOf(AgentEvent.CompactionStarted(0), AgentEvent.WorkflowStateChanged("compacted-state"), AgentEvent.CompactionFinished),
            compactEvents
        )
        assertEquals(AgentEvent.CompactionFinished, compactEvents.last())
        assertEquals("Generated Title", fullAdapter.generateTitle(pluginContext(), "hi"))
        assertNull(fullAdapter.generateCommitMessage("diff"))

        val minimalAdapter = PluginAgentWorkflowAdapter(
            definition(compact = null, generateTitle = null),
            runtimeManager,
            eventBus
        )
        assertFalse(minimalAdapter.compactSession(pluginContext()))
        assertNull(minimalAdapter.generateTitle(pluginContext(), "hi"))

        var active = true
        val plugin = mockk<InstalledPlugin> {
            every { id } returns "demo.codex"
            every { isActive } answers { active }
            every { content } returns PluginContent(workflows = listOf(definition()))
        }
        val pluginManager = mockk<PluginManager> {
            every { ensureLoaded() } just Runs
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
        val defaultWorkflow = mockk<AgentWorkflow>(relaxed = true)
        val registry = AgentWorkflowRegistry(defaultWorkflow)
        val registrar = PluginWorkflowRegistrar(
            pluginManager = pluginManager,
            runtimeManager = runtimeManager,
            workflowEventBus = eventBus,
            workflowRegistry = registry
        )
        registrar.syncAll()
        assertEquals(2, registry.workflows.value.size)
        assertTrue(registry.resolve("demo.codex:codex") is PluginAgentWorkflowAdapter)

        active = false
        registrar.syncAll()
        assertEquals(1, registry.workflows.value.size)
        assertThrows(WorkflowNotFoundException::class.java) { registry.resolve("demo.codex:codex") }
    }
}
