package com.aicode.feature.agent.presentation.run

import com.aicode.feature.agent.presentation.StreamingAssistantMessage
import com.aicode.feature.agent.presentation.hasVisibleContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class StreamingAssistantStore(
    private val maxRetainedSessions: Int = 8,
    private val maxRetainedChars: Long = 1_000_000
) {
    private val lock = Any()
    private val runs = mutableMapOf<String, Any>()
    private val runningCommands = mutableSetOf<String>()
    private val selectedSessions = mutableMapOf<Any, String>()
    private val _messages = MutableStateFlow<Map<String, StreamingAssistantMessage>>(emptyMap())
    val messages: StateFlow<Map<String, StreamingAssistantMessage>> = _messages.asStateFlow()

    fun startRun(sessionId: String, token: Any) = synchronized(lock) {
        runs[sessionId] = token
    }

    fun finishRun(sessionId: String, token: Any, finishedAt: Long? = null) = synchronized(lock) {
        if (runs[sessionId] !== token) return@synchronized
        runs.remove(sessionId)
        val current = _messages.value[sessionId]
        val messages = when {
            current == null -> _messages.value
            !current.settled -> _messages.value - sessionId
            current.reasoningStartMs != null && current.reasoningEndMs == null && finishedAt != null ->
                _messages.value + (sessionId to current.copy(reasoningEndMs = finishedAt))
            else -> _messages.value
        }
        _messages.value = trimLocked(messages)
    }

    fun setCommandRunning(sessionId: String, running: Boolean) = synchronized(lock) {
        if (running) runningCommands.add(sessionId) else runningCommands.remove(sessionId)
        _messages.value = trimLocked(_messages.value)
    }

    fun setSelectedSession(ownerToken: Any, sessionId: String?) = synchronized(lock) {
        if (sessionId == null) selectedSessions.remove(ownerToken)
        else selectedSessions[ownerToken] = sessionId
        _messages.value = trimLocked(_messages.value)
    }

    fun update(
        sessionId: String,
        messageId: String,
        transform: (StreamingAssistantMessage) -> StreamingAssistantMessage
    ) = synchronized(lock) {
        val current = _messages.value[sessionId]?.takeIf { it.messageId == messageId }
            ?: StreamingAssistantMessage(sessionId, messageId)
        _messages.value = trimLocked(_messages.value + (sessionId to transform(current)))
    }

    fun updateIfPresent(
        sessionId: String,
        messageId: String?,
        transform: (StreamingAssistantMessage) -> StreamingAssistantMessage
    ) = synchronized(lock) {
        val current = _messages.value[sessionId]?.takeIf { it.messageId == messageId }
            ?: return@synchronized
        _messages.value = trimLocked(_messages.value + (sessionId to transform(current)))
    }

    fun markPersisted(
        sessionId: String,
        messageId: String,
        content: String,
        reasoning: String
    ) = synchronized(lock) {
        val current = _messages.value[sessionId]?.takeIf {
            it.messageId == messageId && it.content == content && it.reasoning == reasoning && !it.persisted
        } ?: return@synchronized
        val messages = (_messages.value - sessionId) + (sessionId to current.copy(persisted = true))
        _messages.value = trimLocked(messages)
    }

    fun acknowledgeRenderedAssistant(sessionId: String, messageId: String) = synchronized(lock) {
        val current = _messages.value[sessionId] ?: return@synchronized
        if (current.messageId == messageId && current.settled) {
            _messages.value = _messages.value - sessionId
        }
    }

    fun settlePartial(
        sessionId: String,
        stoppedText: String,
        stoppedAt: Long,
        hasRunningTools: Boolean
    ): StreamingAssistantMessage? = synchronized(lock) {
        val current = _messages.value[sessionId] ?: return@synchronized null
        if (current.settled) return@synchronized null
        if (hasRunningTools || (current.content.isEmpty() && current.reasoning.isEmpty())) {
            _messages.value = _messages.value - sessionId
            return@synchronized null
        }
        val partial = current.content.trimEnd()
        val stopped = current.copy(
            content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText,
            reasoning = current.reasoning.takeIf { it.hasVisibleContent() }.orEmpty(),
            settled = true,
            persisted = false,
            reasoningEndMs = current.reasoningStartMs?.let { current.reasoningEndMs ?: stoppedAt }
        )
        _messages.value = _messages.value + (sessionId to stopped)
        stopped
    }

    fun clear(sessionId: String, keepSettled: Boolean = false) = synchronized(lock) {
        clearLocked(sessionId, keepSettled)
    }

    fun clearSession(sessionId: String) = synchronized(lock) {
        runs.remove(sessionId)
        runningCommands.remove(sessionId)
        clearLocked(sessionId, keepSettled = false)
    }

    fun clearAll() = synchronized(lock) {
        runs.clear()
        runningCommands.clear()
        _messages.value = emptyMap()
    }

    private fun clearLocked(sessionId: String, keepSettled: Boolean) {
        if (!keepSettled || _messages.value[sessionId]?.settled != true) {
            _messages.value = _messages.value - sessionId
        }
    }

    private fun trimLocked(
        messages: Map<String, StreamingAssistantMessage>
    ): Map<String, StreamingAssistantMessage> {
        val protectedSessions = selectedSessions.values.toSet()
        val candidates = messages.values.filter {
            it.settled && it.persisted && it.sessionId !in runs &&
                it.sessionId !in runningCommands && it.sessionId !in protectedSessions
        }
        var sessions = candidates.size
        var chars = candidates.sumOf { it.content.length.toLong() + it.reasoning.length }
        if (sessions <= maxRetainedSessions && chars <= maxRetainedChars) return messages
        val retained = messages.toMutableMap()
        for (candidate in candidates) {
            if (sessions <= maxRetainedSessions && chars <= maxRetainedChars) break
            retained.remove(candidate.sessionId)
            sessions--
            chars -= candidate.content.length.toLong() + candidate.reasoning.length
        }
        return retained
    }
}
