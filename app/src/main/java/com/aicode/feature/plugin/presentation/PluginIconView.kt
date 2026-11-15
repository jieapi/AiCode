package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.plugin.domain.PluginIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Book
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Code
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Github
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Package
import compose.icons.feathericons.Play
import compose.icons.feathericons.Server
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.ShoppingBag
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.User
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 插件图标解码后的最大边：列表 40dp、详情 48dp，3 倍屏也不超过 144px。 */
private const val PLUGIN_ICON_MAX_EDGE = 160

/**
 * 插件图标槽的内容：包内位图解码成功时填满槽位，否则显示内置图标。
 * 读盘与解码放在 IO 线程，避免插件列表滚动时卡主线程；解码只随路径变化重启，
 * 同一路径文件内容变化需等下次重组或版本变化才反映。
 */
@Composable
internal fun PluginIconContent(
    icon: PluginIcon?,
    tint: Color,
    iconSize: Dp,
    shape: Shape
) {
    val path = icon?.file?.absolutePath
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeSampledBitmap(path, PLUGIN_ICON_MAX_EDGE) }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(shape)
        )
    } else {
        Icon(
            imageVector = icon?.name?.let { resolvePluginBuiltinIcon(it) } ?: FeatherIcons.Package,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** 清单内置图标名到 Feather 图标的映射；未知名称回退 Package。 */
internal fun resolvePluginBuiltinIcon(name: String): ImageVector = when (name.lowercase()) {
    "book", "memory", "brain", "notes" -> FeatherIcons.Book
    "file", "text" -> FeatherIcons.FileText
    "settings", "config" -> FeatherIcons.Sliders
    "terminal", "shell" -> FeatherIcons.Terminal
    "code", "format" -> FeatherIcons.Code
    "cloud", "provider", "gateway" -> FeatherIcons.Cloud
    "server", "mcp" -> FeatherIcons.Server
    "tool", "hammer", "build" -> FeatherIcons.Tool
    "monitor", "overlay" -> FeatherIcons.Monitor
    "users", "agents" -> FeatherIcons.Users
    "user" -> FeatherIcons.User
    "play", "run" -> FeatherIcons.Play
    "zap" -> FeatherIcons.Zap
    "activity", "flask", "test" -> FeatherIcons.Activity
    "android", "phone" -> FeatherIcons.Smartphone
    "trash", "clean" -> FeatherIcons.Trash2
    "github" -> FeatherIcons.Github
    "shop", "store" -> FeatherIcons.ShoppingBag
    else -> FeatherIcons.Package
}
