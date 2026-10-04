package com.aicode.feature.settings.data.repository

import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.DEFAULT_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aicode.feature.settings.domain.model.ProviderKey
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.model.sanitized
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao,
    private val agentDatabase: AgentDatabase
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
        private val json = Json { ignoreUnknownKeys = true }

        private fun encodeMap(map: Map<String, String>): String =
            if (map.isEmpty()) "" else json.encodeToString(map)

        private fun decodeMap(raw: String): Map<String, String> =
            if (raw.isBlank()) emptyMap()
            else runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

        /** 解析多 Key 存储串：每行 `值|权重`；旧数据无 `|` 时权重取默认值。 */
        private fun parseProviderKeys(raw: String): List<ProviderKey> =
            raw.split("\n").mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val sep = trimmed.lastIndexOf('|')
                if (sep < 0) return@mapNotNull ProviderKey(trimmed)
                val value = trimmed.substring(0, sep).trim()
                if (value.isEmpty()) return@mapNotNull null
                val weight = trimmed.substring(sep + 1).trim().toIntOrNull() ?: DEFAULT_KEY_WEIGHT
                ProviderKey(value, weight.coerceIn(0, MAX_KEY_WEIGHT))
            }
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? = withContext(Dispatchers.IO) {
        aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    /**
     * 保存提供商。排序值以数据库当前值为准（重排可能异步持久化，UI 传入的
     * sortOrder 可能陈旧，直接使用会撤销刚完成的排序）；新提供商取最大排序 +1。
     */
    override suspend fun saveProvider(provider: AIProviderConfig): Unit = withContext(Dispatchers.IO) {
        agentDatabase.withTransaction {
            val current = aiProviderDao.getProviderById(provider.id)
            val sortOrder = current?.sortOrder ?: (aiProviderDao.getMaxSortOrder() + 1)
            FileLogger.i(TAG, "保存提供商 id=${provider.id} name=${provider.name} sortOrder=$sortOrder")
            aiProviderDao.insertProvider(provider.copy(sortOrder = sortOrder).sanitized().toEntity())
        }
    }

    /**
     * 按传入顺序重排提供商。只写 id + sortOrder 两列，
     * 避免整行 REPLACE 覆盖并发修改的其它字段（如 apiKey/模型列表）。
     */
    override suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        FileLogger.d(TAG, "重排提供商 共 ${providers.size} 个")
        agentDatabase.withTransaction {
            providers.forEachIndexed { index, p -> aiProviderDao.updateSortOrder(p.id, index) }
        }
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
            apiKey = KeystoreCipher.decryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = parseProviderKeys(KeystoreCipher.decryptString(apiKeys)),
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(keyRotationStrategy) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes
                .split(",").mapNotNull { it.trim().toIntOrNull() }.distinct(),
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = runCatching { ProxyType.valueOf(proxyType) }.getOrDefault(ProxyType.HTTP),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.decryptString(proxyPassword),
            customHeaders = decodeMap(KeystoreCipher.decryptString(customHeaders)),
            scriptParams = decodeMap(KeystoreCipher.decryptString(scriptParams))
        ).sanitized()
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = KeystoreCipher.encryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = KeystoreCipher.encryptString(apiKeys.joinToString("\n") { "${it.value}|${it.weight}" }),
            keyRotationStrategy = keyRotationStrategy.name,
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes.joinToString(","),
            baseUrl = baseUrl,
            useFullUrl = useFullUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = proxyType.name,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.encryptString(proxyPassword),
            customHeaders = KeystoreCipher.encryptString(encodeMap(customHeaders)),
            scriptParams = KeystoreCipher.encryptString(encodeMap(scriptParams))
        )
    }
}
