package com.aicode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Key 条目的地址覆盖解析：只有多 Key 模式下、且该条目确实填了地址时才生效。 */
class AIProviderConfigBaseUrlTest {

    private fun config(
        apiKey: String = "sk-single",
        multiKeyEnabled: Boolean = false,
        apiKeys: List<ProviderKey> = emptyList()
    ) = AIProviderConfig(
        id = "provider-1",
        name = "测试提供商",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = "https://main.example.com",
        defaultModel = "gpt-test",
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys
    )

    @Test
    fun singleKey_usesProviderBaseUrl() {
        assertEquals("https://main.example.com", config().effectiveBaseUrl())
    }

    @Test
    fun multiKey_usesOwnOverride() {
        val cfg = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(
                ProviderKey("sk-a", 1, "https://relay-a.example.com"),
                ProviderKey("sk-b", 1)
            )
        )

        assertEquals("https://relay-a.example.com", cfg.effectiveBaseUrl("sk-a"))
        assertEquals("https://main.example.com", cfg.effectiveBaseUrl("sk-b"))
    }

    @Test
    fun multiKey_defaultArgumentFollowsFirstUsableKey() {
        val cfg = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(ProviderKey("sk-a", 1, "https://relay-a.example.com"))
        )

        assertEquals("https://relay-a.example.com", cfg.effectiveBaseUrl())
    }

    @Test
    fun multiKeyDisabled_ignoresOverrides() {
        val cfg = config(
            apiKey = "sk-single",
            multiKeyEnabled = false,
            apiKeys = listOf(ProviderKey("sk-single", 1, "https://relay-a.example.com"))
        )

        assertEquals("https://main.example.com", cfg.effectiveBaseUrl("sk-single"))
    }

    @Test
    fun unknownKey_fallsBackToProviderBaseUrl() {
        val cfg = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(ProviderKey("sk-a", 1, "https://relay-a.example.com"))
        )

        assertEquals("https://main.example.com", cfg.effectiveBaseUrl("sk-unknown"))
    }

    // ---------- sanitized ----------

    @Test
    fun sanitized_stripsWhitespaceAndPipeFromKeyBaseUrl() {
        val cleaned = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(ProviderKey("sk-a", 1, " https://relay.example.com/v1 | "))
        ).sanitized()

        assertEquals("https://relay.example.com/v1", cleaned.apiKeys.single().baseUrl)
    }

    /** 同一个 Key 配不同中转是两条独立通道，保存时不能被去重合并。 */
    @Test
    fun sanitized_keepsSameKeyWithDifferentBaseUrl() {
        val cleaned = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(
                ProviderKey("sk-a", 1, "https://relay-a.example.com"),
                ProviderKey("sk-a", 1, "https://relay-b.example.com")
            )
        ).sanitized()

        assertEquals(
            listOf("https://relay-a.example.com", "https://relay-b.example.com"),
            cleaned.apiKeys.map { it.baseUrl }
        )
    }

    /** 值、权重与地址都相同的条目仍然只保留一条。 */
    @Test
    fun sanitized_dropsExactDuplicateEntries() {
        val cleaned = config(
            apiKey = "unused",
            multiKeyEnabled = true,
            apiKeys = listOf(
                ProviderKey("sk-a", 2, "https://relay-a.example.com"),
                ProviderKey("sk-a", 2, " https://relay-a.example.com ")
            )
        ).sanitized()

        assertEquals(1, cleaned.apiKeys.size)
    }

    /** 按条目取地址：同值不同中转时，各条目返回自己的地址。 */
    @Test
    fun multiKey_entryArgumentUsesItsOwnOverride() {
        val relayA = ProviderKey("sk-a", 1, "https://relay-a.example.com")
        val relayB = ProviderKey("sk-a", 1, "https://relay-b.example.com")
        val cfg = config(apiKey = "unused", multiKeyEnabled = true, apiKeys = listOf(relayA, relayB))

        assertEquals("https://relay-a.example.com", cfg.effectiveBaseUrl(relayA))
        assertEquals("https://relay-b.example.com", cfg.effectiveBaseUrl(relayB))
    }
}
