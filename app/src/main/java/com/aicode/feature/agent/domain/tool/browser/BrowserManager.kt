package com.aicode.feature.agent.domain.tool.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

data class BrowserTabState(
    val id: String,
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val devToolsOpen: Boolean = false
)

data class BrowserState(
    val tabs: List<BrowserTabState> = emptyList(),
    val activeTabId: String = "",
    val attached: Boolean = false,
    val nightMode: Boolean = false
) {
    val activeTab: BrowserTabState? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    val url: String get() = activeTab?.url.orEmpty()
    val title: String get() = activeTab?.title.orEmpty()
    val loading: Boolean get() = activeTab?.loading == true
    val error: String? get() = activeTab?.error
    val devToolsOpen: Boolean get() = activeTab?.devToolsOpen == true
}

data class BrowserDialog(
    val type: String,
    val message: String,
    val defaultValue: String?
)

data class BrowserConsoleEntry(
    val level: String,
    val message: String,
    val line: Int,
    val source: String
)

@Singleton
class BrowserManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver
) {

    companion object {
        private const val TAG = "BrowserManager"
        private const val NAVIGATE_TIMEOUT_MS = 30_000L
        private const val EVAL_TIMEOUT_MS = 30_000L
        private const val WAIT_POLL_INTERVAL_MS = 500L
        private const val SCREENSHOT_QUALITY = 80
        private const val MAX_CONTENT_CHARS = 100_000
        private const val MAX_BACKBONE_NODES = 600
        private const val DOM_STABLE_QUIET_MS = 500L
        private const val DOM_STABLE_POLL_MS = 200L
        private const val GO_URL_TIMEOUT_MS = 3_000L
        private const val GO_POLL_MS = 100L
        private const val GO_LOAD_TIMEOUT_MS = 10_000L
        private const val HEADLESS_WIDTH_DP = 412
        private const val HEADLESS_HEIGHT_DP = 915
        private const val MAX_CONSOLE_LOGS = 200
        private const val DIALOG_TIMEOUT_MS = 30_000L
        const val MAX_TABS = 10

        private const val NIGHT_STYLE_ID = "__bicode_night_css__"
        private val NIGHT_BG_COLOR = 0xFF121212.toInt()

        /** 心跳保活 JS：每秒写时间戳；页面不可见被冻结后时间戳会停摆，读取端据此判断并唤醒。 */
        private const val KEEPALIVE_JS =
            "(function(){window.__bicodeKeepAlive={n:0,t:Date.now()};" +
                "setInterval(function(){var k=window.__bicodeKeepAlive;k.n++;k.t=Date.now();},1000);})()"

        /** 反色夜间样式：整页 invert + hue-rotate，图片/视频二次反色还原原始观感。 */
        private const val NIGHT_CSS =
            "html{filter:invert(1) hue-rotate(180deg);background:#fff}" +
                "img,video,picture,canvas,svg image,[style*=\"background-image\"]" +
                "{filter:invert(1) hue-rotate(180deg)}"
    }

    private class TabHolder(
        val id: String,
        val webView: WebView,
        var url: String = "",
        var title: String = "",
        var loading: Boolean = false,
        var error: String? = null,
        var devToolsOpen: Boolean = false,
        val consoleLogs: ArrayDeque<BrowserConsoleEntry> = ArrayDeque(),
        var pendingDialog: BrowserDialog? = null,
        var pendingDialogResult: JsResult? = null,
        var dialogTimeout: Runnable? = null,
        val pendingJsCalls: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap(),
        var loadDeferred: CompletableDeferred<Result<String>>? = null
    ) {
        fun toState(): BrowserTabState = BrowserTabState(
            id = id,
            url = url,
            title = title,
            loading = loading,
            error = error,
            devToolsOpen = devToolsOpen
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<TabHolder>()
    private var activeTabId: String = ""
    private var tabSeq = 1
    private var containerView: FrameLayout? = null
    private var hiddenHost: ViewGroup? = null

    /** App 外观是否为深色，由 UI 层通过 [setAppDarkTheme] 推送。 */
    private var appDarkTheme: Boolean = false

    /** 手动覆盖：null 表示跟随 App 外观，true/false 为用户在浏览器内的显式选择。 */
    private var nightModeOverride: Boolean? = null

    private val nightMode: Boolean get() = nightModeOverride ?: appDarkTheme

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** 面板不可见时仍需保活的标签：计时器被 WebView 暂停后由监督协程定期唤醒。 */
    private val keepAliveTabIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var supervisorJob: kotlinx.coroutines.Job? = null

    /** 每 60s 检查一次保活标签的心跳，冻结则唤醒；避免长时间不可见后连 reload 都超时。 */
    private fun startKeepAliveSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = supervisorScope.launch {
            while (isActive) {
                delay(60_000)
                if (containerView != null) continue
                if (keepAliveTabIds.isEmpty()) continue
                for (id in keepAliveTabIds) wakeIfFrozen(id)
            }
        }
    }

    private val supervisorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /**
     * 页面不可见（面板关闭/非活动标签）时 WebView 会暂停渲染层：JS 定时器降速到约 0.5 次/秒，
     * 5-7 分钟后完全冻结（实测），届时连 reload 都会超时。心跳读取 + 短暂强制可见可解除暂停。
     */
    private fun injectKeepAlive(tab: TabHolder) {
        runCatching { tab.webView.evaluateJavascript(KEEPALIVE_JS, null) }
    }

    /**
     * 若标签处于冻结状态（心跳停摆且面板未打开）则唤醒：强制 VISIBLE + 触发布局，
     * 让 WebView 恢复渲染管线后放回隐藏宿主。供后台监督协程与工具调用前调用。
     *
     * 心跳读取带 2s 超时：已冻结的 WebView 无法执行 JS（callback 永不回调），
     * 裸 await 会永久挂起阻塞监督协程——超时即判定冻结，直接进唤醒流程。
     */
    suspend fun wakeIfFrozen(tabId: String? = null) {
        if (containerView != null) return
        val tab = findTab(tabId ?: activeTabId) ?: return
        if (!keepAliveTabIds.contains(tab.id)) return
        val frozen = try {
            val before = withTimeout(2_000) {
                tab.webView.evaluateJavascriptSync(
                    "(function(){var k=window.__bicodeKeepAlive;return k?k.t:0})()"
                )
            }?.trim()?.trim('"')?.toLongOrNull()
            before == null || System.currentTimeMillis() - before > 10_000
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            true
        }
        if (!frozen) return
        runCatching {
            tab.webView.visibility = View.VISIBLE
            val density = appContext.resources.displayMetrics.density
            tab.webView.measure(
                View.MeasureSpec.makeMeasureSpec((HEADLESS_WIDTH_DP * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((HEADLESS_HEIGHT_DP * density).toInt(), View.MeasureSpec.EXACTLY)
            )
            tab.webView.layout(0, 0, (HEADLESS_WIDTH_DP * density).toInt(), (HEADLESS_HEIGHT_DP * density).toInt())
            tab.webView.requestLayout()
            tab.webView.invalidate()
            // 唤醒后重注入心跳：原注入可能失败（页面未加载完/JS 异常），
            // 不重注入会永久误判冻结、无限空转唤醒。
            injectKeepAlive(tab)
        }
    }

    /** 选择器辅助函数，prepend 到所有需要选择器的 JS 中。支持 ref= / text= / text*= / role= / xpath= / CSS。 */
    private val selectorHelper = """
        function __resolveSelector(selector){
            if(selector.startsWith('ref=')){
                var id=selector.substring(4);
                var reg=window.__bicodeRefs||{};
                var el=reg[id];
                return (el && el.isConnected) ? el : null;
            }
            if(selector.startsWith('text=')){
                var t=selector.substring(5);
                var els=document.querySelectorAll('*');
                for(var i=els.length-1;i>=0;i--){
                    var own=els[i].childNodes;
                    for(var j=0;j<own.length;j++){
                        if(own[j].nodeType===3&&own[j].textContent.trim()===t)return els[i];
                    }
                }
                for(var i=0;i<els.length;i++){
                    if(els[i].textContent.trim()===t)return els[i];
                }
                return null;
            }
            if(selector.startsWith('text*=')){
                var t=selector.substring(6);
                var els=document.querySelectorAll('*');
                var best=null,bestLen=Infinity;
                for(var i=0;i<els.length;i++){
                    var tag=els[i].tagName;
                    if(tag==='HTML'||tag==='BODY'||tag==='HEAD'||tag==='SCRIPT'||tag==='STYLE')continue;
                    var text=els[i].textContent||'';
                    if(text.includes(t)&&text.length<bestLen){
                        best=els[i];bestLen=text.length;
                    }
                }
                return best;
            }
            if(selector.startsWith('role=')){
                var m=selector.match(/^role=(\w+)(?:\[name="(.+)"\])?$/);
                if(!m)return null;
                var role=m[1],name=m[2];
                var els=document.querySelectorAll('[role="'+role+'"]');
                if(name){
                    for(var i=0;i<els.length;i++){
                        if(els[i].textContent.includes(name))return els[i];
                    }
                    return null;
                }
                return els[0]||null;
            }
            if(selector.startsWith('xpath=')){
                var x=selector.substring(6);
                return document.evaluate(x,document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue;
            }
            return document.querySelector(selector);
        }
    """.trimIndent()

    inner class BrowserJsBridge(private val tabId: String) {
        @JavascriptInterface
        fun resolve(callId: String, result: String) {
            val tab = findTab(tabId) ?: return
            tab.pendingJsCalls[callId]?.complete(result)
            tab.pendingJsCalls.remove(callId)
        }

        @JavascriptInterface
        fun reject(callId: String, error: String) {
            val tab = findTab(tabId) ?: return
            tab.pendingJsCalls[callId]?.completeExceptionally(RuntimeException(error))
            tab.pendingJsCalls.remove(callId)
        }
    }

    private fun findTab(tabId: String): TabHolder? = tabs.firstOrNull { it.id == tabId }

    private fun ensureActiveTab(): TabHolder {
        if (tabs.isEmpty()) {
            return createTabInternal("tab-${tabSeq++}", null)
        }
        return findTab(activeTabId) ?: tabs.first().also { activeTabId = it.id }
    }

    private fun resolveTab(tabId: String?): TabHolder {
        if (tabId.isNullOrBlank()) {
            return ensureActiveTab()
        }
        return findTab(tabId) ?: throw IllegalArgumentException("标签页不存在: $tabId")
    }

    private fun configureWebView(wv: WebView, tabId: String) {
        val tabHolderRef = { findTab(tabId) }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val tab = tabHolderRef() ?: return
                tab.loading = true
                tab.url = url.orEmpty()
                tab.title = ""
                tab.error = null
                view?.setBackgroundColor(if (nightMode) NIGHT_BG_COLOR else Color.WHITE)
                if (view != null && nightMode) applyNightModeTo(view)
                publishState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabHolderRef() ?: return
                tab.loading = false
                tab.url = url.orEmpty()
                if (view != null && nightMode) applyNightModeTo(view)
                if (view != null && tab.devToolsOpen) injectEruda(view, autoShow = false)
                val finish = {
                    tab.loadDeferred?.complete(Result.success(url.orEmpty()))
                    tab.loadDeferred = null
                    publishState()
                }
                if (view == null) {
                    finish()
                } else {
                    view.evaluateJavascript("(function(){return document.title})()") { result ->
                        val title = result?.trim()?.trim('"') ?: ""
                        tab.title = title
                        finish()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                val tab = tabHolderRef() ?: return
                if (request?.isForMainFrame == true) {
                    val msg = error?.description?.toString() ?: "未知错误"
                    tab.loading = false
                    tab.error = msg
                    tab.loadDeferred?.complete(Result.failure(RuntimeException(msg)))
                    tab.loadDeferred = null
                    publishState()
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view != null && nightMode && newProgress in 15..30) {
                    applyNightModeTo(view)
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val tab = tabHolderRef() ?: return true
                val level = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "error"
                    ConsoleMessage.MessageLevel.WARNING -> "warning"
                    ConsoleMessage.MessageLevel.DEBUG -> "debug"
                    else -> "log"
                }
                if (tab.consoleLogs.size >= MAX_CONSOLE_LOGS) tab.consoleLogs.removeFirst()
                tab.consoleLogs.addLast(BrowserConsoleEntry(level, msg.message(), msg.lineNumber(), msg.sourceId()))
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                tab.pendingDialog = BrowserDialog("alert", message.orEmpty(), null)
                tab.pendingDialogResult = null
                result.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                holdDialog(tab, "confirm", message.orEmpty(), null, result)
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val tab = tabHolderRef() ?: return false
                if (result == null) return false
                holdDialog(tab, "prompt", message.orEmpty(), defaultValue, result)
                return true
            }
        }

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // 支持导航/渲染 file:// 本地页面；该默认值随 targetSdk 变化，显式开启避免被静默关闭
        wv.settings.allowFileAccess = true
        wv.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.setBackgroundColor(if (nightMode) NIGHT_BG_COLOR else Color.WHITE)
        applyDarkThemeToSettings(wv)
        wv.addJavascriptInterface(BrowserJsBridge(tabId), "__browserBridge__")

        // 设置 Headless 默认尺寸，保证后台与离屏测试可用
        val density = appContext.resources.displayMetrics.density
        val width = (HEADLESS_WIDTH_DP * density).toInt()
        val height = (HEADLESS_HEIGHT_DP * density).toInt()
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, width, height)
    }

    private fun holdDialog(tab: TabHolder, type: String, message: String, defaultValue: String?, result: JsResult) {
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.pendingDialog = BrowserDialog(type, message, defaultValue)
        tab.pendingDialogResult = result
        val timeout = Runnable {
            if (tab.pendingDialogResult === result) {
                tab.pendingDialogResult = null
                tab.pendingDialog = null
                result.cancel()
            }
        }
        tab.dialogTimeout = timeout
        mainHandler.postDelayed(timeout, DIALOG_TIMEOUT_MS)
    }

    private fun createTabInternal(id: String, initialUrl: String?): TabHolder {
        val wv = WebView(appContext)
        configureWebView(wv, id)
        val tab = TabHolder(id = id, webView = wv)
        tabs.add(tab)
        activeTabId = id

        // 若当前前端面板未打开，自动挂到 hiddenHost 激活 Chromium 渲染管线
        if (containerView == null && hiddenHost != null) {
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            hiddenHost?.addView(wv, ViewGroup.LayoutParams(width, height))
        }

        publishState()
        updateContainerView()
        FileLogger.i(TAG, "Tab created: $id (initialUrl=$initialUrl)")
        return tab
    }

    private fun publishState() {
        _state.update {
            it.copy(
                tabs = tabs.map { t -> t.toState() },
                activeTabId = activeTabId,
                attached = containerView != null,
                nightMode = nightMode
            )
        }
    }

    // ================= 夜间模式 =================

    /** App 外观变化时调用，重算并应用到所有标签页。 */
    fun setAppDarkTheme(dark: Boolean) {
        if (appDarkTheme == dark) return
        appDarkTheme = dark
        applyNightMode()
    }

    /** 底栏手动切换；切回与 App 外观一致时自动恢复跟随。 */
    fun toggleNightMode() {
        val next = !nightMode
        nightModeOverride = if (next == appDarkTheme) null else next
        applyNightMode()
    }

    private fun applyNightMode() {
        val night = nightMode
        tabs.forEach { tab ->
            tab.webView.setBackgroundColor(if (night) NIGHT_BG_COLOR else Color.WHITE)
            applyDarkThemeToSettings(tab.webView)
            applyNightModeTo(tab.webView)
        }
        publishState()
    }

    /** 适配内核级暗色模式（开启 prefers-color-scheme: dark 支持）。 */
    private fun applyDarkThemeToSettings(wv: WebView) {
        val night = nightMode
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, night)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    wv.settings,
                    if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(
                        wv.settings,
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                    )
                }
            }
        } catch (e: Throwable) {
            FileLogger.w(TAG, "Failed to apply dark theme to WebSettings", e)
        }
    }

    /** 注入或移除夜间样式。带原生深色主题保护（对已支持深色的 GitHub 等网站避免反相破坏）。 */
    private fun applyNightModeTo(wv: WebView) {
        val js = if (nightMode) {
            """
            (function(){
                try {
                    var meta = document.querySelector('meta[name="color-scheme"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'color-scheme';
                        meta.content = 'dark';
                        (document.head || document.documentElement).appendChild(meta);
                    } else {
                        meta.content = 'dark';
                    }
                    if (document.documentElement) {
                        document.documentElement.style.colorScheme = 'dark';
                    }
                } catch(e){}

                var bg = '';
                try {
                    var el = document.body || document.documentElement;
                    if (el) bg = window.getComputedStyle(el).backgroundColor;
                } catch(e){}

                function isDark(c) {
                    if (!c || c === 'transparent' || c === 'rgba(0, 0, 0, 0)') return false;
                    var m = c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
                    if (!m) return false;
                    var r = parseInt(m[1]), g = parseInt(m[2]), b = parseInt(m[3]);
                    return (0.299 * r + 0.587 * g + 0.114 * b) < 128;
                }

                var id = '$NIGHT_STYLE_ID';
                var styleEl = document.getElementById(id);

                if (isDark(bg)) {
                    if (styleEl && styleEl.parentNode) styleEl.parentNode.removeChild(styleEl);
                    return;
                }

                if (!styleEl) {
                    styleEl = document.createElement('style');
                    styleEl.id = id;
                    styleEl.textContent = ${jsStringLiteral(NIGHT_CSS)};
                    (document.head || document.documentElement).appendChild(styleEl);
                }
            })()
            """.trimIndent()
        } else {
            """
            (function(){
                var id = '$NIGHT_STYLE_ID';
                var el = document.getElementById(id);
                if (el && el.parentNode) el.parentNode.removeChild(el);
                try {
                    var meta = document.querySelector('meta[name="color-scheme"]');
                    if (meta && meta.content === 'dark') meta.content = 'light';
                    if (document.documentElement) document.documentElement.style.colorScheme = '';
                } catch(e){}
            })()
            """.trimIndent()
        }
        wv.evaluateJavascript(js, null)
    }

    // ================= 开发者工具 =================

    private var erudaScriptCache: String? = null

    private fun loadErudaScript(): String {
        erudaScriptCache?.let { return it }
        val script = try {
            appContext.assets.open("scripts/eruda.min.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to read eruda.min.js from assets", e)
            ""
        }
        erudaScriptCache = script
        return script
    }

    private fun injectEruda(wv: WebView, autoShow: Boolean) {
        val script = loadErudaScript()
        if (script.isBlank()) return
        val showCall = if (autoShow) "try { window.eruda.show(); } catch(e){}" else ""
        val js = """
            (function() {
                if (window.eruda) {
                    $showCall
                    return;
                }
                try {
                    $script
                    window.eruda.init();
                    $showCall
                } catch(e) {
                    console.error('Failed to init Eruda', e);
                }
            })()
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    suspend fun toggleDevTools(tabId: String? = null): Boolean = withContext(Dispatchers.Main) {
        val targetId = tabId ?: activeTabId
        val tab = findTab(targetId) ?: return@withContext false
        val next = !tab.devToolsOpen
        tab.devToolsOpen = next

        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (e: Throwable) {
            FileLogger.w(TAG, "Failed to setWebContentsDebuggingEnabled", e)
        }

        if (next) {
            injectEruda(tab.webView, autoShow = true)
        } else {
            val hideJs = """
                (function() {
                    if (window.eruda) {
                        try {
                            window.eruda.destroy();
                        } catch(e) {
                            try { window.eruda.hide(); } catch(_){}
                        }
                    }
                })()
            """.trimIndent()
            tab.webView.evaluateJavascript(hideJs, null)
        }
        publishState()
        next
    }

    private fun updateContainerView() {
        val container = containerView ?: return
        val active = findTab(activeTabId) ?: return
        if (active.webView.parent === container) return

        container.removeAllViews()
        (active.webView.parent as? ViewGroup)?.removeView(active.webView)
        container.addView(
            active.webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 把其他非激活 Tab 放回 hiddenHost，保持渲染管线处于就绪状态
        hiddenHost?.let { host ->
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            tabs.forEach { t ->
                if (t.id != activeTabId && t.webView.parent !== host) {
                    (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                    host.addView(t.webView, ViewGroup.LayoutParams(width, height))
                }
            }
        }
    }

    /** 注册 Activity 级的隐藏宿主容器，让离屏 WebView 在后台也能触发 onAttachedToWindow 激活光栅化渲染。 */
    fun attachHiddenHost(host: ViewGroup) {
        hiddenHost = host
        val density = appContext.resources.displayMetrics.density
        val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
        val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
        tabs.forEach { tab ->
            if (tab.webView.parent == null) {
                host.addView(tab.webView, ViewGroup.LayoutParams(width, height))
            }
        }
    }

    fun detachHiddenHost() {
        hiddenHost?.removeAllViews()
        hiddenHost = null
    }

    /** Compose UI 挂载容器 */
    fun getOrCreateContainerView(context: Context): View {
        val container = containerView ?: FrameLayout(context).also {
            containerView = it
        }
        ensureActiveTab()
        updateContainerView()
        _state.update { it.copy(attached = true) }
        return container
    }

    /** 面板重新打开后取消全部保活监督：清空集合并停掉监督协程。 */
    fun attachVisible(tabId: String? = null) {
        keepAliveTabIds.clear()
        supervisorJob?.cancel()
        supervisorJob = null
    }

    fun detachFromViewHierarchy() {
        containerView?.removeAllViews()
        containerView = null
        _state.update { it.copy(attached = false) }

        // 前端面板关闭后，把当前激活 Tab 移回 hiddenHost 保持渲染管线激活，
        // 并注册保活：WebView 不可见 5-7 分钟后渲染层被冻结，监督协程会定期唤醒。
        hiddenHost?.let { host ->
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            findTab(activeTabId)?.let { active ->
                if (active.webView.parent !== host) {
                    (active.webView.parent as? ViewGroup)?.removeView(active.webView)
                    host.addView(active.webView, ViewGroup.LayoutParams(width, height))
                }
                keepAliveTabIds.add(active.id)
                injectKeepAlive(active)
            }
        }
        startKeepAliveSupervisor()
    }

    fun destroy() {
        keepAliveTabIds.clear()
        supervisorJob?.cancel()
        supervisorJob = null
        tabs.forEach { tab ->
            tab.webView.apply {
                stopLoading()
                removeJavascriptInterface("__browserBridge__")
                destroy()
            }
            tab.loadDeferred?.cancel()
            tab.pendingJsCalls.values.forEach { it.cancel() }
            tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
            tab.pendingDialogResult?.cancel()
            tab.consoleLogs.clear()
        }
        tabs.clear()
        containerView?.removeAllViews()
        containerView = null
        publishState()
    }

    fun isVisible(tabId: String? = null): Boolean {
        val tab = findTab(tabId ?: activeTabId) ?: return false
        val wv = tab.webView
        return wv.parent != null && wv.width > 0 && wv.height > 0
    }

    // ================= 多标签页管理 API =================

    suspend fun newTab(url: String? = null): String = withContext(Dispatchers.Main) {
        if (tabs.size >= MAX_TABS) {
            throw IllegalStateException("已达到最大标签页数量限制 ($MAX_TABS)")
        }
        val id = "tab-${tabSeq++}"
        val tab = createTabInternal(id, url)
        if (!url.isNullOrBlank()) {
            navigate(url, id)
        }
        id
    }

    suspend fun closeTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        val tab = findTab(tabId) ?: return@withContext false
        val index = tabs.indexOf(tab)
        tab.webView.apply {
            stopLoading()
            removeJavascriptInterface("__browserBridge__")
            destroy()
        }
        tab.loadDeferred?.cancel()
        tab.pendingJsCalls.values.forEach { it.cancel() }
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.pendingDialogResult?.cancel()
        tab.consoleLogs.clear()
        tabs.remove(tab)
        keepAliveTabIds.remove(tabId)

        if (tabs.isEmpty()) {
            // 所有标签都被关闭，自动重置为一个新的空白标签页
            createTabInternal("tab-${tabSeq++}", null)
        } else if (activeTabId == tabId) {
            val newIndex = (index - 1).coerceAtLeast(0)
            activeTabId = tabs[newIndex].id
        }

        updateContainerView()
        publishState()
        true
    }

    suspend fun selectTab(tabId: String): Boolean = withContext(Dispatchers.Main) {
        if (findTab(tabId) == null) return@withContext false
        // 面板关闭期间的标签切换要同步保活集合：新激活标签纳入保活，旧标签移出。
        if (containerView == null && keepAliveTabIds.isNotEmpty()) {
            keepAliveTabIds.remove(activeTabId)
            keepAliveTabIds.add(tabId)
        }
        activeTabId = tabId
        updateContainerView()
        publishState()
        true
    }

    fun listTabs(): List<BrowserTabState> = _state.value.tabs

    fun getActiveTabId(): String {
        ensureActiveTab()
        return activeTabId
    }

    // ================= 浏览器操作 API =================

    suspend fun navigate(url: String, tabId: String? = null): String {
        val target = url.trim()
        val resolved: String? = when {
            target.startsWith("http://") || target.startsWith("https://") -> target
            // 本地文件：file:// 或裸容器路径，映射为宿主真实文件后再交给 WebView
            isLocalPathInput(target) -> withContext(Dispatchers.IO) { resolveLocalFileUrl(target) }
            else -> null
        }
        return withContext(Dispatchers.Main) {
            val tab = resolveTab(tabId)
            if (resolved != null) {
                loadInto(tab, resolved)
            } else {
                // 无协议头：优先 https，SSL/连接失败再回退 http（部分站点未配置 SSL）
                try {
                    loadInto(tab, "https://$target")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadInto(tab, "http://$target")
                }
            }
        }
    }

    /** 输入是否为本地文件路径：`file://`，或以 `/`、`~`、`./`、`../` 开头的裸路径。 */
    private fun isLocalPathInput(input: String): Boolean =
        input.startsWith("file://") ||
            input.startsWith("/") ||
            input.startsWith("~") ||
            input.startsWith("./") ||
            input.startsWith("../")

    /**
     * 把本地文件输入解析为 WebView 可加载的 `file://` URL。
     *
     * WebView 跑在 App 宿主进程，只能读设备真实文件系统，看不到容器的 PRoot 视图。因此这里先按宿主真实路径
     * 查找（如 `/storage/emulated/0/...`），命中即用；否则经 [FileAccessProvider] 把容器路径（`~/workspace/...`、
     * `/etc/...`）映射为宿主真实文件——本地模式直接落到真实文件（相对子资源可正常加载），远程模式下载到临时文件
     * （仅页面本身可加载，相对子资源会失效）。解析失败时回退为原路径的 `file://`，交由 WebView 报错。
     */
    private fun resolveLocalFileUrl(input: String): String {
        val rawPath = if (input.startsWith("file://")) Uri.decode(input.removePrefix("file://")) else input
        if (rawPath.isBlank()) return input
        val expanded = pathHomeResolver.expandHome(rawPath)
        if (expanded.startsWith("/")) {
            val direct = File(expanded)
            if (direct.exists()) return Uri.fromFile(direct).toString()
        }
        val host = try {
            fileAccess.copyToLocal(rawPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "本地文件路径解析失败，按原路径加载: $rawPath", e)
            return Uri.fromFile(File(expanded)).toString()
        }
        return Uri.fromFile(host).toString()
    }

    private suspend fun loadInto(tab: TabHolder, finalUrl: String): String {
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        tab.webView.loadUrl(finalUrl)
        return withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrThrow()
    }

    suspend fun evaluateJavaScript(script: String, tabId: String? = null): String? = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        tab.pendingJsCalls[callId] = deferred

        // 彻底废除 eval，改用 async IIFE 包装并直接 await。
        // 单表达式自动补 return（支持 Promise 自动展开等待，且不受 CSP unsafe-eval 限制）；
        // 语句体（顶层含 ; / 换行或以语句关键字开头）原样放入函数体，值由脚本自行 return。
        val trimmed = script.trim()
        val body = if (isExpressionScript(trimmed)) {
            val expr = trimmed.trimEnd().removeSuffix(";").trimEnd()
            "return ($expr);"
        } else {
            trimmed
        }

        val wrapped = """
            (function(){
                var callId = ${jsStringLiteral(callId)};
                (async function(){
                    $body
                })().then(function(r){
                    var json;
                    try { json = JSON.stringify(r !== undefined ? r : null); } catch(e) { json = JSON.stringify(String(r)); }
                    __browserBridge__.resolve(callId, json);
                }).catch(function(e){
                    __browserBridge__.reject(callId, (e && e.message) ? e.message : String(e));
                });
            })();
        """.trimIndent()

        tab.webView.evaluateJavascript(wrapped) { }
        try {
            withTimeout(EVAL_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            tab.pendingJsCalls.remove(callId)
            throw e
        }
    }

    suspend fun clickElement(selector: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 步骤 1：先在 JS 层面解析元素、平滑滚动到视口中心并获取视口几何坐标
        val prepareScript = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                try {
                    el.scrollIntoView({behavior:'instant',block:'center',inline:'center'});
                } catch(e){}
                var rect = el.getBoundingClientRect();
                var before = location.href;
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    text: (el.textContent || '').trim().substring(0, 100),
                    href: el.href || null,
                    x: rect.left + rect.width / 2,
                    y: rect.top + rect.height / 2,
                    w: rect.width,
                    h: rect.height,
                    beforeUrl: before
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()

        val prepJson = wv.evaluateJavascriptSync(prepareScript) ?: "{}"
        val prep = parseEvalResult(prepJson) as? JsonObject
        val matched = (prep?.get("matched") as? JsonPrimitive)?.content != "false"
        if (!matched) return@withContext """{"matched":false}"""

        val x = (prep?.get("x") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        val y = (prep?.get("y") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        val w = (prep?.get("w") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        val h = (prep?.get("h") as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
        val beforeUrl = (prep?.get("beforeUrl") as? JsonPrimitive)?.content.orEmpty()
        val density = appContext.resources.displayMetrics.density

        // 步骤 2：若元素具有可见尺寸，使用 Android 原生 MotionEvent 模拟真实物理触摸（isTrusted: true）
        val nativeClicked = if (w > 0f && h > 0f) {
            val clickX = x * density
            val clickY = y * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, clickX, clickY, 0)
            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, clickX, clickY, 0)
            val downHandled = wv.dispatchTouchEvent(down)
            delay(50)
            val upHandled = wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            downHandled || upHandled
        } else false

        // 步骤 3：若物理点击未生效或元素尺寸为 0，回退到 JS dispatchEvent
        if (!nativeClicked) {
            val fallbackScript = """
                $selectorHelper
                (function(sel){
                    var el = __resolveSelector(sel);
                    if(!el) return;
                    var events = ['mouseover','mousedown','mouseup','click'];
                    for(var i=0;i<events.length;i++){
                        el.dispatchEvent(new MouseEvent(events[i],{
                            view: window, bubbles: true, cancelable: true, buttons: 1
                        }));
                    }
                })(${jsStringLiteral(selector)})
            """.trimIndent()
            wv.evaluateJavascriptSync(fallbackScript)
        }

        // 等待 300ms 检测是否有 SPA 路由切换
        delay(300)
        val afterUrl = wv.url.orEmpty()
        val navigatedTo = if (afterUrl.isNotEmpty() && afterUrl != beforeUrl) afterUrl else null

        val tag = (prep?.get("tag") as? JsonPrimitive)?.content
        val text = (prep?.get("text") as? JsonPrimitive)?.content
        val href = (prep?.get("href") as? JsonPrimitive)?.content

        JsonObject(mapOf(
            "matched" to JsonPrimitive(true),
            "tag" to (tag?.let { JsonPrimitive(it) } ?: JsonNull),
            "text" to (text?.let { JsonPrimitive(it) } ?: JsonNull),
            "href" to (href?.let { JsonPrimitive(it) } ?: JsonNull),
            "navigatedTo" to (navigatedTo?.let { JsonPrimitive(it) } ?: JsonNull)
        )).toString()
    }

    suspend fun fillElement(selector: String, value: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                if(el.tagName === 'SELECT'){
                    var opt = null;
                    for(var i=0;i<el.options.length;i++){
                        var o = el.options[i];
                        if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                    }
                    if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                    el.value = opt.value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return JSON.stringify({matched:true, tag:'select', value:el.value});
                }
                var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                ).set || (Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ) && Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                ).set);
                if(setter){ setter.call(el, val); }
                else { el.value = val; }
                if(el._valueTracker){ el._valueTracker.setValue(''); }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    type: el.type || null,
                    value: el.value
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        tab.webView.evaluateJavascriptSync(js) ?: "{}"
    }

    suspend fun selectOption(selector: String, value: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel, val){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false, reason:'not-found'});
                if(el.tagName !== 'SELECT') return JSON.stringify({matched:false, reason:'not-select'});
                var opt = null;
                for(var i=0;i<el.options.length;i++){
                    var o = el.options[i];
                    if(o.value === val || (o.textContent||'').trim() === val){ opt = o; break; }
                }
                if(!opt) return JSON.stringify({matched:false, reason:'option-not-found'});
                el.value = opt.value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({
                    matched: true,
                    tag: 'select',
                    value: el.value,
                    text: (opt.textContent||'').trim()
                });
            })(${jsStringLiteral(selector)}, ${jsStringLiteral(value)})
        """.trimIndent()
        tab.webView.evaluateJavascriptSync(js) ?: "{}"
    }

    fun pendingDialogInfo(tabId: String? = null): BrowserDialog? = findTab(tabId ?: activeTabId)?.pendingDialog

    suspend fun resolveDialog(accept: Boolean, text: String?, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = findTab(tabId ?: activeTabId)
        val result = tab?.pendingDialogResult
        val dialog = tab?.pendingDialog
        if (result == null || dialog == null) {
            return@withContext """{"handled":false,"reason":"no-dialog"}"""
        }
        tab.dialogTimeout?.let { mainHandler.removeCallbacks(it) }
        tab.dialogTimeout = null
        tab.pendingDialogResult = null
        tab.pendingDialog = null
        try {
            if (accept) {
                if (dialog.type == "prompt" && result is JsPromptResult) {
                    result.confirm(text ?: dialog.defaultValue ?: "")
                } else {
                    result.confirm()
                }
            } else {
                result.cancel()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "resolveDialog failed", e)
        }
        """{"handled":true,"type":"${dialog.type}","accepted":$accept}"""
    }

    suspend fun getConsoleLogs(level: String?, clear: Boolean, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val filtered = if (level.isNullOrBlank()) tab.consoleLogs.toList()
        else tab.consoleLogs.filter { it.level.equals(level, ignoreCase = true) }
        val logs = filtered.joinToString(",") { e ->
            JsonObject(mapOf(
                "level" to JsonPrimitive(e.level),
                "message" to JsonPrimitive(e.message),
                "line" to JsonPrimitive(e.line),
                "source" to JsonPrimitive(e.source)
            )).toString()
        }
        if (clear) tab.consoleLogs.clear()
        """{"count":${filtered.size},"logs":[$logs]}"""
    }

    suspend fun hoverElement(selector: String, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            $selectorHelper
            (function(sel){
                var el = __resolveSelector(sel);
                if(!el) return JSON.stringify({matched:false});
                var events = ['mouseenter','mouseover','mousemove'];
                for(var i=0;i<events.length;i++){
                    el.dispatchEvent(new MouseEvent(events[i],{
                        view: window, bubbles: true, cancelable: true
                    }));
                }
                return JSON.stringify({
                    matched: true,
                    tag: el.tagName ? el.tagName.toLowerCase() : null,
                    text: (el.textContent || '').trim().substring(0, 100)
                });
            })(${jsStringLiteral(selector)})
        """.trimIndent()
        val result = tab.webView.evaluateJavascriptSync(js)
        result ?: "{}"
    }

    suspend fun pressKey(key: String, tabId: String? = null): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            (function(key){
                var keyMap = {
                    'Enter': {key:'Enter',code:'Enter',keyCode:13},
                    'Escape': {key:'Escape',code:'Escape',keyCode:27},
                    'Tab': {key:'Tab',code:'Tab',keyCode:9},
                    'ArrowUp': {key:'ArrowUp',code:'ArrowUp',keyCode:38},
                    'ArrowDown': {key:'ArrowDown',code:'ArrowDown',keyCode:40},
                    'ArrowLeft': {key:'ArrowLeft',code:'ArrowLeft',keyCode:37},
                    'ArrowRight': {key:'ArrowRight',code:'ArrowRight',keyCode:39},
                    'Backspace': {key:'Backspace',code:'Backspace',keyCode:8},
                    'Delete': {key:'Delete',code:'Delete',keyCode:46},
                    ' ': {key:' ',code:'Space',keyCode:32}
                };
                var k = keyMap[key] || {key:key,code:key,keyCode:key.charCodeAt(0)};
                var target = document.activeElement || document.body;
                ['keydown','keypress','keyup'].forEach(function(type){
                    target.dispatchEvent(new KeyboardEvent(type, {
                        key: k.key, code: k.code, keyCode: k.keyCode,
                        bubbles: true, cancelable: true
                    }));
                });
                return true;
            })(${jsStringLiteral(key)})
        """.trimIndent()
        val result = tab.webView.evaluateJavascriptSync(js)
        result == "true"
    }

    suspend fun getText(selector: String? = null, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 异步渲染沉淀等待：若页面仍处于加载态，等待最多 2000ms
        if (tab.loading) {
            val deadline = System.currentTimeMillis() + 2_000L
            while (tab.loading && System.currentTimeMillis() < deadline) {
                delay(100)
            }
        }

        val js = if (selector != null) {
            """
            $selectorHelper
            (function(){
                var el=__resolveSelector(${jsStringLiteral(selector)});
                if(!el) return '';
                var t=el.innerText;
                if(t&&t.trim())return t;
                var clone=el.cloneNode(true);
                var junk=clone.querySelectorAll('script,style,noscript,template');
                for(var i=0;i<junk.length;i++){junk[i].parentNode.removeChild(junk[i]);}
                return clone.textContent||'';
            })()
            """.trimIndent()
        } else {
            "(function(){return document.body?document.body.innerText:''})()"
        }

        var result = wv.evaluateJavascriptSync(js)
        var text = unescapeJsString(result).trim()

        // 若正文为空且页面可能在异步挂载中，等待最多 1000ms 沉淀
        if (text.isEmpty() && selector == null) {
            val deadline = System.currentTimeMillis() + 1_000L
            while (text.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(150)
                result = wv.evaluateJavascriptSync(js)
                text = unescapeJsString(result).trim()
            }
        }

        text
    }

    suspend fun getHtml(selector: String? = null, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 异步渲染沉淀等待：若页面仍处于加载态，等待最多 2000ms
        if (tab.loading) {
            val deadline = System.currentTimeMillis() + 2_000L
            while (tab.loading && System.currentTimeMillis() < deadline) {
                delay(100)
            }
        }

        val js = if (selector != null) {
            "$selectorHelper\n(function(){var el=__resolveSelector(${jsStringLiteral(selector)});return el?el.outerHTML:''})()"
        } else {
            "(function(){return document.documentElement?document.documentElement.outerHTML:''})()"
        }
        val result = wv.evaluateJavascriptSync(js)
        val raw = unescapeJsString(result)
        if (raw.length > MAX_CONTENT_CHARS) raw.take(MAX_CONTENT_CHARS) + "\n\n[网页内容超长，已截断...]" else raw
    }

    suspend fun getBackbone(maxDepth: Int = 15, tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = """
            (function(maxDepth, maxNodes){
                var SKIP = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, LINK:1, META:1, HEAD:1};
                var ROLE_BY_TAG = {
                    A:'link', BUTTON:'button', TEXTAREA:'textbox', SELECT:'combobox', OPTION:'option',
                    IMG:'img', H1:'heading', H2:'heading', H3:'heading', H4:'heading', H5:'heading', H6:'heading',
                    UL:'list', OL:'list', LI:'listitem', DL:'list', DT:'term', DD:'definition',
                    TABLE:'table', TR:'row', TD:'cell', TH:'columnheader',
                    THEAD:'rowgroup', TBODY:'rowgroup', TFOOT:'rowgroup',
                    NAV:'navigation', HEADER:'banner', FOOTER:'contentinfo', ASIDE:'complementary',
                    MAIN:'main', FORM:'form', ARTICLE:'article', SECTION:'generic', DIALOG:'dialog',
                    SUMMARY:'button', DETAILS:'group', FIGURE:'figure', FIGCAPTION:'caption',
                    PROGRESS:'progressbar', P:'paragraph', BLOCKQUOTE:'blockquote',
                    PRE:'code', CODE:'code', HR:'separator', IFRAME:'iframe',
                    VIDEO:'video', AUDIO:'audio', OUTPUT:'status', TIME:'time'
                };
                var INPUT_ROLE = {
                    text:'textbox', search:'searchbox', email:'textbox', tel:'textbox', url:'textbox',
                    password:'textbox', number:'spinbutton', checkbox:'checkbox', radio:'radio',
                    range:'slider', button:'button', submit:'button', reset:'button', file:'button', color:'button'
                };
                var CONTENT_NAME = {
                    link:1, button:1, heading:1, cell:1, columnheader:1, rowheader:1, listitem:1,
                    paragraph:1, menuitem:1, tab:1, option:1, term:1, definition:1, caption:1
                };
                var INTERACTIVE = {
                    link:1, button:1, textbox:1, searchbox:1, checkbox:1, radio:1, combobox:1,
                    slider:1, spinbutton:1, menuitem:1, tab:1, option:1, switch:1
                };
                var refs = {};
                var refCount = 0;
                var count = 0;
                var budgetHit = false;

                function norm(s){ return (s || '').replace(/\s+/g, ' ').trim(); }
                function roleOf(el){
                    var explicit = el.getAttribute('role');
                    if (explicit) { var r = explicit.trim().split(/\s+/)[0]; if (r) return r; }
                    var tag = el.tagName;
                    if (tag === 'INPUT') return INPUT_ROLE[(el.type || 'text').toLowerCase()] || 'textbox';
                    return ROLE_BY_TAG[tag] || 'generic';
                }
                function isVisible(el){
                    if (el.hidden) return false;
                    if (el.getAttribute('aria-hidden') === 'true') return false;
                    if (el.style && (el.style.display === 'none' || el.style.visibility === 'hidden')) return false;
                    if (typeof el.checkVisibility === 'function') {
                        try { return el.checkVisibility({ checkVisibilityCSS: true, checkOpacity: false }); } catch (e) {}
                    }
                    var cs = getComputedStyle(el);
                    return cs.display !== 'none' && cs.visibility !== 'hidden';
                }
                function accessibleName(el, role){
                    var lb = el.getAttribute('aria-labelledby');
                    if (lb) {
                        var parts = [];
                        lb.trim().split(/\s+/).forEach(function(id){
                            var r = document.getElementById(id);
                            if (r) parts.push(r.textContent || '');
                        });
                        var byLabel = norm(parts.join(' '));
                        if (byLabel) return byLabel;
                    }
                    var al = el.getAttribute('aria-label');
                    if (al && norm(al)) return norm(al);
                    var tag = el.tagName;
                    if (tag === 'IMG') { var alt = el.getAttribute('alt'); if (alt && norm(alt)) return norm(alt); }
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                        var ph = el.getAttribute('placeholder');
                        if (ph && norm(ph)) return norm(ph);
                        if (el.id) {
                            var lab = document.querySelector('label[for="' + el.id + '"]');
                            if (lab && norm(lab.textContent)) return norm(lab.textContent);
                        }
                        if (el.value != null && norm(String(el.value))) return norm(String(el.value));
                    }
                    if (CONTENT_NAME[role]) { var t = norm(el.textContent); if (t) return t; }
                    var ti = el.getAttribute('title');
                    if (ti && norm(ti)) return norm(ti);
                    if (el.childElementCount === 0) { var own = norm(el.textContent); if (own) return own; }
                    return '';
                }
                function walk(el, depth){
                    if (!el || !el.tagName) return [];
                    var tag = el.tagName;
                    if (SKIP[tag]) return [];
                    if (!isVisible(el)) return [];
                    var role = roleOf(el);
                    if (depth > maxDepth) {
                        return [{ role: role, truncated: true, childCount: el.children.length }];
                    }
                    if (count >= maxNodes) { budgetHit = true; return []; }
                    var name = accessibleName(el, role);
                    var kids = [];
                    for (var i = 0; i < el.children.length; i++) {
                        var sub = walk(el.children[i], depth + 1);
                        for (var k = 0; k < sub.length; k++) kids.push(sub[k]);
                    }
                    if (role === 'generic' && !name) return kids;
                    count++;
                    var node = { role: role };
                    if (name) node.name = name.substring(0, 200);
                    if (role === 'generic' && el.childElementCount === 0) node.role = 'text';
                    if (role === 'heading') { var lv = parseInt(tag.charAt(1), 10); if (lv) node.level = lv; }
                    if (INTERACTIVE[role]) {
                        refCount++;
                        var ref = 'e' + refCount;
                        refs[ref] = el;
                        node.ref = ref;
                    }
                    if (el.getAttribute) {
                        var href = el.getAttribute('href');
                        if (href && (role === 'link' || role === 'menuitem' || role === 'tab')) node.url = href;
                    }
                    if ((tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')
                        && el.value != null && String(el.value)) node.value = String(el.value).substring(0, 200);
                    if (tag === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) node.checked = !!el.checked;
                    else if (el.getAttribute('aria-checked')) node.checked = el.getAttribute('aria-checked') === 'true';
                    if (el.getAttribute('aria-expanded')) node.expanded = el.getAttribute('aria-expanded') === 'true';
                    if (el.disabled || el.getAttribute('aria-disabled') === 'true') node.disabled = true;
                    if (kids.length) node.children = kids;
                    return [node];
                }
                window.__bicodeRefs = refs;
                var out = { role: 'document' };
                var top = walk(document.body, 0);
                if (top.length) out.children = top;
                if (budgetHit) out.budgetExceeded = true;
                return JSON.stringify(out);
            })($maxDepth, $MAX_BACKBONE_NODES)
        """.trimIndent()
        val result = tab.webView.evaluateJavascriptSync(js)
        unescapeJsString(result)
    }

    suspend fun wait(condition: String, timeoutMs: Long = 10_000, tabId: String? = null): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        when {
            condition == "domStable" -> waitForDomStable(tab, timeoutMs)
            condition.startsWith("text=") -> {
                val text = condition.substring(5)
                waitForCondition(tab, "var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){if(els[i].textContent.trim()===${jsStringLiteral(text)})return true;}return false;", timeoutMs)
            }
            condition.startsWith("text*=") -> {
                val text = condition.substring(6)
                waitForCondition(tab, "return document.body&&document.body.innerText.includes(${jsStringLiteral(text)});", timeoutMs)
            }
            condition.startsWith("selector=") -> {
                val sel = condition.substring(9)
                waitForCondition(tab, "$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(sel)});", timeoutMs)
            }
            else -> {
                waitForCondition(tab, "$selectorHelper\nreturn !!__resolveSelector(${jsStringLiteral(condition)});", timeoutMs)
            }
        }
    }

    private suspend fun waitForCondition(tab: TabHolder, checkJs: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val wrapped = "(function(){try{$checkJs}catch(e){return false}})()"
        while (System.currentTimeMillis() < deadline) {
            val result = tab.webView.evaluateJavascriptSync(wrapped)
            if (result == "true") return true
            delay(WAIT_POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun waitForDomStable(tab: TabHolder, timeoutMs: Long = 5_000): Boolean {
        val sigJs = "(function(){var b=document.body||document.documentElement;" +
            "return b?b.getElementsByTagName('*').length+':'+b.innerHTML.length:'0:0'})()"
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val sig = tab.webView.evaluateJavascriptSync(sigJs)?.trim('"') ?: ""
            val now = System.currentTimeMillis()
            if (sig.isNotEmpty() && sig == last) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= DOM_STABLE_QUIET_MS) return true
            } else {
                last = sig
                stableSince = 0L
            }
            delay(DOM_STABLE_POLL_MS)
        }
        return false
    }

    suspend fun scroll(selector: String? = null, x: Int? = null, y: Int? = null, tabId: String? = null): String =
        withContext(Dispatchers.Main) {
            val tab = resolveTab(tabId)
            val beforeJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0})})()"
            val beforeResult = tab.webView.evaluateJavascriptSync(beforeJs)
            val before = unescapeJsString(beforeResult)

            val actionJs = when {
                selector != null -> """
                    $selectorHelper
                    (function(){var el=__resolveSelector(${jsStringLiteral(selector)});
                    if(!el) return false;
                    el.scrollIntoView({behavior:'smooth',block:'center'});
                    return true;})()
                """.trimIndent()
                x != null || y != null -> "(function(){window.scrollBy(${x ?: 0}, ${y ?: 0});return true;})()"
                else -> "(function(){window.scrollTo(0,document.body?document.body.scrollHeight:0);return true;})()"
            }
            tab.webView.evaluateJavascriptSync(actionJs)

            delay(300)
            val afterJs = "(function(){return JSON.stringify({y:window.scrollY||0,x:window.scrollX||0,atTop:(window.scrollY||0)===0,atBottom:(window.innerHeight+(window.scrollY||0))>=(document.body?document.body.scrollHeight:0)})})()"
            val afterResult = tab.webView.evaluateJavascriptSync(afterJs)
            val after = unescapeJsString(afterResult)

            """{"from":$before,"to":$after}"""
        }

    suspend fun screenshot(tabId: String? = null): AgentImage? = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView

        // 离屏兜底尺寸：若未被系统布局过（w/h <= 0），手动测量布局
        if (wv.width <= 0 || wv.height <= 0) {
            val density = appContext.resources.displayMetrics.density
            val width = (HEADLESS_WIDTH_DP * density).toInt().coerceAtLeast(1)
            val height = (HEADLESS_HEIGHT_DP * density).toInt().coerceAtLeast(1)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, width, height)
        }
        if (wv.width <= 0 || wv.height <= 0) return@withContext null

        // 等待视觉状态就绪（API 23+ 原生机制，保证光栅化合成完毕）
        waitForVisualState(wv)

        val bitmap = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 关键：WebView 处于硬件层时，software Canvas 回读不到 GPU 帧缓冲——离屏未挂载、或挂载到窗口（打开过浏览器面板）都会白屏；
        // 因此截图期间一律临时切为 LAYER_TYPE_SOFTWARE 遍历 display list 真实绘制到 Bitmap，画完还原。
        val oldLayerType = wv.layerType
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        try {
            wv.draw(canvas)
        } finally {
            wv.setLayerType(oldLayerType, null)
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, baos)
        bitmap.recycle()
        AgentImage(mimeType = "image/jpeg", base64Data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
    }

    private suspend fun waitForVisualState(wv: WebView, timeoutMs: Long = 500L) {
        val deferred = CompletableDeferred<Unit>()
        wv.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                deferred.complete(Unit)
            }
        })
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            // 超时兜底，直接继续绘制
        }
    }

    suspend fun screenshotIfVisible(tabId: String? = null): AgentImage? =
        if (isVisible(tabId)) screenshot(tabId) else null

    suspend fun getViewportInfo(tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val js = "(function(){return JSON.stringify({w:window.innerWidth,h:window.innerHeight,dpr:window.devicePixelRatio||1,scrollY:window.scrollY||0,scrollHeight:document.body?document.body.scrollHeight:0})})()"
        unescapeJsString(tab.webView.evaluateJavascriptSync(js))
    }

    suspend fun goBack(tabId: String? = null): Boolean =
        navigateHistory(tabId, { it.canGoBack() }) { it.goBack() }

    suspend fun goForward(tabId: String? = null): Boolean =
        navigateHistory(tabId, { it.canGoForward() }) { it.goForward() }

    private suspend fun navigateHistory(
        tabId: String?,
        canGo: (WebView) -> Boolean,
        action: (WebView) -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val wv = tab.webView
        if (!canGo(wv)) return@withContext false
        val before = wv.url.orEmpty()
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        action(wv)

        val urlDeadline = System.currentTimeMillis() + GO_URL_TIMEOUT_MS
        var after = before
        while (after == before && System.currentTimeMillis() < urlDeadline) {
            delay(GO_POLL_MS)
            after = wv.url.orEmpty()
        }
        if (after == before) {
            val href = currentHref(tab)
            if (href.isNotEmpty() && href != before) after = href
        }
        if (after == before) {
            if (tab.loadDeferred === deferred) tab.loadDeferred = null
            return@withContext false
        }
        if (tab.loading) {
            try {
                withTimeout(GO_LOAD_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                // 同文档导航不会触发 onPageFinished，超时后直接继续
            }
        }
        if (tab.loadDeferred === deferred) tab.loadDeferred = null
        syncTitle(tab)
        true
    }

    private suspend fun currentHref(tab: TabHolder): String {
        return unescapeJsString(tab.webView.evaluateJavascriptSync("(function(){return location.href})()"))
    }

    private suspend fun syncTitle(tab: TabHolder) {
        val raw = tab.webView.evaluateJavascriptSync("(function(){return document.title})()")
        val title = raw?.trim()?.trim('"') ?: ""
        tab.title = title
        publishState()
    }

    suspend fun reload(tabId: String? = null): String = withContext(Dispatchers.Main) {
        val tab = resolveTab(tabId)
        val deferred = CompletableDeferred<Result<String>>()
        tab.loadDeferred = deferred
        tab.webView.reload()
        withTimeout(NAVIGATE_TIMEOUT_MS) { deferred.await() }.getOrThrow()
    }

    fun getUrl(tabId: String? = null): String = findTab(tabId ?: activeTabId)?.url ?: _state.value.url
    fun getTitle(tabId: String? = null): String = findTab(tabId ?: activeTabId)?.title ?: _state.value.title

    fun parseEvalResult(raw: String?): JsonElement {
        if (raw.isNullOrBlank()) return JsonNull
        return try {
            val element = Json.parseToJsonElement(raw)
            if (element is JsonPrimitive && element.isString) {
                try { Json.parseToJsonElement(element.content) } catch (e: Exception) { element }
            } else {
                element
            }
        } catch (e: Exception) {
            JsonPrimitive(raw)
        }
    }

    private suspend fun WebView.evaluateJavascriptSync(script: String): String? {
        val deferred = CompletableDeferred<String?>()
        evaluateJavascript(script) { result -> deferred.complete(result) }
        return deferred.await()
    }

    /**
     * 判断脚本是否为单个表达式（可直接 `return (script)` 取值）。
     * 仅看顶层（括号/花括号深度 0）是否出现 `;` 或换行——IIFE 内部语句的 `;` 不受影响。
     */
    private fun isExpressionScript(script: String): Boolean {
        val s = script.trimEnd().removeSuffix(";").trimEnd()
        if (s.isEmpty()) return false
        val statementStarts = listOf(
            "return", "var", "let", "const", "if", "for", "while", "function",
            "switch", "throw", "try", "do", "class", "import", "export"
        )
        if (statementStarts.any { s == it || s.startsWith("$it ") || s.startsWith("$it(") || s.startsWith("$it{") }) return false
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> {
                    if (c == '\\') i++ else if (c == quote) quote = null
                }
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ';' && depth == 0 -> return false
                c == '\n' && depth == 0 -> return false
            }
            i++
        }
        return true
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    private fun unescapeJsString(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var result = s.trim()
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length - 1)
        }
        return result
            .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")
    }
}
