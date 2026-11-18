package com.aicode.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志媒体脱敏（[AILogger]）：请求/响应体里的图片 base64 必须在写盘前被替换为占位符，
 * 尤其要覆盖 OpenAI `image_url.url` 这种嵌套 data URL——旧的全文正则对它是漏网的。
 */
class AILoggerRedactTest {

    private fun bigBase64(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString(chars) { repeat(chars) { append(alphabet[it % alphabet.length]) } }
    }

    @Test
    fun redactsOpenAiImageUrlDataUrl() {
        val data = bigBase64(2000)
        val body = mapOf(
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf(
                                "url" to "data:image/jpeg;base64,$data",
                                "detail" to "auto"
                            )
                        )
                    )
                )
            )
        )

        val out = AILogger.redactToJson(body)

        assertFalse("内嵌图片 base64 未被脱敏", out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
        assertTrue(out.contains("\"detail\": \"auto\""))
    }

    @Test
    fun redactsAnthropicSourceData() {
        val data = bigBase64(2000)
        val body = mapOf(
            "source" to mapOf("type" to "base64", "media_type" to "image/png", "data" to data)
        )

        val out = AILogger.redactToJson(body)

        assertFalse("source.data 的 base64 未被脱敏", out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
    }

    @Test
    fun redactsBase64DataField() {
        val data = bigBase64(2000)

        val out = AILogger.redactToJson(mapOf("base64Data" to data))

        assertFalse(out.contains(data))
        assertTrue(out.contains("[base64 omitted:"))
    }

    @Test
    fun keepsNormalTextIntact() {
        val out = AILogger.redactToJson(mapOf("text" to "hello world，这是一段正常文本，不应被改动。"))

        assertTrue(out.contains("hello world"))
        assertTrue(out.contains("正常文本"))
        assertFalse(out.contains("base64 omitted"))
    }

    @Test
    fun keepsShortUrlIntact() {
        val out = AILogger.redactToJson(mapOf("url" to "https://example.com/a.png"))

        assertTrue(out.contains("https://example.com/a.png"))
        assertFalse(out.contains("base64 omitted"))
    }

    @Test
    fun logsOnlyAtDebugOrVerbose() {
        val prev = AILogger.minLevel
        try {
            AILogger.setMinLevel(LogLevel.VERBOSE)
            assertTrue(AILogger.isLoggingEnabled())

            AILogger.setMinLevel(LogLevel.DEBUG)
            assertTrue(AILogger.isLoggingEnabled())

            AILogger.setMinLevel(LogLevel.INFO)
            assertFalse(AILogger.isLoggingEnabled())

            AILogger.setMinLevel(LogLevel.WARN)
            assertFalse(AILogger.isLoggingEnabled())

            AILogger.setMinLevel(LogLevel.ERROR)
            assertFalse(AILogger.isLoggingEnabled())

            AILogger.setMinLevel(LogLevel.NONE)
            assertFalse(AILogger.isLoggingEnabled())
        } finally {
            AILogger.setMinLevel(prev)
        }
    }
}
