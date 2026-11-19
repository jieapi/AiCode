package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ExecutionBackend
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件把模型返回的工具调用交给宿主执行的入口。
 *
 * 只允许内置与 MCP 工具（`plugin__` 命名空间一律拒绝），并复用聊天页同一套权限策略与
 * [ToolPermissionManager]：ASK 的请求以 [PendingToolPermission.ORIGIN_PLUGIN] 挂起，只在插件
 * 自己的会话里可见，不进入聊天页弹窗与侧边栏等待指示。插件不走聊天页的插件代审核链，
 * 请求只交给插件页面呈现，由使用者选择后通过 [pending] / [resolve] 回传。
 *
 * 会话 id 以 `plugin:<插件 ID>` 为根，插件可加 `:` 后缀区分自己的多个任务；不能使用其它会话，
 * 避免读到或放行聊天页的授权请求。
 */
@Singleton
class PluginToolService @Inject constructor(
    private val pluginManager: PluginManager,
    private val workspaceRepository: WorkspaceRepository,
    private val toolRegistry: ToolRegistry,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val permissionManager: ToolPermissionManager,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection
) {

    /** 待审批请求 → 发起它的会话；resolve 只带 requestId，需要据此找回会话。 */
    private val approvalSessions = ConcurrentHashMap<String, String>()

    /**
     * 执行一次工具调用。
     *
     * `timeoutMs` 同时约束等待授权与执行本身：前者超时抛 `TOOL_APPROVAL_TIMEOUT`，
     * 后者抛 `TOOL_TIMEOUT`。策略拒绝、用户拒绝与工具不存在分别抛 `TOOL_DENIED` /
     * `TOOL_USER_REJECTED` / `TOOL_NOT_FOUND`。
     */
    suspend fun execute(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val name = PluginHostPaths.requireString(params, "name").trim()
        if (name.isEmpty()) throw paramInvalid("name is blank")
        if (name.startsWith(PLUGIN_TOOL_PREFIX)) {
            throw PluginHostException(
                "TOOL_NOT_ALLOWED",
                "plugin tools cannot be executed through tools.execute: $name"
            )
        }
        if (name in SESSION_BOUND_TOOLS) {
            throw PluginHostException(
                "TOOL_NOT_ALLOWED",
                "tool requires a chat session: $name"
            )
        }
        val tool = toolRegistry.getTool(name)
            ?: throw PluginHostException("TOOL_NOT_FOUND", "tool not found: $name")
        val arguments = readArguments(params)
        val sessionId = resolveSessionId(pluginId, params)
        val timeoutMs = readTimeoutMs(params)

        val context = buildContext(params, sessionId)
        val guard = ExecutionGuard(pluginId, context.projectRoot)
        return guard.run {
            val project = policyEngine.captureProject(context.projectRoot)
            guard.check()
            val eval = policyEngine.evaluate(tool, name, arguments, AgentMode.BUILD, project)
            guard.check()
            if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
                throw PluginHostException(
                    "TOOL_DENIED",
                    eval.denyReason ?: "tool denied by permission policy: $name"
                )
            }
            val needsApproval = eval.verdict == ToolPermissionPolicyEngine.Verdict.ASK &&
                tool.permissionPolicy != ToolPermissionPolicy.AUTO_APPROVE
            if (needsApproval) {
                awaitApproval(tool, name, arguments, eval, sessionId, timeoutMs, project, guard)
            }

            guard.check()
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    // 工具后端仍动态选路；这里只保护调用边界，不能原子锁住后端内部的所有挂起点。
                    guard.check()
                    tool.executeWithContext(arguments, context)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginHostException) {
                throw e
            } catch (e: Exception) {
                throw PluginHostException("TOOL_ERROR", e.message ?: e.javaClass.simpleName)
            } ?: throw PluginHostException("TOOL_TIMEOUT", "tool execution timed out: $name")
            guard.check()
            resultJson(result)
        }
    }

    /** 当前会话里由本插件发起的待审批请求；没有时 `approval` 为 null。 */
    fun pending(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val sessionId = resolveSessionId(pluginId, params)
        val approval = permissionManager.pendingForSession(sessionId)
            ?.takeIf { it.origin == PendingToolPermission.ORIGIN_PLUGIN }
        return buildJsonObject {
            put("approval", approval?.let(::approvalJson) ?: JsonNull)
        }
    }

    /** 回传使用者对 [pending] 请求的选择；只允许处理本插件会话里 origin=plugin 的请求。 */
    fun resolve(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val requestId = PluginHostPaths.requireString(params, "requestId").trim()
        if (requestId.isEmpty()) throw paramInvalid("requestId is blank")
        val sessionId = approvalSessions[requestId] ?: defaultSessionId(pluginId)
        val baseSessionId = defaultSessionId(pluginId)
        if (sessionId != baseSessionId && !sessionId.startsWith("$baseSessionId:")) {
            throw PluginHostException("APPROVAL_NOT_FOUND", "tool approval not found: $requestId")
        }
        permissionManager.pendingForSession(sessionId)
            ?.takeIf { it.id == requestId && it.origin == PendingToolPermission.ORIGIN_PLUGIN }
            ?: throw PluginHostException("APPROVAL_NOT_FOUND", "tool approval not found: $requestId")
        val choice = when (PluginHostPaths.requireString(params, "choice").trim().lowercase()) {
            "once" -> PermissionChoice.ONCE
            "always" -> PermissionChoice.ALWAYS
            "reject" -> PermissionChoice.REJECT
            else -> throw paramInvalid("unknown approval choice")
        }
        permissionManager.resolve(requestId, choice)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun awaitApproval(
        tool: AgentTool,
        name: String,
        arguments: Map<String, JsonElement>,
        eval: ToolPermissionPolicyEngine.EvalResult,
        sessionId: String,
        timeoutMs: Long,
        project: PermissionRulesRepository.ProjectContext,
        guard: ExecutionGuard
    ) {
        val callId = "plugin-tool-${UUID.randomUUID()}"
        val argsPreview = JsonObject(arguments).toString().take(MAX_ARGS_PREVIEW_CHARS)
        val request = tool.buildPermissionRequest(callId, arguments, argsPreview).copy(
            rememberablePatterns = eval.rememberablePatterns,
            rememberDisabledReason = eval.rememberDisabledReason,
            sessionId = sessionId,
            origin = PendingToolPermission.ORIGIN_PLUGIN
        )
        guard.check()
        approvalSessions[callId] = sessionId
        val choice = try {
            withTimeoutOrNull(timeoutMs) {
                guard.check()
                permissionManager.awaitApproval(request)
            }
        } finally {
            approvalSessions.remove(callId)
        }
        guard.check()
        if (choice == null) {
            throw PluginHostException("TOOL_APPROVAL_TIMEOUT", "tool approval timed out: $name")
        }
        when (choice) {
            PermissionChoice.REJECT ->
                throw PluginHostException("TOOL_USER_REJECTED", "user rejected tool execution: $name")
            PermissionChoice.ALWAYS -> if (eval.rememberablePatterns.isNotEmpty()) {
                policyEngine.remember(name, eval.rememberablePatterns, project)
                guard.check()
            }
            PermissionChoice.ONCE -> Unit
        }
    }

    private inner class ExecutionGuard(
        private val pluginId: String,
        private val workspacePath: String
    ) {
        private val backend = executionModeHolder.currentBackend()
        private val backendId = backend.id
        private val environmentKey = backend.environmentCacheKey()
        private val failure = AtomicReference<PluginHostException?>()
        private val invalidated = CompletableDeferred<PluginHostException>()

        private fun refresh(
            currentWorkspacePath: String? = workspaceRepository.current.value?.path,
            currentBackend: ExecutionBackend = executionModeHolder.currentBackend(),
            pluginActive: Boolean = pluginManager.plugins.value.any { it.id == pluginId && it.isActive }
        ) {
            if (failure.get() != null) return
            val error = when {
                !pluginActive -> PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
                currentWorkspacePath != workspacePath ||
                    currentBackend.id != backendId ||
                    currentBackend.environmentCacheKey() != environmentKey ->
                    PluginHostException("STALE_CONTEXT", "tool execution context has changed")
                else -> null
            }
            if (error != null && failure.compareAndSet(null, error)) invalidated.complete(error)
        }

        fun check() {
            refresh()
            failure.get()?.let { throw it }
        }

        suspend fun <T> run(block: suspend () -> T): T = supervisorScope {
            // Unconfined 让状态发布时立即留下失效标记，避免切走再切回恢复旧授权。
            val watchers = listOf(
                launch(Dispatchers.Unconfined) {
                    workspaceRepository.current.collect { refresh(currentWorkspacePath = it?.path) }
                },
                launch(Dispatchers.Unconfined) {
                    executionModeHolder.activeBackend.collect { refresh(currentBackend = it) }
                },
                launch(Dispatchers.Unconfined) {
                    pluginManager.plugins.collect { plugins ->
                        refresh(pluginActive = plugins.any { it.id == pluginId && it.isActive })
                    }
                },
                launch(Dispatchers.Unconfined) { remoteSshConnection.connectionState.collect { refresh() } },
                // config 只有同步快照，没有专门的身份流；连接状态之外补充有界检查。
                launch {
                    while (isActive) {
                        refresh()
                        delay(CONTEXT_CHECK_INTERVAL_MS)
                    }
                }
            )
            val execution = async {
                check()
                block()
            }
            try {
                select {
                    invalidated.onAwait { throw it }
                    execution.onAwait {
                        check()
                        it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                check()
                throw e
            } finally {
                execution.cancel()
                watchers.forEach { it.cancel() }
            }
        }
    }

    private fun readArguments(params: JsonObject): Map<String, JsonElement> {
        val raw = params["arguments"] ?: return emptyMap()
        if (raw is JsonNull) return emptyMap()
        val arguments = raw as? JsonObject ?: throw paramInvalid("arguments must be an object")
        if (arguments.toString().length > MAX_ARGS_CHARS) {
            throw paramInvalid("arguments are too large")
        }
        return arguments
    }

    /** `plugin:<插件 ID>` 或它的 `:` 后缀子会话；其它会话一律拒绝。 */
    private fun resolveSessionId(pluginId: String, params: JsonObject): String {
        val requested = optionalString(params, "sessionId")?.trim().orEmpty()
        if (requested.isEmpty()) return defaultSessionId(pluginId)
        val base = defaultSessionId(pluginId)
        if (requested != base && !requested.startsWith("$base:")) {
            throw PluginHostException("SESSION_FORBIDDEN", "sessionId is outside the plugin namespace")
        }
        return requested
    }

    private fun defaultSessionId(pluginId: String): String = "$SESSION_PREFIX$pluginId"

    private fun readTimeoutMs(params: JsonObject): Long {
        val raw = params["timeoutMs"] ?: return DEFAULT_TIMEOUT_MS
        val value = (raw as? JsonPrimitive)?.longOrNull
            ?: throw paramInvalid("timeoutMs must be a number")
        return value.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    private fun buildContext(params: JsonObject, sessionId: String): AgentContext {
        val raw = params["context"]
        val context = when {
            raw == null || raw is JsonNull -> null
            else -> raw as? JsonObject ?: throw paramInvalid("context must be an object")
        }
        return AgentContext(
            currentFile = context?.let { optionalString(it, "path") },
            selectedCode = context?.let { optionalString(it, "selection") },
            projectRoot = requireWorkspace(),
            language = context?.let { optionalString(it, "language") },
            sessionId = sessionId,
            mode = AgentMode.BUILD
        )
    }

    private fun resultJson(result: ToolResult): JsonElement = when (result) {
        is ToolResult.Success -> buildJsonObject {
            put("status", "success")
            putData(result.data)
            if (result.images.isNotEmpty()) putImages(result.images)
        }
        is ToolResult.Error -> buildJsonObject {
            put("status", "error")
            put("message", result.message)
            put("code", result.code)
        }
        is ToolResult.Partial -> buildJsonObject {
            put("status", "partial")
            putData(result.data)
            put("message", result.message)
        }
    }

    /** 结果数据超过 [MAX_DATA_BYTES] 时改为截断后的字符串，并标记 dataTruncated。 */
    private fun JsonObjectBuilder.putData(data: JsonElement) {
        val encoded = data.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_DATA_BYTES) {
            put("data", data)
        } else {
            put("data", truncateUtf8(encoded, MAX_DATA_BYTES))
            put("dataTruncated", true)
        }
    }

    /** 单图 base64 与总量都有上限；放不下的图整张省略，并标记 imagesTruncated。 */
    private fun JsonObjectBuilder.putImages(images: List<AgentImage>) {
        val kept = mutableListOf<JsonObject>()
        var total = 0
        var truncated = false
        images.forEach { image ->
            val size = image.base64Data.length
            if (size > MAX_IMAGE_BASE64_BYTES || total + size > MAX_TOTAL_IMAGE_BASE64_BYTES) {
                truncated = true
                return@forEach
            }
            total += size
            kept += buildJsonObject {
                put("mimeType", image.mimeType)
                put("base64", image.base64Data)
                image.path.takeIf { it.isNotBlank() }?.let { put("path", it) }
            }
        }
        if (kept.isNotEmpty()) put("images", JsonArray(kept))
        if (truncated) put("imagesTruncated", true)
    }

    /** 按 UTF-8 字节上限截断，且不切断多字节字符。 */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun approvalJson(pending: PendingToolPermission): JsonObject = buildJsonObject {
        put("id", pending.id)
        put("toolName", pending.toolName)
        put("title", pending.title)
        put("summary", pending.summary)
        put("details", pending.details.take(MAX_APPROVAL_DETAIL_CHARS))
        put("argsPreview", pending.argsPreview.take(MAX_APPROVAL_DETAIL_CHARS))
        put("sessionId", pending.sessionId)
        put("rememberablePatterns", JsonArray(pending.rememberablePatterns.map { JsonPrimitive(it) }))
        pending.rememberDisabledReason?.let { put("rememberDisabledReason", it) }
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

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.contentOrNull
            ?: throw paramInvalid("$key must be a string")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val SESSION_PREFIX = "plugin:"
        const val PLUGIN_TOOL_PREFIX = "plugin__"
        const val CONTEXT_CHECK_INTERVAL_MS = 100L
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_ARGS_PREVIEW_CHARS = 500
        const val MAX_APPROVAL_DETAIL_CHARS = 4_000
        const val MAX_DATA_BYTES = 256 * 1024
        const val MAX_ARGS_CHARS = 2 * 1024 * 1024
        const val MAX_IMAGE_BASE64_BYTES = 1536 * 1024
        const val MAX_TOTAL_IMAGE_BASE64_BYTES = 2 * 1024 * 1024

        /** 依赖聊天会话/Agent 上下文的工具，插件侧无法提供对应上下文，直接拒绝。 */
        val SESSION_BOUND_TOOLS = setOf(
            "askUserQuestion",
            "planMode",
            "goalStatus",
            "todo",
            "task",
            "messageParent"
        )
    }
}
