package com.aicode.feature.plugin.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 插件悬浮窗的前台服务。
 *
 * 窗口本身由 [PluginOverlayManager] 持有；服务只负责把进程留在前台、把锁屏状态转给窗口，
 * 并在任务被划掉或服务销毁时统一回收窗口。
 */
@AndroidEntryPoint
class PluginOverlayService : Service() {

    @Inject
    lateinit var overlayManager: PluginOverlayManager

    private val handler = Handler(Looper.getMainLooper())

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> overlayManager.onScreenStateChanged(false)
                Intent.ACTION_SCREEN_ON -> overlayManager.onScreenStateChanged(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerScreenStateReceiver()
        // 服务重建时锁屏状态可能已经变过，先把当前状态同步给 manager。
        getSystemService(PowerManager::class.java)?.let { power ->
            overlayManager.onScreenStateChanged(power.isInteractive)
        }
        if (!startForegroundSafely()) return
        overlayManager.onServiceStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            overlayManager.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) return START_NOT_STICKY
        // 服务被系统拉起时可能已经没有窗口，延迟停止，避免空转通知。
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!overlayManager.hasOverlays()) stopSelf()
        }, EMPTY_STOP_DELAY_MS)
        // 进程被回收后没有窗口却重建服务只会留下一条空的前台通知，交给下次 create 重新拉起。
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        overlayManager.closeAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenStateReceiver) }
        overlayManager.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { FileLogger.w(TAG, "register screen state receiver failed", it) }
    }

    /**
     * 进入前台；失败时立即退出服务。
     *
     * 系统对 `startForegroundService()` 的计时器不会因为这里捕获了异常而取消，
     * 服务继续活着就会以 ForegroundServiceDidNotStartInTimeException 杀掉整个进程；
     * 主动 stopSelf() 让服务记录消失，系统到点检查时跳过。
     */
    private fun startForegroundSafely(): Boolean {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val closeAll = PendingIntent.getService(
            this,
            1,
            Intent(this, PluginOverlayService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.plugins_overlay_notification_title))
            .setContentText(getString(R.string.plugins_overlay_notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.plugins_overlay_notification_close), closeAll)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        return runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure {
                FileLogger.e(TAG, "startForeground failed", it)
                stopSelf()
            }
            .isSuccess
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.plugins_overlay_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.plugins_overlay_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "PluginOverlayService"
        const val CHANNEL_ID = "plugin_overlay"
        const val NOTIFICATION_ID = 41
        const val EMPTY_STOP_DELAY_MS = 1_500L
        const val ACTION_CLOSE_ALL = "com.aicode.action.CLOSE_PLUGIN_OVERLAYS"
    }
}
