package com.aicode.feature.plugin.runtime

/**
 * 插件提供商的全局 ID：`plugin:<插件ID>:<局部ID>`。
 *
 * 插件与局部 ID 都只含字母、数字、`-`、`_`（插件 ID 另含 `.`），不含冒号，
 * 因此前缀之后可以安全切分。ID 前缀同时是插件归属的唯一标记，持久化后不依赖
 * 额外数据库列。
 */
object PluginProviderIds {

    const val ID_PREFIX = "plugin:"

    fun idOf(pluginId: String, providerId: String): String = "$ID_PREFIX$pluginId:$providerId"

    fun isPluginId(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun pluginIdOf(id: String): String? {
        val (pluginId, _) = split(id) ?: return null
        return pluginId
    }

    fun localIdOf(id: String): String? {
        val (_, localId) = split(id) ?: return null
        return localId
    }

    private fun split(id: String): Pair<String, String>? {
        if (!isPluginId(id)) return null
        val rest = id.removePrefix(ID_PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0 || separator == rest.lastIndex) return null
        return rest.substring(0, separator) to rest.substring(separator + 1)
    }
}
