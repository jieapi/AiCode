package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API 请求体构造（[buildResponsesTools] / [buildResponsesInput]）。
 * 覆盖点即两类曾导致 400 的写法：工具定义嵌套 `function`、工具往返仍用 Chat Completions 的
 * assistant.tool_calls + role:"tool" 消息。
 */
class ResponsesPayloadTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "desc",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    private fun call(id: String, name: String, path: String) =
        ToolCall(id = id, name = name, arguments = mapOf("path" to JsonPrimitive(path)))

    @Suppress("UNCHECKED_CAST")
    private fun parts(value: Any?): List<Map<String, Any>> = value as List<Map<String, Any>>

    @Test
    fun tool_definition_is_flat_without_function_wrapper() {
        val defs = buildResponsesTools(listOf(FakeTool("readFile")))
        assertEquals(1, defs?.size)
        val json = Gson().toJsonTree(defs!!.first()).asJsonObject
        assertEquals("function", json.get("type").asString)
        assertEquals("readFile", json.get("name").asString)
        assertTrue(json.has("description"))
        assertTrue(json.has("parameters"))
        // strict 在官方 schema 里不带 optional 标记，必须出现；工具 schema 不保证严格子集，固定 false
        assertFalse(json.get("strict").asBoolean)
        // 嵌套写法会被服务端以「tools[0]: missing field `name`」拒绝
        assertFalse(json.has("function"))
    }

    @Test
    fun no_tools_means_no_tools_field() {
        assertNull(buildResponsesTools(emptyList()))
    }

    @Test
    fun blank_system_prompt_adds_no_item() {
        val items = buildResponsesInput("", "system", emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun tool_round_trip_becomes_function_call_and_output_items() {
        val items = buildResponsesInput(
            systemPrompt = "you are helpful",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(id = "u1", content = "读一下 a.txt"),
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "好的",
                    toolCalls = listOf(call("call_1", "readFile", "a.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "call_1", toolName = "readFile", result = "file body")
            )
        )

        assertEquals(5, items.size)
        assertEquals("system", items[0]["role"])
        assertEquals("you are helpful", items[0]["content"])
        assertEquals("user", items[1]["role"])
        assertEquals("读一下 a.txt", items[1]["content"])

        assertEquals("assistant", items[2]["role"])
        val assistantParts = parts(items[2]["content"])
        assertEquals(ResponsesPart.OUTPUT_TEXT, assistantParts[0]["type"])
        assertEquals("好的", assistantParts[0]["text"])

        assertEquals(ResponsesItem.FUNCTION_CALL, items[3]["type"])
        assertEquals("call_1", items[3]["call_id"])
        assertEquals("readFile", items[3]["name"])
        assertEquals("""{"path":"a.txt"}""", items[3]["arguments"])

        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[4]["type"])
        assertEquals("call_1", items[4]["call_id"])
        assertEquals("file body", items[4]["output"])
    }

    @Test
    fun assistant_without_text_emits_only_function_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "listFiles", "."))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "listFiles", result = "a.txt")
            )
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.FUNCTION_CALL, items[0]["type"])
        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[1]["type"])
    }

    @Test
    fun websocket_continuation_input_contains_only_new_user_and_tool_items() {
        val continuation = buildResponsesContinuationInput(
            listOf(
                AgentMessage.UserMessage(id = "u2", content = "继续"),
                AgentMessage.AssistantMessage(id = "a2", content = "先查一下"),
                AgentMessage.ToolResultMessage(id = "call_2", toolName = "readFile", result = "新文件内容")
            )
        )
        assertEquals(2, continuation.size)
        assertEquals("user", continuation[0]["role"])
        assertEquals("继续", continuation[0]["content"])
        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, continuation[1]["type"])
        assertEquals("call_2", continuation[1]["call_id"])
        assertEquals("新文件内容", continuation[1]["output"])
    }

    @Test
    fun continuation_uses_only_messages_after_the_matching_snapshot() {
        val first = AgentMessage.UserMessage(id = "u1", content = "开始")
        val assistant = AgentMessage.AssistantMessage(id = "a1", content = "调用工具")
        val tool = AgentMessage.ToolResultMessage(id = "call_1", toolName = "readFile", result = "结果")
        val prior = listOf(first, assistant)
        val result = prepareResponsesContinuationInput(
            fullInput = listOf(mapOf("role" to "user", "content" to "完整历史")),
            messages = prior + tool,
            submittedMessages = prior,
            previousResponseId = "resp_prev"
        )
        assertTrue(result.canContinue)
        assertEquals("resp_prev", result.previousResponseId)
        assertEquals(1, result.input.size)
        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, result.input.single()["type"])
        assertEquals("call_1", result.input.single()["call_id"])
    }

    @Test
    fun continuation_falls_back_to_full_input_when_compaction_changes_history() {
        val result = prepareResponsesContinuationInput(
            fullInput = listOf(mapOf("role" to "user", "content" to "压缩后的上下文")),
            messages = listOf(AgentMessage.UserMessage(id = "new", content = "压缩后的上下文")),
            submittedMessages = listOf(AgentMessage.UserMessage(id = "old", content = "旧上下文")),
            previousResponseId = "resp_old"
        )
        assertFalse(result.canContinue)
        assertNull(result.previousResponseId)
        assertEquals("压缩后的上下文", result.input.single()["content"])
    }

    @Test
    fun continuation_input_keeps_new_image_user_content() {
        val continuation = buildResponsesContinuationInput(
            listOf(
                AgentMessage.UserMessage(
                    id = "u2",
                    content = "比较这张图",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "PIC"))
                )
            )
        )
        val content = parts(continuation.single()["content"])
        assertEquals(ResponsesPart.INPUT_TEXT, content[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, content[1]["type"])
    }

    @Test
    fun continuation_input_preserves_images_from_new_tool_results() {
        val continuation = buildResponsesContinuationInput(
            listOf(
                AgentMessage.ToolResultMessage(
                    id = "call_1",
                    toolName = "viewImage",
                    result = "图像分析",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "DATA"))
                )
            )
        )
        val output = parts(continuation.single()["output"])
        assertEquals(ResponsesPart.INPUT_TEXT, output[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, output[1]["type"])
    }

    @Test
    fun orphan_tool_result_is_dropped() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.ToolResultMessage(id = "ghost", toolName = "readFile", result = "body"),
                AgentMessage.UserMessage(id = "u1", content = "hi")
            )
        )
        assertEquals(1, items.size)
        assertEquals("user", items[0]["role"])
    }

    @Test
    fun call_without_result_is_dropped_together_with_the_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "要执行两个工具",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        // assistant 文本 + c1 的调用与结果；被用户拒绝执行的 c2 不出现，避免上游报缺少工具响应
        assertEquals(3, items.size)
        assertEquals("c1", items[1]["call_id"])
        assertEquals("c1", items[2]["call_id"])
        assertFalse(items.any { it["call_id"] == "c2" })
    }

    @Test
    fun out_of_order_results_are_paired_by_call_id() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "body b"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        assertEquals(4, items.size)
        assertEquals(listOf("c1", "c2", "c1", "c2"), items.map { it["call_id"] })
        assertEquals("body a", items[2]["output"])
        assertEquals("body b", items[3]["output"])
    }

    @Test
    fun multiple_calls_in_one_turn_are_written_before_their_outputs() {
        // 调用/结果交替会让服务端把第二个起的调用归到新的 assistant 轮（中间隔了
        // function_call_output），DeepSeek 思考模式下那些轮没有 reasoning 会直接 400
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "并行读三个文件",
                    toolCalls = listOf(
                        call("c1", "readFile", "a.txt"),
                        call("c2", "readFile", "b.txt"),
                        call("c3", "readFile", "c.txt")
                    )
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "b"),
                AgentMessage.ToolResultMessage(id = "c3", toolName = "readFile", result = "c")
            )
        )
        assertEquals(
            listOf(
                "assistant", ResponsesItem.FUNCTION_CALL, ResponsesItem.FUNCTION_CALL, ResponsesItem.FUNCTION_CALL,
                ResponsesItem.FUNCTION_CALL_OUTPUT, ResponsesItem.FUNCTION_CALL_OUTPUT, ResponsesItem.FUNCTION_CALL_OUTPUT
            ),
            items.map { it["type"] ?: it["role"] }
        )
        assertEquals(listOf("c1", "c2", "c3"), items.drop(4).map { it["call_id"] })
    }

    @Test
    fun user_images_become_input_image_parts() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(
                    id = "u1",
                    content = "这是什么",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "AAAA"))
                )
            )
        )
        val content = parts(items[0]["content"])
        assertEquals(ResponsesPart.INPUT_TEXT, content[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, content[1]["type"])
        assertEquals("data:image/png;base64,AAAA", content[1]["image_url"])
    }

    @Test
    fun tool_result_images_become_input_image_parts_in_output() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "viewImage", "a.png"))),
                AgentMessage.ToolResultMessage(
                    id = "c1",
                    toolName = "viewImage",
                    result = "截图如下",
                    images = listOf(AgentImage(mimeType = "image/jpeg", base64Data = "BBBB"))
                )
            )
        )
        val output = parts(items[1]["output"])
        assertEquals(ResponsesPart.INPUT_TEXT, output[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, output[1]["type"])
        assertEquals("data:image/jpeg;base64,BBBB", output[1]["image_url"])
    }

    @Test
    fun developer_role_is_used_for_reasoning_models() {
        val items = buildResponsesInput("prompt", "developer", emptyList())
        assertEquals("developer", items[0]["role"])
    }

    @Test
    fun reasoning_is_sent_back_as_item_before_assistant_content() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "先读文件",
                    reasoning = "得先看看文件内容",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body")
            ),
            includeReasoningItems = true
        )
        // 思考 item 必须紧贴在所属 assistant 内容之前，服务端才能归并到同一轮
        assertEquals(4, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        val reasoningParts = parts(items[0]["content"])
        assertEquals(ResponsesPart.REASONING_TEXT, reasoningParts[0]["type"])
        assertEquals("得先看看文件内容", reasoningParts[0]["text"])
        assertEquals("assistant", items[1]["role"])
        assertEquals(ResponsesItem.FUNCTION_CALL, items[2]["type"])
    }

    @Test
    fun reasoning_is_not_sent_back_by_default() {
        // 官方路径：reasoning item 需 id / summary / encrypted_content，只有明文反而会被拒，因此不发
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "好的", reasoning = "先想一下")
            )
        )
        assertEquals(1, items.size)
        assertFalse(items.any { it["type"] == ResponsesItem.REASONING })
    }

    @Test
    fun empty_reasoning_gets_placeholder_item_when_included() {
        // DeepSeek 思考模式：请求带 tools 时每轮 assistant 都要有 reasoning，未存思考则发空文本
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(AgentMessage.AssistantMessage(id = "a1", content = "好的")),
            includeReasoningItems = true
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        assertEquals("", parts(items[0]["content"])[0]["text"])
    }

    @Test
    fun assistant_with_nothing_to_send_gets_no_reasoning_item() {
        // 无正文、且唯一调用因缺结果被裁掉（用户拒绝执行）：整轮不写 item，
        // 否则 reasoning 会成为无相邻 assistant 消息可归并的孤立 item
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "",
                    reasoning = "打算调个工具",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"))
                )
            ),
            includeReasoningItems = true
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun encrypted_reasoning_snapshot_is_sent_back_when_valid() {
        // OpenAI 官方/通用 Responses 规范：当存有包含有效 encrypted_content 的快照时，回传合法 reasoning item
        val snapshot = """{"type":"reasoning","encrypted_content":"gAAAAABtest","summary":[{"type":"summary_text","text":"已完成分析"}]}"""
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "最终回复",
                    thinkingBlocksJson = snapshot
                )
            )
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        assertEquals("gAAAAABtest", items[0]["encrypted_content"])
        @Suppress("UNCHECKED_CAST")
        val summary = items[0]["summary"] as List<Map<String, Any>>
        assertEquals(1, summary.size)
        assertEquals("已完成分析", summary[0]["text"])
        assertEquals("assistant", items[1]["role"])
    }

    @Test
    fun reasoning_snapshot_without_encrypted_content_is_safely_ignored() {
        // 若快照缺少 encrypted_content，防御性过滤掉，绝不回传残缺项（防止 400）
        val invalidSnapshot = """{"type":"reasoning","encrypted_content":"","summary":[]}"""
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "最终回复",
                    thinkingBlocksJson = invalidSnapshot
                )
            )
        )
        assertEquals(1, items.size)
        assertEquals("assistant", items[0]["role"])
    }
}
