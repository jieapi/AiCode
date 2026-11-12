package com.aicode.feature.plugin.runtime

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把生效插件声明的 AI 工具同步进 [ToolRegistry]。
 *
 * 插件在设备支持 JavaScriptEngine 时才注册工具；停用、撤权、卸载或内容损坏时移除对应工具。
 * 名字由 [PluginToolAdapter] 命名空间化，不会覆盖内置工具或其他插件。
 */
@Singleton
class PluginToolManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val toolRegistry: ToolRegistry,
    private val runtime: PluginRuntimeManager
) {
    private val started = AtomicBoolean(false)
    private val registeredLock = Any()
    private val registeredNames = mutableSetOf<String>()
    private var activePluginIds = emptySet<String>()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            withContext(Dispatchers.IO) { pluginManager.ensureLoaded() }
            pluginManager.plugins.collect { plugins ->
                runCatching { sync(plugins) }
                    .onFailure { FileLogger.e(TAG, "同步插件 AI 工具失败", it) }
            }
        }
    }

    private fun sync(plugins: List<InstalledPlugin>) {
        plugins.filter { it.isActive }.forEach { plugin ->
            plugin.content.runtime?.let { runtime.closeOtherVersions(plugin.id, plugin.record.version) }
        }
        val desired = plugins
            .filter { it.isActive }
            .flatMap { plugin ->
                val manifest = plugin.manifest ?: return@flatMap emptyList()
                plugin.content.runtime ?: return@flatMap emptyList()
                val pluginName = manifest.name.resolve(Locale.getDefault().toLanguageTag())
                plugin.content.tools
                    .map { tool ->
                        PluginToolAdapter(
                            context = context,
                            pluginId = plugin.id,
                            pluginName = pluginName,
                            pluginVersion = plugin.record.version,
                            tool = tool,
                            runtime = runtime,
                            isStillAuthorized = { isAuthorized(plugin.id) }
                        )
                    }
            }
            .distinctBy { it.name }

        val currentActiveIds = plugins
            .filter { it.isActive && it.content.runtime != null }
            .map { it.id }
            .toSet()
        (activePluginIds - currentActiveIds).forEach { runtime.closePlugin(it) }
        activePluginIds = currentActiveIds

        val desiredNames = desired.map { it.name }.toSet()
        synchronized(registeredLock) {
            (registeredNames - desiredNames).forEach { name ->
                toolRegistry.unregister(name)
            }
            desired.forEach { tool ->
                toolRegistry.register(tool.name, tool)
            }
            registeredNames.clear()
            registeredNames += desiredNames
        }
        if (desired.isNotEmpty()) {
            FileLogger.i(TAG, "已注册 ${desired.size} 个插件 AI 工具: ${desiredNames.joinToString()}")
        }
    }

    private fun isAuthorized(pluginId: String): Boolean = pluginManager.plugins.value.any { plugin ->
        plugin.id == pluginId && plugin.isActive
    }

    private companion object {
        const val TAG = "PluginToolManager"
    }
}
