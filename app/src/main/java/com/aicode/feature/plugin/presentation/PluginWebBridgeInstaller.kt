package com.aicode.feature.plugin.presentation

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.core.util.FileLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 插件页面桥的唯一注入入口。
 *
 * 优先使用 WebMessageListener：允许的来源域由 WebView 判定，回调再校验主 frame 与 origin，
 * 子 frame 与远程页面拿不到可用的桥。系统不支持时回退 `addJavascriptInterface`，
 * 此时桥对所有 frame 可见，安全边界依赖 [PluginWebViewFactory] 下发的 CSP 只允许包内脚本执行。
 *
 * 对同一个 WebView 重复注入不会重复注册；换桥实例时先解除旧注册再注册新的。
 * [bridge] 在回退路径下必须提供带 `@JavascriptInterface` 的 `postMessage(String)` 方法。
 */
internal object PluginWebBridgeInstaller {

    const val JS_OBJECT_NAME = "__aicodeBridge"
    const val VIRTUAL_SCHEME = "https"
    const val VIRTUAL_HOST = "appassets.androidplatform.net"
    const val VIRTUAL_ORIGIN = "$VIRTUAL_SCHEME://$VIRTUAL_HOST"

    val ALLOWED_ORIGINS: Set<String> = setOf(VIRTUAL_ORIGIN)

    private const val TAG = "PluginWebBridge"

    private class Registration(
        bridge: Any,
        val usesWebMessageListener: Boolean
    ) {
        // 只弱引用桥：桥的回调持有 WebView，若值强引用桥，WeakHashMap 的键就永远不会被回收。
        private val bridgeRef = WeakReference(bridge)

        fun isSameBridge(candidate: Any): Boolean = bridgeRef.get() === candidate
    }

    // WebView 会持有 Activity Context，用弱键避免缓存淘汰后的实例被安装表拖住。
    private val registrations = WeakHashMap<WebView, Registration>()

    fun install(webView: WebView, bridge: Any, onMessage: (String) -> Unit) {
        synchronized(registrations) {
            val current = registrations[webView]
            if (current != null) {
                if (current.isSameBridge(bridge)) return
                unregister(webView, current)
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                val listener = object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (!isTrustedBridgeFrame(
                                isMainFrame = isMainFrame,
                                scheme = sourceOrigin.scheme,
                                host = sourceOrigin.host,
                                port = sourceOrigin.port
                            )
                        ) {
                            FileLogger.w(TAG, "忽略非插件页主 frame 的桥调用: $sourceOrigin")
                            return
                        }
                        val payload = message.data ?: return
                        runCatching { onMessage(payload) }
                            .onFailure { FileLogger.w(TAG, "桥消息处理失败", it) }
                    }
                }
                val added = runCatching {
                    WebViewCompat.addWebMessageListener(
                        webView,
                        JS_OBJECT_NAME,
                        ALLOWED_ORIGINS,
                        listener
                    )
                }.onFailure {
                    FileLogger.w(TAG, "WebMessageListener 注册失败，回退 addJavascriptInterface", it)
                }.isSuccess
                if (added) {
                    registrations[webView] = Registration(bridge, usesWebMessageListener = true)
                    return
                }
            } else {
                FileLogger.w(
                    TAG,
                    "WebView 不支持 WebMessageListener，回退 addJavascriptInterface；" +
                        "桥对所有 frame 可见，安全边界依赖 CSP 限制脚本来源"
                )
            }
            webView.addJavascriptInterface(bridge, JS_OBJECT_NAME)
            registrations[webView] = Registration(bridge, usesWebMessageListener = false)
        }
    }

    private fun unregister(webView: WebView, registration: Registration) {
        if (registration.usesWebMessageListener) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, JS_OBJECT_NAME) }
                .onFailure { FileLogger.w(TAG, "移除 WebMessageListener 失败", it) }
        } else {
            runCatching { webView.removeJavascriptInterface(JS_OBJECT_NAME) }
                .onFailure { FileLogger.w(TAG, "移除 JavascriptInterface 失败", it) }
        }
    }
}

/**
 * 判定一次桥调用是否来自宿主虚拟域的主 frame。
 *
 * 只按 scheme/host/port 比较，不做字符串前缀匹配：`appassets.androidplatform.net.evil.example`
 * 这类域会因 host 不等而被拒绝。`port` 取 [Uri.getPort] 的语义：未显式指定为 -1，显式 443 也是同一来源。
 */
internal fun isTrustedBridgeFrame(
    isMainFrame: Boolean,
    scheme: String?,
    host: String?,
    port: Int
): Boolean {
    if (!isMainFrame) return false
    if (!PluginWebBridgeInstaller.VIRTUAL_SCHEME.equals(scheme, ignoreCase = true)) return false
    if (!PluginWebBridgeInstaller.VIRTUAL_HOST.equals(host, ignoreCase = true)) return false
    return port == -1 || port == 443
}
