package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.index.NoMatchingReleaseException
import com.aicode.feature.plugin.data.index.PluginIndexClient
import com.aicode.feature.plugin.data.index.PluginIndexException
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginLogoCacheEntry
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.data.index.PluginOfficialRepositories
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.data.index.isPluginIndexCacheFresh
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginStoreItem(
    val plugin: PluginIndexPlugin,
    val source: PluginIndexSource,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val compatible: Boolean,
    /** 插件源码仓库是否归属 AiCode 官方账号。 */
    val official: Boolean
)

data class PluginStorePreview(
    val preview: PluginPreview,
    val origin: PluginInstallOrigin,
    val expectedRepository: String?,
    val sourceLabel: String,
    val officialSource: Boolean,
    val installImmediately: Boolean
)

enum class PluginStoreErrorCode {
    NETWORK,
    INVALID_INDEX,
    NO_COMPATIBLE_RELEASE,
    DOWNLOAD_FAILED,
    PREVIEW_FAILED
}

data class PluginStoreError(
    val code: PluginStoreErrorCode,
    val detail: String? = null,
    val pluginError: PluginError? = null,
    /** 安装/下载错误所属的插件；索引错误为 null。 */
    val pluginId: String? = null
)

data class PluginStoreUiState(
    /** 首次读取来源前就显示加载态，避免先闪一下空列表。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val logoRefreshVersion: Long = 0,
    val sources: List<PluginIndexSource> = emptyList(),
    val items: List<PluginStoreItem> = emptyList(),
    /** 每个已安装插件可用的最高版本更新条目；供已安装页展示「可更新」。 */
    val updatesByPluginId: Map<String, PluginStoreItem> = emptyMap(),
    val error: PluginStoreError? = null,
    val installingPluginId: String? = null,
    val downloadProgress: Float? = null,
    val preview: PluginStorePreview? = null
)

@HiltViewModel
class PluginStoreViewModel @Inject constructor(
    private val client: PluginIndexClient,
    private val sourceRepository: PluginIndexSourceRepository,
    private val manager: PluginManager
) : ViewModel() {

    private val _state = MutableStateFlow(PluginStoreUiState())
    val state: StateFlow<PluginStoreUiState> = _state.asStateFlow()

    /** 一次加载结束后要提示的失败来源数；仅在用户下拉刷新或确实有来源失败时发送。 */
    private val _refreshResult = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val refreshResult: SharedFlow<Int> = _refreshResult.asSharedFlow()

    /** 来源地址列表变化时才重新拉取；markFetched 等只改时间戳的写入不会触发加载。 */
    private var loadedUrls: List<String>? = null
    private var loadToken = 0
    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            sourceRepository.sources.collect { sources ->
                _state.update { it.copy(sources = sources) }
                val urls = sources.map { it.indexUrl }
                if (urls != loadedUrls) {
                    loadedUrls = urls
                    loadAll(force = false)
                }
            }
        }
        viewModelScope.launch {
            manager.plugins.collect { installed ->
                val versions = installed.associate { it.id to it.record.version }
                _state.update { current ->
                    val updatedItems = current.items.map { item ->
                        val version = versions[item.plugin.id]
                        item.copy(
                            installedVersion = version,
                            updateAvailable = isNewer(item.plugin.version, version)
                        )
                    }
                    current.copy(
                        items = updatedItems,
                        updatesByPluginId = updatesOf(updatedItems)
                    )
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(logoRefreshVersion = it.logoRefreshVersion + 1) }
        loadAll(force = true)
    }

    fun openStore() {
        if (_state.value.loading) return
        val now = System.currentTimeMillis()
        if (_state.value.sources.any { !isPluginIndexCacheFresh(it.lastFetchedAt, now) }) {
            loadAll(force = false)
        }
    }

    suspend fun readPluginLogoCache(url: String): PluginLogoCacheEntry? =
        client.readPluginLogoCache(url)

    suspend fun refreshPluginLogo(url: String): PluginLogoCacheEntry? =
        client.refreshPluginLogo(url)

    /** 按来源顺序先展示磁盘缓存，再拉取网络索引并叠加结果。 */
    private fun loadAll(force: Boolean) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        val token = ++loadToken
        _state.update { it.copy(loading = true, refreshing = it.items.isNotEmpty(), error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = manager.plugins.value.associate { it.id to it.record.version }
            val previous = _state.value.items.groupBy { it.source.id }
            val cachedItems = mutableListOf<PluginStoreItem>()
            sources.forEach { source ->
                if (token != loadToken) return@launch
                val cached = runCatching { client.readCachedIndex(source) }.getOrNull()
                if (cached != null) {
                    cachedItems += storeItems(source, cached.index.plugins, installed)
                } else {
                    cachedItems += previous[source.id].orEmpty()
                }
            }
            if (token != loadToken) return@launch
            _state.update {
                it.copy(
                    items = cachedItems,
                    updatesByPluginId = updatesOf(cachedItems),
                    refreshing = cachedItems.isNotEmpty()
                )
            }
            val cachedItemsBySource = cachedItems.groupBy { it.source.id }

            val items = mutableListOf<PluginStoreItem>()
            var firstError: PluginStoreError? = null
            var failureCount = 0
            sources.forEach { source ->
                val result = runCatching { client.fetchIndex(source, forceRefresh = force) }
                if (token != loadToken) return@launch
                result.onSuccess { snapshot ->
                    items += storeItems(source, snapshot.index.plugins, installed)
                    if (!snapshot.stale) {
                        sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                    }
                }.onFailure { e ->
                    FileLogger.w(TAG, "读取插件索引失败: ${source.id}", e)
                    failureCount++
                    if (firstError == null) {
                        firstError = PluginStoreError(
                            code = if (e is PluginIndexException) {
                                PluginStoreErrorCode.INVALID_INDEX
                            } else {
                                PluginStoreErrorCode.NETWORK
                            },
                            detail = e.message
                        )
                    }
                    items += cachedItemsBySource[source.id].orEmpty()
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    items = items,
                    updatesByPluginId = updatesOf(items),
                    error = firstError
                )
            }
            if (force || failureCount > 0) {
                _refreshResult.tryEmit(failureCount)
            }
        }
    }

    private fun storeItems(
        source: PluginIndexSource,
        plugins: List<PluginIndexPlugin>,
        installed: Map<String, String>
    ): List<PluginStoreItem> = plugins.map { plugin ->
        val installedVersion = installed[plugin.id]
        PluginStoreItem(
            plugin = plugin,
            source = source,
            installedVersion = installedVersion,
            updateAvailable = isNewer(plugin.version, installedVersion),
            compatible = client.isCompatible(plugin),
            official = PluginOfficialRepositories.isOfficial(plugin.repository)
        )
    }

    fun install(item: PluginStoreItem, confirmedUnofficial: Boolean = false) {
        if (!item.compatible) return
        if (_state.value.installingPluginId != null) return
        _state.update {
            it.copy(installingPluginId = item.plugin.id, downloadProgress = null, error = null)
        }
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val currentJob = currentCoroutineContext()[Job]
            try {
                val asset = client.resolveRelease(item.plugin)
                val packageFile = client.download(asset) { downloadedBytes, totalBytes ->
                    val progress = (downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    _state.update { current ->
                        if (current.installingPluginId == item.plugin.id) {
                            current.copy(downloadProgress = progress)
                        } else {
                            current
                        }
                    }
                }
                val result = packageFile.file.inputStream().use { manager.preview(it) }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is PluginPreviewResult.Ready -> {
                        val preview = result.preview
                        val manifestVersion = preview.manifest.version.toString()
                        if (preview.manifest.id != item.plugin.id ||
                            preview.manifest.publisher != item.plugin.author ||
                            preview.manifest.repository != item.plugin.repository ||
                            asset.version != manifestVersion
                        ) {
                            manager.discard(preview.token)
                            _state.update {
                                it.copy(
                                    installingPluginId = null,
                                    downloadProgress = null,
                                    error = PluginStoreError(
                                        code = PluginStoreErrorCode.PREVIEW_FAILED,
                                        pluginId = item.plugin.id
                                    )
                                )
                            }
                            return@launch
                        }
                        val official = item.official
                        _state.update {
                            it.copy(
                                installingPluginId = null,
                                downloadProgress = null,
                                preview = PluginStorePreview(
                                    preview = preview,
                                    origin = PluginInstallOrigin(
                                        kind = PluginInstallOrigin.KIND_INDEX,
                                        sourceId = item.source.id,
                                        sourceName = item.source.name,
                                        indexUrl = item.source.indexUrl,
                                        repository = item.plugin.repository,
                                        official = official
                                    ),
                                    expectedRepository = if (official) null else item.plugin.repository,
                                    sourceLabel = item.source.name,
                                    officialSource = official,
                                    installImmediately = official || confirmedUnofficial
                                )
                            )
                        }
                    }
                    is PluginPreviewResult.Failed -> _state.update {
                        it.copy(
                            installingPluginId = null,
                            downloadProgress = null,
                            error = PluginStoreError(
                                code = PluginStoreErrorCode.PREVIEW_FAILED,
                                pluginError = result.errors.firstOrNull(),
                                pluginId = item.plugin.id
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoMatchingReleaseException) {
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        downloadProgress = null,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.NO_COMPATIBLE_RELEASE,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "下载插件失败: ${item.plugin.id}", e)
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        downloadProgress = null,
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.DOWNLOAD_FAILED,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
            } finally {
                if (installJob === currentJob) installJob = null
            }
        }
        installJob = job
        job.start()
    }

    fun cancelInstall() {
        val pluginId = _state.value.installingPluginId ?: return
        val job = installJob ?: return
        installJob = null
        job.cancel()
        _state.update { current ->
            if (current.installingPluginId == pluginId) {
                current.copy(installingPluginId = null, downloadProgress = null, error = null)
            } else {
                current
            }
        }
    }

    fun consumePreview() {
        _state.update { it.copy(preview = null) }
    }

    private fun isNewer(candidate: String?, installed: String?): Boolean {
        if (candidate == null || installed == null) return false
        val left = SemVer.parse(candidate) ?: return false
        val right = SemVer.parse(installed) ?: return false
        return left > right
    }

    /** 每个插件取一个可用的最高版本，同一插件出现在多个来源时只提示一次。 */
    private fun updatesOf(items: List<PluginStoreItem>): Map<String, PluginStoreItem> =
        items.asSequence()
            .filter { it.updateAvailable && it.compatible }
            .groupBy { it.plugin.id }
            .mapValues { (_, candidates) ->
                candidates.maxByOrNull { item ->
                    item.plugin.version?.let(SemVer::parse) ?: SemVer(0, 0, 0)
                }!!
            }

    private companion object {
        const val TAG = "PluginStoreViewModel"
    }
}
