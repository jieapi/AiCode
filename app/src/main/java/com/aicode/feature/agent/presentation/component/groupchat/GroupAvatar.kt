package com.aicode.feature.agent.presentation.component.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 确定性头像：名字哈希 → 稳定色相 + 形状 + 首字符。同名同脸，无需存储。
 * [avatarColor] 可为 hex（#E8505B）或 "hsl:<0-360>"；缺省按名字哈希。
 * [avatarShape] 支持 circle/squircle/hexagon；缺省 circle。
 */
@Composable
fun GroupAvatar(
    name: String,
    avatarColor: String? = null,
    avatarShape: String? = null,
    size: Dp = 28.dp
) {
    val bg = rememberAvatarColor(name, avatarColor)
    val shape = rememberAvatarShape(avatarShape)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = firstChar(name),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun rememberAvatarColor(name: String, override: String?): Color {
    override?.let { raw ->
        val hex = raw.trim()
        if (hex.startsWith("#") && hex.length == 7) {
            runCatching { return Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
        }
        if (hex.startsWith("hsl:", ignoreCase = true)) {
            hex.removePrefix("hsl:").removePrefix("HSL:").trim().toFloatOrNull()?.let { hue ->
                return Color.hsv(hue % 360f, 0.55f, 0.75f)
            }
        }
    }
    val hue = ((name.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.55f, 0.75f)
}

@Composable
private fun rememberAvatarShape(override: String?): Shape {
    return when (override?.trim()?.lowercase()) {
        "squircle" -> RoundedCornerShape(8.dp)
        "hexagon" -> HexagonShape
        else -> CircleShape
    }
}

private val HexagonShape = object : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w, h * 0.25f)
            lineTo(w, h * 0.75f)
            lineTo(w * 0.5f, h)
            lineTo(0f, h * 0.75f)
            lineTo(0f, h * 0.25f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

private fun firstChar(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    // 英文名取首字母大写；中文名取首字。
    return trimmed.first().uppercaseChar().toString()
}
