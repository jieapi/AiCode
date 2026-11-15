package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 最终结果里回传的图片与是否发生丢弃。 */
class PluginModelEncodedImages(val array: JsonArray, val truncated: Boolean)

/**
 * 模型句柄的图片编解码。
 *
 * 输入侧把 `{mimeType, path?, base64?}` 解析成 [AgentImage]：path 优先，按宿主文件规则读取；
 * base64 内嵌按解码后大小校验。单条消息与单次请求的张数/总字节数都在这里累计，
 * 保证插件无法用图片把请求体撑爆。输出侧按单图与总量上限裁剪模型直出的图片。
 */
class PluginModelImageCodec(private val hostPaths: PluginHostPaths) {

    private var requestImages = 0
    private var requestBytes = 0L
    private var inlineBytes = 0L

    /** 解析一条消息的 `images` 字段；不是数组时返回空列表。 */
    fun parse(raw: JsonElement?, messageIndex: Int): List<AgentImage> {
        if (raw == null || raw is JsonNull) return emptyList()
        val array = raw as? JsonArray
            ?: throw paramInvalid("messages[$messageIndex].images must be an array")
        if (array.isEmpty()) return emptyList()
        if (array.size > MAX_IMAGES_PER_MESSAGE) {
            throw imageTooLarge("too many images in messages[$messageIndex]")
        }
        return array.mapIndexed { imageIndex, element -> parseOne(element, messageIndex, imageIndex) }
    }

    private fun parseOne(element: JsonElement, messageIndex: Int, imageIndex: Int): AgentImage {
        val label = "messages[$messageIndex].images[$imageIndex]"
        val item = element as? JsonObject ?: throw paramInvalid("$label must be an object")
        val mimeType = (item["mimeType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.lowercase(Locale.ROOT)
            ?: throw paramInvalid("$label.mimeType is required")
        if (mimeType !in SUPPORTED_MIME_TYPES) {
            throw PluginHostException("IMAGE_TYPE_UNSUPPORTED", "unsupported image mimeType: $mimeType")
        }
        val filePath = optionalString(item, "path")?.takeIf { it.isNotBlank() }
        val inline = optionalString(item, "base64")?.takeIf { it.isNotEmpty() }
        if (requestImages >= MAX_IMAGES_PER_REQUEST) {
            throw imageTooLarge("too many images in one request")
        }
        val image = when {
            filePath != null -> readFromPath(filePath, mimeType, label)
            inline != null -> fromBase64(inline, mimeType, label)
            else -> throw paramInvalid("$label requires path or base64")
        }
        if (filePath == null) {
            inlineBytes += image.size
            if (inlineBytes > MAX_INLINE_TOTAL_BYTES) {
                throw imageTooLarge("inline images in one request are too large")
            }
        }
        requestImages += 1
        requestBytes += image.size
        if (requestBytes > MAX_IMAGES_TOTAL_BYTES) {
            throw imageTooLarge("images in one request are too large")
        }
        return image.image
    }

    private class LoadedImage(val image: AgentImage, val size: Long)

    private fun readFromPath(path: String, mimeType: String, label: String): LoadedImage {
        val read = try {
            hostPaths.readBinary(path, MAX_PATH_IMAGE_BYTES)
        } catch (e: PluginHostException) {
            if (e.code == "TOO_LARGE") throw imageTooLarge("$label is too large") else throw e
        }
        val result = read as? JsonObject
            ?: throw PluginHostException("IO_ERROR", "cannot read image: $path")
        val base64 = (result["base64"] as? JsonPrimitive)?.content
            ?: throw PluginHostException("IO_ERROR", "cannot read image: $path")
        val size = (result["size"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw PluginHostException("IO_ERROR", "cannot read image: $path")
        if (size > MAX_PATH_IMAGE_BYTES) throw imageTooLarge("$label is too large")
        return LoadedImage(AgentImage(mimeType = mimeType, base64Data = base64, path = path), size)
    }

    private fun fromBase64(base64: String, mimeType: String, label: String): LoadedImage {
        val size = try {
            Base64.getDecoder().decode(base64).size.toLong()
        } catch (e: IllegalArgumentException) {
            throw paramInvalid("$label.base64 is invalid")
        }
        if (size > MAX_INLINE_IMAGE_BYTES) throw imageTooLarge("$label is too large")
        return LoadedImage(AgentImage(mimeType = mimeType, base64Data = base64), size)
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw paramInvalid("$key must be a string")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private fun imageTooLarge(message: String): PluginHostException =
        PluginHostException("IMAGE_TOO_LARGE", message)

    companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        const val MAX_PATH_IMAGE_BYTES = 8L * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 1L * 1024 * 1024
        const val MAX_IMAGES_PER_MESSAGE = 4
        const val MAX_IMAGES_PER_REQUEST = 8
        const val MAX_IMAGES_TOTAL_BYTES = 16L * 1024 * 1024
        const val MAX_INLINE_TOTAL_BYTES = 2L * 1024 * 1024

        const val MAX_OUTPUT_IMAGE_CHARS = 1536 * 1024
        const val MAX_OUTPUT_IMAGES_CHARS = 2 * 1024 * 1024

        /** 模型直出的图片：跳过空数据，超过单图/总量上限的丢弃并标记截断。 */
        fun encodeOutput(images: List<AgentImage>): PluginModelEncodedImages {
            val result = mutableListOf<JsonObject>()
            var totalChars = 0
            var truncated = false
            for (image in images) {
                val base64 = image.base64Data
                if (base64.isEmpty()) continue
                if (base64.length > MAX_OUTPUT_IMAGE_CHARS || totalChars + base64.length > MAX_OUTPUT_IMAGES_CHARS) {
                    truncated = true
                    continue
                }
                totalChars += base64.length
                result.add(buildJsonObject {
                    put("mimeType", image.mimeType)
                    put("base64", base64)
                })
            }
            return PluginModelEncodedImages(JsonArray(result), truncated)
        }
    }
}
