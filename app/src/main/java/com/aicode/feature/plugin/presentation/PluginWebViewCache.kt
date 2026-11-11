package com.aicode.feature.plugin.presentation

import android.view.ViewGroup
import android.webkit.WebView

/**
 * 设置页内的插件 WebView 缓存。
 *
 * WebView 创建要拉起 Chromium 进程，每次打开都新建会有明显白屏。这里保留最近一个实例：
 * 同一页面再次打开直接 attach，不重新加载；切到别的页面时复用实例 loadUrl。
 * 缓存只活在一次设置页会话里，退出设置页统一销毁，避免 WebView 长期持有 Activity Context。
 */
internal object PluginWebViewCache {

    private var key: String? = null
    private var webView: WebView? = null
    private var loadedUrl: String? = null

    fun take(pageKey: String): WebView? = webView?.takeIf { key == pageKey }

    fun isLoaded(pageKey: String, url: String): Boolean = key == pageKey && loadedUrl == url

    fun put(pageKey: String, view: WebView) {
        // 同一页面复用实例时保留已加载标记，否则下次打开会误判为未加载而重新加载。
        if (key == pageKey && webView === view) return
        if (webView !== view) {
            webView?.let { existing ->
                (existing.parent as? ViewGroup)?.removeView(existing)
                existing.destroy()
            }
        }
        key = pageKey
        webView = view
        loadedUrl = null
    }

    fun markLoaded(pageKey: String, url: String) {
        if (key == pageKey) loadedUrl = url
    }

    /** 页面离开时只从父容器摘掉，保留实例与已加载内容。 */
    fun release(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun clear() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        key = null
        loadedUrl = null
    }
}
