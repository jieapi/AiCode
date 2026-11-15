package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.domain.model.AdaptiveCardAction
import com.aicode.feature.settings.domain.model.AdaptiveCardElement
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.model.ActionButtonElement
import com.aicode.feature.settings.domain.model.BadgeElement
import com.aicode.feature.settings.domain.model.CardColor
import com.aicode.feature.settings.domain.model.CardPadding
import com.aicode.feature.settings.domain.model.ColumnElement
import com.aicode.feature.settings.domain.model.ColumnSetElement
import com.aicode.feature.settings.domain.model.ColumnWidth
import com.aicode.feature.settings.domain.model.ContainerElement
import com.aicode.feature.settings.domain.model.ContainerStyle
import com.aicode.feature.settings.domain.model.DividerElement
import com.aicode.feature.settings.domain.model.FactSetElement
import com.aicode.feature.settings.domain.model.FlowRowElement
import com.aicode.feature.settings.domain.model.GridElement
import com.aicode.feature.settings.domain.model.ImageElement
import com.aicode.feature.settings.domain.model.MetricElement
import com.aicode.feature.settings.domain.model.ProgressBarElement
import com.aicode.feature.settings.domain.model.RowElement
import com.aicode.feature.settings.domain.model.ScrollRowElement
import com.aicode.feature.settings.domain.model.SpacerElement
import com.aicode.feature.settings.domain.model.SpacingSize
import com.aicode.feature.settings.domain.model.StatusDotElement
import com.aicode.feature.settings.domain.model.TabElement
import com.aicode.feature.settings.domain.model.TabSetElement
import com.aicode.feature.settings.domain.model.TextBlockElement
import com.aicode.feature.settings.domain.model.TextSize
import com.aicode.feature.settings.domain.model.TextWeight

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.settings.domain.model.ActionSubmitElement
import com.aicode.feature.settings.domain.model.CardElement
import com.aicode.feature.settings.domain.model.CodeBlockElement
import com.aicode.feature.settings.domain.model.InputChoiceItem
import com.aicode.feature.settings.domain.model.InputListElement
import com.aicode.feature.settings.domain.model.InputSelectElement
import com.aicode.feature.settings.domain.model.InputTextElement
import com.aicode.feature.settings.domain.model.InputToggleElement
import com.aicode.feature.settings.domain.model.MarkdownBlockElement
import com.aicode.feature.settings.domain.parser.AdaptiveCardParser
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.HelpCircle

val LocalCardFormState = compositionLocalOf<MutableMap<String, Any>?> { null }
val LocalCardOnSubmit = compositionLocalOf<((String, String?, Map<String, Any>) -> Unit)?> { null }

/**
 * Adaptive Card 原生 Compose 渲染主入口。
 * 统一支持供应商面板（DIY）与插件结构化页面的渲染。
 */
@Composable
fun AdaptiveCardView(
    card: AdaptiveCardRoot,
    isExpanded: Boolean,
    onAction: (AdaptiveCardAction) -> Unit = {},
    onRefresh: () -> Unit = {},
    formState: MutableMap<String, Any>? = null,
    onSubmit: ((actionId: String, commandTemplate: String?, formValues: Map<String, Any>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(
        LocalCardFormState provides formState,
        LocalCardOnSubmit provides onSubmit
    ) {
        if (!isExpanded) {
            // ── 收起态视图 ──
            if (card.compact != null) {
                RenderElement(card.compact, isCompact = true, onAction = onAction, onRefresh = onRefresh, modifier = modifier)
            } else {
                // 自动从 body 提取前 2~3 个主要组件紧凑展示
                Row(
                    modifier = modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    card.body.take(3).forEach { element ->
                        Box(modifier = Modifier.weight(1f)) {
                            RenderElement(element, isCompact = true, onAction = onAction, onRefresh = onRefresh)
                        }
                    }
                }
            }
        } else {
            // ── 展开态视图 ──
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                card.body.forEach { element ->
                    RenderElement(element, isCompact = false, onAction = onAction, onRefresh = onRefresh)
                }
            }
        }
    }
}

@Composable
private fun RenderElement(
    element: AdaptiveCardElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (element) {
        is ColumnSetElement -> RenderColumnSet(element, isCompact, onAction, onRefresh, modifier)
        is ColumnElement -> RenderColumn(element, isCompact, onAction, onRefresh, modifier)
        is ContainerElement -> RenderContainer(element, isCompact, onAction, onRefresh, modifier)
        is RowElement -> RenderRow(element, isCompact, onAction, onRefresh, modifier)
        is GridElement -> RenderGrid(element, isCompact, onAction, onRefresh, modifier)
        is FlowRowElement -> RenderFlowRow(element, isCompact, onAction, onRefresh, modifier)
        is ScrollRowElement -> RenderScrollRow(element, isCompact, onAction, onRefresh, modifier)
        is SpacerElement -> RenderSpacer(element, modifier)
        is ImageElement -> RenderImage(element, modifier)
        is TabSetElement -> RenderTabSet(element, isCompact, onAction, onRefresh, modifier)
        is TabElement -> {
            element.items.forEach { child ->
                RenderElement(child, isCompact, onAction, onRefresh)
            }
        }
        is ActionButtonElement -> RenderActionButton(element, onAction, onRefresh, modifier)
        is TextBlockElement -> RenderTextBlock(element, isCompact, modifier)
        is ProgressBarElement -> RenderProgressBar(element, isCompact, modifier)
        is MetricElement -> RenderMetric(element, isCompact, modifier)
        is BadgeElement -> RenderBadge(element, modifier)
        is StatusDotElement -> RenderStatusDot(element, modifier)
        is FactSetElement -> RenderFactSet(element, modifier)
        is DividerElement -> RenderDivider(element, modifier)
        // ── 通用表单与富文本扩展 ──
        is InputTextElement -> RenderInputText(element, modifier)
        is InputSelectElement -> RenderInputSelect(element, modifier)
        is InputToggleElement -> RenderInputToggle(element, modifier)
        is InputListElement -> RenderInputList(element, modifier)
        is CodeBlockElement -> RenderCodeBlock(element, modifier)
        is MarkdownBlockElement -> RenderMarkdownBlock(element, modifier)
        is CardElement -> RenderCard(element, isCompact, onAction, onRefresh, modifier)
        is ActionSubmitElement -> RenderActionSubmit(element, modifier)
    }
}

@Composable
private fun RenderColumnSet(
    element: ColumnSetElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val spacingDp = element.gapDp?.dp ?: resolveSpacing(element.spacing)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val horizontalArrangement = when (element.horizontalAlignment?.trim()?.lowercase()) {
        "center" -> Arrangement.spacedBy(spacingDp, Alignment.CenterHorizontally)
        "right", "end" -> Arrangement.spacedBy(spacingDp, Alignment.End)
        else -> Arrangement.spacedBy(spacingDp, Alignment.Start)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .applyPadding(element.padding)
            .applyConstraints(minHeightDp = element.minHeightDp, maxHeightDp = element.maxHeightDp),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = if (isCompact) Alignment.CenterVertically else Alignment.Top
    ) {
        element.columns.forEachIndexed { index, column ->
            if (!isCompact && index > 0 && column.separator) {
                // 列间竖直分割线
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.sm)
                        .width(1.dp)
                        .height(48.dp)
                        .background(borderColor)
                )
            }

            val colModifier = when (column.width) {
                is ColumnWidth.Auto -> Modifier.wrapContentWidth()
                is ColumnWidth.Stretch -> Modifier.weight(1f).fillMaxWidth()
                is ColumnWidth.Weighted -> Modifier.weight(column.width.weight)
                is ColumnWidth.Fixed -> Modifier.width(column.width.dp.dp)
            }
                .applyPadding(column.padding)
                .applyConstraints(
                    minWidthDp = column.minWidthDp,
                    maxWidthDp = column.maxWidthDp,
                    minHeightDp = column.minHeightDp,
                    maxHeightDp = column.maxHeightDp
                )

            Box(modifier = colModifier) {
                RenderColumn(column, isCompact, onAction, onRefresh)
            }
        }
    }
}

@Composable
private fun RenderColumn(
    column: ColumnElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val verticalArrangement = when (column.verticalContentAlignment?.trim()?.lowercase()) {
        "center" -> Arrangement.spacedBy(columnGap(column, isCompact), Alignment.CenterVertically)
        "bottom" -> Arrangement.spacedBy(columnGap(column, isCompact), Alignment.Bottom)
        else -> Arrangement.spacedBy(columnGap(column, isCompact), Alignment.Top)
    }
    val horizontalAlignment = when (column.horizontalAlignment?.trim()?.lowercase()) {
        "center" -> Alignment.CenterHorizontally
        "right", "end" -> Alignment.End
        else -> Alignment.Start
    }

    Column(
        modifier = modifier,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment
    ) {
        column.items.forEach { child ->
            RenderElement(child, isCompact, onAction, onRefresh)
        }
    }
}

private fun columnGap(column: ColumnElement, isCompact: Boolean): Dp {
    return column.gapDp?.dp ?: resolveSpacing(column.spacing).let { if (it == 0.dp && isCompact) 3.dp else if (it == 0.dp) 4.dp else it }
}

@Composable
private fun RenderContainer(
    container: ContainerElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (bgColor, borderCol) = resolveContainerColors(container.style)
    val topSpacing = resolveSpacing(container.spacing)
    val cornerRadius = container.cornerRadiusDp?.dp ?: Radius.sm
    val shape = RoundedCornerShape(cornerRadius)
    val innerGap = container.gapDp?.dp ?: Spacing.xs
    val defaultPadding = if (container.style == ContainerStyle.DEFAULT) {
        CardPadding.Zero
    } else {
        CardPadding(top = 4, right = 8, bottom = 4, left = 8)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpacing)
            .applyMargin(container.margin)
            .applyConstraints(
                minWidthDp = container.minWidthDp,
                maxWidthDp = container.maxWidthDp,
                minHeightDp = container.minHeightDp,
                maxHeightDp = container.maxHeightDp
            )
            .clip(shape)
            .border(1.dp, borderCol, shape),
        shape = shape,
        color = bgColor
    ) {
        val effectivePadding = container.padding ?: defaultPadding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .applyPadding(if (container.bleed) CardPadding.Zero else effectivePadding),
            verticalArrangement = Arrangement.spacedBy(innerGap)
        ) {
            container.items.forEach { child ->
                RenderElement(child, isCompact, onAction, onRefresh)
            }
        }
    }
}

@Composable
private fun RenderRow(
    row: RowElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rowGap = row.gapDp?.dp ?: resolveSpacing(row.spacing)
    val verticalAlign = when (row.verticalAlignment?.trim()?.lowercase()) {
        "top" -> Alignment.Top
        "bottom" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(row.margin)
            .applyPadding(row.padding)
            .applyConstraints(minHeightDp = row.minHeightDp, maxHeightDp = row.maxHeightDp),
        horizontalArrangement = Arrangement.spacedBy(rowGap),
        verticalAlignment = verticalAlign
    ) {
        row.items.forEachIndexed { index, child ->
            val weight = row.itemWeights.getOrNull(index)
            val childModifier = when {
                weight != null -> Modifier.weight(weight)
                child is ColumnElement || child is ColumnSetElement -> Modifier.weight(1f)
                else -> Modifier
            }
            Box(modifier = childModifier) {
                RenderElement(child, isCompact, onAction, onRefresh)
            }
        }
    }
}

@Composable
private fun RenderTextBlock(
    element: TextBlockElement,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val textColor = resolveColor(element.color, isSubtle = element.isSubtle)
    val fontSize = element.fontSizeSp?.sp
        ?: if (isCompact && element.size == TextSize.DEFAULT) 12.sp else resolveFontSize(element.size)
    val fontWeight = resolveFontWeight(element.weight)
    val lineHeight = element.lineHeightSp?.sp ?: TextUnit.Unspecified
    val textAlign = when (element.horizontalAlignment?.trim()?.lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "justify" -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val textModifier = if (element.horizontalAlignment != null) {
        modifier.fillMaxWidth()
    } else {
        modifier
    }
        .applyMargin(element.margin)
        .applyPadding(element.padding)

    Text(
        text = element.text,
        modifier = textModifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = textColor,
        textAlign = textAlign,
        maxLines = element.maxLines ?: if (isCompact) 1 else 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RenderProgressBar(
    element: ProgressBarElement,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = resolveColor(element.color, isSubtle = false)
    val trackBgColor = element.trackColor?.let { parseHexColor(it) }
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    val targetFraction = (element.value / 100f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (element.animated) 300 else 0),
        label = "progressBarFraction"
    )

    val heightDp = if (isCompact) 4.dp else element.heightDp.dp
    val shape = if (element.cornerRadiusDp != null) {
        RoundedCornerShape(element.cornerRadiusDp.dp)
    } else {
        CircleShape
    }

    val displayText = when {
        !element.text.isNullOrBlank() -> element.text
        element.showPercent -> "${element.value.toInt()}%"
        else -> null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .height(heightDp)
            .clip(shape)
            .background(trackBgColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = animatedFraction)
                .fillMaxHeight()
                .clip(shape)
                .background(activeColor)
        )

        if (!displayText.isNullOrBlank() && !isCompact) {
            val textCol = element.textColor?.let { resolveColor(it, false) }
                ?: if (element.value > 50f) Color.White else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayText,
                    fontSize = (element.heightDp * 0.7f).coerceIn(9f, 12f).sp,
                    fontWeight = FontWeight.Bold,
                    color = textCol,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RenderMetric(
    element: MetricElement,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = resolveColor(element.color, isSubtle = false)

    if (isCompact) {
        // 收起态下的指标呈现
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (element.label.isNotBlank()) {
                    Text(
                        text = element.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val displayVal = if (element.value.isNotBlank()) element.value else "${element.percent?.toInt() ?: 0}%"
                Text(
                    text = displayVal,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Bold,
                    color = activeColor
                )
            }
            if (element.percent != null) {
                RenderProgressBar(
                    ProgressBarElement(value = element.percent, color = element.color, heightDp = 4),
                    isCompact = true
                )
            }
        }
    } else {
        // 展开态下的卡片呈现
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (element.label.isNotBlank()) {
                Text(
                    text = element.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (element.percent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayVal = if (element.value.isNotBlank()) element.value else "${element.percent.toInt()}%"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = displayVal,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                            fontWeight = FontWeight.Bold,
                            color = activeColor
                        )
                        RenderTrendBadge(element.trend)
                    }
                }
                RenderProgressBar(
                    ProgressBarElement(value = element.percent, color = element.color, heightDp = 6),
                    isCompact = false
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = element.value,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        color = if (element.color != CardColor.Default) activeColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (element.unit.isNotBlank()) {
                        Text(
                            text = element.unit,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RenderTrendBadge(element.trend)
                }
            }

            if (element.subText.isNotBlank()) {
                Text(
                    text = element.subText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RenderTrendBadge(trend: String?) {
    if (trend.isNullOrBlank()) return
    when (trend.trim().lowercase()) {
        "up" -> Text(
            text = "▲",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.semanticColors.success
        )
        "down" -> Text(
            text = "▼",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun RenderBadge(
    element: BadgeElement,
    modifier: Modifier = Modifier
) {
    val (bgColor, borderColor) = resolveContainerColors(element.style)
    val textColor = when (element.style) {
        ContainerStyle.GOOD -> MaterialTheme.semanticColors.success
        ContainerStyle.WARNING -> MaterialTheme.semanticColors.warning
        ContainerStyle.ATTENTION -> MaterialTheme.colorScheme.error
        ContainerStyle.ACCENT -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.xs))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val iconVector = element.icon?.let { resolveIcon(it) }
            if (iconVector != null) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = textColor
                )
            }
            Text(
                text = element.text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

@Composable
private fun RenderStatusDot(
    element: StatusDotElement,
    modifier: Modifier = Modifier
) {
    val dotColor = resolveColor(element.color, isSubtle = false)
    Box(
        modifier = modifier
            .size(element.sizeDp.dp)
            .clip(CircleShape)
            .background(dotColor)
    )
}

@Composable
private fun RenderFactSet(
    element: FactSetElement,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        element.facts.forEach { fact ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fact.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun RenderDivider(
    element: DividerElement,
    modifier: Modifier = Modifier
) {
    val spacing = resolveSpacing(element.spacing)
    Box(
        modifier = modifier
            .padding(vertical = spacing)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun RenderSpacer(
    element: SpacerElement,
    modifier: Modifier = Modifier
) {
    val spacerModifier = when {
        element.heightDp != null -> modifier.height(element.heightDp.dp)
        element.widthDp != null -> modifier.width(element.widthDp.dp)
        else -> modifier.height(Spacing.sm)
    }
    Spacer(spacerModifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderFlowRow(
    element: FlowRowElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hGap = element.gapDp?.dp ?: Spacing.sm
    val vGap = element.verticalGapDp?.dp ?: Spacing.xs

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .applyPadding(element.padding)
            .applyConstraints(minHeightDp = element.minHeightDp, maxHeightDp = element.maxHeightDp),
        horizontalArrangement = Arrangement.spacedBy(hGap),
        verticalArrangement = Arrangement.spacedBy(vGap)
    ) {
        element.items.forEach { child ->
            RenderElement(child, isCompact, onAction, onRefresh)
        }
    }
}

@Composable
private fun RenderScrollRow(
    element: ScrollRowElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gap = element.gapDp?.dp ?: Spacing.sm

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .applyMargin(element.margin)
            .applyPadding(element.padding)
            .applyConstraints(minHeightDp = element.minHeightDp, maxHeightDp = element.maxHeightDp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        element.items.forEach { child ->
            RenderElement(child, isCompact, onAction, onRefresh)
        }
    }
}

@Composable
private fun RenderImage(
    element: ImageElement,
    modifier: Modifier = Modifier
) {
    val iconVector = element.icon?.let { resolveIcon(it) } ?: return
    val tintColor = resolveColor(element.color, isSubtle = false)
    Icon(
        imageVector = iconVector,
        contentDescription = null,
        modifier = modifier
            .size(element.sizeDp.dp)
            .applyMargin(element.margin)
            .applyPadding(element.padding),
        tint = tintColor
    )
}

@Composable
private fun RenderTabSet(
    element: TabSetElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (element.tabs.isEmpty()) return

    if (isCompact) {
        // 收起态：只渲染第一个 tab 的内容
        element.tabs.first().items.forEach { child ->
            RenderElement(child, isCompact, onAction, onRefresh)
        }
        return
    }

    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    val safeIndex = selectedTabIndex.coerceIn(0, element.tabs.lastIndex)
    val isPills = element.tabStyle?.trim()?.lowercase() == "pills"
    val isBottom = element.tabPosition?.trim()?.lowercase() == "bottom"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .applyPadding(element.padding)
    ) {
        val tabBar: @Composable () -> Unit = {
            if (isPills) {
                RenderPillsTabBar(
                    tabs = element.tabs,
                    selectedIndex = safeIndex,
                    onSelect = { selectedTabIndex = it },
                    indicatorColor = element.indicatorColor,
                    backgroundColor = element.tabBackgroundColor,
                    contentColor = element.tabContentColor,
                    cornerRadiusDp = element.cornerRadiusDp
                )
            } else {
                PrimaryScrollableTabRow(
                    selectedTabIndex = safeIndex,
                    edgePadding = 0.dp,
                    containerColor = element.tabBackgroundColor?.let { resolveColor(it, false) } ?: androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = element.tabContentColor?.let { resolveColor(it, false) } ?: MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    element.tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = safeIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                TabLabel(
                                    tab = tab,
                                    selected = safeIndex == index,
                                    indicatorColor = element.indicatorColor,
                                    contentColor = element.tabContentColor
                                )
                            }
                        )
                    }
                }
            }
        }

        if (!isBottom) {
            tabBar()
        }

        AnimatedContent(
            targetState = safeIndex,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "tabContent"
        ) { tabIndex ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                element.tabs.getOrNull(tabIndex)?.items?.forEach { child ->
                    RenderElement(child, isCompact = false, onAction = onAction, onRefresh = onRefresh)
                }
            }
        }

        if (isBottom) {
            tabBar()
        }
    }
}

@Composable
private fun TabLabel(
    tab: TabElement,
    selected: Boolean,
    indicatorColor: CardColor?,
    contentColor: CardColor?
) {
    val activeColor = if (selected) {
        tab.color?.let { resolveColor(it, false) }
            ?: indicatorColor?.let { resolveColor(it, false) }
            ?: MaterialTheme.colorScheme.primary
    } else {
        contentColor?.let { resolveColor(it, false) }
            ?: MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val iconVector = tab.icon?.let { resolveIcon(it) }
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = activeColor
            )
        }
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = activeColor
        )
        if (!tab.badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.surface,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderPillsTabBar(
    tabs: List<TabElement>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    indicatorColor: CardColor?,
    backgroundColor: CardColor?,
    contentColor: CardColor?,
    cornerRadiusDp: Int?
) {
    val pillRadius = cornerRadiusDp?.dp ?: Radius.sm
    val selectedBg = indicatorColor?.let { resolveColor(it, false) }
        ?: MaterialTheme.colorScheme.primaryContainer
    val selectedFg = indicatorColor?.let { resolveColor(it, false) }
        ?: MaterialTheme.colorScheme.primary
    val unselectedFg = contentColor?.let { resolveColor(it, false) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                backgroundColor?.let { resolveColor(it, false) } ?: androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(pillRadius))
                    .clickable { onSelect(index) },
                shape = RoundedCornerShape(pillRadius),
                color = if (selected) selectedBg else androidx.compose.ui.graphics.Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val iconVector = tab.icon?.let { resolveIcon(it) }
                    if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (selected) selectedFg else unselectedFg
                        )
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) selectedFg else unselectedFg
                    )
                    if (!tab.badge.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) selectedFg else unselectedFg)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.badge,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.surface,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderActionButton(
    element: ActionButtonElement,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (bgColor, _) = resolveContainerColors(element.style)
    val fgColor = resolveColor(element.color, false)
    val effectiveBg = if (element.style == ContainerStyle.DEFAULT) {
        fgColor.copy(alpha = 0.12f)
    } else {
        bgColor
    }

    val isRefresh = element.actionType.lowercase() == "refresh"

    val action: AdaptiveCardAction? = when (element.actionType.lowercase()) {
        "refresh" -> null
        "copy", "copytoclipboard" -> element.value?.takeIf { it.isNotBlank() }
            ?.let { AdaptiveCardAction.CopyToClipboard(element.title, it) }
        else -> element.url?.takeIf { it.isNotBlank() }
            ?.let { AdaptiveCardAction.OpenUrl(element.title, it, element.icon) }
    }
    if (!isRefresh && action == null) return

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .applyMargin(element.margin)
            .applyPadding(element.padding)
            .clickable { if (isRefresh) onRefresh() else action?.let(onAction) },
        shape = RoundedCornerShape(Radius.sm),
        color = effectiveBg,
        border = if (element.style == ContainerStyle.DEFAULT) null
            else androidx.compose.foundation.BorderStroke(1.dp, fgColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val iconVector = element.icon?.let { resolveIcon(it) }
            if (iconVector != null) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = fgColor
                )
            }
            Text(
                text = element.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Medium,
                color = fgColor
            )
        }
    }
}

private fun resolveIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector? {
    return when (name.trim().lowercase()) {
        "external-link", "externallink", "link" -> FeatherIcons.ExternalLink
        "copy" -> FeatherIcons.Copy
        "help", "help-circle", "question" -> FeatherIcons.HelpCircle
        else -> null
    }
}

@Composable
private fun resolveColor(color: CardColor, isSubtle: Boolean): Color {
    if (isSubtle && color == CardColor.Default) {
        return MaterialTheme.colorScheme.onSurfaceVariant
    }
    return when (color) {
        CardColor.Default -> MaterialTheme.colorScheme.onSurface
        CardColor.Subtle -> MaterialTheme.colorScheme.onSurfaceVariant
        CardColor.Accent -> MaterialTheme.colorScheme.primary
        CardColor.Good -> MaterialTheme.semanticColors.success
        CardColor.Warning -> MaterialTheme.semanticColors.warning
        CardColor.Attention -> MaterialTheme.colorScheme.error
        is CardColor.Custom -> parseHexColor(color.hex) ?: MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun resolveContainerColors(style: ContainerStyle): Pair<Color, Color> {
    return when (style) {
        ContainerStyle.SUBTLE -> Pair(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
        ContainerStyle.EMPHASIS -> Pair(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            MaterialTheme.colorScheme.outlineVariant
        )
        ContainerStyle.GOOD -> Pair(
            MaterialTheme.semanticColors.success.copy(alpha = 0.12f),
            MaterialTheme.semanticColors.success.copy(alpha = 0.3f)
        )
        ContainerStyle.WARNING -> Pair(
            MaterialTheme.semanticColors.warning.copy(alpha = 0.12f),
            MaterialTheme.semanticColors.warning.copy(alpha = 0.3f)
        )
        ContainerStyle.ATTENTION -> Pair(
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        )
        ContainerStyle.ACCENT -> Pair(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
        ContainerStyle.DEFAULT -> Pair(
            Color.Transparent,
            Color.Transparent
        )
    }
}

private fun Modifier.applyPadding(padding: CardPadding?): Modifier {
    if (padding == null || padding.isZero) return this
    return this.padding(
        start = padding.left.dp,
        top = padding.top.dp,
        end = padding.right.dp,
        bottom = padding.bottom.dp
    )
}

private fun Modifier.applyMargin(margin: CardPadding?): Modifier {
    return applyPadding(margin)
}

private fun Modifier.applyConstraints(
    minWidthDp: Int? = null,
    maxWidthDp: Int? = null,
    minHeightDp: Int? = null,
    maxHeightDp: Int? = null
): Modifier {
    var modifier = this
    if (minWidthDp != null || maxWidthDp != null) {
        modifier = modifier.widthIn(
            min = minWidthDp?.dp ?: Dp.Unspecified,
            max = maxWidthDp?.dp ?: Dp.Unspecified
        )
    }
    if (minHeightDp != null || maxHeightDp != null) {
        modifier = modifier.heightIn(
            min = minHeightDp?.dp ?: Dp.Unspecified,
            max = maxHeightDp?.dp ?: Dp.Unspecified
        )
    }
    return modifier
}

private fun resolveFontSize(size: TextSize): TextUnit {
    return when (size) {
        TextSize.MICRO -> 10.sp
        TextSize.SMALL -> 12.sp
        TextSize.DEFAULT -> 14.sp
        TextSize.MEDIUM -> 16.sp
        TextSize.LARGE -> 18.sp
        TextSize.EXTRA_LARGE -> 22.sp
    }
}

private fun resolveFontWeight(weight: TextWeight): FontWeight {
    return when (weight) {
        TextWeight.LIGHTER -> FontWeight.Light
        TextWeight.DEFAULT -> FontWeight.Normal
        TextWeight.BOLDER -> FontWeight.Bold
    }
}

private fun resolveSpacing(spacing: SpacingSize): Dp {
    return when (spacing) {
        SpacingSize.NONE -> 0.dp
        SpacingSize.SMALL -> Spacing.xs
        SpacingSize.MEDIUM -> Spacing.sm
        SpacingSize.LARGE -> Spacing.md
    }
}

private fun parseHexColor(hex: String): Color? {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrNull()
}


@Composable
private fun RenderGrid(
    element: GridElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hGap = (element.gapDp ?: 8).dp
    val vGap = (element.verticalGapDp ?: 8).dp
    val columns = maxOf(1, element.columns)
    val chunked = remember(element.items, columns) {
        element.items.chunked(columns)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin)
            .applyPadding(element.padding),
        verticalArrangement = Arrangement.spacedBy(vGap)
    ) {
        chunked.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(hGap)
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        RenderElement(item, isCompact, onAction, onRefresh)
                    }
                }
                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 通用结构化表单与富组件原生渲染（支持插件与自定义面板）
// ════════════════════════════════════════════════════════════════

@Composable
private fun RenderInputText(
    element: InputTextElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = formState?.get(element.id)?.toString() ?: element.defaultValue
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (element.label.isNotBlank()) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        AppTextField(
            value = currentVal,
            onValueChange = { newVal -> formState?.set(element.id, newVal) },
            placeholder = element.placeholder.ifBlank { null },
            singleLine = !element.multiline,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RenderInputSelect(
    element: InputSelectElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = formState?.get(element.id)?.toString() ?: element.defaultValue
    var expanded by remember { mutableStateOf(false) }
    val currentChoice = element.choices.firstOrNull { it.value == currentVal } ?: element.choices.firstOrNull()
    val displayTitle = currentChoice?.title ?: currentVal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = element.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.semanticColors.cardSurface)
            ) {
                element.choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = choice.title,
                                color = if (choice.value == currentVal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (choice.value == currentVal) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            formState?.set(element.id, choice.value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderInputToggle(
    element: InputToggleElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val currentVal = (formState?.get(element.id) as? Boolean) ?: element.defaultValue
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            element.hint?.takeIf { it.isNotBlank() }?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AppSwitch(
            checked = currentVal,
            onCheckedChange = { formState?.set(element.id, it) }
        )
    }
}

@Composable
private fun RenderInputList(
    element: InputListElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    @Suppress("UNCHECKED_CAST")
    val list = (formState?.get(element.id) as? List<String>) ?: element.defaultValues
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (element.label.isNotBlank()) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        list.forEachIndexed { index, param ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AppTextField(
                    value = param,
                    onValueChange = { newVal ->
                        val updated = list.toMutableList()
                        updated[index] = newVal
                        formState?.set(element.id, updated)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = element.placeholder.ifBlank {
                        stringResource(R.string.card_form_param_placeholder)
                    }
                )
                IconButton(
                    onClick = {
                        val updated = list.toMutableList()
                        updated.removeAt(index)
                        formState?.set(element.id, updated)
                    }
                ) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .clickable {
                    val updated = list.toMutableList()
                    updated.add("")
                    formState?.set(element.id, updated)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.card_form_add_param),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RenderCodeBlock(
    element: CodeBlockElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val codeToShow = if (formState != null && element.code.contains("$")) {
        AdaptiveCardParser.interpolate(element.code, formState.toMap())
    } else {
        element.code
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .padding(Spacing.md)
    ) {
        Text(
            text = codeToShow,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            color = Color(0xFFD4D4D4)
        )
    }
}

@Composable
private fun RenderMarkdownBlock(
    element: MarkdownBlockElement,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        MarkdownContent(
            text = element.content,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RenderCard(
    element: CardElement,
    isCompact: Boolean,
    onAction: (AdaptiveCardAction) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(element.expandedDefault) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .applyMargin(element.margin),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .applyPadding(element.padding ?: CardPadding.all(12))
        ) {
            if (!element.title.isNullOrBlank()) {
                if (element.collapsible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = element.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Text(
                        text = element.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            AnimatedVisibility(visible = !element.collapsible || expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    element.items.forEach { child ->
                        RenderElement(child, isCompact, onAction, onRefresh)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderActionSubmit(
    element: ActionSubmitElement,
    modifier: Modifier = Modifier
) {
    val formState = LocalCardFormState.current
    val onSubmit = LocalCardOnSubmit.current
    Button(
        onClick = {
            onSubmit?.invoke(element.id, element.commandTemplate, formState?.toMap() ?: emptyMap())
        },
        shape = RoundedCornerShape(Radius.mdLarge),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = element.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
