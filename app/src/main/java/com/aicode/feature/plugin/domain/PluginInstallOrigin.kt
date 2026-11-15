package com.aicode.feature.plugin.domain

/**
 * 一次安装的来源。本地 zip 只记 local；在线安装在宿主记录里绑定索引来源、
 * 插件源码仓库与包摘要，更新时沿同一来源检查。
 */
data class PluginInstallOrigin(
    val kind: String = KIND_LOCAL,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val indexUrl: String? = null,
    val repository: String? = null,
    val official: Boolean = false
) {
    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_INDEX = "index"
    }
}
