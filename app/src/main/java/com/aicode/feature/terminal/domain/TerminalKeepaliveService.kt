package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 前台保活期间持有的单把 Wi-Fi 锁（API 29+ 用低延迟锁，低版本回退兼容锁），避免双锁并持触发厂商功耗治理。 */
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockInitialized = false

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // 进程被系统回收后 START_STICKY 重建：persistent 是内存态会丢失。
        // 从持久化开关恢复常驻前台，否则服务空转后 stopSelf，保活形同虚设。
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        startRequested = false
        serviceScope.cancel()
        releaseWifiLocks()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 app = 明确不要后台运行：停掉前台服务并取消 WorkManager 周期兜底，
        // 否则进程被杀后服务会被 START_STICKY 或 KeepaliveWorker 重新拉起并弹通知。
        // 开关本身保持不变，下次打开 app 时由 MainActivity / AIEditorApp 自动恢复。
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && !persistent) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0) {
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 进入前台并刷新通知；文案随「常驻保活 / 后台会话」组合变化。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.aicode.MainActivity] 恢复）。
     */
    private fun ensureForeground() {
        val text = when {
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount)
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount)
            else -> getString(R.string.notification_keepalive_enabled)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onSuccess { acquireWifiLocks() }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * 获取单把 Wi-Fi 锁，尽量保持 Wi-Fi 不休眠。延后到首次进入前台时惰性创建：
     * API 29+ `WIFI_MODE_FULL_LOW_LATENCY` 已取代旧的 `WIFI_MODE_FULL_HIGH_PERF`，
     * 不再双锁并持以降低后台功耗查杀概率。
     */
    private fun acquireWifiLocks() {
        runCatching {
            if (!wifiLockInitialized) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AiCode:Keepalive:low-latency")
                } else {
                    @Suppress("DEPRECATION")
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:Keepalive")
                }.apply { setReferenceCounted(false) }
                wifiLockInitialized = true
            }
            wifiLock?.let { lock -> if (!lock.isHeld) lock.acquire() }
        }.onFailure { FileLogger.w(TAG, "acquireWifiLock failed", it) }
    }

    private fun releaseWifiLocks() {
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"

        @Volatile
        private var startRequested = false

        /** 开启常驻保活（幂等）。返回是否成功投递启动请求。 */
        fun enablePersistent(context: Context): Boolean {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                startRequested = true
                true
            }.onFailure {
                if (isBackgroundStartRestriction(it)) {
                    FileLogger.i(TAG, "enablePersistent skipped: app in background, keepalive deferred")
                } else {
                    FileLogger.e(TAG, "enablePersistent startService failed", it)
                }
            }.getOrDefault(false)
        }

        /** 关闭常驻保活（幂等）。仅在确曾启动或服务运行中时发送停止动作，避免后台凭空拉起服务。 */
        fun disablePersistent(context: Context) {
            if (!startRequested && !isRunning(context)) return
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure {
                    if (isBackgroundStartRestriction(it)) {
                        startRequested = false
                    } else {
                        FileLogger.e(TAG, "disablePersistent startService failed", it)
                    }
                }
        }

        /** 登记一个后台会话（不改变用户常驻开关）。由调用方按状态迁移保证不重复计数；返回是否投递成功。 */
        fun startSession(context: Context): Boolean {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                startRequested = true
                true
            }.onFailure {
                if (isBackgroundStartRestriction(it)) {
                    FileLogger.i(TAG, "startSession skipped: app in background, deferred")
                } else {
                    FileLogger.e(TAG, "startSession startService failed", it)
                }
            }.getOrDefault(false)
        }

        /** 结束一个后台会话。服务未启动且未运行中时跳过，避免在后台拉起已停止服务。 */
        fun stopSession(context: Context) {
            if (!startRequested && !isRunning(context)) return
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure {
                    if (isBackgroundStartRestriction(it)) {
                        startRequested = false
                    } else {
                        FileLogger.e(TAG, "stopSession startService failed", it)
                    }
                }
        }

        private fun isBackgroundStartRestriction(error: Throwable): Boolean =
            error.message?.contains("app is in background") == true ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    error is android.app.ForegroundServiceStartNotAllowedException)

        /** 判断保活服务当前是否在运行。WorkManager 兜底拉起前先探测，避免反复 startService。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val serviceName = TerminalKeepaliveService::class.java.name
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }.getOrDefault(false)
    }
}
