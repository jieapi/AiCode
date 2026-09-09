package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.ChatCompletionResponse
import com.aicode.feature.agent.data.remote.openai.Choice
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.data.remote.openai.OpenAIChatMessage
import com.aicode.feature.agent.data.remote.openai.OpenAIFunctionCall
import com.aicode.feature.agent.data.remote.openai.OpenAIToolCall
import com.aicode.feature.agent.data.remote.openai.PromptTokensDetails
import com.aicode.feature.agent.data.remote.openai.Usage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 适配器（[OpenAIAdapter]）：Chat Completions 请求构造 / 响应映射、Responses API 载荷与
 * 解析、SSE 流式聚合。底层 payload/accumulator 纯函数由 ResponsesPayloadTest /
 * ResponsesStreamAccumulatorTest 覆盖，这里侧重 adapter 的组装与分发逻辑。
 */
class OpenAIAdapterTest {

    private fun api(): OpenAIApi = mockk()

    private fun adapter(api: OpenAIApi): OpenAIAdapter =
        OpenAIAdapter(api).apply {
            apiKey = "k"
            model = "gpt-4-turbo"
        }

    private fun response(
        content: Any? = "ok",
        toolCalls: List<OpenAIToolCall>? = null,
        finishReason: String? = "stop",
        reasoningContent: String? = null,
        usage: Usage = Usage(10, 5, 15, PromptTokensDetails(cached_tokens = 3))
    ): ChatCompletionResponse = ChatCompletionResponse(
        id = "id1",
        `object` = "chat.completion",
        created = 0L,
        model = "gpt-4-turbo",
        choices = listOf(
            Choice(
                index = 0,
                message = OpenAIChatMessage(
                    role = "assistant",
                    content = content,
                    tool_calls = toolCalls,
                    reasoning_content = reasoningContent
                ),
                delta = null,
                finish_reason = finishReason
            )
        ),
        usage = usage
    )

    private fun toolCall(id: String, name: String, arguments: String): OpenAIToolCall =
        OpenAIToolCall(id = id, function = OpenAIFunctionCall(name = name, arguments = arguments))

    // ── Chat Completions 非流式：请求构造 ──────────────────────────────

    @Test
    fun complete_buildsRequestWithSystemPromptToolsAndDefaults() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        val tool = mockk<AgentTool>(relaxed = true)
        every { tool.name } returns "readFile"
        every { tool.description } returns "读取文件"
        every { tool.toJsonSchema() } returns mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        val adapter = adapter(api)
        adapter.temperature = null
        val result = adapter.complete(
            "你是助手",
            listOf(AgentMessage.UserMessage(content = "你好")),
            tools = listOf(tool)
        )

        val req = reqSlot.captured
        assertEquals("gpt-4-turbo", req.model)
        assertNull(req.temperature) // null 不带 temperature
        assertEquals(2, req.messages.size)
        assertEquals("system", req.messages[0].role)
        assertEquals("你是助手", req.messages[0].content)
        assertEquals("user", req.messages[1].role)
        assertEquals(1, req.tools?.size)
        assertEquals("readFile", req.tools?.get(0)?.function?.name)
        assertEquals("auto", req.tool_choice)
        assertEquals(false, req.stream)
        assertNull(req.prompt_cache_key) // 默认关闭
        assertEquals("ok", result.content)
        assertEquals("stop", result.stopReason)
        assertEquals(10, result.inputTokens)
        assertEquals(5, result.outputTokens)
        assertEquals(3, result.cachedInputTokens)
    }

    @Test
    fun complete_systemRole_usesDeveloperForReasoningModels() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).apply { model = "o3-mini" }
            .complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))
        assertEquals("developer", reqSlot.captured.messages[0].role)

        adapter(api).apply { model = "gpt-5.2" }
            .complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))
        assertEquals("developer", reqSlot.captured.messages[0].role)

        adapter(api).apply { model = "gpt-4-turbo" }
            .complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))
        assertEquals("system", reqSlot.captured.messages[0].role)
    }

    @Test
    fun complete_reasoningEffort_normalizesNoneAndMax() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).complete("", emptyList(), reasoningEffort = "none")
        assertNull(reqSlot.captured.reasoning_effort)

        adapter(api).complete("", emptyList(), reasoningEffort = "minimal")
        assertNull(reqSlot.captured.reasoning_effort)

        adapter(api).complete("", emptyList(), reasoningEffort = "xhigh")
        assertEquals("high", reqSlot.captured.reasoning_effort)

        adapter(api).complete("", emptyList(), reasoningEffort = "medium")
        assertEquals("medium", reqSlot.captured.reasoning_effort)
    }

    @Test
    fun complete_temperature_passedThroughWhenSet() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).apply { temperature = 0.7f }.complete("", emptyList())
        assertEquals(0.7f, reqSlot.captured.temperature!!, 0.001f)
    }

    @Test
    fun complete_chatCacheKey_sentWhenEnabled() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).apply {
            chatCacheKeyEnabled = true
            logSessionId = "session-42"
        }.complete("", emptyList())
        assertEquals("session-42", reqSlot.captured.prompt_cache_key)

        adapter(api).apply {
            chatCacheKeyEnabled = false
            logSessionId = "session-42"
        }.complete("", emptyList())
        assertNull(reqSlot.captured.prompt_cache_key)
    }

    @Test
    fun complete_useFullUrl_usesBaseUrlDirectly() = runTest {
        val api = api()
        val urlSlot = slot<String>()
        coEvery { api.createChatCompletion(capture(urlSlot), any(), any(), any()) } returns response()

        adapter(api).apply {
            baseUrl = "https://custom.example/v1/chat/completions"
            useFullUrl = true
        }.complete("", emptyList())
        assertEquals("https://custom.example/v1/chat/completions", urlSlot.captured)
    }

    // ── Chat Completions：响应映射 ─────────────────────────────────────

    @Test
    fun complete_mapsToolCallsAndReasoning() = runTest {
        val api = api()
        coEvery { api.createChatCompletion(any(), any(), any(), any()) } returns response(
            content = null,
            toolCalls = listOf(toolCall("call_1", "readFile", "{\"path\":\"a.txt\"}")),
            finishReason = "tool_calls",
            reasoningContent = "think step"
        )

        val result = adapter(api).complete("", emptyList())

        assertEquals(1, result.toolCalls.size)
        assertEquals("call_1", result.toolCalls[0].id)
        assertEquals("readFile", result.toolCalls[0].name)
        assertEquals(JsonPrimitive("a.txt"), result.toolCalls[0].arguments["path"])
        assertEquals("tool_calls", result.stopReason)
        assertEquals("think step", result.reasoning)
    }

    @Test
    fun complete_invalidToolArguments_fallsBackToEmptyObject() = runTest {
        val api = api()
        coEvery { api.createChatCompletion(any(), any(), any(), any()) } returns response(
            content = null,
            toolCalls = listOf(toolCall("call_1", "writeFile", "not-json")),
            finishReason = "tool_calls"
        )

        val result = adapter(api).complete("", emptyList())

        assertEquals(JsonObject(emptyMap()), result.toolCalls[0].arguments)
    }

    @Test
    fun complete_listContent_extractsTextAndOmitsInlineBase64() = runTest {
        val api = api()
        coEvery { api.createChatCompletion(any(), any(), any(), any()) } returns response(
            content = listOf(
                mapOf("type" to "text", "text" to "正文"),
                mapOf("type" to "image_url", "image_url" to "data:image/png;base64,iVBORw0KGgoAAA")
            )
        )

        val result = adapter(api).complete("", emptyList())

        assertTrue(result.content.contains("正文"))
        assertTrue(result.content.contains("图片已省略"))
        assertTrue(!result.content.contains("iVBORw0KGgo"))
    }

    // ── Chat Completions：历史消息清洗 ─────────────────────────────────

    @Test
    fun complete_replaysAssistantToolCallsWithReasoning() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).apply { model = "deepseek-chat" }.complete(
            "",
            listOf(
                AgentMessage.AssistantMessage(
                    content = "准备调用",
                    toolCalls = listOf(ToolCall("call_1", "readFile", JsonObject(emptyMap()))),
                    reasoning = "上一轮思考"
                ),
                AgentMessage.ToolResultMessage(id = "call_1", toolName = "readFile", result = "内容")
            )
        )

        val msgs = reqSlot.captured.messages
        val assistant = msgs.first { it.role == "assistant" }
        assertEquals("准备调用", assistant.content)
        assertEquals("上一轮思考", assistant.reasoning_content) // DeepSeek 必须回传
        assertEquals(1, assistant.tool_calls?.size)
        val toolMsg = msgs.first { it.role == "tool" }
        assertEquals("call_1", toolMsg.tool_call_id)
        assertEquals("内容", toolMsg.content)
    }

    @Test
    fun complete_orphanToolMessage_dropped() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).complete(
            "",
            listOf(
                AgentMessage.UserMessage(content = "你好"),
                AgentMessage.ToolResultMessage(id = "orphan", toolName = "readFile", result = "无前驱")
            )
        )

        // 孤立 tool 消息被跳过，不会带 role=tool 的消息
        assertTrue(reqSlot.captured.messages.none { it.role == "tool" })
    }

    @Test
    fun complete_unresolvedToolCall_droppedWithItsResponse() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).complete(
            "",
            listOf(
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(
                        ToolCall("c1", "readFile", JsonObject(emptyMap())),
                        ToolCall("c2", "writeFile", JsonObject(emptyMap()))
                    )
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "r1")
            )
        )

        val assistant = reqSlot.captured.messages.first { it.role == "assistant" }
        // 未执行的 c2 被裁剪，c1 保留
        assertEquals(listOf("c1"), assistant.tool_calls?.map { it.id })
        assertEquals(1, reqSlot.captured.messages.count { it.role == "tool" })
    }

    @Test
    fun complete_outOfOrderToolResult_reattachedToCaller() = runTest {
        val api = api()
        val reqSlot = slot<ChatCompletionRequest>()
        coEvery { api.createChatCompletion(any(), any(), any(), capture(reqSlot)) } returns response()

        adapter(api).complete(
            "",
            listOf(
                AgentMessage.AssistantMessage(
                    content = "调用",
                    toolCalls = listOf(ToolCall("c1", "list", JsonObject(emptyMap())))
                ),
                AgentMessage.UserMessage(content = "插队消息"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "list", result = "结果")
            )
        )

        val msgs = reqSlot.captured.messages
        val assistantIdx = msgs.indexOfFirst { it.role == "assistant" }
        // 结果被吸附回 assistant 之后、插队消息之前
        assertEquals("tool", msgs[assistantIdx + 1].role)
        assertEquals("c1", msgs[assistantIdx + 1].tool_call_id)
    }

    // ── Responses API ─────────────────────────────────────────────────

    @Test
    fun responsesComplete_buildsInputAndMapsOutput() = runTest {
        val api = api()
        val reqSlot = slot<Map<String, Any?>>()
        coEvery { api.createResponses(any(), any(), any(), capture(reqSlot)) } returns com.google.gson.JsonObject().apply {
            addProperty("status", "completed")
            add("output", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("type", "message")
                    add("content", com.google.gson.JsonArray().apply {
                        add(com.google.gson.JsonObject().apply {
                            addProperty("type", "output_text")
                            addProperty("text", "回复内容")
                        })
                    })
                })
                add(com.google.gson.JsonObject().apply {
                    addProperty("type", "function_call")
                    addProperty("call_id", "fc_1")
                    addProperty("name", "readFile")
                    addProperty("arguments", "{\"path\":\"/a\"}")
                })
            })
            add("usage", com.google.gson.JsonObject().apply {
                addProperty("input_tokens", 100)
                addProperty("output_tokens", 50)
                add("input_tokens_details", com.google.gson.JsonObject().apply {
                    addProperty("cached_tokens", 40)
                })
            })
        }

        val adapter = adapter(api).apply { useResponseApi = true }
        val result = adapter.complete("系统提示", listOf(AgentMessage.UserMessage(content = "hi")), reasoningEffort = "high")

        val req = reqSlot.captured
        assertEquals("gpt-4-turbo", req["model"])
        assertTrue((req["input"] as List<*>).isNotEmpty())
        assertEquals(listOf("reasoning.encrypted_content"), req["include"])
        val reasoning = req["reasoning"] as Map<*, *>
        assertEquals("high", reasoning["effort"])
        assertEquals("auto", reasoning["summary"])

        assertEquals("回复内容", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("fc_1", result.toolCalls[0].id)
        assertEquals("readFile", result.toolCalls[0].name)
        assertEquals(JsonPrimitive("/a"), result.toolCalls[0].arguments["path"])
        assertEquals("tool_calls", result.stopReason) // 响应含 function_call，优先按工具调用结束
        assertEquals(100, result.inputTokens)
        assertEquals(50, result.outputTokens)
        assertEquals(40, result.cachedInputTokens)
    }

    @Test
    fun responsesComplete_truncated_mapsToLength() = runTest {
        val api = api()
        coEvery { api.createResponses(any(), any(), any(), any()) } returns com.google.gson.JsonObject().apply {
            addProperty("status", "incomplete")
            add("incomplete_details", com.google.gson.JsonObject().apply {
                addProperty("reason", "max_output_tokens")
            })
            add("output", com.google.gson.JsonArray())
        }

        val result = adapter(api).apply { useResponseApi = true }.complete("", emptyList())

        assertEquals("length", result.stopReason)
    }

    // ── Chat Completions 流式 ─────────────────────────────────────────

    private fun sseBody(vararg lines: String): ResponseBody =
        (lines.joinToString("\n") + "\n").toResponseBody(null)

    @Test
    fun streamChat_collectsTextAndFinal() = runTest {
        val api = api()
        coEvery { api.streamChatCompletion(any(), any(), any(), any()) } returns sseBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"},\"finish_reason\":null}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: {\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6,\"prompt_tokens_details\":{\"cached_tokens\":4}}}",
            "data: [DONE]"
        )

        val chunks = adapter(api).completeStream("", emptyList()).toList()

        val text = chunks.filterIsInstance<AIStreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("你好", text)
        val final = chunks.filterIsInstance<AIStreamChunk.Final>().single()
        assertEquals("你好", final.response.content)
        assertEquals("stop", final.response.stopReason)
        assertEquals(12, final.response.inputTokens)
        assertEquals(6, final.response.outputTokens)
        assertEquals(4, final.response.cachedInputTokens)
    }

    @Test
    fun streamChat_assemblesStreamedToolCallFragments() = runTest {
        val api = api()
        coEvery { api.streamChatCompletion(any(), any(), any(), any()) } returns sseBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{\\\"pat\"}}]},\"finish_reason\":null}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"h\\\":\\\"/a\\\"}\"}}]},\"finish_reason\":null}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]"
        )

        val chunks = adapter(api).completeStream("", emptyList()).toList()

        val final = chunks.filterIsInstance<AIStreamChunk.Final>().single()
        assertEquals(1, final.response.toolCalls.size)
        assertEquals("call_1", final.response.toolCalls[0].id)
        assertEquals("readFile", final.response.toolCalls[0].name)
        val args = final.response.toolCalls[0].arguments
        assertTrue(args.containsKey("path"))
        assertEquals(JsonPrimitive("/a"), args["path"])
        assertEquals("tool_calls", final.response.stopReason)
    }

    @Test
    fun streamChat_midStreamError_doesNotAbortWholeStream() = runTest {
        val api = api()
        coEvery { api.streamChatCompletion(any(), any(), any(), any()) } returns sseBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"前\"},\"finish_reason\":null}]}",
            "data: {\"bogus\": true}", // 无 choices → 跳过
            "data: {\"choices\":[{\"delta\":{\"content\":\"后\"},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]"
        )

        val chunks = adapter(api).completeStream("", emptyList()).toList()

        val text = chunks.filterIsInstance<AIStreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("前后", text)
    }
}