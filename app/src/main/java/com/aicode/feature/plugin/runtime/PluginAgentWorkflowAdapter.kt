package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import com.aicode.feature.agent.domain.workflow.SessionRef
import com.aicode.feature.agent.domain.workflow.WorkflowTurnContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.plugin.domain.PluginWorkflowDefinition
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 将插件声明的 [PluginWorkflowDefinition] 适配为宿主统一的 [AgentWorkflow]。
 *
 * 优先走二进制 IPC 流式通道（`openWorkflowStream`）；当通道未就绪抛出
 * `PLUGIN_IPC_UNAVAILABLE` 时，自动回退到 JSON-RPC `invokeWorkflow(startTurn)` +
 * [PluginWorkflowEventBus] 订阅，直至收到 `completed` 或 `failed`。
 */
class PluginAgentWorkflowAdapter(
    val definition: PluginWorkflowDefinition,
    private val runtimeManager: PluginRuntimeManager,
    private val workflowEventBus: PluginWorkflowEventBus
) : AgentWorkflow {

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        val turn = context.turn
        val sessionId = when (val ref = turn?.sessionRef) {
            is SessionRef.Room -> ref.opaqueId
            is SessionRef.External -> ref.opaqueId
            null -> context.sessionId
        }
        val turnId = turn?.runId ?: UUID.randomUUID().toString()
        val args = buildStartTurnArgs(
            turnId = turnId,
            userRequest = userRequest,
            context = context,
            workspacePath = context.projectRoot,
            providerId = turn?.providerId,
            model = turn?.model,
            workflowState = turn?.workflowState
        )

        val state = TurnStreamState(sessionId = sessionId)

        try {
            var terminalEmitted = false
            try {
                runtimeManager.openWorkflowStream(
                    pluginId = definition.pluginId,
                    version = definition.pluginVersion,
                    runtime = definition.runtime,
                    handler = definition.handlers.startTurn,
                    args = args
                ).collect { chunk ->
                    val eventObj = extractChunkEvent(chunk) ?: return@collect
                    if (emitMappedEvent(eventObj, state, ::send, turn)) {
                        terminalEmitted = true
                    }
                }
                if (!terminalEmitted) {
                    send(AgentEvent.Completed)
                }
            } catch (e: PluginProcessException) {
                if (e.code in STREAM_FALLBACK_CODES) {
                    runViaEventBusFallback(
                        turnId = turnId,
                        args = args,
                        state = state,
                        emit = ::send,
                        turnContext = turn
                    )
                } else {
                    FileLogger.w(TAG, "[${definition.key}] 工作流流式调用失败: ${e.message}", e)
                    send(AgentEvent.Failed(e.message.ifBlank { "Plugin workflow failed" }, e.code))
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    runtimeManager.invokeWorkflow(
                        pluginId = definition.pluginId,
                        version = definition.pluginVersion,
                        runtime = definition.runtime,
                        handler = definition.handlers.cancelTurn,
                        args = buildJsonObject {
                            put("sessionId", sessionId.orEmpty())
                            put("turnId", turnId)
                            put("reason", "cancelled")
                        },
                        timeoutMs = CONTROL_TIMEOUT_MS
                    )
                }.onFailure { cancelErr ->
                    FileLogger.w(TAG, "[${definition.key}] cancelTurn 失败: turnId=$turnId", cancelErr)
                }
            }
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${definition.key}] 工作流执行异常: ${e.message}", e)
            send(AgentEvent.Failed(e.message ?: "Plugin workflow failed"))
        }
    }

    override suspend fun compactSession(
        sessionId: String,
        onEvent: suspend (AgentEvent) -> Unit
    ): Boolean = false

    override suspend fun compactSession(
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): Boolean {
        val handler = definition.handlers.compact ?: return false
        val turn = context.turn ?: return false
        onEvent(AgentEvent.CompactionStarted(estimatedTokens = context.lastInputTokens))
        return try {
            val result = runtimeManager.invokeWorkflow(
                pluginId = definition.pluginId,
                version = definition.pluginVersion,
                runtime = definition.runtime,
                handler = handler,
                args = buildJsonObject {
                    put("sessionId", turn.sessionRef.opaqueId)
                    put("workspacePath", context.projectRoot)
                    turn.providerId?.takeIf { it.isNotBlank() }?.let { put("providerId", it) }
                    turn.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                    turn.workflowState?.let { put("workflowState", encodeWorkflowStateElement(it)) }
                },
                timeoutMs = CONTROL_TIMEOUT_MS
            )
            val obj = result as? JsonObject
            if (obj != null && obj.containsKey("workflowState")) {
                persistWorkflowState(turn, obj["workflowState"], onEvent)
            }
            val error = obj?.get("error")?.stringVal()?.takeIf { it.isNotBlank() }
            if (error != null) {
                onEvent(AgentEvent.CompactionFailed(error))
                false
            } else {
                val compacted = obj?.get("compacted")?.booleanVal()
                    ?: obj?.get("changed")?.booleanVal()
                    ?: obj?.get("ok")?.booleanVal()
                    ?: true
                onEvent(AgentEvent.CompactionFinished)
                compacted
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${definition.key}] compact 失败: sessionId=${turn.sessionRef.opaqueId}", e)
            onEvent(AgentEvent.CompactionFailed(e.message ?: "Compaction failed"))
            false
        }
    }

    override suspend fun generateTitle(sessionId: String, request: String): String? = null

    override suspend fun generateTitle(context: AgentContext, request: String): String? {
        val handler = definition.handlers.generateTitle ?: return null
        val turn = context.turn ?: return null
        return try {
            val result = runtimeManager.invokeWorkflow(
                pluginId = definition.pluginId,
                version = definition.pluginVersion,
                runtime = definition.runtime,
                handler = handler,
                args = buildJsonObject {
                    put("sessionId", turn.sessionRef.opaqueId)
                    put("userRequest", request)
                    put("workspacePath", context.projectRoot)
                    turn.providerId?.takeIf { it.isNotBlank() }?.let { put("providerId", it) }
                    turn.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                },
                timeoutMs = CONTROL_TIMEOUT_MS
            )
            val rawTitle = when (result) {
                is JsonPrimitive -> result.takeIf { it.isString }?.contentOrNull
                is JsonObject -> result["title"]?.stringVal()
                else -> null
            }
            rawTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_MAX_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${definition.key}] generateTitle 失败: sessionId=${turn.sessionRef.opaqueId}", e)
            null
        }
    }

    override suspend fun generateCommitMessage(diff: String): String? = null

    private suspend fun runViaEventBusFallback(
        turnId: String,
        args: JsonObject,
        state: TurnStreamState,
        emit: suspend (AgentEvent) -> Unit,
        turnContext: WorkflowTurnContext?
    ) {
        val eventChannel = Channel<JsonObject>(Channel.UNLIMITED)
        val unsubscribe = workflowEventBus.subscribe(definition.pluginId, turnId) { event ->
            eventChannel.trySend(event)
        }
        try {
            coroutineScope {
                var terminalEmitted = false
                var rpcFailure: Throwable? = null
                val rpcJob = launch {
                    try {
                        val result = runtimeManager.invokeWorkflow(
                            pluginId = definition.pluginId,
                            version = definition.pluginVersion,
                            runtime = definition.runtime,
                            handler = definition.handlers.startTurn,
                            args = args
                        )
                        val obj = result as? JsonObject
                        if (obj != null && obj.containsKey("workflowState") && state.sessionId != null) {
                            persistWorkflowState(turnContext, obj["workflowState"], emit)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        rpcFailure = t
                        eventChannel.close()
                    }
                }

                var timedOut = false
                try {
                    withTimeout(CONTROL_TIMEOUT_MS) {
                        for (eventObj in eventChannel) {
                            if (emitMappedEvent(eventObj, state, emit, turnContext)) {
                                terminalEmitted = true
                                eventChannel.close()
                                break
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                }

                val failure = rpcFailure
                if (!terminalEmitted && failure != null) {
                    val code = (failure as? PluginProcessException)?.code
                    emit(AgentEvent.Failed(failure.message ?: "Plugin workflow failed", code))
                } else if (!terminalEmitted && timedOut) {
                    emit(AgentEvent.Failed("Plugin workflow timed out waiting for terminal event", "PLUGIN_WORKFLOW_TIMEOUT"))
                } else if (!terminalEmitted) {
                    emit(AgentEvent.Failed("Plugin workflow ended without a terminal event", "PLUGIN_WORKFLOW_NO_TERMINAL"))
                }

                if (rpcJob.isActive) {
                    withTimeoutOrNull(CONTROL_TIMEOUT_MS) { rpcJob.join() } ?: rpcJob.cancel()
                }
            }
        } finally {
            unsubscribe()
            eventChannel.close()
        }
    }

    private suspend fun emitMappedEvent(
        eventObj: JsonObject,
        state: TurnStreamState,
        emit: suspend (AgentEvent) -> Unit,
        turnContext: WorkflowTurnContext? = null
    ): Boolean {
        val type = eventObj["type"]?.stringVal() ?: return false
        when (type) {
            "assistant.delta" -> {
                val msgId = state.resolveMessageId(eventObj["messageId"]?.stringVal())
                val accumulated = eventObj["accumulated"]?.stringVal()
                val text = if (accumulated != null) {
                    state.assistantBuffer.setLength(0)
                    state.assistantBuffer.append(accumulated)
                    accumulated
                } else {
                    val delta = eventObj["text"]?.stringVal() ?: eventObj["delta"]?.stringVal().orEmpty()
                    state.assistantBuffer.append(delta)
                    state.assistantBuffer.toString()
                }
                emit(AgentEvent.AssistantDelta(accumulated = text, messageId = msgId))
            }
            "reasoning.delta" -> {
                val msgId = state.resolveMessageId(eventObj["messageId"]?.stringVal())
                val accumulated = eventObj["accumulated"]?.stringVal()
                val text = if (accumulated != null) {
                    state.reasoningBuffer.setLength(0)
                    state.reasoningBuffer.append(accumulated)
                    accumulated
                } else {
                    val delta = eventObj["text"]?.stringVal() ?: eventObj["delta"]?.stringVal().orEmpty()
                    state.reasoningBuffer.append(delta)
                    state.reasoningBuffer.toString()
                }
                emit(AgentEvent.ReasoningDelta(accumulated = text, messageId = msgId))
            }
            "assistant.message" -> {
                val msgId = state.resolveMessageId(eventObj["messageId"]?.stringVal())
                val content = eventObj["content"]?.stringVal() ?: state.assistantBuffer.toString()
                val reasoning = eventObj["reasoning"]?.stringVal() ?: state.reasoningBuffer.toString()
                val toolCalls = parseToolCalls(eventObj["toolCalls"] as? JsonArray)
                val usageObj = eventObj["usage"] as? JsonObject
                val inputTokens = eventObj["inputTokens"]?.intVal() ?: usageObj?.get("input")?.intVal() ?: 0
                val outputTokens = eventObj["outputTokens"]?.intVal() ?: usageObj?.get("output")?.intVal() ?: 0
                val cachedInputTokens = eventObj["cachedInputTokens"]?.intVal()
                    ?: usageObj?.get("cachedInput")?.intVal() ?: 0
                val signature = eventObj["signature"]?.stringVal().orEmpty()
                val thinkingBlocksJson = eventObj["thinkingBlocksJson"]?.stringVal().orEmpty()
                if (eventObj.containsKey("workflowState") && state.sessionId != null) {
                    persistWorkflowState(turnContext, eventObj["workflowState"], emit)
                }
                emit(
                    AgentEvent.AssistantText(
                        content = content,
                        toolCalls = toolCalls,
                        reasoning = reasoning,
                        signature = signature,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cachedInputTokens = cachedInputTokens,
                        thinkingBlocksJson = thinkingBlocksJson,
                        messageId = msgId
                    )
                )
                state.resetTurnBuffers()
            }
            "tool.preparing" -> {
                val toolName = eventObj["toolName"]?.stringVal() ?: return false
                emit(AgentEvent.ToolCallPreparing(toolName = toolName))
            }
            "tool.started" -> {
                val callId = eventObj["callId"]?.stringVal() ?: eventObj["id"]?.stringVal() ?: return false
                val toolName = eventObj["toolName"]?.stringVal() ?: return false
                val argsPreview = eventObj["argsPreview"]?.stringVal()
                    ?: elementToArgsPreview(eventObj["arguments"])
                state.toolNames[callId] = toolName
                state.toolBuffers.remove(callId)
                emit(AgentEvent.ToolCallStarted(id = callId, toolName = toolName, argsPreview = argsPreview))
            }
            "tool.progress" -> {
                val callId = eventObj["callId"]?.stringVal() ?: eventObj["id"]?.stringVal() ?: return false
                val toolName = eventObj["toolName"]?.stringVal() ?: state.toolNames[callId].orEmpty()
                val accumulated = eventObj["accumulated"]?.stringVal()
                val text = if (accumulated != null) {
                    state.toolBuffers[callId] = StringBuilder(accumulated)
                    accumulated
                } else {
                    val chunk = eventObj["chunk"]?.stringVal() ?: eventObj["text"]?.stringVal().orEmpty()
                    val buf = state.toolBuffers.getOrPut(callId) { StringBuilder() }
                    buf.append(chunk)
                    buf.toString()
                }
                emit(AgentEvent.ToolCallProgress(id = callId, toolName = toolName, accumulated = text))
            }
            "tool.finished" -> {
                val callId = eventObj["callId"]?.stringVal() ?: eventObj["id"]?.stringVal() ?: return false
                val toolName = eventObj["toolName"]?.stringVal() ?: state.toolNames[callId].orEmpty()
                val status = eventObj["status"]?.stringVal()?.lowercase(Locale.ROOT)
                val isError = eventObj["isError"]?.booleanVal() ?: (status == "error")
                val errorText = eventObj["error"]?.stringVal()?.takeIf { it.isNotBlank() }
                val resultText = if (isError && errorText != null) {
                    errorText
                } else {
                    elementToText(eventObj["result"]) ?: errorText.orEmpty()
                }
                val argsPreview = eventObj["argsPreview"]?.stringVal()
                state.toolBuffers.remove(callId)
                emit(
                    AgentEvent.ToolCallFinished(
                        id = callId,
                        toolName = toolName,
                        result = resultText,
                        isError = isError,
                        argsPreview = argsPreview
                    )
                )
            }
            "retrying" -> {
                val attempt = eventObj["attempt"]?.intVal() ?: 1
                val maxRetries = eventObj["maxRetries"]?.intVal() ?: 1
                val statusCode = eventObj["statusCode"]?.intVal()
                val kind = parseRetryErrorKind(
                    kind = eventObj["kind"]?.stringVal(),
                    code = eventObj["code"]?.stringVal(),
                    statusCode = statusCode
                )
                emit(
                    AgentEvent.Retrying(
                        attempt = attempt,
                        maxRetries = maxRetries,
                        error = RetryErrorInfo(kind = kind, statusCode = statusCode)
                    )
                )
            }
            "keySwitched" -> {
                val index = eventObj["newIndex"]?.intVal() ?: eventObj["index"]?.intVal() ?: 1
                val total = eventObj["total"]?.intVal() ?: 1
                emit(AgentEvent.KeySwitched(newIndex = index, total = total))
            }
            "contextUsage" -> {
                val inputTokens = eventObj["inputTokens"]?.intVal() ?: 0
                val inputBudget = eventObj["inputBudget"]?.intVal()
                    ?: eventObj["contextLimit"]?.intVal()
                    ?: 0
                val estimated = eventObj["estimated"]?.booleanVal() ?: false
                emit(
                    AgentEvent.ContextUsage(
                        inputTokens = inputTokens,
                        inputBudget = inputBudget,
                        estimated = estimated
                    )
                )
            }
            "compaction.started" -> {
                val estimatedTokens = eventObj["estimatedTokens"]?.intVal() ?: 0
                emit(AgentEvent.CompactionStarted(estimatedTokens = estimatedTokens))
            }
            "compaction.finished" -> {
                if (eventObj.containsKey("workflowState") && state.sessionId != null) {
                    persistWorkflowState(turnContext, eventObj["workflowState"], emit)
                }
                emit(AgentEvent.CompactionFinished)
            }
            "compaction.failed" -> {
                val reason = eventObj["message"]?.stringVal()
                    ?: eventObj["reason"]?.stringVal()
                    ?: "Compaction failed"
                emit(AgentEvent.CompactionFailed(reason = reason))
            }
            "modeChanged" -> {
                val modeStr = eventObj["mode"]?.stringVal() ?: return false
                val newMode = AgentMode.entries.firstOrNull {
                    it.name.equals(modeStr, ignoreCase = true)
                } ?: return false
                val reason = eventObj["reason"]?.stringVal().orEmpty()
                emit(AgentEvent.ModeChanged(newMode = newMode, reason = reason))
            }
            "failed" -> {
                val message = eventObj["message"]?.stringVal()
                    ?: eventObj["error"]?.stringVal()
                    ?: "Plugin workflow failed"
                val code = eventObj["reasonCode"]?.stringVal() ?: eventObj["code"]?.stringVal()
                emit(AgentEvent.Failed(error = message, reasonCode = code))
                return true
            }
            "completed" -> {
                if (eventObj.containsKey("workflowState") && state.sessionId != null) {
                    persistWorkflowState(turnContext, eventObj["workflowState"], emit)
                }
                emit(AgentEvent.Completed)
                return true
            }
        }
        return false
    }

    private suspend fun persistWorkflowState(
        turn: WorkflowTurnContext?,
        raw: JsonElement?,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val serialized = when (raw) {
            null, JsonNull -> null
            is JsonPrimitive -> if (raw.isString) raw.content else raw.toString()
            else -> raw.toString()
        }
        turn?.persistence?.persistWorkflowState(serialized)
        emit(AgentEvent.WorkflowStateChanged(serialized))
    }

    private fun buildStartTurnArgs(
        turnId: String,
        userRequest: String,
        context: AgentContext,
        workspacePath: String,
        providerId: String?,
        model: String?,
        workflowState: String?
    ): JsonObject = buildJsonObject {
        put("sessionId", when (val ref = context.turn?.sessionRef) {
            is SessionRef.Room -> ref.opaqueId
            is SessionRef.External -> ref.opaqueId
            null -> context.sessionId.orEmpty()
        })
        put("turnId", turnId)
        put("userRequest", userRequest)
        put("mode", context.mode.name.lowercase(Locale.ROOT))
        put("workspacePath", workspacePath)
        providerId?.takeIf { it.isNotBlank() }?.let { put("providerId", it) }
        model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        context.reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
        workflowState?.let { put("workflowState", encodeWorkflowStateElement(it)) }
        put("recentMessages", buildRecentMessages(context.history))
    }

    private fun buildRecentMessages(history: List<AgentMessage>): JsonArray {
        val trimmed = if (history.size > MAX_RECENT_MESSAGES) {
            history.takeLast(MAX_RECENT_MESSAGES)
        } else {
            history
        }
        return buildJsonArray {
            for (msg in trimmed) {
                when (msg) {
                    is AgentMessage.UserMessage -> add(
                        buildJsonObject {
                            put("id", msg.id)
                            put("role", "user")
                            put("content", truncateText(msg.content, MAX_HISTORY_TEXT_CHARS))
                        }
                    )
                    is AgentMessage.AssistantMessage -> add(
                        buildJsonObject {
                            put("id", msg.id)
                            put("role", "assistant")
                            put("content", truncateText(msg.content, MAX_HISTORY_TEXT_CHARS))
                            if (msg.reasoning.isNotBlank()) {
                                put("reasoning", truncateText(msg.reasoning, MAX_HISTORY_TEXT_CHARS))
                            }
                            if (msg.toolCalls.isNotEmpty()) {
                                put("toolCalls", buildJsonArray {
                                    for (tc in msg.toolCalls) {
                                        add(buildJsonObject {
                                            put("id", tc.id)
                                            put("name", tc.name)
                                            val argsJson = JsonObject(tc.arguments)
                                            val serialized = argsJson.toString()
                                            if (serialized.length > MAX_TOOL_ARGS_CHARS) {
                                                put("arguments", serialized.take(MAX_TOOL_ARGS_CHARS))
                                                put("argumentsTruncated", true)
                                            } else {
                                                put("arguments", argsJson)
                                            }
                                        })
                                    }
                                })
                            }
                        }
                    )
                    is AgentMessage.ToolResultMessage -> add(
                        buildJsonObject {
                            put("id", msg.messageId)
                            put("role", "tool")
                            put("toolName", msg.toolName)
                            put("toolCallId", msg.id)
                            put(
                                "content",
                                truncateText(msg.modelResult ?: msg.result, MAX_HISTORY_TEXT_CHARS)
                            )
                        }
                    )
                }
            }
        }
    }

    private fun parseToolCalls(array: JsonArray?): List<ToolCall> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.stringVal()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj["name"]?.stringVal()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val argsMap: Map<String, JsonElement> = when (val argsEl = obj["arguments"]) {
                is JsonObject -> argsEl
                is JsonPrimitive -> {
                    val str = argsEl.contentOrNull.orEmpty()
                    val parsed = runCatching { JSON.parseToJsonElement(str) as? JsonObject }.getOrNull()
                    parsed ?: if (str.isNotEmpty()) mapOf("raw" to JsonPrimitive(str)) else emptyMap()
                }
                else -> emptyMap()
            }
            ToolCall(id = id, name = name, arguments = argsMap)
        }
    }

    private fun extractChunkEvent(chunk: BinaryStreamChunk): JsonObject? {
        if (chunk.meta.containsKey("type")) return chunk.meta
        val nested = chunk.meta["event"] as? JsonObject
        if (nested != null) return nested
        if (chunk.payload.isNotEmpty()) {
            val text = chunk.payload.toString(Charsets.UTF_8)
            return runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        return null
    }

    private fun encodeWorkflowStateElement(raw: String): JsonElement {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { JSON.parseToJsonElement(trimmed) }.getOrNull()
            if (parsed != null) return parsed
        }
        return JsonPrimitive(raw)
    }

    private class TurnStreamState(val sessionId: String?) {
        var activeMessageId: String? = null
        val assistantBuffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        val toolNames = mutableMapOf<String, String>()
        val toolBuffers = mutableMapOf<String, StringBuilder>()

        fun resolveMessageId(explicit: String?): String {
            if (!explicit.isNullOrBlank()) {
                activeMessageId = explicit
                return explicit
            }
            return activeMessageId ?: UUID.randomUUID().toString().also { activeMessageId = it }
        }

        fun resetTurnBuffers() {
            activeMessageId = null
            assistantBuffer.setLength(0)
            reasoningBuffer.setLength(0)
        }
    }

    private companion object {
        const val TAG = "PluginWorkflowAdapter"
        const val MAX_RECENT_MESSAGES = 20
        const val MAX_HISTORY_TEXT_CHARS = 4_000
        const val MAX_TOOL_ARGS_CHARS = 4_000
        const val TITLE_MAX_CHARS = 50
        const val CONTROL_TIMEOUT_MS = 30_000L

        val STREAM_FALLBACK_CODES = setOf(
            "PLUGIN_IPC_UNAVAILABLE",
            "PLUGIN_IPC_NOT_CONNECTED",
            "PLUGIN_IPC_CLOSED"
        )

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun truncateText(text: String, maxChars: Int): String =
            if (text.length <= maxChars) text else text.take(maxChars)

        fun JsonElement.stringVal(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun JsonElement.booleanVal(): Boolean? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        fun JsonElement.intVal(): Int? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        fun elementToArgsPreview(element: JsonElement?): String = when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> element.toString()
        }

        fun elementToText(element: JsonElement?): String? = when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull ?: element.toString()
            else -> element.toString()
        }

        fun parseRetryErrorKind(kind: String?, code: String?, statusCode: Int?): RetryErrorKind {
            val normalized = (kind ?: code).orEmpty().lowercase(Locale.ROOT)
            return when {
                normalized == "rate_limit" || normalized.contains("rate_limit") || statusCode == 429 ->
                    RetryErrorKind.RATE_LIMIT
                normalized == "server_overloaded" || normalized.contains("overload") || statusCode == 503 ->
                    RetryErrorKind.SERVER_OVERLOADED
                normalized == "server_error" || normalized == "internal_error" ||
                    (statusCode != null && statusCode >= 500) ->
                    RetryErrorKind.SERVER_ERROR
                normalized == "timeout" || normalized.contains("timeout") ->
                    RetryErrorKind.TIMEOUT
                normalized == "connection_refused" -> RetryErrorKind.CONNECTION_REFUSED
                normalized == "dns_failed" -> RetryErrorKind.DNS_FAILED
                normalized == "connection_reset" -> RetryErrorKind.CONNECTION_RESET
                normalized == "ssl_error" -> RetryErrorKind.SSL_ERROR
                normalized == "network" -> RetryErrorKind.NETWORK
                else -> RetryErrorKind.UNKNOWN
            }
        }
    }
}
