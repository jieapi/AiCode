package com.aicode.feature.agent.domain.tool.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.util.UUID
import javax.inject.Inject

/**
 * 当前聊天模型支持视觉时直接附加图片；否则创建识图会话，返回分析文本与 vision_id。
 * 返回 vision_id 后可用 id + prompt 继续追问。
 * 会话请求体落盘在 ~/.aicode/vision-sessions/（见 [VisionSessionStore]）。
 * 识图模型不校验视觉能力，调用失败时把错误信息原样作为工具结果返回。
 */
class ViewImageTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val visionSessionStore: VisionSessionStore,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val modelMetadataService: ModelMetadataService,
    private val sessionUseCase: SessionUseCase,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    @param:ApplicationContext private val appContext: Context
) : AbstractContextualTool() {
    override val name = "viewImage"
    override val description = "查看当前执行环境中的图片。聊天模型支持视觉时直接附加图片；否则调用识图模型，返回分析内容与 vision_id。仅在收到 vision_id 后使用 id 继续追问。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "images" to ToolParameter(
            name = "images",
            type = ParameterType.ARRAY,
            description = "图片路径列表，1~5 张，与 id 二选一。支持工作区相对路径、~/workspace/... 或当前执行环境的绝对路径。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "识图会话 id，用于继续之前的识图会话追问；与 images 二选一。",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "提问或关注点。传 images 时可选，默认描述图片；传 id 追问时必填。",
            required = false
        ),
        "detail" to ToolParameter(
            name = "detail",
            type = ParameterType.STRING,
            description = "清晰度：high（默认）与 original 均优先保留不超过 4 MiB 的 JPEG/PNG/WebP/GIF，其他图片转为最长边不超过 1536 的 JPEG；low 转为最长边不超过 512 的 JPEG。",
            required = false,
            enum = listOf("low", "high", "original")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val images = parseImages(args)
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val detail = args["detail"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it in SUPPORTED_DETAILS } ?: "high"

        if (images.isNotEmpty() && id.isNotEmpty()) {
            return ToolResult.Error("images 与 id 只能二选一：传 images 开启新的识图会话，传 id 继续已有会话。", "INVALID_ARGS")
        }
        if (images.isEmpty() && id.isEmpty()) {
            return ToolResult.Error("缺少参数：开启识图会话需传 images（1~5 张图片路径），继续追问需传 id 与 prompt。", "MISSING_ARGS")
        }
        if (images.size > MAX_IMAGES) {
            return ToolResult.Error("一次最多传 $MAX_IMAGES 张图片，当前 ${images.size} 张。", "TOO_MANY_IMAGES")
        }
        val supportsVision = isCurrentChatModelSupportsVision(context.sessionId)
        return if (supportsVision && images.isNotEmpty()) {
            loadImagesDirectlyToContext(images, prompt, detail)
        } else if (images.isNotEmpty()) {
            createSession(images, prompt, detail, context.sessionId)
        } else {
            continueSession(id, prompt, context.sessionId)
        }
    }

    private fun loadImagesDirectlyToContext(
        images: List<String>,
        prompt: String,
        detail: String
    ): ToolResult {
        val encoded = mutableListOf<AgentImage>()
        for (path in images) {
            when (val r = encodeImage(path, detail)) {
                is EncodeOutcome.Ok -> encoded.add(r.image)
                is EncodeOutcome.Fail -> return ToolResult.Error(r.message, r.code)
            }
        }
        val content = buildString {
            append(appContext.getString(R.string.tool_vision_images_attached, encoded.size))
            if (prompt.isNotBlank()) {
                append('\n')
                append(appContext.getString(R.string.tool_vision_focus, prompt))
            }
        }
        return ToolResult.Success(
            data = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("loaded_to_context"),
                    "image_count" to JsonPrimitive(encoded.size),
                    "content" to JsonPrimitive(content),
                    "paths" to JsonArray(images.map { JsonPrimitive(it) })
                )
            ),
            images = encoded
        )
    }

    private suspend fun createSession(images: List<String>, prompt: String, detail: String, sessionId: String?): ToolResult {
        val encoded = mutableListOf<AgentImage>()
        for (path in images) {
            when (val r = encodeImage(path, detail)) {
                is EncodeOutcome.Ok -> encoded.add(r.image)
                is EncodeOutcome.Fail -> return ToolResult.Error(r.message, r.code)
            }
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val effectivePrompt = prompt.ifBlank { defaultPrompt(images.size) }
        val messages = listOf(
            AgentMessage.UserMessage(content = effectivePrompt, images = encoded)
        )
        return try {
            visionSessionStore.save(id, messages)
            val provider = resolveVisionProvider(sessionId)
            val response = callVisionProvider(provider, messages, sessionId)
            val content = response.content.ifBlank { appContext.getString(R.string.tool_vision_empty_response) }
            visionSessionStore.save(id, messages + AgentMessage.AssistantMessage(content = content))
            successResult(id, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            visionSessionStore.delete(id)
            FileLogger.e(TAG, "识图失败", e)
            ToolResult.Error(e.message ?: "识图调用失败", "VISION_CALL_FAILED")
        }
    }

    private suspend fun continueSession(id: String, prompt: String, sessionId: String?): ToolResult {
        if (prompt.isBlank()) {
            return ToolResult.Error("继续识图会话需提供 prompt。", "MISSING_PROMPT")
        }
        val history = visionSessionStore.load(id)
            ?: return ToolResult.Error("识图会话不存在或已过期：$id", "SESSION_NOT_FOUND")
        val messages = history + AgentMessage.UserMessage(content = prompt)
        return try {
            val provider = resolveVisionProvider(sessionId)
            val response = callVisionProvider(provider, messages, sessionId)
            val content = response.content.ifBlank { appContext.getString(R.string.tool_vision_empty_response) }
            visionSessionStore.save(id, messages + AgentMessage.AssistantMessage(content = content))
            successResult(id, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "识图失败", e)
            ToolResult.Error(e.message ?: "识图调用失败", "VISION_CALL_FAILED")
        }
    }

    private suspend fun callVisionProvider(
        provider: AIProvider,
        messages: List<AgentMessage>,
        sessionId: String?
    ): AIResponse {
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = android.os.SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        return try {
            val response = provider.complete("", messages, emptyList())
            usage = response
            callCompleted = true
            response
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (android.os.SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "vision",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
    }

    private fun successResult(id: String, content: String): ToolResult = ToolResult.Success(
        JsonObject(
            mapOf(
                "vision_id" to JsonPrimitive(id),
                "content" to JsonPrimitive(content)
            )
        )
    )

    private fun parseImages(args: Map<String, JsonElement>): List<String> {
        val array = args["images"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    }

    private fun defaultPrompt(count: Int): String = if (count == 1) {
        appContext.getString(R.string.tool_vision_default_single)
    } else {
        appContext.getString(R.string.tool_vision_default_multiple, count)
    }

    /**
     * 识图模型解析：配置了「识图模型」用专用模型，否则用当前聊天模型
     * （session 绑定 provider 优先，回退全局默认）。不校验模型的视觉能力。
     */
    private suspend fun isCurrentChatModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveCurrentChatConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    private suspend fun resolveVisionProvider(sessionId: String?): AIProvider {
        val visionProviderId = visionModelSettingsRepository.getVisionProviderId().trim()
        val visionModel = visionModelSettingsRepository.getVisionModel().trim()
        if (visionProviderId.isNotEmpty() && visionModel.isNotEmpty()) {
            val config = aiProviderRepository.getProviderById(visionProviderId)
            if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                return createStandaloneProvider(config.copy(selectedModel = visionModel), sessionId)
            }
        }
        val config = resolveCurrentChatConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank()) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    private suspend fun resolveCurrentChatConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                    return if (!boundModel.isNullOrBlank()) config.copy(selectedModel = boundModel) else config
                }
            }
        }
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        provider.apiKey = config.apiKey
        provider.baseUrl = config.effectiveBaseUrl(config.apiKey)
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    private fun encodeImage(path: String, detail: String): EncodeOutcome {
        return try {
            val file = fileAccess.copyToLocal(path)
            FileLogger.d(TAG, "viewImage path=$path -> ${file.absolutePath}, detail=$detail")
            if (!fileAccess.exists(path)) return EncodeOutcome.Fail("文件不存在：$path", "FILE_NOT_FOUND")
            if (!fileAccess.isFile(path)) return EncodeOutcome.Fail("路径不是文件：$path", "NOT_A_FILE")
            val fileSize = fileAccess.fileSize(path)
            if (fileSize <= 0L) return EncodeOutcome.Fail("图片文件为空：$path", "EMPTY_FILE")

            val bounds = decodeBounds(file)
                ?: return EncodeOutcome.Fail("无法识别图片格式：$path", "UNSUPPORTED_IMAGE")
            val sourceMime = guessMimeType(file)
            if (!sourceMime.startsWith("image/")) {
                return EncodeOutcome.Fail("不是支持的图片文件：$path", "UNSUPPORTED_IMAGE")
            }

            val originalOk = sourceMime in ORIGINAL_MIME_TYPES
            val encoded = when (detail) {
                "low" -> encodePreview(file, bounds, LOW_MAX_EDGE, LOW_TARGET_BYTES, detail)
                else -> if (originalOk && fileSize <= MAX_ORIGINAL_BYTES) {
                    originalImage(path, bounds, sourceMime, fileSize)
                } else {
                    encodePreview(file, bounds, HIGH_MAX_EDGE, HIGH_TARGET_BYTES, detail)
                }
            }
            EncodeOutcome.Ok(
                AgentImage(
                    mimeType = encoded.mimeType,
                    base64Data = encoded.base64Data,
                    path = fileAccess.toDisplayPath(path)
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "viewImage 编码异常: $path", e)
            EncodeOutcome.Fail(e.message ?: "读取图片失败", "READ_IMAGE_ERROR")
        }
    }

    private fun originalImage(path: String, bounds: ImageBounds, mime: String, size: Long): EncodedImage =
        EncodedImage(
            mimeType = mime,
            base64Data = Base64.encodeToString(fileAccess.readBytes(path), Base64.NO_WRAP),
            width = bounds.width,
            height = bounds.height,
            detail = "original",
            encodedBytes = size
        )

    private fun decodeBounds(file: File): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) ImageBounds(width, height) else null
    }

    private fun encodePreview(file: File, bounds: ImageBounds, maxEdge: Int, targetBytes: Int, detail: String): EncodedImage {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.width, bounds.height, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("无法解码图片：${file.name}")

        try {
            val scaled = scaleToMaxEdge(decoded, maxEdge)
            try {
                val encoded = compressJpeg(scaled, targetBytes)
                return EncodedImage(
                    mimeType = "image/jpeg",
                    base64Data = Base64.encodeToString(encoded, Base64.NO_WRAP),
                    width = scaled.width,
                    height = scaled.height,
                    detail = detail,
                    encodedBytes = encoded.size.toLong()
                )
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, targetBytes: Int): ByteArray {
        var best = ByteArray(0)
        for (quality in JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            best = bytes
            if (bytes.size <= targetBytes) break
        }
        return best
    }

    private fun guessMimeType(file: File): String {
        val extMime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }

    private sealed interface EncodeOutcome {
        data class Ok(val image: AgentImage) : EncodeOutcome
        data class Fail(val message: String, val code: String) : EncodeOutcome
    }

    private data class ImageBounds(val width: Int, val height: Int)

    private data class EncodedImage(
        val mimeType: String,
        val base64Data: String,
        val width: Int,
        val height: Int,
        val detail: String,
        val encodedBytes: Long
    )

    private companion object {
        const val TAG = "ImageTools"
        const val MAX_IMAGES = 5
        const val LOW_MAX_EDGE = 512
        const val HIGH_MAX_EDGE = 1536
        const val LOW_TARGET_BYTES = 96 * 1024
        const val HIGH_TARGET_BYTES = 512 * 1024
        const val MAX_ORIGINAL_BYTES = 4 * 1024 * 1024
        val JPEG_QUALITIES = listOf(90, 86, 78, 70, 62)
        val SUPPORTED_DETAILS = setOf("low", "high", "original")
        val ORIGINAL_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
