package com.aicode.feature.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHttpHeadersTest {

    private val originalUrl = "https://api.example.com/v1/chat/completions"

    private val originalHeaders = mapOf(
        "Authorization" to "Bearer sk-real",
        "X-Api-Key" to "real-key",
        "X-Goog-Api-Key" to "google-key",
        "X-Custom-Key" to "sk-real",
        "X-Plain" to "hello",
        "Content-Type" to "application/json",
        "X-Trace-Id" to "trace-1"
    )

    @Test
    fun sensitiveHeaders_areRecognized() {
        listOf(
            "Authorization",
            "X-Api-Key",
            "x-goog-api-key"
        ).forEach { assertTrue(it, PluginHttpHeaders.isSensitive(it)) }

        listOf(
            "Content-Type", "X-Trace-Id", "Accept", "User-Agent",
            "Cookie", "Proxy-Authorization", "X-Custom-Auth", "X-Custom-Key"
        ).forEach {
            assertFalse(it, PluginHttpHeaders.isSensitive(it))
        }
    }

    @Test
    fun redact_replacesSensitiveValuesOnly() {
        val redacted = PluginHttpHeaders.redact(originalHeaders)

        assertEquals(PluginHttpHeaders.PLACEHOLDER, redacted["Authorization"])
        assertEquals(PluginHttpHeaders.PLACEHOLDER, redacted["X-Api-Key"])
        assertEquals(PluginHttpHeaders.PLACEHOLDER, redacted["X-Goog-Api-Key"])
        assertEquals(PluginHttpHeaders.PLACEHOLDER, redacted["X-Custom-Key"])
        assertEquals("hello", redacted["X-Plain"])
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("trace-1", redacted["X-Trace-Id"])
        assertEquals(originalHeaders.size, redacted.size)
    }

    @Test
    fun merge_sameOrigin_restoresPlaceholder() {
        val merged = PluginHttpHeaders.merge(
            originalUrl = originalUrl,
            originalHeaders = originalHeaders,
            returnedHeaders = mapOf(
                "authorization" to PluginHttpHeaders.PLACEHOLDER,
                "X-Api-Key" to PluginHttpHeaders.PLACEHOLDER,
                "X-Custom-Key" to PluginHttpHeaders.PLACEHOLDER,
                "X-Plain" to "world",
                "X-Trace-Id" to "trace-2"
            ),
            finalUrl = "https://api.example.com:443/v1/chat/completions"
        )

        assertEquals("Bearer sk-real", merged["Authorization"])
        assertEquals("real-key", merged["X-Api-Key"])
        assertEquals("google-key", merged["X-Goog-Api-Key"])
        assertEquals("sk-real", merged["X-Custom-Key"])
        assertEquals("world", merged["X-Plain"])
        assertEquals("trace-2", merged["X-Trace-Id"])
    }

    @Test
    fun merge_sameOrigin_ignoresSensitiveReturnValues() {
        val merged = PluginHttpHeaders.merge(
            originalUrl = originalUrl,
            originalHeaders = originalHeaders,
            returnedHeaders = mapOf(
                "Authorization" to "Bearer plugin-supplied",
                "X-Api-Key" to "plugin-key"
            ),
            finalUrl = originalUrl
        )

        assertEquals("Bearer sk-real", merged["Authorization"])
        assertEquals("real-key", merged["X-Api-Key"])
    }

    @Test
    fun merge_crossOrigin_dropsSensitiveHeaders() {
        val merged = PluginHttpHeaders.merge(
            originalUrl = originalUrl,
            originalHeaders = originalHeaders,
            returnedHeaders = mapOf(
                "Authorization" to PluginHttpHeaders.PLACEHOLDER,
                "X-Route" to "team-a"
            ),
            finalUrl = "https://gateway.example.net/v1/chat/completions"
        )

        assertNull(merged["Authorization"])
        assertNull(merged["X-Api-Key"])
        assertNull(merged["X-Goog-Api-Key"])
        assertNull(merged["X-Custom-Key"])
        assertEquals("hello", merged["X-Plain"])
        assertEquals("application/json", merged["Content-Type"])
        assertEquals("trace-1", merged["X-Trace-Id"])
        assertEquals("team-a", merged["X-Route"])
    }

    @Test
    fun merge_originComparison_usesSchemeHostAndPort() {
        val headers = mapOf("Authorization" to "Bearer sk-real")

        assertNull(
            PluginHttpHeaders.merge(
                originalUrl = "https://api.example.com/v1/chat",
                originalHeaders = headers,
                returnedHeaders = emptyMap(),
                finalUrl = "https://api.example.com:8443/v1/chat"
            )["Authorization"]
        )
        assertNull(
            PluginHttpHeaders.merge(
                originalUrl = "https://api.example.com/v1/chat",
                originalHeaders = headers,
                returnedHeaders = emptyMap(),
                finalUrl = "http://api.example.com/v1/chat"
            )["Authorization"]
        )
        assertNull(
            PluginHttpHeaders.merge(
                originalUrl = "https://api.example.com/v1/chat",
                originalHeaders = headers,
                returnedHeaders = emptyMap(),
                finalUrl = "https://mirror.example.com/v1/chat"
            )["Authorization"]
        )
    }

    @Test
    fun merge_keepsUnreturnedHeadersAndAppendsUnknownOnes() {
        val merged = PluginHttpHeaders.merge(
            originalUrl = originalUrl,
            originalHeaders = originalHeaders,
            returnedHeaders = mapOf("X-Route" to "team-a"),
            finalUrl = originalUrl
        )

        assertEquals("trace-1", merged["X-Trace-Id"])
        assertEquals("application/json", merged["Content-Type"])
        assertEquals("team-a", merged["X-Route"])
        assertEquals(originalHeaders.size + 1, merged.size)
    }

    @Test
    fun merge_appliesPlainHeadersIncrementally() {
        val merged = PluginHttpHeaders.merge(
            originalUrl = originalUrl,
            originalHeaders = originalHeaders,
            returnedHeaders = mapOf(
                "x-trace-id" to "trace-2",
                "X-Route" to "team-a"
            ),
            finalUrl = originalUrl
        )

        assertEquals("trace-2", merged["X-Trace-Id"])
        assertEquals(1, merged.keys.count { it.equals("X-Trace-Id", ignoreCase = true) })
        assertEquals("team-a", merged["X-Route"])
    }
}
