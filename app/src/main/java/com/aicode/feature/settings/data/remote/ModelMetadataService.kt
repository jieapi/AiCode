package com.aicode.feature.settings.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.mergeModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: Cache? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 上次测活成功的源 id 持久化于此（SharedPreferences，键值文件，系统清理不影响）。 */
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** models.dev 仅作元数据增强：独立短超时 client，不可达时快速失败，不占用共享的 120s 流式超时。 */
    private val metadataClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
        .build()

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            mergeCustom(providerId, modelId, auto)
        }

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
                mergeCustom(providerId, modelId, auto)
            }
        }

    /** 自定义元数据优先于自动解析（拉取/内置）结果；providerId 为空（未关联配置）时跳过合并。 */
    private suspend fun mergeCustom(providerId: String, modelId: String, auto: ModelMetadata): ModelMetadata {
        if (providerId.isBlank()) return auto
        val custom = customModelMetadataStore.get(providerId, modelId)
        return mergeModelMetadata(modelId, auto, custom)
    }

    /** 启动时统一调用：先轻量测活候选源并记忆可用源；缓存过期时再用可用源拉全量。 */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val activeSource = probeSources()
            val diskCache = loadCatalogFromDisk()
            if (diskCache != null && isFresh(diskCache)) return@withContext
            fetchCatalogFromSources(preferred = activeSource)
        }
    }

    private fun isFresh(cache: Cache): Boolean =
        System.currentTimeMillis() - cache.loadedAtMs < CACHE_MAX_AGE_MS

    /** 纯只读链路：内存 → 磁盘缓存(24h 内) → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。
     *  网络刷新失败时（[fetchCatalogFromSources]），会把过期磁盘缓存降级塞入内存 [cached]，
     *  避免回退到更旧的内置 assets 快照。 */
    private fun loadCatalog(): Map<String, Map<String, ModelMetadata>> {
        cached?.let {
            return it.catalog
        }

        loadCatalogFromDisk()?.takeIf { isFresh(it) }?.let {
            cached = it
            return it.catalog
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it.catalog
        }

        return emptyMap()
    }

    /**
     * 按「测活选中源 → 候选顺序」尝试拉取全量目录，成功即停并记忆该源；
     * 全部失败降级磁盘缓存（即使已过期），避免回退到更旧的内置 assets 快照。
     */
    private fun fetchCatalogFromSources(preferred: Source?) {
        val ordered = buildList {
            preferred?.let { add(it.id) }
            addAll(CANDIDATE_SOURCES.map { it.id }.filter { it != preferred?.id })
        }
        for (id in ordered) {
            val source = CANDIDATE_SOURCES.firstOrNull { it.id == id } ?: continue
            val result = runCatching {
                val request = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", "aicode")
                    .get()
                    .build()
                metadataClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(200)}")
                    writeCatalogCache(body)
                    parseCatalog(json.parseToJsonElement(body))
                }
            }
            if (result.isSuccess) {
                cached = Cache(System.currentTimeMillis(), result.getOrThrow())
                rememberSource(source.id)
                return
            }
            FileLogger.w(TAG, "拉取模型元数据失败 source=${source.id}", result.exceptionOrNull())
        }
        loadCatalogFromDisk()?.let { cached = it }
    }

    /** 按「记忆源 → 候选顺序」轻量测活，返回第一个可用源并更新/清空记忆；全部失败返回 null。 */
    private fun probeSources(): Source? {
        val remembered = rememberedSourceId()
        val ordered = buildList {
            remembered?.let { add(it) }
            addAll(CANDIDATE_SOURCES.map { it.id }.filter { it != remembered })
        }
        var found: Source? = null
        for (id in ordered) {
            val source = CANDIDATE_SOURCES.firstOrNull { it.id == id } ?: continue
            if (probe(source)) {
                found = source
                break
            }
        }
        if (found != null) {
            rememberSource(found.id)
        } else if (remembered != null) {
            clearRememberedSource()
        }
        return found
    }

    /** 轻量连通测试：Range 取前 2KB，读到 1 字节即活（随后立即关闭，不下载全量）。 */
    private fun probe(source: Source): Boolean = runCatching {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "aicode")
            .header("Range", "bytes=0-2047")
            .get()
            .build()
        metadataClient.newCall(request).execute().use { response ->
            response.isSuccessful && (response.body?.byteStream()?.read() ?: -1) >= 0
        }
    }.getOrDefault(false)

    private fun rememberedSourceId(): String? = prefs.getString(KEY_LAST_SOURCE, null)

    private fun rememberSource(id: String) {
        prefs.edit().putString(KEY_LAST_SOURCE, id).apply()
    }

    private fun clearRememberedSource() {
        prefs.edit().remove(KEY_LAST_SOURCE).apply()
    }

    private fun loadCatalogFromDisk(): Cache? {
        val file = cacheFile()
        if (!file.isFile) return null
        return runCatching {
            val body = file.readText(Charsets.UTF_8)
            val loadedAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            Cache(loadedAtMs, parseCatalog(json.parseToJsonElement(body)))
        }.getOrNull()
    }

    private fun loadCatalogFromAssets(): Cache? = runCatching {
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        Cache(0L, parseCatalog(json.parseToJsonElement(body)))
    }.getOrNull()

    private fun writeCatalogCache(body: String) {
        runCatching {
            cacheFile().writeText(body, Charsets.UTF_8)
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    /** 目录中匹配不到模型时的兜底：统一视为文本模型，128k 输入 / 64k 输出。 */
    private fun default(type: ProviderType, modelId: String): ModelMetadata = ModelMetadata(
        id = modelId,
        providerId = type.name.lowercase(),
        displayName = modelId,
        contextTokens = DEFAULT_CONTEXT_TOKENS,
        inputTokens = DEFAULT_CONTEXT_TOKENS,
        outputTokens = DEFAULT_OUTPUT_TOKENS,
        supportsTools = true,
        supportsVision = false,
        supportsReasoning = false,
        source = ModelMetadata.Source.INFERRED
    )

    private fun findMetadata(
        catalog: Map<String, Map<String, ModelMetadata>>,
        type: ProviderType,
        modelId: String
    ): ModelMetadata? {
        val normalized = modelId.removePrefix("models/")
        val preferredProviders = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        // 先精确匹配，失败后依次尝试剥离常见后缀的变体（如 gpt-5-high -> gpt-5）
        for (candidate in strippedCandidates(normalized)) {
            for (provider in preferredProviders) {
                catalog[provider]?.get(candidate)?.let { return it }
            }
            catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
        }
        return null
    }

    /** 生成匹配候选：原始 id 在前，随后迭代剥离常见后缀（-thinking/-preview/-high/-low 及括号形式），可连续剥离多层。 */
    private fun strippedCandidates(modelId: String): List<String> {
        val candidates = mutableListOf(modelId)
        var current = modelId
        var changed: Boolean
        do {
            changed = false
            for (suffix in MODEL_SUFFIXES) {
                if (current.length > suffix.length && current.endsWith(suffix)) {
                    current = current.dropLast(suffix.length)
                    candidates.add(current)
                    changed = true
                    break
                }
            }
        } while (changed)
        return candidates
    }

    private fun parseCatalog(root: JsonElement): Map<String, Map<String, ModelMetadata>> {
        return root.jsonObject.mapValues { (providerId, providerEl) ->
            val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
            models.mapValues { (_, modelEl) ->
                val model = modelEl.jsonObject
                val limit = model["limit"]?.jsonObject
                val modalities = model["modalities"]?.jsonObject
                val inputModalities = modalities?.get("input")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty()
                val cost = model["cost"]?.jsonObject
                val reasoningOptions = parseReasoningOptions(model["reasoning_options"])
                ModelMetadata(
                    id = model["id"]?.jsonPrimitive?.content ?: "",
                    providerId = providerId,
                    displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                    contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                    inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                    outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                    supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                    supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                    supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                    reasoningEffortOptions = reasoningOptions.takeIf { it.isNotEmpty() },
                    inputCostUsdPerM = cost?.get("input")?.jsonPrimitive?.doubleOrNull,
                    outputCostUsdPerM = cost?.get("output")?.jsonPrimitive?.doubleOrNull,
                    cacheReadCostUsdPerM = cost?.get("cache_read")?.jsonPrimitive?.doubleOrNull,
                    source = ModelMetadata.Source.MODELS_DEV
                )
            }
        }
    }

    private data class Cache(
        val loadedAtMs: Long,
        val catalog: Map<String, Map<String, ModelMetadata>>
    )

    companion object {
        /** 从 models.dev 的 reasoning_options 数组中提取 effort 类型的档位 values；无 effort 档位时返回空列表。 */
        fun parseReasoningOptions(reasoningOptions: JsonElement?): List<String> =
            reasoningOptions?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "effort" }
                ?.get("values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                .orEmpty()

        const val TAG = "ModelMetadataService"
        private const val PREFS_NAME = "model_metadata_prefs"
        private const val KEY_LAST_SOURCE = "last_success_source"
        const val CACHE_FILE_NAME = "models-dev-api.json"
        const val ASSET_FILE_NAME = "api.official.json"
        const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000

        /** 兜底模糊匹配：依次尝试剥离的模型 id 后缀。 */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )

        private data class Source(val id: String, val url: String)

        /** 候选源：官方优先（代理/海外网络可用），jsDelivr 镜像兜底（国内可达，每日同步）。 */
        private val CANDIDATE_SOURCES = listOf(
            Source("official", "https://models.dev/api.json"),
            Source("jsdelivr_gcore", "https://gcore.jsdelivr.net/gh/symfony/models-dev@main/models-dev.json"),
            Source("jsdelivr_cdn", "https://cdn.jsdelivr.net/gh/symfony/models-dev@main/models-dev.json"),
        )
    }
}
