package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 提供商级代理条目：由 [ProviderProxyRegistry] 按目标 URL 查表得到。 */
data class ProviderProxyEntry(
    val providerId: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** baseUrl 的路径前缀（已归一化：去尾斜杠；根路径为空串，匹配该主机全部请求）。 */
    val basePath: String = ""
) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * 提供商级代理注册表：把「配了独立代理的 provider」按 baseUrl 的 host + 路径前缀建索引。
 *
 * AppProxy 的全局动态 ProxySelector 每次 select(uri) 时先按目标 host+path 查本表——
 * 命中则用该 provider 的代理，否则回退全局代理。同一主机上不同路径（不同 URL）的
 * 提供商可各自精准分派；仅 host+路径完全相同的条目无法区分（保留先配置者，记警告日志）。
 */
@Singleton
class ProviderProxyRegistry @Inject constructor(
    aiProviderRepository: AIProviderRepository
) {
    // 应用级作用域：单例生命周期与 App 进程一致（随进程退出而消亡），无需外部取消。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** host(lowercase) → 该主机下的代理条目列表；无配置时为空 map。 */
    @Volatile
    private var byHost: Map<String, List<ProviderProxyEntry>> = emptyMap()

    init {
        scope.launch {
            aiProviderRepository.getAllProviders().collectLatest { providers ->
                val entries = providers
                    .filter { it.proxyEnabled && it.proxyHost.isNotBlank() && it.proxyPort in 1..65535 }
                    .mapNotNull { entry ->
                        providerHostAndPath(entry.baseUrl)?.let { (host, path) -> host to entry.toEntry(path) }
                    }
                // host+路径完全相同的多个提供商在代理层无法区分：保留最先配置者并记日志。
                entries.groupBy { it.first }.filter { it.value.size > 1 }.forEach { (key, pairs) ->
                    FileLogger.w(
                        TAG,
                        "多个提供商共用 $key，代理配置冲突，仅最先配置生效: ${pairs.joinToString { p -> p.second.providerId }}"
                    )
                }
                byHost = entries.groupBy({ it.first }, { it.second })
                FileLogger.d(TAG, "提供商代理表已刷新: ${byHost.size} 个主机 ${entries.size} 项")
            }
        }
    }

    /**
     * 按目标 URL 的 host + 路径前缀查找。路径匹配规则：条目 basePath 为空（根路径，
     * 匹配该主机全部请求）、请求路径与 basePath 相等、或请求路径以 basePath + "/" 开头。
     * 多条命中取 basePath 最长者（最具体优先）；同长重复条目取最先配置者。
     */
    fun findByUrl(host: String, path: String?): ProviderProxyEntry? {
        val candidates = byHost[host.lowercase()] ?: return null
        val reqPath = path?.takeIf { it.isNotBlank() } ?: "/"
        val matched = candidates.filter { e ->
            val base = e.basePath
            base.isEmpty() || reqPath == base || reqPath.startsWith("$base/")
        }
        if (matched.size <= 1) return matched.firstOrNull()
        return matched.maxByOrNull { it.basePath.length }
    }

    /** 按代理服务器地址（而非目标 host）查找：SOCKS5 握手认证时按连接所用的代理匹配凭据。 */
    fun findByProxyHost(proxyHost: String): ProviderProxyEntry? =
        byHost.values.flatten().firstOrNull { it.host.equals(proxyHost, ignoreCase = true) && it.username.isNotBlank() }

    private fun AIProviderConfig.toEntry(basePath: String) = ProviderProxyEntry(
        providerId = id,
        type = proxyType,
        host = proxyHost,
        port = proxyPort,
        username = proxyUsername,
        password = proxyPassword,
        basePath = basePath
    )

    private fun providerHostAndPath(baseUrl: String): Pair<String, String>? =
        runCatching {
            val uri = java.net.URI(baseUrl.trim())
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val path = uri.path?.trimEnd('/') ?: ""
            host to path
        }.getOrNull()

    private companion object {
        const val TAG = "ProviderProxyRegistry"
    }
}
