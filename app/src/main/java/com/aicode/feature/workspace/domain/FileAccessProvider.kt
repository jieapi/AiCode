package com.aicode.feature.workspace.domain

import java.io.File

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

    /** 读取文件全文文本。文件不存在时抛 [NoSuchFileException]。 */
    fun readFile(path: String): String

    /**
     * 逐行读取文件，供 [ReadFileTool] 按行窗口读取。
     * 返回行序列；文件不存在时抛 [NoSuchFileException]。
     * 本地实现用 useLines 流式读；远程实现先 SFTP 下载到临时文件再逐行读（或直接 SFTP 读流按行切）。
     */
    fun readLines(path: String): Sequence<String>

    /** 写入文件全文。父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。 */
    fun writeFile(path: String, content: String, overwrite: Boolean = true)

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
     * 远程模式下若调用方需要本地文件路径，改用 [copyToLocal]。
     */
    fun readBytes(path: String): ByteArray

    /**
     * 写入原始字节。父目录不存在则自动创建。
     * [overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * 供上传附件等二进制写入使用。
     */
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

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

    /** 创建目录（含父目录）。 */
    fun mkdirs(path: String)

    /** 获取父目录路径（容器路径形式）。 */
    fun parentPath(path: String): String?

    /** 把内部真实路径还原为 AI 视角的容器路径（回显用）。 */
    fun toDisplayPath(path: String): String
}

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
