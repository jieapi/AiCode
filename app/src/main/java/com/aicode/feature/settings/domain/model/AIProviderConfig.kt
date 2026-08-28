package com.aicode.feature.settings.domain.model

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    val baseUrl: String,
    val defaultModel: String,
    /** 该提供商已添加的可用模型列表（拉取或手动添加）。 */
    val models: List<String> = emptyList(),
    /** 当前选中使用的模型；为空时回退到 defaultModel。 */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic 显式缓存断点（cache_control）。仅 ANTHROPIC 类型生效，默认开启。 */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型生效，默认关闭。 */
    val openaiChatCacheKey: Boolean = false,
    /** 套餐余量查询脚本路径（位于 ~/.aicode/scripts/，或绝对路径/自定义命令）。 */
    val balanceScriptPath: String = "",
    /** 套餐余量自动刷新间隔（分钟），0 表示仅进入时/手动刷新，支持 1, 3, 5, 10 等。默认 5 分钟。 */
    val balanceRefreshInterval: Int = 5,
    /** 自定义请求头 User-Agent；留空使用默认。 */
    val userAgent: String = "",
    /** 提供商列表排序序号，越小越靠前；-1 表示未分配（保存时取 max+1 排到末尾）。 */
    val sortOrder: Int = -1,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = ""
) {
    /** 实际生效的模型：优先 selectedModel，其次 defaultModel。 */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }
}

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
