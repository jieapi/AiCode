package com.aicode.feature.settings.domain.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.flow.Flow

interface AIProviderRepository {
    fun getAllProviders(): Flow<List<AIProviderConfig>>
    suspend fun getProviderById(id: String): AIProviderConfig?
    suspend fun saveProvider(provider: AIProviderConfig)
    suspend fun deleteProvider(id: String)

    /** 批量删除（列表多选批量操作）。 */
    suspend fun deleteProviders(ids: Collection<String>)

    suspend fun setSelectedModel(id: String, model: String)

    /** 只更新 API Key（加密落库），避免整行 REPLACE 覆盖并发修改的其它字段。 */
    suspend fun setProviderApiKey(id: String, apiKey: String)
    suspend fun updateModels(id: String, models: List<String>)
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)

    /** 批量启用 / 停用（列表多选批量操作）。 */
    suspend fun setProvidersEnabled(ids: Collection<String>, isEnabled: Boolean)

    /** 按传入顺序重排提供商（重分配 sortOrder 并整体保存）。 */
    suspend fun reorderProviders(providers: List<AIProviderConfig>)
}
