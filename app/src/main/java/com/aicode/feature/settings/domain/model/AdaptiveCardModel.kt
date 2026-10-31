package com.aicode.feature.settings.domain.model

/**
 * 传递给面板脚本的实时上下文环境变量。
 */
data class DashboardContext(
    val model: String = "",
    val workspacePath: String = "",
    val workspaceName: String = "",
    val sessionId: String = "",
    val lastInputTokens: Int = 0,
    val lastOutputTokens: Int = 0,
    val lastCachedTokens: Int = 0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val modelContextTokens: Int = 0,
    val modelMaxInputTokens: Int = 0,
    val modelMaxOutputTokens: Int = 0,
    val modelInputCostUsdPerM: Double = 0.0,
    val modelOutputCostUsdPerM: Double = 0.0,
    val modelCacheReadCostUsdPerM: Double = 0.0,
    val modelSupportsTools: Boolean = false,
    val modelSupportsVision: Boolean = false,
    val modelSupportsReasoning: Boolean = false,
    val messageCount: Int = 0,
    val agentState: String = "",
    val sessionMode: String = "",
    val reasoningEffort: String = "",
    val refreshReason: String = ""
)

/**
 * Adaptive Card 根对象。
 */
data class AdaptiveCardRoot(
    val version: String = "1.5",
    val compact: AdaptiveCardElement? = null,
    val body: List<AdaptiveCardElement> = emptyList()
)

/**
 * 语义化颜色定义，支持标准语义色或自定义 16 进制颜色。
 */
sealed interface CardColor {
    data object Default : CardColor
    data object Subtle : CardColor
    data object Accent : CardColor
    data object Good : CardColor
    data object Warning : CardColor
    data object Attention : CardColor
    data class Custom(val hex: String) : CardColor

    companion object {
        fun fromString(str: String?): CardColor {
            if (str.isNullOrBlank()) return Default
            return when (str.trim().lowercase()) {
                "default" -> Default
                "subtle" -> Subtle
                "accent" -> Accent
                "good" -> Good
                "warning" -> Warning
                "attention", "error", "danger" -> Attention
                else -> if (str.startsWith("#")) Custom(str) else Default
            }
        }
    }
}

/**
 * 容器背景样式。
 */
enum class ContainerStyle {
    DEFAULT,
    SUBTLE,
    EMPHASIS,
    GOOD,
    WARNING,
    ATTENTION,
    ACCENT;

    companion object {
        fun fromString(str: String?): ContainerStyle {
            return when (str?.trim()?.lowercase()) {
                "subtle" -> SUBTLE
                "emphasis" -> EMPHASIS
                "good" -> GOOD
                "warning" -> WARNING
                "attention", "error", "danger" -> ATTENTION
                "accent" -> ACCENT
                else -> DEFAULT
            }
        }
    }
}

/**
 * 文本字号。
 */
enum class TextSize {
    MICRO,       // 10sp
    SMALL,       // 12sp
    DEFAULT,     // 14sp
    MEDIUM,      // 16sp
    LARGE,       // 18sp
    EXTRA_LARGE; // 22sp

    companion object {
        fun fromString(str: String?): TextSize {
            return when (str?.trim()?.lowercase()) {
                "micro" -> MICRO
                "small" -> SMALL
                "medium" -> MEDIUM
                "large" -> LARGE
                "extralarge", "extra_large", "xl" -> EXTRA_LARGE
                else -> DEFAULT
            }
        }
    }
}

/**
 * 文本字重。
 */
enum class TextWeight {
    LIGHTER,
    DEFAULT,
    BOLDER;

    companion object {
        fun fromString(str: String?): TextWeight {
            return when (str?.trim()?.lowercase()) {
                "lighter", "light" -> LIGHTER
                "bolder", "bold", "semibold" -> BOLDER
                else -> DEFAULT
            }
        }
    }
}

/**
 * 间距尺寸。
 */
enum class SpacingSize {
    NONE,
    SMALL,
    MEDIUM,
    LARGE;

    companion object {
        fun fromString(str: String?): SpacingSize {
            return when (str?.trim()?.lowercase()) {
                "none" -> NONE
                "small", "sm" -> SMALL
                "large", "lg" -> LARGE
                else -> MEDIUM
            }
        }
    }
}

/**
 * 边距定义（dp）。
 */
data class CardPadding(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0
) {
    val isZero: Boolean get() = top == 0 && right == 0 && bottom == 0 && left == 0

    companion object {
        val Zero = CardPadding()
        fun all(value: Int) = CardPadding(value, value, value, value)
        fun symmetric(vertical: Int = 0, horizontal: Int = 0) = CardPadding(vertical, horizontal, vertical, horizontal)
    }
}

/**
 * 列宽度模式。
 */
sealed interface ColumnWidth {
    data object Auto : ColumnWidth
    data object Stretch : ColumnWidth
    data class Weighted(val weight: Float) : ColumnWidth
    data class Fixed(val dp: Int) : ColumnWidth

    companion object {
        fun fromString(str: String?): ColumnWidth {
            if (str.isNullOrBlank()) return Stretch
            val trimmed = str.trim().lowercase()
            return when (trimmed) {
                "auto" -> Auto
                "stretch" -> Stretch
                else -> {
                    if (trimmed.endsWith("dp") || trimmed.endsWith("px")) {
                        val num = trimmed.removeSuffix("dp").removeSuffix("px").trim().toIntOrNull()
                        if (num != null && num > 0) Fixed(num) else Stretch
                    } else {
                        val weight = trimmed.toFloatOrNull()
                        if (weight != null && weight > 0f) Weighted(weight) else Stretch
                    }
                }
            }
        }
    }
}

/**
 * 键值对明细项。
 */
data class FactItem(
    val title: String,
    val value: String
)

/**
 * Adaptive Card 组件基础接口。
 */
sealed interface AdaptiveCardElement

/**
 * 多列网格容器。
 */
data class ColumnSetElement(
    val columns: List<ColumnElement> = emptyList(),
    val spacing: SpacingSize = SpacingSize.MEDIUM,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val horizontalAlignment: String? = null
) : AdaptiveCardElement

/**
 * 单个列。
 */
data class ColumnElement(
    val width: ColumnWidth = ColumnWidth.Stretch,
    val separator: Boolean = false,
    val spacing: SpacingSize = SpacingSize.NONE,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val minWidthDp: Int? = null,
    val maxWidthDp: Int? = null,
    val verticalContentAlignment: String? = null,
    val horizontalAlignment: String? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * 通用块级容器。
 */
data class ContainerElement(
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val bleed: Boolean = false,
    val spacing: SpacingSize = SpacingSize.NONE,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val minWidthDp: Int? = null,
    val maxWidthDp: Int? = null,
    val cornerRadiusDp: Int? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * 单行水平容器（常用于 compact 收起态）。
 */
data class RowElement(
    val spacing: SpacingSize = SpacingSize.SMALL,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val verticalAlignment: String? = null,
    val itemWeights: List<Float?> = emptyList(),
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * 文本组件。
 */
data class TextBlockElement(
    val text: String,
    val size: TextSize = TextSize.DEFAULT,
    val fontSizeSp: Float? = null,
    val lineHeightSp: Float? = null,
    val weight: TextWeight = TextWeight.DEFAULT,
    val color: CardColor = CardColor.Default,
    val isSubtle: Boolean = false,
    val maxLines: Int? = null,
    val verticalAlignment: String? = null,
    val horizontalAlignment: String? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * 进度条组件。
 */
data class ProgressBarElement(
    val value: Float = 0f,
    val color: CardColor = CardColor.Accent,
    val trackColor: String? = null,
    val heightDp: Int = 6,
    val animated: Boolean = true,
    val cornerRadiusDp: Int? = null,
    val text: String? = null,
    val showPercent: Boolean = false,
    val textColor: CardColor? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * 综合指标块（标题 + 大字数值 + 单位 + 进度条 + 副文本）。
 */
data class MetricElement(
    val label: String = "",
    val value: String = "",
    val unit: String = "",
    val subText: String = "",
    val percent: Float? = null,
    val color: CardColor = CardColor.Default,
    val trend: String? = null
) : AdaptiveCardElement

/**
 * 胶囊徽章。
 */
data class BadgeElement(
    val text: String,
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val icon: String? = null
) : AdaptiveCardElement

/**
 * 状态小圆点。
 */
data class StatusDotElement(
    val color: CardColor = CardColor.Good,
    val sizeDp: Int = 6
) : AdaptiveCardElement

/**
 * 键值对明细列表。
 */
data class FactSetElement(
    val facts: List<FactItem> = emptyList()
) : AdaptiveCardElement

/**
 * 分割线。
 */
data class DividerElement(
    val spacing: SpacingSize = SpacingSize.MEDIUM
) : AdaptiveCardElement

/**
 * 显式空白占位元素。
 */
data class SpacerElement(
    val heightDp: Int? = null,
    val widthDp: Int? = null,
    val weight: Float? = null
) : AdaptiveCardElement

/**
 * 自动换行流式布局容器。
 */
data class FlowRowElement(
    val items: List<AdaptiveCardElement> = emptyList(),
    val gapDp: Int? = null,
    val verticalGapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null
) : AdaptiveCardElement

/**
 * 横向滚动容器。
 */
data class ScrollRowElement(
    val items: List<AdaptiveCardElement> = emptyList(),
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null
) : AdaptiveCardElement

/**
 * 图标元素。icon 使用内置图标名称映射。
 */
data class ImageElement(
    val icon: String? = null,
    val sizeDp: Int = 16,
    val color: CardColor = CardColor.Default,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * 单个标签页。
 */
data class TabElement(
    val label: String,
    val icon: String? = null,
    val badge: String? = null,
    val color: CardColor? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * 标签页容器，在有限空间内通过 Tab 切换展示多组内容。
 */
data class TabSetElement(
    val tabs: List<TabElement> = emptyList(),
    val tabPosition: String? = null,
    val tabStyle: String? = null,
    val indicatorColor: CardColor? = null,
    val tabBackgroundColor: CardColor? = null,
    val tabContentColor: CardColor? = null,
    val cornerRadiusDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * 可点击按钮元素，可放在任意容器中。替代旧的根级 actions。
 */
data class ActionButtonElement(
    val title: String,
    val actionType: String = "openUrl",
    val url: String? = null,
    val value: String? = null,
    val icon: String? = null,
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val color: CardColor = CardColor.Accent,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * 动作操作。
 */
sealed interface AdaptiveCardAction {
    val title: String

    data class OpenUrl(
        override val title: String,
        val url: String,
        val icon: String? = null
    ) : AdaptiveCardAction

    data class CopyToClipboard(
        override val title: String,
        val value: String
    ) : AdaptiveCardAction
}

// ── 以下为增强的通用表单与富交互节点（支持插件结构化 UI 与自定义面板扩展） ──

/** 表单输入元素统一接口，[id] 为变量键名。 */
sealed interface FormInputElement : AdaptiveCardElement {
    val id: String
}

/** 单行/多行文本输入框。 */
data class InputTextElement(
    override val id: String,
    val label: String = "",
    val placeholder: String = "",
    val defaultValue: String = "",
    val multiline: Boolean = false
) : FormInputElement

/** 下拉选项条目。 */
data class InputChoiceItem(
    val title: String,
    val value: String
)

/** 下拉单选/选择集。 */
data class InputSelectElement(
    override val id: String,
    val label: String = "",
    val choices: List<InputChoiceItem> = emptyList(),
    val defaultValue: String = ""
) : FormInputElement

/** 布尔开关（Toggle/Switch）。 */
data class InputToggleElement(
    override val id: String,
    val label: String = "",
    val hint: String? = null,
    val defaultValue: Boolean = false
) : FormInputElement

/** 动态参数列表输入（支持增加/删除条目）。 */
data class InputListElement(
    override val id: String,
    val label: String = "",
    val placeholder: String = "",
    val defaultValues: List<String> = emptyList()
) : FormInputElement

/** 语法高亮代码块展示。 */
data class CodeBlockElement(
    val code: String,
    val language: String = "bash"
) : AdaptiveCardElement

/** Markdown 富文本说明块。 */
data class MarkdownBlockElement(
    val content: String
) : AdaptiveCardElement

/** 带标题、支持折叠的分组卡片。 */
data class CardElement(
    val title: String? = null,
    val collapsible: Boolean = false,
    val expandedDefault: Boolean = true,
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * 二维网格布局容器：支持固定列数或最小项宽自适应降列。
 * 解决原有自定义面板只能单列 Column 或多行手动拼凑 ColumnSet 的限制。
 */
data class GridElement(
    val columns: Int = 2,
    val minColumnWidthDp: Int? = null,
    val gapDp: Int? = null,
    val verticalGapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/** 表单提交/命令执行主操作按钮。 */
data class ActionSubmitElement(
    val id: String = "submit",
    val title: String,
    val commandTemplate: String? = null,
    val terminalTitle: String? = null,
    val style: ContainerStyle = ContainerStyle.ACCENT
) : AdaptiveCardElement
