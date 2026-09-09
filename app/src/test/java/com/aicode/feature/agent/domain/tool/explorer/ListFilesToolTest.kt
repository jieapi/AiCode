package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * list 工具的 `| head [-n N]` 输出截断与管道白名单。
 *
 * list 不走 shell（直接遍历宿主文件系统），`| head` 在语义上等价于「只输出前 N 行」，
 * 但安全校验与 search 共用同一套 [headLinesOf] 白名单，非 head 管道一律拒绝。
 */
class ListFilesToolTest {

    private val dir = "/root/workspace/demo"
    private val files = listOf(
        FileEntry("a.txt", isDirectory = false, size = 10, lastModified = 1000),
        FileEntry("b.txt", isDirectory = false, size = 20, lastModified = 2000),
        FileEntry("c.txt", isDirectory = false, size = 30, lastModified = 3000),
        FileEntry(".hidden", isDirectory = false, size = 5, lastModified = 500)
    )

    private val tool = ListFilesTool(FakeFileAccess())

    private inner class FakeFileAccess : FileAccessProvider {
        override fun exists(path: String): Boolean = path == dir
        override fun isDirectory(path: String): Boolean = path == dir
        override fun isFile(path: String): Boolean = path != dir
        override fun listFiles(path: String): List<FileEntry> = if (path == dir) files else emptyList()
        override fun parentPath(path: String): String? = "/root/workspace"
        override fun toDisplayPath(path: String): String = path

        override fun readFile(path: String): String = throw UnsupportedOperationException()
        override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) = throw UnsupportedOperationException()
        override fun fileSize(path: String): Long = throw UnsupportedOperationException()
        override fun lastModified(path: String): Long = throw UnsupportedOperationException()
        override fun permissions(path: String): String = throw UnsupportedOperationException()
        override fun readBytes(path: String): ByteArray = throw UnsupportedOperationException()
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun copyToLocal(path: String): File = throw UnsupportedOperationException()
        override fun delete(path: String) = throw UnsupportedOperationException()
        override fun mkdirs(path: String) = throw UnsupportedOperationException()
        override fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
    }

    private suspend fun runList(args: String): ToolResult {
        return tool.execute(mapOf("args" to JsonPrimitive(args)))
    }

    private fun contentOf(result: ToolResult): String {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun entriesOf(result: ToolResult): Int {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["entries"]?.jsonPrimitive?.contentOrNull?.toInt() ?: -1
    }

    private fun truncatedOf(result: ToolResult): Boolean {
        val success = result as ToolResult.Success
        return (success.data as JsonObject)["truncated"]?.jsonPrimitive?.contentOrNull == "true"
    }

    @Test
    fun list_noPipe_listsAllEntries() = runTest {
        val result = runList("-la $dir")
        assertTrue(result is ToolResult.Success)
        assertEquals(6, entriesOf(result)) // . .. a.txt b.txt c.txt .hidden
        assertFalse(truncatedOf(result))
        assertTrue(contentOf(result).contains("a.txt"))
        assertTrue(contentOf(result).contains(".hidden"))
    }

    @Test
    fun list_headLimit_truncatesOutput() = runTest {
        val result = runList("-la $dir | head -2")
        assertTrue(result is ToolResult.Success)
        assertEquals(2, entriesOf(result))
        assertTrue(truncatedOf(result))
        val content = contentOf(result)
        // 尾随换行会让 lines() 多出一个空元素，先 trimEnd 再数行
        assertTrue(content.trimEnd('\n').lines().size == 2)
        // head 截断是用户主动要求，不应追加系统「已达上限」提示
        assertFalse(content.contains("已达"))
    }

    @Test
    fun list_bareHead_defaultsTo10Lines() = runTest {
        val result = runList("-la $dir | head")
        assertTrue(result is ToolResult.Success)
        assertEquals(6, entriesOf(result))
        assertFalse(truncatedOf(result))
    }

    @Test
    fun list_headZero_outputsNothing() = runTest {
        val result = runList("-la $dir | head -0")
        assertTrue(result is ToolResult.Success)
        assertEquals(0, entriesOf(result))
        assertTrue(truncatedOf(result))
    }

    @Test
    fun list_multipleHeadSegments_takeMinimum() = runTest {
        val result = runList("-la $dir | head -2 | head -1")
        assertTrue(result is ToolResult.Success)
        assertEquals(1, entriesOf(result))
        assertTrue(truncatedOf(result))
    }

    @Test
    fun list_arbitraryPipeCommand_isRejected() = runTest {
        val result = runList("-la $dir | rm -rf /")
        val error = result as ToolResult.Error
        assertEquals("INVALID_PIPE", error.code)
    }

    @Test
    fun list_pipeWithoutLeadingArgs_isRejected() = runTest {
        val result = runList("| head -2")
        val error = result as ToolResult.Error
        assertEquals("INVALID_PIPE", error.code)
    }
}
