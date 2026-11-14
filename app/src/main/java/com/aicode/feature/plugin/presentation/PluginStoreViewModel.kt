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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginStoreItem(
    val plugin: PluginIndexPlugin,
    val source: PluginIndexSource,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val compatible: Boolean
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
    val pluginError: PluginError? = null
)

data class PluginStoreUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val sources: List<PluginIndexSource> = emptyList(),
    val selectedSourceId: String? = null,
    val items: List<PluginStoreItem> = emptyList(),
    val stale: Boolean = false,
    val fetchedAt: Long = 0,
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

    init {
        viewModelScope.launch {
            sourceRepository.sources.collect { sources ->
                val enabled = sources.filter { it.enabled }
                val selected = _state.value.selectedSourceId
                    ?.takeIf { id -> enabled.any { it.id == id } }
                    ?: enabled.firstOrNull()?.id
                _state.update { it.copy(sources = sources, selectedSourceId = selected) }
                if (selected != null && _state.value.items.isEmpty() && !_state.value.loading) {
                    load(selected)
                }
            }
        }
        viewModelScope.launch {
            manager.plugins.collect { installed ->
                val versions = installed.associate { it.id to it.record.version }
                _state.update { current ->
                    current.copy(
                        items = current.items.map { item ->
                            val version = versions[item.plugin.id]
                            item.copy(
                                installedVersion = version,
                                updateAvailable = isNewer(item.plugin.version, version)
                            )
                        }
                    )
                }
            }
        }
    }

    fun selectSource(id: String) {
        if (id == _state.value.selectedSourceId) return
        _state.update { it.copy(selectedSourceId = id, items = emptyList(), error = null) }
        load(id)
    }

    fun refresh() {
        val id = _state.value.selectedSourceId ?: return
        load(id, force = true)
    }

    fun load(id: String, force: Boolean = false) {
        val source = _state.value.sources.firstOrNull { it.id == id } ?: return
        loadSource(source, force)
    }

    private fun loadSource(source: PluginIndexSource, force: Boolean) {
        if (_state.value.loading && !force) return
        _state.update {
            it.copy(
                loading = true,
                refreshing = it.items.isNotEmpty(),
                error = null,
                selectedSourceId = source.id
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = client.fetchIndex(source, forceRefresh = force)
                val installed = manager.plugins.value.associate { it.id to it.record.version }
                val items = snapshot.index.plugins.map { plugin ->
                    val installedVersion = installed[plugin.id]
                    PluginStoreItem(
                        plugin = plugin,
                        source = source,
                        installedVersion = installedVersion,
                        updateAvailable = isNewer(plugin.version, installedVersion),
                        compatible = client.isCompatible(plugin)
                    )
                }
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = items,
                        stale = snapshot.stale,
                        fetchedAt = snapshot.fetchedAt,
                        error = null
                    )
                }
                if (!snapshot.stale) {
                    sourceRepository.markFetched(source.id, snapshot.fetchedAt)
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "读取插件索引失败: ${source.id}", e)
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = PluginStoreError(
                            code = if (e is PluginIndexException) {
                                PluginStoreErrorCode.INVALID_INDEX
                            } else {
                                PluginStoreErrorCode.NETWORK
                            },
                            detail = e.message
                        )
                    )
                }
            }
        }
    }

    fun install(item: PluginStoreItem) {
        if (_state.value.stale || !item.compatible) return
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
                                    error = PluginStoreError(PluginStoreErrorCode.PREVIEW_FAILED)
                                )
                            }
                            return@launch
                        }
                        val official = PluginOfficialRepositories.isOfficial(item.plugin.repository)
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
                                pluginError = result.errors.firstOrNull()
                            )
                        )
                    }
                }
            } catch (e: NoMatchingReleaseException) {
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        error = PluginStoreError(PluginStoreErrorCode.NO_COMPATIBLE_RELEASE, e.message)
                    )
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "下载插件失败: ${item.plugin.id}", e)
                _state.update {
                    it.copy(
                        installingPluginId = null,
                        error = PluginStoreError(PluginStoreErrorCode.DOWNLOAD_FAILED, e.message)
                    )
                }
            }
        }
    }

    fun consumePreview() {
        _state.update { it.copy(preview = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun addSource(name: String, url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sourceRepository.add(name, url)
            if (result.isSuccess) {
                val added = result.getOrNull()!!
                sourceRepository.sources.first { list -> list.any { it.id == added.id } }
                withContext(Dispatchers.Main) {
                    onResult(true)
                    _state.update { it.copy(selectedSourceId = added.id, items = emptyList()) }
                }
                loadSource(added, force = false)
            } else {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun removeSource(id: String) {
        viewModelScope.launch(Dispatchers.IO) { sourceRepository.remove(id) }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { sourceRepository.setEnabled(id, enabled) }
    }

    private fun isNewer(candidate: String?, installed: String?): Boolean {
        if (candidate == null || installed == null) return false
        val left = SemVer.parse(candidate) ?: return false
        val right = SemVer.parse(installed) ?: return false
        return left > right
    }

    private companion object {
        const val TAG = "PluginStoreViewModel"
    }
}
