package com.aicode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinUrlTest {

    /** base 末尾带斜杠：拼接前去掉，避免双斜杠。 */
    @Test
    fun baseTrailingSlash_trimmed() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            joinUrl("https://api.openai.com/v1/", "chat/completions")
        )
    }

    /** base 末尾与 path 开头是相同的版本段（v1）：去重只留一份。 */
    @Test
    fun duplicateVersionSegment_deduplicated() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            joinUrl("https://api.openai.com/v1", "v1/chat/completions")
        )
    }

    /** base 末尾为 v1beta 且 path 也以 v1beta 开头：同样去重。 */
    @Test
    fun duplicateV1betaSegment_deduplicated() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            joinUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                "v1beta/models/gemini-2.0-flash:generateContent"
            )
        )
    }

    /** base 末尾是版本段、path 也以版本段开头且不同：以 base 版本为准，丢弃 path 版本段。 */
    @Test
    fun baseVersionWins_pathVersionDropped() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            joinUrl("https://open.bigmodel.cn/api/paas/v4", "v1/chat/completions")
        )
    }

    /** base 为 v2、path 为 v1：仍以 base 版本为准。 */
    @Test
    fun baseV2Wins_pathV1Dropped() {
        assertEquals(
            "https://host/v2/models",
            joinUrl("https://host/v2", "v1/models")
        )
    }

    /** path 只有版本段、无后续路径：返回 base 本身。 */
    @Test
    fun versionOnlyPath_returnsBase() {
        assertEquals(
            "https://host/v2",
            joinUrl("https://host/v2", "v1")
        )
    }

    /** base 不带版本段：普通拼接。 */
    @Test
    fun plainJoin_pathAppended() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            joinUrl("https://api.deepseek.com", "v1/chat/completions")
        )
    }

    /** host 根路径 base（无子路径）：直接拼接。 */
    @Test
    fun hostOnlyBase_pathAppended() {
        assertEquals(
            "https://host/v1/chat",
            joinUrl("https://host", "v1/chat")
        )
    }

    /** path 前导斜杠：拼接前去掉，避免双斜杠。 */
    @Test
    fun pathLeadingSlash_trimmed() {
        assertEquals(
            "https://host/v1/chat",
            joinUrl("https://host", "/v1/chat")
        )
    }

    /** base 首尾空白：拼接前剔除。 */
    @Test
    fun baseWhitespace_trimmed() {
        assertEquals(
            "https://host/v1/chat/completions",
            joinUrl(" https://host/v1 ", "v1/chat/completions")
        )
    }

    /** 版本段去重忽略大小写，且保留 base 原有的写法。 */
    @Test
    fun duplicateSegment_ignoreCaseKeepsBaseCase() {
        assertEquals(
            "https://host/V1/chat",
            joinUrl("https://host/V1", "v1/chat")
        )
    }
}