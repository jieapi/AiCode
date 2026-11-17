package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RetryState
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.StreamingAssistantMessage
import com.aicode.feature.plugin.domain.session.PluginSessionSummary
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PluginRemoteRunState(
    val running: Boolean = false,
    val assistant: StreamingAssistantMessage? = null,
    val settled: List<AgentUIMessage> = emptyList(),
    val tools: Map<String, RunningToolOutput> = emptyMap(),
    val retry: RetryState? = null,
    val error: String? = null,
    val renderedIds: Set<String> = emptySet()
)

@Singleton
class PluginRemoteRunManager @Inject constructor(
    private val providerRepository: AIProviderRepository,
    private val providerFactory: ProviderInstanceFactory,
    private val pluginManager: PluginManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Any()
    private val mutableStates = MutableStateFlow<Map<String, PluginRemoteRunState>>(emptyMap())
    internal val states = mutableStates.asStateFlow()

    init {
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                val active = plugins.filter { it.isActive }.mapTo(HashSet()) { it.id }
                synchronized(lock) {
                    jobs.filterKeys { id -> active.none { id.startsWith("$it:") } }
                        .values.forEach { it.cancel() }
                }
            }
        }
    }

    fun start(session: PluginSessionSummary, prompt: String?, onFinished: suspend () -> Unit) {
        synchronized(lock) {
            if (jobs[session.id]?.isActive == true) return
            update(session.id) { it.copy(running = true, error = null, retry = null) }
            jobs[session.id] = scope.launch {
                try {
                    val localProviderId = requireNotNull(session.providerId) { "REMOTE_PROVIDER_REQUIRED" }
                    val providerId = PluginProviderIds.idOf(session.pluginId, localProviderId)
                    val config = providerRepository.getProviderById(providerId)
                        ?: error("REMOTE_PROVIDER_NOT_FOUND")
                    val provider = providerFactory.create(
                        config.copy(selectedModel = session.model?.takeIf { it.isNotBlank() } ?: config.effectiveModel),
                        session.id
                    )
                    provider.customHeaders = provider.customHeaders + mapOf(
                        "X-AiCode-Session-Id" to session.id,
                        "X-AiCode-Operation-Id" to UUID.randomUUID().toString(),
                        "X-AiCode-Subscribe-Only" to (prompt == null).toString()
                    )
                    provider.useResponseApi = false
                    val messages = prompt?.let { listOf(AgentMessage.UserMessage(content = it)) }.orEmpty()
                    provider.completeStream("", messages, emptyList()).collect { chunk ->
                        when (chunk) {
                            is AIStreamChunk.TextDelta -> if (chunk.messageId != null) {
                                settle(session.id, chunk.messageId)
                            } else append(session.id, chunk.text, reasoning = false)
                            is AIStreamChunk.ReasoningDelta -> append(session.id, chunk.text, reasoning = true)
                            is AIStreamChunk.Retrying -> update(session.id) {
                                it.copy(assistant = null, retry = RetryState(chunk.attempt, chunk.maxRetries, chunk.error))
                            }
                            is AIStreamChunk.Final -> Unit
                            is AIStreamChunk.ToolCallDeclared -> error("REMOTE_TOOL_CALLS_NOT_SUPPORTED")
                            is AIStreamChunk.KeySwitched -> Unit
                        }
                    }
                    settle(session.id, null)
                    onFinished()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    update(session.id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
                } finally {
                    update(session.id) { it.copy(running = false, retry = null, tools = emptyMap()) }
                }
            }
        }
    }

    fun stop(sessionId: String) {
        synchronized(lock) { jobs.remove(sessionId)?.cancel() }
    }

    internal fun updateTool(sessionId: String, callId: String, tool: RunningToolOutput?) {
        update(sessionId) { it.copy(tools = if (tool == null) it.tools - callId else it.tools + (callId to tool)) }
    }

    internal fun retainMessages(sessionId: String, renderedIds: Set<String>) {
        update(sessionId) { it.copy(
            settled = it.settled.filterNot { message -> message.id in renderedIds },
            renderedIds = renderedIds
        ) }
    }

    private fun append(sessionId: String, text: String, reasoning: Boolean) {
        if (text.isEmpty()) return
        update(sessionId) { state ->
            val current = state.assistant ?: StreamingAssistantMessage(
                sessionId = sessionId,
                messageId = "remote-stream-${UUID.randomUUID()}"
            )
            state.copy(
                retry = null,
                assistant = if (reasoning) current.copy(reasoning = current.reasoning + text)
                    else current.copy(content = current.content + text)
            )
        }
    }

    private fun settle(sessionId: String, messageId: String?) {
        update(sessionId) { state ->
            val current = state.assistant ?: return@update state
            val message = AgentUIMessage(
                id = messageId ?: current.messageId,
                role = MessageRole.ASSISTANT,
                content = current.content,
                reasoning = current.reasoning.takeIf { it.isNotBlank() }
            )
            state.copy(assistant = null, settled = if (message.id in state.renderedIds) state.settled
                else state.settled.filterNot { it.id == message.id } + message)
        }
    }

    private fun update(sessionId: String, transform: (PluginRemoteRunState) -> PluginRemoteRunState) {
        mutableStates.update { current -> current + (sessionId to transform(current[sessionId] ?: PluginRemoteRunState())) }
    }
}
