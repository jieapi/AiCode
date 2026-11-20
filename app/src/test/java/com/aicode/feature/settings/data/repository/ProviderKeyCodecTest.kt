package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.ProviderKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** 多 Key 存储串的编解码：新格式带地址覆盖，且必须兼容已有用户的旧数据。 */
class ProviderKeyCodecTest {

    @Test
    fun encode_thenDecode_roundTripsWithBaseUrl() {
        val keys = listOf(
            ProviderKey("sk-a", 1),
            ProviderKey("sk-b", 3, "https://relay.example.com/v1")
        )

        assertEquals(keys, decodeProviderKeys(encodeProviderKeys(keys)))
    }

    @Test
    fun encode_omitsBaseUrlWhenBlank() {
        assertEquals("sk-a|1", encodeProviderKeys(listOf(ProviderKey("sk-a", 1))))
    }

    // ---------- 旧格式兼容 ----------

    @Test
    fun decode_legacyValueWithWeight() {
        assertEquals(listOf(ProviderKey("sk-a", 2)), decodeProviderKeys("sk-a|2"))
    }

    @Test
    fun decode_legacyBareValue() {
        assertEquals(listOf(ProviderKey("sk-a")), decodeProviderKeys("sk-a"))
    }

    @Test
    fun decode_valueContainingPipe() {
        // 旧版本用 lastIndexOf('|') 切权重，值里含 '|' 时也要保持相同结果。
        assertEquals(listOf(ProviderKey("sk-a|part", 2)), decodeProviderKeys("sk-a|part|2"))
    }

    // ---------- 新格式 ----------

    @Test
    fun decode_baseUrlOverride() {
        assertEquals(
            listOf(ProviderKey("sk-a", 1, "https://relay.example.com")),
            decodeProviderKeys("sk-a|1|https://relay.example.com")
        )
    }

    @Test
    fun decode_httpsBaseUrlOverride() {
        assertEquals(
            listOf(ProviderKey("sk-a", 5, "http://10.0.0.2:8080/v1")),
            decodeProviderKeys("sk-a|5|http://10.0.0.2:8080/v1")
        )
    }

    @Test
    fun decode_nonHttpThirdSegment_isTreatedAsPartOfKey() {
        // 只认 http(s) 前缀的第三段为地址，避免把「值里恰好有 |」的旧数据误判成地址。
        assertEquals(
            listOf(ProviderKey("sk-a|1|relay.example.com")),
            decodeProviderKeys("sk-a|1|relay.example.com")
        )
    }

    @Test
    fun decode_skipsBlankLinesAndEmptyValues() {
        assertEquals(listOf(ProviderKey("sk-a", 1)), decodeProviderKeys("\nsk-a|1\n   \n"))
        assertEquals(emptyList<ProviderKey>(), decodeProviderKeys(""))
    }

    @Test
    fun decode_multipleLines_preservesOrder() {
        val raw = "sk-a|1|https://a.example.com\nsk-b|2\nsk-c"

        assertEquals(
            listOf(
                ProviderKey("sk-a", 1, "https://a.example.com"),
                ProviderKey("sk-b", 2),
                ProviderKey("sk-c")
            ),
            decodeProviderKeys(raw)
        )
    }
}
