package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginHostException
import com.aicode.feature.plugin.domain.PluginHostPaths
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 插件对自身 `contributes.providers` 条目的运行时维护入口。
 *
 * 插件启动本地兼容服务后，往往自己就知道可用模型与访问令牌；这里让它在不经过设置页的
 * 情况下同步模型列表与上下文窗口、设置服务要求的 API Key。可操作范围严格限定为当前插件清单里
 * 声明、且已由 [PluginProviderRegistrar] 注册的提供商——提供商 ID 必须解析到
 * `plugin:<插件ID>:<局部ID>`，传入其它插件的条目一律按不存在处理。
 *
 * 接口只写不读密钥：[setApiKey] 覆盖单 Key 字段，从不返回旧值；[listModels] 只返回
 * 模型列表与当前选中模型，不暴露地址、请求头或凭据。
 */
@Singleton
class PluginProviderService @Inject constructor(
    private val pluginManager: PluginManager,
    private val providerRepository: AIProviderRepository,
    private val modelMetadataService: ModelMetadataService,
    private val customModelMetadataStore: CustomModelMetadataStore,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val subagentTierSettingsRepository: SubagentTierSettingsRepository
) {

    /** 读取插件自己提供商当前配置的模型列表；不访问上游服务。 */
    suspend fun listModels(pluginId: String, params: JsonObject): JsonElement {
        val config = requireOwnedProvider(pluginId, params)
        return modelsJson(config)
    }

    /**
     * 追加一个模型；重复添加视为成功，提供商还没有选中模型时自动选中它。
     * `options.contextTokens` 可一并写入该模型的上下文窗口，供自动识别不到的模型使用。
     */
    suspend fun addModel(pluginId: String, params: JsonObject): JsonElement {
        val config = requireOwnedProvider(pluginId, params)
        val model = requireModel(params)
        val contextTokens = optionalPositiveInt(params, "contextTokens")
        val existing = model in config.models
        if (!existing && config.models.size >= MAX_MODELS_PER_PROVIDER) {
            throw PluginHostException("MODEL_LIMIT", "provider has too many models")
        }
        val models = if (existing) config.models else config.models + model
        if (!existing) providerRepository.updateModels(config.id, models)
        val effectiveModel = config.effectiveModel
        val selectedModel = if (effectiveModel.isBlank() || effectiveModel !in models) {
            providerRepository.setSelectedModel(config.id, model)
            model
        } else {
            config.selectedModel
        }
        contextTokens?.let { applyModelContext(config, model, it) }
        return modelsJson(config.copy(models = models, selectedModel = selectedModel), model = model)
    }

    /** 删除一个模型；模型不存在视为成功，删除的正好是选中模型时回退到剩余模型。 */
    suspend fun deleteModel(pluginId: String, params: JsonObject): JsonElement {
        val config = requireOwnedProvider(pluginId, params)
        val model = requireModel(params)
        if (model !in config.models) {
            customModelMetadataStore.remove(config.id, model)
            return modelsJson(config, model = model, removed = false)
        }
        val models = config.models.filterNot { it == model }
        providerRepository.updateModels(config.id, models)
        customModelMetadataStore.remove(config.id, model)
        val selectedModel = if (config.effectiveModel == model) {
            val fallback = models.firstOrNull()
                ?: config.defaultModel.takeIf { it in models }.orEmpty()
            providerRepository.setSelectedModel(config.id, fallback)
            fallback
        } else {
            config.selectedModel
        }
        clearModelBindings(config.id, model)
        return modelsJson(
            config.copy(models = models, selectedModel = selectedModel),
            model = model,
            removed = true
        )
    }

    /** 修改模型上下文窗口；自动识别不到的插件模型由插件自己给出准确值。 */
    suspend fun setModelContext(pluginId: String, params: JsonObject): JsonElement {
        val config = requireOwnedProvider(pluginId, params)
        val model = requireModel(params)
        if (model !in config.models) {
            throw PluginHostException("MODEL_NOT_AVAILABLE", "model is not added to provider: $model")
        }
        val contextTokens = optionalPositiveInt(params, "contextTokens")
            ?: throw PluginHostException("PARAM_MISSING", "contextTokens is required")
        applyModelContext(config, model, contextTokens)
        return buildJsonObject {
            put("providerId", config.id)
            put("model", model)
            put("contextTokens", contextTokens)
        }
    }

    /** 设置插件提供商使用的单 Key；空串表示清除，值只加密落盘、不回显。 */
    suspend fun setApiKey(pluginId: String, params: JsonObject): JsonElement {
        val config = requireOwnedProvider(pluginId, params)
        val apiKey = PluginHostPaths.requireString(params, "apiKey").filterNot { it.isWhitespace() }
        if (apiKey.toByteArray(Charsets.UTF_8).size > MAX_API_KEY_BYTES) {
            throw PluginHostException("TOO_LARGE", "apiKey too large")
        }
        providerRepository.setProviderApiKey(config.id, apiKey)
        val updated = providerRepository.getProviderById(config.id)
        return buildJsonObject {
            put("providerId", config.id)
            put("hasKey", updated?.hasUsableApiKey == true)
        }
    }

    /**
     * 解析并校验目标提供商：只接受本插件清单声明过的提供商。
     * `providerId` 支持清单里的局部 ID，也支持 `model.listProviders` 返回的全局 ID。
     */
    private suspend fun requireOwnedProvider(pluginId: String, params: JsonObject): AIProviderConfig {
        pluginManager.ensureLoaded()
        val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
        val raw = PluginHostPaths.requireString(params, "providerId").trim()
        if (raw.isEmpty()) throw paramInvalid("providerId is blank")
        if (raw.length > MAX_PROVIDER_ID_CHARS) throw paramInvalid("providerId too long")
        val localId = if (PluginProviderIds.isPluginId(raw)) {
            if (PluginProviderIds.pluginIdOf(raw) != pluginId) throw providerNotFound(raw)
            PluginProviderIds.localIdOf(raw) ?: throw providerNotFound(raw)
        } else {
            raw
        }
        if (plugin.content.providers.none { it.localId == localId }) {
            throw providerNotFound(raw)
        }
        return providerRepository.getProviderById(PluginProviderIds.idOf(pluginId, localId))
            ?: throw providerNotFound(raw)
    }

    /** 把上下文窗口写入自定义元数据；先解析当前合并值，避免默认 false 覆盖自动识别的能力位。 */
    private suspend fun applyModelContext(config: AIProviderConfig, model: String, contextTokens: Int) {
        val current = modelMetadataService.resolve(config.id, config.type, model)
        customModelMetadataStore.put(config.id, model, current.copy(contextTokens = contextTokens))
    }

    private fun optionalPositiveInt(params: JsonObject, key: String): Int? {
        val raw = params[key] ?: return null
        if (raw is JsonNull) return null
        val value = (raw as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
            ?: throw PluginHostException("PARAM_INVALID", "$key must be a positive integer")
        if (value !in 1..MAX_CONTEXT_TOKENS) {
            throw PluginHostException("PARAM_INVALID", "$key is out of range")
        }
        return value
    }

    private fun requireModel(params: JsonObject): String {
        val model = PluginHostPaths.requireString(params, "model")
            .filterNot { it == '\n' || it == '\r' || it == '\t' }
            .trim()
        if (model.isEmpty()) throw paramInvalid("model is blank")
        if (model.length > MAX_MODEL_CHARS) throw paramInvalid("model too long")
        return model
    }

    private fun modelsJson(
        config: AIProviderConfig,
        model: String? = null,
        removed: Boolean? = null
    ): JsonElement = buildJsonObject {
        put("providerId", config.id)
        model?.let { put("model", it) }
        put("models", JsonArray(config.models.map { JsonPrimitive(it) }))
        put("selectedModel", config.selectedModel)
        removed?.let { put("removed", it) }
    }

    /** 模型被插件删除后，清掉仍指向它的默认 / 识图 / 生图 / 压缩 / 标题 / 子代理档位绑定。 */
    private suspend fun clearModelBindings(providerId: String, model: String) {
        if (defaultModelSettingsRepository.getDefaultProviderId() == providerId &&
            defaultModelSettingsRepository.getDefaultModel() == model
        ) {
            defaultModelSettingsRepository.clear()
        }
        if (visionModelSettingsRepository.getVisionProviderId() == providerId &&
            visionModelSettingsRepository.getVisionModel() == model
        ) {
            visionModelSettingsRepository.clear()
        }
        if (imageGenModelSettingsRepository.getImageGenProviderId() == providerId &&
            imageGenModelSettingsRepository.getImageGenModel() == model
        ) {
            imageGenModelSettingsRepository.clear()
        }
        if (compactionModelSettingsRepository.getCompactionProviderId() == providerId &&
            compactionModelSettingsRepository.getCompactionModel() == model
        ) {
            compactionModelSettingsRepository.clear()
        }
        if (titleModelSettingsRepository.getTitleProviderId() == providerId &&
            titleModelSettingsRepository.getTitleModel() == model
        ) {
            titleModelSettingsRepository.clear()
        }
        subagentTierSettingsRepository.snapshot()
            .filterValues { it.providerId == providerId && it.model == model }
            .keys
            .forEach { subagentTierSettingsRepository.clear(it) }
    }

    private fun providerNotFound(providerId: String): PluginHostException =
        PluginHostException("PROVIDER_NOT_FOUND", "provider not declared by this plugin: $providerId")

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val MAX_MODELS_PER_PROVIDER = 500
        const val MAX_MODEL_CHARS = 200
        const val MAX_PROVIDER_ID_CHARS = 200
        const val MAX_API_KEY_BYTES = 16 * 1024
        const val MAX_CONTEXT_TOKENS = 10_000_000
    }
}
