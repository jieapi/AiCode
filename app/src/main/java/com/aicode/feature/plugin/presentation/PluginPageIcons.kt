package com.aicode.feature.plugin.presentation

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.Book
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Code
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Image
import compose.icons.feathericons.Package
import compose.icons.feathericons.Play
import compose.icons.feathericons.Server
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap

/**
 * 插件页面 icon 名到宿主矢量图的映射。设置页、侧栏与顶栏菜单共用同一份，
 * 未知名称回退到包图标，保证同一个入口在不同位置外观一致。
 */
internal fun pluginPageIcon(icon: String): ImageVector = when (icon.lowercase()) {
    "book", "memory", "brain" -> FeatherIcons.Book
    "file", "text" -> FeatherIcons.FileText
    "settings", "sliders" -> FeatherIcons.Sliders
    "terminal", "code" -> FeatherIcons.Terminal
    "globe", "browser" -> FeatherIcons.Globe
    "users", "user" -> FeatherIcons.Users
    "zap", "bolt" -> FeatherIcons.Zap
    "server" -> FeatherIcons.Server
    "cloud" -> FeatherIcons.Cloud
    "image", "photo" -> FeatherIcons.Image
    "camera" -> FeatherIcons.Camera
    "tool" -> FeatherIcons.Tool
    "activity" -> FeatherIcons.Activity
    "play" -> FeatherIcons.Play
    "package", "box" -> FeatherIcons.Package
    else -> FeatherIcons.Package
}
