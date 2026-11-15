package com.aicode.feature.plugin.data.index

import java.net.URI

/** 官方插件判定只看插件源码仓库是否归属 AiCode 官方 GitHub 账号/组织。 */
object PluginOfficialRepositories {
    val GITHUB_OWNERS = setOf("jieapi")

    fun ownerOf(repository: String): String? {
        val uri = runCatching { URI(repository) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com") return null
        val segments = uri.path.trim('/').split('/')
        if (segments.size != 2 || segments.any { it.isBlank() }) return null
        return segments[0].lowercase()
    }

    fun isOfficial(repository: String): Boolean = ownerOf(repository) in GITHUB_OWNERS
}
