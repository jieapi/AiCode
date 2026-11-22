package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.data.remote.openai.ResponsesToolDefinition
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 把模型给出的工具入参 JSON 字符串解析为 JsonObject；为空或非法时回退为空对象。 */
internal fun parseToolArguments(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}

/** 工具定义 → Responses 扁平 function 定义；无工具时返回 null（不发 `tools` 字段）。 */
internal fun buildResponsesTools(tools: List<AgentTool>): List<ResponsesToolDefinition>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        ResponsesToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = tool.toJsonSchema()
        )
    }

/**
 * 会话历史 → Responses 的 `input` item 序列。
 *
 * 与 Chat Completions 的三种 role 消息不同，Responses 把工具往返拆成独立 item：
 * 模型的调用是 `function_call`（按 `call_id` 标识），执行结果是 `function_call_output`，
 * 二者不再依附于 assistant 消息与 `role:"tool"` 消息。
 *
 * 配对约束与 Chat Completions 一致且同样必须在客户端保证：`function_call_output` 必须能
 * 找到同 `call_id` 的 `function_call`，声明过的 `function_call` 也必须有结果。这里按调用逐个
 * 吸附其结果，顺带处理两类历史脏数据：
 * - 结果乱序落位（如 askUserQuestion 阻塞期间其他工具结果插队）→ 吸附回调用之后；
 * - 孤立结果（前驱调用已被上下文压缩裁掉）→ 丢弃；
 * - 调用无结果（如用户拒绝执行）→ 连调用一起裁掉。
 *
 * **同一轮的多个 `function_call` 必须连续写完，再集中写 `function_call_output`**，不能
 * 调用/结果交替：服务端把 `function_call` 归并到相邻的 assistant 消息，中间一旦隔了
 * `function_call_output`（等价于 tool 消息），后续调用就会被当成新的 assistant 轮。对 DeepSeek
 * 思考模式而言，那些凭空多出来的轮没有 reasoning item，于是报
 * 400 The `reasoning_text` in the thinking mode must be passed back to the API。
 * 集中写也与 Chat Completions 的「一条 assistant 带 N 个 tool_calls + N 条 tool 消息」等价。
 *
 * 思考内容只在 [includeReasoningItems] 为真时以独立的 `reasoning` item 回传，紧贴在所属 assistant
 * 内容之前（与模型输出的 item 顺序一致，服务端会归并到相邻的 assistant 消息），思考为空时
 * 发空文本占位。开关存在是因为两家要求相反：
 * - DeepSeek 思考模式下只要请求带了 `tools`，历史每轮 assistant 都必须完整回传思考内容
 *   （即使该轮未实际调用工具），否则 400；它只收明文 `content`，不支持 summary / encrypted_content。
 * - OpenAI 官方的 reasoning item 必须带 `id`/`summary` 与 `encrypted_content`，
 *   只有当存有合法有效密文时才予以回传；若缺失则直接略过，防止只有明文触发服务端 400 拒绝。
 */
internal fun buildResponsesInput(
    systemPrompt: String,
    systemRole: String,
    messages: List<AgentMessage>,
    includeReasoningItems: Boolean = false
): List<Map<String, Any?>> {
    val items = mutableListOf<Map<String, Any?>>()
    if (systemPrompt.isNotBlank()) {
        items.add(mapOf("role" to systemRole, "content" to systemPrompt))
    }

    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> items.add(
                mapOf("role" to "user", "content" to message.toResponsesContent())
            )

            is AgentMessage.AssistantMessage -> {
                // 先把有结果的调用配好对：无结果的调用会被裁掉（如用户拒绝执行），若整轮
                // 都被裁掉且没有正文，这轮不写任何 item，reasoning 也就无处可挂。
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                // 思考模式的服务要求上一轮的思考内容原样回传，缺了报 400：
                // The `reasoning_text` in the thinking mode must be passed back to the API。
                val hasBody = message.content.isNotBlank() || paired.isNotEmpty()
                val encryptedReasoning = decodeResponsesEncryptedReasoning(message.thinkingBlocksJson)
                if (hasBody) {
                    if (encryptedReasoning != null) {
                        // 优先回传 OpenAI 官方/通用 Responses 加密快照（含 summary 与 encrypted_content）
                        items.add(encryptedReasoning)
                    } else if (includeReasoningItems) {
                        // DeepSeek 思考模式：回传明文 reasoning_text
                        items.add(
                            mapOf(
                                "type" to ResponsesItem.REASONING,
                                "content" to listOf(
                                    mapOf(
                                        "type" to ResponsesPart.REASONING_TEXT,
                                        "text" to message.reasoning
                                    )
                                )
                            )
                        )
                    }
                }
                if (message.content.isNotBlank()) {
                    items.add(
                        mapOf(
                            "role" to "assistant",
                            "content" to listOf(
                                mapOf("type" to ResponsesPart.OUTPUT_TEXT, "text" to message.content)
                            )
                        )
                    )
                }
                for ((call, _) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL,
                            "call_id" to call.id,
                            "name" to call.name,
                            "arguments" to JsonObject(call.arguments).toString()
                        )
                    )
                }
                for ((call, result) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL_OUTPUT,
                            "call_id" to call.id,
                            "output" to result.toResponsesOutput()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return items
}

internal data class ResponsesContinuationInput(
    val canContinue: Boolean,
    val input: List<Map<String, Any?>>,
    val previousResponseId: String?
)

internal fun prepareResponsesContinuationInput(
    fullInput: List<Map<String, Any?>>,
    messages: List<AgentMessage>,
    submittedMessages: List<AgentMessage>,
    previousResponseId: String?
): ResponsesContinuationInput {
    val canContinue = previousResponseId != null && submittedMessages.size <= messages.size &&
        submittedMessages.indices.all { submittedMessages[it] == messages[it] }
    val input = if (canContinue) {
        buildResponsesContinuationInput(messages.drop(submittedMessages.size))
    } else {
        fullInput
    }
    return ResponsesContinuationInput(input = input, canContinue = canContinue, previousResponseId = previousResponseId.takeIf { canContinue })
}

/** 新 WebSocket response 续传时只发送上轮完成后新增的用户消息与工具结果。 */
internal fun buildResponsesContinuationInput(messages: List<AgentMessage>): List<Map<String, Any?>> =
    messages.mapNotNull { message ->
        when (message) {
            is AgentMessage.UserMessage -> mapOf(
                "role" to "user",
                "content" to message.toResponsesContent()
            )
            is AgentMessage.ToolResultMessage -> mapOf(
                "type" to ResponsesItem.FUNCTION_CALL_OUTPUT,
                "call_id" to message.id,
                "output" to message.toResponsesOutput()
            )
            is AgentMessage.AssistantMessage -> null
        }
    }

/**
 * 从快照 JSON 中反序列化出 OpenAI 官方 Responses 规范的加密 reasoning item。
 * 严格按照 OpenCode / OpenAI 防御规则：只有当包含非空字符串 `encrypted_content` 时才被允许回传；
 * 否则返回 null，绝不发送残缺的 reasoning item 导致服务端抛出 400 错误。
 */
internal fun decodeResponsesEncryptedReasoning(thinkingBlocksJson: String?): Map<String, Any?>? {
    if (thinkingBlocksJson.isNullOrBlank()) return null
    return runCatching {
        val obj = JsonParser.parseString(thinkingBlocksJson).asJsonObject
        if (obj.get("type")?.asString != ResponsesItem.REASONING) return null
        val encryptedContent = obj.get("encrypted_content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        if (encryptedContent.isNullOrBlank()) return null
        val summaryArr = obj.get("summary")?.takeIf { it.isJsonArray }?.asJsonArray
        val summaryList = summaryArr?.mapNotNull { el ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                val map = mutableMapOf<String, Any>()
                o.get("type")?.takeIf { !it.isJsonNull }?.asString?.let { map["type"] = it }
                o.get("text")?.takeIf { !it.isJsonNull }?.asString?.let { map["text"] = it }
                map.takeIf { it.isNotEmpty() }
            } else null
        } ?: emptyList()
        mapOf(
            "type" to ResponsesItem.REASONING,
            "summary" to summaryList,
            "encrypted_content" to encryptedContent
        )
    }.getOrNull()
}

/** 用户消息内容：纯文本直接给字符串，带图时拆成 input_text / input_image part 列表。 */
private fun AgentMessage.UserMessage.toResponsesContent(): Any {
    if (images.isEmpty()) return content
    val parts = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to content))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

/** 工具结果：纯文本直接给字符串，带图时用 part 列表（Responses 允许 function_call_output 内含图片）。 */
private fun AgentMessage.ToolResultMessage.toResponsesOutput(): Any {
    // 文件类工具喂模型用精简投影文本，UI/持久化仍走完整 result。
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    if (images.isEmpty()) return modelText
    val parts = mutableListOf<Map<String, Any>>()
    if (modelText.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to modelText))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

private fun AgentImage.toResponsesImagePart(): Map<String, Any> = mapOf(
    "type" to ResponsesPart.INPUT_IMAGE,
    "image_url" to "data:$mimeType;base64,$base64Data",
    "detail" to "auto"
)
