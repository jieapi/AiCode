package com.aicode.feature.plugin.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 插件悬浮窗的前台服务。
 *
 * 窗口本身由 [PluginOverlayManager] 持有；服务只负责把进程留在前台，
 * 并在任务被划掉或服务销毁时统一回收窗口。
 */
@AndroidEntryPoint
class PluginOverlayService : Service() {

    @Inject
    lateinit var overlayManager: PluginOverlayManager

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely()
        overlayManager.onServiceStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            overlayManager.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        // 服务被系统重建时可能已经没有窗口，延迟停止，避免空转通知。
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!overlayManager.hasOverlays()) stopSelf()
        }, EMPTY_STOP_DELAY_MS)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        overlayManager.closeAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlayManager.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
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
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.w(TAG, "startForeground failed", it) }
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
