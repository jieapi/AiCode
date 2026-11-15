package com.aicode.feature.plugin.presentation

import android.view.ViewGroup
import android.webkit.WebView

/**
 * 设置页内的插件 WebView 缓存。
 *
 * 按 pageKey 保留最近使用的至多 [MAX_ENTRIES] 个实例：同一页面再次打开直接 attach，
 * 不重新加载；不同页面各自持有实例，打开新页面不会拆掉仍在显示的旧页面。
 * WebView 每次创建都要拉起 Chromium 进程，反复新建会有明显白屏。
 *
 * 桥必须跟着 WebView 一起缓存：注入的 Java 对象只在页面下次加载时才会出现在 JS 里，
 * 缓存复用时重新 addJavascriptInterface 不会生效，页面会一直拿着已取消的旧桥。
 *
 * 淘汰只回收已经脱离容器的实例：还挂在容器里的实例可能正在显示，留到 [release] 后再销毁。
 * 缓存只活在一次宿主会话里，退出时 [clear] 统一销毁，避免 WebView 长期持有 Activity Context。
 */
internal object PluginWebViewCache {

    /** 同时保留的页面实例上限；超出后按最近使用顺序淘汰已脱离容器的实例。 */
    private const val MAX_ENTRIES = 3

    private class CachedPage(val view: WebView) {
        var bridge: PluginWebBridge? = null
        var loadedUrl: String? = null
        var loadingUrl: String? = null
    }

    /** 访问顺序 LRU：[take] / [put] 命中的实例排到队尾。 */
    private val pages = LinkedHashMap<String, CachedPage>(4, 0.75f, true)

    /** 被同页新实例顶替、但仍在容器里显示的实例；脱离容器后销毁。 */
    private val superseded = mutableListOf<CachedPage>()

    /** 最近打开过的插件页 key；缓存清空后仍保留，供进入设置时预热。 */
    var lastPageKey: String? = null
        private set

    val hasCachedWebView: Boolean get() = pages.isNotEmpty()

    fun take(pageKey: String): WebView? = pages[pageKey]?.view

    fun takeBridge(pageKey: String): PluginWebBridge? = pages[pageKey]?.bridge

    fun isLoaded(pageKey: String, url: String): Boolean = pages[pageKey]?.loadedUrl == url

    fun isLoading(pageKey: String, url: String): Boolean = pages[pageKey]?.loadingUrl == url

    fun markLoading(pageKey: String, url: String) {
        pages[pageKey]?.loadingUrl = url
    }

    fun clearLoading(pageKey: String) {
        pages[pageKey]?.loadingUrl = null
    }

    fun put(pageKey: String, view: WebView) {
        lastPageKey = pageKey
        val existing = pages[pageKey]
        // 同一页面复用实例时保留已加载标记与桥，否则下次打开会误判为未加载而重新加载。
        if (existing?.view === view) return
        if (existing != null) {
            pages.remove(pageKey)
            retire(existing)
        }
        pages[pageKey] = CachedPage(view)
        trim()
    }

    fun putBridge(pageKey: String, value: PluginWebBridge) {
        val entry = pages[pageKey] ?: return
        if (entry.bridge === value) return
        entry.bridge?.close()
        entry.bridge = value
    }

    fun markLoaded(pageKey: String, url: String) {
        pages[pageKey]?.let { entry ->
            entry.loadedUrl = url
            entry.loadingUrl = null
        }
    }

    /** 页面离开时只从父容器摘掉，保留实例与已加载内容。 */
    fun release(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        trim()
        val retired = superseded.firstOrNull { it.view === view } ?: return
        superseded.remove(retired)
        destroy(retired)
    }

    fun clear() {
        val cached = pages.values.toList()
        pages.clear()
        cached.forEach { destroy(it) }
        val retired = superseded.toList()
        superseded.clear()
        retired.forEach { destroy(it) }
    }

    /** 淘汰超出上限、且已经脱离容器的实例；仍在显示的实例留在缓存里等 [release]。 */
    private fun trim() {
        if (pages.size <= MAX_ENTRIES) return
        val iterator = pages.entries.iterator()
        while (pages.size > MAX_ENTRIES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.view.parent != null) continue
            iterator.remove()
            destroy(entry.value)
        }
    }

    private fun retire(entry: CachedPage) {
        if (entry.view.parent == null) destroy(entry) else superseded += entry
    }

    private fun destroy(entry: CachedPage) {
        (entry.view.parent as? ViewGroup)?.removeView(entry.view)
        entry.view.destroy()
        val bridge = entry.bridge ?: return
        entry.bridge = null
        // 桥可能已经被同页的新实例接管，只关闭没有别处引用的。
        if (pages.values.none { it.bridge === bridge } && superseded.none { it.bridge === bridge }) {
            bridge.close()
        }
    }
}
