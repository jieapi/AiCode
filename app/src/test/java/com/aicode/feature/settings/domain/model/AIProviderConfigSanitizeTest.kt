package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AIProviderConfigSanitizeTest {

    private fun config(
        name: String = "OpenAI",
        apiKey: String = "sk-abc",
        baseUrl: String = "https://api.openai.com/",
        models: List<String> = listOf("gpt-4o"),
        selectedModel: String = "gpt-4o",
        defaultModel: String = "gpt-4o",
        customHeaders: Map<String, String> = emptyMap(),
        dashboardScriptPath: String = "",
        proxyHost: String = "",
        proxyUsername: String = "",
        proxyPassword: String = ""
    ) = AIProviderConfig(
        id = "p1",
        name = name,
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        customHeaders = customHeaders,
        dashboardScriptPath = dashboardScriptPath,
        proxyHost = proxyHost,
        proxyUsername = proxyUsername,
        proxyPassword = proxyPassword
    )

    @Test
    fun apiKey_stripsLineBreaksAndSpaces() {
        val sanitized = config(apiKey = " sk-abc\ndef \tghi\r\n").sanitized()

        assertEquals("sk-abcdefghi", sanitized.apiKey)
    }

    @Test
    fun baseUrl_stripsAllWhitespace() {
        val sanitized = config(baseUrl = " https://api.example.com/v1 \n").sanitized()

        assertEquals("https://api.example.com/v1", sanitized.baseUrl)
    }

    @Test
    fun proxyHost_stripsAllWhitespace() {
        val sanitized = config(proxyHost = " 127.0.0.1\n").sanitized()

        assertEquals("127.0.0.1", sanitized.proxyHost)
    }

    @Test
    fun textFields_keepInnerSpacesButDropLineBreaks() {
        val sanitized = config(
            name = " My\nProvider ",
            dashboardScriptPath = " ~/.aicode/scripts/my panel.py \n",
            proxyUsername = " user name\n",
            proxyPassword = " pa ss\r\n"
        ).sanitized()

        assertEquals("MyProvider", sanitized.name)
        assertEquals("~/.aicode/scripts/my panel.py", sanitized.dashboardScriptPath)
        assertEquals("user name", sanitized.proxyUsername)
        assertEquals("pa ss", sanitized.proxyPassword)
    }

    @Test
    fun customHeaders_trimKeysStripLineBreaksAndDropEmptyKeys() {
        val sanitized = config().copy(customHeaders = mapOf(
            " User-Agent " to " AiCode/1.0 (Android)\n",
            "X-Session" to " sk-abc \n",
            "  " to "ignored"
        )).sanitized()

        assertEquals(
            mapOf("User-Agent" to "AiCode/1.0 (Android)", "X-Session" to "sk-abc"),
            sanitized.customHeaders
        )
    }

    @Test
    fun models_trimmedDeduplicatedAndEmptiesDropped() {
        val sanitized = config(
            models = listOf(" gpt-4o ", "gpt-4o\n", "", "  ", "o3-mini\r"),
            selectedModel = " gpt-4o\n",
            defaultModel = "gpt-4o\r"
        ).sanitized()

        assertEquals(listOf("gpt-4o", "o3-mini"), sanitized.models)
        assertEquals("gpt-4o", sanitized.selectedModel)
        assertEquals("gpt-4o", sanitized.defaultModel)
    }

    @Test
    fun scriptParams_trimKeysStripLineBreaksAndDropEmptyKeys() {
        val sanitized = config().copy(scriptParams = mapOf(
            " ACCOUNT_ID " to "v1\n",
            "SECRET" to " sk-abc \n",
            "  " to "ignored"
        )).sanitized()

        assertEquals(
            mapOf("ACCOUNT_ID" to "v1", "SECRET" to "sk-abc"),
            sanitized.scriptParams
        )
    }

    @Test
    fun maxConcurrentRequests_zeroMeansUnlimitedAndPositiveValuesAreUnbounded() {
        assertEquals(0, config().copy(maxConcurrentRequests = 0).sanitized().maxConcurrentRequests)
        assertEquals(0, config().copy(maxConcurrentRequests = -1).sanitized().maxConcurrentRequests)
        assertEquals(1_000, config().copy(maxConcurrentRequests = 1_000).sanitized().maxConcurrentRequests)
    }

    @Test
    fun cleanConfig_unchanged() {
        val clean = config()

        assertEquals(clean, clean.sanitized())
    }
}
