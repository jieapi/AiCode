package com.aicode.feature.agent.domain.tool.browser

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import android.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class BrowserTool @Inject constructor(
    private val browserManager: BrowserManager,
    private val fileAccess: FileAccessProvider,
    private val browserSettingsRepository: com.aicode.feature.settings.data.repository.BrowserSettingsRepository
) : AgentTool() {

    private companion object {
        const val TAG = "BrowserTool"
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        const val DEFAULT_BACKBONE_DEPTH = 15
        const val DEFAULT_SCREENSHOT_DIR = "~/workspace/.aicode/browser-screenshots"
        val READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")
    }

    override val name = "browser"
    override val description = "控制内置浏览器执行自动化操作，支持多标签与后台运行。所有操作可带 tabId（缺省作用于当前激活标签）。" +
        "selector 支持 ref= / text= / text*= / role=button[name=xxx] / xpath= / CSS；getBackbone 返回无障碍树（role/name/ref），ref 可直接用作 selector。" +
        "需查看页面视觉内容时显式调用 action=\"screenshot\"。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)

    private val actionEnum = listOf(
        "navigate", "evaluate", "click", "fill", "select", "hover", "press",
        "getText", "getHtml", "getBackbone", "screenshot", "console",
        "wait", "scroll", "dialog", "back", "forward", "reload",
        "newTab", "closeTab", "selectTab", "listTabs"
    )

    private val actionSchema: Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to actionEnum,
        "description" to "操作类型。navigate=导航URL(支持 http(s)/file:// 与容器路径，非网址输入按搜索关键词处理); evaluate=执行JS(支持 Promise); click/fill/hover/press=交互(兼容 React); select=下拉选择; getText/getHtml=提取内容; getBackbone=无障碍树(role/name/ref); screenshot=截图; console=控制台日志; wait=等待条件; scroll=滚动; dialog=处理confirm/prompt; back/forward/reload=导航控制; newTab/closeTab/selectTab/listTabs=标签页管理"
    )

    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作类型，见 enum列表", true),
        "tabId" to ToolParameter("tabId", ParameterType.STRING, "目标标签页 ID（如 tab-1），缺省时作用于当前激活的标签页", false),
        "path" to ToolParameter("path", ParameterType.STRING, "screenshot: 截图保存路径（可选，缺省默认保存在 ~/workspace/.aicode/browser-screenshots/）", false),
        "url" to ToolParameter("url", ParameterType.STRING, "navigate/newTab: URL。支持 http(s)；本地文件支持 file:// 或容器路径（~/workspace/…、/etc/…）；无协议头时优先 https，失败回退 http；不像网址的输入则用内置搜索引擎（Bing）搜索", false),
        "script" to ToolParameter("script", ParameterType.STRING, "evaluate: JS 代码（支持 Promise/async）", false),
        "selector" to ToolParameter("selector", ParameterType.STRING,
            "click/fill/hover/getText/getHtml/scroll/wait: 选择器。支持 ref=e22（getBackbone 返回的引用）/ text=登录 / text*=登录 / role=button[name=\"登录\"] / xpath=//a / CSS", false),
        "value" to ToolParameter("value", ParameterType.STRING, "fill/select: 要填入的值或要选中的 option（value 或可见文本）", false),
        "accept" to ToolParameter("accept", ParameterType.BOOLEAN, "dialog: 是否接受对话框（true=确定，false=取消），默认 true", false),
        "text" to ToolParameter("text", ParameterType.STRING, "dialog: prompt 输入内容（accept=true 时生效）", false),
        "level" to ToolParameter("level", ParameterType.STRING, "console: 日志级别过滤（log/warning/error/debug）", false),
        "clear" to ToolParameter("clear", ParameterType.BOOLEAN, "console: 取完后是否清空日志，默认 false", false),
        "key" to ToolParameter("key", ParameterType.STRING,
            "press: 键名（Enter/Escape/Tab/ArrowUp/ArrowDown/ArrowLeft/ArrowRight/Backspace/Delete/空格/普通字符）", false),
        "condition" to ToolParameter("condition", ParameterType.STRING,
            "wait: 等待条件。text=登录(精确匹配) / text*=登录(包含匹配) / selector=#result(CSS存在) / domStable(DOM稳定)", false),
        "timeout" to ToolParameter("timeout", ParameterType.INTEGER, "wait: 超时毫秒，默认 10000", false),
        "maxDepth" to ToolParameter("maxDepth", ParameterType.INTEGER, "getBackbone: 无障碍树最大深度，默认 15", false)
    )

    override fun toJsonSchema(): Map<String, Any> {
        val intKeys = setOf("timeout", "maxDepth")
        val boolKeys = setOf("accept", "clear")
        val properties = LinkedHashMap<String, Any>()
        properties["action"] = actionSchema
        for ((key, param) in parameters) {
            if (key == "action") continue
            properties[key] = mapOf(
                "type" to when {
                    key in intKeys -> "integer"
                    key in boolKeys -> "boolean"
                    else -> "string"
                },
                "description" to param.description
            )
        }
        return mapOf("type" to "object", "properties" to properties, "required" to listOf("action"))
    }

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
        return if (action in READ_ONLY_ACTIONS) setOf(ToolCapability.NETWORK_READ)
        else setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")
        val tabId = args["tabId"]?.jsonPrimitive?.contentOrNull?.trim()

        // AI 控制浏览器权限：用户在设置页逐 action 关闭的项，执行前直接拒绝。
        if (!browserSettingsRepository.isActionEnabled(action)) {
            return ToolResult.Error(
                "浏览器操作「$action」已被用户在设置中禁用",
                "ACTION_DISABLED_BY_USER"
            )
        }

        return try {
            executeAction(action, tabId, args)
        } catch (e: IllegalStateException) {
            FileLogger.e(TAG, "浏览器操作失败: $action", e)
            ToolResult.Error(e.message ?: "浏览器未初始化", "BROWSER_NOT_READY")
        } catch (e: IllegalArgumentException) {
            ToolResult.Error(e.message ?: "参数错误", "INVALID_ARGUMENT")
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("操作超时：$action", "TIMEOUT")
        } catch (e: Exception) {
            FileLogger.e(TAG, "浏览器操作异常: $action", e)
            ToolResult.Error("操作失败：${e.message}", "BROWSER_ERROR")
        }
    }

    private suspend fun executeAction(action: String, tabId: String?, args: Map<String, JsonElement>): ToolResult {
        return when (action) {
            "newTab" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                FileLogger.i(TAG, "newTab: url=$url")
                val newId = browserManager.newTab(url)
                buildOk(action, mapOf(
                    "tabId" to JsonPrimitive(newId),
                    "url" to JsonPrimitive(browserManager.getUrl(newId)),
                    "title" to JsonPrimitive(browserManager.getTitle(newId))
                ), tabId = newId)
            }

            "closeTab" -> {
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                FileLogger.i(TAG, "closeTab: targetTabId=$targetTabId")
                val closed = browserManager.closeTab(targetTabId)
                val remainingTabs = browserManager.listTabs().map { JsonPrimitive(it.id) }
                buildOk(action, mapOf(
                    "closedTabId" to JsonPrimitive(targetTabId),
                    "success" to JsonPrimitive(closed),
                    "activeTabId" to JsonPrimitive(browserManager.getActiveTabId()),
                    "tabs" to JsonArray(remainingTabs)
                ))
            }

            "selectTab" -> {
                val targetTabId = tabId
                    ?: return ToolResult.Error("selectTab 缺少必需参数：tabId", "MISSING_TAB_ID")
                FileLogger.i(TAG, "selectTab: targetTabId=$targetTabId")
                val selected = browserManager.selectTab(targetTabId)
                buildOk(action, mapOf(
                    "activeTabId" to JsonPrimitive(targetTabId),
                    "success" to JsonPrimitive(selected),
                    "url" to JsonPrimitive(browserManager.getUrl(targetTabId)),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            "listTabs" -> {
                FileLogger.i(TAG, "listTabs")
                val tabs = browserManager.listTabs().map { tab ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(tab.id),
                        "url" to JsonPrimitive(tab.url),
                        "title" to JsonPrimitive(tab.title),
                        "loading" to JsonPrimitive(tab.loading),
                        "active" to JsonPrimitive(tab.id == browserManager.getActiveTabId())
                    ))
                }
                buildOk(action, mapOf(
                    "tabs" to JsonArray(tabs),
                    "activeTabId" to JsonPrimitive(browserManager.getActiveTabId())
                ))
            }

            "navigate" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("navigate 缺少必需参数：url", "MISSING_URL")
                FileLogger.i(TAG, "navigate: $url (tabId=$tabId)")
                val finalUrl = browserManager.navigate(url, tabId)
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                buildOk(action, mapOf(
                    "requestedUrl" to JsonPrimitive(url),
                    "finalUrl" to JsonPrimitive(finalUrl),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            "evaluate" -> {
                val script = args["script"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("evaluate 缺少必需参数：script", "MISSING_SCRIPT")
                FileLogger.i(TAG, "evaluate: ${script.take(100)} (tabId=$tabId)")
                val result = browserManager.evaluateJavaScript(script, tabId)
                val value = browserManager.parseEvalResult(result)
                buildOk(action, mapOf("value" to value), tabId = tabId)
            }

            "click" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("click 缺少必需参数：selector", "MISSING_SELECTOR")
                FileLogger.i(TAG, "click: $selector (tabId=$tabId)")
                val json = browserManager.clickElement(selector, tabId)
                buildFromJson(action, json, tabId)
            }

            "fill" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill 缺少必需参数：selector", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill 缺少必需参数：value", "MISSING_VALUE")
                FileLogger.i(TAG, "fill: $selector (tabId=$tabId)")
                val json = browserManager.fillElement(selector, value, tabId)
                buildFromJson(action, json, tabId)
            }

            "hover" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("hover 缺少必需参数：selector", "MISSING_SELECTOR")
                FileLogger.i(TAG, "hover: $selector (tabId=$tabId)")
                val json = browserManager.hoverElement(selector, tabId)
                buildFromJson(action, json, tabId)
            }

            "press" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("press 缺少必需参数：key", "MISSING_KEY")
                FileLogger.i(TAG, "press: $key (tabId=$tabId)")
                browserManager.pressKey(key, tabId)
                buildOk(action, mapOf("key" to JsonPrimitive(key)), tabId = tabId)
            }

            "getText" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val text = browserManager.getText(selector, tabId)
                val truncated = text.take(50_000)
                val resultText = if (text.length > 50_000) "$truncated\n\n[内容超长，已截断]" else truncated
                buildOk(action, mapOf(
                    "text" to JsonPrimitive(resultText),
                    "selector" to JsonPrimitive(selector ?: "body")
                ), tabId = tabId)
            }

            "getHtml" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val html = browserManager.getHtml(selector, tabId)
                buildOk(action, mapOf(
                    "html" to JsonPrimitive(html),
                    "selector" to JsonPrimitive(selector ?: "document")
                ), tabId = tabId)
            }

            "getBackbone" -> {
                val maxDepth = args["maxDepth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_BACKBONE_DEPTH
                FileLogger.i(TAG, "getBackbone: depth=$maxDepth (tabId=$tabId)")
                val tree = browserManager.getBackbone(maxDepth, tabId)
                buildOk(action, mapOf(
                    "tree" to browserManager.parseEvalResult(tree),
                    "maxDepth" to JsonPrimitive(maxDepth)
                ), tabId = tabId)
            }

            "screenshot" -> {
                FileLogger.i(TAG, "screenshot (tabId=$tabId)")
                val image = browserManager.screenshot(tabId)
                if (image != null) {
                    val resolvedTabId = tabId ?: browserManager.getActiveTabId()
                    val timestamp = System.currentTimeMillis()
                    val customPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
                    val savePath = if (!customPath.isNullOrEmpty()) customPath else "$DEFAULT_SCREENSHOT_DIR/screenshot_${timestamp}_$resolvedTabId.jpg"

                    try {
                        val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                        fileAccess.writeBytes(savePath, bytes, overwrite = true)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "保存截图到文件失败: $savePath", e)
                    }

                    val viewport = browserManager.getViewportInfo(tabId)
                    val data = buildData(action, mapOf(
                        "status" to JsonPrimitive("captured"),
                        "filePath" to JsonPrimitive(savePath),
                        "viewport" to (browserManager.parseEvalResult(viewport))
                    ), tabId = tabId)
                    ToolResult.Success(data, images = listOf(image.copy(path = savePath)))
                } else {
                    buildError(action, "截图失败：浏览器尺寸为 0 或渲染异常。", "SCREENSHOT_FAILED", tabId = tabId)
                }
            }

            "wait" -> {
                val condition = args["condition"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("wait 缺少必需参数：condition", "MISSING_CONDITION")
                val timeout = args["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_WAIT_TIMEOUT_MS
                FileLogger.i(TAG, "wait: $condition, timeout=$timeout (tabId=$tabId)")
                val met = try {
                    browserManager.wait(condition, timeout, tabId)
                } catch (e: TimeoutCancellationException) { false }
                buildOk(action, mapOf(
                    "condition" to JsonPrimitive(condition),
                    "met" to JsonPrimitive(met),
                    "message" to JsonPrimitive(if (met) "条件已满足" else "超时未满足")
                ), tabId = tabId)
            }

            "select" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select 缺少必需参数：selector", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select 缺少必需参数：value", "MISSING_VALUE")
                FileLogger.i(TAG, "select: $selector -> $value (tabId=$tabId)")
                val json = browserManager.selectOption(selector, value, tabId)
                buildFromJson(action, json, tabId)
            }

            "dialog" -> {
                val accept = args["accept"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                FileLogger.i(TAG, "dialog: accept=$accept (tabId=$tabId)")
                val detail = browserManager.parseEvalResult(browserManager.resolveDialog(accept, text, tabId))
                buildOk(action, mapOf("result" to detail), tabId = tabId)
            }

            "console" -> {
                val level = args["level"]?.jsonPrimitive?.contentOrNull
                val clear = args["clear"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                FileLogger.i(TAG, "console: level=$level, clear=$clear (tabId=$tabId)")
                val json = browserManager.getConsoleLogs(level, clear, tabId)
                buildOk(action, mapOf("logs" to browserManager.parseEvalResult(json)), tabId = tabId)
            }

            "scroll" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                FileLogger.i(TAG, "scroll: selector=$selector, x=$x, y=$y (tabId=$tabId)")
                val json = browserManager.scroll(selector, x, y, tabId)
                buildFromJson(action, json, tabId)
            }

            "back" -> {
                val success = browserManager.goBack(tabId)
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "已后退" else "无法后退：无历史记录或导航未生效")
                ), tabId = tabId)
            }

            "forward" -> {
                val success = browserManager.goForward(tabId)
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "已前进" else "无法前进：无历史记录或导航未生效")
                ), tabId = tabId)
            }

            "reload" -> {
                val url = browserManager.reload(tabId)
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                buildOk(action, mapOf(
                    "url" to JsonPrimitive(url),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            else -> ToolResult.Error("未知的 action：$action", "UNKNOWN_ACTION")
        }
    }

    /** 统一响应封装；存在挂起的 confirm/prompt 时附加 pendingDialog 提示 AI 处理。 */
    private fun envelope(ok: Boolean, action: String, detail: JsonElement, tabId: String? = null): JsonObject {
        val resolvedTabId = tabId ?: browserManager.getActiveTabId()
        val fields = LinkedHashMap<String, JsonElement>()
        fields["ok"] = JsonPrimitive(ok)
        fields["action"] = JsonPrimitive(action)
        fields["tabId"] = JsonPrimitive(resolvedTabId)
        fields["url"] = JsonPrimitive(browserManager.getUrl(resolvedTabId))
        fields["title"] = JsonPrimitive(browserManager.getTitle(resolvedTabId))
        fields["detail"] = detail
        fields["error"] = JsonNull
        browserManager.pendingDialogInfo(resolvedTabId)?.let { d ->
            fields["pendingDialog"] = JsonObject(mapOf(
                "type" to JsonPrimitive(d.type),
                "message" to JsonPrimitive(d.message)
            ))
        }
        return JsonObject(fields)
    }

    private fun buildResult(ok: Boolean, action: String, detail: Map<String, JsonElement>, tabId: String? = null): ToolResult {
        return ToolResult.Success(envelope(ok, action, JsonObject(detail), tabId))
    }

    private fun buildData(action: String, detail: Map<String, JsonElement>, tabId: String? = null): JsonObject {
        return envelope(true, action, JsonObject(detail), tabId)
    }

    private fun buildOk(action: String, detail: Map<String, JsonElement>, tabId: String? = null): ToolResult {
        return ToolResult.Success(buildData(action, detail, tabId))
    }

    private fun buildFromJson(action: String, jsonStr: String, tabId: String? = null): ToolResult {
        val detail = browserManager.parseEvalResult(jsonStr)
        val ok = (detail as? JsonObject)?.get("matched")?.jsonPrimitive?.contentOrNull != "false"
        return ToolResult.Success(envelope(ok, action, detail, tabId))
    }

    private fun buildError(action: String, message: String, code: String, tabId: String? = null): ToolResult {
        val resolvedTabId = tabId ?: browserManager.getActiveTabId()
        return ToolResult.Success(JsonObject(mapOf(
            "ok" to JsonPrimitive(false),
            "action" to JsonPrimitive(action),
            "tabId" to JsonPrimitive(resolvedTabId),
            "url" to JsonPrimitive(browserManager.getUrl(resolvedTabId)),
            "title" to JsonPrimitive(browserManager.getTitle(resolvedTabId)),
            "detail" to JsonObject(emptyMap()),
            "error" to JsonObject(mapOf(
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message)
            ))
        )))
    }
}
