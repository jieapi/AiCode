package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? {
        return aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    override suspend fun saveProvider(provider: AIProviderConfig) {
        val sortOrder = if (provider.sortOrder >= 0) provider.sortOrder else aiProviderDao.getMaxSortOrder() + 1
        FileLogger.i(TAG, "保存提供商 id=${provider.id} name=${provider.name} sortOrder=$sortOrder")
        aiProviderDao.insertProvider(provider.copy(sortOrder = sortOrder).toEntity())
    }

    override suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        val entities = providers.mapIndexed { index, p -> p.copy(sortOrder = index).toEntity() }
        FileLogger.d(TAG, "重排提供商 共 ${entities.size} 个")
        aiProviderDao.insertAllProviders(entities)
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "删除提供商 id=$id")
        aiProviderDao.deleteProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "切换模型 provider=$id model=$model")
        aiProviderDao.setSelectedModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "更新模型列表 provider=$id 共 ${models.size} 个")
        aiProviderDao.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "设置提供商状态 provider=$id isEnabled=$isEnabled")
        aiProviderDao.setProviderEnabled(id, isEnabled)
    }

    private fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = try { ProviderType.valueOf(type) } catch (e: Exception) { ProviderType.OPENAI },
            apiKey = apiKey,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            balanceScriptPath = balanceScriptPath,
            balanceRefreshInterval = balanceRefreshInterval,
            userAgent = userAgent,
            sortOrder = sortOrder
        )
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            useFullUrl = useFullUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            balanceScriptPath = balanceScriptPath,
            balanceRefreshInterval = balanceRefreshInterval,
            userAgent = userAgent,
            sortOrder = sortOrder
        )
    }
}
