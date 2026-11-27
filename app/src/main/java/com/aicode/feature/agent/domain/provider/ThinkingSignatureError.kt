package com.aicode.feature.agent.domain.provider

import retrofit2.HttpException
import java.io.IOException

/**
 * Anthropic 400：回传的历史 thinking 块签名校验失败。
 *
 * [fromMessageId] 是报错消息在会话历史中的 id。上游把索引打码（如 `***.***.content.0`）
 * 或换了文案时无法定位，此时为 null，调用方应清空全部历史的 thinking 快照。
 */
class ThinkingSignatureInvalidException(
    val fromMessageId: String?,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

private val THINKING_SIGNATURE_MARKERS = listOf(
    "Invalid `signature` in `thinking` block",
    "Invalid signature in thinking block"
)

private val MESSAGE_POSITION = Regex("""messages\.(\d+)\.content\.(\d+)""")

/**
 * 从 400 响应体解析要清理的起始消息 id；不是签名错误时返回 null。
 * 识别只看文案，位置解析失败不影响降级（fromMessageId 为 null 表示清理全部）。
 */
internal fun thinkingSignatureError(body: String, sourceIds: List<String>): ThinkingSignatureInvalidException? {
    if (THINKING_SIGNATURE_MARKERS.none { body.contains(it) }) return null
    val messageIndex = MESSAGE_POSITION.find(body)?.groupValues?.get(1)?.toIntOrNull()
    return ThinkingSignatureInvalidException(
        fromMessageId = messageIndex?.let { sourceIds.getOrNull(it) },
        message = "thinking 块签名校验失败（400）"
    )
}

/**
 * 判断一次 HTTP 失败是否为 thinking 签名错误。
 * 用 peekBody 读错误体，不消费原始 body，后续错误日志仍能取到内容。
 */
internal fun Throwable.asThinkingSignatureInvalid(sourceIds: List<String>): ThinkingSignatureInvalidException? {
    if (this !is HttpException || code() != 400) return null
    // extractRawErrorBody 会把原文缓存给后续 enrich/日志复用，这里消费 body 不影响诊断。
    val body = extractRawErrorBody() ?: return null
    return thinkingSignatureError(body, sourceIds)?.also { it.initCause(this) }
}