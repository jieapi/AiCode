package com.aicode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LogLevel
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.RecentCallRecord
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerImageCatalog
import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.domain.mcp.McpToolDescriptor
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.data.remote.ContainerImageDownloader
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import com.aicode.feature.settings.data.remote.UpdateCheckService
import com.aicode.feature.settings.data.repository.UpdateCheckSettingsRepository
import com.aicode.feature.settings.data.repository.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.ExecutionModeRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.repository.RemoteRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderBalanceResult
import com.aicode.feature.settings.domain.model.ProviderBalanceState
import com.aicode.feature.settings.domain.service.ProviderBalanceRunner
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.R
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>, val debugInfo: ModelTestResult? = null) : FetchState()
    data class Error(val message: String, val debugInfo: ModelTestResult? = null) : FetchState()
}

/** 镜像下载页的完整 UI 状态：空闲 / 下载中（进度）/ 下载完成（可安装）/ 失败。 */
sealed interface ContainerImageDownloadUiState {
    data object Idle : ContainerImageDownloadUiState
    data class Downloading(
        val entryId: String,
        val name: String,
        val version: String,
        val sourceId: String,
        val bytesRead: Long,
        val totalBytes: Long
    ) : ContainerImageDownloadUiState
    data class Done(
        val entryId: String,
        val name: String,
        val version: String,
        val fileUri: String
    ) : ContainerImageDownloadUiState
    data class Error(val entryId: String, val message: String) : ContainerImageDownloadUiState
}

data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val content: String = "",
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/** 检查更新弹窗状态（全局弹窗宿主在主页 AppNavigation 层渲染，自动/手动共用）。 */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data object UpToDate : UpdateCheckUiState
    data class NewVersion(val latestTag: String, val changelog: String) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

/** Token 统计周期：决定统计起始时间与趋势粒度（今天=小时粒度，其余=天粒度）。 */
enum class TokenStatsPeriod(val labelRes: Int, val startMillis: (Long) -> Long) {
    TODAY(R.string.settings_token_stats_period_today, { now ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }),
    LAST_7_DAYS(R.string.settings_token_stats_period_7d, { now -> now - 7 * 86_400_000L }),
    LAST_30_DAYS(R.string.settings_token_stats_period_30d, { now -> now - 30 * 86_400_000L }),
    ALL(R.string.settings_token_stats_period_all, { 0L })
}

/** Token 统计页的完整 UI 状态，由周期 + 5 个聚合 Flow 组合而成。 */
data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: com.aicode.feature.agent.data.local.dao.CallSummary? = null,
    val trend: List<com.aicode.feature.agent.data.local.dao.DayCallStats> = emptyList(),
    val providers: List<com.aicode.feature.agent.data.local.dao.ProviderCallStats> = emptyList(),
    val models: List<com.aicode.feature.agent.data.local.dao.ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    /** 调用明细当前页（0 起）。 */
    val callsPage: Int = 0,
    /** 当前周期调用总数，供分页显示总页数。 */
    val callsTotal: Int = 0,
    /** 当前周期总费用（USD，按 models.dev 单价估算）。 */
    val totalCostUsd: Double = 0.0,
    /** 调用明细分页内每条记录的费用（key=记录 id，null=模型无单价）。 */
    val recentCallCosts: Map<Long, Double?> = emptyMap()
)

/** 技能列表页的 UI 状态：技能 + 来源作用域 + 启停状态。 */
data class SkillUiEntry(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val disabled: Boolean,
    val instructions: String
)

/**
 * 把趋势聚合补全为周期内的完整时间轴：无记录的天/小时补 0，避免周期内调用集中在同一天时
 * 趋势只有单个点而无法绘制折线图。「全部」周期从最早有记录的那天起补到当天。
 */
private fun padTrend(
    period: TokenStatsPeriod,
    trend: List<com.aicode.feature.agent.data.local.dao.DayCallStats>,
    tzOffsetMillis: Long
): List<com.aicode.feature.agent.data.local.dao.DayCallStats> {
    if (trend.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val byDay = trend.associateBy { it.day }
    val isHourly = period == TokenStatsPeriod.TODAY
    val bucketMillis = if (isHourly) 3_600_000L else 86_400_000L
    val endIndex = (now + tzOffsetMillis) / bucketMillis
    val startIndex = when (period) {
        TokenStatsPeriod.TODAY -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
        TokenStatsPeriod.ALL -> trend.first().day
        else -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
    }
    if (startIndex > endIndex) return trend
    return (startIndex..endIndex).map { index ->
        byDay[index] ?: com.aicode.feature.agent.data.local.dao.DayCallStats(index, 0, 0, 0, 0, 0, null, null)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val backgroundSettingsRepository: BackgroundSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val skillRepository: SkillRepository,
    private val skillConfigRepository: SkillConfigRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerImageCatalog: ContainerImageCatalog,
    private val containerImageDownloader: ContainerImageDownloader,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val updateCheckSettingsRepository: UpdateCheckSettingsRepository,
    private val updateCheckService: UpdateCheckService,
    private val providerBalanceRunner: ProviderBalanceRunner,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val proxySettingsRepository: ProxySettingsRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200
        const val CALLS_PAGE_SIZE = 10
        /** 缓存读价缺失时按输入价的折扣估算。 */
        const val CACHE_READ_DISCOUNT = 0.1
    }

    /** 终端个性化配置。 */
    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalSettings())

    fun setTerminalTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setTerminalCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    // ── 全局代理 ──

    /** 全局代理配置快照（SharedPreferences 即时读写，改即对新连接生效）。 */
    val proxyConfig: StateFlow<ProxyConfig> = proxySettingsRepository.config

    /** 代理连通性测试状态。 */
    sealed interface ProxyTestUiState {
        data object Idle : ProxyTestUiState
        data object Testing : ProxyTestUiState
        data class Success(val message: String) : ProxyTestUiState
        data class Error(val message: String) : ProxyTestUiState
    }

    private val _proxyTestState = MutableStateFlow<ProxyTestUiState>(ProxyTestUiState.Idle)
    val proxyTestState: StateFlow<ProxyTestUiState> = _proxyTestState.asStateFlow()

    fun setProxyEnabled(enabled: Boolean) = proxySettingsRepository.setEnabled(enabled)
    fun setProxyType(type: ProxyType) = proxySettingsRepository.setType(type)
    fun setProxyHost(host: String) = proxySettingsRepository.setHost(host)
    fun setProxyPort(port: Int) = proxySettingsRepository.setPort(port)
    fun setProxyUsername(username: String) = proxySettingsRepository.setUsername(username)
    fun setProxyPassword(password: String) = proxySettingsRepository.setPassword(password)
    fun setProxyNoProxy(noProxy: String) = proxySettingsRepository.setNoProxy(noProxy)

    fun testProxy(probeUrl: String) = testProxy(proxySettingsRepository.config.value, probeUrl)

    /** 测任意一份代理配置（供提供商代理独立页复用表单态配置）。 */
    fun testProxy(cfg: ProxyConfig, probeUrl: String) {
        if (_proxyTestState.value is ProxyTestUiState.Testing) return
        _proxyTestState.value = ProxyTestUiState.Testing
        viewModelScope.launch {
            val result = AppProxy.testProxy(context, cfg, probeUrl)
            _proxyTestState.value = if (result.ok) {
                ProxyTestUiState.Success(result.message)
            } else {
                ProxyTestUiState.Error(result.message)
            }
        }
    }

    private val _providers = MutableStateFlow<List<AIProviderConfig>>(emptyList())
    val providers: StateFlow<List<AIProviderConfig>> = _providers.asStateFlow()

    /** 新会话默认模型（主页空会话中选择后记忆），供未绑定会话回退。 */
    val defaultModelProviderId: StateFlow<String> = defaultModelSettingsRepository.providerIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultModel: StateFlow<String> = defaultModelSettingsRepository.modelFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 识图模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _visionProviderId = MutableStateFlow("")
    val visionProviderId: StateFlow<String> = _visionProviderId.asStateFlow()

    private val _visionModel = MutableStateFlow("")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    /** 压缩专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _compactionProviderId = MutableStateFlow("")
    val compactionProviderId: StateFlow<String> = _compactionProviderId.asStateFlow()

    private val _compactionModel = MutableStateFlow("")
    val compactionModel: StateFlow<String> = _compactionModel.asStateFlow()

    /** 标题总结专用模型选择：providerId 为空即「跟随当前聊天模型」。 */
    private val _titleProviderId = MutableStateFlow("")
    val titleProviderId: StateFlow<String> = _titleProviderId.asStateFlow()

    private val _titleModel = MutableStateFlow("")
    val titleModel: StateFlow<String> = _titleModel.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    private val _updateCheckEnabled = MutableStateFlow(updateCheckSettingsRepository.autoCheckEnabled)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    private val _updateCheckChannel = MutableStateFlow(updateCheckSettingsRepository.channel)
    val updateCheckChannel: StateFlow<UpdateChannel> = _updateCheckChannel.asStateFlow()

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _agentSoundEnabled = MutableStateFlow(false)
    val agentSoundEnabled: StateFlow<Boolean> = _agentSoundEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    /** 全局自定义背景图文件路径（null=未设置），供设置弹窗展示与预览。 */
    private val _backgroundImagePath = MutableStateFlow<String?>(null)
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /** 背景图不透明度（0.05~1.0），实时写 DataStore，全局背景同步变化。 */
    private val _backgroundAlpha = MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** 用户选择的应用语言 tag（null 表示跟随系统）。 */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillUiEntry>>(emptyList())
    val skills: StateFlow<List<SkillUiEntry>> = _skills.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _balanceTestState = MutableStateFlow<ProviderBalanceState>(ProviderBalanceState.Idle)
    val balanceTestState: StateFlow<ProviderBalanceState> = _balanceTestState.asStateFlow()

    private val _providerBalances = MutableStateFlow<Map<String, ProviderBalanceState>>(emptyMap())
    val providerBalances: StateFlow<Map<String, ProviderBalanceState>> = _providerBalances.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _activeProfileId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _defaultContainerId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val defaultContainerId: StateFlow<String> = _defaultContainerId.asStateFlow()

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** 全部 profile（内置 Alpine 也作为普通一项持久化在列表里，首次启动自动写入）。 */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, listOf(ContainerProfile.BUILTIN_ALPINE))

    /** 各容器已识别的系统类型（profile id → os id），UI 据此显示对应系统图标。 */
    val containerOsMap: StateFlow<Map<String, String>> = containerOsDetector.osMap

    /** 公告内容（跟随界面语言），与 [containerAnnouncementOutdated] 配套供弹窗渲染。 */
    private val _containerAnnouncementText = MutableStateFlow("")
    val containerAnnouncementText: StateFlow<String> = _containerAnnouncementText.asStateFlow()

    /** 公告是否需要弹出：本地未存哈希或与当前内容哈希不一致（内容更新过）时为 true。 */
    private val _containerAnnouncementOutdated = MutableStateFlow(false)
    val containerAnnouncementOutdated: StateFlow<Boolean> = _containerAnnouncementOutdated.asStateFlow()

    private val _imageCatalog = MutableStateFlow<List<ContainerImageEntry>>(emptyList())
    val imageCatalog: StateFlow<List<ContainerImageEntry>> = _imageCatalog.asStateFlow()

    /** 全局下载源 id 列表（来自目录 JSON 的 sources 键，保持顺序），供右上角切换。 */
    private val _imageSourceOptions = MutableStateFlow<List<String>>(emptyList())
    val imageSourceOptions: StateFlow<List<String>> = _imageSourceOptions.asStateFlow()

    /** 当前选中的下载源（默认官方）。 */
    private val _selectedImageSource = MutableStateFlow("official")
    val selectedImageSource: StateFlow<String> = _selectedImageSource.asStateFlow()

    /** 已下载/已安装的镜像记录（entryId → 记录），跨重启保留。 */
    private val _downloadedImages = MutableStateFlow<Map<String, DownloadedImageRecord>>(emptyMap())
    val downloadedImages: StateFlow<Map<String, DownloadedImageRecord>> = _downloadedImages.asStateFlow()

    /** 当前选中源下无下载 URL 的镜像 id（如腾讯云不提供 Ubuntu），UI 据此禁用下载按钮。 */
    private val _sourceUnavailableIds = MutableStateFlow<Set<String>>(emptySet())
    val sourceUnavailableIds: StateFlow<Set<String>> = _sourceUnavailableIds.asStateFlow()

    private val _containerImageDownload = MutableStateFlow<ContainerImageDownloadUiState>(ContainerImageDownloadUiState.Idle)
    val containerImageDownload: StateFlow<ContainerImageDownloadUiState> = _containerImageDownload.asStateFlow()

    /** 当前下载任务句柄，取消下载时 cancel 它（底层 OkHttp call 同步中断）。 */
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** 当前执行模式（本地 PRoot / 远程 SSH），供 UI 判断是否显示远程连接指示器。 */
    val executionMode: StateFlow<ExecutionMode> = executionModeHolder.mode

    /** 远程 SSH 连接状态，供 UI 显示指示器。 */
    val connectionState: StateFlow<ConnectionState> = remoteSshConnection.connectionState

    /** Token 统计：当前选中的统计周期。 */
    private val _tokenStatsPeriod = MutableStateFlow(TokenStatsPeriod.LAST_7_DAYS)
    val tokenStatsPeriod: StateFlow<TokenStatsPeriod> = _tokenStatsPeriod.asStateFlow()

    /** Token 统计：周期内汇总、趋势、渠道、模型、明细的组合状态。 */
    private val _tokenStats = MutableStateFlow(TokenStatsUiState())
    val tokenStats: StateFlow<TokenStatsUiState> = _tokenStats.asStateFlow()

    /** Token 统计：调用明细分页页码（0 起）。 */
    private val _tokenStatsPage = MutableStateFlow(0)

    /** 工作区已配置的远程连接通道，供容器镜像 SSH 模式下拉复用。 */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        _imageCatalog.value = containerImageCatalog.load()
        _imageSourceOptions.value = containerImageCatalog.sourceIds
        _selectedImageSource.value = containerImageCatalog.sourceIds.firstOrNull { it == "official" }
            ?: containerImageCatalog.sourceIds.firstOrNull() ?: "official"
        // 已下载/已安装记录跨重启保留
        viewModelScope.launch {
            containerSettingsRepository.downloadedImagesFlow.collect { records ->
                _downloadedImages.value = records.associateBy { it.entryId }
            }
        }
        // 当前源下不可用的镜像集合（源切换后自动更新）
        viewModelScope.launch {
            combine(_imageCatalog, _selectedImageSource) { entries, sourceId ->
                entries.filter { containerImageCatalog.urlFor(it, sourceId, ContainerImageCatalog.CURRENT_ABI) == null }
                    .map { it.id }.toSet()
            }.collect { _sourceUnavailableIds.value = it }
        }
        // 公告按「内容哈希比对」判断是否弹出：首次（无哈希）或内容更新（哈希不一致）即弹。
        viewModelScope.launch {
            combine(_languageTag, containerSettingsRepository.announcementShownHashFlow) { tag, storedHash ->
                val text = loadContainerAnnouncement(context, tag)
                _containerAnnouncementText.value = text
                _containerAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        viewModelScope.launch {
            launch {
                repository.getAllProviders().collectLatest {
                    _providers.value = it
                }
            }

            launch {
                visionModelSettingsRepository.providerIdFlow.collectLatest {
                    _visionProviderId.value = it
                }
            }

            launch {
                visionModelSettingsRepository.modelFlow.collectLatest {
                    _visionModel.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.providerIdFlow.collectLatest {
                    _compactionProviderId.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.modelFlow.collectLatest {
                    _compactionModel.value = it
                }
            }

            launch {
                titleModelSettingsRepository.providerIdFlow.collectLatest {
                    _titleProviderId.value = it
                }
            }

            launch {
                titleModelSettingsRepository.modelFlow.collectLatest {
                    _titleModel.value = it
                }
            }

            launch {
                logSettingsRepository.levelFlow.collectLatest {
                    _logLevel.value = it
                }
            }

            launch {
                keepaliveSettingsRepository.enabledFlow.collectLatest {
                    _keepaliveEnabled.value = it
                }
            }

            launch {
                agentSoundSettingsRepository.enabledFlow.collectLatest {
                    _agentSoundEnabled.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                backgroundSettingsRepository.imagePathFlow.collectLatest {
                    _backgroundImagePath.value = it
                }
            }

            launch {
                backgroundSettingsRepository.alphaFlow.collectLatest {
                    _backgroundAlpha.value = it
                }
            }

            launch {
                languageSettingsRepository.languageFlow.collectLatest {
                    _languageTag.value = it
                }
            }

            launch {
                containerSettingsRepository.activeProfileIdFlow.collectLatest {
                    _activeProfileId.value = it
                }
            }

            launch {
                containerSettingsRepository.defaultContainerIdFlow.collectLatest {
                    _defaultContainerId.value = it
                }
            }

            launch {
                // 首次启动写入内置 Alpine 默认项（置位标记后不再自动补回）
                containerSettingsRepository.ensureBuiltinDefault()
            }

            launch {
                containerSettingsRepository.customProfilesFlow.collectLatest {
                    // 按添加时间降序（新的在前）；旧数据 createdAt 同为 0 时保持存储顺序
                    _customProfiles.value = it.sortedByDescending { profile -> profile.createdAt }
                }
            }

            launch {
                mcpConfigRepository.effectiveEntriesFlow.collectLatest {
                    _mcpEntries.value = it
                }
            }

            launch {
                skillConfigRepository.changes.collectLatest {
                    refreshSkills()
                }
            }

            launch {
                refreshSkills()
            }

            launch {
                permissionRulesRepository.globalRulesFlow.collectLatest {
                    _globalRules.value = it
                }
            }

            launch {
                permissionRulesRepository.currentProjectRulesFlow.collectLatest {
                    _projectRules.value = it
                }
            }

            launch {
                _tokenStatsPeriod.flatMapLatest { period ->
                    val start = period.startMillis(System.currentTimeMillis())
                    val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                    combine(
                        if (period == TokenStatsPeriod.TODAY) {
                            llmCallRecordDao.getHourStats(start, tz)
                        } else {
                            llmCallRecordDao.getDayStats(start, tz)
                        },
                        llmCallRecordDao.getSummary(start),
                        llmCallRecordDao.getProviderStats(start),
                        llmCallRecordDao.getModelStats(start),
                        // 明细分页：页号或总数变化时重查当前页，其余聚合不重复计算
                        combine(_tokenStatsPage, llmCallRecordDao.getCallsCount(start)) { page, total -> page to total }
                            .flatMapLatest { (page, total) ->
                                llmCallRecordDao.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE)
                                    .map { calls -> Triple(calls, total, page) }
                            }
                    ) { rawTrend, summary, providers, models, (calls, total, page) ->
                        val trend = padTrend(period, rawTrend, tz)
                        val costs = withContext(Dispatchers.IO) {
                            val perCall = calls.associate {
                                it.record.id to callCostUsd(it.record.providerId, it.record.model, it.record.inputTokens.toLong(), it.record.cachedInputTokens.toLong(), it.record.outputTokens.toLong())
                            }
                            val periodTotal = models.sumOf { m ->
                                callCostUsd(null, m.model, m.inputTokens, m.cachedInputTokens, m.outputTokens) ?: 0.0
                            }
                            perCall to periodTotal
                        }
                        TokenStatsUiState(
                            period, summary, trend, providers, models, calls, page, total,
                            totalCostUsd = costs.second,
                            recentCallCosts = costs.first
                        )
                    }
                }.collectLatest { _tokenStats.value = it }
            }
        }
    }

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        viewModelScope.launch {
            // 作用域迁移：仅当保存作用域与原来不同时，从原作用域移除旧条目，
            // 避免残留条目在合并时（项目优先）继续覆盖新作用域的配置。
            if (originalName != null && initialScope != null && initialScope != scope) {
                val oldBase = if (initialScope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
                val oldUpdated = oldBase.filterNot { it.name == originalName }
                if (initialScope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(oldUpdated) else mcpConfigRepository.setProjectServers(oldUpdated)
            }
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val ordered = LinkedHashMap<String, McpServerConfig>()
            base.forEach { ordered[it.name] = it }
            if (originalName != null && originalName != config.name) {
                ordered.remove(originalName)
            }
            ordered[config.name] = config
            val updated = ordered.values.toList()
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                // 仅重连被改动的 server，其他 server 不受影响；重命名时先断开旧名。
                if (originalName != null && originalName != config.name) {
                    mcpManager.removeServer(originalName)
                }
                mcpManager.reloadServer(config.name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name == name }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.removeServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun reloadMcp() {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 仅重连指定 server（编辑弹窗右上角刷新工具用）。 */
    fun reloadMcpServer(name: String) {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 重新扫描技能（进入设置页 / 启停切换后调用，反映磁盘上增删改）。 */
    fun refreshSkills() {
        viewModelScope.launch {
            _skills.value = withContext(Dispatchers.IO) {
                skillRepository.listAllSkills().map { entry ->
                    SkillUiEntry(
                        name = entry.skill.name,
                        description = entry.skill.description,
                        scope = entry.scope,
                        disabled = skillRepository.isSkillDisabled(entry.skill.name),
                        instructions = entry.skill.instructions
                    )
                }
            }
        }
    }

    /** 切换技能的启用/禁用状态（写入对应作用域的 skills.json）。 */
    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) {
        viewModelScope.launch {
            skillRepository.setSkillDisabled(name, !enabled, scope)
            refreshSkills()
        }
    }

    /** 删除指定作用域的技能（删除其目录，不可恢复），随后立即刷新列表。 */
    fun deleteSkill(name: String, scope: SkillScope) {
        viewModelScope.launch {
            skillRepository.deleteSkill(name, scope)
            refreshSkills()
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch {
            logSettingsRepository.setLevel(level)
        }
    }

    // ── 检查更新 ──

    /**
     * 检查更新。手动模式（关于页点击）不受开关与每日限制；自动模式（主页启动）
     * 需开关开启且当天未检测过，失败时静默（不弹错误窗），无论结果都记录当天已检。
     */
    fun checkUpdate(manual: Boolean) {
        if (!manual) {
            if (!updateCheckSettingsRepository.autoCheckEnabled) return
            if (updateCheckSettingsRepository.hasCheckedToday()) return
        }
        if (_updateCheckState.value == UpdateCheckUiState.Checking) return
        viewModelScope.launch {
            // 手动检查显示「检测中」反馈；自动检查全程无感，仅检测到新版本才弹窗
            if (manual) {
                _updateCheckState.value = UpdateCheckUiState.Checking
            }
            val result = updateCheckService.checkForUpdate(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel
            )
            _updateCheckState.value = when (result) {
                is UpdateCheckResult.UpToDate -> {
                    if (manual) UpdateCheckUiState.UpToDate else UpdateCheckUiState.Idle
                }
                is UpdateCheckResult.NewVersion -> UpdateCheckUiState.NewVersion(
                    latestTag = result.info.latestTag,
                    changelog = result.info.changelog
                )
                is UpdateCheckResult.Error -> {
                    if (manual) UpdateCheckUiState.Error(result.message) else UpdateCheckUiState.Idle
                }
            }
            // 无论结果如何，都刷新 ~/.aicode/update-info.json 供 AI 读取
            updateCheckSettingsRepository.writeUpdateInfo(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel,
                result = result
            )
            if (!manual) {
                updateCheckSettingsRepository.markCheckedToday()
            }
        }
    }

    /** 关闭更新弹窗。 */
    fun dismissUpdateCheck() {
        _updateCheckState.value = UpdateCheckUiState.Idle
    }

    /** 自动检查更新开关（默认开启）。 */
    fun setUpdateCheckEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoCheckEnabled = enabled
        _updateCheckEnabled.value = enabled
    }

    /** 更新通道：稳定版 / 最新版。 */
    fun setUpdateCheckChannel(channel: UpdateChannel) {
        updateCheckSettingsRepository.channel = channel
        _updateCheckChannel.value = channel
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    fun refreshLogs(filterServerName: String? = _logViewerState.value.filterServerName, silent: Boolean = false) {
        loadLogs(
            filterServerName = filterServerName?.takeIf { it.isNotBlank() },
            preferredFileName = _logViewerState.value.selectedFileName,
            silent = silent
        )
    }

    fun selectLogFile(fileName: String) {
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = fileName
        )
    }

    private fun loadLogs(filterServerName: String?, preferredFileName: String?, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _logViewerState.update {
                    it.copy(
                        loading = true,
                        filterServerName = filterServerName,
                        error = null
                    )
                }
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val files = FileLogger.listLogFiles()
                    val todayName = "log-${java.time.LocalDate.now()}.txt"
                    val selected = files.firstOrNull { it.name == preferredFileName }
                        ?: files.firstOrNull { it.name == todayName }
                        ?: files.lastOrNull()
                    if (selected == null) {
                        return@runCatching LogViewerUiState(
                            filterServerName = filterServerName,
                            error = context.getString(R.string.settings_log_no_files)
                        )
                    }

                    val rawLines = selected.readLines(Charsets.UTF_8)
                    val filteredLines = if (filterServerName.isNullOrBlank()) {
                        rawLines
                    } else {
                        rawLines.filter { line ->
                            line.contains("[$filterServerName]") ||
                                line.contains(filterServerName, ignoreCase = true)
                        }
                    }
                    val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

                    LogViewerUiState(
                        files = files.map { it.name },
                        selectedFileName = selected.name,
                        filterServerName = filterServerName,
                        content = visibleLines.joinToString("\n"),
                        totalLines = filteredLines.size,
                        shownLines = visibleLines.size
                    )
                }.getOrElse { e ->
                    LogViewerUiState(
                        filterServerName = filterServerName,
                        error = context.getString(R.string.settings_log_read_failed, e.message ?: "")
                    )
                }
            }
            _logViewerState.value = state
        }
    }

    // 仅持久化标志位——启停 Service 由 AIEditorApp 监听 enabledFlow 统一完成。
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keepaliveSettingsRepository.setEnabled(enabled)
        }
    }

    fun setAgentSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentSoundSettingsRepository.setEnabled(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    /** 选择新背景图（拷贝到私有目录后替换）。 */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            backgroundSettingsRepository.setBackgroundImage(uri)
        }
    }

    /** 移除背景图。 */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundSettingsRepository.clearBackground()
        }
    }

    /** 调节背景图透明度（0.05~1.0），实时持久化。 */
    fun setBackgroundAlpha(alpha: Float) {
        viewModelScope.launch {
            backgroundSettingsRepository.setBackgroundAlpha(alpha)
        }
    }

    /** 设置应用语言；tag 为空字符串或 null 表示跟随系统。 */
    fun setLanguage(tag: String?) {
        viewModelScope.launch {
            languageSettingsRepository.setLanguage(tag?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * 切换当前选中的容器 profile，并按其 [ContainerProfile.mode] 同步切全局执行模式。
     *
     * 本地镜像 → [ExecutionMode.LOCAL_PROOT]；远程 SSH 镜像 → [ExecutionMode.REMOTE_SSH]，
     * 并据其 [RootfsSource.RemoteSsh] 绑定的工作区通道构造 [RemoteConnectionSettings] 持久化 + 触发 SSH 连接。
     * 委托层每次调用读 holder，切换即时生效，无需重启。
     */
    fun setActiveContainerProfile(id: String) {
        viewModelScope.launch {
            applyProfile(id)
        }
    }

    /** 设置远程工作区模式下的默认容器（仅本地 PRoot 容器可选）。 */
    fun setDefaultContainerId(id: String) {
        viewModelScope.launch {
            containerSettingsRepository.setDefaultContainerId(id)
        }
    }

    /** 把 [id] 应用为当前激活 profile：持久化 + 按 mode 切执行模式。找不到时回退列表第一个，再兜底内置。 */
    private suspend fun applyProfile(id: String) {
        val profile = _customProfiles.value.firstOrNull { it.id == id }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == id }
            ?: _customProfiles.value.firstOrNull()
            ?: return
        containerSettingsRepository.setActiveProfile(profile.id)
        when (profile.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
            }

            ExecutionMode.REMOTE_SSH -> {
                val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return
                val conn = remoteConnections.value.firstOrNull { it.id == ssh.connectionId }
                    ?: return
                val settings = com.aicode.feature.settings.data.repository.RemoteConnectionSettings(
                    host = conn.host,
                    port = conn.port,
                    username = conn.username,
                    password = conn.password,
                    remoteWorkspacePath = ssh.remoteWorkspacePath.ifBlank { "/home/${conn.username}/workspace" }
                )
                executionModeRepository.setRemoteConnection(settings)
                executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
                // 运行时切换需主动连接（启动时由 AIEditorApp 连）；复用 RemoteSshConnection.connect
                runCatching {
                    remoteSshConnection.connect(
                        com.aicode.feature.agent.domain.container.RemoteConnectionConfig(
                            host = settings.host,
                            port = settings.port,
                            username = settings.username,
                            auth = com.aicode.feature.workspace.domain.remote.RemoteAuth.Password(settings.password),
                            remoteWorkspacePath = settings.remoteWorkspacePath
                        )
                    )
                }.onFailure { FileLogger.w("SettingsViewModel", "切换到远程镜像时 SSH 连接失败", it) }
            }
        }
    }

    /** 重置容器：内置恢复出厂（清覆盖配置 + 删 rootfs），自定义本地镜像删 rootfs 下次重新解压。远程 SSH 无本地数据，UI 不提供入口。 */
    fun resetContainer(profile: ContainerProfile) {
        viewModelScope.launch {
            if (profile.isBuiltin) {
                containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
            }
            containerInstaller.resetRootfs(profile)
        }
    }

    /** 空态恢复：把内置 Alpine 默认配置重新加回列表。 */
    fun restoreBuiltinAlpine() {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        }
    }

    /** 保存（新增/覆盖）自定义容器 profile。 */
    fun saveCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 编辑自定义 profile：覆盖配置；若镜像来源变了则删旧 rootfs 触发重新解压。 */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                containerInstaller.deleteCustomRootfs(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 删除容器 profile（内置 Alpine 也可删，删光后由空态恢复），连带清理本地 rootfs。 */
    fun deleteContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            containerInstaller.deleteCustomRootfs(profile)
            if (_activeProfileId.value == profile.id) {
                // 删除的是当前激活项：切到剩余第一个；列表空则回退内置 id（引擎 Alpine 兜底）
                val remaining = _customProfiles.value.filterNot { it.id == profile.id }
                applyProfile(remaining.firstOrNull()?.id ?: ContainerProfile.BUILTIN_ID)
            }
        }
    }

    /** 标记公告已展示：把当前内容哈希写入存储，下次内容不变则不再弹；内容更新哈希变自然重新弹。 */
    fun markContainerAnnouncementShown() {
        viewModelScope.launch {
            containerSettingsRepository.markAnnouncementShown(sha256(_containerAnnouncementText.value))
        }
    }

    /** 切换全局下载源（官方/华为云/阿里云/腾讯云等）。 */
    fun setImageSource(sourceId: String) {
        _selectedImageSource.value = sourceId
    }

    /** 源显示名：按界面语言取目录 JSON 里定义的 name，跟随系统语言时按系统语言。 */
    fun sourceDisplayName(sourceId: String, languageTag: String?): String {
        val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
        val key = if (lang.startsWith("zh", ignoreCase = true)) "zh" else "en"
        return containerImageCatalog.sourceName(sourceId, key) ?: sourceId
    }

    /** 开始下载镜像：按当前架构与全局源拼 URL，进度实时写入 [_containerImageDownload]。 */
    fun startContainerImageDownload(entry: ContainerImageEntry, sourceId: String) {
        if (_containerImageDownload.value is ContainerImageDownloadUiState.Downloading) return
        val url = containerImageCatalog.urlFor(entry, sourceId, ContainerImageCatalog.CURRENT_ABI) ?: return
        val suffix = when {
            url.endsWith(".tar.gz") -> "tar.gz"
            url.endsWith(".tar.xz") || url.endsWith(".txz") -> "tar.xz"
            url.endsWith(".tgz") -> "tgz"
            else -> "tar.gz"
        }
        val fileName = "download_${entry.id}_${entry.version}_${System.currentTimeMillis()}.$suffix"
        downloadJob = viewModelScope.launch {
            _containerImageDownload.value =
                ContainerImageDownloadUiState.Downloading(entry.id, entry.name, entry.version, sourceId, 0, 0)
            try {
                val uri = containerImageDownloader.download(url, fileName) { read, total ->
                    _containerImageDownload.value = ContainerImageDownloadUiState.Downloading(
                        entry.id, entry.name, entry.version, sourceId, read, total
                    )
                }
                // 下载完成即持久化记录，安装与否都保留，跨重启可见。
                containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entry.id, uri))
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Done(entry.id, entry.name, entry.version, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _containerImageDownload.value = ContainerImageDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Error(entry.id, e.message ?: context.getString(R.string.container_download_failed, ""))
            }
        }
    }

    /** 取消正在进行的下载（半成品文件由下载器清理）。 */
    fun cancelContainerImageDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * 把已下载的镜像作为自定义 profile 导入容器列表（可重复导入，每次新建 profile），后续流程与手动导入一致。
     * 默认追加 `--link2symlink` proot 参数（硬链接模拟为符号链接，Android 上常见），与内置 Alpine 一致。
     */
    fun importDownloadedImage(entryId: String, fileUri: String) {
        val entry = _imageCatalog.value.firstOrNull { it.id == entryId } ?: return
        viewModelScope.launch {
            val profile = ContainerProfile(
                id = "custom-${System.currentTimeMillis()}",
                name = "${entry.name} ${entry.version}",
                rootfsSource = RootfsSource.LocalFile(fileUri),
                shellPath = null,
                extraArgs = listOf("--link2symlink"),
                isBuiltin = false
            )
            containerSettingsRepository.upsertCustomProfile(profile)
            // 保留已下载记录，仅标记已导入；UI 状态不消失。
            containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entryId, fileUri, installed = true))
            _containerImageDownload.value = ContainerImageDownloadUiState.Idle
        }
    }

    /** 删除已下载的镜像：清记录 + 删下载文件（仅限 rootfs_images 目录，防误删）；若正在下载该条目先取消。 */
    fun deleteDownloadedImage(entryId: String) {
        val record = _downloadedImages.value[entryId] ?: return
        if ((_containerImageDownload.value as? ContainerImageDownloadUiState.Downloading)?.entryId == entryId) {
            cancelContainerImageDownload()
        }
        viewModelScope.launch {
            runCatching {
                val file = java.io.File(Uri.parse(record.fileUri).path ?: return@runCatching)
                if (file.parentFile?.name == "rootfs_images") file.delete()
            }
            containerSettingsRepository.removeDownloadedImage(entryId)
        }
    }

    /** 设置识图模型；providerId 留空等同 [clearVisionModel]（跟随聊天模型）。 */
    fun setVisionModel(providerId: String, model: String) {
        viewModelScope.launch {
            visionModelSettingsRepository.setVisionModel(providerId, model)
        }
    }

    /** 清空识图模型——回退到跟随当前聊天模型。 */
    fun clearVisionModel() {
        viewModelScope.launch {
            visionModelSettingsRepository.clear()
        }
    }

    /** 设置压缩专用模型；providerId 留空等同 [clearCompactionModel]（跟随聊天模型）。 */
    fun setCompactionModel(providerId: String, model: String) {
        viewModelScope.launch {
            compactionModelSettingsRepository.setCompactionModel(providerId, model)
        }
    }

    /** 清空压缩专用模型——回退到跟随当前聊天模型。 */
    fun clearCompactionModel() {
        viewModelScope.launch {
            compactionModelSettingsRepository.clear()
        }
    }

    /** 设置标题总结专用模型；providerId 留空等同 [clearTitleModel]（跟随聊天模型）。 */
    fun setTitleModel(providerId: String, model: String) {
        viewModelScope.launch {
            titleModelSettingsRepository.setTitleModel(providerId, model)
        }
    }

    /** 清空标题总结专用模型——回退到跟随当前聊天模型。 */
    fun clearTitleModel() {
        viewModelScope.launch {
            titleModelSettingsRepository.clear()
        }
    }

    fun setTokenStatsPeriod(period: TokenStatsPeriod) {
        _tokenStatsPeriod.value = period
        // 切周期后明细回到第一页
        _tokenStatsPage.value = 0
    }

    /** 单次调用的预估费用（USD）；模型无单价返回 null。缓存读价缺失时按输入价 10% 估算。 */
    private suspend fun callCostUsd(providerId: String?, model: String?, inputTokens: Long, cachedInputTokens: Long, outputTokens: Long): Double? {
        val modelId = model ?: return null
        // 统一走元数据解析入口（自动拉取/内置 → 自定义 三级回退），与编辑页回显价格一致。
        val provider = _providers.value.firstOrNull { it.id == providerId }
        val meta = modelMetadataService.resolve(providerId.orEmpty(), provider?.type ?: ProviderType.OPENAI, modelId)
        val inputPrice = meta.inputCostUsdPerM ?: return null
        val outputPrice = meta.outputCostUsdPerM ?: 0.0
        val cachePrice = meta.cacheReadCostUsdPerM ?: inputPrice * CACHE_READ_DISCOUNT
        val uncached = (inputTokens - cachedInputTokens).coerceAtLeast(0)
        return (uncached * inputPrice + cachedInputTokens * cachePrice + outputTokens * outputPrice) / 1_000_000.0
    }

    /** 调用明细翻页；越界时钳制到合法范围。 */
    fun setTokenStatsPage(page: Int) {
        val total = _tokenStats.value.callsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / CALLS_PAGE_SIZE
        _tokenStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** 清空全部调用记录（Token 统计页右上角重置）；Room Flow 自动把各聚合刷新为空。 */
    fun resetTokenStats() {
        viewModelScope.launch {
            llmCallRecordDao.deleteAll()
            _tokenStatsPage.value = 0
        }
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            repository.saveProvider(provider)
            // 保存后重置该提供商在内存中的面板状态，以便主页即时以最新脚本与配置重新加载
            _providerBalances.update { it - provider.id }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    /** 提供商列表长按拖拽排序：同步更新内存顺序（reorderable 库要求 onMove 返回前列表已更新，否则拖拽项闪烁），再异步持久化 sortOrder。 */
    fun reorderProviders(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _providers.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _providers.value = reordered
        viewModelScope.launch {
            repository.reorderProviders(reordered)
        }
    }

    fun fetchModels(provider: AIProviderConfig) {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            modelApiService.fetchModels(provider.baseUrl, provider.apiKey, provider.type, provider.useFullUrl, provider.userAgent)
                .onSuccess { result ->
                    _fetchState.value = FetchState.Success(result.models, result.debugInfo)
                    resolveModelMetadata(provider.id, provider.type, result.models)
                }
                .onFailure { error ->
                    val debug = (error as? com.aicode.feature.settings.data.remote.FetchModelsException)?.debugInfo
                    _fetchState.value = FetchState.Error(
                        error.message ?: context.getString(R.string.settings_models_fetch_failed),
                        debug
                    )
                }
        }
    }

    fun resolveModelMetadata(providerId: String, type: ProviderType, modelIds: List<String>) {
        val normalizedIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(providerId, type, normalizedIds)
            _modelMetadata.update { current -> current + metadata }
        }
    }

    /**
     * 加载所有已启用 provider 的全部模型元数据，合并进 [modelMetadata]。
     * 供「识图模型」等需要展示跨 provider 模型能力标签的页面在进入时调用--
     * 这些页面不像 ProviderEditor 那样会在编辑单个 provider 时顺带 resolve，
     * 不主动加载则 map 为空、所有模型都被误判为不支持图片。
     *
     * 实现要点（避免设置页卡顿）：
     * - 单协程顺序处理各 provider：首个 resolveAll 触发 catalog 加载（内存/磁盘 24h 缓存/内置 assets）并写入内存缓存，
     *   后续 provider 命中缓存。resolve 链路不发网络请求，models.dev 刷新统一由 App 启动时异步触发。
     * - 全部解析完一次性 update，避免多次 emit 导致设置页反复重组。
     */
    fun loadAllModelMetadata() {
        val enabled = _providers.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, ModelMetadata>()
            for (provider in enabled) {
                val ids = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                resolved += modelMetadataService.resolveAll(provider.id, provider.type, ids)
            }
            if (resolved.isNotEmpty()) {
                _modelMetadata.update { it + resolved }
            }
        }
    }

    fun resetFetchState() {
        _fetchState.value = FetchState.Idle
    }

    fun testModel(provider: AIProviderConfig, model: String) {
        viewModelScope.launch {
            _testing.update { it + model }
            val result = modelApiService.testModel(provider.baseUrl, provider.apiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.userAgent)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    fun listAvailableBalanceScripts(): List<String> {
        return providerBalanceRunner.listAvailableScripts()
    }

    fun testBalanceScript(provider: AIProviderConfig, scriptPath: String) {
        viewModelScope.launch {
            _balanceTestState.value = ProviderBalanceState.Loading
            providerBalanceRunner.runScript(provider, scriptPath)
                .onSuccess { result ->
                    _balanceTestState.value = ProviderBalanceState.Success(result)
                }
                .onFailure { error ->
                    _balanceTestState.value = ProviderBalanceState.Error(
                        error.message ?: context.getString(R.string.balance_exec_failed),
                        error.localizedMessage ?: ""
                    )
                }
        }
    }

    fun clearBalanceTestState() {
        _balanceTestState.value = ProviderBalanceState.Idle
    }

    fun refreshProviderBalance(
        provider: AIProviderConfig,
        context: DashboardContext? = null,
        force: Boolean = false
    ) {
        if (provider.balanceScriptPath.isBlank()) {
            _providerBalances.update { it - provider.id }
            return
        }
        val current = _providerBalances.value[provider.id]
        if (!force && current is ProviderBalanceState.Success) return
        if (current is ProviderBalanceState.Loading) return

        // 已有 Success 时不切 Loading，避免 UI 高度塌缩再恢复（收起→展开闪烁）
        val showLoading = current !is ProviderBalanceState.Success
        viewModelScope.launch {
            if (showLoading) {
                _providerBalances.update { it + (provider.id to ProviderBalanceState.Loading) }
            }
            providerBalanceRunner.runScript(provider, context = context)
                .onSuccess { result ->
                    _providerBalances.update { it + (provider.id to ProviderBalanceState.Success(result)) }
                }
                .onFailure { error ->
                    // 函数参数 context: DashboardContext 遮蔽了类的 ApplicationContext；
                    // 且此处位于 launch{} 内，this 已变为 CoroutineScope，必须用类标签限定
                    _providerBalances.update { it + (provider.id to ProviderBalanceState.Error(error.message ?: this@SettingsViewModel.context.getString(R.string.balance_query_failed))) }
                }
        }
    }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            repository.setSelectedModel(providerId, model)
        }
    }

    fun deleteGlobalRule(rule: PermissionRule) {
        viewModelScope.launch { permissionRulesRepository.removeGlobalRule(rule) }
    }

    fun deleteProjectRule(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.removeProjectRule(name, rule) }
    }

    fun promoteRuleToGlobal(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.promoteToGlobal(name, rule) }
    }
}

/** 读取公告 md（zh 用中文版，其余英文版）；跟随系统语言时按系统语言判断。读取失败返回空串（空内容不弹窗）。 */
private fun loadContainerAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/container-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** SHA-256 十六进制摘要，用于公告内容比对：内容更新则哈希变，自动重新弹出。 */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
