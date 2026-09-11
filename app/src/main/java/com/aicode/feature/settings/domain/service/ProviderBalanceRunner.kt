package com.aicode.feature.settings.domain.service

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.AIProviderConfig
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
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.DividerElement
import com.aicode.feature.settings.domain.model.FactItem
import com.aicode.feature.settings.domain.model.FactSetElement
import com.aicode.feature.settings.domain.model.FlowRowElement
import com.aicode.feature.settings.domain.model.ImageElement
import com.aicode.feature.settings.domain.model.MetricElement
import com.aicode.feature.settings.domain.model.ProgressBarElement
import com.aicode.feature.settings.domain.model.ProviderBalanceResult
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
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderBalanceRunner @Inject constructor(
    private val commandEngine: CommandEngine,
    private val containerInstaller: ContainerInstaller,
    private val keyRotator: ProviderKeyRotator,
    private val workspaceRepository: WorkspaceRepository
) {
    companion object {
        private const val TAG = "ProviderBalanceRunner"
        const val DEFAULT_BALANCE_SCRIPT = "demo_balance.py"
        const val DEFAULT_SUBSCRIPTION_SCRIPT = "demo_subscription.py"
        private const val SCRIPT_TIMEOUT_MS = 15_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 解析标准 Adaptive Card JSON 输出为 [ProviderBalanceResult]。
         */
        fun parseBalanceJson(rawOutput: String): ProviderBalanceResult {
            if (rawOutput.isBlank()) {
                return ProviderBalanceResult(AdaptiveCardRoot(), rawOutput)
            }

            val jsonSnippet = extractJsonSnippet(rawOutput)
                ?: throw IllegalArgumentException("输出中未找到有效的 JSON 结构: $rawOutput")

            val parsedElement = json.parseToJsonElement(jsonSnippet)
            val card = parseCardRoot(parsedElement)
            return ProviderBalanceResult(card = card, rawOutput = rawOutput)
        }

        private fun parseCardRoot(element: JsonElement): AdaptiveCardRoot {
            if (element is JsonObject) {
                val version = element["version"]?.primitiveStringOrNull() ?: "1.5"
                val refreshInterval = element["refreshInterval"]?.primitiveIntOrNull()
                    ?: element["interval"]?.primitiveIntOrNull()

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
                    // 如果单对象不是 AdaptiveCard 根，直接解析为单个 element 放入 body
                    parseElement(element)?.let { bodyList.add(it) }
                }

                return AdaptiveCardRoot(
                    version = version,
                    refreshInterval = refreshInterval,
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

        fun parseElement(element: JsonElement): AdaptiveCardElement? {
            val obj = element as? JsonObject ?: return null
            val type = obj["type"]?.primitiveStringOrNull()?.trim()?.lowercase() ?: "textblock"

            // visible 字段过滤：false 时直接跳过不渲染
            val visible = obj["visible"]?.primitiveBooleanOrNull()
            if (visible == false) return null

            return when (type) {
                "columnset", "columns" -> parseColumnSet(obj)
                "column" -> parseColumn(obj)
                "container", "box", "card" -> parseContainer(obj)
                "row" -> parseRow(obj)
                "flowrow", "flow", "wrap" -> parseFlowRow(obj)
                "scrollrow", "scroll" -> parseScrollRow(obj)
                "spacer" -> parseSpacer(obj)
                "image", "img", "icon" -> parseImage(obj)
                "tabset", "tabs" -> parseTabSet(obj)
                "tab" -> parseTab(obj)
                "button", "actionbutton", "action" -> parseActionButton(obj)
                "textblock", "text" -> parseTextBlock(obj)
                "progressbar", "progress" -> parseProgressBar(obj)
                "metric", "stat", "statblock" -> parseMetric(obj)
                "badge", "tag" -> parseBadge(obj)
                "statusdot", "dot" -> parseStatusDot(obj)
                "factset", "facts", "table" -> parseFactSet(obj)
                "divider", "separator", "hr" -> parseDivider(obj)
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
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
            val horizontalAlignment = (obj["horizontalAlignment"] ?: obj["horizontal_alignment"] ?: obj["align"])?.primitiveStringOrNull()

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
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
            val minWidthDp = parseDpOrNull(obj["minWidth"] ?: obj["min_width"])
            val maxWidthDp = parseDpOrNull(obj["maxWidth"] ?: obj["max_width"])
            val verticalContentAlignment = (obj["verticalContentAlignment"] ?: obj["vertical_content_alignment"] ?: obj["verticalAlignment"])?.primitiveStringOrNull()
            val horizontalAlignment = (obj["horizontalAlignment"] ?: obj["horizontal_alignment"] ?: obj["align"])?.primitiveStringOrNull()

            val items = mutableListOf<AdaptiveCardElement>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
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
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
            val minWidthDp = parseDpOrNull(obj["minWidth"] ?: obj["min_width"])
            val maxWidthDp = parseDpOrNull(obj["maxWidth"] ?: obj["max_width"])
            val cornerRadiusDp = parseDpOrNull(obj["cornerRadius"] ?: obj["corner_radius"] ?: obj["radius"])

            val items = mutableListOf<AdaptiveCardElement>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
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

        private fun parseRow(obj: JsonObject): RowElement {
            val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
            val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
            val padding = parsePadding(obj["padding"])
            val margin = parsePadding(obj["margin"])
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])
            val verticalAlignment = (obj["verticalAlignment"] ?: obj["vertical_alignment"] ?: obj["align"])?.primitiveStringOrNull()

            val items = mutableListOf<AdaptiveCardElement>()
            val itemWeights = mutableListOf<Float?>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
            if (itemsArray != null) {
                for (item in itemsArray) {
                    val itemObj = item as? JsonObject
                    val weight = itemObj?.get("weight")?.primitiveDoubleOrNull()?.toFloat()
                    itemWeights.add(weight)
                    parseElement(item)?.let { items.add(it) }
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

        private fun parseTextBlock(obj: JsonObject): TextBlockElement {
            val text = (obj["text"] ?: obj["value"] ?: obj["label"])?.primitiveStringOrNull().orEmpty()
            val sizeStr = obj["size"]?.primitiveStringOrNull()
            val size = TextSize.fromString(sizeStr)
            val fontSizeSp = parseSpOrNull(obj["fontSize"] ?: obj["font_size"] ?: if (sizeStr != null && sizeStr.any { it.isDigit() }) obj["size"] else null)
            val lineHeightSp = parseSpOrNull(obj["lineHeight"] ?: obj["line_height"])
            val weight = TextWeight.fromString(obj["weight"]?.primitiveStringOrNull())
            val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
            val isSubtle = obj["isSubtle"]?.primitiveBooleanOrNull() ?: false
            val maxLines = obj["maxLines"]?.primitiveIntOrNull()
            val verticalAlignment = (obj["verticalAlignment"] ?: obj["vertical_alignment"])?.primitiveStringOrNull()
            val horizontalAlignment = (obj["horizontalAlignment"] ?: obj["horizontal_alignment"] ?: obj["align"])?.primitiveStringOrNull()
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
            var value = (obj["value"] ?: obj["percent"] ?: obj["progress"])?.primitiveDoubleOrNull()?.toFloat() ?: 0f
            if (value in 0.0001f..1.0f) {
                value *= 100f
            }
            value = value.coerceIn(0f, 100f)

            val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
            val trackColor = obj["trackColor"]?.primitiveStringOrNull()
            val height = parseDpOrNull(obj["height"] ?: obj["heightDp"]) ?: 6
            val animated = obj["animated"]?.primitiveBooleanOrNull() ?: true
            val cornerRadiusDp = parseDpOrNull(obj["cornerRadius"] ?: obj["corner_radius"] ?: obj["radius"])
            val text = (obj["text"] ?: obj["label"])?.primitiveStringOrNull()
            val showPercent = obj["showPercent"]?.primitiveBooleanOrNull() ?: obj["show_percent"]?.primitiveBooleanOrNull() ?: false
            val textColor = CardColor.fromString(obj["textColor"]?.primitiveStringOrNull() ?: obj["text_color"]?.primitiveStringOrNull())
            val margin = parsePadding(obj["margin"])

            return ProgressBarElement(
                value = value,
                color = color,
                trackColor = trackColor,
                heightDp = height,
                animated = animated,
                cornerRadiusDp = cornerRadiusDp,
                text = text,
                showPercent = showPercent,
                textColor = if (textColor != CardColor.Default) textColor else null,
                margin = margin
            )
        }

        private fun parseMetric(obj: JsonObject): MetricElement {
            val label = (obj["label"] ?: obj["title"] ?: obj["name"])?.primitiveStringOrNull().orEmpty()
            val value = (obj["value"] ?: obj["amount"] ?: obj["text"] ?: obj["balance"])?.primitiveStringOrNull().orEmpty()
            val unit = obj["unit"]?.primitiveStringOrNull().orEmpty()
            val subText = (obj["subText"] ?: obj["sub_text"] ?: obj["info"] ?: obj["description"] ?: obj["detail"])?.primitiveStringOrNull().orEmpty()

            var percent = (obj["percent"] ?: obj["percentage"] ?: obj["progress"])?.primitiveDoubleOrNull()?.toFloat()
            if (percent != null && percent in 0.0001f..1.0f) {
                percent *= 100f
            }
            percent = percent?.coerceIn(0f, 100f)

            val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
            val trend = obj["trend"]?.primitiveStringOrNull()

            return MetricElement(
                label = label,
                value = value,
                unit = unit,
                subText = subText,
                percent = percent,
                color = color,
                trend = trend
            )
        }

        private fun parseBadge(obj: JsonObject): BadgeElement {
            val text = (obj["text"] ?: obj["label"] ?: obj["value"])?.primitiveStringOrNull().orEmpty()
            val style = ContainerStyle.fromString(obj["style"]?.primitiveStringOrNull())
            val icon = obj["icon"]?.primitiveStringOrNull()
            return BadgeElement(text = text, style = style, icon = icon)
        }

        private fun parseStatusDot(obj: JsonObject): StatusDotElement {
            val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
            val sizeDp = parseDpOrNull(obj["size"] ?: obj["sizeDp"]) ?: 6
            return StatusDotElement(color = color, sizeDp = sizeDp)
        }

        private fun parseFactSet(obj: JsonObject): FactSetElement {
            val facts = mutableListOf<FactItem>()
            val factsArray = obj["facts"] as? JsonArray ?: obj["items"] as? JsonArray
            if (factsArray != null) {
                for (item in factsArray) {
                    val fObj = item as? JsonObject ?: continue
                    val title = (fObj["title"] ?: fObj["label"] ?: fObj["key"])?.primitiveStringOrNull().orEmpty()
                    val value = (fObj["value"] ?: fObj["val"] ?: fObj["content"])?.primitiveStringOrNull().orEmpty()
                    if (title.isNotBlank() || value.isNotBlank()) {
                        facts.add(FactItem(title = title, value = value))
                    }
                }
            }
            return FactSetElement(facts = facts)
        }

        private fun parseDivider(obj: JsonObject): DividerElement {
            val spacing = SpacingSize.fromString(obj["spacing"]?.primitiveStringOrNull())
            return DividerElement(spacing = spacing)
        }

        private fun parseSpacer(obj: JsonObject): SpacerElement {
            val heightDp = parseDpOrNull(obj["height"] ?: obj["heightDp"])
            val widthDp = parseDpOrNull(obj["width"] ?: obj["widthDp"])
            val weight = obj["weight"]?.primitiveDoubleOrNull()?.toFloat()
            return SpacerElement(heightDp = heightDp, widthDp = widthDp, weight = weight)
        }

        private fun parseFlowRow(obj: JsonObject): FlowRowElement {
            val gapDp = parseDpOrNull(obj["gap"] ?: obj["itemGap"])
            val verticalGapDp = parseDpOrNull(obj["verticalGap"] ?: obj["vertical_gap"])
            val padding = parsePadding(obj["padding"])
            val margin = parsePadding(obj["margin"])
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])

            val items = mutableListOf<AdaptiveCardElement>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
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
            val minHeightDp = parseDpOrNull(obj["minHeight"] ?: obj["min_height"])
            val maxHeightDp = parseDpOrNull(obj["maxHeight"] ?: obj["max_height"])

            val items = mutableListOf<AdaptiveCardElement>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
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

        private fun parseImage(obj: JsonObject): ImageElement {
            val icon = (obj["icon"] ?: obj["name"])?.primitiveStringOrNull()
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
            val padding = parsePadding(obj["padding"])
            val margin = parsePadding(obj["margin"])
            val tabPosition = obj["tabPosition"]?.primitiveStringOrNull()
            val tabStyle = obj["tabStyle"]?.primitiveStringOrNull()
            val indicatorColor = CardColor.fromString(obj["indicatorColor"]?.primitiveStringOrNull())
                .takeIf { it != CardColor.Default }
            val tabBackgroundColor = CardColor.fromString(obj["tabBackgroundColor"]?.primitiveStringOrNull())
                .takeIf { it != CardColor.Default }
            val tabContentColor = CardColor.fromString(obj["tabContentColor"]?.primitiveStringOrNull())
                .takeIf { it != CardColor.Default }
            val cornerRadiusDp = parseDpOrNull(obj["cornerRadius"] ?: obj["corner_radius"])
            val tabs = mutableListOf<TabElement>()
            val tabsArray = obj["tabs"] as? JsonArray
            if (tabsArray != null) {
                for (tab in tabsArray) {
                    val tabObj = tab as? JsonObject ?: continue
                    parseTab(tabObj)?.let { tabs.add(it) }
                }
            }
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

        private fun parseTab(obj: JsonObject): TabElement? {
            val label = (obj["label"] ?: obj["title"] ?: obj["name"])?.primitiveStringOrNull().orEmpty()
            if (label.isBlank()) return null
            val icon = obj["icon"]?.primitiveStringOrNull()
            val badge = obj["badge"]?.primitiveStringOrNull()
            val color = CardColor.fromString(obj["color"]?.primitiveStringOrNull())
                .takeIf { it != CardColor.Default }
            val items = mutableListOf<AdaptiveCardElement>()
            val itemsArray = obj["items"] as? JsonArray ?: obj["body"] as? JsonArray
            if (itemsArray != null) {
                for (item in itemsArray) {
                    parseElement(item)?.let { items.add(it) }
                }
            }
            return TabElement(label = label, icon = icon, badge = badge, color = color, items = items)
        }

        private fun parseActionButton(obj: JsonObject): ActionButtonElement? {
            val title = (obj["title"] ?: obj["label"])?.primitiveStringOrNull().orEmpty()
            if (title.isBlank()) return null
            val type = obj["type"]?.primitiveStringOrNull()?.trim()?.lowercase() ?: "button"
            val actionType = when {
                type.contains("openurl") || type == "url" || type == "link" -> "openUrl"
                type.contains("copy") -> "copy"
                type.contains("refresh") -> "refresh"
                else -> {
                    val action = obj["action"]?.primitiveStringOrNull()?.trim()?.lowercase()
                    when {
                        action == "copy" || action == "copyToClipboard" -> "copy"
                        action == "refresh" || action == "reload" -> "refresh"
                        else -> "openUrl"
                    }
                }
            }
            val url = obj["url"]?.primitiveStringOrNull()
            val value = (obj["value"] ?: obj["text"])?.primitiveStringOrNull()
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

        private fun parseDpOrNull(element: JsonElement?): Int? {
            if (element == null) return null
            val num = element.primitiveIntOrNull() ?: element.primitiveDoubleOrNull()?.toInt()
            if (num != null) return num
            val str = element.primitiveStringOrNull()?.trim()?.lowercase() ?: return null
            val clean = str.removeSuffix("dp").removeSuffix("px").trim()
            return clean.toIntOrNull() ?: clean.toDoubleOrNull()?.toInt()
        }

        private fun parseSpOrNull(element: JsonElement?): Float? {
            if (element == null) return null
            val num = element.primitiveDoubleOrNull()?.toFloat() ?: element.primitiveIntOrNull()?.toFloat()
            if (num != null) return num
            val str = element.primitiveStringOrNull()?.trim()?.lowercase() ?: return null
            val clean = str.removeSuffix("sp").removeSuffix("px").removeSuffix("pt").trim()
            return clean.toFloatOrNull()
        }

        private fun parsePadding(element: JsonElement?): CardPadding? {
            if (element == null) return null
            val single = parseDpOrNull(element)
            if (single != null) {
                return CardPadding.all(single)
            }
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

        private fun JsonElement.primitiveStringOrNull(): String? {
            return (this as? JsonPrimitive)?.content
        }

        private fun JsonElement.primitiveDoubleOrNull(): Double? {
            return (this as? JsonPrimitive)?.doubleOrNull
        }

        private fun JsonElement.primitiveBooleanOrNull(): Boolean? {
            return (this as? JsonPrimitive)?.booleanOrNull
        }

        private fun JsonElement.primitiveIntOrNull(): Int? {
            return (this as? JsonPrimitive)?.intOrNull
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
    }

    /**
     * 获取 ~/.aicode/scripts 目录下的所有可用脚本文件名列表。
     */
    fun listAvailableScripts(): List<String> {
        val scriptsDir = File(containerInstaller.aicodeDir, "scripts")
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
        }
        return scriptsDir.listFiles { file ->
            file.isFile && !file.name.startsWith(".")
        }?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * 执行提供商的套餐余量脚本并解析返回结果。
     */
    suspend fun runScript(
        provider: AIProviderConfig,
        scriptPathOverride: String? = null,
        context: DashboardContext? = null
    ): Result<ProviderBalanceResult> = runCatching {
        val rawPath = (scriptPathOverride ?: provider.balanceScriptPath).trim()
        if (rawPath.isBlank()) {
            return@runCatching ProviderBalanceResult()
        }

        // 解析容器内路径
        val targetPath = resolveContainerScriptPath(rawPath)

        // 构造环境变量与执行命令
        val envPrefix = buildEnvPrefix(provider, context)
        val execCmd = buildExecCommand(targetPath)
        val fullCommand = "$envPrefix $execCmd"

        FileLogger.i(TAG, "执行面板脚本 provider=${provider.name} targetPath=$targetPath model=${context?.model}")
        // 必须传工作区路径：proot 靠它把当前工作区 bind 到容器内 /root/workspace，
        // 否则脚本里访问工作区文件（如 .aicode/progress.json）会落到 rootfs 的空目录。
        val result = commandEngine.runCommandSyncUnbounded(
            fullCommand,
            projectPath = workspaceRepository.currentPath(),
            timeoutMs = SCRIPT_TIMEOUT_MS
        )
        val output = result.output.trim()

        if (result.exitCode != null && result.exitCode != 0) {
            FileLogger.w(TAG, "套餐余量脚本执行非零退出 code=${result.exitCode} output=$output")
            throw IllegalStateException("脚本退出码: ${result.exitCode}\n$output")
        }

        parseBalanceJson(output)
    }

    private fun resolveContainerScriptPath(path: String): String {
        return when {
            path.startsWith("/") -> path
            path.startsWith("~/") -> path.replaceFirst("~", "/root")
            path.startsWith(".aicode/scripts/") -> "/root/$path"
            path.startsWith("scripts/") -> "/root/.aicode/$path"
            else -> "/root/.aicode/scripts/$path"
        }
    }

    private fun buildEnvPrefix(provider: AIProviderConfig, context: DashboardContext? = null): String {
        fun escape(value: String): String {
            return "'" + value.replace("'", "'\\''") + "'"
        }
        val effectiveModel = context?.model?.ifBlank { null }
            ?: provider.selectedModel.ifBlank { provider.defaultModel }
        // 多 Key 模式下查当前活动的 Key，否则面板会报一个已被切走的 Key 的余额。
        val effectiveApiKey = keyRotator.currentKey(provider) ?: provider.apiKey

        val envs = mutableListOf(
            "AICODE_PROVIDER_ID=${escape(provider.id)}",
            "AICODE_PROVIDER_NAME=${escape(provider.name)}",
            "AICODE_PROVIDER_TYPE=${escape(provider.type.name)}",
            "AICODE_PROVIDER_API_KEY=${escape(effectiveApiKey)}",
            "AICODE_PROVIDER_BASE_URL=${escape(provider.baseUrl)}",
            "AICODE_PROVIDER_DEFAULT_MODEL=${escape(provider.defaultModel)}",
            "AICODE_PROVIDER_SELECTED_MODEL=${escape(provider.selectedModel)}",
            "AICODE_MODEL=${escape(effectiveModel)}"
        )

        if (context != null) {
            envs.add("AICODE_WORKSPACE=${escape(context.workspacePath)}")
            envs.add("AICODE_WORKSPACE_NAME=${escape(context.workspaceName)}")
            envs.add("AICODE_SESSION_ID=${escape(context.sessionId)}")
            envs.add("AICODE_LAST_INPUT_TOKENS=${context.lastInputTokens}")
            envs.add("AICODE_LAST_OUTPUT_TOKENS=${context.lastOutputTokens}")
            envs.add("AICODE_LAST_CACHED_TOKENS=${context.lastCachedTokens}")
            envs.add("AICODE_TOTAL_INPUT_TOKENS=${context.totalInputTokens}")
            envs.add("AICODE_TOTAL_OUTPUT_TOKENS=${context.totalOutputTokens}")
            envs.add("AICODE_MODEL_CONTEXT_TOKENS=${context.modelContextTokens}")
            envs.add("AICODE_MODEL_MAX_INPUT_TOKENS=${context.modelMaxInputTokens}")
            envs.add("AICODE_MODEL_MAX_OUTPUT_TOKENS=${context.modelMaxOutputTokens}")
            envs.add("AICODE_MODEL_INPUT_COST_USD_PER_M=${context.modelInputCostUsdPerM}")
            envs.add("AICODE_MODEL_OUTPUT_COST_USD_PER_M=${context.modelOutputCostUsdPerM}")
            envs.add("AICODE_MODEL_CACHE_READ_COST_USD_PER_M=${context.modelCacheReadCostUsdPerM}")
            envs.add("AICODE_MODEL_SUPPORTS_TOOLS=${context.modelSupportsTools}")
            envs.add("AICODE_MODEL_SUPPORTS_VISION=${context.modelSupportsVision}")
            envs.add("AICODE_MODEL_SUPPORTS_REASONING=${context.modelSupportsReasoning}")
            envs.add("AICODE_MESSAGE_COUNT=${context.messageCount}")
            envs.add("AICODE_AGENT_STATE=${escape(context.agentState)}")
            envs.add("AICODE_SESSION_MODE=${escape(context.sessionMode)}")
            envs.add("AICODE_REASONING_EFFORT=${escape(context.reasoningEffort)}")
            envs.add("AICODE_REFRESH_REASON=${escape(context.refreshReason)}")
        }

        return envs.joinToString(" ")
    }

    private fun buildExecCommand(targetPath: String): String {
        val lower = targetPath.lowercase()
        return when {
            lower.endsWith(".py") -> "python3 \"$targetPath\""
            lower.endsWith(".js") -> "node \"$targetPath\""
            lower.endsWith(".sh") || lower.endsWith(".bash") -> "bash \"$targetPath\""
            else -> "if [ -x \"$targetPath\" ]; then \"$targetPath\"; else bash \"$targetPath\"; fi"
        }
    }
}
