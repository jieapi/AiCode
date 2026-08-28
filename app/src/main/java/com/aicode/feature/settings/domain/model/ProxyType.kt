package com.aicode.feature.settings.domain.model

/**
 * 代理协议类型：HTTP（CONNECT 隧道，可代理全部 http/https 流量）或 SOCKS5（TCP 级转发）。
 * 放在 domain 层：同时被全局代理（ProxySettingsRepository）与提供商级代理（AIProviderConfig）引用。
 */
enum class ProxyType {
    HTTP, SOCKS5
}
