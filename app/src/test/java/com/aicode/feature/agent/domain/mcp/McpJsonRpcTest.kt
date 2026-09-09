package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP JSON-RPC 2.0 报文模型的序列化语义。
 *
 * 线上实际使用 StdioTransport / StreamableHttpTransport 中的 Json 配置
 * （ignoreUnknownKeys + encodeDefaults + explicitNulls=false），
 * 测试与之保持一致；默认 Json（encodeDefaults=false）的行为单独验证，
 * 确保默认值（jsonrpc="2.0"）不会被意外写出。
 */
class McpJsonRpcTest {

    /** 与 MCP 传输层一致的线上配置。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** kotlinx.serialization 默认配置：encodeDefaults=false。 */
    private val defaultJson = Json

    // ---- JsonRpcRequest ----

    @Test
    fun request_roundtrip_keepsAllFields() {
        val request = JsonRpcRequest(
            id = 7,
            method = "tools/call",
            params = JsonObject(mapOf("name" to JsonPrimitive("echo")))
        )

        val decoded = json.decodeFromString<JsonRpcRequest>(json.encodeToString(request))

        assertEquals(request, decoded)
    }

    @Test
    fun request_encodeDefaults_trueWritesJsonrpcVersion() {
        val request = JsonRpcRequest(id = 1, method = "initialize")

        val encoded = json.encodeToString(request)

        assertTrue("线上配置应写出 jsonrpc 默认值", encoded.contains("\"jsonrpc\":\"2.0\""))
    }

    @Test
    fun request_defaultJson_omitsJsonrpcDefaultValue() {
        val request = JsonRpcRequest(id = 1, method = "initialize")

        val encoded = defaultJson.encodeToString(request)

        assertFalse("默认 Json 不应写出默认值", encoded.contains("jsonrpc"))
    }

    @Test
    fun request_decodeWithoutJsonrpc_defaultsTo20() {
        val decoded = json.decodeFromString<JsonRpcRequest>("""{"id":1,"method":"ping"}""")

        assertEquals(JSONRPC_VERSION, decoded.jsonrpc)
    }

    @Test
    fun request_nullParams_omittedAndDecodesToNull() {
        val encoded = json.encodeToString(JsonRpcRequest(id = 1, method = "ping"))

        assertFalse("explicitNulls=false 时不写 params 键", encoded.contains("params"))
        assertNull(json.decodeFromString<JsonRpcRequest>(encoded).params)
    }

    // ---- JsonRpcNotification ----

    @Test
    fun notification_hasNoId_andOptionalParams() {
        val encoded = json.encodeToString(JsonRpcNotification(method = "notifications/initialized"))

        assertFalse("通知没有 id", encoded.contains("\"id\""))
        assertFalse("params 为空时不写出", encoded.contains("params"))

        val decoded = json.decodeFromString<JsonRpcNotification>(
            """{"method":"notifications/cancelled","params":{"requestId":5}}"""
        )
        assertEquals(
            JsonRpcNotification(
                method = "notifications/cancelled",
                params = JsonObject(mapOf("requestId" to JsonPrimitive(5)))
            ),
            decoded
        )
    }

    // ---- JsonRpcResponse ----
    // data class 本身不校验 result/error 互斥，互斥体现在序列化层：
    // explicitNulls=false 时，未设置的一侧不写出；解码时两侧独立还原。

    @Test
    fun response_resultOnly_omitsError() {
        val response = JsonRpcResponse(
            id = 3,
            result = JsonObject(mapOf("ok" to JsonPrimitive(true)))
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"result\""))
        assertFalse("error 为空时不写出", encoded.contains("\"error\""))

        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)
        assertEquals(response, decoded)
        assertNull(decoded.error)
    }

    @Test
    fun response_errorOnly_omitsResult() {
        val response = JsonRpcResponse(
            id = 3,
            error = JsonRpcError(code = -32601, message = "Method not found")
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"error\""))
        assertFalse("result 为空时不写出", encoded.contains("\"result\""))

        val decoded = json.decodeFromString<JsonRpcResponse>(encoded)
        assertEquals(response, decoded)
        assertNull(decoded.result)
    }

    @Test
    fun response_nullableId_roundtrip() {
        val noId = json.decodeFromString<JsonRpcResponse>("""{"result":{}}""")
        assertNull(noId.id)

        val withId = json.decodeFromString<JsonRpcResponse>("""{"id":42,"error":{"code":1,"message":"x"}}""")
        assertEquals(42L, withId.id)
        assertEquals(42L, json.decodeFromString<JsonRpcResponse>(json.encodeToString(withId)).id)
    }

    // ---- JsonRpcError ----

    @Test
    fun error_roundtrip_withAndWithoutData() {
        val withData = JsonRpcError(
            code = -32602,
            message = "Invalid params",
            data = JsonPrimitive("bad field")
        )
        assertEquals(withData, json.decodeFromString<JsonRpcError>(json.encodeToString(withData)))

        val withoutData = json.decodeFromString<JsonRpcError>("""{"code":-32000,"message":"boom"}""")
        assertNull(withoutData.data)
    }

    @Test
    fun error_nullData_omittedFromEncoded() {
        val encoded = json.encodeToString(JsonRpcError(code = -32700, message = "Parse error"))

        assertFalse(encoded.contains("data"))
    }

    // ---- McpToolDescriptor ----

    @Test
    fun toolDescriptor_serialName_isInputSchema() {
        val tool = McpToolDescriptor(
            name = "read_file",
            description = "读取文件",
            inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        )

        val encoded = json.encodeToString(tool)

        assertTrue("字段名必须是 inputSchema（snake_case 约定）", encoded.contains("\"inputSchema\""))
        assertFalse(encoded.contains("input_schema"))

        val decoded = json.decodeFromString<McpToolDescriptor>(
            """{"name":"read_file","description":"读取文件","inputSchema":{"type":"object"}}"""
        )
        assertEquals(tool, decoded)
    }

    @Test
    fun toolDescriptor_wrongKeyName_isNotBound() {
        // 只有 @SerialName("inputSchema") 命中的键才会绑定，input_schema 不会
        val decoded = json.decodeFromString<McpToolDescriptor>(
            """{"name":"read_file","input_schema":{"type":"object"}}"""
        )

        assertNull(decoded.inputSchema)
    }

    @Test
    fun toolDescriptor_nullableDescription_roundtrip() {
        val decoded = json.decodeFromString<McpToolDescriptor>("""{"name":"cmd"}""")

        assertNull(decoded.description)
        assertNull(decoded.inputSchema)
    }

    // ---- McpToolsListResult ----

    @Test
    fun toolsListResult_decodeMissingFields_useDefaults() {
        val decoded = json.decodeFromString<McpToolsListResult>("{}")

        assertTrue(decoded.tools.isEmpty())
        assertNull(decoded.nextCursor)
    }

    @Test
    fun toolsListResult_roundtrip_withExplicitValues() {
        val result = McpToolsListResult(
            tools = listOf(McpToolDescriptor(name = "cmd", inputSchema = JsonObject(emptyMap()))),
            nextCursor = "abc123"
        )

        assertEquals(result, json.decodeFromString<McpToolsListResult>(json.encodeToString(result)))
    }

    @Test
    fun toolsListResult_defaultEmptyList_writtenWithEncodeDefaults() {
        val encoded = json.encodeToString(McpToolsListResult())

        assertTrue("encodeDefaults=true 时应写出默认空列表", encoded.contains("\"tools\":[]"))
        assertFalse("nextCursor 为 null 时不写出", encoded.contains("nextCursor"))
    }
}