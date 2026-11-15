package com.aicode.feature.plugin.presentation

/**
 * 槽位入口的调用上下文；不同槽位按需填字段。
 *
 * 页面通过 `aicode.context()` 读取；动作作为 handler 参数。路径统一使用当前执行环境的真实路径：
 * 本地模式为容器内路径，SSH 模式为远端路径。
 */
data class PluginEntryContext(
    val sessionId: String? = null,
    val path: String? = null,
    val isDirectory: Boolean? = null,
    val selection: String? = null,
    val language: String? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null
)
