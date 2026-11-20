package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.defaultProviderApiPath
import java.io.IOException
import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.OpenAIChatMessage
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.DisableFieldKind
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.model.ReasoningWire
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.aicode.feature.agent.data.remote.openai.OpenAIToolCall
import com.aicode.feature.agent.data.remote.openai.OpenAIToolDefinition
import com.aicode.feature.agent.data.remote.openai.OpenAIFunctionDefinition
import com.aicode.feature.agent.data.remote.openai.OpenAIImagePart
import com.aicode.feature.agent.data.remote.openai.StreamOptions

class OpenAIAdapter @Inject constructor(
    private val api: OpenAIApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.openai.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gpt-4-turbo"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /**
     * 是否在 Chat Completion 路径发送 `prompt_cache_key`（缓存 shard 路由）。
     * 默认关闭——OpenAI 官方 Chat Completion 不接受未知字段（400）；仅第三方兼容服务（DeepInfra/Cerebras 等）支持。
     * Responses API 路径官方原生支持，无条件发送，不受本开关影响。
     */
    var chatCacheKeyEnabled: Boolean = false

    /** 自定义请求头：占位符替换后写出，完全覆盖同名默认头。 */
    override var customHeaders: Map<String, String> = emptyMap()

    // OpenAI 系不发输出上限参数（用服务端默认），仅为接口完整性保留。
    override var maxOutputTokens: Int? = null

    /** 当前模型的思考能力，由工作流按元数据注入；null 时由 [ReasoningResolver] 按模型名兜底。 */
    override var reasoningCapability: ReasoningCapability? = null

    /** 服务端 400 学到的档位优先于元数据：它反映的是该渠道真实接受的白名单。 */
    private fun effectiveReasoningCapability(): ReasoningCapability? =
        ReasoningLearning.recall(providerId, model) ?: reasoningCapability

    // null 时请求体不带 temperature（Gson 跳过 null 字段），交由服务端默认。
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * 目标端点：Responses API 开启时用 `v1/responses`，否则用 chat/completions 路径（[defaultProviderApiPath]）。
     * useResponseApi 时请求体是 Responses 格式（`input`），若仍打到 chat/completions 会被服务端以
     * 「missing field `messages`」拒绝，故必须同步切端点。useFullUrl 时直接用用户填的完整 baseUrl。
     */
    private fun resolveApiUrl(): String {
        if (useFullUrl) return baseUrl
        val path = if (useResponseApi) "v1/responses" else defaultProviderApiPath(ProviderType.OPENAI)
        return joinUrl(baseUrl, path)
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) return completeViaResponses(systemPrompt, messages, tools, reasoningEffort)

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val reasoningPlan = chatReasoningPlan(reasoningEffort)
        val disableToggle = reasoningPlan.wire as? ReasoningWire.DisableToggle
        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = (reasoningPlan.wire as? ReasoningWire.Effort)?.value,
            thinking = if (disableToggle?.field == DisableFieldKind.THINKING_TYPE) mapOf("type" to "disabled") else null,
            enable_thinking = if (disableToggle?.field == DisableFieldKind.ENABLE_THINKING) false else null,
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = false,
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", resolveApiUrl(), request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                // 地址按当次取用的 Key 现算：切 Key 可能带出该 Key 自己的 Base URL 覆盖。
                api.createChatCompletion(url = resolveApiUrl(), authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val message = response.choices.firstOrNull()?.message
        val finishReason = response.choices.firstOrNull()?.finish_reason
        val content = message?.content.asTextContent()
        val images = message?.images?.mapNotNull { it.toAgentImage() } ?: emptyList()
        val toolCalls = message?.tool_calls?.map { convertToToolCall(it) } ?: emptyList()
        val reasoning = message?.reasoning_content?.takeIf { it.isNotEmpty() }
            ?: message?.reasoning?.takeIf { it.isNotEmpty() }
        val usage = response.usage

        return AIResponse(content = content, toolCalls = toolCalls, stopReason = finishReason, reasoning = reasoning, inputTokens = usage?.prompt_tokens ?: 0, outputTokens = usage?.completion_tokens ?: 0, cachedInputTokens = usage?.prompt_tokens_details?.cached_tokens ?: 0, cacheCreationTokens = usage?.prompt_tokens_details?.cache_write_tokens ?: 0, images = images)
    }

    /**
     * o 系列推理模型不接受 system role，要求改用 developer。
     * 按前缀判定：o1/o3/o4 及后续 o 系列（o5、o6…），以及 gpt-5 系（官方归入同一「推理模型」角色约定），
     * 一律用 developer。gpt-4 及更早、以及第三方兼容服务（deepseek/kimi/minimax/moonshot 等）不命中
     * 前缀，继续走 system，不受影响。
     */
    private fun systemRoleForModel(): String = when {
        model.startsWith("o", ignoreCase = true) -> "developer"
        model.startsWith("gpt-5", ignoreCase = true) -> "developer"
        else -> "system"
    }

    /**
     * 思考强度 → 请求字段。档位是否支持、能否关闭、该降级到哪一档全部交给 [ReasoningResolver]：
     * - 支持 max/xhigh 的模型（gpt-5.6、gpt-6、Grok 4.6+ 等）如实透传，不再被压成 high；
     * - 模型确实不支持的低档位向下取最近档，未知模型按保守集合发送；
     * - 关闭思考：厂商私有开关走 `thinking` / `enable_thinking`；
     *   OpenAI 官方在 chat/completions 上只能发 `reasoning_effort="none"`（实测部分服务会当 low 处理，
     *   真正归零需切到 Responses 端点）。
     */
    private fun chatReasoningPlan(effort: String?): ReasoningPlan =
        ReasoningResolver.plan(ReasoningProtocol.OPENAI_CHAT, model, effort, effectiveReasoningCapability())

    private fun responsesReasoningPlan(effort: String?): ReasoningPlan =
        ReasoningResolver.plan(ReasoningProtocol.OPENAI_RESPONSES, model, effort, effectiveReasoningCapability())

    /**
     * Responses 请求体。system prompt 作为首个 message item 进 `input`（与顶层 `instructions` 等价，
     * 服务端同样按首条 system 消息处理），因此不再单独发 `instructions`。
     */
    private fun buildResponsesRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val isDeepSeek = model.contains("deepseek", ignoreCase = true)
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            // 官方默认 store=true，会把整份代码上下文留在服务端 ≥30 天；本地 messages 表才是唯一事实源。
            "store" to false,
            "input" to buildResponsesInput(
                systemPrompt = systemPrompt,
                systemRole = systemRoleForModel(),
                messages = messages,
                // DeepSeek 思考模式：带 tools 的请求必须把历史每轮的思考内容回传（同 Chat
                // Completions 那边的 reasoning_content）；官方/通用路径在存有合法快照时也会自动回传。
                includeReasoningItems = isDeepSeek
            )
        )
        buildResponsesTools(tools)?.let {
            request["tools"] = it
            request["tool_choice"] = "auto"
        }
        if (stream) request["stream"] = true
        // Responses 端点的思考字段：effort 走 reasoning.effort；厂商私有开关走 thinking / enable_thinking。
        when (val wire = responsesReasoningPlan(reasoningEffort).wire) {
            is ReasoningWire.Effort -> {
                if (isDeepSeek) {
                    request["reasoning"] = mapOf("effort" to wire.value)
                } else {
                    // OpenAI 官方/通用 Responses 规范：显式开启思考总结（summary: "auto"），
                    // 并索取 encrypted_content 密文，供无状态多轮对话连续推理。
                    request["reasoning"] = mapOf("effort" to wire.value, "summary" to "auto")
                    request["include"] = listOf("reasoning.encrypted_content")
                }
            }
            // OpenAI 官方关闭思考的唯一可靠写法（chat/completions 上 none 常被降成 low）。
            ReasoningWire.DisableEffortNone -> request["reasoning"] = mapOf("effort" to "none")
            is ReasoningWire.DisableToggle -> when (wire.field) {
                DisableFieldKind.THINKING_TYPE -> {
                    request["thinking"] = mapOf("type" to "disabled")
                    // 少数厂商关闭时仍需带档位（Anthropic 系），OpenAI 兼容侧一般为空。
                    effectiveReasoningCapability()?.effortWhileDisabled?.let {
                        request["reasoning"] = mapOf("effort" to it.apiValue)
                    }                }
                DisableFieldKind.ENABLE_THINKING -> request["enable_thinking"] = false
            }
            ReasoningWire.BetweenTools -> request["thinking"] = mapOf("type" to "between_tools")
            // 预算型与「不发」在这条端点上没有对应字段，交给服务端默认。
            is ReasoningWire.Budget, ReasoningWire.Omit -> Unit
        }
        // OpenAI 官方 Responses 原生支持 prompt_cache_key（同会话路由到同一缓存 shard）；
        // 自动管理缓存的服务（如 DeepSeek）会静默忽略该字段，不会报错。
        logSessionId?.let { request["prompt_cache_key"] = it }
        return request
    }

    /**
     * Responses API 的非流式请求。与 Chat Completions 的差异集中在三处：工具定义字段扁平、
     * 历史是 input item 序列（见 [buildResponsesInput]）、结果从 `output` 的顶层 item 解析
     * （工具调用是独立的 function_call item，不在 assistant 消息的 content 里）。
     */
    private suspend fun completeViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", resolveApiUrl(), request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createResponses(url = resolveApiUrl(), authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val parsed = parseResponsesOutput(response.get("output")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseResponsesUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        val incompleteReason = response.get("incomplete_details")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = responsesStopReason(status, incompleteReason, parsed.toolCalls.isNotEmpty()),
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.thinkingBlocksJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            cacheCreationTokens = usage.cacheCreationTokens,
            images = parsed.images
        )
    }

    // 必须用 channelFlow + send（而非 flow + emit）：首字节超时靠取消 attempt 作用域实现，
    // 取消信号可能落在挂起的 emit 上；flow{} 里恢复后再次 emit（如 Retrying/KeySwitched）会触发
    // kotlinx.coroutines 的 Flow 异常透明性违规（IllegalStateException），channel 的 send 不受该检查约束。
    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = channelFlow {
        if (useResponseApi) {
            streamViaResponses(systemPrompt, messages, tools, reasoningEffort)
            return@channelFlow
        }

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val streamReasoningPlan = chatReasoningPlan(reasoningEffort)
        val streamDisableToggle = streamReasoningPlan.wire as? ReasoningWire.DisableToggle
        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = (streamReasoningPlan.wire as? ReasoningWire.Effort)?.value,
            thinking = if (streamDisableToggle?.field == DisableFieldKind.THINKING_TYPE) mapOf("type" to "disabled") else null,
            enable_thinking = if (streamDisableToggle?.field == DisableFieldKind.ENABLE_THINKING) false else null,
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = true,
            stream_options = StreamOptions(include_usage = true),
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", resolveApiUrl(), request)
        // 原始 SSE 分批落盘（见 AILogger.beginRawSse），本轮结束在 finally 收尾。
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)

        // 流式请求整体可重试；重试前上层会收到 Retrying 事件并清空已展示文本。
        try {
            val triedKeys = mutableSetOf(apiKey)
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        send(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
                    withFirstContentTimeout(firstByteTimeoutMs) { firstContent ->
            val textBuilder = StringBuilder()
            val budget = StreamBudget()
            // tool_call index -> 累积中的工具调用（保序）。
            val toolAccs = LinkedHashMap<Int, OpenAIToolAcc>()
            // 生图扩展：部分兼容服务在 delta.images 里整块返回图片（image_url 的 data URL）。
            val streamedImages = mutableListOf<AgentImage>()
            var finishReason: String? = null
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0

            val body = firstContent.awaitBody(api.streamChatCompletion(
                url = resolveApiUrl(),
                authorization = "Bearer $apiKey",
                extraHeaders = extraHeaders(),
                request = request
            ))

            body.use { rb ->
                firstContent.attach { rb.close() }
                val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val reader = rb.charStream().buffered()
                    // 收到服务端 [DONE] 标记即 break 正常结束；readLine() 返回 null 则视为
                    // 流被异常截断（网络中断/TCP 重置/readTimeout），必须抛异常让重试/日志接管——
                    // 否则原本会用截断数据「正常完成」，表现为 AI 突然中断且无任何错误日志。
                    // （收到 [DONE] 即 break，故走到 readLine()==null 时必然未收到过结束标记。）
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                            ?: throw IOException("SSE 流被中断：未收到 [DONE] 结束标记（疑似网络断开）")
                        idleWatchdog.touch()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        sseLog.append(line)
                        if (data == "[DONE]") break
                        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                        obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                            val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                            val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "未知错误"
                            throw StreamApiException(code, msg)
                        }
                        // 单行 SSE 解析：不同上游/模型的字段类型偶有出入（如把对象写成数组、把字符串写成对象），
                        // Gson 的 getAsJsonObject/getAsJsonArray 在类型不符时会直接抛 ClassCastException，
                        // asString/asInt 对非原始值会抛 UnsupportedOperationException。
                        // 单行异常不应中断整条流——这里宽松解析，出错仅跳过该行；已累积的文本与后续行不受影响。
                        // 必须放行 CancellationException，否则会吞掉协程取消信号。
                        try {
                            obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                                streamInputTokens = u.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                streamOutputTokens = u.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamOutputTokens
                                streamCachedInputTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                streamCacheCreationTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cache_write_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCacheCreationTokens
                            }
                            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                            val delta = choice.getAsJsonObject("delta") ?: continue

                            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                finishReason = it
                            }

                            val remoteMessageId = delta.get("aicode_message_id")
                                ?.takeIf {
                                    com.aicode.feature.plugin.runtime.PluginProviderIds.isPluginId(providerId) &&
                                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                                }?.asString
                            if (remoteMessageId != null) send(AIStreamChunk.TextDelta("", remoteMessageId))
                            // 文字增量
                            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { c ->
                                if (c.isNotEmpty()) {
                                    budget.add(c)
                                    textBuilder.append(c)
                                    firstContent.receivedContent()
                                    onContent()
                                    send(AIStreamChunk.TextDelta(c))
                                }
                            }
                            // 思考过程增量：标准 OpenAI 走 reasoning_content，部分第三方兼容服务
                            // （如 mimo）走顶层 reasoning 或 reasoning_details（reasoning.text 数组）。
                            // 仅 UI 实时展示，不计入正文（思考不落库，重试时重新流出即可，无重复文本风险），
                            // 但收到即说明连接已活，取消首字节超时。
                            val reasoningText = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning_details")?.takeIf { it.isJsonArray }?.asJsonArray
                                    ?.joinToString("") { el ->
                                        el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                                    }
                            if (!reasoningText.isNullOrEmpty()) {
                                budget.add(reasoningText)
                                firstContent.receivedContent()
                                onContent()
                                send(AIStreamChunk.ReasoningDelta(reasoningText))
                            }
                            // 工具调用增量：按 index 聚合 id/name/arguments 片段。
                            // 有些模型（如 DeepSeek）在后续增量 chunk 中只传 arguments 片段，
                            // id 和 name 为空字符串 ""，不应覆盖已收到的有效值——否则首次 chunk
                            // 收到的完整 id/name 会被后续空值清空，导致 ToolCall 丢失。
                            delta.getAsJsonArray("tool_calls")?.takeIf { it.size() > 0 }?.let {
                                firstContent.receivedContent()
                                onContent()
                            }
                            delta.getAsJsonArray("tool_calls")?.forEach { el ->
                                val tc = el.asJsonObject
                                val idx = tc.get("index")?.asInt ?: 0
                                val acc = toolAccs.getOrPut(idx) { OpenAIToolAcc() }
                                // 仅在 id/name 非空时更新，避免增量 chunk 的空值覆盖首 chunk 的有效值
                                tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                tc.getAsJsonObject("function")?.let { fn ->
                                    fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { name ->
                                        // 首次拿到工具名（后续增量只给 arguments 片段）：通知 UI
                                        // 「模型准备调什么」，免得长参数流式期间一直停在「正在思考」
                                        if (acc.name.isEmpty()) send(AIStreamChunk.ToolCallDeclared(name))
                                        acc.name = name
                                    }
                                    fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { budget.add(it); acc.args.append(it) }
                                }
                            }
                            // 生图扩展：图片整块到达、无增量，累积待 Final 交工作流落盘。
                            delta.getAsJsonArray("images")?.forEach { el ->
                                val img = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                                img.toOpenAIAgentImage()?.let { image ->
                                    streamedImages.add(image)
                                    firstContent.receivedContent()
                                    onContent()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: StreamApiException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // 该行 SSE 解析失败，跳过；不影响已累积文本与后续行。
                        }
                    }
                } finally {
                    idleWatchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolAccs.values
                .filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                .map { acc -> ToolCall(id = acc.id, name = acc.name, arguments = parseToolArguments(acc.args.toString())) }
            send(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = finishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens, images = streamedImages)))
                    }
            },
            onRetry = { attempt, max, error -> send(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        } finally {
            // 无论成功/失败/取消，把已收到的原始 SSE 落盘（重试时会从上次中断处续写）。
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Responses API 的流式请求。与 Chat Completions 的 delta 帧不同，这里是语义事件流（见
     * [ResponsesStreamAccumulator]），且**官方不发 `[DONE]`**：结束只能按 response.completed /
     * incomplete / failed 判定。沿用旧的“没收到 [DONE] 就报断流”会把正常结束当成错误，
     * 故这里以 [ResponsesStreamAccumulator.terminated] 作为退出条件，读到流尾仍未终止才算被截断。
     */
    private suspend fun ProducerScope<AIStreamChunk>.streamViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", resolveApiUrl(), request)
        // 原始 SSE 分批落盘（见 AILogger.beginRawSse），本轮结束在 finally 收尾。
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)
        try {
            val triedKeys = mutableSetOf(apiKey)
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        send(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
                    withFirstContentTimeout(firstByteTimeoutMs) { firstContent ->
                    val acc = ResponsesStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamResponses(
                        url = resolveApiUrl(),
                        authorization = "Bearer $apiKey",
                        extraHeaders = extraHeaders(),
                        request = request
                    ))

                    body.use { rb ->
                        firstContent.attach { rb.close() }
                        val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                        val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                            runCatching { rb.close() }
                        }
                        try {
                            val reader = rb.charStream().buffered()
                            while (!acc.terminated) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine()
                                    ?: throw IOException("SSE 流被中断：未收到 response.completed 结束事件（疑似网络断开）")
                                idleWatchdog.touch()
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                sseLog.append(line)
                                // 官方 Responses 不发 [DONE]，但部分兼容服务会补发，收到即视为流结束。
                                if (data == "[DONE]") break
                                val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                                obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                                    val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "未知错误"
                                    throw StreamApiException(code, msg)
                                }
                                // 单个事件的字段类型异常不应废掉整条流，只跳过该事件；
                                // 但 StreamApiException（response.failed）与取消信号必须放行。
                                val delta = try {
                                    acc.accept(obj).also {
                                        if (acc.receivedContent) {
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                    }
                                } catch (e: StreamApiException) {
                                    throw e
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    coroutineContext.ensureActive()
                                    null
                                }
                                when (delta) {
                                    is ResponsesDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        send(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // 思考增量仅用于 UI 展示，不计入正文；收到即说明连接已活，取消首字节超时。
                                    is ResponsesDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        send(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // 工具名先于参数到达：通知 UI 提前把状态换成具体场景
                                    is ResponsesDelta.ToolCallDeclared -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        send(AIStreamChunk.ToolCallDeclared(delta.name))
                                    }
                                    null -> {}
                                }
                            }
                        } finally {
                            idleWatchdog.cancel()
                            closeHandle?.dispose()
                        }
                    }

                    send(AIStreamChunk.Final(acc.toResponse()))
                    }
                },
                onRetry = { attempt, max, error -> send(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /** 流式过程中按 index 累积的工具调用状态。 */
    private class OpenAIToolAcc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private fun convertToOpenAIMessages(messages: List<AgentMessage>): MutableList<OpenAIChatMessage> {
        val raw = messages.map { message ->
            when (message) {
                is AgentMessage.UserMessage -> OpenAIChatMessage(
                    role = "user",
                    content = message.toOpenAIUserContent()
                )
                is AgentMessage.AssistantMessage -> {
                    val toolCalls = if (message.toolCalls.isNotEmpty()) {
                        message.toolCalls.map { convertToOpenAIToolCall(it) }
                    } else null
                    // DeepSeek 思考模式要求 assistant 消息的 reasoning_content 字段必须存在
                    // （即使是空串也要带上），否则工具调用轮回传时 API 报 400。
                    val reasoningContent = if (model.contains("deepseek", ignoreCase = true)) {
                        message.reasoning
                    } else {
                        message.reasoning.ifEmpty { null }
                    }
                    OpenAIChatMessage(
                        role = "assistant",
                        content = message.content,
                        tool_calls = toolCalls,
                        reasoning_content = reasoningContent
                    )
                }
                is AgentMessage.ToolResultMessage -> {
                    // 文件类工具喂模型用精简投影文本（减少完整 diff 回传的 token），UI/持久化仍走完整 result。
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val parts = mutableListOf<Map<String, Any>>()
                        if (modelText.isNotBlank()) {
                            parts.add(mapOf("type" to "text", "text" to modelText))
                        }
                        message.images.forEach { image ->
                            parts.add(image.toOpenAIImagePart())
                        }
                        parts
                    } else {
                        modelText
                    }
                    OpenAIChatMessage(
                        role = "tool",
                        content = content,
                        tool_call_id = message.id
                    )
                }
            }
        }

        // 防御性清理：保证 assistant(tool_calls) 与其 tool 响应消息按 tool_call_id 一一配对并紧跟，
        // 避免上游 400。可能破坏配对的场景：
        // 1) 并发/异步工具结果乱序落位（如 askUserQuestion 阻塞等待期间其他工具结果插队），
        //    assistant(tool_calls) 与其响应被其他消息隔开 → 将匹配的 tool 响应吸附回紧跟其后；
        // 2) 孤立 tool 消息（前驱 assistant 无 tool_calls，如上下文压缩导致配对断裂）→ 跳过，
        //    否则 OpenAI 报 "Messages with role 'tool' must be a response to a preceding
        //    message with 'tool_calls'"；
        // 3) assistant 声明的 tool_calls 无对应响应（如用户拒绝导致部分调用未执行）→ 裁剪，
        //    否则 OpenAI 报 "insufficient tool messages following tool_calls message"。
        val cleaned = mutableListOf<OpenAIChatMessage>()
        val consumed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (consumed[i]) continue
            val msg = raw[i]
            if (msg.role == "assistant" && msg.tool_calls?.isNotEmpty() == true) {
                val remaining = msg.tool_calls!!.map { it.id }.toMutableSet()
                val matchedTools = mutableListOf<OpenAIChatMessage>()
                for (j in i + 1 until raw.size) {
                    if (consumed[j]) continue
                    val m = raw[j]
                    if (m.role == "tool" && m.tool_call_id != null && m.tool_call_id in remaining) {
                        matchedTools.add(m)
                        consumed[j] = true
                        remaining.remove(m.tool_call_id)
                        if (remaining.isEmpty()) break
                    }
                }
                val keptCalls = if (remaining.isEmpty()) msg.tool_calls
                else msg.tool_calls!!.filter { it.id !in remaining }
                cleaned.add(if (keptCalls === msg.tool_calls) msg else msg.copy(tool_calls = keptCalls.ifEmpty { null }))
                cleaned.addAll(matchedTools)
            } else if (msg.role == "tool") {
                consumed[i] = true // 孤立 tool 消息，跳过
            } else {
                cleaned.add(msg)
            }
        }
        return cleaned
    }

    private fun AgentMessage.UserMessage.toOpenAIUserContent(): Any {
        if (images.isEmpty()) return content

        val parts = mutableListOf<Map<String, Any>>()
        if (content.isNotBlank()) {
            parts.add(mapOf("type" to "text", "text" to content))
        }
        images.forEach { image ->
            parts.add(image.toOpenAIImagePart())
        }
        return parts
    }

    private fun AgentImage.toOpenAIImagePart(): Map<String, Any> = mapOf(
        "type" to "image_url",
        "image_url" to mapOf(
            "url" to "data:$mimeType;base64,$base64Data",
            "detail" to "auto"
        )
    )

    /** `data:` URL（base64 内嵌）→ AgentImage；远程 URL / 非 base64 / 非法一律返回 null。 */
    private fun dataUrlToAgentImage(url: String): AgentImage? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        if (data.isBlank()) return null
        return AgentImage(mimeType = mime, base64Data = data)
    }

    /** 流式 delta.images 里的单个元素 → AgentImage。 */
    private fun com.google.gson.JsonObject.toOpenAIAgentImage(): AgentImage? {
        val url = when (val img = get("image_url")) {
            is com.google.gson.JsonPrimitive -> img.asString
            is com.google.gson.JsonObject -> img.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        } ?: return null
        return dataUrlToAgentImage(url)
    }

    /** 非流式 message.images 的单个图片项 → AgentImage。 */
    private fun OpenAIImagePart.toAgentImage(): AgentImage? {
        val url = image_url?.url ?: return null
        return dataUrlToAgentImage(url)
    }

    /**
     * OpenAI chat completion 返回的 content 可能是字符串或数组（多模态/生图模型）。
     * 数组元素里可能含 image_url 的 base64 data URL（几 MB），直接 toString() 会把整段 base64
     * 当成 assistant 文本落库，撑爆 SQLite CursorWindow 导致启动崩溃。这里只提取文本部分，
     * 图片只保留说明/远程 URL 引用，绝不把 base64 写进 content。
     */
    private fun Any?.asTextContent(): String = when (this) {
        null -> ""
        is String -> this
        is List<*> -> extractTextFromContentParts(this)
        else -> toString()
    }

    private fun extractTextFromContentParts(parts: List<*>): String {
        val text = StringBuilder()
        for (part in parts) {
            when (part) {
                is Map<*, *> -> {
                    when (val type = part["type"] as? String) {
                        "text", "input_text", "output_text" -> {
                            (part["text"] as? String)?.let { text.append(it) }
                        }
                        "image_url" -> {
                            val url = when (val img = part["image_url"]) {
                                is String -> img
                                is Map<*, *> -> img["url"] as? String
                                else -> null
                            }
                            if (url != null && url.startsWith("data:image", ignoreCase = true)) {
                                text.append("\n[图片已省略：内嵌图片数据过大]")
                            } else if (!url.isNullOrBlank()) {
                                text.append("\n[图片：").append(url).append("]")
                            }
                        }
                        "input_image" -> {
                            text.append("\n[图片已省略：内嵌图片数据过大]")
                        }
                        else -> {}
                    }
                }
                is String -> text.append(part)
                else -> {}
            }
        }
        return text.toString()
    }

    private fun convertToToolCall(openAIToolCall: OpenAIToolCall): ToolCall {
        val argumentsJson = runCatching {
            Json.parseToJsonElement(openAIToolCall.function.arguments).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        return ToolCall(
            id = openAIToolCall.id,
            name = openAIToolCall.function.name,
            arguments = argumentsJson
        )
    }

    private fun convertToOpenAIToolCall(toolCall: ToolCall): OpenAIToolCall {
        return OpenAIToolCall(
            id = toolCall.id,
            type = "function",
            function = com.aicode.feature.agent.data.remote.openai.OpenAIFunctionCall(
                name = toolCall.name,
                arguments = JsonObject(toolCall.arguments).toString()
            )
        )
    }
}
