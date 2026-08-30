package com.aicode.feature.settings.domain.model

import androidx.annotation.StringRes
import com.aicode.R

/**
 * 常用 AI 提供商预设模板：在提供商列表页点「+」时弹出选择，选中后自动预填
 * 名称 / 类型 / Base URL，用户只需补 API Key 与模型。
 *
 * @param key 唯一标识（品牌 key，用于 logo 匹配）
 * @param nameRes 国际化名称资源
 * @param type 协议类型（第三方 OpenAI 兼容服务统一用 OPENAI）
 * @param baseUrl 官方 API 根地址（joinUrl 会容忍末尾版本段，如 /v1、/api/v3）
 * @param logoKey 品牌 logo key；无对应 drawable 资源时为 null（用通用图标）
 */
data class ProviderPreset(
    val key: String,
    @param:StringRes val nameRes: Int,
    val type: ProviderType,
    val baseUrl: String,
    val logoKey: String? = null
) {
    companion object {
        val OPENAI = ProviderPreset("openai", R.string.provider_preset_openai, ProviderType.OPENAI, "https://api.openai.com/", "openai")
        val ANTHROPIC = ProviderPreset("anthropic", R.string.provider_preset_anthropic, ProviderType.ANTHROPIC, "https://api.anthropic.com/", "anthropic")
        val GEMINI = ProviderPreset("gemini", R.string.provider_preset_gemini, ProviderType.GEMINI, "https://generativelanguage.googleapis.com/", "gemini")
        val DEEPSEEK = ProviderPreset("deepseek", R.string.provider_preset_deepseek, ProviderType.OPENAI, "https://api.deepseek.com/", "deepseek")
        val MOONSHOT = ProviderPreset("moonshot", R.string.provider_preset_moonshot, ProviderType.OPENAI, "https://api.moonshot.cn/v1", "moonshot")
        val QWEN = ProviderPreset("qwen", R.string.provider_preset_qwen, ProviderType.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen")
        val ZHIPU = ProviderPreset("zhipu", R.string.provider_preset_zhipu, ProviderType.OPENAI, "https://open.bigmodel.cn/api/paas/v4", "zhipu")
        val DOUBAO = ProviderPreset("doubao", R.string.provider_preset_doubao, ProviderType.OPENAI, "https://ark.cn-beijing.volces.com/api/v3", "doubao")
        val MINIMAX = ProviderPreset("minimax", R.string.provider_preset_minimax, ProviderType.OPENAI, "https://api.minimax.chat/v1", "minimax")
        val GROK = ProviderPreset("grok", R.string.provider_preset_grok, ProviderType.OPENAI, "https://api.x.ai/v1", "grok")
        val OPENROUTER = ProviderPreset("openrouter", R.string.provider_preset_openrouter, ProviderType.OPENAI, "https://openrouter.ai/api/v1", null)
        val GROQ = ProviderPreset("groq", R.string.provider_preset_groq, ProviderType.OPENAI, "https://api.groq.com/openai/v1", null)
        val MISTRAL = ProviderPreset("mistral", R.string.provider_preset_mistral, ProviderType.OPENAI, "https://api.mistral.ai/v1", null)

        val ALL_PRESETS = listOf(
            OPENAI,
            ANTHROPIC,
            GEMINI,
            DEEPSEEK,
            MOONSHOT,
            QWEN,
            ZHIPU,
            DOUBAO,
            MINIMAX,
            GROK,
            OPENROUTER,
            GROQ,
            MISTRAL
        )
    }
}
