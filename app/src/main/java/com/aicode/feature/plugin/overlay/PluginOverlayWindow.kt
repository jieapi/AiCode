package com.aicode.feature.plugin.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    private val contentView = FrameLayout(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var bridge: PluginOverlayBridge? = null
    private var dragRegionCss = RectF(0f, 0f, layoutParams.width / density, layoutParams.height / density)
    private var visibilityPolicy = initialVisibilityPolicy
    private var appForeground = initialForeground
    private var requestedVisible = true
    private var resizeAnimator: ValueAnimator? = null
    private var logicalWidthDp = (layoutParams.width / density).toInt()
    private var logicalHeightDp = (layoutParams.height / density).toInt()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var touchActive = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            sendEvent(buildJsonObject { put("type", "longPress") })
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(contentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentView.addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        dragHandle.setBackgroundColor(Color.TRANSPARENT)
        contentView.addView(
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
        put("x", (layoutParams.x + contentView.translationX) / density)
        put("y", (layoutParams.y + contentView.translationY) / density)
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
    fun resize(
        widthDp: Int,
        heightDp: Int,
        animate: Boolean = false,
        x: Float? = null,
        y: Float? = null
    ) {
        resizeAnimator?.cancel()
        mainHandler.removeCallbacks(longPressRunnable)
        touchActive = false
        dragging = false
        dragHandle.isPressed = false
        val fromWidth = contentView.clipBounds?.width() ?: layoutParams.width
        val fromHeight = contentView.clipBounds?.height() ?: layoutParams.height
        val fromX = (layoutParams.x + contentView.translationX).roundToInt()
        val fromY = (layoutParams.y + contentView.translationY).roundToInt()
        val toWidth = (widthDp * density).toInt().coerceAtLeast(1)
        val toHeight = (heightDp * density).toInt().coerceAtLeast(1)
        val metrics = resources.displayMetrics
        val toX = (x?.times(density) ?: fromX.toFloat()).roundToInt()
            .coerceIn(0, (metrics.widthPixels - toWidth).coerceAtLeast(0))
        val toY = (y?.times(density) ?: fromY.toFloat()).roundToInt()
            .coerceIn(0, (metrics.heightPixels - toHeight).coerceAtLeast(0))
        val wasFull = dragRegionCss.left <= 0f &&
            dragRegionCss.top <= 0f &&
            dragRegionCss.right >= widthDp() &&
            dragRegionCss.bottom >= heightDp()
        logicalWidthDp = widthDp
        logicalHeightDp = heightDp

        fun finishResize() {
            layoutParams.width = toWidth
            layoutParams.height = toHeight
            layoutParams.x = toX
            layoutParams.y = toY
            contentView.translationX = 0f
            contentView.translationY = 0f
            contentView.clipBounds = null
            if (wasFull) dragRegionCss = RectF(0f, 0f, widthDp.toFloat(), heightDp.toFloat())
            applyDragRegion()
            runCatching { windowManager.updateViewLayout(this, layoutParams) }
            if (x != null || y != null) onPositionChanged(layoutParams.x, layoutParams.y)
            postMessage(
                buildJsonObject {
                    put("type", "resize")
                    put("width", widthDp)
                    put("height", heightDp)
                    put("x", layoutParams.x / density)
                    put("y", layoutParams.y / density)
                }
            )
        }

        if (!animate || visibility != View.VISIBLE) {
            finishResize()
            return
        }
        // 系统窗口重布局代价高；动画期间仅更新内容裁剪与平移。
        val envelopeX = minOf(fromX, toX)
        val envelopeY = minOf(fromY, toY)
        layoutParams.x = envelopeX
        layoutParams.y = envelopeY
        layoutParams.width = maxOf(fromX + fromWidth, toX + toWidth) - envelopeX
        layoutParams.height = maxOf(fromY + fromHeight, toY + toHeight) - envelopeY
        contentView.translationX = (fromX - envelopeX).toFloat()
        contentView.translationY = (fromY - envelopeY).toFloat()
        contentView.clipBounds = Rect(0, 0, fromWidth, fromHeight)
        runCatching { windowManager.updateViewLayout(this, layoutParams) }
        resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RESIZE_DURATION_MS
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                contentView.translationX = fromX - envelopeX + (toX - fromX) * fraction
                contentView.translationY = fromY - envelopeY + (toY - fromY) * fraction
                contentView.clipBounds = Rect(
                    0,
                    0,
                    (fromWidth + (toWidth - fromWidth) * fraction).roundToInt(),
                    (fromHeight + (toHeight - fromHeight) * fraction).roundToInt()
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (resizeAnimator === animation) resizeAnimator = null
                    if (!cancelled) finishResize()
                }
            })
        }
        resizeAnimator?.start()
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

    private fun widthDp(): Int = logicalWidthDp

    private fun heightDp(): Int = logicalHeightDp

    fun moveBy(dx: Float, dy: Float) {
        resizeAnimator?.end()
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
        if (!visible) resizeAnimator?.end()
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
        resizeAnimator?.cancel()
        resizeAnimator = null
        mainHandler.removeCallbacks(longPressRunnable)
        bridge?.close()
        bridge = null
        dragHandle.setOnTouchListener(null)
        runCatching { webView.destroy() }
    }

    private fun onDragTouch(event: MotionEvent): Boolean {
        if (resizeAnimator?.isRunning == true ||
            (event.actionMasked != MotionEvent.ACTION_DOWN && !touchActive)
        ) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
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
                touchActive = false
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
                touchActive = false
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
        const val RESIZE_DURATION_MS = 280L
    }
}

/** 宿主接受的浮层尺寸范围，单位为 dp。 */
internal object PluginOverlayLimits {
    const val MIN_DP = 48
    const val MAX_WIDTH_DP = 720
    const val MAX_HEIGHT_DP = 1200
}
