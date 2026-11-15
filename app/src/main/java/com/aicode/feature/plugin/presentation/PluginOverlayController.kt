package com.aicode.feature.plugin.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 插件 HTML 页面里全屏弹层的位置。
 *
 * 全局背景图绘制在 Activity 内容层最上方，而 WebView 里的弹层仍在 WebView 内，
 * 因此背景图会叠到弹层上；原生 Dialog 是独立窗口，只有弹层区域没有背景图。
 * 页面把弹层的 CSS 像素矩形上报给宿主，宿主换算成屏幕像素后在背景图上挖洞，
 * 其余区域继续保留背景图。
 */
object PluginOverlayController {

    /** 页面 `getBoundingClientRect()` 的结果，单位是 WebView 的 CSS 像素。 */
    data class CssRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val topRadius: Float,
        val bottomRadius: Float,
        val coversBottom: Boolean = false
    )

    /** 换算到屏幕物理像素后的弹层区域。 */
    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val topRadius: Float,
        val bottomRadius: Float
    )

    data class State(
        val bounds: List<Bounds> = emptyList(),
        val suppressBackground: Boolean = false,
        val bottomFill: Boolean = false
    )

    /** 按页面缓存键记录当前打开的弹层；rect 为 null 表示页面只报告了打开状态。 */
    private val active = mutableMapOf<String, CssRect?>()
    private val anchors = mutableMapOf<String, (CssRect) -> Bounds?>()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 页面可见期间注册坐标换算；WebView 销毁时注销。 */
    @Synchronized
    fun registerAnchor(pageKey: String, mapper: (CssRect) -> Bounds?) {
        anchors[pageKey] = mapper
        publish()
    }

    @Synchronized
    fun unregisterAnchor(pageKey: String) {
        anchors.remove(pageKey)
        active.remove(pageKey)
        publish()
    }

    @Synchronized
    fun setOverlay(pageKey: String, visible: Boolean, rect: CssRect?) {
        if (visible) active[pageKey] = rect else active.remove(pageKey)
        publish()
    }

    /** WebView 位置变化时重新换算当前弹层矩形。 */
    @Synchronized
    fun refresh(pageKey: String) {
        if (active.containsKey(pageKey)) publish()
    }

    /**
     * 只要有一个弹层拿不到矩形，就退回整页隐藏背景图，避免背景叠在弹层上。
     * 多个弹层同时存在时逐个挖洞。
     */
    private fun publish() {
        val bounds = mutableListOf<Bounds>()
        var missing = false
        var bottomFill = false
        active.forEach { (key, rect) ->
            if (rect?.coversBottom == true) bottomFill = true
            val mapped = if (rect == null) null else anchors[key]?.invoke(rect)
            if (mapped != null) bounds += mapped else missing = true
        }
        _state.value = State(bounds = bounds, suppressBackground = missing, bottomFill = bottomFill)
    }
}
