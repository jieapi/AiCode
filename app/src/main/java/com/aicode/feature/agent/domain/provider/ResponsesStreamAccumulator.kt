package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesEvent
import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Responses 响应体 `output` 数组的解析结果。 */
internal data class ResponsesOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** 模型经服务端 image_generation 工具产出的图片（`result` base64）。 */
    val images: List<AgentImage> = emptyList(),
    /** 官方 Responses 思考项快照（包含 encrypted_content 与 summary），供多轮无状态安全回传。 */
    val thinkingBlocksJson: String? = null
)

/** Responses 的 token 用量。 */
internal data class ResponsesUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0
)

/**
 * 解析 Responses 的 `output` 数组：非流式响应与流式终止事件共用。
 *
 * 与 Chat Completions 的关键差别：工具调用是 output 里的**顶层 item**（`type: "function_call"`，
 * 自带 `call_id` / `name` / `arguments`），不是 assistant 消息 content 里的一个 part。
 * 按旧写法去 message.content 里找会永远解析不到工具调用。
 */
internal fun parseResponsesOutput(output: JsonArray?): ResponsesOutput {
    if (output == null) return ResponsesOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    var thinkingBlocksSnapshot: String? = null
    output.forEach { element ->
        // 上游字段类型偶有出入，单个 item 解析失败不应废掉整个响应
        runCatching {
            val item = element.asJsonObject
            when (item.str("type")) {
                ResponsesItem.MESSAGE -> item.arr("content")?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    when (part.str("type")) {
                        ResponsesPart.OUTPUT_TEXT -> text.append(part.str("text").orEmpty())
                        // 拒答说明在 refusal 字段，不归入则整个回复为空白
                        ResponsesPart.REFUSAL -> text.append(part.str("refusal").orEmpty())
                    }
                }

                ResponsesItem.FUNCTION_CALL -> toolCalls.add(
                    ToolCall(
                        id = item.str("call_id") ?: item.str("id").orEmpty(),
                        name = item.str("name").orEmpty(),
                        arguments = parseToolArguments(item.str("arguments").orEmpty())
                    )
                )

                // 服务端生图：图片整块在 item 的 result（base64）里到达，无流式增量。
                ResponsesItem.IMAGE_GENERATION_CALL -> {
                    item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                        images.add(AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result))
                    }
                }

                // 思考内容可能在 content（reasoning_text）或 summary（summary_text），视服务而定；
                // 两边都可能是 null（如不返回思考明文时）。
                // 同时提取加密思考快照（encrypted_content），供无状态多轮交互安全回传。
                ResponsesItem.REASONING -> {
                    item.arr("content")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.REASONING_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    item.arr("summary")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.SUMMARY_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                    if (encContent != null && thinkingBlocksSnapshot == null) {
                        val snapshot = JsonObject().apply {
                            addProperty("type", ResponsesItem.REASONING)
                            addProperty("encrypted_content", encContent)
                            item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                        }
                        thinkingBlocksSnapshot = snapshot.toString()
                    }
                }
            }
        }
    }
    return ResponsesOutput(text.toString(), reasoning.toString(), toolCalls, images, thinkingBlocksSnapshot)
}

/** image_generation_call 的 `output_format` → MIME；缺省为官方默认 png。 */
private fun mimeForImageFormat(format: String?): String = when (format?.lowercase()) {
    "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

internal fun parseResponsesUsage(usage: JsonObject?): ResponsesUsage {
    if (usage == null) return ResponsesUsage()
    return ResponsesUsage(
        inputTokens = usage.int("input_tokens"),
        outputTokens = usage.int("output_tokens"),
        cachedInputTokens = usage.obj("input_tokens_details")?.int("cached_tokens") ?: 0,
        cacheCreationTokens = usage.obj("input_tokens_details")?.int("cache_write_tokens") ?: 0
    )
}

/**
 * Responses 的 `status` → 统一 stopReason（沿用 Chat Completions 的取值，供上层复用同一套判定）。
 * 输出被 token 上限截断必须映射成 `length`，否则 [AIResponse.isTruncated] 为 false，
 * Agent 循环不会自动续写，表现为回答被无声截断。截断原因的取值各家不一：
 * OpenAI 官方文档示例给 `max_tokens`，DeepSeek / 部分兼容服务给 `max_output_tokens`。
 */
internal fun responsesStopReason(status: String?, incompleteReason: String?, hasToolCalls: Boolean): String? = when {
    status == "incomplete" && incompleteReason in TRUNCATION_REASONS -> "length"
    hasToolCalls -> "tool_calls"
    status == "completed" -> "stop"
    else -> status
}

private val TRUNCATION_REASONS = setOf("max_output_tokens", "max_tokens")

/** 流式事件产生的增量文本，供上层推送给 UI。 */
internal sealed class ResponsesDelta {
    data class Text(val text: String) : ResponsesDelta()
    data class Reasoning(val text: String) : ResponsesDelta()

    /** 刚开始产出某次 function_call、工具名已知（参数还在流式传输中），供 UI 提前说清「在调什么」。 */
    data class ToolCallDeclared(val name: String) : ResponsesDelta()
}

/**
 * Responses 流式语义事件累积器。
 *
 * 事件按 `output_index` 归组：`response.output_item.added` 给出 function_call 的 call_id/name，
 * `response.function_call_arguments.delta` 逐片追加入参，`response.output_item.done` /
 * `response.function_call_arguments.done` 给出完整入参（用于覆盖，防 delta 丢帧）。
 * 终止事件（completed / incomplete）携带完整 response 对象，用于兜底补齐、取 usage 与 status；
 * failed 直接抛 [StreamApiException] 交给重试判定。
 */
internal class ResponsesStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class CallAcc {
        var callId = ""
        var name = ""
        var args = StringBuilder()
    }

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val budget = StreamBudget()
    private var thinkingBlocksSnapshotJson: String? = null
    private val calls = LinkedHashMap<String, CallAcc>()
    /** 流中 output_item.done 里到达的图片，按 item id 去重。 */
    private val images = LinkedHashMap<String, AgentImage>()
    /** 终止事件里兜底解析出的图片（流中 delta 事件缺失时的补齐来源）。 */
    private val finalImages = mutableListOf<AgentImage>()
    /** 终止事件里兜底解析出的工具调用（流中 delta 事件缺失时的补齐来源）。 */
    private val finalCalls = mutableListOf<ToolCall>()
    private var status: String? = null
    private var responseId: String? = null
    private var incompleteReason: String? = null
    private var usage = ResponsesUsage()

    /** 是否已收到终止事件。未收到就断流说明流被截断，调用方应按异常处理。 */
    var terminated = false
        private set

    /** 处理一个 SSE data 行解析出的事件对象，返回本次事件带来的增量文本（无则 null）。 */
    fun accept(event: JsonObject): ResponsesDelta? {
        when (event.str("type")) {
            ResponsesEvent.OUTPUT_TEXT_DELTA, ResponsesEvent.REFUSAL_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                text.append(delta)
                receivedContent = true
                return ResponsesDelta.Text(delta)
            }

            ResponsesEvent.REASONING_TEXT_DELTA, ResponsesEvent.REASONING_SUMMARY_TEXT_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                reasoning.append(delta)
                receivedContent = true
                return ResponsesDelta.Reasoning(delta)
            }

            ResponsesEvent.OUTPUT_ITEM_ADDED, ResponsesEvent.OUTPUT_ITEM_DONE -> {
                val item = event.obj("item") ?: return null
                when (item.str("type")) {
                    ResponsesItem.FUNCTION_CALL -> {
                        val acc = calls.getOrPut(event.callKey()) { CallAcc() }
                        item.str("call_id")?.takeIf { it.isNotEmpty() }?.let { acc.callId = it }
                        item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { budget.add(it); acc.args = StringBuilder(it) }
                        item.str("name")?.takeIf { it.isNotEmpty() }?.let { name ->
                            receivedContent = true
                            val first = acc.name.isEmpty()
                            acc.name = name
                            // 工具名先于参数到达：让 UI 提前把状态说具体（每个调用只推一次）
                            if (first) return ResponsesDelta.ToolCallDeclared(name)
                        }
                    }
                    ResponsesItem.REASONING -> {
                        val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                        if (encContent != null) {
                            val snapshot = JsonObject().apply {
                                addProperty("type", ResponsesItem.REASONING)
                                addProperty("encrypted_content", encContent)
                                item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                            }
                            thinkingBlocksSnapshotJson = snapshot.toString()
                        }
                    }
                    ResponsesItem.IMAGE_GENERATION_CALL -> {
                        item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                            val key = item.str("id") ?: event.callKey()
                            receivedContent = true
                            images[key] = AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result)
                        }
                    }
                }
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                // 长工具参数流也属于「正在收到内容」，必须取消首字节超时。
                receivedContent = true
                calls.getOrPut(event.callKey()) { CallAcc() }.args.append(delta)
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DONE -> {
                val full = event.str("arguments").orEmpty()
                if (full.isEmpty()) return null
                budget.add(full)
                calls.getOrPut(event.callKey()) { CallAcc() }.args = StringBuilder(full)
            }

            ResponsesEvent.COMPLETED, ResponsesEvent.INCOMPLETE -> {
                // 先置位：终止语义不能受 response 对象解析异常影响，否则调用方会继续读到流尾、误报断流。
                terminated = true
                absorbFinalResponse(event.obj("response"))
            }

            ResponsesEvent.FAILED -> {
                terminated = true
                val response = event.obj("response")
                absorbFinalResponse(response)
                val error = response?.obj("error") ?: event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Responses 流以 response.failed 结束"
                )
            }

            // 传输层错误事件：code / message 就在事件自身上（不嵌在 error 对象里）。
            ResponsesEvent.ERROR, ResponsesEvent.RESPONSE_ERROR -> {
                val error = event.obj("error")
                throw StreamApiException(
                    code = event.str("code") ?: error?.str("code"),
                    message = event.str("message") ?: error?.str("message") ?: "Responses 流返回 error 事件"
                )
            }
        }
        return null
    }

    fun toResponse(): AIResponse {
        val streamed = calls.values
            .filter { it.callId.isNotEmpty() || it.name.isNotEmpty() }
            .map { ToolCall(id = it.callId, name = it.name, arguments = parseToolArguments(it.args.toString())) }
        val toolCalls = streamed + finalCalls.filter { call ->
            streamed.none { it.id == call.id && call.id.isNotEmpty() }
        }
        return AIResponse(
            content = text.toString(),
            toolCalls = toolCalls,
            stopReason = responsesStopReason(status, incompleteReason, toolCalls.isNotEmpty()),
            reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
            thinkingBlocksJson = thinkingBlocksSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            cacheCreationTokens = usage.cacheCreationTokens,
            images = images.values.filter { it.base64Data.isNotEmpty() }.ifEmpty { finalImages },
            responseId = responseId
        )
    }

    /** 终止事件携带完整 response：取 status/usage，并用顶层 output 兜底补齐流中漏掉的内容。 */
    private fun absorbFinalResponse(response: JsonObject?) {
        if (response == null) return
        status = response.str("status")
        responseId = response.str("id")
        incompleteReason = response.obj("incomplete_details")?.str("reason")
        usage = parseResponsesUsage(response.obj("usage"))

        val parsed = parseResponsesOutput(response.arr("output"))
        if (text.isEmpty()) { budget.add(parsed.text); text.append(parsed.text) }
        if (reasoning.isEmpty()) { budget.add(parsed.reasoning); reasoning.append(parsed.reasoning) }
        if (thinkingBlocksSnapshotJson == null) thinkingBlocksSnapshotJson = parsed.thinkingBlocksJson
        finalCalls.clear()
        finalCalls.addAll(parsed.toolCalls)
        finalImages.clear()
        finalImages.addAll(parsed.images)
    }

    private fun JsonObject.callKey(): String =
        str("output_index") ?: str("item_id") ?: "0"
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// 下面两个取代 Gson 自带的 getAsJsonArray / getAsJsonObject：它们直接强转，遇到 null
// （如 reasoning item 的 `content: null`、failed 事件里缺失的 `output`）会抛 ClassCastException。
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
