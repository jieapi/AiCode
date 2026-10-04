package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证落库前的内容净化与按字节限长：剥离内嵌 base64 图片 data URL、超长内容按 UTF-8 字节截断，
 * 防止超大字段撑爆单行触发 SQLite CursorWindow 崩溃。
 */
class MessagePersistenceUseCaseTest {

    @Test
    fun historyPlacesSummaryBeforeRetainedMessagesWithoutChangingDisplayTimestamps() {
        val retained = AgentMessageEntity(id = "retained", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        val marker = AgentMessageEntity(id = "marker", sessionId = "session", role = "USER", content = "marker", timestamp = 200, isCompactionMarker = true)
        val summary = AgentMessageEntity(id = "summary", sessionId = "session", role = "ASSISTANT", content = "summary", timestamp = 201, isContextSummary = true)
        val latest = retained.copy(id = "latest", timestamp = 300)
        val display = listOf(retained, marker, summary, latest)
        val history = MessagePersistenceUseCase.orderHistoryEntities(display)
        assertEquals(listOf("marker", "summary", "retained", "latest"), history.map { it.id })
        assertEquals(listOf(200L, 201L, 100L, 300L), history.map { it.timestamp })
        assertEquals(listOf("retained", "marker", "summary", "latest"), display.map { it.id })
    }

    @Test
    fun sanitizeContent_stripsInlineBase64Image() {
        val input = "这是回复。看图：![截图](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==) 结束。"

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("iVBORw0KGgo"))
        assertTrue(result.contains("这是回复"))
    }

    @Test
    fun sanitizeContent_stripsBareDataUrl() {
        val input = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQ=="

        val result = MessagePersistenceUseCase.sanitizeContent(input)

        assertTrue(result.contains("图片已省略"))
        assertFalse(result.contains("/9j/4AAQSk"))
    }

    @Test
    fun sanitizeContent_truncatesOversizedPlainText() {
        val oversized = "a".repeat(300_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_boundsBytesForMultibyteText() {
        // 中文单字符 3 字节：字符数上限约束不住真实占用，必须按字节截断。
        val oversized = "汉".repeat(100_000)

        val result = MessagePersistenceUseCase.sanitizeContent(oversized)

        assertTrue(result.endsWith(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER))
        val kept = result.removeSuffix(MessagePersistenceUseCase.CONTENT_TRUNCATED_MARKER)
        assertTrue(kept.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
    }

    @Test
    fun sanitizeContent_keepsNormalTextUnchanged() {
        val normal = "普通消息，不需要处理。".repeat(10)

        assertEquals(normal, MessagePersistenceUseCase.sanitizeContent(normal))
    }

    @Test
    fun capBytes_truncatesOversizedJson() {
        val oversized = "[{\"id\":\"" + "a".repeat(300_000) + "\"}]"

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
    }

    @Test
    fun capBytes_doesNotSplitSurrogatePair() {
        val oversized = "😀".repeat(50_000)

        val result = MessagePersistenceUseCase.capBytes(
            oversized,
            MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES
        )

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertEquals(0, result.length % 2)
    }

    @Test
    fun capBytes_keepsNormalJsonUnchanged() {
        val normal = "[{\"id\":\"call_1\",\"name\":\"writeFile\"}]"

        assertEquals(
            normal,
            MessagePersistenceUseCase.capBytes(normal, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        )
    }
}
