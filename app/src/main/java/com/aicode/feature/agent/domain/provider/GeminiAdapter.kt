package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningWire
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import com.google.gson.JsonObject
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
import kotlinx.serialization.json.jsonObject
import java.io.IOException

class GeminiAdapter @Inject constructor(
    private val api: GeminiApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://generativelanguage.googleapis.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gemini-1.5-flash"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** 自定义请求头：占位符替换后写出，完全覆盖同名默认头。 */
    override var customHeaders: Map<String, String> = emptyMap()

    // Gemini 发 generationConfig.maxOutputTokens（模型元数据的输出上限）；为 null 时不发该参数，用服务端默认。
    override var maxOutputTokens: Int? = null

    /** 当前模型的思考能力，由工作流按元数据注入；null 时由 [ReasoningResolver] 按模型名兜底。 */
    override var reasoningCapability: ReasoningCapability? = null

    /** 服务端 400 学到的档位优先于元数据：它反映的是该渠道真实接受的白名单。 */
    private fun effectiveReasoningCapability(): ReasoningCapability? =
        ReasoningLearning.recall(providerId, model) ?: reasoningCapability

    // 原生 generateContent 不发 generationConfig.temperature：Gemini 2.0 起服务端默认就是 1.0，与推荐值一致。
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * Interactions API 的目标端点。与 generateContent 的两点不同：
     * 模型名在请求体里（故没有「baseUrl 末尾是模型名」那种特例），流式与非流式**同一个端点**，
     * 靠 `?alt=sse` + 请求体 `stream: true` 切换。useFullUrl 时直接用用户填的完整 baseUrl。
     */
    private fun resolveInteractionsUrl(stream: Boolean): String {
        if (useFullUrl) return baseUrl
        val url = joinUrl(baseUrl, "v1beta/interactions")
        return if (stream) "$url?alt=sse" else url
    }

    /**
     * 原生 generateContent 端点。用户可能把 baseUrl 直接填到模型名（如 `.../models/gemini-2.5-pro`），
     * 此时只追加操作名，不再拼 `v1beta/models/{model}`。
     * 按当前 [baseUrl] 现算：多 Key 模式下每条 Key 可带自己的地址，切 Key 后地址会变。
     */
    private fun resolveGenerateContentUrl(stream: Boolean): String {
        if (useFullUrl) return baseUrl
        val operation = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        return if (baseUrl.trimEnd('/').endsWith(model)) {
            baseUrl.trimEnd('/') + operation
        } else {
            joinUrl(baseUrl, "v1beta/models/$model$operation")
        }
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) {
            return completeViaInteractions(systemPrompt, messages, tools, reasoningEffort)
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = resolveGenerateContentUrl(stream = false)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.generateContent(url = resolveGenerateContentUrl(stream = false), apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        var contentText = ""
        var thinkingText = ""
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        var finishReason: String? = null
        var partsSnapshot: String? = null

        // 非流式响应的字段类型偶有出入（内容安全拦截时 candidates 缺失或为空、finishReason/text 可能为
        // JSON null），Gson 的 getAsJsonArray/getAsJsonObject/asString 对这些情况会直接抛异常。
        // 与流式路径（:208-218）对齐：每个字段用 takeIf { !it.isJsonNull } 守卫，整段 runCatching 兜底，
        // 坏响应返回带 stopReason 的明确结果交由上层处理，而非把解析异常抛给用户。
        val parseFailure = runCatching {
            val candidates = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            candidates?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { candidate ->
                finishReason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                val content = candidate.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
                val parts = content?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
                parts?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    val isThought = part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean == true
                    if (part.has("text")) {
                        val text = part.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (isThought) thinkingText += text else contentText += text
                    }
                    part.inlineImagePart()?.let { images.add(it) }
                    if (part.has("functionCall")) {
                        val fnCall = part.get("functionCall")?.takeIf { it.isJsonObject }?.asJsonObject
                        val name = fnCall?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        // 并行调用时服务端会给 id；缺失才退回用函数名（同名工具调两次会撞 id）。
                        val callId = fnCall?.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                        val argsStr = fnCall?.get("args")?.takeIf { it.isJsonObject }?.asJsonObject?.toString() ?: "{}"
                        val argsJson = parseArgs(argsStr)
                        toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                    }
                }
                partsSnapshot = snapshotOf(parts)
            }

            // 内容安全拦截：candidates 为空但 promptFeedback 给出 blockReason（SAFETY / PROHIBITED_CONTENT
            // 等），把它作为 stopReason 上抛，上层据此按「中止」展示原因，而不是静默返回空白正文。
            if (finishReason == null && (candidates == null || candidates.size() == 0)) {
                response.get("promptFeedback")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("blockReason")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { finishReason = it }
            }
        }.exceptionOrNull()

        val usageMetadata = response.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val inputTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        // candidatesTokenCount 不含思考 token，而思考按输出价计费，故两者相加才是真实输出量。
        val outputTokens = (usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0) +
            (usageMetadata?.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
        val cachedInputTokens = usageMetadata?.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        // 响应结构损坏（解析过程抛异常）且没有可展示的正文/原因：返回明确的中止结果而非崩溃，
        // stopReason=failed 命中 isAborted，上层会展示错误文案。
        val aborted = parseFailure != null && finishReason == null
        return AIResponse(
            content = contentText,
            toolCalls = toolCalls,
            stopReason = if (aborted) "failed" else finishReason,
            stopDetail = if (aborted) parseFailure?.message ?: "Gemini 响应解析失败" else null,
            reasoning = thinkingText.ifEmpty { null },
            thinkingBlocksJson = partsSnapshot,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            images = images
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
            streamViaInteractions(systemPrompt, messages, tools, reasoningEffort)
            return@channelFlow
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = resolveGenerateContentUrl(stream = true)
        
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)

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
                val toolCalls = mutableListOf<ToolCall>()
                val images = mutableListOf<AgentImage>()
                // model 轮的 parts 原样快照：文本分片按段合并，functionCall 与 thoughtSignature 原样保留。
                val snapshotParts = mutableListOf<JsonObject>()
                var currentFinishReason: String? = null
                var streamInputTokens = 0
                var streamOutputTokens = 0
                var streamCachedInputTokens = 0

                val body = firstContent.awaitBody(api.streamGenerateContent(url = resolveGenerateContentUrl(stream = true), apiKey = apiKey, extraHeaders = extraHeaders(), request = request))

                body.use { rb ->
                    firstContent.attach { rb.close() }
                    val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                    val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                        runCatching { rb.close() }
                    }
                    try {
                        val reader = rb.charStream().buffered()
                        while (true) {
                            coroutineContext.ensureActive()
                            val line = reader.readLine()
                                ?: throw IOException("SSE 流被中断（疑似网络断开）")
                            idleWatchdog.touch()
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            sseLog.append(line)
                            val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                            
                            try {
                                obj.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject?.let { um ->
                                    streamInputTokens = um.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                    // 思考 token 不在 candidatesTokenCount 里，但按输出价计费，两者相加才是真实输出量。
                                    val candidateTokens = um.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    val thoughtTokens = um.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    if (candidateTokens != null || thoughtTokens != null) {
                                        streamOutputTokens = (candidateTokens ?: 0) + (thoughtTokens ?: 0)
                                    }
                                    streamCachedInputTokens = um.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                }
                                val chunkCandidates = obj.getAsJsonArray("candidates")
                                chunkCandidates?.firstOrNull()?.asJsonObject?.let { candidate ->
                                    val reason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                                    if (reason != null && reason != "null") currentFinishReason = reason

                                    val content = candidate.getAsJsonObject("content")
                                    content?.getAsJsonArray("parts")?.forEach { partEl ->
                                        val part = partEl.asJsonObject
                                        val isThought = part.get("thought")?.asBoolean == true
                                        if (part.has("functionCall")) {
                                            firstContent.receivedContent()
                                            onContent()
                                            budget.add(part.toString())
                                        }
                                        accumulateSnapshotPart(snapshotParts, part, isThought)
                                        if (part.has("text")) {
                                            val text = part.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                budget.add(text)
                                                if (isThought) {
                                                    // 思考增量：仅 UI 实时展示，不计入正文、不计入正文（不落库，重试时可安全重新流出）
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    send(AIStreamChunk.ReasoningDelta(text))
                                                } else {
                                                    textBuilder.append(text)
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    send(AIStreamChunk.TextDelta(text))
                                                }
                                            }
                                        }
                                        part.inlineImagePart()?.let { img ->
                                            images.add(img)
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                        if (part.has("functionCall")) {
                                            val fnCall = part.getAsJsonObject("functionCall")
                                            val name = fnCall.get("name")?.asString ?: ""
                                            // 工具名一出现就通知 UI，把「正在思考」换成具体场景
                                            if (name.isNotEmpty()) send(AIStreamChunk.ToolCallDeclared(name))
                                            val callId = fnCall.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                                            val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                                            val argsJson = parseArgs(argsStr)
                                            toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                                        }
                                    }
                                }
                                if (currentFinishReason != null) break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                coroutineContext.ensureActive()
                                // ignore
                            }
                        }
                    } finally {
                        idleWatchdog.cancel()
                        closeHandle?.dispose()
                    }
                }

                send(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = currentFinishReason, thinkingBlocksJson = snapshotOf(snapshotParts), inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, images = images)))
                    }
                },
                onRetry = { attempt, max, error -> send(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        } finally {
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Interactions 请求体。与 generateContent 的差异集中在四处：
     * - `system_instruction` 是**顶层字符串**，不再是 `{role, parts}` 对象；
     * - 历史是扁平的 step 序列（见 [buildInteractionsInput]），模型名进请求体；
     * - 工具声明扁平，不再套 `functionDeclarations`；
     * - 思考只有 `thinking_level`（2.5 系那套 `thinkingBudget` 在这里不存在）。
     *
     * 固定发 `store=false` 走无状态：本地 messages 列表才是唯一事实源，上下文压缩、重新生成、
     * 失败重试都依赖客户端持有全部历史，服务端状态会与这些直接冲突。
     * 不发 `temperature`——Interactions 的 GenerationConfig 里没有这个字段。
     */
    private fun buildInteractionsRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            "input" to buildInteractionsInput(messages),
            "store" to false
        )
        if (systemPrompt.isNotBlank()) request["system_instruction"] = systemPrompt
        buildInteractionsTools(tools)?.let { request["tools"] = it }
        if (stream) request["stream"] = true

        val generationConfig = mutableMapOf<String, Any>()
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["max_output_tokens"] = it }
        interactionsThinkingLevel(reasoningEffort)?.let {
            generationConfig["thinking_level"] = it
            // 不显式要摘要就只能拿到思考签名、拿不到可展示的思考文本，UI 的思考区会一直空着。
            // 与 thinking_level 绑在一起发：不思考的模型（gemma / 图像 / 音乐）不该收到这两个字段。
            generationConfig["thinking_summaries"] = "auto"
        }
        if (generationConfig.isNotEmpty()) request["generation_config"] = generationConfig
        return request
    }

    /**
     * 思考强度 → Interactions 的 `thinking_level`。档位集合与降级规则见 [ReasoningResolver]；
     * Interactions 只认档位、没有预算字段，因此预算型模型（2.5 系）在这里不发思考配置。
     */
    private fun interactionsThinkingLevel(reasoningEffort: String?): String? {
        val plan = ReasoningResolver.plan(ReasoningProtocol.GEMINI, model, reasoningEffort, effectiveReasoningCapability())
        return (plan.wire as? ReasoningWire.Effort)?.value
    }

    /**
     * Interactions 的非流式请求。结果从 `steps` 时间线解析（见 [parseInteractionSteps]），
     * 结束原因不再是 `finishReason` 而是 interaction 的 `status`：`incomplete` 表示撞了输出上限、
     * `requires_action` 表示有工具待执行，取值归一见 [interactionStopReason]。
     */
    private suspend fun completeViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", resolveInteractionsUrl(stream = false), request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createInteraction(url = resolveInteractionsUrl(stream = false), apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        val parsed = parseInteractionSteps(response.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseInteractionsUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        // status=failed 时正文通常为空，理由只在 errors 里，取出来交给上层展示。
        val errorDetail = response.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString

        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = interactionStopReason(status),
            stopDetail = errorDetail,
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = parsed.images
        )
    }

    /**
     * Interactions 的流式请求。与 generateContent 的 candidates 分片不同，这里是语义事件流
     * （见 [GeminiInteractionsStreamAccumulator]），且没有 `[DONE]`：结束按 interaction 的
     * status 到终态判定，读到流尾仍未终止才算被截断。
     */
    private suspend fun ProducerScope<AIStreamChunk>.streamViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", resolveInteractionsUrl(stream = true), request)
        // 原始 SSE 分批落盘（见 AILogger.beginRawSse），本轮结束在 finally 收尾。
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)
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
                    val acc = GeminiInteractionsStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamInteraction(
                        url = resolveInteractionsUrl(stream = true),
                        apiKey = apiKey,
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
                                    ?: throw IOException("SSE 流被中断：interaction 未到终态（疑似网络断开）")
                                idleWatchdog.touch()
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                sseLog.append(line)
                                if (data == "[DONE]") break
                                val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                                // 单个事件的字段类型异常不应废掉整条流，只跳过该事件；
                                // 但 StreamApiException（error 事件）与取消信号必须放行。
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
                                    is InteractionsDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        send(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // 思考增量仅用于 UI 展示，不计入正文；收到即说明连接已活。
                                    is InteractionsDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        send(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // 工具名先于参数到达：通知 UI 提前把状态换成具体场景
                                    is InteractionsDelta.ToolCallDeclared -> {
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
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            // 400 文案常带白名单：学到之后后续请求直接用，不再重复踩同一个档位。
            ReasoningLearning.observe(providerId, model, enriched, effectiveReasoningCapability())
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /**
     * generateContent / streamGenerateContent 共用的请求体。
     * `generationConfig` 里 thinkingConfig 与 maxOutputTokens 必须合并写（分两次赋值会互相覆盖）。
     */
    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): MutableMap<String, Any> {
        val request = mutableMapOf<String, Any>(
            "contents" to convertToGeminiContents(messages)
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { request["tools"] = listOf(mapOf("functionDeclarations" to it)) }

        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        return request
    }

    /**
     * model 轮 parts 的原样快照；仅当存在需要原样回传的内容（thoughtSignature 或 functionCall）时才生成，
     * 纯文本轮返回 null，让历史走常规重建（避开正文双写）。
     */
    private fun snapshotOf(parts: com.google.gson.JsonArray?): String? {
        if (parts == null || parts.size() == 0) return null
        val needsSnapshot = parts.any { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            o.has("functionCall") || o.has("thoughtSignature") || o.has("inlineData")
        }
        return if (needsSnapshot) parts.toString() else null
    }

    private fun snapshotOf(parts: List<JsonObject>): String? {
        if (parts.isEmpty()) return null
        val array = com.google.gson.JsonArray().apply { parts.forEach { add(it) } }
        return snapshotOf(array)
    }

    /**
     * 把一个流式 part 并入快照：functionCall 单独成段原样保留；文本分片按“是否 thought”分段合并，
     * 分片上的 thoughtSignature 写回所属段（签名是 part 级元数据，只会随某一片到达）。
     */
    /** 图片 part（camelCase 的 inlineData / snake_case 的 inline_data）→ [AgentImage]；图像模型直出的图整块到达。 */
    private fun JsonObject.inlineImagePart(): AgentImage? =
        (get("inlineData") ?: get("inline_data"))?.takeIf { it.isJsonObject }?.asJsonObject?.let { inline ->
            val data = inline.get("data")?.takeIf { !it.isJsonNull }?.asString
            if (data.isNullOrBlank()) null
            else AgentImage(
                mimeType = inline.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                    ?: inline.get("mime_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: "image/png",
                base64Data = data
            )
        }

    private fun accumulateSnapshotPart(snapshot: MutableList<JsonObject>, part: JsonObject, isThought: Boolean) {
        if (part.has("functionCall")) {
            snapshot.add(part.deepCopy())
            return
        }
        if (!part.has("text")) {
            // 图片整块到达、无文本分片可合并，原样进快照供下一轮编辑时回传（含 SynthID 等元数据）。
            if (part.has("thoughtSignature") || part.has("inlineData")) snapshot.add(part.deepCopy())
            return
        }
        val last = snapshot.lastOrNull()?.takeIf { prev ->
            !prev.has("functionCall") &&
                prev.has("text") &&
                (prev.get("thought")?.asBoolean == true) == isThought
        }
        if (last != null) {
            last.addProperty("text", last.get("text").asString + (part.get("text")?.asString ?: ""))
            part.get("thoughtSignature")?.takeIf { !it.isJsonNull }?.let { last.add("thoughtSignature", it) }
        } else {
            snapshot.add(part.deepCopy())
        }
    }

    /** 把快照还原成 parts 数组；为空或损坏时返回 null，由调用方回退重建逻辑。 */
    private fun decodeSnapshotParts(json: String): com.google.gson.JsonArray? {
        if (json.isBlank()) return null
        return runCatching {
            JsonParser.parseString(json).asJsonArray.takeIf { it.size() > 0 }
        }.getOrNull()
    }

    /**
     * 思考强度 → Gemini thinkingConfig。协议面与档位由 [ReasoningResolver] 决定：
     * - Gemini 3 / 4：`thinkingLevel`（档位集合按模型不同，Pro 无 minimal、3.7/3.8 Flash 无 minimal）；
     * - Gemini 2.5：`thinkingBudget`（Pro 不能关闭，Flash/Flash-Lite 关闭即预算 0）。
     * 显式带上 `includeThoughts` 以返回思考内容；预算 0 除外——Gemini 拒绝
     * `thinkingBudget=0` 与 `includeThoughts=true` 同时出现（invalid argument，请求硬失败）。
     */
    private fun buildThinkingConfig(reasoningEffort: String?): Map<String, Any>? {
        val plan = ReasoningResolver.plan(ReasoningProtocol.GEMINI, model, reasoningEffort, effectiveReasoningCapability())
        return when (val wire = plan.wire) {
            is ReasoningWire.Effort -> mapOf("thinkingLevel" to wire.value, "includeThoughts" to true)
            is ReasoningWire.Budget ->
                if (wire.tokens == 0) mapOf("thinkingBudget" to 0)
                else mapOf("thinkingBudget" to wire.tokens, "includeThoughts" to true)
            // 关闭/不支持在这个协议面上没有「关闭」字段：不思考的模型本就不该收到这两项。
            ReasoningWire.DisableEffortNone, ReasoningWire.BetweenTools,
            is ReasoningWire.DisableToggle, ReasoningWire.Omit -> null
        }
    }

    private fun parseArgs(raw: String): kotlinx.serialization.json.JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return kotlinx.serialization.json.JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }
    }

    private fun convertToGeminiContents(messages: List<AgentMessage>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        // 防御性跟踪：上一个 assistant(即 model) 消息是否包含 functionCall
        var lastModelHadFunctionCall = false
        // 同一 model 轮次的多个工具结果必须并进同一个 user content：并行调用时 Gemini 与各家兼容
        // 网关都按「一轮回传全部 functionResponse」配对，拆成多轮会被判成 tool 结果缺失（HTTP 400）。
        var pendingToolResponseParts: MutableList<Map<String, Any>>? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotBlank()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                    result.add(
                        mapOf(
                            "role" to "user",
                            "parts" to parts
                        )
                    )
                    lastModelHadFunctionCall = false
                    pendingToolResponseParts = null
                }
                is AgentMessage.AssistantMessage -> {
                    lastModelHadFunctionCall = message.toolCalls.isNotEmpty()
                    pendingToolResponseParts = null
                    // 上轮 model 输出存有原样快照时直接原样回传：thoughtSignature 是 part 级元数据，
                    // 重建 parts 会丢失签名，Gemini 3 系就接不上上一轮的推理上下文。
                    val snapshot = decodeSnapshotParts(message.thinkingBlocksJson)
                    if (snapshot != null) {
                        result.add(mapOf("role" to "model", "parts" to snapshot))
                        continue
                    }
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotEmpty()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    for (toolCall in message.toolCalls) {
                        parts.add(
                            mapOf(
                                "functionCall" to mapOf(
                                    "name" to toolCall.name,
                                    "args" to toolCall.arguments
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) {
                        result.add(
                            mapOf(
                                "role" to "model",
                                "parts" to parts
                            )
                        )
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // 防御性清理：跳过没有配对 functionCall 的孤立 functionResponse
                    if (!lastModelHadFunctionCall) continue
                    val parts = pendingToolResponseParts ?: mutableListOf<Map<String, Any>>().also {
                        result.add(mapOf("role" to "user", "parts" to it))
                        pendingToolResponseParts = it
                    }
                    // 文件类工具喂模型用精简投影文本，UI/持久化仍走完整 result。
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    // name 发函数名，并行调用时额外带 id 回去配对（旧数据的 id 就是函数名，此时不发 id）。
                    val functionName = message.toolName.ifBlank { message.id }
                    val functionResponse = mutableMapOf<String, Any>(
                        "name" to functionName,
                        "response" to mapOf("result" to modelText)
                    )
                    if (message.id.isNotBlank() && message.id != functionName) {
                        functionResponse["id"] = message.id
                    }
                    parts.add(mapOf("functionResponse" to functionResponse))
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                }
            }
        }
        return result
    }

    private fun AgentImage.toGeminiInlineDataPart(): Map<String, Any> {
        return mapOf(
            "inline_data" to mapOf(
                "mime_type" to mimeType,
                "data" to base64Data
            )
        )
    }
}
