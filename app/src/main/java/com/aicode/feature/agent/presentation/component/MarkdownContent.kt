package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.semanticColors
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

internal class MarkdownRenderCache(
    private val maxEntries: Int = 80
) {
    private val parsedStates = object : LinkedHashMap<String, MarkdownParseState.Success>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState.Success>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(text: String): MarkdownParseState.Success? = parsedStates[text]

    fun put(state: MarkdownParseState.Success) {
        parsedStates[state.content] = state
    }
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /** 解析未完成（Loading）时显示的内容；为 null 时回退显示原文纯文本（聊天流式场景）。 */
    loading: (@Composable () -> Unit)? = null,
    /** 长文本虚拟化渲染：正文改用 LazyColumn 逐块渲染（调用方需配合 heightIn 等有限高度约束
     *  才能触发懒加载），避免超大 md 一次性全量组合/测量造成的卡顿。 */
    lazyScroll: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact：正文、列表用 bodySmall，标题降一档，用于思考气泡等次要文本区域
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    val processed = remember(text) { MarkdownPreprocessor.process(text) }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val mathTransformer = remember(baseTransformer, body.fontSize.value) {
        MathImageTransformer(baseTransformer, body.fontSize.value)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        // rememberMarkdownState 必须无条件参与组合：若缓存命中就走 if 分支跳过它，其内部
        // remember 状态会被 dispose；下一帧文本变化（流式增长/停顿后恢复）缓存 miss 重建时
        // 初始状态是 Loading，会闪现原始 md 文本（解析/未解析反复横跳的根因）。
        // 缓存只作为渲染加速：解析结果与当前文本一致时直接用；不一致但缓存命中当前文本时
        // 用缓存；否则进入 Loading。注意：本库 rememberMarkdownState 在文本变化时会把
        // state 无条件置为 Loading（retainState 参数在此版本不生效），Loading 分支若回退
        // 显示原文纯文本，流式场景下每次文本变化都会闪现「最新文本的裸文本」（底部字在闪）。
        // 因此 Loading 期间优先渲染最近一次成功解析的结果（旧文本的完整 md 排版），解析
        // 完成后再平滑切到新文本，内容只增不跳。
        val mdState = rememberMarkdownState(content = processed, retainState = true)
        val parseState by mdState.state.collectAsState()
        val cachedState = cache?.get(processed)
        // 委托属性不能智能转换，先取局部快照再判断
        val currentState = parseState
        val parsedState: MarkdownParseState = when {
            currentState is MarkdownParseState.Success && currentState.content == processed -> currentState
            cachedState != null -> cachedState
            else -> currentState
        }

        // 最近一次成功解析的结果：Loading 期间的渲染兜底
        var lastSuccessState by remember { mutableStateOf<MarkdownParseState.Success?>(null) }
        if (parsedState is MarkdownParseState.Success) {
            lastSuccessState = parsedState
            LaunchedEffect(cache, parsedState) {
                cache?.put(parsedState)
            }
        }

        // Success 渲染当前结果；Loading 渲染上次成功结果（避免纯文本闪现）；Error 回退原文
        val renderState: MarkdownParseState.Success? = when (parsedState) {
            is MarkdownParseState.Success -> parsedState
            is MarkdownParseState.Loading -> lastSuccessState
            is MarkdownParseState.Error -> null
        }

        if (renderState != null) {
            // 长文本用 LazyColumn 逐块渲染时，animations（文本尺寸动画等）不适用，属预期。
            val successRenderer: @Composable (
                state: MarkdownParseState.Success,
                components: MarkdownComponents,
                modifier: Modifier
            ) -> Unit = if (lazyScroll) {
                { state, components, m ->
                    LazyMarkdownSuccess(state = state, components = components, modifier = m)
                }
            } else {
                { state, components, m ->
                    MarkdownSuccess(state = state, components = components, modifier = m)
                }
            }

            val mdComponents = markdownComponents(
                // 外层 SelectionContainer（MessageBubbles）统一负责选区；超长助手消息已由
                // AIChatPanel 拆成多条有界 item，不存在超长单 item 的选区树/交互失效问题。
                codeFence = {
                    MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlightsBuilder,
                        showHeader = true,
                    )
                },
                codeBlock = {
                    MarkdownHighlightedCodeBlock(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlightsBuilder,
                        showHeader = true,
                    )
                },
                // 库默认 maxLines=1 + Ellipsis，单元格长文会被截断；这里放开为完整多行显示。
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, header, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
            )

            Markdown(
                state = renderState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // 关闭段落文本的 animateContentSize：快速流式更新下它会持续追赶目标高度，反而弹性抖动。
                animations = markdownAnimations(animateTextSize = { this }),
                imageTransformer = mathTransformer,
                components = mdComponents,
                success = successRenderer,
            )
        } else if (loading != null) {
            loading()
        } else {
            PlainMarkdownText(
                text = text,
                color = color,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}
