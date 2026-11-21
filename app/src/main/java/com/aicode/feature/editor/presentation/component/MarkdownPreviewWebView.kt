package com.aicode.feature.editor.presentation.component

import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.presentation.MarkdownPreviewViewModel

/**
 * Markdown 预览页：用 WebView 渲染完整 Markdown（含内嵌 HTML），还原 GitHub 观感。
 *
 * 预览页里的本地文件链接会像浏览器一样就地导航过去（Markdown 目标由 [MarkdownPreviewViewModel] 渲染成新预览页）；
 * 返回键优先回退 WebView 历史、到头再退出预览；外部链接不跳转。
 *
 * 与聊天区的 Compose 渲染是两条独立路径，互不影响。
 */
@Composable
internal fun MarkdownPreviewWebView(
    text: String,
    path: String,
    onExitPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarkdownPreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) BG_DARK else BG_LIGHT
    val exitPreview by rememberUpdatedState(onExitPreview)
    var webViewEpoch by remember { mutableIntStateOf(0) }

    val webView = remember(webViewEpoch) {
        val themedContext = ContextThemeWrapper(context, webViewThemeRes(dark))
        WebView(themedContext).apply {
            settings.javaScriptEnabled = true
            // 页面只经 assetLoader 取资源，不暴露本地文件系统
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val response = viewModel.assetLoader.shouldInterceptRequest(request.url)
                    FileLogger.v(TAG, "预览资源请求: ${request.url} -> ${if (response == null) "走网络" else "已拦截"}")
                    return response
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    // 页内锚点（#xxx）：仅 fragment 变化、path 与当前页面一致，放行交给 WebView 滚动
                    if (url.fragment != null && url.path == view.url?.let { Uri.parse(it).path }) return false
                    // 指向工作区的文件链接放行，让 WebView 就地导航（Markdown 由 assetLoader 渲染成预览页）
                    if (viewModel.containerPathForUrl(url) != null) return false
                    // 其余（外链等）一律拦下
                    return true
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    FileLogger.e(TAG, "Markdown 预览渲染进程退出: didCrash=${detail?.didCrash()}")
                    (view?.parent as? ViewGroup)?.removeView(view)
                    runCatching { view?.destroy() }
                    if (webViewEpoch < 1) {
                        webViewEpoch += 1
                    } else {
                        exitPreview()
                    }
                    return true
                }
            }
        }
    }

    // WebView 的 isLightTheme 由上下文主题决定，深色切换时同步更新，避免滚动条等控件配色不匹配。
    LaunchedEffect(webView, dark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
        webView.setBackgroundColor(bgColor)
    }

    DisposableEffect(webView) {
        onDispose { runCatching { webView.destroy() } }
    }

    LaunchedEffect(webView, text, path, dark) {
        val html = viewModel.buildHtml(text, dark)
        webView.loadDataWithBaseURL(viewModel.baseUrl(path), html, "text/html", "utf-8", null)
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else exitPreview()
    }

    androidx.compose.runtime.key(webViewEpoch) {
        AndroidView(factory = { webView }, modifier = modifier)
    }
}

private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

private const val BG_DARK = 0xFF0D1117.toInt()
private const val BG_LIGHT = 0xFFFFFFFF.toInt()
private const val TAG = "MarkdownPreview"
