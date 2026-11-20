package com.aicode.feature.plugin.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginOverlayPermissionRepository
import com.aicode.feature.plugin.data.PluginOverlayPosition
import com.aicode.feature.plugin.data.PluginOverlayPositionRepository
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOverlaySurface
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.presentation.PluginWebBridgeInstaller
import com.aicode.feature.plugin.presentation.PluginWebViewFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 插件浮层创建或操作失败；调用方转成 host API 错误码。 */
class PluginOverlayException(val code: String, message: String) : Exception(message)

/**
 * 插件系统悬浮窗的唯一宿主。
 *
 * 窗口、WebView、权限和生命周期都由这里持有；插件只拿到 overlayId 与消息通道，
 * 不能直接接触 WindowManager、Binder 或 Surface。
 */
@Singleton
class PluginOverlayManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val permissionService: com.aicode.feature.plugin.runtime.PluginPermissionService,
    private val hostApiDispatcher: dagger.Lazy<com.aicode.feature.plugin.runtime.PluginHostApiDispatcher>,
    private val permissionRepository: PluginOverlayPermissionRepository,
    private val positionRepository: PluginOverlayPositionRepository,
    private val appForegroundMonitor: AppForegroundMonitor
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val windows = ConcurrentHashMap<String, PluginOverlayWindow>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val createMutex = Mutex()

    init {
        scope.launch {
            appForegroundMonitor.isForeground.collect { foreground ->
                windows.values.forEach { window ->
                    window.onAppForegroundChanged(foreground)
                    window.postMessage(
                        buildJsonObject {
                            put("type", "appState")
                            put("foreground", foreground)
                        }
                    )
                }
            }
        }
    }

    @Volatile
    private var serviceStarted = false

    /** 屏幕点亮状态；熄屏时隐藏并暂停所有浮层 WebView。 */
    @Volatile
    private var screenOn: Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** 浮层页面发给插件进程的消息；由 PluginRuntimeManager 在初始化时接入。 */
    @Volatile
    private var eventSink: ((pluginId: String, overlayId: String, payload: JsonElement) -> Unit)? = null

    fun setEventSink(sink: ((pluginId: String, overlayId: String, payload: JsonElement) -> Unit)?) {
        eventSink = sink
    }

    fun onServiceStarted() {
        serviceStarted = true
    }

    fun onServiceStopped() {
        serviceStarted = false
        closeAllInternal()
    }

    /** 由前台上报锁屏状态：熄屏立即隐藏并暂停浮层，亮屏后各窗口按自身策略恢复。 */
    fun onScreenStateChanged(isOn: Boolean) {
        if (screenOn == isOn) return
        screenOn = isOn
        scope.launch {
            windows.values.forEach { window -> window.onScreenStateChanged(isOn) }
        }
    }

    fun hasOverlays(): Boolean = windows.isNotEmpty()

    fun isAppForeground(): Boolean = appForegroundMonitor.current()

    suspend fun create(
        pluginId: String,
        surfaceId: String?,
        params: JsonObject
    ): JsonObject = createMutex.withLock {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginOverlayException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val declaration = plugin.content.overlay
            ?: throw PluginOverlayException("OVERLAY_NOT_DECLARED", "plugin did not declare overlay")
        val surface = when {
            surfaceId.isNullOrBlank() -> declaration.surfaces.firstOrNull()
            else -> declaration.surfaces.firstOrNull { it.localId == surfaceId }
        } ?: throw PluginOverlayException("SURFACE_NOT_FOUND", "overlay surface not found: $surfaceId")
        if (!permissionRepository.isGranted(pluginId)) {
            throw PluginOverlayException("OVERLAY_NOT_AUTHORIZED", "user did not grant overlay for this plugin")
        }
        if (!Settings.canDrawOverlays(context)) {
            throw PluginOverlayException("OVERLAY_SYSTEM_PERMISSION_MISSING", "system overlay permission is not granted")
        }
        val widthDp = readSize(params, "width", surface.width, PluginOverlayLimits.MAX_WIDTH_DP)
        val heightDp = readSize(params, "height", surface.height, PluginOverlayLimits.MAX_HEIGHT_DP)
        val visibilityPolicy = readVisibilityPolicy(params, "visibilityPolicy", "always")
        val savedPosition = positionRepository.get(pluginId, surface.localId)
        windows[surface.key]?.let { existing ->
            if (existing.visibilityPolicy() != visibilityPolicy) {
                mainHandler.post { existing.setVisibilityPolicy(visibilityPolicy) }
            }
            return@withLock existing.describe()
        }
        val perPlugin = windows.values.count { it.pluginId == pluginId }
        if (perPlugin >= MAX_SURFACES_PER_PLUGIN) {
            throw PluginOverlayException("OVERLAY_LIMIT", "too many overlays for this plugin")
        }
        if (windows.size >= MAX_SURFACES_TOTAL) {
            throw PluginOverlayException("OVERLAY_LIMIT", "too many overlays")
        }

        ensureService()
        withContext(Dispatchers.Main.immediate) {
            // ensureService 之后才回到主线程，其间可能有并发 create 抢先建好窗口，这里复查。
            windows[surface.key]?.let { existing ->
                if (existing.visibilityPolicy() != visibilityPolicy) {
                    existing.setVisibilityPolicy(visibilityPolicy)
                }
                return@withContext existing.describe()
            }
            createWindow(surface, params, widthDp, heightDp, visibilityPolicy, savedPosition)
        }
    }

    fun close(pluginId: String, overlayId: String): JsonObject {
        val window = windows[overlayId]
            ?: throw PluginOverlayException("OVERLAY_NOT_FOUND", "overlay not found: $overlayId")
        if (window.pluginId != pluginId) {
            throw PluginOverlayException("OVERLAY_DENIED", "overlay does not belong to this plugin")
        }
        if (windows.remove(overlayId, window)) {
            mainHandler.post { detachWindow(window) }
        }
        return buildJsonObject { put("ok", true) }
    }

    fun closeBySurface(pluginId: String, surfaceId: String): JsonObject {
        val key = "$pluginId:$surfaceId"
        val window = windows[key]
            ?: throw PluginOverlayException("OVERLAY_NOT_FOUND", "overlay not found: $surfaceId")
        if (window.pluginId != pluginId) {
            throw PluginOverlayException("OVERLAY_DENIED", "overlay does not belong to this plugin")
        }
        if (windows.remove(key, window)) {
            mainHandler.post { detachWindow(window) }
        }
        return buildJsonObject { put("ok", true) }
    }

    fun closePlugin(pluginId: String) {
        windows.values.filter { it.pluginId == pluginId }.forEach { window ->
            if (windows.remove(window.overlayId, window)) {
                mainHandler.post { detachWindow(window) }
            }
        }
    }

    fun reloadPlugin(pluginId: String) {
        scope.launch {
            val granted = permissionRepository.isGranted(pluginId) && Settings.canDrawOverlays(context)
            val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            if (!granted || plugin == null) {
                closePlugin(pluginId)
                return@launch
            }
            windows.values.filter { it.pluginId == pluginId }.forEach { window ->
                val surface = plugin.content.overlay?.surfaces?.firstOrNull { it.key == window.overlayId }
                val previous = window.describe()
                val policy = window.visibilityPolicy()
                if (!windows.remove(window.overlayId, window)) return@forEach
                detachWindow(window, stopServiceIfEmpty = false)
                if (surface != null) {
                    runCatching {
                        createWindow(
                            surface = surface,
                            params = buildJsonObject {
                                put("x", previous.getValue("x"))
                                put("y", previous.getValue("y"))
                            },
                            widthDp = surface.width,
                            heightDp = surface.height,
                            visibilityPolicy = policy,
                            savedPosition = null
                        )
                    }.onFailure { FileLogger.w(TAG, "reload overlay failed: ${window.overlayId}", it) }
                }
            }
            if (windows.isEmpty() && serviceStarted) {
                serviceStarted = false
                runCatching { context.stopService(Intent(context, PluginOverlayService::class.java)) }
            }
        }
    }

    fun closeAll() {
        mainHandler.post { closeAllInternal() }
    }

    fun postMessage(pluginId: String, overlayId: String, payload: JsonElement): JsonObject =
        postMessageTo(pluginId, overlayId, null, payload)

    /** 不依赖 overlayId，按 surface 查找当前窗口；适合常驻进程主动推状态。 */
    fun postMessageBySurface(pluginId: String, surfaceId: String, payload: JsonElement): JsonObject =
        postMessageTo(pluginId, null, surfaceId, payload)

    private fun postMessageTo(
        pluginId: String,
        overlayId: String?,
        surfaceId: String?,
        payload: JsonElement
    ): JsonObject {
        val window = when {
            !overlayId.isNullOrBlank() -> windows[overlayId]
            !surfaceId.isNullOrBlank() -> windows["$pluginId:$surfaceId"]
            else -> null
        } ?: throw PluginOverlayException(
            "OVERLAY_NOT_FOUND",
            "overlay not found: ${overlayId ?: surfaceId.orEmpty()}"
        )
        if (window.pluginId != pluginId) {
            throw PluginOverlayException("OVERLAY_DENIED", "overlay does not belong to this plugin")
        }
        window.postMessage(payload)
        return buildJsonObject { put("ok", true) }
    }

    fun update(pluginId: String, overlayId: String, params: JsonObject): JsonObject {
        val window = windows[overlayId]
            ?: throw PluginOverlayException("OVERLAY_NOT_FOUND", "overlay not found: $overlayId")
        if (window.pluginId != pluginId) {
            throw PluginOverlayException("OVERLAY_DENIED", "overlay does not belong to this plugin")
        }
        val dx = (params["dx"] as? JsonPrimitive)?.floatOrNull
        val dy = (params["dy"] as? JsonPrimitive)?.floatOrNull
        if (dx != null || dy != null) {
            mainHandler.post { window.moveBy(dx ?: 0f, dy ?: 0f) }
        }
        val width = (params["width"] as? JsonPrimitive)?.intOrNull
        val height = (params["height"] as? JsonPrimitive)?.intOrNull
        if (width != null || height != null) {
            val (currentWidth, currentHeight) = window.sizeDp()
            val nextWidth = width ?: currentWidth
            val nextHeight = height ?: currentHeight
            if (nextWidth !in PluginOverlayLimits.MIN_DP..PluginOverlayLimits.MAX_WIDTH_DP ||
                nextHeight !in PluginOverlayLimits.MIN_DP..PluginOverlayLimits.MAX_HEIGHT_DP
            ) {
                throw PluginOverlayException("PARAM_INVALID", "overlay size out of range")
            }
            mainHandler.post { window.resize(nextWidth, nextHeight) }
        }
        val visible = (params["visible"] as? JsonPrimitive)?.booleanOrNull
        if (visible != null) {
            mainHandler.post { window.setVisible(visible) }
        }
        val visibilityPolicy = (params["visibilityPolicy"] as? JsonPrimitive)?.contentOrNull
        if (visibilityPolicy != null) {
            val policy = validateVisibilityPolicy(visibilityPolicy)
            mainHandler.post { window.setVisibilityPolicy(policy) }
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun createWindow(
        surface: PluginOverlaySurface,
        params: JsonObject,
        widthDp: Int,
        heightDp: Int,
        visibilityPolicy: String,
        savedPosition: PluginOverlayPosition?
    ): JsonObject {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
        val heightPx = (heightDp * density).toInt().coerceAtLeast(1)
        val page = PluginPage(
            pluginId = surface.pluginId,
            localId = surface.localId,
            title = com.aicode.feature.plugin.domain.LocalizedText.single(surface.localId),
            slot = null,
            pageFile = surface.pageFile,
            icon = "circle",
            installDir = surface.installDir,
            pluginVersion = surface.pluginVersion
        )
        val layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 内容已完成位移，系统窗口收尾时不再叠加移动动画。
                setCanPlayMoveAnimation(false)
            }
            x = (params["x"] as? JsonPrimitive)?.intOrNull ?: savedPosition?.x ?: defaultX(widthPx)
            y = (params["y"] as? JsonPrimitive)?.intOrNull ?: savedPosition?.y ?: defaultY(heightPx)
            x = x.coerceIn(0, (context.resources.displayMetrics.widthPixels - widthPx).coerceAtLeast(0))
            y = y.coerceIn(0, (context.resources.displayMetrics.heightPixels - heightPx).coerceAtLeast(0))
        }
        val webView = PluginWebViewFactory.createOverlay(
            context = context,
            page = page,
            onPageFinished = { FileLogger.d(TAG, "[${surface.key}] overlay page finished") },
            onMainFrameError = { FileLogger.w(TAG, "[${surface.key}] overlay page failed") }
        )
        val window = PluginOverlayWindow(
            context = context,
            pluginId = surface.pluginId,
            overlayId = surface.key,
            windowManager = windowManager,
            layoutParams = layoutParams,
            webView = webView,
            draggable = surface.draggable,
            snap = surface.snap,
            initialVisibilityPolicy = visibilityPolicy,
            initialForeground = appForegroundMonitor.current(),
            initialScreenOn = screenOn,
            onPositionChanged = { x, y ->
                scope.launch(Dispatchers.IO) {
                    positionRepository.save(surface.pluginId, surface.localId, x, y)
                }
            }
        )
        val bridge = PluginOverlayBridge(
            pluginId = surface.pluginId,
            overlayId = surface.key,
            page = page,
            density = density,
            window = window,
            hostApiDispatcher = hostApiDispatcher.get(),
            onPageMessage = { payload -> eventSink?.invoke(surface.pluginId, surface.key, payload) },
            onClose = { removeWindowById(surface.key) }
        )
        PluginWebBridgeInstaller.install(webView, bridge) { message -> bridge.postMessage(message) }
        window.bindBridge(bridge)
        windowManager.addView(window, layoutParams)
        windows[surface.key] = window
        webView.loadUrl(PluginWebViewFactory.pageUrl(page))
        return window.describe()
    }

    private fun removeWindowById(overlayId: String) {
        val window = windows.remove(overlayId) ?: return
        detachWindow(window)
    }

    private fun detachWindow(window: PluginOverlayWindow, stopServiceIfEmpty: Boolean = true) {
        runCatching { windowManager.removeViewImmediate(window) }
            .onFailure { FileLogger.w(TAG, "remove overlay failed: ${window.overlayId}", it) }
        window.dispose()
        if (stopServiceIfEmpty && windows.isEmpty()) {
            serviceStarted = false
            runCatching { context.stopService(Intent(context, PluginOverlayService::class.java)) }
        }
    }

    private fun closeAllInternal() {
        val snapshot = windows.values.toList()
        windows.clear()
        snapshot.forEach { window -> detachWindow(window) }
    }

    private fun ensureService() {
        if (serviceStarted) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PluginOverlayService::class.java))
        }.onFailure {
            throw PluginOverlayException("OVERLAY_SERVICE_FAILED", it.message ?: it.javaClass.simpleName)
        }
    }

    private fun defaultX(widthPx: Int): Int =
        (context.resources.displayMetrics.widthPixels - widthPx - 24 * context.resources.displayMetrics.density).toInt()

    private fun defaultY(heightPx: Int): Int =
        (context.resources.displayMetrics.heightPixels - heightPx - 96 * context.resources.displayMetrics.density).toInt()

    private fun readSize(params: JsonObject, key: String, default: Int, max: Int): Int {
        val raw = params[key] ?: return default
        val value = (raw as? JsonPrimitive)?.intOrNull
            ?: throw PluginOverlayException("PARAM_INVALID", "$key must be an integer")
        if (value !in PluginOverlayLimits.MIN_DP..max) {
            throw PluginOverlayException("PARAM_INVALID", "$key out of range")
        }
        return value
    }

    private fun readVisibilityPolicy(params: JsonObject, key: String, default: String): String {
        val raw = params[key] ?: return default
        val value = (raw as? JsonPrimitive)?.contentOrNull
            ?: throw PluginOverlayException("PARAM_INVALID", "$key must be a string")
        return validateVisibilityPolicy(value)
    }

    private fun validateVisibilityPolicy(value: String): String {
        if (value !in VISIBILITY_POLICIES) {
            throw PluginOverlayException("PARAM_INVALID", "unsupported visibilityPolicy: $value")
        }
        return value
    }

    private companion object {
        const val TAG = "PluginOverlay"
        const val MAX_SURFACES_PER_PLUGIN = 3
        const val MAX_SURFACES_TOTAL = 8
        val VISIBILITY_POLICIES = setOf("always", "foreground", "background")
    }
}
