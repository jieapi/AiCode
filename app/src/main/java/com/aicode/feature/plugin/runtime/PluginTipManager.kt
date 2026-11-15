package com.aicode.feature.plugin.runtime

import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginManager
import dagger.Lazy
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 一条待展示的插件提示；[actionHandler] 非空时按钮点击会回调插件进程。 */
data class PluginTip(
    val id: String,
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val text: String,
    val actionLabel: String? = null,
    val actionHandler: String? = null
)

class PluginTipException(val code: String, message: String) : Exception(message)

/**
 * 插件 tips 的宿主队列：插件只提供文本与可选动作标识，UI、来源标记与动作分发由宿主掌握。
 *
 * 队列有界（最多 [MAX_TIPS] 条），同一插件只保留最新一条；App 不在聊天页时提示留在队列里，
 * 进入聊天页后按顺序展示。
 */
@Singleton
class PluginTipManager @Inject constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: Lazy<PluginRuntimeManager>
) {
    private val _tips = MutableStateFlow<List<PluginTip>>(emptyList())
    val tips: StateFlow<List<PluginTip>> = _tips.asStateFlow()

    fun show(pluginId: String, params: JsonObject): JsonObject {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginTipException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val text = PluginTipParams.requireText(params, "text", MAX_TEXT_CHARS)
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(MAX_TITLE_CHARS)
        val action = params["action"] as? JsonObject
        val actionLabel = action?.let {
            PluginTipParams.requireText(it, "label", MAX_ACTION_LABEL_CHARS)
        }
        val actionHandler = action?.let {
            PluginTipParams.requireHandler(it)
        }
        if (actionHandler != null && plugin.content.runtime == null) {
            throw PluginTipException("RUNTIME_UNAVAILABLE", "tip action requires a plugin runtime")
        }
        val tip = PluginTip(
            id = "tip-${UUID.randomUUID()}",
            pluginId = pluginId,
            pluginName = plugin.manifest?.name?.resolve(Locale.getDefault().toLanguageTag()) ?: pluginId,
            title = title,
            text = text,
            actionLabel = actionLabel,
            actionHandler = actionHandler
        )
        _tips.update { current -> current.filterNot { it.pluginId == pluginId } }
        val pending = _tips.value
        if (pending.size >= MAX_TIPS) {
            throw PluginTipException("TIP_QUEUE_FULL", "too many pending tips")
        }
        _tips.value = pending + tip
        return buildJsonObject {
            put("tipId", tip.id)
            put("queued", true)
        }
    }

    fun dismiss(tipId: String) {
        _tips.update { current -> current.filterNot { it.id == tipId } }
    }

    /** 插件停用/卸载时清掉它尚未展示的提示，避免点击回调已失效的插件。 */
    fun dismissPlugin(pluginId: String) {
        _tips.update { current -> current.filterNot { it.pluginId == pluginId } }
    }

    /** 展示层按钮点击：移除提示并调用插件声明的 handler；失败只记日志。 */
    suspend fun invokeAction(tipId: String) {
        val tip = _tips.value.firstOrNull { it.id == tipId } ?: return
        dismiss(tipId)
        val handler = tip.actionHandler ?: return
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == tip.pluginId && it.isActive }
            ?: return
        val runtime = plugin.content.runtime ?: return
        try {
            runtimeManager.get().invoke(
                pluginId = plugin.id,
                version = plugin.record.version,
                runtime = runtime,
                handler = handler,
                args = buildJsonObject {
                    put("tipId", tip.id)
                    put("action", "primary")
                },
                method = "ui.invoke",
                timeoutMs = ACTION_TIMEOUT_MS
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件 tip 动作失败: ${tip.pluginId}/$handler", e)
        }
    }

    private object PluginTipParams {
        fun requireText(params: JsonObject, key: String, max: Int): String {
            val value = (params[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isEmpty()) throw PluginTipException("PARAM_INVALID", "missing $key")
            if (value.length > max) throw PluginTipException("PARAM_INVALID", "$key too long")
            return value
        }

        fun requireHandler(params: JsonObject): String {
            val handler = (params["handler"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!HANDLER_NAME.matches(handler)) throw PluginTipException("PARAM_INVALID", "invalid handler")
            return handler
        }
    }

    private companion object {
        const val TAG = "PluginTipManager"
        const val MAX_TIPS = 3
        const val MAX_TITLE_CHARS = 80
        const val MAX_TEXT_CHARS = 500
        const val MAX_ACTION_LABEL_CHARS = 24
        const val ACTION_TIMEOUT_MS = 10_000L
        val HANDLER_NAME = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
    }
}
