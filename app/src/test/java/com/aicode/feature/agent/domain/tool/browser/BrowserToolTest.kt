package com.aicode.feature.agent.domain.tool.browser

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BrowserToolTest {

    private lateinit var browserManager: BrowserManager
    private lateinit var tool: BrowserTool

    private val args = mapOf(
        "action" to JsonPrimitive("evaluate"),
        "script" to JsonPrimitive("1 + 1")
    )

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
        browserManager = mockk()
        coEvery { browserManager.withAutomation<ToolResult>(any()) } coAnswers {
            firstArg<suspend () -> ToolResult>().invoke()
        }
        val fileAccess: FileAccessProvider = mockk()
        val context: Context = mockk()
        tool = BrowserTool(browserManager, fileAccess, context)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun execute_timeout_returnsTimeout() = runTest {
        coEvery { browserManager.evaluateJavaScript("1 + 1", null) } coAnswers {
            withTimeout(1L) { awaitCancellation() }
        }

        val result = tool.execute(args)

        assertEquals(ToolResult.Error("操作超时：evaluate", "TIMEOUT"), result)
    }

    @Test
    fun execute_cancellation_rethrowsSameException() = runTest {
        val cancellation = CancellationException("Cancelled")
        coEvery { browserManager.evaluateJavaScript("1 + 1", null) } throws cancellation

        try {
            tool.execute(args)
            fail("CancellationException should propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test
    fun execute_illegalState_returnsBrowserNotReady() = runTest {
        coEvery { browserManager.evaluateJavaScript("1 + 1", null) } throws
            IllegalStateException("Browser not initialized")

        val result = tool.execute(args)

        assertEquals(ToolResult.Error("Browser not initialized", "BROWSER_NOT_READY"), result)
    }

    @Test
    fun execute_illegalArgument_returnsInvalidArgument() = runTest {
        coEvery { browserManager.evaluateJavaScript("1 + 1", null) } throws
            IllegalArgumentException("Invalid script")

        val result = tool.execute(args)

        assertEquals(ToolResult.Error("Invalid script", "INVALID_ARGUMENT"), result)
    }

    @Test
    fun execute_exception_returnsBrowserError() = runTest {
        coEvery { browserManager.evaluateJavaScript("1 + 1", null) } throws
            Exception("Script failed")

        val result = tool.execute(args)

        assertEquals(ToolResult.Error("操作失败：Script failed", "BROWSER_ERROR"), result)
    }
}
