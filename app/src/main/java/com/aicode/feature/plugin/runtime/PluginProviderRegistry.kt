package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.repository.PluginProviderSelection
import com.aicode.feature.settings.data.repository.PluginProviderSelectionRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * 把已启用插件声明的提供商转换成宿主只读的 [AIProviderConfig]。
 *
 * 插件负责启动或提供 OpenAI 兼容服务，宿主只读取 [baseUrl] 与模型并复用现有
 * OpenAI 适配器；这些配置不写入用户 providers.json，启停插件即生效。
 */
@Singleton
class PluginProviderRegistry @Inject constructor(
    private val pluginManager: PluginManager,
    private val selectionRepository: PluginProviderSelectionRepository
) {

    suspend fun activeProviders(): List<AIProviderConfig> {
        pluginManager.ensureLoaded()
        return toConfigs(pluginManager.plugins.value, selectionRepository.selections.first())
    }

    /** 设置页订阅用：插件启停、安装、卸载或模型选择变化后自动刷新。 */
    fun providersFlow(): Flow<List<AIProviderConfig>> = flow {
        pluginManager.ensureLoaded()
        emitAll(
            combine(pluginManager.plugins, selectionRepository.selections) { plugins, selections ->
                toConfigs(plugins, selections)
            }
        )
    }

    suspend fun find(id: String): AIProviderConfig? =
        activeProviders().firstOrNull { it.id == id }

    private fun toConfigs(
        plugins: List<InstalledPlugin>,
        selections: Map<String, PluginProviderSelection>
    ): List<AIProviderConfig> {
        val languageTag = Locale.getDefault().toLanguageTag()
        return plugins
            .filter { it.isActive }
            .flatMap { plugin ->
                plugin.content.providers.map { provider ->
                    val id = idOf(plugin.id, provider.localId)
                    val selection = selections[id]
                    val models = selection?.models.orEmpty()
                    val selectedModel = selection?.selectedModel
                        ?.takeIf { it in models }
                        ?: models.firstOrNull().orEmpty()
                    AIProviderConfig(
                        id = id,
                        name = provider.name.resolve(languageTag),
                        type = ProviderType.OPENAI,
                        apiKey = PLACEHOLDER_API_KEY,
                        baseUrl = provider.baseUrl,
                        defaultModel = models.firstOrNull().orEmpty(),
                        models = models,
                        selectedModel = selectedModel,
                        isEnabled = true,
                        pluginId = plugin.id,
                        pluginProviderId = provider.localId
                    )
                }
            }
    }

    companion object {
        /** 插件服务自行处理上游凭据，宿主只发一个占位值通过适配器的 Key 检查。 */
        const val PLACEHOLDER_API_KEY = "plugin"
        const val ID_PREFIX = "plugin:"

        fun idOf(pluginId: String, providerId: String): String = "$ID_PREFIX$pluginId:$providerId"

        fun isPluginId(id: String): Boolean = id.startsWith(ID_PREFIX)
    }
}
