package com.aicode.feature.plugin.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 单个插件浮层的窗口内容：透明 WebView + 可拖拽手柄。
 *
 * WebView 负责渲染插件页面；手柄覆盖页面声明的拖拽区域，宿主在这里完成窗口移动、
 * 点击/长按识别，再把事件送回页面。手柄之外的区域仍由页面正常接收触摸。
 */
internal class PluginOverlayWindow(
    context: Context,
    val pluginId: String,
    val overlayId: String,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val webView: WebView,
    private val draggable: Boolean,
    initialVisibilityPolicy: String,
    initialForeground: Boolean,
    private val onPositionChanged: (Int, Int) -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dragHandle = View(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var bridge: PluginOverlayBridge? = null
    private var dragRegionCss = RectF(0f, 0f, layoutParams.width / density, layoutParams.height / density)
    private var visibilityPolicy = initialVisibilityPolicy
    private var appForeground = initialForeground
    private var requestedVisible = true

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            sendEvent(buildJsonObject { put("type", "longPress") })
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dragHandle.setBackgroundColor(Color.TRANSPARENT)
        addView(
            dragHandle,
            LayoutParams(layoutParams.width, layoutParams.height).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        dragHandle.visibility = if (draggable) View.VISIBLE else View.GONE
        dragHandle.setOnTouchListener { _, event -> onDragTouch(event) }
        applyVisibility()
    }

    fun bindBridge(value: PluginOverlayBridge) {
        bridge = value
    }

    fun describe(): JsonObject = buildJsonObject {
        put("overlayId", overlayId)
        put("pluginId", pluginId)
        put("width", widthDp())
        put("height", heightDp())
        put("x", layoutParams.x)
        put("y", layoutParams.y)
        put("draggable", draggable)
        put("visibilityPolicy", visibilityPolicy)
    }

    fun selfInfo(): JsonObject = buildJsonObject {
        put("overlayId", overlayId)
        put("pluginId", pluginId)
        put("width", widthDp())
        put("height", heightDp())
        put("x", (layoutParams.x / density).toInt())
        put("y", (layoutParams.y / density).toInt())
        put("density", density)
        put("draggable", draggable)
        put("visibilityPolicy", visibilityPolicy)
        put("foreground", appForeground)
    }

    fun visibilityPolicy(): String = visibilityPolicy

    fun isAppForeground(): Boolean = appForeground

    fun setVisibilityPolicy(policy: String) {
        visibilityPolicy = policy
        applyVisibility()
    }

    fun onAppForegroundChanged(foreground: Boolean) {
        appForeground = foreground
        applyVisibility()
    }

    fun sizeDp(): Pair<Int, Int> = widthDp() to heightDp()

    /** rect 使用页面 CSS 像素，宿主换算成窗口像素。 */
    fun setDragRegion(rect: RectF) {
        if (!draggable) return
        dragRegionCss = RectF(rect)
        applyDragRegion()
    }

    /** 插件运行时调整窗口尺寸；尺寸单位为 dp，页面会收到 resize 消息。 */
    fun resize(widthDp: Int, heightDp: Int) {
        val oldWidth = widthDp()
        val oldHeight = heightDp()
        val wasFull = dragRegionCss.left <= 0f &&
            dragRegionCss.top <= 0f &&
            dragRegionCss.right >= oldWidth &&
            dragRegionCss.bottom >= oldHeight
        layoutParams.width = (widthDp * density).toInt().coerceAtLeast(1)
        layoutParams.height = (heightDp * density).toInt().coerceAtLeast(1)
        if (wasFull) {
            dragRegionCss = RectF(0f, 0f, widthDp.toFloat(), heightDp.toFloat())
        } else {
            dragRegionCss.right = dragRegionCss.right.coerceAtMost(widthDp.toFloat())
            dragRegionCss.bottom = dragRegionCss.bottom.coerceAtMost(heightDp.toFloat())
        }
        applyDragRegion()
        clampPosition()
        runCatching { windowManager.updateViewLayout(this, layoutParams) }
        postMessage(
            buildJsonObject {
                put("type", "resize")
                put("width", widthDp)
                put("height", heightDp)
            }
        )
    }

    private fun applyDragRegion() {
        if (!draggable) return
        val left = (dragRegionCss.left * density).toInt().coerceIn(0, layoutParams.width)
        val top = (dragRegionCss.top * density).toInt().coerceIn(0, layoutParams.height)
        val width = (dragRegionCss.width() * density).toInt().coerceIn(0, layoutParams.width - left)
        val height = (dragRegionCss.height() * density).toInt().coerceIn(0, layoutParams.height - top)
        val lp = dragHandle.layoutParams as LayoutParams
        lp.width = width
        lp.height = height
        lp.leftMargin = left
        lp.topMargin = top
        dragHandle.layoutParams = lp
        dragHandle.requestLayout()
    }

    private fun widthDp(): Int = (layoutParams.width / density).toInt()

    private fun heightDp(): Int = (layoutParams.height / density).toInt()

    fun moveBy(dx: Float, dy: Float) {
        layoutParams.x += (dx * density).toInt()
        layoutParams.y += (dy * density).toInt()
        clampPosition()
        runCatching { windowManager.updateViewLayout(this, layoutParams) }
        onPositionChanged(layoutParams.x, layoutParams.y)
    }

    fun setVisible(visible: Boolean) {
        requestedVisible = visible
        applyVisibility()
    }

    private fun applyVisibility() {
        val visible = requestedVisible && when (visibilityPolicy) {
            "foreground" -> appForeground
            "background" -> !appForeground
            else -> true
        }
        visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) webView.onResume() else webView.onPause()
    }

    fun postMessage(payload: JsonElement) {
        evaluate("window.__aicodeOverlayMessage(${JSONObject.quote(payload.toString())})")
    }

    fun evaluate(script: String) {
        mainHandler.post {
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    fun dispose() {
        bridge?.close()
        bridge = null
        dragHandle.setOnTouchListener(null)
        runCatching { webView.destroy() }
    }

    private fun onDragTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                dragging = false
                longPressFired = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                dragHandle.isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    longPressFired = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    sendEvent(buildJsonObject { put("type", "dragStart") })
                }
                if (dragging) {
                    layoutParams.x = startX + dx.toInt()
                    layoutParams.y = startY + dy.toInt()
                    clampPosition()
                    runCatching { windowManager.updateViewLayout(this, layoutParams) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                dragHandle.isPressed = false
                if (dragging) {
                    sendEvent(
                        buildJsonObject {
                            put("type", "dragEnd")
                            put("x", layoutParams.x)
                            put("y", layoutParams.y)
                        }
                    )
                    onPositionChanged(layoutParams.x, layoutParams.y)
                } else if (!longPressFired) {
                    sendEvent(buildJsonObject { put("type", "click") })
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                dragHandle.isPressed = false
                dragging = false
                return true
            }
        }
        return false
    }

    private fun sendEvent(event: JsonObject) {
        postMessage(event)
    }

    private fun clampPosition() {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - layoutParams.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layoutParams.height).coerceAtLeast(0)
        layoutParams.x = layoutParams.x.coerceIn(0, maxX)
        layoutParams.y = layoutParams.y.coerceIn(0, maxY)
    }

    private companion object {
        const val LONG_PRESS_MS = 520L
    }
}

/** 宿主接受的浮层尺寸范围，单位为 dp。 */
internal object PluginOverlayLimits {
    const val MIN_DP = 48
    const val MAX_WIDTH_DP = 720
    const val MAX_HEIGHT_DP = 1200
}
