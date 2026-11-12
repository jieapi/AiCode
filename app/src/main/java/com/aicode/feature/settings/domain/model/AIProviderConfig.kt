package com.aicode.feature.settings.domain.model

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** 多 Key 模式开关：开启后从 [apiKeys] 轮换取用，关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 模式下的候选 Key（按列表顺序优先，各条自带调度权重）。 */
    val apiKeys: List<ProviderKey> = emptyList(),
    /** 多 Key 取用策略：顺序（失败才切）或轮询（新会话轮流起步）。 */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** 已废弃：早期「连续失败达阈值才切」的阈值。现在命中 [keySwitchStatusCodes] 即立即切换，保留仅为兼容旧数据库列。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却多少分钟后重新纳入候选；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    /** 命中即触发多 Key 自动切换的 HTTP 状态码；留空时回退 [DEFAULT_KEY_SWITCH_STATUS_CODES]。 */
    val keySwitchStatusCodes: List<Int> = emptyList(),
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
    /** 自定义面板脚本路径（位于 ~/.aicode/scripts/，或绝对路径/自定义命令）。 */
    val dashboardScriptPath: String = "",
    /** 自定义面板自动刷新间隔（分钟），0 表示仅进入时/手动刷新，支持 1, 3, 5, 10 等。默认 5 分钟。 */
    val dashboardRefreshInterval: Int = 5,
    /**
     * 自定义请求头（Header 名 -> 值），完全覆盖该提供商所有请求的同名默认头。
     * 值支持占位符 `{{SESSION_ID}}`（会话 id）与 `{{API_KEY}}`（本次实际取用的 Key），
     * 发送请求前由 provider 适配器替换后写出。
     */
    val customHeaders: Map<String, String> = emptyMap(),
    /** 提供商列表排序序号，越小越靠前；-1 表示未分配（保存时取 max+1 排到末尾）。 */
    val sortOrder: Int = -1,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /**
     * 自定义面板 (DIY) 脚本参数（Key-Value）。
     * 执行面板脚本时注入为额外环境变量 `AICODE_KEY_<KEY>`，
     * 值支持引用提供商配置占位符，如 `{{PROVIDER_API_KEY}}`、`{{BASE_URL}}`。
     */
    val scriptParams: Map<String, String> = emptyMap(),
    /** 非空表示该提供商由插件登记，值对应插件 ID；此类提供商只读、不写入用户配置。 */
    val pluginId: String? = null,
    /** 插件内的提供商局部 ID；与 [pluginId] 一起定位贡献。 */
    val pluginProviderId: String? = null
) {
    /** 实际生效的模型：优先 selectedModel，其次 defaultModel。 */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * 实际可用的 Key 列表。多 Key 模式开启且列表非空时用 [apiKeys]，否则退回单 [apiKey]，
     * 使「开关关闭」与「开着但没填」都能落到既有单 Key 行为上。
     */
    val effectiveApiKeys: List<ProviderKey>
        get() = if (multiKeyEnabled && apiKeys.any { it.value.isNotBlank() }) {
            apiKeys.filter { it.value.isNotBlank() }
        } else {
            listOf(ProviderKey(apiKey)).filter { it.value.isNotBlank() }
        }

    /** 是否已配置至少一个可用 Key；插件提供商由插件服务自行处理凭据，宿主不要求 Key。 */
    val hasUsableApiKey: Boolean get() = pluginId != null || effectiveApiKeys.isNotEmpty()

    /** 实际生效的切换状态码：留空回退默认集合。 */
    val effectiveKeySwitchStatusCodes: Set<Int>
        get() = keySwitchStatusCodes.filter { it in 100..599 }.toSet()
            .ifEmpty { DEFAULT_KEY_SWITCH_STATUS_CODES }

    /** 拉取模型列表 / 连通性测试等无会话上下文的请求用第一个可用 Key。 */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull()?.value ?: ""
}

/**
 * 多 Key 模式下的一条候选 Key：值 + 调度权重。
 *
 * [weight] 参与轮询调度：越大被分到的新会话越多；≤ 0 表示该 Key 暂不参与调度（保留但不选用）。
 */
data class ProviderKey(
    val value: String,
    val weight: Int = DEFAULT_KEY_WEIGHT
)

/** 调度权重默认值与上限。 */
const val DEFAULT_KEY_WEIGHT: Int = 1
const val MAX_KEY_WEIGHT: Int = 1_000_000

/** 绝不包含空白的字段（API Key / URL / 代理主机）：连中间空白一并去掉。 */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** 允许内部空格的字段（名称 / 模型名 / UA / 路径 / 代理账号）：去换行与制表符，再 trim 首尾。 */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * 清洗手填/粘贴的提供商配置。粘贴 API Key 常带入换行或首尾空格，直接拼进 Authorization
 * 头会被 OkHttp 拒绝（Unexpected char 0x0a in Authorization value）；baseUrl / 代理主机带空白则
 * 拼出非法 URL。写入与读取两侧各清洗一次，已存的脏数据不靠迁移也能恢复可用。
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys
        .map { it.copy(value = it.value.stripAllWhitespace(), weight = it.weight.coerceIn(0, MAX_KEY_WEIGHT)) }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.value },
    keySwitchStatusCodes = keySwitchStatusCodes.filter { it in 100..599 }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    dashboardScriptPath = dashboardScriptPath.stripLineBreaks(),
    customHeaders = customHeaders
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() },
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks(),
    scriptParams = scriptParams
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() }
)

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

/** 默认触发多 Key 自动切换的 HTTP 状态码：鉴权（401）、计费/额度（402）、权限（403）、限流（429）。 */
val DEFAULT_KEY_SWITCH_STATUS_CODES: Set<Int> = setOf(401, 402, 403, 429)

enum class KeyRotationStrategy {
    /** 顺序：始终用第一个未冷却的 Key，只有连续失败达阈值才前移。 */
    SEQUENTIAL,

    /** 轮询：新会话轮流分配起始 Key；同一会话内仍粘住同一个 Key 以保住服务端 prompt 缓存。 */
    ROUND_ROBIN
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
