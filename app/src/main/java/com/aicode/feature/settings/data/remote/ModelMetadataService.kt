package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.mergeModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeCatalogParses = java.util.concurrent.atomic.AtomicInteger(0)

    /** 串行化目录加载：并发的 resolve 会同时穿透 [cached] 未命中的空窗，各自把 5MB 目录读盘并解析一遍。 */
    private val catalogLock = Mutex()

    /** 统一拉取器：模型元数据从独立数据分支拉取，避免每日机器产物污染 main。 */
    private val repoFetcher = RepoDataFetcher(context, branch = MODELS_BRANCH)

    @Volatile
    private var cached: Catalog? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            mergeCustom(providerId, modelId, auto)
        }

    /** 仅按目录/内置规则识别（不含自定义覆盖），供「手动添加模型」自动填充与重置为默认元数据。 */
    suspend fun resolveAuto(type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            findMetadata(loadCatalog(), type, modelId) ?: default(type, modelId)
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

    /** 启动时统一调用：经统一拉取器从本仓库刷新模型目录（12h 缓存，失败静默）。 */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val fetchStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "catalog.fetch.start", details = "operation=$fetchStartedNs")
            val result = runCatching { repoFetcher.fetch(MODELS_REPO_PATH) }.getOrNull()
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.fetch.finished",
                elapsedMs = (System.nanoTime() - fetchStartedNs) / 1_000_000,
                details = "operation=$fetchStartedNs hasContent=${result is RepoDataFetcher.FetchResult.Success || result is RepoDataFetcher.FetchResult.FallbackDiskCache}"
            )
            val body = when (result) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                else -> null
            } ?: return@withContext
            if (body.isBlank()) return@withContext
            // 解析放在锁外：持锁解析 5MB 目录会让同时进来的 resolve（发送消息前的必经路径）一直排队。
            runCatching { parseCatalogWithDiagnostics(body, "refresh").also { fresh -> catalogLock.withLock { cached = fresh } } }
                .onFailure { FileLogger.w(TAG, "解析模型元数据失败", it) }
        }
    }

    /** 纯只读链路：内存 → 本仓库磁盘缓存 → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。 */
    private suspend fun loadCatalog(): Catalog = catalogLock.withLock {
        cached?.let { return it }

        val readStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "catalog.disk.start", details = "operation=$readStartedNs")
        val diskBody = repoFetcher.readLocalCache(MODELS_REPO_PATH)
        FileLogger.memoryCheckpoint(
            TAG,
            "catalog.disk.finished",
            elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
            details = "operation=$readStartedNs chars=${diskBody?.length ?: 0} present=${diskBody != null}"
        )
        diskBody?.let { body ->
            runCatching { parseCatalogWithDiagnostics(body, "disk") }.getOrNull()?.let {
                cached = it
                return it
            }
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it
        }

        return Catalog(emptyMap(), emptyMap())
    }

    private fun loadCatalogFromAssets(): Catalog? = runCatching {
        val readStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "catalog.assets.start", details = "operation=$readStartedNs")
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        FileLogger.memoryCheckpoint(
            TAG,
            "catalog.assets.ready",
            elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
            details = "operation=$readStartedNs chars=${body.length}"
        )
        parseCatalogWithDiagnostics(body, "assets")
    }.getOrNull()

    private fun parseCatalogWithDiagnostics(body: String, source: String): Catalog {
        val startedNs = System.nanoTime()
        val concurrent = activeCatalogParses.incrementAndGet()
        try {
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.start",
                details = "operation=$startedNs source=$source chars=${body.length} activeParses=$concurrent"
            )
            val root = json.parseToJsonElement(body)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.json.ready",
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
                details = "operation=$startedNs source=$source activeParses=${activeCatalogParses.get()}"
            )
            val catalog = parseCatalog(root)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.ready",
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
                details = "operation=$startedNs source=$source providers=${catalog.byProvider.size} models=${catalog.byProvider.values.sumOf { it.size }} activeParses=${activeCatalogParses.get()}"
            )
            return catalog
        } finally {
            activeCatalogParses.decrementAndGet()
        }
    }

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

    companion object {
        /** reasoning_options 数组（统一成 JsonObject 列表，非数组/空返回空表）。 */
        private fun reasoningOptionEntries(reasoningOptions: JsonElement?): List<JsonObject> =
            reasoningOptions?.takeIf { it !is JsonNull }
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .orEmpty()

        /** 从 models.dev 的 reasoning_options 数组中提取 effort 类型的档位 values；无 effort 档位时返回空列表。 */
        fun parseReasoningOptions(reasoningOptions: JsonElement?): List<String> =
            reasoningOptionEntries(reasoningOptions)
                .firstOrNull { it["type"]?.jsonPrimitive?.content == "effort" }
                ?.get("values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()

        /**
         * models.dev 的 `toggle` 类型：声明该模型可以开关思考。
         * 具体用哪个线上字段关闭由 [com.aicode.feature.agent.domain.provider.ReasoningResolver] 按厂商判定。
         */
        fun parseReasoningToggle(reasoningOptions: JsonElement?): Boolean =
            reasoningOptionEntries(reasoningOptions).any { it["type"]?.jsonPrimitive?.content == "toggle" }

        /** models.dev 的 `budget_tokens` 类型：返回 (min, max)，非预算型返回 null。 */
        fun parseReasoningBudget(reasoningOptions: JsonElement?): Pair<Int?, Int?>? =
            reasoningOptionEntries(reasoningOptions)
                .firstOrNull { it["type"]?.jsonPrimitive?.content == "budget_tokens" }
                ?.let { entry ->
                    entry["min"]?.jsonPrimitive?.intOrNull to entry["max"]?.jsonPrimitive?.intOrNull
                }

        /**
         * 解析后的模型目录：[byProvider] 保留目录里的原始 id 大小写；[lowerByProvider] 结构相同但键统一小写
         * （复用同一批 [ModelMetadata] 实例），用于大小写不敏感匹配。
         */
        internal data class Catalog(
            val byProvider: Map<String, Map<String, ModelMetadata>>,
            val lowerByProvider: Map<String, Map<String, ModelMetadata>>
        )

        internal fun parseCatalog(root: JsonElement): Catalog {
            val byProvider = root.jsonObject.mapValues { (providerId, providerEl) ->
                val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
                models.mapValues { (_, modelEl) ->
                    val model = modelEl.jsonObject
                    val limit = model["limit"]?.jsonObject
                    val modalities = model["modalities"]?.jsonObject
                    val inputModalities = modalities?.get("input")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val outputModalities = modalities?.get("output")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val cost = model["cost"]?.jsonObject
                    val reasoningOptions = model["reasoning_options"]
                    val reasoningLevels = parseReasoningOptions(reasoningOptions)
                    val reasoningBudget = parseReasoningBudget(reasoningOptions)
                    ModelMetadata(
                        id = model["id"]?.jsonPrimitive?.content ?: "",
                        providerId = providerId,
                        displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                        contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                        inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                        outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                        supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                        // 图像输出能力：models.dev 的 modalities.output 标注，或 Nano Banana 系 id 后缀兜底
                        // （内置快照缺 output 模态时也能识别 gemini-*-image 模型）。
                        supportsImageOutput = "image" in outputModalities ||
                            (model["id"]?.jsonPrimitive?.content.orEmpty().endsWith("-image")),
                        supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsCustomTemperature = model["temperature"]?.jsonPrimitive?.booleanOrNull == true,
                        reasoningLevels = reasoningLevels.takeIf { it.isNotEmpty() },
                        reasoningCanDisable = parseReasoningToggle(reasoningOptions),
                        reasoningBudgetMin = reasoningBudget?.first,
                        reasoningBudgetMax = reasoningBudget?.second,
                        inputCostUsdPerM = cost?.get("input")?.jsonPrimitive?.doubleOrNull,
                        outputCostUsdPerM = cost?.get("output")?.jsonPrimitive?.doubleOrNull,
                        cacheReadCostUsdPerM = cost?.get("cache_read")?.jsonPrimitive?.doubleOrNull,
                        cacheWriteCostUsdPerM = cost?.get("cache_write")?.jsonPrimitive?.doubleOrNull,
                        source = ModelMetadata.Source.MODELS_DEV
                    )
                }
            }
            return Catalog(
                byProvider = byProvider,
                lowerByProvider = byProvider.mapValues { (_, models) ->
                    models.mapKeys { (id, _) -> id.lowercase() }
                }
            )
        }

        /**
         * 模型 id 匹配：每轮完整名称（精确 → 忽略大小写）优先于已知后缀候选，再逐级剥离 vendor 前缀重试；
         * 所有候选落空后才去掉最后一个连字符后缀一次，每次查找都先偏好 provider，再退到全目录。
         */
        internal fun findMetadata(
            catalog: Catalog,
            type: ProviderType,
            modelId: String
        ): ModelMetadata? {
            val preferred = preferredProviders(type)
            val normalized = if (modelId.startsWith(MODELS_PREFIX, ignoreCase = true)) {
                modelId.substring(MODELS_PREFIX.length)
            } else {
                modelId
            }
            val names = listOf(normalized) + vendorStrippedCandidates(normalized)
            for (name in names) {
                lookup(catalog.byProvider, preferred, listOf(name))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(name.lowercase()))?.let { return it }
                lookup(catalog.byProvider, preferred, strippedCandidates(name).drop(1))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, strippedCandidates(name.lowercase()).drop(1))?.let { return it }
            }
            for (name in names) {
                val lastDash = name.lastIndexOf('-')
                if (lastDash <= name.lastIndexOf('/') + 1) continue
                val candidate = name.substring(0, lastDash)
                lookup(catalog.byProvider, preferred, listOf(candidate))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(candidate.lowercase()))?.let { return it }
            }
            return null
        }

        private fun preferredProviders(type: ProviderType): List<String> = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        /** 在 [catalog]（键大小写已由调用方对齐）里按候选顺序查找：先偏好 provider，再全目录同名键。 */
        private fun lookup(
            catalog: Map<String, Map<String, ModelMetadata>>,
            preferred: List<String>,
            candidates: List<String>
        ): ModelMetadata? {
            for (candidate in candidates) {
                for (provider in preferred) {
                    catalog[provider]?.get(candidate)?.let { return it }
                }
                catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
            }
            return null
        }

        /**
         * 逐级剥离 vendor 前缀后的候选（`z-ai/glm-5.3-flash` → `glm-5.3-flash`，
         * `@cf/zai-org/glm-5.3-flash` → `zai-org/glm-5.3-flash` → `glm-5.3-flash`）。
         */
        private fun vendorStrippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf<String>()
            var current = modelId
            while (true) {
                val slash = current.indexOf('/')
                if (slash < 0 || slash == current.lastIndex) break
                current = current.substring(slash + 1)
                candidates.add(current)
            }
            return candidates
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

        const val TAG = "ModelMetadataService"
        const val ASSET_FILE_NAME = "api.official.json"

        /** 承载 models.dev 全量快照的独立数据分支（CI 每日同步，main 只保留人工提交）。 */
        const val MODELS_BRANCH = "data"

        /** 统一拉取器中的仓库相对路径：本仓库维护的 models.dev 全量快照（CI 每日同步至 [MODELS_BRANCH]）。 */
        const val MODELS_REPO_PATH = "data/models.json"
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000

        /** Gemini 风格的模型 id 前缀（`models/gemini-2.5-pro`），匹配前剥离，忽略大小写。 */
        private const val MODELS_PREFIX = "models/"

        /** 兜底模糊匹配：依次尝试剥离的模型 id 后缀。 */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
    }
}