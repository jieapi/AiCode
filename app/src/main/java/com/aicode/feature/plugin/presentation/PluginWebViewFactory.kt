package com.aicode.feature.plugin.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginPage
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 插件页 WebView 的统一创建入口。
 *
 * 设置页面板与进入设置时的预热共用同一套配置，避免两处各写一份导致行为不一致。
 * 页面资源只经 [WebViewAssetLoader] 的虚拟域加载：`/plugin/` 映射插件安装目录，
 * `/__aicode/` 提供宿主的 `aicode.js` 与动态主题变量，不暴露本地文件系统。
 *
 * HTML 响应附带 CSP，禁止远程脚本、iframe 与 object；资源字节走进程内缓存减少重复读盘，
 * 但响应头保持 `Cache-Control: no-store`，插件覆盖与 APK 更新后 WebView 不会继续用旧内容。
 */
internal object PluginWebViewFactory {

    const val PAGE_BASE_URL = "https://appassets.androidplatform.net/plugin/"

    fun pageUrl(page: PluginPage): String {
        val version = page.pluginVersion.takeIf { it.isNotBlank() }?.let { "?v=$it" }.orEmpty()
        return PAGE_BASE_URL + page.pageFile + version
    }

    /** 插件浮层：透明 WebView，禁用滚动条与外部导航，页面资源仍只走虚拟域。 */
    fun createOverlay(
        context: Context,
        page: PluginPage,
        onPageFinished: (WebView) -> Unit,
        onMainFrameError: () -> Unit,
        onRenderProcessGone: (WebView) -> Unit = {}
    ): WebView = create(
        context = context,
        page = page,
        dark = false,
        backgroundColor = android.graphics.Color.TRANSPARENT,
        themeVariables = { emptyMap() },
        onPageFinished = onPageFinished,
        onMainFrameError = onMainFrameError,
        onRenderProcessGone = onRenderProcessGone
    ).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.domStorageEnabled = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = android.view.View.OVER_SCROLL_NEVER
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        page: PluginPage,
        dark: Boolean,
        backgroundColor: Int,
        themeVariables: () -> Map<String, String>,
        onPageFinished: (WebView) -> Unit,
        onMainFrameError: () -> Unit,
        onRenderProcessGone: (WebView) -> Unit = {}
    ): WebView {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/plugin/", PluginPageAssetHandler(page.installDir.canonicalFile))
            .addPathHandler("/__aicode/", HostUiAssetHandler(context) { themeVariables() })
            .build()

        return WebView(ContextThemeWrapper(context, webViewThemeRes(dark))).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            setBackgroundColor(backgroundColor)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    FileLogger.d(
                        "PluginWebView",
                        "[${page.pluginId}] ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = true

                override fun onPageFinished(view: WebView, url: String?) {
                    onPageFinished(view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) onMainFrameError()
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    FileLogger.e(
                        "PluginWebView",
                        "[${page.pluginId}] 渲染进程退出: didCrash=${detail?.didCrash()}"
                    )
                    if (view != null) {
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        runCatching {
                            view.stopLoading()
                            view.destroy()
                        }
                        onRenderProcessGone(view)
                    }
                    onMainFrameError()
                    return true
                }
            }
        }
    }

    fun clearResourceCache() {
        ResourceCache.clear()
    }

    fun webViewThemeRes(dark: Boolean): Int =
        if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

    private class PluginPageAssetHandler(private val root: File) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file = File(root, path).canonicalFile
            val prefix = root.path + File.separator
            if (file != root && !file.path.startsWith(prefix)) return null
            if (!file.isFile) return null
            val bytes = cachedBytes(file) ?: return null
            return webResource(mimeOf(path), bytes)
        }

        private fun cachedBytes(file: File): ByteArray? {
            // 键含 canonical path + mtime + size：插件同版本覆盖文件会换键，不会读到旧字节。
            val key = "plugin:${file.path}:${file.lastModified()}:${file.length()}"
            ResourceCache.get(key)?.let { return it }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            ResourceCache.put(key, bytes)
            return bytes
        }
    }

    private class HostUiAssetHandler(
        private val context: Context,
        private val variables: () -> Map<String, String>
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path == "theme.css") {
                // 跟随主题动态生成，不进程内缓存，也不让 WebView 复用旧副本。
                val css = PluginThemeTokens.css(variables())
                return WebResourceResponse(
                    "text/css",
                    "utf-8",
                    ByteArrayInputStream(css.toByteArray(Charsets.UTF_8))
                ).apply {
                    setResponseHeaders(mapOf("Cache-Control" to "no-store"))
                }
            }
            if (path.isBlank() || path.startsWith("/") || path.split('/').any { it == ".." || it == "." }) {
                return null
            }
            val bytes = cachedBytes(path) ?: return null
            return webResource(mimeOf(path), bytes)
        }

        private fun cachedBytes(path: String): ByteArray? {
            // 宿主资源随 APK 固定，进程内不会变化，键只用路径。
            val key = "host:$path"
            ResourceCache.get(key)?.let { return it }
            val bytes = runCatching {
                context.assets.open("plugin-ui/$path").use { it.readBytes() }
            }.getOrNull() ?: return null
            ResourceCache.put(key, bytes)
            return bytes
        }
    }

    /**
     * 插件页资源禁用浏览器缓存：APK 更新后 WebView 不能继续用旧版 aicode.js。
     * 内存缓存只省掉重复读盘，不改变这条响应头。
     */
    private fun webResource(mime: String, bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes)).apply {
            setResponseHeaders(
                buildMap {
                    put("Cache-Control", "no-store")
                    contentSecurityPolicyForMime(mime)?.let { put("Content-Security-Policy", it) }
                }
            )
        }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    /**
     * 进程内资源缓存，只用于避免每次 shouldInterceptRequest 都读磁盘。
     *
     * LRU 上限约 [MAX_ENTRIES] 项 / [MAX_BYTES] 字节：插件资源键含 mtime 与 size，
     * 宿主资源键只用路径。`theme.css` 是动态生成内容，不经过这里。
     */
    private object ResourceCache {
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 8 * 1024 * 1024

        private class Entry(val bytes: ByteArray)

        private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
        private var totalBytes = 0

        @Synchronized
        fun get(key: String): ByteArray? = entries[key]?.bytes

        @Synchronized
        fun put(key: String, bytes: ByteArray) {
            if (bytes.size > MAX_BYTES) return
            entries.remove(key)?.let { totalBytes -= it.bytes.size }
            entries[key] = Entry(bytes)
            totalBytes += bytes.size
            val iterator = entries.entries.iterator()
            while ((entries.size > MAX_ENTRIES || totalBytes > MAX_BYTES) && iterator.hasNext()) {
                val entry = iterator.next()
                totalBytes -= entry.value.bytes.size
                iterator.remove()
            }
        }
        @Synchronized
        fun clear() {
            entries.clear()
            totalBytes = 0
        }
    }
}

/** 插件页 HTML 的 CSP：脚本只允许包内与内联，远程脚本、iframe、object 全部阻断。 */
internal const val HTML_CONTENT_SECURITY_POLICY: String =
    "default-src 'self' appassets.androidplatform.net data: blob:; " +
        "script-src 'self' appassets.androidplatform.net 'unsafe-inline' 'unsafe-eval'; " +
        "style-src 'self' appassets.androidplatform.net 'unsafe-inline' https: data:; " +
        "img-src 'self' appassets.androidplatform.net data: blob: https:; " +
        "font-src 'self' appassets.androidplatform.net data: https:; " +
        "media-src 'self' appassets.androidplatform.net data: blob: https:; " +
        "connect-src 'self' appassets.androidplatform.net http: https:; " +
        "frame-src 'none'; " +
        "object-src 'none'; " +
        "base-uri 'none'; " +
        "frame-ancestors 'none'"

/** 只有 HTML 需要 CSP；CSS/JS/图片等子资源不加，避免多余响应头。 */
internal fun contentSecurityPolicyForMime(mime: String): String? =
    if (mime == "text/html") HTML_CONTENT_SECURITY_POLICY else null
