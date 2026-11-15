package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 把启用插件声明的提供商注册成普通提供商记录，插件停用或卸载时删除。
 *
 * 注册后的条目与用户自建提供商完全同权：可改名、改地址、改 Key、拉取与选择模型。
 * 因此只在缺失时插入，不覆盖已有条目，避免把使用者的修改回滚成清单默认值。
 */
@Singleton
class PluginProviderRegistrar @Inject constructor(
    private val pluginManager: PluginManager,
    private val providerRepository: AIProviderRepository
) {

    /** 与插件当前清单对齐：新增缺失的提供商，移除不再声明的提供商。 */
    suspend fun sync(pluginId: String) {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId }
        val providers = plugin?.takeIf { it.isActive }?.content?.providers.orEmpty()
        val existing = pluginProviderIds(pluginId)
        val wanted = providers.map { PluginProviderIds.idOf(pluginId, it.localId) }.toSet()

        val stale = existing.filterNot { it in wanted }
        if (stale.isNotEmpty()) providerRepository.deleteProviders(stale)

        val languageTag = Locale.getDefault().toLanguageTag()
        providers
            .filter { PluginProviderIds.idOf(pluginId, it.localId) !in existing }
            .forEach { provider ->
                providerRepository.saveProvider(
                    AIProviderConfig(
                        id = PluginProviderIds.idOf(pluginId, provider.localId),
                        name = provider.name.resolve(languageTag),
                        type = ProviderType.OPENAI,
                        apiKey = PLACEHOLDER_API_KEY,
                        baseUrl = provider.baseUrl,
                        defaultModel = ""
                    )
                )
            }
    }

    /** 插件停用或卸载：移除它注册的全部提供商记录。 */
    suspend fun removeAll(pluginId: String) {
        val ids = pluginProviderIds(pluginId)
        if (ids.isNotEmpty()) providerRepository.deleteProviders(ids)
    }

    private suspend fun pluginProviderIds(pluginId: String): List<String> =
        providerRepository.getAllProviders().first()
            .map { it.id }
            .filter { PluginProviderIds.pluginIdOf(it) == pluginId }

    companion object {
        /** 插件服务自行处理上游凭据；占位值只用于通过宿主适配器的 Key 检查。 */
        const val PLACEHOLDER_API_KEY = "plugin"
    }
}
