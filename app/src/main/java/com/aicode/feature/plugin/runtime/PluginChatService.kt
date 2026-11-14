package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.session.AgentChatRequest
import com.aicode.feature.agent.domain.session.AgentChatRequestBus
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件会话与消息能力。读取范围限定当前工作区；发送消息仍由聊天页 ViewModel 执行，
 * 因此与手动输入共享工具授权、PLAN/AUTO 模式、hooks、通知与队列语义，不会绕过宿主审批。
 *
 * `send` / `stop` / `open` 需要聊天页 ViewModel 存活（App 主界面存在时即满足）；
 * 只创建会话或读取列表、消息不依赖 UI。
 */
@Singleton
class PluginChatService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val workspaceRepository: WorkspaceRepository,
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val requestBus: AgentChatRequestBus
) {

    suspend fun listSessions(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val includeSubagents = optionalBoolean(params, "includeSubagents") ?: false
        val limit = optionalInt(params, "limit", DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT)
        val sessions = if (includeSubagents) {
            chatSessionDao.getAllSessionsByWorkspaceOnce(workspace)
        } else {
            chatSessionDao.getRootSessionsByWorkspaceOnce(workspace)
        }.take(limit)
        return buildJsonObject {
            put("sessions", JsonArray(sessions.map { sessionJson(it) }))
        }
    }

    suspend fun createSession(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val title = optionalString(params, "title")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also { if (it.length > MAX_TITLE_CHARS) throw paramInvalid("title too long") }
            ?: context.getString(R.string.chat_new_session_btn)
        val mode = parseMode(params["mode"])
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        val session = sessionUseCase.newSessionEntity(
            workspacePath = workspace,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        ).copy(title = title, mode = mode.name)
        sessionUseCase.upsertSession(session)
        return buildJsonObject {
            put("session", sessionJson(session))
        }
    }

    suspend fun listMessages(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val sessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val session = requireSession(sessionId, workspace)
        val limit = optionalInt(params, "limit", DEFAULT_MESSAGE_LIMIT, 1, MAX_MESSAGE_LIMIT)
        val before = optionalLong(params, "before")
        val after = optionalLong(params, "after")
        if (before != null && after != null) throw paramInvalid("before and after are mutually exclusive")

        // 多取一条用于判断是否还有更早/更多消息；返回前按时间正序。
        val rows = agentMessageDao.getMessagesPageDescending(
            sessionId = session.id,
            before = before,
            after = after,
            limit = limit + 1
        )
        val hasMoreRows = rows.size > limit
        var usedChars = 0
        var truncatedByBudget = false
        val kept = mutableListOf<AgentMessageEntity>()
        for (message in rows.take(limit)) {
            val cost = message.content.length + (message.toolName?.length ?: 0)
            if (kept.isNotEmpty() && usedChars + cost > MAX_LISTED_MESSAGE_CHARS) {
                truncatedByBudget = true
                break
            }
            kept.add(message)
            usedChars += cost
        }
        return buildJsonObject {
            put("sessionId", session.id)
            put("messages", JsonArray(kept.asReversed().map { messageJson(it) }))
            put("hasMore", hasMoreRows || truncatedByBudget)
        }
    }

    suspend fun sendMessage(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val sessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val session = requireSession(sessionId, workspace)
        val text = PluginHostPaths.requireString(params, "text")
        if (text.isBlank()) throw paramInvalid("text is blank")
        if (text.length > MAX_MESSAGE_CHARS) throw paramInvalid("text too long")
        submit(AgentChatRequest.Send(session.id, text, session.workspacePath))
        return buildJsonObject {
            put("sessionId", session.id)
            put("accepted", true)
        }
    }

    suspend fun stop(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val sessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val session = requireSession(sessionId, workspace)
        submit(AgentChatRequest.Stop(session.id))
        return buildJsonObject {
            put("sessionId", session.id)
            put("accepted", true)
        }
    }

    suspend fun openSession(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val workspace = requireWorkspace()
        val sessionId = PluginHostPaths.requireString(params, "sessionId").trim()
        val session = requireSession(sessionId, workspace)
        submit(AgentChatRequest.Select(session.id))
        return buildJsonObject {
            put("sessionId", session.id)
            put("accepted", true)
        }
    }

    private fun submit(request: AgentChatRequest) {
        if (!requestBus.isAttached) {
            throw PluginHostException("CHAT_UI_UNAVAILABLE", "chat UI is not available")
        }
        if (!requestBus.submit(request)) {
            throw PluginHostException("CHAT_BUSY", "chat request queue is full")
        }
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun requireWorkspace(): String {
        val current = workspaceRepository.current.value
        if (current == null || current.path.isBlank()) {
            throw PluginHostException("WORKSPACE_UNAVAILABLE", "no active workspace")
        }
        return current.path
    }

    private suspend fun requireSession(sessionId: String, workspace: String): ChatSessionEntity {
        if (sessionId.isBlank()) throw paramInvalid("missing sessionId")
        val session = sessionUseCase.getSessionById(sessionId)
            ?: throw PluginHostException("SESSION_NOT_FOUND", "session not found: $sessionId")
        if (!sameWorkspace(session.workspacePath, workspace)) {
            throw PluginHostException("SESSION_FORBIDDEN", "session is outside the current workspace")
        }
        return session
    }

    private fun sameWorkspace(left: String, right: String): Boolean =
        left.trimEnd('/') == right.trimEnd('/')

    private fun parseMode(value: JsonElement?): AgentMode {
        val raw = (value as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return AgentMode.BUILD
        return when (raw) {
            "build" -> AgentMode.BUILD
            "plan" -> AgentMode.PLAN
            "auto" -> throw PluginHostException("MODE_UNSUPPORTED", "auto mode is user-only")
            else -> throw paramInvalid("unknown mode: $raw")
        }
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.contentOrNull
            ?: throw paramInvalid("$key must be a string")
    }

    private fun optionalBoolean(params: JsonObject, key: String): Boolean? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.booleanOrNull
            ?: throw paramInvalid("$key must be a boolean")
    }

    private fun optionalLong(params: JsonObject, key: String): Long? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.longOrNull
            ?: throw paramInvalid("$key must be a number")
    }

    private fun optionalInt(params: JsonObject, key: String, default: Int, min: Int, max: Int): Int {
        val value = optionalLong(params, key) ?: return default
        return value.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private fun sessionJson(session: ChatSessionEntity): JsonObject = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("mode", session.mode.lowercase())
        put("isPinned", session.isPinned)
        session.parentId?.let { put("parentId", it) }
        session.subagentType?.let { put("subagentType", it) }
        session.providerId?.let { put("providerId", it) }
        session.model?.let { put("model", it) }
        put("totalInputTokens", session.totalInputTokens)
        put("totalOutputTokens", session.totalOutputTokens)
    }

    private fun messageJson(message: AgentMessageEntity): JsonObject = buildJsonObject {
        put("id", message.id)
        put("role", message.role.lowercase())
        put("content", message.content)
        put("timestamp", message.timestamp)
        message.toolName?.let { put("toolName", it) }
        put("isError", message.isError)
        put("isCompacted", message.isCompacted)
        put("isContextSummary", message.isContextSummary)
        put("isCompactionMarker", message.isCompactionMarker)
        put("inputTokens", message.inputTokens)
        put("outputTokens", message.outputTokens)
        put("cachedInputTokens", message.cachedInputTokens)
    }

    private companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 200
        const val DEFAULT_MESSAGE_LIMIT = 50
        const val MAX_MESSAGE_LIMIT = 200
        const val MAX_TITLE_CHARS = 100
        const val MAX_MESSAGE_CHARS = 32_000
        const val MAX_LISTED_MESSAGE_CHARS = 1_000_000
    }
}
