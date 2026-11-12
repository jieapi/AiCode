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
import com.aicode.feature.plugin.domain.PluginSettingsPage
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 插件页 WebView 的统一创建入口。
 *
 * 设置页面板与进入设置时的预热共用同一套配置，避免两处各写一份导致行为不一致。
 * 页面资源只经 [WebViewAssetLoader] 的虚拟域加载：`/plugin/` 映射插件安装目录，
 * `/__aicode/` 提供宿主的 `aicode.js` 与动态主题变量，不暴露本地文件系统。
 */
internal object PluginWebViewFactory {

    const val PAGE_BASE_URL = "https://appassets.androidplatform.net/plugin/"

    fun pageUrl(page: PluginSettingsPage): String {
        val version = page.pluginVersion.takeIf { it.isNotBlank() }?.let { "?v=$it" }.orEmpty()
        return PAGE_BASE_URL + page.pageFile + version
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(
        context: Context,
        page: PluginSettingsPage,
        dark: Boolean,
        backgroundColor: Int,
        themeVariables: () -> Map<String, String>,
        onPageFinished: (WebView) -> Unit,
        onMainFrameError: () -> Unit
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
            }
        }
    }

    fun webViewThemeRes(dark: Boolean): Int =
        if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

    private class PluginPageAssetHandler(private val root: File) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val file = File(root, path).canonicalFile
            val prefix = root.path + File.separator
            if (file != root && !file.path.startsWith(prefix)) return null
            if (!file.isFile) return null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            return webResource(mimeOf(path), bytes)
        }
    }

    private class HostUiAssetHandler(
        private val context: Context,
        private val variables: () -> Map<String, String>
    ) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path == "theme.css") {
                val css = PluginThemeTokens.css(variables())
                return WebResourceResponse(
                    "text/css",
                    "utf-8",
                    ByteArrayInputStream(css.toByteArray(Charsets.UTF_8))
                )
            }
            val bytes = runCatching {
                val assetPath = if (path == "eruda.min.js") "scripts/eruda.min.js" else "plugin-ui/$path"
                context.assets.open(assetPath).use { it.readBytes() }
            }.getOrNull() ?: return null
            return webResource(mimeOf(path), bytes)
        }
    }

    /** 插件页资源禁用缓存：APK 更新后 WebView 不能继续用旧版 aicode.js。 */
    private fun webResource(mime: String, bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes)).apply {
            setResponseHeaders(mapOf("Cache-Control" to "no-store"))
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
}
