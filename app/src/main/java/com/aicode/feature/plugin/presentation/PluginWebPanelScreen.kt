package com.aicode.feature.plugin.presentation

import android.view.ContextThemeWrapper
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.plugin.domain.PluginSettingsPage

/**
 * 插件 HTML 页面宿主。
 *
 * 页面资源经 [PluginWebViewFactory] 的虚拟域加载，与进入设置时的预热共用同一套 WebView 配置。
 * 主题变化时更新 CSS 变量，页面不重新加载。
 */
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
    val pageUrl = PluginWebViewFactory.pageUrl(page)
    val cached = remember(page.key) { PluginWebViewCache.take(page.key) }
    val cachedBridge = remember(page.key) {
        if (cached != null) PluginWebViewCache.takeBridge(page.key) else null
    }
    var failed by remember(page.key) { mutableStateOf(false) }
    var viewportReady by remember(page.key) { mutableStateOf(false) }
    // 返回键派发给页面后要等 JS 结果；期间重复触发不再派发，避免连续退出。
    var backDispatching by remember(page.key) { mutableStateOf(false) }

    val webView = remember(page.key) {
        (cached ?: PluginWebViewFactory.create(
            context = context,
            page = page,
            dark = dark,
            backgroundColor = backgroundColor,
            themeVariables = { currentVariables },
            onPageFinished = { view ->
                failed = false
                PluginWebViewCache.markLoaded(page.key, pageUrl)
                view.evaluateJavascript(PluginThemeTokens.applyScript(currentVariables), null)
            },
            onMainFrameError = { failed = true }
        )).also { PluginWebViewCache.put(page.key, it) }
    }

    val bridge = remember(webView, page.key) {
        cachedBridge ?: bridgeFactory({ script ->
            runCatching { webView.evaluateJavascript(script, null) }
        }, onClosePage).also { created ->
            PluginWebViewCache.putBridge(page.key, created)
            // 注入对象只在页面下次加载时出现；这里先注入，LaunchedEffect 随后 loadUrl。
            webView.addJavascriptInterface(created, "__aicodeBridge")
        }
    }

    // 缓存复用时页面持有的是同一个桥实例，回调要换成当前组合的。
    SideEffect {
        bridge.updateCallbacks(
            theme = { if (dark) "dark" else "light" },
            onClosePage = onClosePage,
            post = { script ->
                runCatching { webView.evaluateJavascript(script, null) }
            }
        )
    }

    LaunchedEffect(webView, pageUrl, viewportReady) {
        if (viewportReady && !PluginWebViewCache.isLoaded(page.key, pageUrl)) {
            webView.loadUrl(pageUrl)
        }
    }

    LaunchedEffect(dark, variables) {
        (webView.context as? ContextThemeWrapper)?.setTheme(PluginWebViewFactory.webViewThemeRes(dark))
        webView.setBackgroundColor(backgroundColor)
        webView.evaluateJavascript(PluginThemeTokens.applyScript(variables), null)
    }

    // 返回优先级：WebView 自身历史（hash 路由等）→ 页面注册的页面内返回 → 退出插件页。
    BackHandler {
        if (backDispatching) return@BackHandler
        if (webView.canGoBack()) {
            webView.goBack()
            return@BackHandler
        }
        backDispatching = true
        webView.evaluateJavascript(BACK_DISPATCH_SCRIPT) { result ->
            backDispatching = false
            if (result != "true") onClosePage()
        }
    }

    DisposableEffect(webView) {
        onDispose {
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
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) viewportReady = true
                }
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

private const val BACK_DISPATCH_SCRIPT =
    "(function(){try{return window.__aicodeBack&&window.__aicodeBack()===true}catch(e){return false}})()"
