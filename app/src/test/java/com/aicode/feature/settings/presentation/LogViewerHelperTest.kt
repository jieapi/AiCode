package com.aicode.feature.settings.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogViewerHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `readFilteredAppLogTail keeps stack trace continuation lines for matched level`() {
        val file = tempFolder.newFile("log-test.txt")
        file.writeText(
            listOf(
                "2026-11-18 10:00:00.000 INFO [Main] started",
                "2026-11-18 10:00:01.000 ERROR [McpClient] [srv1] boom",
                "java.lang.RuntimeException: fail",
                "\tat com.aicode.Test.run(Test.kt:10)",
                "2026-11-18 10:00:02.000 DEBUG [Agent] continuing"
            ).joinToString("\n")
        )

        val result = LogViewerHelper.readFilteredAppLogTail(
            file = file,
            mcpServerName = null,
            levelFilter = LogLevelFilter.ERROR,
            tagFilter = null,
            maxLines = 100
        )
        assertEquals(
            listOf(
                "2026-11-18 10:00:01.000 ERROR [McpClient] [srv1] boom",
                "java.lang.RuntimeException: fail",
                "\tat com.aicode.Test.run(Test.kt:10)"
            ),
            result.visibleLines
        )
        assertFalse(result.hasMoreEarlier)
    }

    @Test
    fun `readFilteredAppLogTail fast path returns tail lines and accurate total count`() {
        val file = tempFolder.newFile("log-large.txt")
        val lines = (1..50).map { i ->
            "2026-11-18 10:00:${String.format("%02d", i % 60)}.000 INFO [Tag] line-$i"
        }
        file.writeText(lines.joinToString("\n") + "\n")

        val result = LogViewerHelper.readFilteredAppLogTail(
            file = file,
            mcpServerName = null,
            levelFilter = LogLevelFilter.ALL,
            tagFilter = null,
            maxLines = 10
        )
        assertEquals(50, result.totalLines)
        assertEquals(10, result.visibleLines.size)
        assertTrue(result.visibleLines.first().endsWith("line-41"))
        assertTrue(result.visibleLines.last().endsWith("line-50"))
        assertTrue(result.hasMoreEarlier)
    }

    @Test
    fun `readFilteredAppLogTail filters by tag and mcp server together`() {
        val file = tempFolder.newFile("log-mcp.txt")
        file.writeText(
            listOf(
                "2026-11-18 10:00:00.000 INFO [McpClient] [git] connected",
                "2026-11-18 10:00:01.000 WARN [McpManager] [git] slow",
                "2026-11-18 10:00:02.000 INFO [McpClient] [fs] connected",
                "2026-11-18 10:00:03.000 INFO [OkHttp] git mention"
            ).joinToString("\n")
        )

        val result = LogViewerHelper.readFilteredAppLogTail(
            file = file,
            mcpServerName = "git",
            levelFilter = LogLevelFilter.WARN_PLUS,
            tagFilter = "McpManager",
            maxLines = 100
        )
        assertEquals(
            listOf("2026-11-18 10:00:01.000 WARN [McpManager] [git] slow"),
            result.visibleLines
        )
    }

    @Test
    fun `indexAiLogFile and readAiBlockDetail correlate interleaved concurrent requests by byte ranges`() {
        val sep = "=".repeat(78)
        val file = tempFolder.newFile("session-test.log")
        file.writeText(
            """
            $sep
            2026-11-18 12:00:00.100  REQUEST #1   [Anthropic / claude-sonnet]
            POST https://api.example.com/v1/messages
            --- request body ---
            {"msg":"hi"}

            $sep
            2026-11-18 12:00:00.200  REQUEST #2   [OpenAI / gpt-4o]
            POST https://api.openai.com/v1/chat/completions
            --- request body ---
            {"msg":"title"}
            2026-11-18 12:00:01.000  ERROR #2   [OpenAI]
            java.io.IOException: timeout
            --- error response body ---
            {"error":"gateway timeout"}
            2026-11-18 12:00:01.500  RESPONSE #1   [Anthropic / stream]
            --- raw SSE ---
            data: {"type":"ping"}
            """.trimIndent() + "\n"
        )

        val blocks = LogViewerHelper.indexAiLogFile(file)
        assertEquals(2, blocks.size)

        val b1 = blocks[0]
        assertEquals(1, b1.seq)
        assertFalse(b1.isError)
        assertTrue(b1.isStream)
        assertEquals("POST https://api.example.com/v1/messages", b1.methodUrl)

        val d1 = LogViewerHelper.readAiBlockDetail(file, b1)
        assertTrue(d1.requestBody.contains("\"msg\":\"hi\""))
        assertTrue(d1.responseBody.orEmpty().contains("ping"))

        val b2 = blocks[1]
        assertEquals(2, b2.seq)
        assertTrue(b2.isError)
        assertFalse(b2.isStream)

        val d2 = LogViewerHelper.readAiBlockDetail(file, b2)
        assertTrue(d2.requestBody.contains("\"msg\":\"title\""))
        assertTrue(d2.responseBody.orEmpty().contains("gateway timeout"))
    }
}
