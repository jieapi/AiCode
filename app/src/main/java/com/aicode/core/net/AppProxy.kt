package com.aicode.core.net

import android.content.Context
import com.aicode.feature.settings.data.repository.DEFAULT_NO_PROXY
import com.aicode.feature.settings.data.repository.ProviderProxyEntry
import com.aicode.feature.settings.data.repository.ProviderProxyRegistry
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.domain.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Route
import java.io.IOException
import java.net.Authenticator.RequestorType
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder

/**
 * 全局 HTTP 代理接入点（无状态单例，每次读取 SharedPreferences 最新值，改设置即对新连接生效）。
 *
 * 接入方式：
 * - [applyGlobal] 在 Application.attachBaseContext（任何 Hilt 注入之前）调用：把动态
 *   [selector] 设为 JVM 全局默认。OkHttpClient 构建时捕获该引用、HttpURLConnection 每次
 *   打开连接都查全局 selector，二者都实时调用 select() 读最新配置，无需重建客户端。
 * - OkHttp 的代理认证走 [okHttpAuthenticator]（须在构建 OkHttpClient 时显式挂载，OkHttp
 *   默认不响应 407 质询）；HttpURLConnection 的认证走全局 java.net.Authenticator
 *   （[applyGlobal] 一并设置）。
 * - 容器内命令（curl/git/npm/pip 等）经 [proxyEnv] 注入 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY
 *   环境变量，由 LinuxContainerEngine 叠加进容器进程环境。
 */
object AppProxy {

    @Volatile
    private var appContext: Context? = null

    /** 提供商级代理注册表（DI 注入后注册）：按目标 host 分派 provider 专属代理。 */
    @Volatile
    private var providerRegistry: ProviderProxyRegistry? = null

    /** 在 Application.attachBaseContext 中调用（早于 OkHttpClient 首次构建）。 */
    fun applyGlobal(context: Context) {
        // attachBaseContext 阶段 getApplicationContext() 可能为 null（Application 尚未完全
        // 绑定），此时直接用传入的 base（Application 本身就是合法 Context）——否则后续
        // config() 读不到代理设置，所有请求静默直连（表现为容器内代理生效而 App 侧不生效）。
        appContext = context.applicationContext ?: context
        ProxySelector.setDefault(selector)
        java.net.Authenticator.setDefault(httpUrlConnectionAuthenticator)
    }

    /** 由 DI 层在启动装配后注册（AIEditorApp.onCreate），此后 selector 按 host 分派 provider 代理。 */
    fun registerProviderProxyRegistry(registry: ProviderProxyRegistry) {
        providerRegistry = registry
    }

    private fun config(): ProxyConfig {
        val ctx = appContext ?: return ProxyConfig()
        return ProxySettingsRepository.readConfig(ctx)
    }

    /** 某次 select 选中的代理（provider 级优先于全局）与对应凭据。 */
    private data class SelectedProxy(
        val type: ProxyType, val host: String, val port: Int,
        val username: String, val password: String
    )

    /** 按目标 host+路径分派：provider 级代理优先（路径前缀最具体者胜），否则全局代理；不可用返回 null。 */
    private fun selectProxy(host: String, path: String?): SelectedProxy? {
        providerRegistry?.findByUrl(host, path)?.takeIf { it.valid }?.let { e ->
            return SelectedProxy(e.type, e.host, e.port, e.username, e.password)
        }
        val cfg = config()
        if (!cfg.enabled || !cfg.valid) return null
        return SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password)
    }

    private fun proxyOf(sel: SelectedProxy): Proxy {
        val type = when (sel.type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress(sel.host, sel.port))
    }

    /**
     * 动态 ProxySelector：每次 select() 读最新配置。
     * 按目标 host 分派：命中 provider 级代理则用 provider 配置，否则用全局配置；
     * 未启用 / 配置不完整 / 命中 NO_PROXY 时返回直连。
     */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val cfg = config()
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (isNoProxy(host, uri.port, cfg.noProxy)) return listOf(Proxy.NO_PROXY)
            val sel = selectProxy(host, uri.path) ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyOf(sel))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    /** 供 OkHttpClient 构建时挂载：代理返回 407 时应答 Basic 认证，按目标 host 分派凭据（provider 优先）。 */
    val okHttpAuthenticator: Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
            if (response.code != 407) return null
            val url = response.request.url
            providerRegistry?.findByUrl(url.host, url.encodedPath)?.takeIf { it.username.isNotBlank() }?.let { e ->
                return response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(e.username, e.password))
                    .build()
            }
            val cfg = config()
            if (!cfg.enabled || cfg.username.isBlank()) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(cfg.username, cfg.password))
                .build()
        }
    }

    /** 全局 java.net.Authenticator：java.net 层的代理认证都走这里（HttpURLConnection 的 407、
     *  以及所有经 `Socket(proxy)` 的 SOCKS5 握手——包括 OkHttp 的 SOCKS 连接）。
     *
     *  Android 的 SocksSocketImpl（与 OpenJDK 不同）不读 java.net.socks.username/password
     *  系统属性，SOCKS5 用户名/密码认证只走本回调，且调用的是不带 RequestorType 的
     *  六参 requestPasswordAuthentication 重载（getRequestorType() 为 null），因此
     *  SOCKS5 分支不能检查 requestorType，也不能依赖全局开关（testProxy 开关关着也要能测）。
     *  此刻可从 [getRequestingHost] 拿到“当前连接的代理服务器地址”，据此按 host 匹配
     *  provider/全局配置返回对应凭据；返回 null 时 Android 兑底用 user.name+空密码，
     *  对强制认证的代理必然失败。
     *  HTTP：仅应答全局配置（项目内 HttpURLConnection 已无调用者，保留防御）。
     */
    private val httpUrlConnectionAuthenticator: java.net.Authenticator =
        object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val protocol = getRequestingProtocol()?.lowercase()
                if (protocol == "socks5") {
                    val proxyHost = getRequestingHost()
                        ?: getRequestingSite()?.hostAddress
                        ?: return null
                    providerRegistry?.findByProxyHost(proxyHost)?.let { e ->
                        if (e.username.isNotBlank()) {
                            return PasswordAuthentication(e.username, e.password.toCharArray())
                        }
                    }
                    val cfg = config()
                    if (cfg.type == ProxyType.SOCKS5 && cfg.username.isNotBlank() &&
                        cfg.host.equals(proxyHost, ignoreCase = true)
                    ) {
                        return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
                    }
                    return null
                }
                if (requestorType != RequestorType.PROXY) return null
                if (protocol != "http" && protocol != "https") return null
                val cfg = config()
                if (!cfg.enabled || cfg.username.isBlank()) return null
                return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
            }
        }

    /** 容器内进程的代理环境变量；未启用或配置不完整时返回空 map（直连）。 */
    fun proxyEnv(context: Context): Map<String, String> {
        val cfg = ProxySettingsRepository.readConfig(context)
        if (!cfg.enabled || !cfg.valid) return emptyMap()
        val noProxy = cfg.noProxy.ifBlank { DEFAULT_NO_PROXY }
        return when (cfg.type) {
            ProxyType.HTTP -> {
                val proxyUrl = "http://${authority(cfg)}"
                mapOf(
                    "HTTP_PROXY" to proxyUrl,
                    "HTTPS_PROXY" to proxyUrl,
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
            ProxyType.SOCKS5 -> {
                // socks5h：域名解析也走代理（防 DNS 泄漏）。只设 ALL_PROXY 而不设
                // HTTP_PROXY/HTTPS_PROXY——否则 curl 对 http/https 目标会优先用
                // http_proxy 直连式代理协议连到 SOCKS 端口而失败。
                val proxyUrl = "socks5h://${authority(cfg)}"
                mapOf(
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
        }
    }

    /** 代理 URL 的 host:port 部分；有用户名时拼 userinfo（百分号编码）。 */
    private fun authority(cfg: ProxyConfig): String =
        if (cfg.username.isBlank()) {
            "${cfg.host}:${cfg.port}"
        } else {
            "${encodeUserInfo(cfg.username)}:${encodeUserInfo(cfg.password)}@${cfg.host}:${cfg.port}"
        }

    /** NO_PROXY 匹配：逗号分隔，语义与 curl（容器侧）一致——`*` 全部直连；
     *  `example.com` / `.example.com` 匹配自身及子域；`*.example.com` 仅子域；
     *  `host:port` 需端口一致；IPv4/IPv6 CIDR 网段（如 10.0.0.0/8、fd00::/8）按前缀匹配；
     *  大小写不敏感；配置为空时回退默认值（与容器注入一致）。 */
    private fun isNoProxy(host: String, port: Int, noProxy: String): Boolean {
        val list = noProxy.ifBlank { DEFAULT_NO_PROXY }
        val h = host.lowercase()
        return list.split(",").any { e ->
            val entry = e.trim().lowercase()
            when {
                entry.isEmpty() -> false
                entry == "*" -> true
                else -> {
                    val (hostPart, entryPort) = splitEntryHostPort(entry)
                    (entryPort == null || port == entryPort) && when {
                        hostPart.contains('/') -> cidrMatch(h, hostPart)
                        hostPart.startsWith("*.") -> h.endsWith("." + hostPart.removePrefix("*."))
                        else -> {
                            val hp = hostPart.removePrefix(".")
                            h == hp || h.endsWith(".$hp")
                        }
                    }
                }
            }
        }
    }

    /** 拆分 entry 的 host 与可选端口：`[::1]:1080`、`example.com:8080` 拆端口；
     *  纯 IPv6 字面量（两个以上冒号）视为无端口，避免把 `::1` 误当 host+port。 */
    private fun splitEntryHostPort(entry: String): Pair<String, Int?> {
        if (entry.startsWith("[")) {
            val close = entry.indexOf(']')
            if (close > 0) {
                val port = entry.substring(close + 1).removePrefix(":").toIntOrNull()
                return entry.substring(1, close) to port
            }
        }
        if (entry.count { it == ':' } > 1) return entry to null
        val colon = entry.indexOf(':')
        if (colon >= 0) {
            val p = entry.substring(colon + 1).toIntOrNull()
            if (p != null) return entry.substring(0, colon) to p
        }
        return entry to null
    }

    /** CIDR 前缀匹配。仅接受 IP 字面量（不做 DNS 解析——select() 在网络路径上），
     *  任意一侧非法或 v4/v6 混配返回 false。 */
    private fun cidrMatch(host: String, cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        val hostBytes = parseLiteralIp(host) ?: return false
        val netBytes = parseLiteralIp(cidr.substring(0, slash)) ?: return false
        if (hostBytes.size != netBytes.size || prefix !in 0..hostBytes.size * 8) return false
        var i = prefix / 8
        while (i > 0) {
            i--
            if (hostBytes[i] != netBytes[i]) return false
        }
        val rem = prefix % 8
        if (rem == 0) return true
        val mask = (0xFF shl (8 - rem)) and 0xFF
        return (hostBytes[prefix / 8].toInt() and mask) == (netBytes[prefix / 8].toInt() and mask)
    }

    private fun parseLiteralIp(s: String): ByteArray? = try {
        val isLiteral = if (s.contains(':')) {
            s.none { !it.isDigit() && it !in "abcdefABCDEF:." }
        } else {
            val parts = s.split(".")
            parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
        }
        if (isLiteral) java.net.InetAddress.getByName(s)?.address else null
    } catch (e: Exception) {
        null
    }

    /** userinfo 百分号编码（RFC 3986 userinfo 允许子集），避免密码含 @ : 等字符破坏 URL 解析。 */
    private fun encodeUserInfo(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch in "-._~!$&'()*+,;=") {
                sb.append(ch)
            } else {
                sb.append(URLEncoder.encode(ch.toString(), "UTF-8"))
            }
        }
        return sb.toString()
    }

    // ── 代理连通性测试 ───────────────────────────────────────────

    /** 代理测试结果。 */
    data class ProxyTestResult(val ok: Boolean, val message: String)

    /** 探针 URL：固定使用谷歌搜索引擎域名的连通性端点（返回 204，国内可直连），
     *  测试代理是否真正转发流量。不用第三方 IP 查询服务：避免隐私外发（出口 IP
     *  不出本机），且 204 响应不依赖探测服务的文本格式。 */
    private const val PROBE_URL = "https://www.google.com/generate_204"
    private const val TEST_TIMEOUT_SECONDS = 8L

    /**
     * 用给定配置发起一次探针请求（不受全局开关影响，可直接测当前表单值）。
     * [probeUrl] 为测试目标 URL，留空回退默认值；
     * HTTP 代理认证走 407 Basic；SOCKS5 认证经全局 Authenticator 按 host 分派（见上）。
     */
    suspend fun testProxy(context: Context, cfg: ProxyConfig, probeUrl: String = PROBE_URL): ProxyTestResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (cfg.host.isBlank() || cfg.port !in 1..65535) {
                return@withContext ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_invalid_config))
            }
            probe(context, SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password), probeUrl)
        }

    private suspend fun probe(context: Context, sel: SelectedProxy, probeUrl: String): ProxyTestResult {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .proxy(proxyOf(sel))
            .proxyAuthenticator(okHttpAuthenticator)
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url(probeUrl.ifBlank { PROBE_URL }).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty().trim()
                if (resp.isSuccessful) {
                    ProxyTestResult(true, context.getString(com.aicode.R.string.proxy_test_ok, resp.code))
                } else {
                    ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_http_error, resp.code, body.take(120)))
                }
            }
        } catch (e: Exception) {
            ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_connect_failed, e.message ?: ""))
        }
    }
}
