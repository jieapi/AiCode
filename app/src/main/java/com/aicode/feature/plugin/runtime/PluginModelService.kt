package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.workflow.ContextTokenEstimator
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件模型句柄：把「供应商 + 模型 + 默认提示词/参数」封装成宿主托管的句柄，
 * 插件用它直接发起单次模型补全。不走聊天页 ViewModel，也不执行 Agent 工具循环。
 *
 * 流式采用 start / read / cancel 的拉取式协议：请求体只经一次 JSON-RPC，
 * 事件在宿主缓冲后由插件按 cursor 批量读取，避免逐 token 跨进程调用。
 */
@Singleton
class PluginModelService @Inject constructor(
    private val pluginManager: PluginManager,
    private val providerRepository: AIProviderRepository,
    private val providerFactory: ProviderInstanceFactory,
    private val modelMetadataService: ModelMetadataService,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository
) {
    private data class ModelParams(
        val reasoningEffort: String? = null,
        val maxOutputTokens: Int? = null,
        val temperature: Float? = null
    )

    private class Handle(
        val id: String,
        val pluginId: String,
        val pluginVersion: String,
        val providerId: String,
        val model: String,
        val name: String?,
        val systemPrompt: String?,
        val params: ModelParams,
        val createdAt: Long
    )

    private data class BufferedEvent(val event: JsonObject, val chars: Int)

    private class Stream(
        val id: String,
        val pluginId: String,
        val handleId: String,
        val origin: String
    ) {
        val bufferLock = Any()
        val readLock = Mutex()
        val signal = kotlinx.coroutines.channels.Channel<Unit>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED
        )
        val events = ArrayDeque<BufferedEvent>()
        var delivered = 0
        var bufferedChars = 0L
        @Volatile var done = false
        var status = "running"
        var final: JsonObject? = null
        var error: JsonObject? = null
        @Volatile var doneAt = 0L
        @Volatile var lastReadAt = System.currentTimeMillis()
        @Volatile var job: Job? = null
    }

    private val handles = ConcurrentHashMap<String, Handle>()
    private val streams = ConcurrentHashMap<String, Stream>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupStale()
            }
        }
    }

    /** 已配置的供应商；不返回 Key、地址、自定义头或代理配置。 */
    suspend fun listProviders(pluginId: String): JsonElement {
        ensureAccess(pluginId)
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val providers = providerRepository.getAllProviders().first()
        return buildJsonObject {
            put("providers", JsonArray(providers.map { config ->
                buildJsonObject {
                    put("id", config.id)
                    put("name", config.name)
                    put("type", config.type.name.lowercase(Locale.ROOT))
                    put("enabled", config.isEnabled)
                    put("hasKey", config.hasUsableApiKey)
                    put("isPlugin", PluginProviderIds.isPluginId(config.id))
                    put("isDefault", config.id == defaultProviderId)
                    put("defaultModel", config.defaultModel)
                    put("selectedModel", config.selectedModel)
                    put("modelCount", config.models.size)
                }
            }))
        }
    }

    /** 供应商已添加的模型及元数据；默认不联网，不写回供应商配置。 */
    suspend fun listModels(pluginId: String, params: JsonObject): JsonElement {
        ensureAccess(pluginId)
        val providerId = PluginHostPaths.requireString(params, "providerId").trim()
        val config = requireProvider(providerId)
        val modelIds = configuredModels(config)
        val listed = modelIds.take(MAX_MODELS)
        val metadata = modelMetadataService.resolveAll(config.id, config.type, listed)
        return buildJsonObject {
            put("providerId", config.id)
            put("models", JsonArray(listed.map { id -> modelJson(metadata.getValue(id)) }))
            if (modelIds.size > listed.size) put("truncated", true)
        }
    }

    /** 创建句柄；provider/model 在每次请求时重新校验，用户改配置后下一次生效。 */
    suspend fun create(pluginId: String, params: JsonObject): JsonElement {
        val plugin = ensureAccess(pluginId)
        val providerId = PluginHostPaths.requireString(params, "providerId").trim()
        if (providerId.isEmpty()) throw paramInvalid("providerId is blank")
        if (PluginProviderIds.pluginIdOf(providerId) == pluginId) {
            throw PluginHostException(
                "SELF_PROVIDER_FORBIDDEN",
                "a plugin cannot create a model handle for its own provider"
            )
        }
        val config = requireProvider(providerId)
        ensureProviderUsable(config)
        val model = resolveModel(config, optionalString(params, "model"))
        val metadata = modelMetadataService.resolve(config.id, config.type, model)
        ensureChatModel(metadata)
        val systemPrompt = optionalString(params, "systemPrompt")?.also {
            if (it.length > MAX_SYSTEM_PROMPT_CHARS) throw paramInvalid("systemPrompt too long")
        }
        val modelParams = parseParams(params["params"])
        validateParams(modelParams, metadata)
        val name = optionalString(params, "name")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also { if (it.length > MAX_HANDLE_NAME_CHARS) throw paramInvalid("name too long") }
        enforceHandleLimit(pluginId)
        val handle = Handle(
            id = UUID.randomUUID().toString(),
            pluginId = pluginId,
            pluginVersion = plugin.record.version,
            providerId = config.id,
            model = model,
            name = name,
            systemPrompt = systemPrompt,
            params = modelParams,
            createdAt = System.currentTimeMillis()
        )
        handles[handle.id] = handle
        return buildJsonObject { put("handle", handleJson(handle)) }
    }

    fun close(pluginId: String, params: JsonObject): JsonElement {
        ensureAccess(pluginId)
        val handleId = PluginHostPaths.requireString(params, "handleId").trim()
        val handle = handles[handleId]?.takeIf { it.pluginId == pluginId }
            ?: throw PluginHostException("HANDLE_NOT_FOUND", "model handle not found: $handleId")
        closeHandle(handle)
        return buildJsonObject { put("ok", true) }
    }

    /** 发起一次流式补全；立即返回 streamId，事件由 [read] 拉取。 */
    suspend fun start(pluginId: String, params: JsonObject, origin: String): JsonElement {
        ensureAccess(pluginId)
        cleanupStale()
        val handleId = PluginHostPaths.requireString(params, "handleId").trim()
        val handle = handles[handleId]?.takeIf { it.pluginId == pluginId }
            ?: throw PluginHostException("HANDLE_NOT_FOUND", "model handle not found: $handleId")
        val messages = parseMessages(params["messages"])
        if (messages.all { it.textContent().isBlank() }) throw paramInvalid("messages are empty")
        val systemPrompt = optionalString(params, "systemPrompt") ?: handle.systemPrompt
        if (systemPrompt != null && systemPrompt.length > MAX_SYSTEM_PROMPT_CHARS) {
            throw paramInvalid("systemPrompt too long")
        }
        val modelParams = mergeParams(handle.params, parseParams(params["params"]))
        val config = requireProvider(handle.providerId)
        ensureProviderUsable(config)
        val model = resolveModel(config, handle.model)
        val metadata = modelMetadataService.resolve(config.id, config.type, model)
        ensureChatModel(metadata)
        validateParams(modelParams, metadata)
        val estimated = ContextTokenEstimator.estimate(systemPrompt.orEmpty(), messages, emptyList())
        val budget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (estimated >= budget) {
            throw PluginHostException(
                "CONTEXT_TOO_LARGE",
                "estimated input $estimated tokens exceeds budget $budget"
            )
        }
        enforceStreamLimit(pluginId)
        val scopeId = "plugin-$pluginId-${handle.id}"
        val provider = try {
            providerFactory.create(config.copy(selectedModel = model), scopeId)
        } catch (e: IllegalStateException) {
            throw PluginHostException("PROVIDER_NO_KEY", e.message ?: "provider has no usable key")
        }
        modelParams.maxOutputTokens?.let { requested ->
            provider.maxOutputTokens = requested.coerceAtMost(
                metadata.outputTokens?.takeIf { it > 0 } ?: MAX_OUTPUT_TOKENS
            )
        }
        modelParams.temperature?.let { provider.temperature = it }
        val stream = Stream(
            id = UUID.randomUUID().toString(),
            pluginId = pluginId,
            origin = origin,
            handleId = handle.id
        )
        streams[stream.id] = stream
        stream.job = scope.launch {
            runStream(
                stream = stream,
                provider = provider,
                systemPrompt = systemPrompt.orEmpty(),
                messages = messages,
                params = modelParams,
                secretValues = config.effectiveApiKeys.map { it.value }
            )
        }
        return buildJsonObject { put("streamId", stream.id) }
    }

    /**
     * 按 cursor 读取一批事件；[waitMs] 内没有新事件时返回空批次。
     * done=true 时带 status 与 final/error。
     */
    suspend fun read(pluginId: String, params: JsonObject): JsonElement {
        ensureAccess(pluginId)
        cleanupStale()
        val streamId = PluginHostPaths.requireString(params, "streamId").trim()
        val stream = streams[streamId]?.takeIf { it.pluginId == pluginId }
            ?: throw PluginHostException("STREAM_NOT_FOUND", "model stream not found: $streamId")
        val cursor = optionalInt(params, "cursor") ?: 0
        if (cursor < 0) throw paramInvalid("cursor must be >= 0")
        val maxEvents = (optionalInt(params, "maxEvents") ?: DEFAULT_READ_EVENTS)
            .coerceIn(1, MAX_READ_EVENTS)
        val waitMs = (optionalLong(params, "waitMs") ?: DEFAULT_READ_WAIT_MS)
            .coerceIn(MIN_READ_WAIT_MS, MAX_READ_WAIT_MS)
        return stream.readLock.withLock {
            val deadline = System.currentTimeMillis() + waitMs
            var result: JsonObject? = null
            while (result == null) {
                result = takeReadResult(stream, cursor, maxEvents)
                if (result == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        result = emptyReadResult(stream)
                    } else {
                        withTimeoutOrNull(remaining) { stream.signal.receive() }
                    }
                }
            }
            stream.lastReadAt = System.currentTimeMillis()
            result ?: emptyReadResult(stream)
        }
    }

    fun cancel(pluginId: String, params: JsonObject): JsonElement {
        ensureAccess(pluginId)
        val streamId = PluginHostPaths.requireString(params, "streamId").trim()
        val stream = streams[streamId]?.takeIf { it.pluginId == pluginId }
            ?: throw PluginHostException("STREAM_NOT_FOUND", "model stream not found: $streamId")
        cancelStream(stream)
        return buildJsonObject { put("ok", true) }
    }

    /** 页面关闭时取消该页面发起的流，不影响同插件的 runtime 请求。 */
    fun closeOrigin(origin: String) {
        streams.values.filter { it.origin == origin }.forEach { cancelStream(it) }
    }

    /** 插件停用、卸载或版本替换时调用：关闭全部句柄并取消在途流。 */
    fun closePlugin(pluginId: String) {
        handles.values.filter { it.pluginId == pluginId }.forEach(::closeHandle)
        streams.values.filter { it.pluginId == pluginId }.forEach { cancelStream(it) }
    }

    private suspend fun runStream(
        stream: Stream,
        provider: AIProvider,
        systemPrompt: String,
        messages: List<AgentMessage>,
        params: ModelParams,
        secretValues: List<String>
    ) {
        val startedAt = System.currentTimeMillis()
        val accumulated = StringBuilder()
        val deltaLock = Any()
        val pending = StringBuilder()
        var pendingType: String? = null

        fun flushDeltaLocked() {
            if (pending.isEmpty()) return
            val type = pendingType ?: "text"
            val text = pending.toString()
            pending.setLength(0)
            pendingType = null
            var offset = 0
            while (offset < text.length) {
                val end = minOf(offset + MAX_TEXT_EVENT_CHARS, text.length)
                emit(stream, textEvent(type, text.substring(offset, end)))
                offset = end
            }
        }

        fun flushDelta() {
            synchronized(deltaLock) { flushDeltaLocked() }
        }

        fun appendDelta(type: String, text: String) {
            if (text.isEmpty()) return
            synchronized(deltaLock) {
                if (pendingType != null && pendingType != type) flushDeltaLocked()
                pendingType = type
                pending.append(text)
                if (pending.length >= MAX_TEXT_EVENT_CHARS) flushDeltaLocked()
            }
        }

        var finalResponse: AIResponse? = null
        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive && !stream.done) {
                        delay(DELTA_FLUSH_INTERVAL_MS)
                        flushDelta()
                    }
                }
                try {
                    provider.completeStream(systemPrompt, messages, emptyList(), params.reasoningEffort)
                        .collect { chunk ->
                            if (stream.done) return@collect
                            when (chunk) {
                                is AIStreamChunk.TextDelta -> {
                                    accumulated.append(chunk.text)
                                    appendDelta("text", chunk.text)
                                }
                                is AIStreamChunk.ReasoningDelta -> appendDelta("reasoning", chunk.text)
                                is AIStreamChunk.ToolCallDeclared -> {
                                    flushDelta()
                                    emit(stream, buildJsonObject {
                                        put("type", "toolCall")
                                        put("name", chunk.name)
                                    })
                                }
                                is AIStreamChunk.Retrying -> {
                                    flushDelta()
                                    emit(stream, retryEvent(chunk))
                                }
                                is AIStreamChunk.KeySwitched -> {
                                    flushDelta()
                                    emit(stream, buildJsonObject {
                                        put("type", "keySwitch")
                                        put("index", chunk.newIndex)
                                        put("total", chunk.total)
                                    })
                                }
                                is AIStreamChunk.Final -> {
                                    flushDelta()
                                    finalResponse = chunk.response
                                }
                            }
                        }
                    flushDelta()
                    val response = finalResponse
                        ?: AIResponse(content = accumulated.toString())
                    finishSuccess(stream, response, System.currentTimeMillis() - startedAt)
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            flushDelta()
            finishCancelled(stream)
            throw e
        } catch (e: Exception) {
            flushDelta()
            finishError(stream, e, accumulated.toString(), secretValues)
        }
    }

    private fun emit(stream: Stream, event: JsonObject) {
        val chars = event.toString().length
        synchronized(stream.bufferLock) {
            if (stream.done) return
            if (stream.events.size >= MAX_BUFFERED_EVENTS || stream.bufferedChars + chars > MAX_BUFFERED_CHARS) {
                stream.done = true
                stream.status = "error"
                stream.doneAt = System.currentTimeMillis()
                stream.error = errorJson(
                    "STREAM_BUFFER_OVERFLOW",
                    "stream buffer overflow; read the stream more frequently"
                )
            } else {
                stream.events.addLast(BufferedEvent(event, chars))
                stream.bufferedChars += chars
            }
        }
        stream.signal.trySend(Unit)
        if (stream.done) stream.job?.cancel()
    }

    private fun takeReadResult(stream: Stream, cursor: Int, maxEvents: Int): JsonObject? {
        synchronized(stream.bufferLock) {
            if (cursor < stream.delivered || cursor > stream.delivered + stream.events.size) {
                throw PluginHostException("CURSOR_INVALID", "cursor is out of range")
            }
            if (stream.events.isEmpty() && !stream.done) return null
            val result = mutableListOf<JsonObject>()
            var chars = 0
            while (result.size < maxEvents && stream.events.isNotEmpty()) {
                val next = stream.events.first()
                if (result.isNotEmpty() && chars + next.chars > MAX_READ_CHARS) break
                stream.events.removeFirst()
                stream.bufferedChars -= next.chars
                stream.delivered += 1
                chars += next.chars
                result += next.event
            }
            return readResultJson(stream, result)
        }
    }

    private fun emptyReadResult(stream: Stream): JsonObject =
        synchronized(stream.bufferLock) { readResultJson(stream, emptyList()) }

    private fun readResultJson(stream: Stream, events: List<JsonObject>): JsonObject = buildJsonObject {
        put("streamId", stream.id)
        put("events", JsonArray(events))
        put("cursor", stream.delivered)
        put("done", stream.done)
        if (stream.done) {
            put("status", stream.status)
            stream.final?.let { put("final", it) }
            stream.error?.let { put("error", it) }
        }
    }

    private fun finishSuccess(stream: Stream, response: AIResponse, durationMs: Long) {
        val final = responseJson(response, durationMs)
        synchronized(stream.bufferLock) {
            if (stream.done) return
            stream.done = true
            stream.status = "success"
            stream.final = final
            stream.doneAt = System.currentTimeMillis()
        }
        stream.signal.trySend(Unit)
    }

    private fun finishCancelled(stream: Stream) {
        synchronized(stream.bufferLock) {
            if (stream.done) return
            stream.done = true
            stream.status = "cancelled"
            stream.doneAt = System.currentTimeMillis()
        }
        stream.signal.trySend(Unit)
    }

    private fun finishError(
        stream: Stream,
        error: Throwable,
        partial: String,
        secretValues: List<String>
    ) {
        val (code, message) = mapError(error)
        val payload = buildJsonObject {
            put("code", code)
            put("message", sanitizeMessage(message, secretValues))
            if (partial.isNotBlank()) {
                put("partialContent", partial.take(MAX_ERROR_PARTIAL_CHARS))
                if (partial.length > MAX_ERROR_PARTIAL_CHARS) put("partialContentTruncated", true)
            }
        }
        synchronized(stream.bufferLock) {
            if (stream.done) return
            stream.done = true
            stream.status = "error"
            stream.error = payload
            stream.doneAt = System.currentTimeMillis()
        }
        stream.signal.trySend(Unit)
    }

    private fun cancelStream(stream: Stream) {
        synchronized(stream.bufferLock) {
            if (!stream.done) {
                stream.done = true
                stream.status = "cancelled"
                stream.doneAt = System.currentTimeMillis()
            }
        }
        stream.signal.trySend(Unit)
        stream.job?.cancel()
    }

    private fun closeHandle(handle: Handle) {
        handles.remove(handle.id, handle)
        streams.values.filter { it.handleId == handle.id }.forEach { cancelStream(it) }
    }

    private fun cleanupStale() {
        val now = System.currentTimeMillis()
        streams.values.filter { it.done && now - it.doneAt > DONE_TTL_MS }
            .forEach { streams.remove(it.id, it) }
        streams.values.filter { !it.done && now - it.lastReadAt > STREAM_IDLE_MS }
            .forEach { cancelStream(it) }
    }

    private fun ensureAccess(pluginId: String): InstalledPlugin {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        if (plugin.content.modelAccess == null) {
            throw PluginHostException(
                "MODEL_ACCESS_NOT_DECLARED",
                "plugin did not declare contributes.modelAccess"
            )
        }
        handles.values
            .filter { it.pluginId == pluginId && it.pluginVersion != plugin.record.version }
            .forEach(::closeHandle)
        return plugin
    }

    private suspend fun requireProvider(providerId: String): AIProviderConfig =
        providerRepository.getProviderById(providerId)
            ?: throw PluginHostException("PROVIDER_NOT_FOUND", "provider not found: $providerId")

    private fun ensureProviderUsable(config: AIProviderConfig) {
        if (!config.isEnabled) {
            throw PluginHostException("PROVIDER_DISABLED", "provider is disabled: ${config.id}")
        }
        if (!config.hasUsableApiKey) {
            throw PluginHostException("PROVIDER_NO_KEY", "provider has no usable key: ${config.id}")
        }
    }

    private fun resolveModel(config: AIProviderConfig, requested: String?): String {
        val model = requested?.trim()?.takeIf { it.isNotEmpty() } ?: config.effectiveModel
        if (model.isBlank()) {
            throw PluginHostException("MODEL_NOT_AVAILABLE", "provider has no model: ${config.id}")
        }
        val available = configuredModels(config)
        if (model !in available) {
            throw PluginHostException(
                "MODEL_NOT_AVAILABLE",
                "model $model is not in provider ${config.id}"
            )
        }
        return model
    }

    private fun configuredModels(config: AIProviderConfig): List<String> =
        (config.models + listOf(config.defaultModel, config.selectedModel))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun ensureChatModel(metadata: ModelMetadata) {
        if (metadata.modelType != ModelMetadata.ModelType.CHAT) {
            throw PluginHostException("MODEL_UNSUPPORTED", "model is not a chat model: ${metadata.id}")
        }
    }

    private fun validateParams(params: ModelParams, metadata: ModelMetadata) {
        params.maxOutputTokens?.let {
            if (it <= 0 || it > MAX_OUTPUT_TOKENS) {
                throw paramInvalid("maxOutputTokens must be in 1..$MAX_OUTPUT_TOKENS")
            }
        }
        if (params.temperature != null && !metadata.supportsCustomTemperature) {
            throw PluginHostException(
                "PARAM_UNSUPPORTED",
                "model does not accept custom temperature: ${metadata.id}"
            )
        }
    }

    private fun mergeParams(base: ModelParams, overrideParams: ModelParams): ModelParams = ModelParams(
        reasoningEffort = overrideParams.reasoningEffort ?: base.reasoningEffort,
        maxOutputTokens = overrideParams.maxOutputTokens ?: base.maxOutputTokens,
        temperature = overrideParams.temperature ?: base.temperature
    )

    private fun enforceHandleLimit(pluginId: String) {
        cleanupStale()
        if (handles.values.count { it.pluginId == pluginId } >= MAX_HANDLES_PER_PLUGIN) {
            throw PluginHostException("HANDLE_LIMIT", "too many model handles for this plugin")
        }
        if (handles.size >= MAX_HANDLES_TOTAL) {
            throw PluginHostException("HANDLE_LIMIT", "too many model handles")
        }
    }

    private fun enforceStreamLimit(pluginId: String) {
        val active = streams.values.count { !it.done }
        if (streams.values.count { !it.done && it.pluginId == pluginId } >= MAX_STREAMS_PER_PLUGIN) {
            throw PluginHostException("STREAM_LIMIT", "too many active model streams for this plugin")
        }
        if (active >= MAX_STREAMS_TOTAL) {
            throw PluginHostException("STREAM_LIMIT", "too many active model streams")
        }
    }

    private fun parseMessages(raw: JsonElement?): List<AgentMessage> {
        val array = raw as? JsonArray
            ?: throw paramInvalid("messages must be an array")
        if (array.isEmpty()) throw paramInvalid("messages must not be empty")
        if (array.size > MAX_MESSAGES) throw paramInvalid("too many messages")
        var totalChars = 0
        return array.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw paramInvalid("messages[$index] must be an object")
            val role = (item["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.trim()?.lowercase(Locale.ROOT)
                ?: throw paramInvalid("messages[$index].role is required")
            val content = (item["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw paramInvalid("messages[$index].content is required")
            if (content.length > MAX_MESSAGE_CHARS) {
                throw paramInvalid("messages[$index].content too long")
            }
            totalChars += content.length
            if (totalChars > MAX_MESSAGES_CHARS) throw paramInvalid("messages too large")
            when (role) {
                "user" -> AgentMessage.UserMessage(content = content)
                "assistant" -> AgentMessage.AssistantMessage(content = content)
                else -> throw paramInvalid("messages[$index].role must be user or assistant")
            }
        }
    }

    private fun AgentMessage.textContent(): String = when (this) {
        is AgentMessage.UserMessage -> content
        is AgentMessage.AssistantMessage -> content
        is AgentMessage.ToolResultMessage -> result
    }

    private fun parseParams(raw: JsonElement?): ModelParams {
        if (raw == null || raw is JsonNull) return ModelParams()
        val obj = raw as? JsonObject ?: throw paramInvalid("params must be an object")
        val reasoningEffort = optionalString(obj, "reasoningEffort")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also { if (it.length > MAX_REASONING_EFFORT_CHARS) throw paramInvalid("reasoningEffort too long") }
        val maxOutputTokens = optionalInt(obj, "maxOutputTokens")
        val temperature = optionalDouble(obj, "temperature")?.also {
            if (it < 0.0 || it > 2.0) throw paramInvalid("temperature must be in 0..2")
        }?.toFloat()
        return ModelParams(
            reasoningEffort = reasoningEffort,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature
        )
    }

    private fun modelJson(metadata: ModelMetadata): JsonObject = buildJsonObject {
        put("id", metadata.id)
        put("displayName", metadata.displayName)
        put("contextTokens", metadata.contextTokens)
        put("inputTokens", metadata.inputTokens)
        put("outputTokens", metadata.outputTokens)
        put("supportsTools", metadata.supportsTools)
        put("supportsVision", metadata.supportsVision)
        put("supportsReasoning", metadata.supportsReasoning)
        put("supportsCustomTemperature", metadata.supportsCustomTemperature)
        put("supportsImageOutput", metadata.supportsImageOutput)
        put("modelType", metadata.modelType.name.lowercase(Locale.ROOT))
        metadata.reasoningLevels?.takeIf { it.isNotEmpty() }?.let { levels ->
            put("reasoningLevels", JsonArray(levels.map { JsonPrimitive(it) }))
        }
        metadata.reasoningDefaultLevel?.let { put("reasoningDefaultLevel", it) }
        metadata.inputCostUsdPerM?.let { put("inputCostUsdPerM", it) }
        metadata.outputCostUsdPerM?.let { put("outputCostUsdPerM", it) }
        metadata.cacheReadCostUsdPerM?.let { put("cacheReadCostUsdPerM", it) }
        metadata.cacheWriteCostUsdPerM?.let { put("cacheWriteCostUsdPerM", it) }
        put("source", metadata.source.name.lowercase(Locale.ROOT))
    }

    private fun handleJson(handle: Handle): JsonObject = buildJsonObject {
        put("id", handle.id)
        put("providerId", handle.providerId)
        put("model", handle.model)
        handle.name?.let { put("name", it) }
        put("params", paramsJson(handle.params))
        put("createdAt", handle.createdAt)
    }

    private fun paramsJson(params: ModelParams): JsonObject = buildJsonObject {
        params.reasoningEffort?.let { put("reasoningEffort", it) }
        params.maxOutputTokens?.let { put("maxOutputTokens", it) }
        params.temperature?.let { put("temperature", it) }
    }

    private fun textEvent(type: String, text: String): JsonObject = buildJsonObject {
        put("type", type)
        put("text", text)
    }

    private fun retryEvent(chunk: AIStreamChunk.Retrying): JsonObject = buildJsonObject {
        put("type", "retry")
        put("attempt", chunk.attempt)
        put("maxRetries", chunk.maxRetries)
        put("error", buildJsonObject {
            put("code", chunk.error.kind.name.lowercase(Locale.ROOT))
            chunk.error.statusCode?.let { put("statusCode", it) }
        })
    }

    private fun responseJson(response: AIResponse, durationMs: Long): JsonObject = buildJsonObject {
        val content = response.content
        put("content", content.take(MAX_FINAL_CONTENT_CHARS))
        if (content.length > MAX_FINAL_CONTENT_CHARS) put("contentTruncated", true)
        response.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
            put("reasoning", reasoning.take(MAX_FINAL_REASONING_CHARS))
            if (reasoning.length > MAX_FINAL_REASONING_CHARS) put("reasoningTruncated", true)
        }
        if (response.toolCalls.isNotEmpty()) {
            put("toolCalls", JsonArray(response.toolCalls.take(MAX_FINAL_TOOL_CALLS).map { call ->
                buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    val arguments = JsonObject(call.arguments)
                    val encoded = arguments.toString()
                    if (encoded.length <= MAX_TOOL_ARGS_CHARS) {
                        put("arguments", arguments)
                    } else {
                        put("arguments", encoded.take(MAX_TOOL_ARGS_CHARS))
                        put("argumentsTruncated", true)
                    }
                }
            }))
            if (response.toolCalls.size > MAX_FINAL_TOOL_CALLS) put("toolCallsTruncated", true)
        }
        response.stopReason?.let { put("stopReason", it) }
        response.stopDetail?.let { put("stopDetail", it.take(MAX_STOP_DETAIL_CHARS)) }
        if (response.images.isNotEmpty()) put("imageCount", response.images.size)
        put("usage", buildJsonObject {
            put("input", response.inputTokens)
            put("output", response.outputTokens)
            put("cachedInput", response.cachedInputTokens)
            put("cacheCreation", response.cacheCreationTokens)
        })
        put("durationMs", durationMs)
    }

    private fun mapError(error: Throwable): Pair<String, String> = when (error) {
        is PluginHostException -> error.code to error.message
        is AllKeysFailedException -> "ALL_KEYS_FAILED" to (error.message ?: "all keys failed")
        is StreamApiException -> "MODEL_ERROR" to (error.message ?: "model request failed")
        else -> "MODEL_ERROR" to (error.message ?: error.javaClass.simpleName)
    }

    private fun sanitizeMessage(message: String, secretValues: List<String>): String {
        var result = message
        secretValues.filter { it.isNotBlank() }.forEach { secret ->
            result = result.replace(secret, "***")
        }
        result = result.filterNot { it.isISOControl() && it != '\n' }
        return result.take(MAX_ERROR_MESSAGE_CHARS)
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message.take(MAX_ERROR_MESSAGE_CHARS))
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw paramInvalid("$key must be a string")
    }

    private fun optionalInt(params: JsonObject, key: String): Int? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        val value = (raw as? JsonPrimitive)?.longOrNull
            ?: throw paramInvalid("$key must be a number")
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw paramInvalid("$key is out of range")
        }
        return value.toInt()
    }

    private fun optionalLong(params: JsonObject, key: String): Long? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.longOrNull
            ?: throw paramInvalid("$key must be a number")
    }

    private fun optionalDouble(params: JsonObject, key: String): Double? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.doubleOrNull
            ?: throw paramInvalid("$key must be a number")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val MAX_MODELS = 500
        const val MAX_MESSAGES = 200
        const val MAX_MESSAGE_CHARS = 64 * 1024
        const val MAX_MESSAGES_CHARS = 256 * 1024
        const val MAX_SYSTEM_PROMPT_CHARS = 64 * 1024
        const val MAX_HANDLE_NAME_CHARS = 64
        const val MAX_REASONING_EFFORT_CHARS = 32
        const val MAX_OUTPUT_TOKENS = 65_536
        const val MAX_HANDLES_PER_PLUGIN = 16
        const val MAX_HANDLES_TOTAL = 128
        const val MAX_STREAMS_PER_PLUGIN = 8
        const val MAX_STREAMS_TOTAL = 32

        const val DEFAULT_READ_EVENTS = 100
        const val MAX_READ_EVENTS = 500
        const val MIN_READ_WAIT_MS = 0L
        const val DEFAULT_READ_WAIT_MS = 10_000L
        const val MAX_READ_WAIT_MS = 20_000L
        const val MAX_READ_CHARS = 256 * 1024

        const val MAX_TEXT_EVENT_CHARS = 16 * 1024
        const val DELTA_FLUSH_INTERVAL_MS = 50L
        const val MAX_BUFFERED_EVENTS = 1_024
        const val MAX_BUFFERED_CHARS = 2L * 1024 * 1024
        const val MAX_FINAL_CONTENT_CHARS = 256 * 1024
        const val MAX_FINAL_REASONING_CHARS = 64 * 1024
        const val MAX_FINAL_TOOL_CALLS = 20
        const val MAX_TOOL_ARGS_CHARS = 4_000
        const val MAX_STOP_DETAIL_CHARS = 4_000
        const val MAX_ERROR_MESSAGE_CHARS = 4_000
        const val MAX_ERROR_PARTIAL_CHARS = 32 * 1024

        const val STREAM_IDLE_MS = 5 * 60_000L
        const val DONE_TTL_MS = 5 * 60_000L
        const val CLEANUP_INTERVAL_MS = 60_000L
    }
}
