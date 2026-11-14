package com.aicode.feature.plugin.presentation

/**
 * 槽位入口的调用上下文；不同槽位按需填字段。
 *
 * 页面通过 `aicode.context()` 读取；动作作为 handler 参数。
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

/** 把容器路径转成插件宿主路径；工作区内使用 `workspace://`，其余原样返回。 */
fun pluginHostPath(raw: String?, workspaceRoot: String?): String? {
    if (raw.isNullOrBlank() || "://" in raw) return raw
    val root = workspaceRoot?.trimEnd('/').orEmpty()
    if (root.isNotEmpty() && raw.startsWith("$root/")) {
        return "workspace://" + raw.removePrefix("$root/")
    }
    return raw
}
