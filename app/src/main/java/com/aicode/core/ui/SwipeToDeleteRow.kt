package com.aicode.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 全项目统一的左滑删除行容器：底层红色删除按钮固定在右端，随左滑露出（带缩放/透明度渐变），
 * 表层内容整体左滑，滑开时点击表层收回。
 *
 * 手势与动画参数全项目保持一致（露出宽度 -112dp、按钮宽度 104dp、回弹弹簧参数），改这里即全局生效。
 *
 * 性能约定（长列表必需）：滑块位置 [Animatable] 只在 `offset {}` / `graphicsLayer {}` 里读取，
 * 绝不能在组合期读 `offsetX.value`——否则手指每移动一帧都会让整行重组，并反复增删删除按钮子树，
 * 列表一长就明显掉帧。
 *
 * @param onDelete 点按删除按钮后触发（先收回再回调）。是否弹二次确认由调用方决定。
 * @param onClick 表层未滑开时的点击回调；null 表示整行不可点击。
 * @param deleteEnabled false 时删除按钮可露出但点击无效（如未下载完成的镜像不可删）。
 * @param selectionMode 多选模式：不渲染滑动手势与删除按钮，整行点击改为勾选，行首加勾选框。
 * @param selected 多选模式下该行是否勾选。
 * @param onToggleSelect 多选模式下整行点击 / 勾选框点击的回调。
 * @param selectable false 时该行在多选模式下不可勾选（勾选框置灰，如不可编辑的提示词片段）。
 * @param content 表层行内容；背景由本组件提供，内边距由调用方内容自带（与既有各页用法一致）。
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    selectable: Boolean = true,
    content: @Composable () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    // 多选模式：行首勾选框 + 整行可点即勾选，滑动手势与红色删除按钮整体不参与组合。
    if (selectionMode) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(rowBackground)
                .clickable(enabled = selectable) { onToggleSelect() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = selectable,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.padding(start = Spacing.lg)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
        return
    }
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { -112.dp.toPx() } }
    val offsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // 不可删除时按钮呈灰色，与可删状态区分
    val buttonBg = if (deleteEnabled) Color(0xFFEF4444) else Color(0xFF9CA3AF)
    val buttonBorder = if (deleteEnabled) Color(0xFFF87171) else Color(0xFFD1D5DB)

    Box(modifier = modifier.fillMaxWidth()) {
        // 底层删除按钮：宽度固定，露出程度/缩放/透明度全部在 graphicsLayer 里按动画值现算。
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(104.dp)
                    .graphicsLayer {
                        // offsetX 与 revealPx 同为负值，比值即露出进度。
                        val progress = (offsetX.value / revealPx).coerceIn(0f, 1f)
                        alpha = (progress * 1.2f).coerceIn(0f, 1f)
                        scaleX = (0.4f + 0.6f * progress).coerceIn(0f, 1f)
                        scaleY = (0.7f + 0.3f * progress).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(buttonBg)
                    .border(1.dp, buttonBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = deleteEnabled) {
                        coroutineScope.launch {
                            offsetX.animateTo(0f)
                            onDelete()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.requiredWidth(104.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_delete),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        // 表层分组行：手势回弹与滑动展开；滑开状态下点击先收回、不触发 onClick
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(rowBackground)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coroutineScope.launch { offsetX.stop() }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < revealPx / 2) {
                                    offsetX.animateTo(
                                        targetValue = revealPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                } else {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(revealPx * 1.15f, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable {
                            if (offsetX.value < -10f) {
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            } else {
                                onClick()
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
