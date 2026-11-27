package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RemoteSftpFileAccessSafetyTest {
    private val connection = mockk<RemoteSshConnection>(relaxed = true)
    private val workspace = mockk<WorkspaceRepository>()
    private val sftp = mockk<SFTPClient>(relaxed = true)
    private val lease = mockk<RemoteSshConnection.SftpLease>(relaxed = true) {
        every { client } returns sftp
    }
    private val access = RemoteSftpFileAccess(connection, workspace, mockk(relaxed = true))
    private val directory = FileAttributes.Builder().withType(FileMode.Type.DIRECTORY).build()
    private val symlink = FileAttributes.Builder().withType(FileMode.Type.SYMLINK).build()

    @Before
    fun setUp() {
        coEvery { connection.acquireSftp() } returns lease
        every { connection.remoteHome } returns "/root"
        every { connection.config } returns mockk(relaxed = true)
        every { workspace.currentPath() } returns "/root/workspace"
        every { sftp.lstat(any()) } returns directory
        every { sftp.canonicalize(any()) } throws SFTPException(StatusCode.OP_UNSUPPORTED, "unsupported")
    }

    @Test
    fun resolvePath_handlesAliasesAndLinkBeforeParent() {
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.readlink("/root/workspace/link") } returns "/root/.aicode/plugins"
        assertEquals("/root/workspace", access.resolvePath("/root//workspace/."))
        assertEquals("/root/.aicode", access.resolvePath("link/.."))
        assertEquals("/root/workspace/link", access.resolvePath("link", followFinalLink = false))
        assertEquals("/root/.aicode/plugins", access.resolvePath("link/.", followFinalLink = false))
        every { sftp.lstat("/root/.aicode/new") } throws SFTPException(StatusCode.NO_SUCH_FILE, "missing")
        assertEquals("/root/.aicode/new/file", access.resolvePath("link/../new/file"))
    }

    @Test
    fun resolvePath_handlesRelativeLinksAndDoesNotHidePermissionErrors() {
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.readlink("/root/workspace/link") } returns "../.aicode/plugins"
        assertEquals("/root/.aicode", access.resolvePath("link/.."))
        every { sftp.lstat("/denied") } throws SFTPException(StatusCode.PERMISSION_DENIED, "denied")
        assertThrows(SFTPException::class.java) { access.resolvePath("/denied/file") }
    }

    @Test
    fun resolvePath_rejectsLinkCyclesButAllowsLinkEntry() {
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.readlink("/root/workspace/link") } returns "link"
        assertThrows(FileSystemLoopException::class.java) { access.resolvePath("link") }
        assertEquals("/root/workspace/link", access.resolvePath("link", followFinalLink = false))
    }

    @Test
    fun deleteAndRename_linkEntryNeverTraversesTarget() {
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.lstat("/root/workspace/renamed") } throws SFTPException(StatusCode.NO_SUCH_FILE, "missing")
        access.deleteRecursively("link/")
        access.delete("link/")
        access.rename("link/", "renamed/")
        verify { sftp.rm("/root/workspace/link") }
        verify { sftp.rename("/root/workspace/link", "/root/workspace/renamed") }
        verify(exactly = 0) { sftp.ls(any<String>()) }
        verify(exactly = 0) { sftp.readlink(any()) }
        verify(exactly = 0) { sftp.statExistence(any()) }
    }

    @Test
    fun resolvePath_usesServerCanonicalizationAndPreservesFinalLink() {
        every { sftp.canonicalize("/root/workspace/link/..") } returns "/data/parent"
        assertEquals("/data/parent", access.resolvePath("link/.."))
        every { sftp.canonicalize("/root/workspace") } returns "/physical/workspace"
        assertEquals("/physical/workspace/link", access.resolvePath("link/", followFinalLink = false))
        every { sftp.canonicalize("/denied") } throws SFTPException(StatusCode.PERMISSION_DENIED, "denied")
        assertThrows(SFTPException::class.java) { access.resolvePath("/denied") }
        verify(exactly = 0) { sftp.lstat(any()) }
    }

    @Test
    fun resolvePath_missingServerPathFallsBackWithoutLexicallyCollapsingLinkParent() {
        every { sftp.canonicalize("/root/workspace/link/../new/file") } throws
            SFTPException(StatusCode.NO_SUCH_FILE, "missing")
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.readlink("/root/workspace/link") } returns "/data/tree/deep"
        every { sftp.lstat("/data/tree/new") } throws SFTPException(StatusCode.NO_SUCH_FILE, "missing")
        assertEquals("/data/tree/new/file", access.resolvePath("link/../new/file"))
    }

    @Test
    fun copy_resolvesLinkBeforeParentAndRejectsNestedAlias() {
        every { sftp.lstat("/root/workspace/link") } returns symlink
        every { sftp.readlink("/root/workspace/link") } returns "/data/tree/deep"
        every { sftp.statExistence("/data/tree") } returns directory
        every { sftp.lstat("/root/workspace/output") } throws SFTPException(StatusCode.NO_SUCH_FILE, "missing")
        every { sftp.ls("/data/tree") } returns emptyList()
        access.copy("link/..", "output", overwrite = false)
        verify { sftp.ls("/data/tree") }
        verify { sftp.mkdirs("/root/workspace/output") }

        every { sftp.lstat("/root/workspace/alias") } returns symlink
        every { sftp.readlink("/root/workspace/alias") } returns "/root/workspace/dir"
        every { sftp.statExistence("/root/workspace/dir") } returns directory
        assertThrows(IOException::class.java) { access.copy("dir", "alias/child", overwrite = true) }
        verify(exactly = 0) { sftp.mkdirs("/root/workspace/dir/child") }
    }

    @Test
    fun copyToLocal_preservesRemoteFileNameInIsolatedCacheDirectory() {
        every { sftp.statExistence("/root/workspace/images/photo.png") } returns
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(4).build()
        every { sftp.get("/root/workspace/images/photo.png", any<String>()) } answers {
            File(secondArg<String>()).writeText("data")
        }

        val local = access.copyToLocal("images/photo.png")
        try {
            assertEquals("photo.png", local.name)
            assertEquals("data", local.readText())
            assertTrue(local.parentFile?.name?.startsWith("aicode_remote_") == true)
            verify { sftp.get("/root/workspace/images/photo.png", match<String> { it.endsWith("/photo.png") }) }
        } finally {
            local.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun boundedRead_oversizeStatDoesNotOpenFile() {
        every { sftp.statExistence("/root/workspace/large") } returns
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(256L * 1024 * 1024).build()
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes("large", 8L * 1024 * 1024) }
        verify(exactly = 0) { sftp.open(any<String>()) }
        coVerify(exactly = 0) { connection.invalidateSftpChannel(any()) }
    }

    @Test
    fun boundedRead_stopsGrowingFileAfterLimitPlusOneAndClosesHandle() {
        every { sftp.statExistence("/root/workspace/growing") } returns
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(1).build()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open("/root/workspace/growing") } returns remote
        var bytesRead = 0L
        every { remote.read(any(), any(), any(), any()) } answers {
            arg<Int>(3).also { bytesRead += it }
        }
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes("growing", 8192) }
        assertEquals(8193L, bytesRead)
        verify(exactly = 1) { remote.close() }
        coVerify(exactly = 0) { connection.invalidateSftpChannel(any()) }
    }

    @Test
    fun boundedRead_acceptsExactLimit() {
        every { sftp.statExistence("/root/workspace/small") } returns
            FileAttributes.Builder().withType(FileMode.Type.REGULAR).withSize(3).build()
        val remote = mockk<RemoteFile>(relaxed = true)
        every { sftp.open("/root/workspace/small") } returns remote
        every { remote.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>()
            if (offset == 0L) {
                byteArrayOf(1, 2, 3).copyInto(arg(1), arg(2))
                3
            } else -1
        }
        assertEquals(listOf<Byte>(1, 2, 3), access.readBytes("small", 3).toList())
        verify(exactly = 1) { remote.close() }
    }
}
