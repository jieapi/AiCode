package com.aicode.feature.settings.domain.parser

import com.aicode.feature.settings.domain.model.ActionButtonElement
import com.aicode.feature.settings.domain.model.ActionSubmitElement
import com.aicode.feature.settings.domain.model.AdaptiveCardElement
import com.aicode.feature.settings.domain.model.AdaptiveCardRoot
import com.aicode.feature.settings.domain.model.BadgeElement
import com.aicode.feature.settings.domain.model.CardColor
import com.aicode.feature.settings.domain.model.CardElement
import com.aicode.feature.settings.domain.model.CardPadding
import com.aicode.feature.settings.domain.model.CodeBlockElement
import com.aicode.feature.settings.domain.model.ColumnElement
import com.aicode.feature.settings.domain.model.ColumnSetElement
import com.aicode.feature.settings.domain.model.ColumnWidth
import com.aicode.feature.settings.domain.model.ContainerElement
import com.aicode.feature.settings.domain.model.ContainerStyle
import com.aicode.feature.settings.domain.model.DividerElement
import com.aicode.feature.settings.domain.model.FactItem
import com.aicode.feature.settings.domain.model.FactSetElement
import com.aicode.feature.settings.domain.model.FlowRowElement
import com.aicode.feature.settings.domain.model.GridElement
import com.aicode.feature.settings.domain.model.ImageElement
import com.aicode.feature.settings.domain.model.InputChoiceItem
import com.aicode.feature.settings.domain.model.InputListElement
import com.aicode.feature.settings.domain.model.InputSelectElement
import com.aicode.feature.settings.domain.model.InputTextElement
import com.aicode.feature.settings.domain.model.InputToggleElement
import com.aicode.feature.settings.domain.model.MarkdownBlockElement
import com.aicode.feature.settings.domain.model.MetricElement
import com.aicode.feature.settings.domain.model.ProgressBarElement
import com.aicode.feature.settings.domain.model.RowElement
import com.aicode.feature.settings.domain.model.ScrollRowElement
import com.aicode.feature.settings.domain.model.SpacingSize
import com.aicode.feature.settings.domain.model.SpacerElement
import com.aicode.feature.settings.domain.model.StatusDotElement
import com.aicode.feature.settings.domain.model.TabElement
import com.aicode.feature.settings.domain.model.TabSetElement
import com.aicode.feature.settings.domain.model.TextBlockElement
import com.aicode.feature.settings.domain.model.TextSize
import com.aicode.feature.settings.domain.model.TextWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 通用结构化卡片解析器：支持供应商面板（DIY）及插件页面的统一结构化声明。
 */
object AdaptiveCardParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析原始 JSON 文本为 [AdaptiveCardRoot]。 */
    fun parseAdaptiveCard(rawJson: String): AdaptiveCardRoot {
        return try {
            val element = json.parseToJsonElement(rawJson)
            parseAdaptiveCard(element)
        } catch (_: Exception) {
            val snippet = extractJsonSnippet(rawJson)
            if (snippet != null) {
                try {
                    val element = json.parseToJsonElement(snippet)
                    parseAdaptiveCard(element)
                } catch (_: Exception) {
                    AdaptiveCardRoot()
                }
            } else {
                AdaptiveCardRoot()
            }
        }
    }

    /** 解析已解析的 [JsonElement] 为 [AdaptiveCardRoot]。 */
    fun parseAdaptiveCard(element: JsonElement): AdaptiveCardRoot {
        if (element is JsonObject) {
            val version = element["version"]?.primitiveStringOrNull() ?: "1.5"

            val compactElement = element["compact"]?.let { parseElement(it) }

            val bodyList = mutableListOf<AdaptiveCardElement>()
            val bodyArray = element["body"] as? JsonArray
                ?: element["items"] as? JsonArray
                ?: element["elements"] as? JsonArray
            if (bodyArray != null) {
                for (item in bodyArray) {
                    parseElement(item)?.let { bodyList.add(it) }
                }
            } else if (element["type"]?.primitiveStringOrNull() != "AdaptiveCard") {
                parseElement(element)?.let { bodyList.add(it) }
            }

            return AdaptiveCardRoot(
                version = version,
                compact = compactElement,
                body = bodyList
            )
        } else if (element is JsonArray) {
            val bodyList = mutableListOf<AdaptiveCardElement>()
            for (item in element) {
                parseElement(item)?.let { bodyList.add(it) }
            }
            return AdaptiveCardRoot(body = bodyList)
        }
        return AdaptiveCardRoot()
    }

    /** 单个元素分派解析 */
    fun parseElement(element: JsonElement): AdaptiveCardElement? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.primitiveStringOrNull()?.trim()?.lowercase() ?: "textblock"

        val visible = obj["visible"]?.primitiveBooleanOrNull()
        if (visible == false) return null

        return when (type) {
            "columnset", "columns" -> parseColumnSet(obj)
            "column" -> parseColumn(obj)
            "container", "box" -> parseContainer(obj)
            "card", "collapsiblecard" -> parseCard(obj)
            "grid", "gridlayout", "gridset" -> parseGrid(obj)
            "row" -> parseRow(obj)
            "flowrow", "flow", "wrap" -> parseFlowRow(obj)
            "scrollrow", "scroll" -> parseScrollRow(obj)
            "spacer" -> parseSpacer(obj)
            "image", "img", "icon" -> parseImage(obj)
            "tabset", "tabs" -> parseTabSet(obj)
            "tab" -> parseTab(obj)
            "button", "actionbutton", "action" -> parseActionButton(obj)
            "submit", "actionsubmit", "run" -> parseActionSubmit(obj)
            "textblock", "text" -> parseTextBlock(obj)
            "progressbar", "progress" -> parseProgressBar(obj)
            "metric", "stat", "statblock" -> parseMetric(obj)
            "badge", "tag" -> parseBadge(obj)
            "statusdot", "dot" -> parseStatusDot(obj)
            "factset", "facts", "table" -> parseFactSet(obj)
            "divider", "separator", "hr" -> parseDivider(obj)
            // ── 表单与富文本扩展 ──
            "input.text", "inputtext", "textinput", "input" -> parseInputText(obj)
            "input.choiceset", "inputselect", "select", "dropdown" -> parseInputSelect(obj)
            "input.toggle", "inputswitch", "switch", "toggle" -> parseInputToggle(obj)
            "input.list", "parameterlist", "params" -> parseInputList(obj)
            "code", "codeblock", "script" -> parseCodeBlock(obj)
            "markdown", "richtext", "doc" -> parseMarkdownBlock(obj)
            else -> parseTextBlock(obj)
        }
    }

    private fun parseColumnSet(obj: JsonObject): ColumnSetElement {
        val columns = mutableListOf<ColumnElement>()
        val colsArray = obj["columns"] as? JsonArray
        if (colsArray != null) {
            for (col in colsArray) {
                val colObj = col as? JsonObject ?: continue
                columns.add(parseColumn(colObj))
            }
        }
        val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["columnGap"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
        val horizontalAlignment = (
            obj["horizontalAlignment"]
                ?: obj["horizontal_alignment"]
                ?: obj["align"]
            )?.primitiveStringOrNull()
        return ColumnSetElement(
            columns = columns,
            spacing = spacing,
            gapDp = gapDp,
            padding = padding,
            margin = margin,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp,
            horizontalAlignment = horizontalAlignment
        )
    }

    private fun parseColumn(obj: JsonObject): ColumnElement {
        val width = ColumnWidth.fromString(obj["width"]?.primitiveStringOrNull())
        val separator = obj["separator"]?.primitiveBooleanOrNull() ?: false
        val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
        val padding = parsePadding(obj["padding"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
        val minWidthDp = parseDpOrNull(obj["minWidth"] ?: obj["min_width"])
        val maxWidthDp = parseDpOrNull(obj["maxWidth"] ?: obj["max_width"])
        val verticalContentAlignment = (
            obj["verticalContentAlignment"]
                ?: obj["vertical_content_alignment"]
                ?: obj["verticalAlignment"]
            )?.primitiveStringOrNull()
        val horizontalAlignment = (
            obj["horizontalAlignment"]
                ?: obj["horizontal_alignment"]
                ?: obj["align"]
            )?.primitiveStringOrNull()

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray
            ?: obj["body"] as? JsonArray
            ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return ColumnElement(
            width = width,
            separator = separator,
            spacing = spacing,
            gapDp = gapDp,
            padding = padding,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp,
            minWidthDp = minWidthDp,
            maxWidthDp = maxWidthDp,
            verticalContentAlignment = verticalContentAlignment,
            horizontalAlignment = horizontalAlignment,
            items = items
        )
    }

    private fun parseContainer(obj: JsonObject): ContainerElement {
        val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
        val bleed = obj["bleed"]?.primitiveBooleanOrNull() ?: false
        val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"])
        val minWidthDp = parseDpOrNull(obj["minWidth"] ?: obj["width"])
        val maxWidthDp = parseDpOrNull(obj["maxWidth"])
        val cornerRadiusDp = parseDpOrNull(obj["cornerRadius"] ?: obj["radius"])

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return ContainerElement(
            style = style,
            bleed = bleed,
            spacing = spacing,
            gapDp = gapDp,
            padding = padding,
            margin = margin,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp,
            minWidthDp = minWidthDp,
            maxWidthDp = maxWidthDp,
            cornerRadiusDp = cornerRadiusDp,
            items = items
        )
    }

    private fun parseCard(obj: JsonObject): CardElement {
        val title = obj["title"]?.primitiveStringOrNull()
        val collapsible = obj["collapsible"]?.primitiveBooleanOrNull() ?: false
        val expandedDefault = obj["expandedDefault"]?.primitiveBooleanOrNull() ?: true
        val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return CardElement(
            title = title,
            collapsible = collapsible,
            expandedDefault = expandedDefault,
            style = style,
            padding = padding,
            margin = margin,
            items = items
        )
    }

    private fun parseGrid(obj: JsonObject): GridElement {
        val columns = obj["columns"]?.primitiveIntOrNull() ?: 2
        val minColumnWidthDp = parseDpOrNull(obj["minColumnWidth"] ?: obj["minWidth"] ?: obj["minItemWidth"])
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["columnGap"] ?: obj["horizontalGap"]) ?: 8
        val verticalGapDp = parseDpOrNull(obj["verticalGap"] ?: obj["rowGap"] ?: obj["gap"]) ?: 8
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return GridElement(
            columns = maxOf(1, columns),
            minColumnWidthDp = minColumnWidthDp,
            gapDp = gapDp,
            verticalGapDp = verticalGapDp,
            padding = padding,
            margin = margin,
            items = items
        )
    }

    private fun parseRow(obj: JsonObject): RowElement {
        val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"])
        val verticalAlignment = obj["verticalAlignment"]?.primitiveStringOrNull()

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        val itemWeights = mutableListOf<Float?>()

        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let {
                    items.add(it)
                    val weight = (item as? JsonObject)?.get("weight")?.primitiveDoubleOrNull()?.toFloat()
                    itemWeights.add(weight)
                }
            }
        }
        return RowElement(
            spacing = spacing,
            gapDp = gapDp,
            padding = padding,
            margin = margin,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp,
            verticalAlignment = verticalAlignment,
            itemWeights = itemWeights,
            items = items
        )
    }

    private fun parseFlowRow(obj: JsonObject): FlowRowElement {
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"] ?: obj["horizontalGap"])
        val verticalGapDp = parseDpOrNull(obj["verticalGap"] ?: obj["gap"] ?: obj["itemGap"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"])

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return FlowRowElement(
            items = items,
            gapDp = gapDp,
            verticalGapDp = verticalGapDp,
            padding = padding,
            margin = margin,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp
        )
    }

    private fun parseScrollRow(obj: JsonObject): ScrollRowElement {
        val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["height"])
        val maxHeightDp = parseDpOrNull(obj["maxHeight"])

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return ScrollRowElement(
            items = items,
            gapDp = gapDp,
            padding = padding,
            margin = margin,
            minHeightDp = minHeightDp,
            maxHeightDp = maxHeightDp
        )
    }

    private fun parseSpacer(obj: JsonObject): SpacerElement {
        val heightDp = parseDpOrNull(obj["height"] ?: obj["heightDp"] ?: obj["size"])
        val widthDp = parseDpOrNull(obj["width"] ?: obj["widthDp"])
        val weight = obj["weight"]?.primitiveDoubleOrNull()?.toFloat()
        return SpacerElement(heightDp = heightDp, widthDp = widthDp, weight = weight)
    }

    private fun parseImage(obj: JsonObject): ImageElement {
        val icon = obj["icon"]?.primitiveStringOrNull() ?: obj["name"]?.primitiveStringOrNull()
        val sizeDp = parseDpOrNull(obj["size"] ?: obj["sizeDp"]) ?: 16
        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        return ImageElement(
            icon = icon,
            sizeDp = sizeDp,
            color = color,
            padding = padding,
            margin = margin
        )
    }

    private fun parseTabSet(obj: JsonObject): TabSetElement {
        val tabs = mutableListOf<TabElement>()
        val tabsArray = obj["tabs"] as? JsonArray
        if (tabsArray != null) {
            for (item in tabsArray) {
                val tabObj = item as? JsonObject ?: continue
                tabs.add(parseTab(tabObj))
            }
        }
        val tabPosition = obj["tabPosition"]?.primitiveStringOrNull()
        val tabStyle = obj["tabStyle"]?.primitiveStringOrNull()
        val indicatorColor = obj["indicatorColor"]?.primitiveStringOrNull()?.let { CardColor.fromString(it) }
        val tabBackgroundColor = obj["tabBackgroundColor"]?.primitiveStringOrNull()?.let { CardColor.fromString(it) }
        val tabContentColor = obj["tabContentColor"]?.primitiveStringOrNull()?.let { CardColor.fromString(it) }
        val cornerRadiusDp = parseDpOrNull(obj["cornerRadius"] ?: obj["radius"])
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        return TabSetElement(
            tabs = tabs,
            tabPosition = tabPosition,
            tabStyle = tabStyle,
            indicatorColor = indicatorColor,
            tabBackgroundColor = tabBackgroundColor,
            tabContentColor = tabContentColor,
            cornerRadiusDp = cornerRadiusDp,
            padding = padding,
            margin = margin
        )
    }

    private fun parseTab(obj: JsonObject): TabElement {
        val label = obj["label"]?.primitiveStringOrNull() ?: obj["title"]?.primitiveStringOrNull() ?: ""
        val icon = obj["icon"]?.primitiveStringOrNull()
        val badge = obj["badge"]?.primitiveStringOrNull()
        val color = obj["color"]?.primitiveStringOrNull()?.let { CardColor.fromString(it) }

        val items = mutableListOf<AdaptiveCardElement>()
        val itemsArray = obj["items"] as? JsonArray ?: obj["elements"] as? JsonArray
        if (itemsArray != null) {
            for (item in itemsArray) {
                parseElement(item)?.let { items.add(it) }
            }
        }
        return TabElement(
            label = label,
            icon = icon,
            badge = badge,
            color = color,
            items = items
        )
    }

    private fun parseActionButton(obj: JsonObject): ActionButtonElement {
        val title = obj["title"]?.primitiveStringOrNull() ?: obj["text"]?.primitiveStringOrNull() ?: ""
        val actionType = obj["actionType"]?.primitiveStringOrNull() ?: obj["action"]?.primitiveStringOrNull() ?: "openUrl"
        val url = obj["url"]?.primitiveStringOrNull()
        val value = obj["value"]?.primitiveStringOrNull()
        val icon = obj["icon"]?.primitiveStringOrNull()
        val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        return ActionButtonElement(
            title = title,
            actionType = actionType,
            url = url,
            value = value,
            icon = icon,
            style = style,
            color = color,
            padding = padding,
            margin = margin
        )
    }

    private fun parseActionSubmit(obj: JsonObject): ActionSubmitElement {
        val id = obj["id"]?.primitiveStringOrNull() ?: "submit"
        val title = obj["title"]?.primitiveStringOrNull() ?: obj["label"]?.primitiveStringOrNull() ?: "运行"
        val commandTemplate = obj["commandTemplate"]?.primitiveStringOrNull() ?: obj["command"]?.primitiveStringOrNull()
        val terminalTitle = obj["terminalTitle"]?.primitiveStringOrNull() ?: title
        val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
        return ActionSubmitElement(
            id = id,
            title = title,
            commandTemplate = commandTemplate,
            terminalTitle = terminalTitle,
            style = style
        )
    }

    private fun parseTextBlock(obj: JsonObject): TextBlockElement {
        val text = (obj["text"] ?: obj["value"] ?: obj["label"])
            ?.primitiveStringOrNull().orEmpty()
        val size = TextSize.fromString(obj["size"]?.primitiveStringOrNull())
        val fontSizeSp = parseSpOrNull(
            obj["fontSize"] ?: obj["fontSizeSp"] ?: obj["font_size"]
                ?: obj["size"]?.takeIf {
                    val raw = it.primitiveStringOrNull()
                    raw != null && raw.any { ch -> ch.isDigit() }
                }
        )
        val lineHeightSp = parseSpOrNull(
            obj["lineHeight"] ?: obj["lineHeightSp"] ?: obj["line_height"]
        )
        val weight = TextWeight.fromString(obj["weight"]?.primitiveStringOrNull())
        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
        val isSubtle = obj["isSubtle"]?.primitiveBooleanOrNull() ?: false
        val maxLines = obj["maxLines"]?.primitiveIntOrNull()
        val verticalAlignment = (obj["verticalAlignment"] ?: obj["vertical_alignment"])
            ?.primitiveStringOrNull()
        val horizontalAlignment = (
            obj["horizontalAlignment"]
                ?: obj["horizontal_alignment"]
                ?: obj["align"]
            )?.primitiveStringOrNull()
        val padding = parsePadding(obj["padding"])
        val margin = parsePadding(obj["margin"])
        return TextBlockElement(
            text = text,
            size = size,
            fontSizeSp = fontSizeSp,
            lineHeightSp = lineHeightSp,
            weight = weight,
            color = color,
            isSubtle = isSubtle,
            maxLines = maxLines,
            verticalAlignment = verticalAlignment,
            horizontalAlignment = horizontalAlignment,
            padding = padding,
            margin = margin
        )
    }

    private fun parseProgressBar(obj: JsonObject): ProgressBarElement {
        var value = (obj["value"] ?: obj["percent"] ?: obj["progress"] ?: obj["percentage"])
            ?.primitiveDoubleOrNull()?.toFloat() ?: 0f
        if (value in 0.0001f..1.0f) value *= 100f
        value = value.coerceIn(0f, 100f)

        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
        val trackColor = obj["trackColor"]?.primitiveStringOrNull()
        val heightDp = parseDpOrNull(obj["height"] ?: obj["heightDp"]) ?: 6
        val animated = obj["animated"]?.primitiveBooleanOrNull() ?: true
        val cornerRadiusDp = parseDpOrNull(
            obj["cornerRadius"] ?: obj["corner_radius"] ?: obj["radius"]
        )
        val text = (obj["text"] ?: obj["label"])?.primitiveStringOrNull()
        val showPercent = obj["showPercent"]?.primitiveBooleanOrNull()
            ?: obj["show_percent"]?.primitiveBooleanOrNull() ?: false
        val textColor = CardColor.fromString(
            obj["textColor"]?.primitiveStringOrNull() ?: obj["text_color"]?.primitiveStringOrNull()
        )
        val margin = parsePadding(obj["margin"])
        return ProgressBarElement(
            value = value,
            color = color,
            trackColor = trackColor,
            heightDp = heightDp,
            animated = animated,
            cornerRadiusDp = cornerRadiusDp,
            text = text,
            showPercent = showPercent,
            textColor = if (textColor != CardColor.Default) textColor else null,
            margin = margin
        )
    }

    private fun parseMetric(obj: JsonObject): MetricElement {
        val label = obj["label"]?.primitiveStringOrNull() ?: ""
        val value = obj["value"]?.primitiveStringOrNull() ?: ""
        val unit = obj["unit"]?.primitiveStringOrNull() ?: ""
        val subText = obj["subText"]?.primitiveStringOrNull() ?: ""
        val percent = obj["percent"]?.primitiveDoubleOrNull()?.toFloat()
        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
        val trend = obj["trend"]?.primitiveStringOrNull()
        return MetricElement(
            label = label,
            value = value,
            unit = unit,
            subText = subText,
            percent = percent?.coerceIn(0f, 1f),
            color = color,
            trend = trend
        )
    }

    private fun parseBadge(obj: JsonObject): BadgeElement {
        val text = obj["text"]?.primitiveStringOrNull() ?: ""
        val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
        val icon = obj["icon"]?.primitiveStringOrNull()
        return BadgeElement(text = text, style = style, icon = icon)
    }

    private fun parseStatusDot(obj: JsonObject): StatusDotElement {
        val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull() ?: "good")
        val sizeDp = parseDpOrNull(obj["size"] ?: obj["sizeDp"]) ?: 6
        return StatusDotElement(color = color, sizeDp = sizeDp)
    }

    private fun parseFactSet(obj: JsonObject): FactSetElement {
        val facts = mutableListOf<FactItem>()
        val factsArray = obj["facts"] as? JsonArray
        if (factsArray != null) {
            for (fact in factsArray) {
                val factObj = fact as? JsonObject ?: continue
                val title = factObj["title"]?.primitiveStringOrNull() ?: ""
                val value = factObj["value"]?.primitiveStringOrNull() ?: ""
                facts.add(FactItem(title, value))
            }
        }
        return FactSetElement(facts = facts)
    }

    private fun parseDivider(obj: JsonObject): DividerElement {
        val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
        return DividerElement(spacing = spacing)
    }

    // ── 表单解析 ──

    private fun parseInputText(obj: JsonObject): InputTextElement {
        val id = obj["id"]?.primitiveStringOrNull() ?: obj["key"]?.primitiveStringOrNull() ?: "text"
        val label = obj["label"]?.primitiveStringOrNull() ?: ""
        val placeholder = obj["placeholder"]?.primitiveStringOrNull() ?: ""
        val defaultValue = obj["defaultValue"]?.primitiveStringOrNull() ?: obj["default"]?.primitiveStringOrNull() ?: ""
        val multiline = obj["isMultiline"]?.primitiveBooleanOrNull() ?: obj["multiline"]?.primitiveBooleanOrNull() ?: false
        return InputTextElement(
            id = id,
            label = label,
            placeholder = placeholder,
            defaultValue = defaultValue,
            multiline = multiline
        )
    }

    private fun parseInputSelect(obj: JsonObject): InputSelectElement {
        val id = obj["id"]?.primitiveStringOrNull() ?: obj["key"]?.primitiveStringOrNull() ?: "select"
        val label = obj["label"]?.primitiveStringOrNull() ?: ""
        val defaultValue = obj["defaultValue"]?.primitiveStringOrNull() ?: obj["default"]?.primitiveStringOrNull() ?: ""

        val choices = mutableListOf<InputChoiceItem>()
        val choicesArray = obj["choices"] as? JsonArray ?: obj["options"] as? JsonArray
        if (choicesArray != null) {
            for (item in choicesArray) {
                val cObj = item as? JsonObject ?: continue
                val title = cObj["title"]?.primitiveStringOrNull() ?: cObj["label"]?.primitiveStringOrNull() ?: ""
                val value = cObj["value"]?.primitiveStringOrNull() ?: title
                choices.add(InputChoiceItem(title = title, value = value))
            }
        }
        return InputSelectElement(
            id = id,
            label = label,
            choices = choices,
            defaultValue = defaultValue
        )
    }

    private fun parseInputToggle(obj: JsonObject): InputToggleElement {
        val id = obj["id"]?.primitiveStringOrNull() ?: obj["key"]?.primitiveStringOrNull() ?: "toggle"
        val label = obj["label"]?.primitiveStringOrNull() ?: ""
        val hint = obj["hint"]?.primitiveStringOrNull()
        val defaultValue = obj["defaultValue"]?.primitiveBooleanOrNull() ?: obj["default"]?.primitiveBooleanOrNull() ?: false
        return InputToggleElement(
            id = id,
            label = label,
            hint = hint,
            defaultValue = defaultValue
        )
    }

    private fun parseInputList(obj: JsonObject): InputListElement {
        val id = obj["id"]?.primitiveStringOrNull() ?: obj["key"]?.primitiveStringOrNull() ?: "list"
        val label = obj["label"]?.primitiveStringOrNull() ?: ""
        val placeholder = obj["placeholder"]?.primitiveStringOrNull() ?: ""
        val defaults = mutableListOf<String>()
        val defArray = obj["defaultValues"] as? JsonArray ?: obj["default"] as? JsonArray
        if (defArray != null) {
            for (elem in defArray) {
                elem.primitiveStringOrNull()?.let { defaults.add(it) }
            }
        }
        return InputListElement(
            id = id,
            label = label,
            placeholder = placeholder,
            defaultValues = defaults
        )
    }

    private fun parseCodeBlock(obj: JsonObject): CodeBlockElement {
        val code = obj["code"]?.primitiveStringOrNull() ?: obj["content"]?.primitiveStringOrNull() ?: ""
        val language = obj["language"]?.primitiveStringOrNull() ?: "bash"
        return CodeBlockElement(code = code, language = language)
    }

    private fun parseMarkdownBlock(obj: JsonObject): MarkdownBlockElement {
        val content = obj["content"]?.primitiveStringOrNull() ?: obj["text"]?.primitiveStringOrNull() ?: ""
        return MarkdownBlockElement(content = content)
    }

    // ── 辅助工具 ──

    private fun parsePadding(element: JsonElement?): CardPadding? {
        if (element == null) return null
        val single = parseDpOrNull(element)
        if (single != null) return CardPadding.all(single)
        if (element is JsonArray) {
            val list = element.mapNotNull { parseDpOrNull(it) }
            return when (list.size) {
                1 -> CardPadding.all(list[0])
                2 -> CardPadding.symmetric(vertical = list[0], horizontal = list[1])
                3 -> CardPadding(top = list[0], right = list[1], bottom = list[2], left = list[1])
                4 -> CardPadding(top = list[0], right = list[1], bottom = list[2], left = list[3])
                else -> null
            }
        }
        if (element is JsonObject) {
            val v = parseDpOrNull(element["vertical"] ?: element["v"])
            val h = parseDpOrNull(element["horizontal"] ?: element["h"])
            val top = parseDpOrNull(element["top"] ?: element["t"]) ?: v ?: 0
            val right = parseDpOrNull(element["right"] ?: element["r"]) ?: h ?: 0
            val bottom = parseDpOrNull(element["bottom"] ?: element["b"]) ?: v ?: 0
            val left = parseDpOrNull(element["left"] ?: element["l"]) ?: h ?: 0
            return CardPadding(top = top, right = right, bottom = bottom, left = left)
        }
        return null
    }

    private fun parseDpOrNull(element: JsonElement?): Int? {
        if (element == null) return null
        if (element is JsonPrimitive) {
            element.intOrNull?.let { return it }
            val str = element.content.trim().lowercase()
            if (str.endsWith("dp") || str.endsWith("px")) {
                return str.removeSuffix("dp").removeSuffix("px").trim().toIntOrNull()
            }
            return str.toIntOrNull()
        }
        return null
    }

    private fun JsonElement.primitiveStringOrNull(): String? = (this as? JsonPrimitive)?.content
    private fun JsonElement.primitiveDoubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement.primitiveBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.primitiveIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun parseSpOrNull(element: JsonElement?): Float? {
        if (element == null) return null
        element.primitiveDoubleOrNull()?.let { return it.toFloat() }
        element.primitiveIntOrNull()?.let { return it.toFloat() }
        val raw = element.primitiveStringOrNull()?.trim()?.lowercase() ?: return null
        return raw.removeSuffix("sp").removeSuffix("px").removeSuffix("pt").trim().toFloatOrNull()
    }

    private fun extractJsonSnippet(text: String): String? {
        val firstObj = text.indexOf('{')
        val firstArr = text.indexOf('[')
        val start = when {
            firstObj >= 0 && firstArr >= 0 -> minOf(firstObj, firstArr)
            firstObj >= 0 -> firstObj
            firstArr >= 0 -> firstArr
            else -> return null
        }
        val lastObj = text.lastIndexOf('}')
        val lastArr = text.lastIndexOf(']')
        val end = maxOf(lastObj, lastArr)
        if (end <= start) return null
        return text.substring(start, end + 1).trim()
    }

    /** 变量插值辅助函数 */
    fun interpolate(template: String, values: Map<String, Any>): String {
        var result = template
        values.forEach { (key, value) ->
            val replacement = when (value) {
                is Boolean -> if (value) "true" else "false"
                is List<*> -> value.filterNotNull().joinToString(" ") { it.toString() }
                else -> value.toString()
            }
            result = result.replace("\$$key", replacement)
            result = result.replace("\${$key}", replacement)
        }
        return result.trim()
    }
}
