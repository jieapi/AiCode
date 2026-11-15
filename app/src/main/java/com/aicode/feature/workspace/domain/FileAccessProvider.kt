package com.aicode.feature.workspace.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/** 目录条目信息，供 [FileAccessProvider.listFiles] 返回。 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录可为 0。 */
    val size: Long,
    /** 最后修改时间（epoch 毫秒）。 */
    val lastModified: Long,
    /** 本地模式下可提供宿主 [File]，供需要本地路径的调用方（如 [BitmapFactory]）使用；远程模式为 null。 */
    val localFile: File? = null,
    /** 可读/可写/可执行权限位（rwx 字符串，如 "rwx"）；远程按 SFTP 权限解析，无法获取时给合理默认。 */
    val permissions: String = "---"
)

/**
 * 文件读写后端抽象：把"在哪读写文件"从硬编码的本地 java.io.File 解耦。
 *
 * 两套实现：
 * - `LocalFileAccess`：包一层 [WorkspacePathMapper]，走 java.io.File 直读（本地模式原行为不变）；
 * - `RemoteSftpFileAccess`：用 SFTP 读写远程文件。
 *
 * 路径入参统一为 AI 给的"容器路径"（`~/workspace/...`），由实现内部映射到本地宿主路径或远程路径。
 * 工具层（FileTools/ImageTools/ListFilesTool/EditFileTool）依赖本接口而非具体实现，
 * 由 DI 按当前执行模式注入对应实例。
 *
 * 需要本地文件路径的能力（如 [android.graphics.BitmapFactory.decodeFile]）用 [readBytes] 或
 * [copyToLocal] 拿到本地临时文件再处理——远程模式下会 SFTP 下载到临时目录。
 */
interface FileAccessProvider {

    /**
     * 读取文件全文文本。文件不存在时抛 [NoSuchFileException]。
     * 远程模式对超过实现上限的文件抛 [RemoteOutputTooLargeException]，避免整篇读进内存。
     */
    fun readFile(path: String): String

    /**
     * 惰性逐行读取文件，供 [ReadFileTool] 按行窗口读取：读到哪算哪，整文件不进内存；
     * 单行超过上限（默认 64K 字符）会被截断。
     * 序列可重复迭代（每次迭代重新打开底层读取）；调用方需在 IO 线程上迭代；文件不存在时抛 [NoSuchFileException]。
     */
    fun readLines(path: String): Sequence<String>

    /**
     * 写入文件全文。父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * [encoding] 指定落盘字符编码；缺省 UTF-8。调用方应把「读入时检测到的编码」原样回传，
     * 避免 GBK 等非 UTF-8 文件在保存时被静默转码破坏。
     */
    fun writeFile(path: String, content: String, overwrite: Boolean = true, encoding: Charset = Charsets.UTF_8)

    /** 文件是否存在。 */
    fun exists(path: String): Boolean

    /** 文件是否为目录。 */
    fun isDirectory(path: String): Boolean

    /** 文件是否为普通文件。 */
    fun isFile(path: String): Boolean

    /** 文件大小（字节）。 */
    fun fileSize(path: String): Long

    /** 最后修改时间（epoch 毫秒）。 */
    fun lastModified(path: String): Long

    /**
     * 文件内容指纹，用于多会话并发编辑的写前校验（乐观并发控制）。
     *
     * 返回 `sha256:<十六进制>`；文件不存在或不是普通文件时返回 null。
     *
     * 选**内容哈希**而非 `size + mtime` 的原因：远端 SFTP 的 mtime 只有秒级精度
     * （见 [RemoteSftpFileAccess.lastModified]），同一秒内的两次写入无法区分，
     * 会让「过期写」漏判。内容哈希无精度问题，代价是每次校验多一次整文件读取——
     * 调用方（editFile/writeFile）本来就要读原文，增量成本可控。
     *
     * 注意：指纹按**原始字节**计算，与调用方用哪种 [Charset] 解码无关；同一文件
     * 只要字节未变，指纹就稳定。
     */
    fun fingerprint(path: String): String?

    /** 权限位字符串（如 "rwx"）。 */
    fun permissions(path: String): String

    /**
     * 列出目录下的条目。本地实现用 [File.listFiles]；远程实现用 SFTP ls。
     * 不含 `.` 和 `..`。返回的 [FileEntry.localFile] 在远程模式下为 null。
     */
    fun listFiles(path: String): List<FileEntry>

    /**
     * 递归列出 [path] 下所有普通文件，深度不超过 [maxDepth]（[path] 的直接子项算第 1 层）。
     * 返回相对 [path] 的路径（`/` 分隔）；目录不存在时返回空列表。
     * 供技能/子代理目录扫描使用。
     */
    fun listFilesRecursive(path: String, maxDepth: Int): List<String>

    /**
     * 读取文件原始字节。供 [ViewImageTool] 等需要二进制数据的工具使用。
     * 远程模式下若调用方需要本地文件路径，改用 [copyToLocal]；文件过大时抛 [RemoteOutputTooLargeException]。
     */
    fun readBytes(path: String): ByteArray

    /** 读取前及读取过程中都限制大小；超出 [maxBytes] 抛 [FileReadTooLargeException]。 */
    fun readBytes(path: String, maxBytes: Long): ByteArray

    /**
     * 按后端文件系统语义逐段解析路径，返回可用于相等/祖先比较的绝对身份路径。
     * 允许不存在的末级路径；不得先词法消除 `..`，以免改变 `link/..` 的含义。
     * [followFinalLink] 为 false 时保留末级符号链接本身，父路径仍解析链接。
     * 返回值只用于比较，不应作为后端操作的输入（本地可能返回宿主路径）。
     */
    fun resolvePath(path: String, followFinalLink: Boolean = true): String

    /**
     * 写入文件原始字节。父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * 供 [GenerateImageTool] 等需要落盘二进制数据（图片等）的工具使用，也用于上传附件等二进制写入；
     * 文本内容仍用 [writeFile]。
     */
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

    /**
     * 从 [input] 流式写入文件，返回写入的字节数；内容不整体驻留内存。
     * 父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * 供上传大附件等场景使用。
     */
    fun writeStream(path: String, input: InputStream, overwrite: Boolean = true): Long

    /**
     * 把文件复制到本地临时文件并返回其 [File]。
     * 本地模式直接返回映射后的宿主 [File]（不复制）；
     * 远程模式 SFTP 下载到缓存目录，返回本地临时文件。
     * 供 [ViewImageTool]（需本地路径喂 BitmapFactory）等使用。
     */
    fun copyToLocal(path: String): File

    /** 删除文件或空目录。 */
    fun delete(path: String)

    /** 递归删除文件或目录（目录非空时连同内容一起删）。目标不存在时静默返回。 */
    fun deleteRecursively(path: String)

    /**
     * 重命名 / 移动。[newPath] 已存在时抛 [FileAlreadyExistsException]，
     * [path] 不存在时抛 [NoSuchFileException]，其它失败抛 [java.io.IOException]。
     */
    fun rename(path: String, newPath: String)

    /**
     * 复制文件或目录（含子内容）到 [newPath]。[path] 不存在时抛 [NoSuchFileException]，
     * 其它失败抛 [java.io.IOException]。[overwrite] 为 false 且 [newPath] 已存在时抛
     * [FileAlreadyExistsException]；为 true 时覆盖。
     */
    fun copy(path: String, newPath: String, overwrite: Boolean = false)

    /**
     * 将 [path] 移动（剪切）到 [newPath]。[path] 不存在时抛 [NoSuchFileException]，
     * 其它失败抛 [java.io.IOException]。[overwrite] 为 false 且 [newPath] 已存在时抛
     * [FileAlreadyExistsException]；为 true 时覆盖。
     * 实现可用 rename 或「复制 + 删除源」。
     */
    fun move(path: String, newPath: String, overwrite: Boolean = false)

    /** 创建目录（含父目录）。 */
    fun mkdirs(path: String)

    /** 获取父目录路径（容器路径形式）。 */
    fun parentPath(path: String): String?

    /** 把内部真实路径还原为 AI 视角的容器路径（回显用）。 */
    fun toDisplayPath(path: String): String
}

/**
 * 计算文件内容指纹：`sha256:<十六进制小写>`。三套 [FileAccessProvider] 实现共用，
 * 保证同一份字节在不同后端下得到同一指纹字符串。
 *
 * 前缀写进指纹本身（而不是只存裸 hex），将来若换算法（如 blake3），旧记录与新记录
 * 天然不可比，会退化成「未知版本」而不是**错误地判定为相等**——这是并发校验里更安全的失败方向。
 */
internal fun sha256Fingerprint(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2 + 7) {
        append("sha256:")
        for (b in digest) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }
}

class FileReadTooLargeException(val maxBytes: Long) : IOException("file exceeds $maxBytes bytes")

internal fun InputStream.readBytesBounded(maxBytes: Long): ByteArray {
    require(maxBytes in 0..Int.MAX_VALUE.toLong())
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val length = minOf(buffer.size.toLong(), maxBytes - total + 1).toInt()
        val count = read(buffer, 0, length)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw FileReadTooLargeException(maxBytes)
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private const val HEX_CHARS = "0123456789abcdef"

/**
 * 单个目录条目名是否合法：非空、不含路径分隔符、不是 `.` 或 `..`。
 * 拦掉 `../` 这类会跳出当前目录的输入，供文件浏览的新建/重命名使用。
 */
fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}
