package com.aicode.feature.agent.data.remote.openai

data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    /** null 时 Gson 跳过该字段：服务端固定温度的模型（kimi-k3、gpt-5 系等）带上任何值都会 400。 */
    val temperature: Float? = null,
    val reasoning_effort: String? = null,
    /**
     * 厂商私有思考开关：`{"type": "disabled"}`。
     * DeepSeek / Kimi / GLM / MiniMax 等只在 OpenAI 兼容层认这个字段，对它们发 `reasoning_effort="none"` 不生效。
     */
    val thinking: Map<String, String>? = null,
    /** Qwen / DashScope 的思考开关（与 [thinking] 互不通用）。 */
    val enable_thinking: Boolean? = null,
    val tools: List<OpenAIToolDefinition>? = null,
    val tool_choice: String? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null,
    val service_tier: String? = null,
    /** 缓存 shard 路由键（同会话请求路由到同一缓存分片）。仅第三方兼容服务支持，OpenAI 官方不接受该字段。 */
    val prompt_cache_key: String? = null
)

data class OpenAIChatMessage(
    val role: String,
    val content: Any?,
    val name: String? = null,
    val tool_calls: List<OpenAIToolCall>? = null,
    val tool_call_id: String? = null,
    /** DeepSeek 思考模式要求将上轮 assistant 消息的 reasoning_content 原样回传，否则 400。 */
    val reasoning_content: String? = null,
    /** 部分第三方兼容服务（如 mimo）用顶层 reasoning 而非 reasoning_content 传思考内容。 */
    val reasoning: String? = null,
    /** 生图模型的响应扩展：部分兼容服务在 assistant message 里返回 `images` 数组（本项目用户中转站即此格式）。 */
    val images: List<OpenAIImagePart>? = null
)

/** Chat Completions 生图扩展里的单个图片项（流式 `delta.images` 与非流式 `message.images` 同形）。 */
data class OpenAIImagePart(
    val type: String? = null,
    val image_url: OpenAIImageUrl? = null,
    val index: Int? = null
)

data class OpenAIImageUrl(val url: String? = null)

data class OpenAIToolDefinition(
    val type: String = "function",
    val function: OpenAIFunctionDefinition
)

data class OpenAIFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIFunctionCall
)

data class OpenAIFunctionCall(
    val name: String,
    val arguments: String
)

data class StreamOptions(
    val include_usage: Boolean = true
)

data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: OpenAIChatMessage?,
    val delta: OpenAIChatMessage?, // Used for streaming
    val finish_reason: String?
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
    val prompt_tokens_details: PromptTokensDetails? = null
)

/**
 * Chat Completions 的输入 token 明细：cached_tokens 为命中缓存的部分，
 * cache_write_tokens 为写入缓存的部分（GPT-5.6 起才有、按高于输入价计费）。
 */
data class PromptTokensDetails(
    val cached_tokens: Int? = null,
    val cache_write_tokens: Int? = null
)

/**
 * Images API（POST /images/generations）请求体。Gson 按字段名直序列化，
 * 字段名必须保持 snake_case 与 OpenAI 接口一致。
 *
 * 各参数按模型兼容性由调用方决定是否携带（GPT image 系不支持 [responseFormat]
 * 且总是返回 b64_json；[background]/[moderation]/[outputFormat] 仅 GPT image 系有效；
 * [style] 仅 dall-e-3 有效）；null 时 Gson 跳过该字段。
 */
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int? = null,
    val size: String? = null,
    val quality: String? = null,
    val response_format: String? = null,
    val output_format: String? = null,
    val background: String? = null,
    val moderation: String? = null,
    val style: String? = null
)

/** Images API 单张生成结果。 */
data class ImageGenerationResult(
    val b64_json: String? = null,
    val url: String? = null,
    val revised_prompt: String? = null
)

/** Images API token 用量（gpt-image 系返回，dall-e 系无此字段）。 */
data class ImageGenerationUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val total_tokens: Int = 0,
    val input_tokens_details: ImageTokenDetails? = null,
    val output_tokens_details: ImageTokenDetails? = null
)

/** Images API token 明细：图片 token 与文本 token 拆分。 */
data class ImageTokenDetails(
    val image_tokens: Int = 0,
    val text_tokens: Int = 0
)

/** Images API 响应体（Gson 反序列化，仅取用所需字段）。 */
data class ImageGenerationResponse(
    val created: Long = 0,
    val data: List<ImageGenerationResult> = emptyList(),
    val usage: ImageGenerationUsage? = null
)
