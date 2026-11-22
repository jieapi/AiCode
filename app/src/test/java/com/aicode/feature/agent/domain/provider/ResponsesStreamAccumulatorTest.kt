package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesEvent
import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses 流式语义事件解析（[ResponsesStreamAccumulator]）。
 * 覆盖点：结束以 response.completed/incomplete/failed 判定（官方不发 `[DONE]`）、
 * function_call 分片入参聚合、截断映射为 `length` 以触发上层续写。
 */
class ResponsesStreamAccumulatorTest {

    /** 事件 JSON 用单引号书写，避免层层转义；解析前替换为双引号。 */
    private fun event(json: String): JsonObject =
        JsonParser.parseString(json.replace('\'', '"')).asJsonObject

    private fun functionCallItem(callId: String, name: String, arguments: String = ""): JsonObject =
        JsonObject().apply {
            addProperty("type", "function_call")
            addProperty("id", "fc_$callId")
            addProperty("call_id", callId)
            addProperty("name", name)
            addProperty("arguments", arguments)
        }

    private fun itemEvent(type: String, outputIndex: Int, item: JsonObject): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            addProperty("output_index", outputIndex)
            add("item", item)
        }

    private fun argsDelta(outputIndex: Int, delta: String): JsonObject =
        JsonObject().apply {
            addProperty("type", ResponsesEvent.FUNCTION_CALL_ARGS_DELTA)
            addProperty("output_index", outputIndex)
            addProperty("item_id", "fc_stream")
            addProperty("delta", delta)
        }

    private fun terminalEvent(
        type: String,
        status: String,
        output: JsonArray = JsonArray(),
        usage: JsonObject? = null,
        incompleteReason: String? = null,
        error: JsonObject? = null
    ): JsonObject {
        val response = JsonObject().apply {
            addProperty("status", status)
            addProperty("id", "resp_test")
            add("output", output)
            usage?.let { add("usage", it) }
            incompleteReason?.let {
                add("incomplete_details", JsonObject().apply { addProperty("reason", it) })
            }
            error?.let { add("error", it) }
        }
        return JsonObject().apply {
            addProperty("type", type)
            add("response", response)
        }
    }

    private fun usage(input: Int, output: Int, cached: Int): JsonObject = JsonObject().apply {
        addProperty("input_tokens", input)
        addProperty("output_tokens", output)
        add("input_tokens_details", JsonObject().apply { addProperty("cached_tokens", cached) })
    }

    @Test
    fun text_deltas_accumulate_and_completed_maps_to_stop() {
        val acc = ResponsesStreamAccumulator()

        val first = acc.accept(event("{'type':'response.output_text.delta','delta':'Hello'}"))
        assertEquals("Hello", (first as ResponsesDelta.Text).text)
        acc.accept(event("{'type':'response.output_text.delta','delta':' world'}"))
        assertFalse(acc.terminated)

        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", usage = usage(12, 3, 8)))

        assertTrue(acc.terminated)
        val response = acc.toResponse()
        assertEquals("Hello world", response.content)
        assertEquals("stop", response.stopReason)
        assertEquals("resp_test", response.responseId)
        assertEquals(12, response.inputTokens)
        assertEquals(3, response.outputTokens)
        assertEquals(8, response.cachedInputTokens)
        assertFalse(response.isTruncated)
    }

    @Test
    fun reasoning_deltas_are_separate_from_content() {
        val acc = ResponsesStreamAccumulator()

        val delta = acc.accept(event("{'type':'response.reasoning_text.delta','delta':'先看文件'}"))
        assertEquals("先看文件", (delta as ResponsesDelta.Reasoning).text)
        acc.accept(event("{'type':'response.output_text.delta','delta':'结论'}"))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val response = acc.toResponse()
        assertEquals("结论", response.content)
        assertEquals("先看文件", response.reasoning)
    }

    @Test
    fun ignored_events_produce_no_delta() {
        val acc = ResponsesStreamAccumulator()
        assertNull(acc.accept(event("{'type':'response.created'}")))
        assertNull(acc.accept(event("{'type':'response.in_progress'}")))
        assertNull(acc.accept(event("{'type':'response.output_text.done','text':'Hello'}")))
        assertFalse(acc.terminated)
    }

    @Test
    fun function_call_name_is_declared_before_arguments() {
        val acc = ResponsesStreamAccumulator()

        // 工具名先到：UI 靠它把「正在思考」提前换成「正在读取文件」
        val declared = acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 0, functionCallItem("c1", "readFile")))
        assertEquals("readFile", (declared as ResponsesDelta.ToolCallDeclared).name)

        // 参数片段不产生声明
        assertNull(acc.accept(argsDelta(0, "{\"path\":")))
        assertNull(acc.accept(argsDelta(0, "\"a.txt\"}")))
        // 同一个调用不重复播报：item.done 也带名字（它还会用完整参数覆盖累积值，见下）
        assertNull(
            acc.accept(
                itemEvent(
                    ResponsesEvent.OUTPUT_ITEM_DONE,
                    0,
                    functionCallItem("c1", "readFile", "{\"path\":\"a.txt\"}")
                )
            )
        )

        // 声明归声明：参数仍要完整聚合、工具调用不能被吞掉
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))
        val call = acc.toResponse().toolCalls.single()
        assertEquals("readFile", call.name)
        assertEquals(JsonPrimitive("a.txt"), call.arguments["path"])
    }

    @Test
    fun function_call_arguments_are_assembled_from_deltas() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 0, functionCallItem("call_abc", "readFile")))
        acc.accept(argsDelta(0, "{\"path\":"))
        acc.accept(argsDelta(0, "\"a.txt\"}"))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val response = acc.toResponse()
        assertEquals(1, response.toolCalls.size)
        val call = response.toolCalls.first()
        assertEquals("call_abc", call.id)
        assertEquals("readFile", call.name)
        assertEquals(JsonPrimitive("a.txt"), call.arguments["path"])
        // 有工具调用时 stopReason 必须是 tool_calls，Agent 循环才会继续执行工具
        assertEquals("tool_calls", response.stopReason)
    }

    @Test
    fun item_done_overrides_partial_arguments() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 0, functionCallItem("c1", "listFiles")))
        acc.accept(argsDelta(0, "{\"pa"))
        acc.accept(
            itemEvent(
                ResponsesEvent.OUTPUT_ITEM_DONE,
                0,
                functionCallItem("c1", "listFiles", "{\"path\":\".\"}")
            )
        )
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val call = acc.toResponse().toolCalls.single()
        assertEquals(JsonPrimitive("."), call.arguments["path"])
    }

    @Test
    fun parallel_calls_are_grouped_by_output_index() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 0, functionCallItem("c1", "readFile")))
        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 1, functionCallItem("c2", "listFiles")))
        acc.accept(argsDelta(0, "{\"path\":\"a.txt\"}"))
        acc.accept(argsDelta(1, "{\"path\":\".\"}"))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val calls = acc.toResponse().toolCalls
        assertEquals(listOf("c1", "c2"), calls.map { it.id })
        assertEquals(JsonPrimitive("a.txt"), calls[0].arguments["path"])
        assertEquals(JsonPrimitive("."), calls[1].arguments["path"])
    }

    @Test
    fun terminal_event_output_backfills_missing_stream_events() {
        val acc = ResponsesStreamAccumulator()

        val message = JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", "assistant")
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "output_text")
                    addProperty("text", "只在终止事件里出现的正文")
                })
            })
        }
        val output = JsonArray().apply {
            add(message)
            add(functionCallItem("c9", "readFile", "{\"path\":\"z.txt\"}"))
        }

        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", output = output))

        val response = acc.toResponse()
        assertEquals("只在终止事件里出现的正文", response.content)
        assertEquals("c9", response.toolCalls.single().id)
        assertEquals(JsonPrimitive("z.txt"), response.toolCalls.single().arguments["path"])
    }

    @Test
    fun streamed_call_is_not_duplicated_by_terminal_output() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_ADDED, 0, functionCallItem("c1", "readFile")))
        acc.accept(argsDelta(0, "{\"path\":\"a.txt\"}"))
        val output = JsonArray().apply { add(functionCallItem("c1", "readFile", "{\"path\":\"a.txt\"}")) }
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", output = output))

        assertEquals(1, acc.toResponse().toolCalls.size)
    }

    @Test
    fun incomplete_by_max_output_tokens_maps_to_length() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(event("{'type':'response.output_text.delta','delta':'半截'}"))
        acc.accept(
            terminalEvent(
                ResponsesEvent.INCOMPLETE,
                status = "incomplete",
                incompleteReason = "max_output_tokens"
            )
        )

        val response = acc.toResponse()
        assertTrue(acc.terminated)
        assertEquals("length", response.stopReason)
        // isTruncated 为 true 才会触发 Agent 循环自动续写
        assertTrue(response.isTruncated)
    }

    @Test
    fun incomplete_by_max_tokens_also_maps_to_length() {
        val acc = ResponsesStreamAccumulator()

        // OpenAI 官方文档示例的截断原因写作 max_tokens，与 DeepSeek 的 max_output_tokens 不同
        acc.accept(
            terminalEvent(
                ResponsesEvent.INCOMPLETE,
                status = "incomplete",
                incompleteReason = "max_tokens"
            )
        )

        assertEquals("length", acc.toResponse().stopReason)
        assertTrue(acc.toResponse().isTruncated)
    }

    @Test
    fun incomplete_by_other_reason_keeps_status() {
        val acc = ResponsesStreamAccumulator()

        acc.accept(
            terminalEvent(
                ResponsesEvent.INCOMPLETE,
                status = "incomplete",
                incompleteReason = "content_filter"
            )
        )

        val response = acc.toResponse()
        assertEquals("incomplete", response.stopReason)
        assertFalse(response.isTruncated)
    }

    @Test
    fun failed_event_throws_stream_api_exception() {
        val acc = ResponsesStreamAccumulator()
        val error = JsonObject().apply {
            addProperty("code", "server_error")
            addProperty("message", "upstream exploded")
        }

        val thrown = runCatching {
            acc.accept(terminalEvent(ResponsesEvent.FAILED, "failed", error = error))
        }.exceptionOrNull()

        assertTrue(thrown is StreamApiException)
        assertEquals("server_error", (thrown as StreamApiException).code)
        assertEquals("upstream exploded", thrown.message)
    }

    @Test
    fun transport_error_event_throws_stream_api_exception() {
        val acc = ResponsesStreamAccumulator()

        val thrown = runCatching {
            acc.accept(event("{'type':'error','code':'rate_limit_exceeded','message':'slow down','param':null}"))
        }.exceptionOrNull()

        assertTrue(thrown is StreamApiException)
        assertEquals("rate_limit_exceeded", (thrown as StreamApiException).code)
        assertEquals("slow down", thrown.message)
    }

    @Test
    fun response_error_event_variant_also_throws() {
        val acc = ResponsesStreamAccumulator()

        val thrown = runCatching {
            acc.accept(event("{'type':'response.error','code':'server_error','message':'boom'}"))
        }.exceptionOrNull()

        assertTrue(thrown is StreamApiException)
        assertEquals("server_error", (thrown as StreamApiException).code)
    }

    @Test
    fun reasoning_summary_delta_is_treated_as_reasoning() {
        val acc = ResponsesStreamAccumulator()

        val delta = acc.accept(event("{'type':'response.reasoning_summary_text.delta','delta':'摘要思考'}"))

        assertEquals("摘要思考", (delta as ResponsesDelta.Reasoning).text)
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))
        assertEquals("摘要思考", acc.toResponse().reasoning)
    }

    @Test
    fun reasoning_item_summary_is_parsed_from_terminal_output() {
        val acc = ResponsesStreamAccumulator()

        val reasoningItem = JsonObject().apply {
            addProperty("type", "reasoning")
            addProperty("id", "rs_1")
            add("content", JsonNull.INSTANCE)
            add("summary", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "summary_text")
                    addProperty("text", "先读文件再修改")
                })
            })
        }
        val output = JsonArray().apply { add(reasoningItem) }

        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", output = output))

        assertEquals("先读文件再修改", acc.toResponse().reasoning)
    }

    @Test
    fun terminal_event_without_output_still_terminates() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'已输出'}"))

        // 部分服务在终止事件里不带 output（或给 null），不能因此认为流未结束
        val terminal = JsonObject().apply {
            addProperty("type", ResponsesEvent.COMPLETED)
            add("response", JsonObject().apply {
                addProperty("status", "completed")
                add("output", JsonNull.INSTANCE)
            })
        }
        acc.accept(terminal)

        assertTrue(acc.terminated)
        assertEquals("已输出", acc.toResponse().content)
        assertEquals("stop", acc.toResponse().stopReason)
    }

    @Test
    fun refusal_delta_is_surfaced_as_content() {
        val acc = ResponsesStreamAccumulator()
        // 拒答不走 output_text：不收这个事件的话用户只能看到空白回复
        acc.accept(event("{'type':'response.refusal.delta','delta':'抱歉，我不能'}"))
        acc.accept(event("{'type':'response.refusal.delta','delta':'帮助这个请求。'}"))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        assertEquals("抱歉，我不能帮助这个请求。", acc.toResponse().content)
    }

    @Test
    fun refusal_part_in_output_is_surfaced_as_content() {
        val acc = ResponsesStreamAccumulator()
        val messageItem = JsonObject().apply {
            addProperty("type", "message")
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "refusal")
                    addProperty("refusal", "抱歉，我不能帮助这个请求。")
                })
            })
        }
        val output = JsonArray().apply { add(messageItem) }

        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", output = output))

        assertEquals("抱歉，我不能帮助这个请求。", acc.toResponse().content)
    }

    @Test
    fun reasoning_encrypted_content_in_stream_is_captured_in_thinking_blocks_json() {
        val acc = ResponsesStreamAccumulator()

        val reasoningItem = JsonObject().apply {
            addProperty("type", "reasoning")
            addProperty("encrypted_content", "gAAAAABtest_stream")
            add("summary", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "summary_text")
                    addProperty("text", "思考总结")
                })
            })
        }
        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_DONE, 0, reasoningItem))
        acc.accept(event("{'type':'response.reasoning_summary_text.delta','delta':'思考总结'}"))
        acc.accept(event("{'type':'response.output_text.delta','delta':'正文'}"))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val resp = acc.toResponse()
        assertEquals("正文", resp.content)
        assertEquals("思考总结", resp.reasoning)
        assertTrue(resp.thinkingBlocksJson != null && resp.thinkingBlocksJson!!.contains("gAAAAABtest_stream"))
    }

    private fun imageItem(id: String, result: String, outputFormat: String = "png"): JsonObject =
        JsonObject().apply {
            addProperty("type", ResponsesItem.IMAGE_GENERATION_CALL)
            addProperty("id", id)
            addProperty("status", "completed")
            addProperty("result", result)
            addProperty("output_format", outputFormat)
        }

    @Test
    fun image_generation_call_in_stream_is_surfaced() {
        val acc = ResponsesStreamAccumulator()

        // added 事件只有 in_progress 状态、没有 result，不应产生图片
        acc.accept(
            itemEvent(
                ResponsesEvent.OUTPUT_ITEM_ADDED,
                0,
                JsonObject().apply {
                    addProperty("type", ResponsesItem.IMAGE_GENERATION_CALL)
                    addProperty("id", "ig_1")
                    addProperty("status", "in_progress")
                }
            )
        )
        // done 事件携带完整 base64 结果
        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_DONE, 0, imageItem("ig_1", "iVBORw0KGgoAAAANS")))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        val response = acc.toResponse()
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images.single().mimeType)
        assertEquals("iVBORw0KGgoAAAANS", response.images.single().base64Data)
        assertEquals("stop", response.stopReason)
    }

    @Test
    fun image_generation_call_format_maps_to_mime() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_DONE, 0, imageItem("ig_jpg", "QUJD", outputFormat = "jpeg")))
        acc.accept(itemEvent(ResponsesEvent.OUTPUT_ITEM_DONE, 1, imageItem("ig_webp", "QUJD", outputFormat = "webp")))
        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed"))

        assertEquals(listOf("image/jpeg", "image/webp"), acc.toResponse().images.map { it.mimeType })
    }

    @Test
    fun image_generation_call_in_terminal_output_backfills() {
        val acc = ResponsesStreamAccumulator()
        val output = JsonArray().apply { add(imageItem("ig_final", "QUJDRA==")) }

        acc.accept(terminalEvent(ResponsesEvent.COMPLETED, "completed", output = output))

        val response = acc.toResponse()
        assertEquals(1, response.images.size)
        assertEquals("QUJDRA==", response.images.single().base64Data)
    }
}
