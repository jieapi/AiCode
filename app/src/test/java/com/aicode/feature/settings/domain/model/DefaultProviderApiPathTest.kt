package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultProviderApiPathTest {

    @Test
    fun anthropic_usesMessagesPath() {
        assertEquals("v1/messages", defaultProviderApiPath(ProviderType.ANTHROPIC))
    }

    @Test
    fun gemini_usesV1beta() {
        assertEquals("v1beta", defaultProviderApiPath(ProviderType.GEMINI))
    }

    @Test
    fun openai_usesChatCompletionsPath() {
        assertEquals("v1/chat/completions", defaultProviderApiPath(ProviderType.OPENAI))
    }
}