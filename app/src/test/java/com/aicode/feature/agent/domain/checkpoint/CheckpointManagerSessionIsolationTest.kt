package com.aicode.feature.agent.domain.checkpoint

import android.content.ContextWrapper
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.sha256Fingerprint
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 活动 checkpoint 的会话归属。
 *
 * 回归点：活动 checkpoint 曾是全局单字段，会话 B 发消息会覆盖会话 A 的活动 checkpoint，
 * A 之后写文件产生的快照被挂到 B 名下；B 一撤销就把 A 已写盘的文件还原或删除
 * （表现为「写盘成功但文件消失」的幽灵文件）。
 */
class CheckpointManagerSessionIsolationTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "cp-test-${System.nanoTime()}").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val workspace = File(root, "workspace").apply { mkdirs() }

    private val dao = FakeCheckpointDao()
    private val fileAccess = LocalFileAccess()
    private val versions = FileVersionRegistry()
    private val manager = CheckpointManager(
        FakeContext(filesDir),
        dao,
        fileAccess,
        WorkspaceFileLock(),
        versions
    )

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoringOtherSession_doesNotRevertModifiedFile() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        // A 的 Agent 在 B 发过消息之后才动文件
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 的新内容", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertEquals(0, restored.restored)
        assertEquals("A 的新内容", File(path).readText())
    }

    @Test
    fun restoringOtherSession_doesNotDeleteCreatedFile() = runTest {
        val path = File(workspace, "new.txt").absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 新建的文件", overwrite = true)

        manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertTrue(File(path).exists())
        assertEquals("A 新建的文件", File(path).readText())
    }

    @Test
    fun restoringOwnSession_revertsAndDeletes() = runTest {
        val modified = File(workspace, "m.txt").apply { writeText("original") }.absolutePath
        val created = File(workspace, "c.txt").absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", modified)
        fileAccess.writeFile(modified, "改过了", overwrite = true)
        manager.beforeFileModified("sessionA", created)
        fileAccess.writeFile(created, "新建的", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(2, restored.restored)
        assertEquals("original", File(modified).readText())
        assertFalse(File(created).exists())
    }

    @Test
    fun restoreSkipsFileChangedAfterSnapshot() = runTest {
        // 核心场景：本会话「见过」某版本之后，文件又被别的会话改过；此时回滚不能覆盖对方。
        // 守卫依据的是「本会话最后看到的版本」，所以这里要先让本会话登记一个版本
        // （真实流程里由 readFile / 上一次写入完成登记）。
        val path = File(workspace, "shared.txt").apply { writeText("v1") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "v2-mine", overwrite = true)
        recordSeenVersion("sessionA", path)

        // 快照与登记都完成之后，别人把文件改成 v3
        File(path).writeText("v3-changed-by-other-session")

        val result = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals("存在本会话未见过的改动时必须跳过", 0, result.restored)
        assertEquals(1, result.skipped.size)
        assertTrue(
            "跳过清单要能定位到文件，实际=${result.skipped}",
            result.skipped.single().endsWith("shared.txt")
        )
        assertEquals("别人的改动必须保留", "v3-changed-by-other-session", File(path).readText())
    }

    @Test
    fun restoreRevertsOwnChangesEvenWhenTheyDiffer() = runTest {
        // 反向保证：本会话自己改过、且这是本会话见过的版本 → 必须照常回滚（这正是回滚的用途）。
        val path = File(workspace, "mine.txt").apply { writeText("v1") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.beforeFileModified("sessionA", path)

        // 本会话的编辑流程：登记（readFile）→ 写入 → 再登记
        recordSeenVersion("sessionA", path)
        fileAccess.writeFile(path, "v2-mine", overwrite = true)
        recordSeenVersion("sessionA", path)

        val result = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals("本会话自己的改动必须能被撤销", 1, result.restored)
        assertTrue(result.skipped.isEmpty())
        assertEquals("v1", File(path).readText())
    }

    /** 模拟「本会话看到了该文件的当前版本」（等价于 readFile 的登记行为）。 */
    private fun recordSeenVersion(sessionId: String, path: String) {
        val fp = fileAccess.fingerprint(path) ?: return
        versions.record(sessionId, fileAccess.toDisplayPath(path), fp)
    }

    @Test
    fun restoreRecreatesFileDeletedAfterSnapshot() = runTest {
        // MODIFY 快照 + 文件在快照后被删除 → 恢复到快照内容。
        // 这是"本会话自己改/删、用户要撤销"的常规场景，必须能回滚回去。
        val path = File(workspace, "vanished.txt").apply { writeText("v1") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.beforeFileModified("sessionA", path)

        assertTrue(File(path).delete())

        val result = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(1, result.restored)
        assertTrue(result.skipped.isEmpty())
        assertEquals("应恢复快照内容", "v1", File(path).readText())
    }

    @Test
    fun restoreIsIdempotent() = runTest {
        // 连续回滚两次结果一致：第一次已把文件写回快照内容，第二次不应再改出别的东西。
        val path = File(workspace, "same.txt").apply { writeText("v1") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "v2-mine", overwrite = true)

        val first = manager.restoreCodeToCheckpoint("sessionA", cpA.id)
        assertEquals(1, first.restored)
        assertEquals("v1", File(path).readText())

        val second = manager.restoreCodeToCheckpoint("sessionA", cpA.id)
        assertEquals(1, second.restored)
        assertEquals("v1", File(path).readText())
    }

    @Test
    fun restoreDeletesCreatedFileEvenIfLaterModified() = runTest {
        // 已知局限的显式记录：CREATE 快照拍摄时文件还不存在，因此没记下内容指纹，
        // 无法判断快照之后是否有人往里写过东西——当前实现一律删除。
        // 这个用例把现状钉住：将来若补上「首次写入后记指纹」，本用例应当改成断言 skipped。
        val path = File(workspace, "created.txt").absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 新建的内容", overwrite = true)
        fileAccess.writeFile(path, "别人又改了", overwrite = true)

        val result = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals("按现状：新建的文件照旧删除", 1, result.restored)
        assertTrue(result.skipped.isEmpty())
        assertFalse(File(path).exists())
    }

    @Test
    fun snapshotsAreScopedToOwningSession() = runTest {
        val pathA = File(workspace, "a.txt").apply { writeText("a") }.absolutePath
        val pathB = File(workspace, "b.txt").apply { writeText("b") }.absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", pathA)
        manager.beforeFileModified("sessionB", pathB)

        assertEquals(listOf(pathA), dao.getFileSnapshotsForCheckpoint(cpA.id).map { it.filePath })
        assertEquals(listOf(pathB), dao.getFileSnapshotsForCheckpoint(cpB.id).map { it.filePath })
    }

    @Test
    fun clearingSessionDropsItsActiveCheckpoint() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")

        manager.clearSessionCheckpoints("sessionA")
        manager.beforeFileModified("sessionA", path)

        assertTrue(dao.getFileSnapshotsForCheckpoint(cpA.id).isEmpty())
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private class FakeCheckpointDao : CheckpointDao {
        private val checkpoints = mutableListOf<CheckpointEntity>()
        private val snapshots = mutableListOf<CheckpointFileSnapshotEntity>()

        override suspend fun insertCheckpoint(checkpoint: CheckpointEntity) {
            checkpoints.removeAll { it.id == checkpoint.id }
            checkpoints += checkpoint
        }

        override suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity) {
            snapshots.removeAll { it.id == snapshot.id }
            snapshots += snapshot
        }

        // 同一毫秒创建的 checkpoint 按插入顺序稳定排序，对齐 Room 的 createdAt ASC
        override suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity> =
            checkpoints.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

        override suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.sessionId == sessionId && it.userMessageId == messageId }

        override suspend fun getCheckpointById(checkpointId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.id == checkpointId }

        override suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity> =
            snapshots.filter { it.checkpointId == checkpointId }

        override suspend fun countSnapshot(checkpointId: String, filePath: String): Int =
            snapshots.count { it.checkpointId == checkpointId && it.filePath == filePath }

        override suspend fun deleteCheckpointsForSession(sessionId: String) {
            checkpoints.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteFileSnapshotsForSession(sessionId: String) {
            val ids = checkpoints.filter { it.sessionId == sessionId }.map { it.id }.toSet()
            snapshots.removeAll { it.checkpointId in ids }
        }

        override suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long) {
            checkpoints.removeAll { it.createdAt < cutoffTimestamp }
        }
    }

    private class LocalFileAccess : FileAccessProvider {
        override fun readFile(path: String): String = File(path).readText()
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(content, encoding)
        }

        override fun exists(path: String): Boolean = File(path).exists()
        override fun delete(path: String) {
            File(path).delete()
        }

        override fun readLines(path: String): Sequence<String> = throw UnsupportedOperationException()
        override fun isDirectory(path: String): Boolean = throw UnsupportedOperationException()
        override fun isFile(path: String): Boolean = throw UnsupportedOperationException()
        override fun fileSize(path: String): Long = throw UnsupportedOperationException()
        override fun lastModified(path: String): Long = throw UnsupportedOperationException()

        /** 真实实现而非抛异常：checkpoint 回滚前要按指纹判断文件是否已被其他会话改动。 */
        override fun fingerprint(path: String): String? {
            val file = File(path)
            if (!file.isFile) return null
            return sha256Fingerprint(file.readBytes())
        }
        override fun permissions(path: String): String = throw UnsupportedOperationException()
        override fun listFiles(path: String): List<FileEntry> = throw UnsupportedOperationException()
        override fun readBytes(path: String): ByteArray = throw UnsupportedOperationException()
        override fun readBytes(path: String, maxBytes: Long): ByteArray = throw UnsupportedOperationException()
        override fun resolvePath(path: String, followFinalLink: Boolean): String = throw UnsupportedOperationException()
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(bytes)
        }
        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = throw UnsupportedOperationException()
        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()
        override fun copyToLocal(path: String): File = throw UnsupportedOperationException()
        override fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override fun mkdirs(path: String) = throw UnsupportedOperationException()
        override fun parentPath(path: String): String? = throw UnsupportedOperationException()
        override fun toDisplayPath(path: String): String = path
    }
}
