package com.aicode.feature.plugin.runtime

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.feature.plugin.domain.PluginHostPaths
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 插件发系统通知的宿主实现。
 *
 * 每个插件使用自己的 notification tag（`plugin:<pluginId>[:key]`），不会覆盖宿主或其他插件的通知；
 * 系统通知关闭、渠道被关闭或缺少权限时返回明确错误，不伪造成功。
 */
@Singleton
class PluginNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    fun notify(pluginId: String, params: JsonObject): JsonElement {
        val title = PluginHostPaths.requireString(params, "title").trim()
        val body = PluginHostPaths.requireString(params, "body").trim()
        if (title.isEmpty()) throw PluginProcessException("PARAM_INVALID", "empty title")
        if (title.length > MAX_TITLE_CHARS) {
            throw PluginProcessException("PARAM_INVALID", "title too long")
        }
        if (body.length > MAX_BODY_CHARS) {
            throw PluginProcessException("PARAM_INVALID", "body too long")
        }
        val key = (params["key"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && (key.length > MAX_KEY_CHARS || key.any { it.isISOControl() })) {
            throw PluginProcessException("PARAM_INVALID", "invalid key")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw PluginProcessException("NOTIFICATION_PERMISSION_DENIED", "应用没有通知权限")
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            throw PluginProcessException("NOTIFICATION_DISABLED", "系统通知已关闭")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                throw PluginProcessException("NOTIFICATION_DISABLED", "插件通知渠道已关闭")
            }
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val tag = "plugin:$pluginId" + (key?.let { ":$it" } ?: "")
        runCatching { manager.notify(tag, NOTIFICATION_ID, notification) }
            .getOrElse { error ->
                throw PluginProcessException(
                    "NOTIFICATION_FAILED",
                    "发送通知失败: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        return buildJsonObject { put("ok", true) }
    }

    companion object {
        const val CHANNEL_ID = "plugin_events"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TITLE_CHARS = 100
        private const val MAX_BODY_CHARS = 1_000
        private const val MAX_KEY_CHARS = 64
    }
}
