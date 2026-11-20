package com.aicode.feature.plugin.overlay

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * 浮层页面的最小桥。
 *
 * 只提供 overlay.* 与 context；不暴露 Shizuku、Shell、终端等能力。
 * 页面脚本由插件提供，桥的能力边界必须比设置页更窄。
 */
internal class PluginOverlayBridge(
    private val pluginId: String,
    private val overlayId: String,
    private val page: PluginPage,
    private val density: Float,
    private val window: PluginOverlayWindow,
    private val hostApiDispatcher: com.aicode.feature.plugin.runtime.PluginHostApiDispatcher,
    private val onPageMessage: (JsonElement) -> Unit,
    private val onClose: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    @JavascriptInterface
    fun postMessage(payload: String) {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = root["params"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val reply = try {
                buildJsonObject {
                    put("ok", true)
                    put("data", dispatch(method, params))
                }
            } catch (e: PluginHostException) {
                errorReply(e.code, e.message)
            } catch (e: Exception) {
                errorReply("INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            deliver(id, reply)
        }
    }

    fun close() {
        closed = true
        scope.cancel()
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "permissions.request", "permissions.cancel" -> hostApiDispatcher.dispatch(
            pluginId = pluginId,
            method = method,
            params = params,
            origin = com.aicode.feature.plugin.runtime.PluginHostApiOrigin.OVERLAY,
            workspaceRoot = { "" }
        )
        "context" -> buildJsonObject {
            put("pluginId", pluginId)
            put("overlayId", overlayId)
            put("pageId", page.localId)
            put("version", page.pluginVersion)
            put("density", density)
        }

        "overlay.self" -> window.selfInfo()

        "app.isForeground" -> JsonPrimitive(window.isAppForeground())

        "overlay.setVisibilityPolicy" -> {
            val policy = requireString(params, "policy")
            if (policy !in VISIBILITY_POLICIES) {
                throw PluginHostException("PARAM_INVALID", "unsupported visibility policy: $policy")
            }
            window.setVisibilityPolicy(policy)
            JsonNull
        }

        "overlay.close" -> {
            mainHandler.post { onClose() }
            JsonNull
        }

        "overlay.setDragRegion" -> {
            val left = requireFloat(params, "left")
            val top = requireFloat(params, "top")
            val width = requireFloat(params, "width")
            val height = requireFloat(params, "height")
            if (width <= 0f || height <= 0f) {
                throw PluginHostException("PARAM_INVALID", "drag region must be positive")
            }
            window.setDragRegion(RectF(left, top, left + width, top + height))
            JsonNull
        }

        "overlay.moveBy" -> {
            val dx = (params["dx"] as? JsonPrimitive)?.floatOrNull ?: 0f
            val dy = (params["dy"] as? JsonPrimitive)?.floatOrNull ?: 0f
            window.moveBy(dx, dy)
            JsonNull
        }

        "overlay.resize" -> {
            val width = requireInt(params, "width")
            val height = requireInt(params, "height")
            if (width !in PluginOverlayLimits.MIN_DP..PluginOverlayLimits.MAX_WIDTH_DP ||
                height !in PluginOverlayLimits.MIN_DP..PluginOverlayLimits.MAX_HEIGHT_DP
            ) {
                throw PluginHostException("PARAM_INVALID", "overlay size out of range")
            }
            val animate = (params["animate"] as? JsonPrimitive)?.booleanOrNull ?: false
            val x = if (params.containsKey("x")) requireFloat(params, "x") else null
            val y = if (params.containsKey("y")) requireFloat(params, "y") else null
            if (x?.isFinite() == false || y?.isFinite() == false) {
                throw PluginHostException("PARAM_INVALID", "overlay position must be finite")
            }
            window.resize(width, height, animate, x, y)
            buildJsonObject {
                put("width", width)
                put("height", height)
            }
        }

        // 浮层页面只负责渲染；需要状态或副作用的消息转给插件进程。
        "overlay.postMessage" -> {
            val payload = params["payload"] ?: JsonNull
            onPageMessage(payload)
            JsonNull
        }

        else -> throw PluginHostException("METHOD_UNKNOWN", "unknown method: $method")
    }

    private fun requireFloat(params: JsonObject, key: String): Float =
        (params[key] as? JsonPrimitive)?.floatOrNull
            ?: throw PluginHostException("PARAM_INVALID", "$key required")

    private fun requireInt(params: JsonObject, key: String): Int =
        (params[key] as? JsonPrimitive)?.intOrNull
            ?: throw PluginHostException("PARAM_INVALID", "$key required")

    private fun requireString(params: JsonObject, key: String): String =
        (params[key] as? JsonPrimitive)?.contentOrNull
            ?: throw PluginHostException("PARAM_INVALID", "$key required")

    private companion object {
        val VISIBILITY_POLICIES = setOf("always", "foreground", "background")
    }

    private fun errorReply(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun deliver(id: String, payload: JsonObject) {
        if (closed) return
        val script = "window.__aicodeResolve(${JSONObject.quote(id)}, ${JSONObject.quote(payload.toString())})"
        window.evaluate(script)
    }
}
