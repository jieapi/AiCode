package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aicode.R
import com.aicode.core.util.ImageDownscaler
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class UploadedWorkspaceFile(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null
)

internal data class PendingUploadAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null,
)

internal val PendingUploadAttachment.isImage: Boolean
    get() = image != null

private fun List<PendingUploadAttachment>.toAttachmentText(context: Context): String {
    if (isEmpty()) return ""
    return buildString {
        append(context.getString(R.string.chat_attachment_prefix))
        this@toAttachmentText.forEach { attachment ->
            append('\n')
            append("- ")
            append(attachment.fileName)
            append("：")
            append(attachment.containerPath)
        }
    }
}

internal fun appendAttachmentsToRequest(
    context: Context,
    request: String,
    attachments: List<PendingUploadAttachment>
): String {
    val attachmentText = attachments.toAttachmentText(context)
    if (attachmentText.isBlank()) return request
    if (request.isBlank()) return attachmentText
    return request.trimEnd() + "\n\n" + attachmentText
}

internal fun UploadedWorkspaceFile.toPendingAttachment(): PendingUploadAttachment =
    PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )

internal fun AgentAttachment.toPendingAttachment(): PendingUploadAttachment {
    val image = if (isImage && localPath.isNotBlank()) {
        val file = File(localPath)
        if (file.exists() && file.isFile && file.length() > 0) {
            try {
                ImageDownscaler.downscaleFileToBase64(
                    file, mimeType.ifBlank { "image/jpeg" }
                )?.let { (mime, base64) ->
                    AgentImage(
                        mimeType = mime,
                        base64Data = base64,
                        path = containerPath
                    )
                }
            } catch (e: Exception) {
                null
            }
        } else null
    } else null

    return PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )
}

private fun PendingUploadAttachment.toAgentAttachment(): AgentAttachment =
    AgentAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        isImage = isImage
    )

internal fun List<PendingUploadAttachment>.toAgentAttachments(): List<AgentAttachment> =
    map { it.toAgentAttachment() }

private fun PendingUploadAttachment.toAgentImage(): AgentImage? = image

internal fun List<PendingUploadAttachment>.toAgentImages(): List<AgentImage> =
    mapNotNull { it.toAgentImage() }

internal fun maxAttachmentMessage(context: Context, max: Int): String =
    context.getString(R.string.chat_attachment_max, max)

internal fun uploadSuccessMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded, count)

internal fun partialUploadMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded_partial, count)

internal fun selectedAttachments(
    uris: List<Uri>,
    currentCount: Int
): List<Uri> = uris.take((MAX_PENDING_ATTACHMENTS - currentCount).coerceAtLeast(0))

internal fun hasAttachmentSlots(currentCount: Int): Boolean =
    currentCount < MAX_PENDING_ATTACHMENTS

private fun imageLimitError(context: Context, limit: Long): String =
    context.getString(R.string.chat_image_too_large, formatBytes(limit))

private fun pickedFileToastPath(context: Context, path: String): String =
    context.getString(R.string.chat_uploaded_to, path)

internal fun emptyWorkspaceMessage(context: Context): String =
    context.getString(R.string.chat_select_workspace_first)

internal fun unreadableFileMessage(context: Context): String =
    context.getString(R.string.chat_read_file_failed)

internal fun uploadFallbackError(context: Context): String =
    context.getString(R.string.chat_upload_failed)

private fun unsupportedImageTypeError(context: Context): String =
    context.getString(R.string.chat_unsupported_image_type)

private fun uploadFileName(context: Context, uri: Uri): String =
    context.displayName(uri).ifBlank { "upload" }

private fun safeUploadFileName(context: Context, uri: Uri): String =
    sanitizeUploadFileName(uploadFileName(context, uri))

private fun imageMimeType(context: Context, uri: Uri, fileName: String): String =
    resolveImageMimeType(context, uri, fileName)

private fun fileMimeType(context: Context, uri: Uri, fileName: String): String =
    context.contentResolver.getType(uri)?.lowercase()
        ?: when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

internal suspend fun copyUriToWorkspace(
    context: Context,
    uri: Uri,
    fileAccess: FileAccessProvider,
    includeImageData: Boolean = false
): UploadedWorkspaceFile = withContext(Dispatchers.IO) {
    val attachmentsDir = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/attachments"
    val fileName = uniqueUploadName(fileAccess, attachmentsDir, safeUploadFileName(context, uri))
    val containerPath = "$attachmentsDir/$fileName"

    val mimeType = if (includeImageData) {
        imageMimeType(context, uri, fileName)
    } else {
        fileMimeType(context, uri, fileName)
    }

    // 原图要整份读进内存再缩小，这里只拦会顶爆堆的超大文件；发给模型的副本在缩小后再按上限校验。
    if (includeImageData) {
        val declared = context.contentSize(uri)
        if (declared != null && declared > MAX_IMAGE_SOURCE_BYTES) error(imageLimitError(context, MAX_IMAGE_SOURCE_BYTES))
    }

    val sizeBytes: Long
    val image: AgentImage?
    if (includeImageData) {
        // 先流式落入 cache 临时文件再走 BitmapFactory.decodeFile，避免最高 40MB 原图整份读入 Java 堆。
        val tempFile = File.createTempFile("aicode-img-", ".upload", context.cacheDir)
        try {
            val totalWritten = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_SOURCE_BYTES) {
                            error(imageLimitError(context, MAX_IMAGE_SOURCE_BYTES))
                        }
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: error(unreadableFileMessage(context))
            val scaled = ImageDownscaler.downscaleFile(tempFile, mimeType, maxFallbackBytes = MAX_IMAGE_UPLOAD_BYTES)
                ?: error(imageLimitError(context, MAX_IMAGE_UPLOAD_BYTES))
            if (scaled.bytes.size > MAX_IMAGE_UPLOAD_BYTES) {
                error(imageLimitError(context, MAX_IMAGE_UPLOAD_BYTES))
            }
            tempFile.inputStream().use { input ->
                fileAccess.writeStream(containerPath, input, overwrite = true)
            }
            sizeBytes = totalWritten
            image = AgentImage(
                mimeType = scaled.mimeType,
                base64Data = Base64.getEncoder().encodeToString(scaled.bytes),
                path = containerPath
            )
        } finally {
            tempFile.delete()
        }
    } else {
        // 普通附件流式落盘：不再整份读进内存，于是文件多大都不会顶爆堆
        sizeBytes = context.contentResolver.openInputStream(uri)?.use { input ->
            fileAccess.writeStream(containerPath, input, overwrite = true)
        } ?: error(unreadableFileMessage(context))
        image = null
    }

    // 缩略图与图片数据需要本地文件：本地模式直接给宿主文件，远程模式下载到临时文件
    val localFile = fileAccess.copyToLocal(containerPath)

    UploadedWorkspaceFile(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localFile.absolutePath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )
}

private fun Context.displayName(uri: Uri): String {
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
    }.orEmpty()
}

private fun Context.contentSize(uri: Uri): Long? {
    return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
    }
}

private fun sanitizeUploadFileName(name: String): String {
    val cleaned = name
        .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
        .joinToString("")
        .trim()
        .trim('.')
    return cleaned.ifBlank { "upload" }.take(160)
}

private fun uniqueUploadName(fileAccess: FileAccessProvider, dir: String, fileName: String): String {
    if (!fileAccess.exists("$dir/$fileName")) return fileName

    val dotIndex = fileName.lastIndexOf('.')
    val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (fileAccess.exists("$dir/$stem-$index$extension")) {
        index += 1
    }
    return "$stem-$index$extension"
}

private fun resolveImageMimeType(context: Context, uri: Uri, fileName: String): String {
    val mime = context.contentResolver.getType(uri)?.lowercase()
    if (mime != null && mime in SUPPORTED_IMAGE_MIME_TYPES) return mime

    val byExtension = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
    if (byExtension != null) return byExtension
    error(unsupportedImageTypeError(context))
}

private val SUPPORTED_IMAGE_MIME_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp"
)

private const val MAX_IMAGE_UPLOAD_BYTES = 5L * 1024 * 1024
private const val MAX_IMAGE_SOURCE_BYTES = 40L * 1024 * 1024
internal const val MAX_PENDING_ATTACHMENTS = 8

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
