package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHttpServiceTest {

    private val pluginId = "demo.http"

    private fun service(active: Boolean = true): PluginHttpService {
        val plugin = mockk<InstalledPlugin> {
            every { id } returns pluginId
            every { isActive } returns active
        }
        val pluginManager = mockk<PluginManager>(relaxed = true) {
            every { plugins } returns MutableStateFlow(listOf(plugin))
        }
        return PluginHttpService(pluginManager = pluginManager, client = OkHttpClient())
    }

    private fun urlParams(url: String): JsonObject = buildJsonObject { put("url", url) }

    private fun errorOf(block: () -> Unit): PluginHostException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected PluginHostException, got $error", error is PluginHostException)
        return error as PluginHostException
    }

    @Test
    fun request_rejectsInactivePluginBeforeValidation() = runTest {
        val error = runCatching {
            service(active = false).request(pluginId, buildJsonObject {})
        }.exceptionOrNull()

        assertTrue(error is PluginHostException)
        assertEquals("PLUGIN_INACTIVE", (error as PluginHostException).code)
    }

    @Test
    fun request_requiresUrl() {
        val error = errorOf { service().parseRequest(buildJsonObject { put("method", "GET") }) }

        assertEquals("PARAM_MISSING", error.code)
    }

    @Test
    fun request_rejectsNonHttpUrls() {
        listOf("file:///etc/passwd", "ftp://example.com/a", "data:text/plain,hi", "example.com").forEach { url ->
            val error = errorOf { service().parseRequest(urlParams(url)) }
            assertEquals(url, "PARAM_INVALID", error.code)
        }
    }

    @Test
    fun request_rejectsMalformedUrl() {
        // 非法百分号转义在主机名里会被 OkHttp 拒绝。
        val error = errorOf { service().parseRequest(urlParams("https://exa%zzmple.com")) }

        assertEquals("PARAM_INVALID", error.code)
    }

    @Test
    fun request_acceptsUrlAt4096Chars() {
        val prefix = "https://example.com/"
        val maxUrl = prefix + "a".repeat(4096 - prefix.length)
        assertEquals(4096, maxUrl.length)

        val spec = service().parseRequest(urlParams(maxUrl))
        assertEquals(maxUrl, spec.url.toString())

        val tooLong = prefix + "a".repeat(4097 - prefix.length)
        assertEquals("PARAM_INVALID", errorOf { service().parseRequest(urlParams(tooLong)) }.code)
    }

    @Test
    fun request_defaultsToGetWith30Seconds() {
        val spec = service().parseRequest(urlParams("https://example.com/api"))

        assertEquals("GET", spec.method)
        assertNull(spec.body)
        assertEquals(30L, spec.timeoutSeconds)
        assertTrue(spec.headers.isEmpty())
    }

    @Test
    fun request_normalizesMethodCase() {
        val spec = service().parseRequest(
            buildJsonObject {
                put("url", "https://example.com/api")
                put("method", " post ")
            }
        )

        assertEquals("POST", spec.method)
    }

    @Test
    fun request_rejectsUnsupportedMethod() {
        val error = errorOf {
            service().parseRequest(
                buildJsonObject {
                    put("url", "https://example.com/api")
                    put("method", "TRACE")
                }
            )
        }

        assertEquals("PARAM_INVALID", error.code)
    }

    @Test
    fun request_rejectsBodyOnGetAndHead() {
        listOf("GET", "HEAD", "get").forEach { method ->
            val error = errorOf {
                service().parseRequest(
                    buildJsonObject {
                        put("url", "https://example.com/api")
                        put("method", method)
                        put("body", "hi")
                    }
                )
            }
            assertEquals(method, "PARAM_INVALID", error.code)
        }
    }

    @Test
    fun request_acceptsBodyUpToOneMiB() {
        val maxBody = "a".repeat(1024 * 1024)
        val spec = service().parseRequest(
            buildJsonObject {
                put("url", "https://example.com/api")
                put("method", "POST")
                put("body", maxBody)
            }
        )
        assertEquals(maxBody.length, spec.body!!.length)
        assertNull(spec.headers["Content-Type"])

        val asciiTooLarge = maxBody + "a"
        assertEquals(
            "PARAM_INVALID",
            errorOf {
                service().parseRequest(
                    buildJsonObject {
                        put("url", "https://example.com/api")
                        put("method", "POST")
                        put("body", asciiTooLarge)
                    }
                )
            }.code
        )

        // 上限按 UTF-8 字节计算：40 万个汉字约 1.14 MiB，字符数不足 1 MiB 也应拒绝。
        val multibyte = "字".repeat(400_000)
        assertEquals(
            "PARAM_INVALID",
            errorOf {
                service().parseRequest(
                    buildJsonObject {
                        put("url", "https://example.com/api")
                        put("method", "PUT")
                        put("body", multibyte)
                    }
                )
            }.code
        )
    }

    @Test
    fun request_acceptsValidHeaders() {
        val spec = service().parseRequest(
            buildJsonObject {
                put("url", "https://example.com/api")
                put(
                    "headers",
                    buildJsonObject {
                        put("Accept", "application/json")
                        put("X-Trace-Id", "abc-123")
                        put("X-Note", "a\tb")
                    }
                )
            }
        )

        assertEquals("application/json", spec.headers["Accept"])
        assertEquals("abc-123", spec.headers["X-Trace-Id"])
        assertEquals("a\tb", spec.headers["X-Note"])
    }

    @Test
    fun request_rejectsInvalidHeaders() {
        fun invalid(headers: JsonElement): String = errorOf {
            service().parseRequest(
                buildJsonObject {
                    put("url", "https://example.com/api")
                    put("headers", headers)
                }
            )
        }.code

        assertEquals("PARAM_INVALID", invalid(JsonPrimitive("nope")))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { repeat(65) { put("X-$it", "v") } }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("X_Token", "v") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("A".repeat(65), "v") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("Host", "evil.example") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("content-length", "10") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("Transfer-Encoding", "chunked") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("Authorization", 1) }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("Authorization", "a\r\nX-Evil: 1") }))
        assertEquals("PARAM_INVALID", invalid(buildJsonObject { put("Authorization", "令牌") }))
    }

    @Test
    fun request_validatesTimeoutRange() {
        fun timeout(value: JsonElement): PluginHostException = errorOf {
            service().parseRequest(
                buildJsonObject {
                    put("url", "https://example.com/api")
                    put("timeout", value)
                }
            )
        }

        assertEquals("PARAM_INVALID", timeout(JsonPrimitive(0)).code)
        assertEquals("PARAM_INVALID", timeout(JsonPrimitive(61)).code)
        assertEquals("PARAM_INVALID", timeout(JsonPrimitive("soon")).code)
        assertEquals("PARAM_INVALID", timeout(JsonPrimitive(true)).code)

        val one = service().parseRequest(
            buildJsonObject {
                put("url", "https://example.com/api")
                put("timeout", 1)
            }
        )
        assertEquals(1L, one.timeoutSeconds)

        val sixty = service().parseRequest(
            buildJsonObject {
                put("url", "https://example.com/api")
                put("timeout", 60)
            }
        )
        assertEquals(60L, sixty.timeoutSeconds)
    }

    @Test
    fun buildResponse_marksTruncatedBody() {
        val body = "a".repeat(1024 * 1024 + 1).toByteArray()

        val result = service().buildResponse(200, emptyMap(), body)

        assertEquals(200, result["status"]!!.jsonPrimitive.int)
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(1024 * 1024, result["body"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun buildResponse_keepsStatusHeadersAndBody() {
        val body = """{"ok":true}""".toByteArray()

        val result = service().buildResponse(
            status = 201,
            headers = mapOf("Content-Type" to "application/json", "X-A" to "1"),
            body = body
        )

        assertEquals(201, result["status"]!!.jsonPrimitive.int)
        assertFalse(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("""{"ok":true}""", result["body"]!!.jsonPrimitive.content)
        val headers = result["headers"]!!.jsonObject
        assertEquals("application/json", headers["Content-Type"]!!.jsonPrimitive.content)
        assertEquals("1", headers["X-A"]!!.jsonPrimitive.content)
    }
}
