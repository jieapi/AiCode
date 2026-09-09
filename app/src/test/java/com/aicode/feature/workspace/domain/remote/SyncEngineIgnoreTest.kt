package com.aicode.feature.workspace.domain.remote

import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteMount
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SyncEngine] 全量同步的忽略规则（自定义忽略 + .gitignore）行为验证，
 * 用 fake [RemoteSyncClient] 断言被忽略的文件确实不会下载/上传。
 */
class SyncEngineIgnoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val remoteRoot = "/remote/ws"
    private val syncEngines = mutableListOf<SyncEngine>()

    @After
    fun tearDown() {
        syncEngines.forEach { it.shutdown() }
        syncEngines.clear()
    }

    private fun newEngine(
        localRoot: File,
        ignoredPatternsStr: String = "",
        useGitIgnore: Boolean = false,
        client: FakeSyncClient
    ): SyncEngine {
        val connection = RemoteConnection(
            id = "c1", name = "test", protocol = RemoteProtocol.LOCAL,
            host = "example.com", port = 22, username = "u"
        )
        val mount = RemoteMount(
            id = "m1", connectionId = "c1",
            remotePath = remoteRoot, localMountPath = localRoot.absolutePath
        )
        return SyncEngine(mount, connection, client, RemoteAuth.Password("p"), ignoredPatternsStr, useGitIgnore, 50)
            .also { syncEngines.add(it) }
    }

    @Test
    fun download_skips_custom_ignored_directories() = runTest {
        val client = FakeSyncClient(
            remoteFiles = mapOf(
                remoteRoot to listOf(
                    RemoteFileInfo("a.txt", false, 10, 0),
                    RemoteFileInfo("build", true, 0, 0),
                    RemoteFileInfo("b.log", false, 10, 0)
                ),
                "$remoteRoot/build" to listOf(RemoteFileInfo("out.log", false, 10, 0))
            )
        )
        newEngine(tempFolder.root, ignoredPatternsStr = "build", client = client).downloadWorkspace()

        assertEquals(listOf("$remoteRoot/a.txt", "$remoteRoot/b.log"), client.downloaded)
        assertFalse("$remoteRoot/build/out.log" in client.downloaded)
    }

    @Test
    fun download_applies_gitignore_star_ext() = runTest {
        tempFolder.newFile(".gitignore").writeText("*.log\n")
        val client = FakeSyncClient(
            remoteFiles = mapOf(
                remoteRoot to listOf(
                    RemoteFileInfo("a.txt", false, 10, 0),
                    RemoteFileInfo("debug.log", false, 10, 0),
                    RemoteFileInfo("src", true, 0, 0)
                ),
                "$remoteRoot/src" to listOf(
                    RemoteFileInfo("main.kt", false, 10, 0),
                    RemoteFileInfo("app.log", false, 10, 0)
                )
            )
        )
        newEngine(tempFolder.root, useGitIgnore = true, client = client).downloadWorkspace()

        assertEquals(listOf("$remoteRoot/a.txt", "$remoteRoot/src/main.kt"), client.downloaded)
        assertFalse("$remoteRoot/debug.log" in client.downloaded)
        assertFalse("$remoteRoot/src/app.log" in client.downloaded)
    }

    @Test
    fun download_applies_gitignore_multi_segment_pattern() = runTest {
        tempFolder.newFile(".gitignore").writeText("build/*.log\n")
        val client = FakeSyncClient(
            remoteFiles = mapOf(
                remoteRoot to listOf(RemoteFileInfo("build", true, 0, 0)),
                "$remoteRoot/build" to listOf(
                    RemoteFileInfo("out.log", false, 10, 0),
                    RemoteFileInfo("keep.txt", false, 10, 0)
                )
            )
        )
        newEngine(tempFolder.root, useGitIgnore = true, client = client).downloadWorkspace()

        assertEquals(listOf("$remoteRoot/build/keep.txt"), client.downloaded)
        assertFalse("$remoteRoot/build/out.log" in client.downloaded)
    }

    @Test
    fun upload_skips_custom_ignored_paths() = runTest {
        tempFolder.newFile("keep.txt").writeText("keep")
        File(tempFolder.newFolder("node_modules", "pkg"), "index.js").writeText("x")
        File(tempFolder.newFolder("dist"), "bundle.js").writeText("y")

        val client = FakeSyncClient()
        newEngine(tempFolder.root, ignoredPatternsStr = "node_modules,dist", client = client).uploadWorkspace()

        assertEquals(listOf("$remoteRoot/keep.txt"), client.uploaded)
        assertTrue(client.createdDirs.isEmpty())
    }

    @Test
    fun upload_applies_gitignore() = runTest {
        tempFolder.newFile(".gitignore").writeText("*.bak\n")
        tempFolder.newFile("real.txt").writeText("real")
        tempFolder.newFile("junk.bak").writeText("junk")
        tempFolder.newFolder("logs")
        File(tempFolder.root, "logs/debug.bak").writeText("d")

        val client = FakeSyncClient()
        newEngine(tempFolder.root, useGitIgnore = true, client = client).uploadWorkspace()

        assertEquals(setOf("$remoteRoot/.gitignore", "$remoteRoot/real.txt"), client.uploaded.toSet())
        assertEquals(listOf("$remoteRoot/logs"), client.createdDirs)
        assertFalse(client.uploaded.any { it.endsWith("junk.bak") || it.endsWith("debug.bak") })
    }

    /** 记录调用轨迹的假客户端，不触网。 */
    private class FakeSyncClient(
        private val remoteFiles: Map<String, List<RemoteFileInfo>> = emptyMap()
    ) : RemoteSyncClient {
        val downloaded = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        val createdDirs = mutableListOf<String>()

        override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> =
            remoteFiles[remotePath] ?: emptyList()
        override suspend fun downloadFile(remotePath: String, localPath: String) {
            downloaded += remotePath
        }
        override suspend fun uploadFile(localPath: String, remotePath: String) {
            uploaded += remotePath
        }
        override suspend fun createDirectory(remotePath: String) {
            createdDirs += remotePath
        }
        override suspend fun delete(remotePath: String) = Unit
        override suspend fun isConnected(): Boolean = true
    }
}
