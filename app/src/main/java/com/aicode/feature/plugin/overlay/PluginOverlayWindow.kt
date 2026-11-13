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
import com.aicode.feature.plugin.domain.PluginOverlaySnap
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
    private val snap: String,
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
    private var snapAnimator: ValueAnimator? = null
    private var snappedEdge: String? = null
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
        refreshSnappedEdge()
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
        put("snap", snap)
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
        put("snap", snap)
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
        snapAnimator?.end()
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
        val maxX = (metrics.widthPixels - toWidth).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - toHeight).coerceAtLeast(0)
        val toX = when {
            x != null -> (x * density).roundToInt()
            snappedEdge == "left" -> 0
            snappedEdge == "right" -> maxX
            else -> fromX
        }.coerceIn(0, maxX)
        val toY = when {
            y != null -> (y * density).roundToInt()
            snappedEdge == "top" -> 0
            snappedEdge == "bottom" -> maxY
            else -> fromY
        }.coerceIn(0, maxY)
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
            refreshSnappedEdge()
            onPositionChanged(layoutParams.x, layoutParams.y)
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
        snapAnimator?.end()
        resizeAnimator?.end()
        snappedEdge = null
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
        snapAnimator?.cancel()
        snapAnimator = null
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
                snapAnimator?.end()
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
                    snappedEdge = null
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
                            put("x", layoutParams.x / density)
                            put("y", layoutParams.y / density)
                        }
                    )
                    if (!startSnap()) {
                        onPositionChanged(layoutParams.x, layoutParams.y)
                    }
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

    private fun allowsSnapX(): Boolean = snap == PluginOverlaySnap.X || snap == PluginOverlaySnap.BOTH

    private fun allowsSnapY(): Boolean = snap == PluginOverlaySnap.Y || snap == PluginOverlaySnap.BOTH

    private fun refreshSnappedEdge() {
        if (snap == PluginOverlaySnap.NONE) {
            snappedEdge = null
            return
        }
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - layoutParams.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layoutParams.height).coerceAtLeast(0)
        snappedEdge = when {
            allowsSnapX() && layoutParams.x <= EDGE_TOLERANCE_PX -> "left"
            allowsSnapX() && layoutParams.x >= maxX - EDGE_TOLERANCE_PX -> "right"
            allowsSnapY() && layoutParams.y <= EDGE_TOLERANCE_PX -> "top"
            allowsSnapY() && layoutParams.y >= maxY - EDGE_TOLERANCE_PX -> "bottom"
            else -> null
        }
    }

    /** 仅当窗口边缘距屏幕边缘足够近时吸附；返回 null 表示停在松手位置。 */
    private fun snapTarget(): Triple<String, Int, Int>? {
        val allowX = allowsSnapX()
        val allowY = allowsSnapY()
        if (!allowX && !allowY) return null
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - layoutParams.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layoutParams.height).coerceAtLeast(0)
        val trigger = SNAP_TRIGGER_DP * density
        val candidates = mutableListOf<Pair<Float, Triple<String, Int, Int>>>()
        if (allowX) {
            val left = layoutParams.x.toFloat()
            val right = (maxX - layoutParams.x).toFloat()
            if (left <= trigger) candidates += left to Triple("left", 0, layoutParams.y)
            if (right <= trigger) candidates += right to Triple("right", maxX, layoutParams.y)
        }
        if (allowY) {
            val top = layoutParams.y.toFloat()
            val bottom = (maxY - layoutParams.y).toFloat()
            if (top <= trigger) candidates += top to Triple("top", layoutParams.x, 0)
            if (bottom <= trigger) candidates += bottom to Triple("bottom", layoutParams.x, maxY)
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun startSnap(): Boolean {
        val (edge, targetX, targetY) = snapTarget() ?: return false
        snapAnimator?.cancel()
        snappedEdge = edge
        notifySnap(edge, targetX, targetY)
        val fromX = layoutParams.x
        val fromY = layoutParams.y
        if (fromX == targetX && fromY == targetY) {
            onPositionChanged(targetX, targetY)
            return true
        }
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                layoutParams.x = (fromX + (targetX - fromX) * fraction).roundToInt()
                layoutParams.y = (fromY + (targetY - fromY) * fraction).roundToInt()
                runCatching { windowManager.updateViewLayout(this@PluginOverlayWindow, layoutParams) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (snapAnimator === animation) snapAnimator = null
                    if (!cancelled) {
                        layoutParams.x = targetX
                        layoutParams.y = targetY
                        runCatching { windowManager.updateViewLayout(this@PluginOverlayWindow, layoutParams) }
                        onPositionChanged(targetX, targetY)
                    }
                }
            })
        }
        snapAnimator?.start()
        return true
    }

    private fun notifySnap(edge: String, x: Int, y: Int) {
        postMessage(
            buildJsonObject {
                put("type", "snap")
                put("edge", edge)
                put("x", x / density)
                put("y", y / density)
            }
        )
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
        const val SNAP_DURATION_MS = 280L
        const val SNAP_TRIGGER_DP = 40f
        const val EDGE_TOLERANCE_PX = 2f
    }
}

/** 宿主接受的浮层尺寸范围，单位为 dp。 */
internal object PluginOverlayLimits {
    const val MIN_DP = 48
    const val MAX_WIDTH_DP = 720
    const val MAX_HEIGHT_DP = 1200
}
