package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.friendlySshError
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper.Companion.CONTAINER_ROOT
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import javax.inject.Inject

private const val TAG = "RemoteSftpFileAccess"

/** writeFile 单次 printf 传入的 base64 分块大小（4 的倍数），远小于 exec 的 MAX_ARG_STRLEN(128KB)。 */
private const val BASE64_CHUNK = 48 * 1024

/**
 * [FileAccessProvider] 的远程实现：用 SSH exec channel 执行命令读写远程文件。
 *
 * **不用 SFTP**：sshj 0.38.0 的 SFTP 实现有长期未修的 Buffer 溢出 bug（issue #461），
 * 在 Android 上必现 `ArrayIndexOutOfBoundsException: dstPos=-4`，且崩溃会拖垮整个 SSH transport。
 * exec channel 不走 SFTPEngine，无此问题。`Bash` 工具已验证 exec 可靠。
 *
 * 路径映射：AI 给的 `~/workspace/...` 映射到 [RemoteSshConnection.config] 的 `remoteWorkspacePath` + 相对路径。
 * 其它绝对路径（如 `/etc/...`）直接作为远程绝对路径使用。
 *
 * 文本文件用 `cat`/重定向读写，二进制文件用 `base64` 中转。
 */
class RemoteSftpFileAccess @Inject constructor(
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository
) : FileAccessProvider {

    /** 当前选中工作区在远程服务器上的真实路径（如 /data/.../test/111）。 */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH 未连接")
        // currentPath() 远程模式返回选中工作区的远程绝对路径；未选中时回退到 remoteWorkspacePath
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    /** 把 AI 路径映射到远程服务器上的真实路径。
     *  ~/workspace 映射到当前选中工作区（与本地模式 WorkspacePathMapper 行为一致），
     *  其它绝对路径直接作为远程绝对路径使用。 */
    private fun toRemotePath(path: String): String =
        remotePathFor(path, currentWorkspaceRoot(), connection.remoteHome)

    /** 把远程路径还原为 AI 视角的容器路径（回显用）。 */
    private fun toDisplayPathFromRemote(remotePath: String): String =
        displayPathFor(remotePath, currentWorkspaceRoot())

    /** 同步执行远程命令并返回完整 stdout。失败时抛友好异常。 */
    private fun execSync(command: String): String = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val session = try {
                connection.startExecSession(command)
            } catch (e: Exception) {
                throw RuntimeException(friendlySshError(e), e)
            }
            try {
                val reader = BufferedReader(InputStreamReader(session.inputStream))
                val output = reader.readText()
                // readText 读到流结束，但 exitStatus 可能还没就绪——close 后才保证有值
                runCatching { session.close() }
                val exitCode = session.exitStatus
                if (exitCode != null && exitCode != 0) {
                    FileLogger.w(TAG, "命令退出码=$exitCode: $command")
                }
                output
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }
    }

    /** 同步执行远程命令，返回「输出 + 退出码」。startExecSession 抛异常（未连接）时向外抛。 */
    private fun execSyncChecked(command: String): Pair<String, Int?> = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val session = connection.startExecSession(command)
            try {
                val output = BufferedReader(InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                output to session.exitStatus
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }
    }

    /** 同步执行远程命令，返回退出码（不抛异常）。 */
    private fun execExitCode(command: String): Int = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val session = try {
                connection.startExecSession(command)
            } catch (e: Exception) {
                FileLogger.w(TAG, friendlySshError(e), e)
                return@withContext -1
            }
            try {
                BufferedReader(InputStreamReader(session.inputStream)).readText()
                runCatching { session.close() }
                session.exitStatus ?: -1
            } catch (e: Exception) {
                runCatching { session.close() }
                FileLogger.w(TAG, "命令执行异常: $command", e)
                -1
            }
        }
    }

    override fun readFile(path: String): String {
        val remote = toRemotePath(path)
        val (output, exit) = runCatching { execSyncChecked("cat ${shellQuote(remote)}") }
            .getOrElse {
                FileLogger.e(TAG, "readFile 失败: $remote", it)
                throw NoSuchFileException(File(remote))
            }
        // cat 对不存在的文件退出码非零：兑现「文件不存在抛 NoSuchFileException」的接口契约。
        if (exit != 0) throw NoSuchFileException(File(remote))
        return output
    }

    override fun readLines(path: String): Sequence<String> {
        val remote = toRemotePath(path)
        val (output, exit) = runCatching { execSyncChecked("cat ${shellQuote(remote)}") }
            .getOrElse { throw NoSuchFileException(File(remote)) }
        if (exit != 0) throw NoSuchFileException(File(remote))
        return output.lines().asSequence()
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val remote = toRemotePath(path)
        if (exists(path) && !overwrite) throw FileAlreadyExistsException(File(remote))
        // 确保父目录存在
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) execExitCode("mkdir -p ${shellQuote(parent)}")
        // 内容按指定编码转字节，再 base64 中转落盘（单行、无换行/引号/特殊字符）。
        // 分块写入：整段 base64 作单个 printf 参数会撞 exec 的 MAX_ARG_STRLEN(128KB) 上限，
        // 大文件据此 E2BIG 失败；这里拆成 48KB 一段（4 的倍数），逐段 printf | base64 -d >> 落盘。
        val bytes = content.toByteArray(encoding)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        if (b64.isEmpty()) {
            // 空内容：overwrite 时截断为空文件；追加模式为空操作（无块可写，避免漏掉 truncate 语义）。
            if (overwrite) {
                val exit = execExitCode(": > ${shellQuote(remote)}")
                if (exit != 0) throw IOException("writeFile 截断失败 退出码=$exit: $remote")
            }
            return
        }
        // 首块用覆盖/追加，其余块一律追加。
        val redirect = if (overwrite) ">" else ">>"
        b64.chunked(BASE64_CHUNK).forEachIndexed { i, chunk ->
            val op = if (i == 0) redirect else ">>"
            val exit = execExitCode("printf %s ${shellQuote(chunk)} | base64 -d $op ${shellQuote(remote)}")
            if (exit != 0) throw IOException("writeFile 分块写入失败 退出码=$exit: $remote")
        }
    }

    override fun exists(path: String): Boolean {
        val remote = toRemotePath(path)
        return execExitCode("test -e ${shellQuote(remote)}") == 0
    }

    override fun isDirectory(path: String): Boolean {
        val remote = toRemotePath(path)
        return execExitCode("test -d ${shellQuote(remote)}") == 0
    }

    override fun isFile(path: String): Boolean {
        val remote = toRemotePath(path)
        return execExitCode("test -f ${shellQuote(remote)}") == 0
    }

    override fun fileSize(path: String): Long {
        val remote = toRemotePath(path)
        return runCatching {
            execSync("stat -c %s ${shellQuote(remote)} 2>/dev/null").trim().toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    override fun lastModified(path: String): Long {
        val remote = toRemotePath(path)
        return runCatching {
            // stat -c %Y 返回 epoch 秒，转毫秒
            (execSync("stat -c %Y ${shellQuote(remote)} 2>/dev/null").trim().toLongOrNull() ?: 0L) * 1000L
        }.getOrDefault(0L)
    }

    override fun permissions(path: String): String {
        val remote = toRemotePath(path)
        return runCatching {
            // stat -c %A 返回符号权限如 -rwxr-xr-x，取后 9 位
            val sym = execSync("stat -c %A ${shellQuote(remote)} 2>/dev/null").trim()
            sym.takeLast(9).takeIf { it.length == 9 } ?: "---"
        }.getOrDefault("---")
    }

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return runCatching {
            // 用 stat 一次性输出 name|type|size|mtime|perms，避免多次 exec
            // %n=文件名 %F=类型 %s=大小 %Y=mtime %A=权限
            // 同时 stat * 与 .*：shell glob 默认不匹配点开头的文件（POSIX 行为），
            // .git/.hidden 等隐藏项要靠 .* 才列得出；. 与 .. 也会被 .* 匹配，由 parseStatEntryLine 过滤
            val output = execSync(
                "stat -c '%n|%F|%s|%Y|%A' ${shellQuote(remote)}/* ${shellQuote(remote)}/.* 2>/dev/null"
            )
            if (output.isBlank()) return emptyList()
            output.lines().mapNotNull { parseStatEntryLine(it) }
        }.getOrElse {
            FileLogger.w(TAG, "listFiles 失败: $remote", it)
            emptyList()
        }
    }

    override fun readBytes(path: String): ByteArray {
        val remote = toRemotePath(path)
        return runCatching {
            // 二进制用 base64 中转
            val b64 = execSync("base64 ${shellQuote(remote)} 2>/dev/null")
            java.util.Base64.getMimeDecoder().decode(b64)
        }.getOrElse {
            FileLogger.e(TAG, "readBytes 失败: $remote", it)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return runCatching {
            val output = execSync("find ${shellQuote(remote)} -maxdepth $maxDepth -type f 2>/dev/null")
            val prefix = remote.trimEnd('/') + "/"
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { if (it.startsWith(prefix)) it.removePrefix(prefix) else it }
                .toList()
        }.getOrElse {
            FileLogger.w(TAG, "listFilesRecursive 失败: $remote", it)
            emptyList()
        }
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        if (exists(path) && !overwrite) throw FileAlreadyExistsException(File(remote))
        // 与 writeFile 相同的 base64 分块落盘：整段作单个 printf 参数会撞 exec 的 MAX_ARG_STRLEN(128KB) 上限。
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) execExitCode("mkdir -p ${shellQuote(parent)}")
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        if (b64.isEmpty()) {
            if (overwrite) {
                val exit = execExitCode(": > ${shellQuote(remote)}")
                if (exit != 0) throw IOException("writeBytes 截断失败 退出码=$exit: $remote")
            }
            return
        }
        val redirect = if (overwrite) ">" else ">>"
        b64.chunked(BASE64_CHUNK).forEachIndexed { i, chunk ->
            val op = if (i == 0) redirect else ">>"
            val exit = execExitCode("printf %s ${shellQuote(chunk)} | base64 -d $op ${shellQuote(remote)}")
            if (exit != 0) throw IOException("writeBytes 分块写入失败 退出码=$exit: $remote")
        }
    }

    override fun copyToLocal(path: String): File {
        val remote = toRemotePath(path)
        val tempFile = File.createTempFile("aicode_remote_", ".copy")
        return try {
            // base64 解码到本地临时文件
            val b64 = execSync("base64 ${shellQuote(remote)} 2>/dev/null")
            tempFile.writeBytes(java.util.Base64.getMimeDecoder().decode(b64))
            tempFile
        } catch (e: Exception) {
            // 用完即删：临时文件不依赖 deleteOnExit（只在进程退出清），失败路径也回收，避免长会话累积。
            runCatching { tempFile.delete() }
            FileLogger.e(TAG, "copyToLocal 失败: $remote", e)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun delete(path: String) {
        val remote = toRemotePath(path)
        // 接口约定 delete 只删文件或空目录：rm -f 删文件（-f 忽略不存在），
        // rmdir 删空目录；目录非空时两者都失败 → 抛异常，与本地 File.delete()（只删空目录）对齐。
        // 用 `||` 短路：文件走 rm 成功即止，空目录走 rmdir 成功即止。
        val exit = execExitCode("rm -f ${shellQuote(remote)} || rmdir ${shellQuote(remote)}")
        if (exit != 0) throw IOException("delete 退出码=$exit: $remote")
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        val exit = execExitCode("rm -rf ${shellQuote(remote)}")
        if (exit != 0) throw IOException("rm -rf 退出码=$exit: $remote")
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(from))
        // busybox mv 未必支持 -n，先自行判存再 mv，避免静默覆盖同名目标
        if (exists(newPath)) throw FileAlreadyExistsException(File(to))
        val exit = execExitCode("mv ${shellQuote(from)} ${shellQuote(to)}")
        if (exit != 0) throw IOException("mv 退出码=$exit: $from -> $to")
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(from))
        if (exists(newPath)) {
            if (!overwrite) throw FileAlreadyExistsException(File(to))
            // 覆盖：先删目标，再 cp -r（busybox 无 -T，先删避免目标已存在目录时静默嵌套）
            execExitCode("rm -rf ${shellQuote(to)}")
        }
        val exit = execExitCode("cp -r ${shellQuote(from)} ${shellQuote(to)}")
        if (exit != 0) throw IOException("cp 退出码=$exit: $from -> $to")
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        if (!exists(path)) throw NoSuchFileException(File(from))
        if (exists(newPath)) {
            if (!overwrite) throw FileAlreadyExistsException(File(to))
            // 覆盖：先删目标再 mv（busybox mv 未必支持 -n/-f，先删保证语义）
            execExitCode("rm -rf ${shellQuote(to)}")
        }
        val exit = execExitCode("mv ${shellQuote(from)} ${shellQuote(to)}")
        if (exit != 0) throw IOException("mv 退出码=$exit: $from -> $to")
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        execExitCode("mkdir -p ${shellQuote(remote)}")
    }

    override fun parentPath(path: String): String? {
        val remote = toRemotePath(path)
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return toDisplayPathFromRemote(parent)
    }

    override fun toDisplayPath(path: String): String = toDisplayPathFromRemote(toRemotePath(path))
}

/** AI 路径 → 远程真实路径：`~/workspace` 前缀映射到 [workspaceRoot]，其它绝对路径原样使用。 */
internal fun remotePathFor(path: String, workspaceRoot: String, remoteHome: String?): String {
    val root = workspaceRoot.trimEnd('/')
    // CONTAINER_ROOT 是 ~/workspace，展开 ~ 后做前缀匹配
    val wsRoot = (remoteHome ?: "~").trimEnd('/') + "/workspace"
    val p = path.trim().let {
        if (it.startsWith("~/")) {
            val home = remoteHome
            if (home != null) home.trimEnd('/') + "/" + it.removePrefix("~/") else it
        } else it
    }
    return when {
        p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
        p.startsWith("$wsRoot/") ->
            root + "/" + p.removePrefix("$wsRoot/")
        p.startsWith("/") -> p
        else -> root + "/" + p
    }
}

/** 远程真实路径 → AI 视角的容器路径（回显用）。 */
internal fun displayPathFor(remotePath: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    return when {
        remotePath == root -> CONTAINER_ROOT
        remotePath.startsWith("$root/") -> CONTAINER_ROOT + "/" + remotePath.removePrefix("$root/")
        else -> remotePath
    }
}

/** 单引号转义：路径含单引号时用 `'\''` 绕过，保证 shell 命令安全。 */
internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 解析 `stat -c '%n|%F|%s|%Y|%A'` 输出行；字段不足、名称为空或为 . / .. 返回 null。 */
internal fun parseStatEntryLine(line: String): FileEntry? {
    val parts = line.split("|")
    if (parts.size < 5) return null
    val name = parts[0].substringAfterLast('/')
    if (name.isBlank() || name == "." || name == "..") return null
    return FileEntry(
        name = name,
        isDirectory = parts[1].contains("directory", ignoreCase = true),
        size = parts[2].toLongOrNull() ?: 0L,
        lastModified = (parts[3].toLongOrNull() ?: 0L) * 1000L,
        localFile = null,
        permissions = parts[4].takeLast(9).ifBlank { "---" }
    )
}
