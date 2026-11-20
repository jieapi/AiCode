package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按提供商配置装配一个新的 [AIProvider] 实例。
 *
 * 调用方传入 [scopeId] 作为 Key 粘性与 AI 日志的上下文：主会话用会话 id，
 * 插件模型句柄用插件/句柄作用域。每次调用都创建独立实例，不复用主对话的 Provider。
 */
@Singleton
class ProviderInstanceFactory @Inject constructor(
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val keyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository
) {

    suspend fun create(config: AIProviderConfig, scopeId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（同一作用域内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        val activeKey = keyRotator.activeKey(config, scopeId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        provider.apiKey = activeKey
        // Key 条目可选带自己的 Base URL（不同 Key 走不同中转），没填就回落到提供商主地址。
        provider.baseUrl = config.effectiveBaseUrl(activeKey)
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, scopeId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(
                            newKey = switched.newKey,
                            newIndex = switched.newIndex,
                            total = switched.total,
                            newBaseUrl = config.effectiveBaseUrl(switched.newKey)
                        )
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = scopeId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }
}
