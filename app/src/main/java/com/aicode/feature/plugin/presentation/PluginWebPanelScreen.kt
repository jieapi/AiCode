package com.aicode.feature.plugin.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.plugin.domain.PluginSettingsPage
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 插件 HTML 页面宿主。
 *
 * 页面资源只经 [WebViewAssetLoader] 的虚拟域加载：`/plugin/` 映射插件安装目录，
 * `/__aicode/` 提供宿主的 `aicode.js` 与动态主题变量，不暴露本地文件系统。
 * 主题变化时更新 CSS 变量，页面不重新加载。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebPanelScreen(
    page: PluginSettingsPage,
    onClosePage: () -> Unit,
    bridgeFactory: (post: (String) -> Unit, onClosePage: () -> Unit) -> PluginWebBridge,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    val fontScale = LocalConfiguration.current.fontScale
    val dark = PluginThemeTokens.isDark(colorScheme)
    val variables = remember(colorScheme, semantic, fontScale) {
        PluginThemeTokens.variables(colorScheme, semantic, fontScale)
    }
    val currentVariables by rememberUpdatedState(variables)
    val backgroundColor = semantic.pageBackground.toArgb()
    val pageUrl = PAGE_BASE_URL + page.pageFile
    val cached = remember(page.key) { PluginWebViewCache.take(page.key) }
    var failed by remember(page.key) { mutableStateOf(false) }

    val assetLoader = remember(page.installDir) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/plugin/", PluginPageAssetHandler(page.installDir.canonicalFile))
            .addPathHandler("/__aicode/", HostUiAssetHandler(context) { currentVariables })
            .build()
    }

    val webView = remember(page.key) {
        (cached ?: WebView(ContextThemeWrapper(context, webViewThemeRes(dark))).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            setBackgroundColor(backgroundColor)
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
                    failed = false
                    PluginWebViewCache.markLoaded(page.key, pageUrl)
                    view.evaluateJavascript(PluginThemeTokens.applyScript(currentVariables), null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) failed = true
                }
            }
        }).also { PluginWebViewCache.put(page.key, it) }
    }

    val bridge = remember(webView, page.key) {
        bridgeFactory({ script ->
            webView.post { runCatching { webView.evaluateJavascript(script, null) } }
        }, onClosePage)
    }

    LaunchedEffect(webView, pageUrl) {
        webView.addJavascriptInterface(bridge, "__aicodeBridge")
        if (!PluginWebViewCache.isLoaded(page.key, pageUrl)) {
            webView.loadUrl(pageUrl)
        }
    }

    LaunchedEffect(dark, variables) {
        (webView.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
        webView.setBackgroundColor(backgroundColor)
        webView.evaluateJavascript(PluginThemeTokens.applyScript(variables), null)
    }

    DisposableEffect(webView) {
        onDispose {
            bridge.close()
            PluginWebViewCache.release(webView)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )

        if (failed) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.plugin_web_page_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    failed = false
                    webView.loadUrl(pageUrl)
                }) {
                    Text(stringResource(R.string.plugin_web_page_retry))
                }
            }
        }
    }
}

private class PluginPageAssetHandler(private val root: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val file = File(root, path).canonicalFile
        val prefix = root.path + File.separator
        if (file != root && !file.path.startsWith(prefix)) return null
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return WebResourceResponse(mimeOf(path), null, ByteArrayInputStream(bytes))
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
            context.assets.open("plugin-ui/$path").use { it.readBytes() }
        }.getOrNull() ?: return null
        return WebResourceResponse(mimeOf(path), null, ByteArrayInputStream(bytes))
    }
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

private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

private const val PAGE_BASE_URL = "https://appassets.androidplatform.net/plugin/"
