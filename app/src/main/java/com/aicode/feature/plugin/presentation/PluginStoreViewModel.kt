package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.index.NoMatchingReleaseException
import com.aicode.feature.plugin.data.index.PluginIndexClient
import com.aicode.feature.plugin.data.index.PluginIndexException
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.data.index.PluginOfficialRepositories
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    val officialSource: Boolean
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
    val sources: List<PluginIndexSource> = emptyList(),
    val items: List<PluginStoreItem> = emptyList(),
    /** 每个已安装插件可用的最高版本更新条目；供已安装页展示「可更新」。 */
    val updatesByPluginId: Map<String, PluginStoreItem> = emptyMap(),
    val error: PluginStoreError? = null,
    val installingPluginId: String? = null,
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
        loadAll(force = true)
    }

    /** 按来源顺序依次拉取，条目也按来源顺序叠加；单个来源失败时保留它上次的条目。 */
    private fun loadAll(force: Boolean) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        val token = ++loadToken
        _state.update { it.copy(loading = true, refreshing = it.items.isNotEmpty(), error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val installed = manager.plugins.value.associate { it.id to it.record.version }
            val previous = _state.value.items.groupBy { it.source.id }
            val items = mutableListOf<PluginStoreItem>()
            var firstError: PluginStoreError? = null
            var failureCount = 0
            sources.forEach { source ->
                val result = runCatching { client.fetchIndex(source, forceRefresh = force) }
                if (token != loadToken) return@launch
                result.onSuccess { snapshot ->
                    items += snapshot.index.plugins.map { plugin ->
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
                    items += previous[source.id].orEmpty()
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

    fun install(item: PluginStoreItem) {
        if (!item.compatible) return
        if (_state.value.installingPluginId != null) return
        _state.update { it.copy(installingPluginId = item.plugin.id, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val asset = client.resolveRelease(item.plugin)
                val packageFile = client.download(asset)
                val result = packageFile.file.inputStream().use { manager.preview(it) }
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
                                    officialSource = official
                                )
                            )
                        }
                    }
                    is PluginPreviewResult.Failed -> _state.update {
                        it.copy(
                            installingPluginId = null,
                            error = PluginStoreError(
                                code = PluginStoreErrorCode.PREVIEW_FAILED,
                                pluginError = result.errors.firstOrNull(),
                                pluginId = item.plugin.id
                            )
                        )
                    }
                }
            } catch (e: NoMatchingReleaseException) {
                _state.update {
                    it.copy(
                        installingPluginId = null,
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
                        error = PluginStoreError(
                            code = PluginStoreErrorCode.DOWNLOAD_FAILED,
                            detail = e.message,
                            pluginId = item.plugin.id
                        )
                    )
                }
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
