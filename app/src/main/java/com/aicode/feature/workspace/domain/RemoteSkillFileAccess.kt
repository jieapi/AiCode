package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.SshHostKeyVerifier
import com.aicode.feature.agent.domain.container.friendlySshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.EnumSet

private const val IO_CHUNK = 32 * 1024
private const val CONNECT_TIMEOUT_MS = 15_000

/** 连接远程技能服务器所需的信息，取自「远程 SSH 模式」的连接配置。 */
data class RemoteSkillConnection(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** 远程工作区根路径；容器路径 `~/workspace` 映射到这里。 */
    val workspaceRoot: String
)

/**
 * 本地模式下管理远程技能用的 [FileAccessProvider]：按 [RemoteSkillConnection] 自建一条独立的
 * SSH/SFTP 通道，不经执行模式的共享连接（[com.aicode.feature.agent.domain.container.RemoteSshConnection]），
 * 避免与命令/文件通道及模式切换相互干扰。
 *
 * 只实现技能扫描与读写需要的子集（`listFiles`/`listFilesRecursive`/`readFile`/`writeFile`/`writeBytes`/
 * `exists`/`isDirectory`/`mkdirs`/`deleteRecursively`），其余接口方法抛 [UnsupportedOperationException]——
 * 技能流程不会调用它们。路径入参为容器路径（`~/workspace/...`），经 [remotePathFor] 映射到远程真实路径。
 */
class RemoteSkillFileAccess(
    private val connection: RemoteSkillConnection,
    private val hostKeyVerifier: SshHostKeyVerifier
) : FileAccessProvider {

    private val mutex = Mutex()
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    /** 远程真实 home（连接后查 $HOME 缓存），供 `~` 展开。 */
    @Volatile
    private var remoteHome: String? = null

    private fun toRemotePath(path: String): String =
        remotePathFor(path, connection.workspaceRoot, remoteHome)

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            sftp.ls(remote).mapNotNull { info ->
                val name = info.name
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                    localFile = null,
                    permissions = formatPermissions(info.attributes.permissions)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            val result = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                val entries = runCatching { sftp.ls(dir) }.getOrNull() ?: return
                for (info in entries) {
                    val name = info.name
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (info.isDirectory) {
                        if (depth < maxDepth) walk(info.path, childRel, depth + 1)
                    } else {
                        result += childRel
                    }
                }
            }
            walk(remote, "", 1)
            result
        }
    }

    override fun readFile(path: String): String = String(readAll(toRemotePath(path)), Charsets.UTF_8)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            sftp.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                writeAll(rf, bytes)
            }
        }
    }

    override fun exists(path: String): Boolean = withSftp { it.statExistence(toRemotePath(path)) != null }

    override fun isDirectory(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.DIRECTORY }

    override fun mkdirs(path: String) = withSftp { it.mkdirs(toRemotePath(path)) }

    override fun deleteRecursively(path: String) = withSftp { deleteRecursive(it, toRemotePath(path)) }

    override fun readLines(path: String): Sequence<String> = unsupported()
    override fun isFile(path: String): Boolean = unsupported()
    override fun fileSize(path: String): Long = unsupported()
    override fun lastModified(path: String): Long = unsupported()
    override fun permissions(path: String): String = unsupported()
    override fun readBytes(path: String): ByteArray = unsupported()
    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = unsupported()
    override fun copyToLocal(path: String): File = unsupported()
    override fun delete(path: String): Unit = unsupported()
    override fun rename(path: String, newPath: String): Unit = unsupported()
    override fun copy(path: String, newPath: String, overwrite: Boolean): Unit = unsupported()
    override fun move(path: String, newPath: String, overwrite: Boolean): Unit = unsupported()
    override fun parentPath(path: String): String? = unsupported()
    override fun toDisplayPath(path: String): String = unsupported()

    /** 关闭底层 SSH/SFTP 通道；之后再次调用任一方法会按需重连。 */
    fun close() = runBlocking {
        withContext(Dispatchers.IO) { mutex.withLock { closeInternal() } }
    }

    private fun <T> withSftp(block: (SFTPClient) -> T): T = runBlocking {
        withContext(Dispatchers.IO) { mutex.withLock { block(ensureSftpLocked()) } }
    }

    /** 取当前 SFTP 通道；失效则关闭重建。调用方须已持有 [mutex]。 */
    private fun ensureSftpLocked(): SFTPClient {
        val existing = sftpClient
        if (existing != null && sshClient?.isConnected == true && sshClient?.isAuthenticated == true) {
            return existing
        }
        closeInternal()
        val conn = connection
        val client = try {
            SSHClient().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                addHostKeyVerifier(hostKeyVerifier)
                connect(conn.host, conn.port)
                authPassword(conn.username, conn.password)
                runCatching {
                    connection.keepAlive?.let {
                        it.setKeepAliveInterval(30)
                        it.start()
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException(friendlySshError(e), e)
        }
        sshClient = client
        runCatching {
            val session = client.startSession()
            val cmd = session.exec("echo \$HOME")
            remoteHome = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                .readText().trim().ifEmpty { null }
            session.close()
        }
        val sftp = client.newSFTPClient()
        sftpClient = sftp
        return sftp
    }

    private fun closeInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sshClient?.disconnect() }
        sftpClient = null
        sshClient = null
    }

    /** 读取远程文件全部字节；不存在抛 [NoSuchFileException]。 */
    private fun readAll(remote: String): ByteArray = withSftp { sftp ->
        val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
        if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("是目录，无法按文件读取: $remote")
        sftp.open(remote).use { rf -> readFully(rf) }
    }

    private fun readFully(rf: RemoteFile): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(IO_CHUNK)
        var offset = 0L
        while (true) {
            val n = rf.read(offset, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            offset += n
        }
        return out.toByteArray()
    }

    private fun writeAll(rf: RemoteFile, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(IO_CHUNK, bytes.size - offset)
            rf.write(offset.toLong(), bytes, offset, n)
            offset += n
        }
    }

    /** 递归删除（SFTP 无递归删除原语）：后序遍历，子项删完后 rmdir。 */
    private fun deleteRecursive(sftp: SFTPClient, remote: String) {
        val attrs = sftp.statExistence(remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, info.path)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    private fun ensureParent(sftp: SFTPClient, remote: String) {
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) sftp.mkdirs(parent)
    }

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("RemoteSkillFileAccess 不支持该操作")
}
