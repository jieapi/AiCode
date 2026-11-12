package com.aicode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.net.AppProxy
import com.aicode.core.util.ApkInstaller
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
import com.aicode.feature.agent.domain.container.displayLabel
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.domain.mcp.McpToolDescriptor
import com.aicode.feature.agent.domain.mcp.auth.McpOAuthCancelledException
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillForm
import com.aicode.feature.agent.domain.skill.SkillImportError
import com.aicode.feature.agent.domain.skill.SkillImportReport
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillSaveError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionForm
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.AgentSaveError
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.domain.subagent.SubagentModelTier
import com.aicode.feature.agent.domain.subagent.SubagentTierSelection
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.data.remote.ContainerImageDownloader
import com.aicode.feature.settings.data.remote.ContainerImageVersionProbe
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.data.remote.ApkDownloadResult
import com.aicode.feature.settings.data.remote.ApkDownloader
import com.aicode.feature.settings.data.remote.ApkVerifier
import com.aicode.feature.settings.data.remote.ApkVerifyResult
import com.aicode.feature.settings.data.remote.UpdateAsset
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import com.aicode.feature.settings.data.remote.UpdateCheckService
import com.aicode.feature.settings.data.repository.UpdateCheckSettingsRepository
import com.aicode.feature.settings.data.repository.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.effectiveVersion
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.ExecutionModeRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.repository.RemoteRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.domain.model.ProviderDashboardResult
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.service.ProviderDashboardRunner
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

/** 重置容器的进行状态：重置中才有值，[deleted] 为已删除的文件与目录数。 */
data class ContainerResetUiState(val name: String, val deleted: Int)

data class McpAuthUiState(
    val serverName: String? = null,
    val inProgress: Boolean = false,
    val error: String? = null,
    val success: Boolean = false
)

data class McpImportUiState(
    val inProgress: Boolean = false,
    val error: String? = null,
    val importedCount: Int? = null
)

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

    /** 检测到新版本。[asset] 为该版本可下载的更新包；Release 没挂 APK 时为 null（只能去浏览器下载）。 */
    data class NewVersion(
        val latestTag: String,
        val changelog: String,
        val asset: UpdateAsset? = null
    ) : UpdateCheckUiState

    /** 正在下载更新包（关闭弹窗不中断，重开可见进度）。 */
    data class Downloading(
        val latestTag: String,
        val percent: Int,
        val downloaded: Long,
        val total: Long
    ) : UpdateCheckUiState

    /** 更新包已落盘并通过自检，等待拉起安装器。[needPermission] 为真表示还没授权安装未知应用。 */
    data class ReadyToInstall(
        val previous: NewVersion,
        val apk: File,
        val needPermission: Boolean
    ) : UpdateCheckUiState

    /** 下载或自检失败。[previous] 保留版本信息，供「重试」与「浏览器下载」继续使用。 */
    data class DownloadFailed(
        val previous: NewVersion,
        val message: String
    ) : UpdateCheckUiState

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
    /** 渠道当前页（0 起）。 */
    val providersPage: Int = 0,
    /** 渠道总数。 */
    val providersTotal: Int = 0,
    /** 模型当前页（0 起）。 */
    val modelsPage: Int = 0,
    /** 模型总数。 */
    val modelsTotal: Int = 0,
    /** 当前周期总费用（USD，渠道自定义单价优先，否则回退 models.dev 单价估算）。 */
    val totalCostUsd: Double = 0.0,
    /** 调用明细分页内每条记录的费用（key=记录 id，null=模型无单价）。 */
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    /** 筛选：指定供应商 ID（null 为不限）。 */
    val filterProviderId: String? = null,
    /** 筛选：指定模型名（null 为不限）。 */
    val filterModel: String? = null,
    /** 可供筛选的供应商列表（id to 名称）。 */
    val availableProviders: List<Pair<String, String>> = emptyList(),
    /** 可供筛选的模型列表。 */
    val availableModels: List<String> = emptyList(),
    /** 各供应商 ID 对应的密钥/凭据数量。 */
    val providerKeyCounts: Map<String, Int> = emptyMap()
)

private data class ModelStatsPaging(
    val paged: List<com.aicode.feature.agent.data.local.dao.ModelCallStats>,
    val page: Int,
    val total: Int
)

/** 技能列表页的 UI 状态：技能 + 来源作用域 + 启停状态。 */
data class SkillUiEntry(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val disabled: Boolean,
    val instructions: String,
    /** 手写技能里的 `required_tools`，编辑页不展示，保存时原样写回。 */
    val requiredTools: List<String> = emptyList()
)

/** 子代理列表页的 UI 状态：定义内容 + 来源作用域 + 启停状态。 */
data class SubAgentUiEntry(
    val name: String,
    val description: String,
    val scope: AgentDefinitionScope,
    val disabled: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val mode: AgentMode?,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val inject: Set<InjectPart>,
    val prompt: String,
    val filePath: String?
)

/** 技能编辑页的保存结果：UI 据此决定是退回列表还是就地报错。 */
sealed interface SkillSaveState {
    data object Idle : SkillSaveState
    data object Saving : SkillSaveState
    data object Saved : SkillSaveState
    data class Failed(val error: SkillSaveError) : SkillSaveState
}

/** 技能导入的 UI 状态：空闲 / 导入中 / 完成（含结果报告）。 */
sealed interface SkillImportState {
    data object Idle : SkillImportState
    data object Running : SkillImportState
    data class Done(val report: SkillImportReport) : SkillImportState
}

/** 子代理编辑页的保存结果：UI 据此决定是退回列表还是就地报错。 */
sealed interface SubAgentSaveState {
    data object Idle : SubAgentSaveState
    data object Saving : SubAgentSaveState
    data object Saved : SubAgentSaveState
    data class Failed(val error: AgentSaveError) : SubAgentSaveState
}

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
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val liveNotificationSettingsRepository: LiveNotificationSettingsRepository,
    private val approvalNotificationSettingsRepository: ApprovalNotificationSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val toolSafetySettingsRepository: ToolSafetySettingsRepository,
    private val skillRepository: SkillRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentDefinitionConfigRepository: AgentDefinitionConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val skillConfigRepository: SkillConfigRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val subagentTierSettingsRepository: SubagentTierSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerImageCatalog: ContainerImageCatalog,
    private val containerImageDownloader: ContainerImageDownloader,
    private val containerImageVersionProbe: ContainerImageVersionProbe,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val updateCheckSettingsRepository: UpdateCheckSettingsRepository,
    private val updateCheckService: UpdateCheckService,
    private val apkDownloader: ApkDownloader,
    private val apkVerifier: ApkVerifier,
    private val providerDashboardRunner: ProviderDashboardRunner,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val proxySettingsRepository: ProxySettingsRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200

        /** 正式包的 applicationId：只有它能应用内覆盖安装 GitHub Releases 里的包。 */
        const val RELEASE_APPLICATION_ID = "com.aicode"
        const val CALLS_PAGE_SIZE = 10
        const val STATS_PAGE_SIZE = 5
        /** 背景透明度停止拖动后的落盘延迟。 */
        const val BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS = 80L

        /** 技能文件导入接受的扩展名（小写，不含点）。 */
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "txt")

        /** 技能压缩包导入接受的扩展名（小写，不含点）。 */
        val ZIP_EXTENSIONS = setOf("zip")
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

    fun setTerminalFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    fun setTerminalShowPluginTabs(enabled: Boolean) {
        viewModelScope.launch { terminalSettingsRepository.setShowPluginTabs(enabled) }
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

    /**
     * 子代理模型档位：三档各一对 (providerId, model)，空串即该档未配置。
     * 映射始终包含全部档位，UI 直接按档位取用，不必判空。
     */
    private val _subagentTierSelections = MutableStateFlow(
        SubagentModelTier.entries.associateWith { SubagentTierSelection() }
    )
    val subagentTierSelections: StateFlow<Map<SubagentModelTier, SubagentTierSelection>> =
        _subagentTierSelections.asStateFlow()

    /** 生图专用模型选择：providerId 为空即未配置（生图不跟随聊天模型）。 */
    private val _imageGenProviderId = MutableStateFlow("")
    val imageGenProviderId: StateFlow<String> = _imageGenProviderId.asStateFlow()

    private val _imageGenModel = MutableStateFlow("")
    val imageGenModel: StateFlow<String> = _imageGenModel.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    /**
     * 更新弹窗是否可见。与 [updateCheckState] 分开：下载中关掉弹窗只收起界面、不打断下载，
     * 之后再点「版本」行即可按原状态重新亮出来（不必重下已完成的包）。
     */
    private val _updateCheckVisible = MutableStateFlow(false)
    val updateCheckVisible: StateFlow<Boolean> = _updateCheckVisible.asStateFlow()

    private val _updateCheckEnabled = MutableStateFlow(updateCheckSettingsRepository.autoCheckEnabled)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    private val _updateCheckChannel = MutableStateFlow(updateCheckSettingsRepository.channel)
    val updateCheckChannel: StateFlow<UpdateChannel> = _updateCheckChannel.asStateFlow()

    private val _updateCheckMirror = MutableStateFlow(updateCheckSettingsRepository.downloadMirror)
    val updateCheckMirror: StateFlow<String> = _updateCheckMirror.asStateFlow()

    /** 更新包下载协程；重试前先顶掉上一个，避免两路并发写同一状态。 */
    private var apkDownloadJob: Job? = null

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _screenOnEnabled = MutableStateFlow(false)
    val screenOnEnabled: StateFlow<Boolean> = _screenOnEnabled.asStateFlow()

    private val _agentSoundEnabled = MutableStateFlow(false)
    val agentSoundEnabled: StateFlow<Boolean> = _agentSoundEnabled.asStateFlow()

    private val _liveNotificationEnabled = MutableStateFlow(false)
    val liveNotificationEnabled: StateFlow<Boolean> = _liveNotificationEnabled.asStateFlow()

    private val _approvalNotificationEnabled = MutableStateFlow(false)
    val approvalNotificationEnabled: StateFlow<Boolean> = _approvalNotificationEnabled.asStateFlow()

    private val _autoRemoveStaleModels = MutableStateFlow(true)
    val autoRemoveStaleModels: StateFlow<Boolean> = _autoRemoveStaleModels.asStateFlow()

    private val _startupSessionMode = MutableStateFlow(StartupSessionMode.NEW_SESSION)
    val startupSessionMode: StateFlow<StartupSessionMode> = _startupSessionMode.asStateFlow()

    private val _firstByteTimeoutSec = MutableStateFlow(300)
    val firstByteTimeoutSec: StateFlow<Int> = _firstByteTimeoutSec.asStateFlow()

    private val _streamIdleTimeoutSec = MutableStateFlow(300)
    val streamIdleTimeoutSec: StateFlow<Int> = _streamIdleTimeoutSec.asStateFlow()

    private val _maxNetworkRetries = MutableStateFlow(6)
    val maxNetworkRetries: StateFlow<Int> = _maxNetworkRetries.asStateFlow()

    private val _enterToSend = MutableStateFlow(false)
    val enterToSend: StateFlow<Boolean> = _enterToSend.asStateFlow()

    private val _compactionThresholdPercent = MutableStateFlow(85)
    val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()

    private val _sendFileMaxSizeMb = MutableStateFlow(100)
    val sendFileMaxSizeMb: StateFlow<Int> = _sendFileMaxSizeMb.asStateFlow()

    private val _goalMaxTurns = MutableStateFlow(50)
    val goalMaxTurns: StateFlow<Int> = _goalMaxTurns.asStateFlow()

    private val _goalMaxTokens = MutableStateFlow(500_000)
    val goalMaxTokens: StateFlow<Int> = _goalMaxTokens.asStateFlow()

    private val _deleteExternalWorkspaceSessions = MutableStateFlow(false)
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = _deleteExternalWorkspaceSessions.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _themePresetId = MutableStateFlow<String?>(null)
    val themePresetId: StateFlow<String?> = _themePresetId.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    /** 全局自定义背景图文件路径（null=未设置），供设置弹窗展示与预览。 */
    private val _backgroundImagePath = MutableStateFlow<String?>(null)
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /** 背景图不透明度（0~0.2，实际范围由 BackgroundSettingsRepository 的 MIN/MAX 决定），实时写 DataStore，全局背景同步变化。 */
    private val _backgroundAlpha = MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** 透明度落盘的节流 job，见 [setBackgroundAlpha]。 */
    private var backgroundAlphaWriteJob: Job? = null

    /** 用户选择的应用语言 tag（null 表示跟随系统）。 */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillUiEntry>>(emptyList())
    val skills: StateFlow<List<SkillUiEntry>> = _skills.asStateFlow()

    private val _skillSaveState = MutableStateFlow<SkillSaveState>(SkillSaveState.Idle)
    val skillSaveState: StateFlow<SkillSaveState> = _skillSaveState.asStateFlow()

    private val _skillImportState = MutableStateFlow<SkillImportState>(SkillImportState.Idle)
    val skillImportState: StateFlow<SkillImportState> = _skillImportState.asStateFlow()

    /**
     * 正在删除的技能名集合（空集合表示没有删除在进行）。
     * 用集合而非单个名字：批量删除时逐条删除、逐条移除，UI 可据此显示进度并保持弹窗转圈。
     */
    private val _skillDeleting = MutableStateFlow<Set<String>>(emptySet())
    val skillDeleting: StateFlow<Set<String>> = _skillDeleting.asStateFlow()

    private val _subAgents = MutableStateFlow<List<SubAgentUiEntry>>(emptyList())
    val subAgents: StateFlow<List<SubAgentUiEntry>> = _subAgents.asStateFlow()

    private val _subAgentSaveState = MutableStateFlow<SubAgentSaveState>(SubAgentSaveState.Idle)
    val subAgentSaveState: StateFlow<SubAgentSaveState> = _subAgentSaveState.asStateFlow()

    /** 正在删除的子代理名集合（空集合表示没有删除在进行，语义同 [skillDeleting]）。 */
    private val _subAgentDeleting = MutableStateFlow<Set<String>>(emptySet())
    val subAgentDeleting: StateFlow<Set<String>> = _subAgentDeleting.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _mcpAuthState = MutableStateFlow(McpAuthUiState())
    val mcpAuthState: StateFlow<McpAuthUiState> = _mcpAuthState.asStateFlow()

    private val _mcpImportState = MutableStateFlow(McpImportUiState())
    val mcpImportState: StateFlow<McpImportUiState> = _mcpImportState.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    /** 模型元数据缓存，键为 [modelMetadataKey]（渠道 + 模型）——单价与能力都可能因渠道而异。 */
    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _dashboardTestState = MutableStateFlow<ProviderDashboardState>(ProviderDashboardState.Idle)
    val dashboardTestState: StateFlow<ProviderDashboardState> = _dashboardTestState.asStateFlow()

    private val _dashboardScripts = MutableStateFlow<List<String>>(emptyList())
    /** 可用面板脚本文件名（打开脚本选择器时经 [loadDashboardScripts] 异步加载）。 */
    val dashboardScripts: StateFlow<List<String>> = _dashboardScripts.asStateFlow()

    private val _providerDashboards = MutableStateFlow<Map<String, ProviderDashboardState>>(emptyMap())
    val providerDashboards: StateFlow<Map<String, ProviderDashboardState>> = _providerDashboards.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _disableSafetyInterception = MutableStateFlow(false)
    val disableSafetyInterception: StateFlow<Boolean> = _disableSafetyInterception.asStateFlow()

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

    /** 「提示词」页公告内容（跟随界面语言），与 [promptsAnnouncementOutdated] 配套供弹窗渲染。 */
    private val _promptsAnnouncementText = MutableStateFlow("")
    val promptsAnnouncementText: StateFlow<String> = _promptsAnnouncementText.asStateFlow()

    /** 「提示词」页公告是否需要弹出：本地未存哈希或与当前内容哈希不一致（内容更新过）时为 true。 */
    private val _promptsAnnouncementOutdated = MutableStateFlow(false)
    val promptsAnnouncementOutdated: StateFlow<Boolean> = _promptsAnnouncementOutdated.asStateFlow()

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

    /**
     * 运行时探测到的精确版本（entryId → 版本号，如 Debian 的 `12.15`）。
     * 只有配了 versionPath 且探测成功的条目会出现在表中；其余条目 UI 回退到内置版本号。
     */
    private val _imageVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val imageVersions: StateFlow<Map<String, String>> = _imageVersions.asStateFlow()

    private val _containerImageDownload = MutableStateFlow<ContainerImageDownloadUiState>(ContainerImageDownloadUiState.Idle)
    val containerImageDownload: StateFlow<ContainerImageDownloadUiState> = _containerImageDownload.asStateFlow()

    /** 正在重置的容器（含已删条目数），为 null 说明不在重置。UI 据此弹进度框。 */
    private val _containerReset = MutableStateFlow<ContainerResetUiState?>(null)
    val containerReset: StateFlow<ContainerResetUiState?> = _containerReset.asStateFlow()

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

    /** Token 统计：渠道分页页码（0 起）。 */
    private val _providerStatsPage = MutableStateFlow(0)

    /** Token 统计：模型分页页码（0 起）。 */
    private val _modelStatsPage = MutableStateFlow(0)

    /** Token 统计：供应商筛选（null 为全部）。 */
    private val _filterProviderId = MutableStateFlow<String?>(null)
    val filterProviderId: StateFlow<String?> = _filterProviderId.asStateFlow()

    /** Token 统计：模型筛选（null 为全部）。 */
    private val _filterModel = MutableStateFlow<String?>(null)
    val filterModel: StateFlow<String?> = _filterModel.asStateFlow()

    /** 工作区已配置的远程连接通道，供容器镜像 SSH 模式下拉复用。 */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        // 先同步拿本地目录（内存 → 磁盘缓存 → 内置 assets），保证首帧就有列表、断网也不为空
        containerImageCatalog.load()
        // 目录快照变化时同步刷新列表与源选项：启动预热从仓库拉到最新版本号后会在这里反映出来
        viewModelScope.launch {
            containerImageCatalog.catalog.collect { data ->
                _imageCatalog.value = data.images
                _imageSourceOptions.value = data.sources.keys.toList()
                // 仅在当前选中源已不存在时才回退（否则会把用户刚切的源重置掉）
                if (_selectedImageSource.value !in data.sources) {
                    _selectedImageSource.value = data.sources.keys.firstOrNull { it == "official" }
                        ?: data.sources.keys.firstOrNull() ?: "official"
                }
            }
        }
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
        // 精确版本探测：Debian 之类的条目内置值只有主版本，而 rootfs 内容随上游滚动，
        // 故按「当前源 + 当前架构」读版本文件补上补丁号。失败静默保留内置版本；
        // 切源后重探（版本文件 URL 随源变化），collectLatest 顺带取消上一轮未完成的探测。
        viewModelScope.launch {
            combine(_imageCatalog, _selectedImageSource) { entries, sourceId -> entries to sourceId }
                .collectLatest { (entries, sourceId) ->
                    _imageVersions.value = entries.mapNotNull { entry ->
                        val url = containerImageCatalog.versionUrlFor(
                            entry, sourceId, ContainerImageCatalog.CURRENT_ABI
                        ) ?: return@mapNotNull null
                        containerImageVersionProbe.probe(url)?.let { entry.id to it }
                    }.toMap()
                }
        }
        // 公告按「内容哈希比对」判断是否弹出：首次（无哈希）或内容更新（哈希不一致）即弹。
        viewModelScope.launch {
            combine(_languageTag, containerSettingsRepository.announcementShownHashFlow) { tag, storedHash ->
                val text = loadContainerAnnouncement(context, tag)
                _containerAnnouncementText.value = text
                _containerAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        // 「提示词」页公告同样按「内容哈希比对」判断是否弹出。
        viewModelScope.launch {
            combine(_languageTag, generalSettingsRepository.promptsAnnouncementShownHashFlow) { tag, storedHash ->
                val text = loadPromptsAnnouncement(context, tag)
                _promptsAnnouncementText.value = text
                _promptsAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        viewModelScope.launch {
            launch {
                repository.getAllProviders()
                    .collectLatest { _providers.value = it }
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

            // 三档子代理模型：任一档变化即刷新快照，UI 按档位渲染。
            SubagentModelTier.entries.forEach { tier ->
                launch {
                    subagentTierSettingsRepository.selectionFlow(tier).collectLatest { selection ->
                        _subagentTierSelections.value = _subagentTierSelections.value + (tier to selection)
                    }
                }
            }

            launch {
                imageGenModelSettingsRepository.providerIdFlow.collectLatest {
                    _imageGenProviderId.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.modelFlow.collectLatest {
                    _imageGenModel.value = it
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
                screenOnSettingsRepository.enabledFlow.collectLatest {
                    _screenOnEnabled.value = it
                }
            }

            launch {
                agentSoundSettingsRepository.enabledFlow.collectLatest {
                    _agentSoundEnabled.value = it
                }
            }

            launch {
                liveNotificationSettingsRepository.enabledFlow.collectLatest {
                    _liveNotificationEnabled.value = it
                }
            }

            launch {
                approvalNotificationSettingsRepository.enabledFlow.collectLatest {
                    _approvalNotificationEnabled.value = it
                }
            }

            launch {
                generalSettingsRepository.autoRemoveStaleModelsFlow.collectLatest {
                    _autoRemoveStaleModels.value = it
                }
            }

            launch {
                generalSettingsRepository.startupSessionModeFlow.collectLatest {
                    _startupSessionMode.value = it
                }
            }

            launch {
                generalSettingsRepository.firstByteTimeoutSecFlow.collectLatest {
                    _firstByteTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.streamIdleTimeoutSecFlow.collectLatest {
                    _streamIdleTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.maxNetworkRetriesFlow.collectLatest {
                    _maxNetworkRetries.value = it
                }
            }

            launch {
                generalSettingsRepository.enterToSendFlow.collectLatest {
                    _enterToSend.value = it
                }
            }

            launch {
                generalSettingsRepository.compactionThresholdPercentFlow.collectLatest {
                    _compactionThresholdPercent.value = it
                }
            }

            launch {
                generalSettingsRepository.sendFileMaxSizeMbFlow.collectLatest {
                    _sendFileMaxSizeMb.value = it
                }
            }

            launch {
                generalSettingsRepository.goalMaxTurnsFlow.collectLatest {
                    _goalMaxTurns.value = it
                }
            }

            launch {
                generalSettingsRepository.goalMaxTokensFlow.collectLatest {
                    _goalMaxTokens.value = it
                }
            }

            launch {
                generalSettingsRepository.deleteExternalWorkspaceSessionsFlow.collectLatest {
                    _deleteExternalWorkspaceSessions.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                themeSettingsRepository.themePresetIdFlow.collectLatest {
                    _themePresetId.value = it
                }
            }

            launch {
                themeSettingsRepository.dynamicColorFlow.collectLatest {
                    _dynamicColorEnabled.value = it
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
                // 先做存量数据归一，再开始收集，避免升级后的第一帧闪出旧顺序：
                // 1) 首次启动写入内置 Alpine 默认项（置位标记后不再自动补回）；
                // 2) 把存量列表顺序归一为「新建在前」的显示序（此后顺序由用户拖拽决定，不再实时排序）。
                containerSettingsRepository.ensureBuiltinDefault()
                containerSettingsRepository.ensureProfileDisplayOrder()
                containerSettingsRepository.customProfilesFlow.collectLatest {
                    // 存储顺序即显示顺序：新增插到最前、编辑不动位置、拖拽重排由 reorderCustomProfiles 落库
                    _customProfiles.value = it
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
                agentDefinitionConfigRepository.changes.collectLatest {
                    refreshSubAgents()
                }
            }

            launch {
                refreshSkills()
            }

            // 全局技能/子代理在远程模式下以远端为源，本地目录监听（skills.changes）不会触发；
            // 执行模式切换或远程连接就绪后需重扫，否则列表停留在旧环境的内容。
            launch {
                executionMode.collectLatest {
                    refreshSkills()
                    refreshSubAgents()
                }
            }

            launch {
                connectionState.collectLatest { state ->
                    if (state == ConnectionState.CONNECTED) {
                        refreshSkills()
                        refreshSubAgents()
                    }
                }
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
                toolSafetySettingsRepository.disableSafetyInterceptionFlow.collectLatest {
                    _disableSafetyInterception.value = it
                }
            }

            launch {
                combine(
                    _tokenStatsPeriod,
                    _filterProviderId,
                    _filterModel
                ) { period, filterProviderId, filterModel ->
                    Triple(period, filterProviderId, filterModel)
                }.flatMapLatest { (period, filterProviderId, filterModel) ->
                    val start = period.startMillis(System.currentTimeMillis())
                    val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                    combine(
                        combine(
                            if (period == TokenStatsPeriod.TODAY) {
                                llmCallRecordDao.getHourStats(start, tz, filterProviderId, filterModel)
                            } else {
                                llmCallRecordDao.getDayStats(start, tz, filterProviderId, filterModel)
                            },
                            llmCallRecordDao.getSummary(start, filterProviderId, filterModel)
                        ) { rawTrend, summary -> rawTrend to summary },
                        combine(
                            llmCallRecordDao.getProviderStats(start, filterProviderId, filterModel),
                            _providerStatsPage
                        ) { list, page ->
                            val total = list.size
                            val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                            val safePage = page.coerceIn(0, lastPage)
                            val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                            Triple(paged, safePage, total)
                        },
                        combine(
                            combine(
                                llmCallRecordDao.getModelStats(start, filterProviderId, filterModel),
                                _modelStatsPage
                            ) { list, page ->
                                val total = list.size
                                val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                                val safePage = page.coerceIn(0, lastPage)
                                val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                                ModelStatsPaging(paged, safePage, total)
                            },
                            // 总费用必须带渠道聚合：自定义单价按渠道存储，丢渠道就会取错价。
                            llmCallRecordDao.getModelProviderCostStats(start, filterProviderId, filterModel)
                        ) { modelPaging, costStats -> modelPaging to costStats },
                        // 明细分页：页号或总数变化时重查当前页，其余聚合不重复计算
                        combine(_tokenStatsPage, llmCallRecordDao.getCallsCount(start, filterProviderId, filterModel)) { page, total -> page to total }
                            .flatMapLatest { (page, total) ->
                                llmCallRecordDao.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE, filterProviderId, filterModel)
                                    .map { calls -> Triple(calls, total, page) }
                            },
                        // 可选筛选选项列表（全局不限过滤条件时的渠道与模型列表）
                        combine(
                            llmCallRecordDao.getProviderStats(start),
                            llmCallRecordDao.getModelStats(start),
                            _providers
                        ) { allProviders, allModels, configuredProviders ->
                            val providerList = (allProviders.mapNotNull { p ->
                                val id = p.providerId ?: return@mapNotNull null
                                id to (p.providerName ?: id)
                            } + configuredProviders.map { it.id to it.name }).distinctBy { it.first }
                            val modelList = allModels.mapNotNull { it.model }.distinct()
                            val keyCounts = configuredProviders.associate { it.id to it.apiKeys.size }
                            Triple(providerList, modelList, keyCounts)
                        }
                    ) { (rawTrend, summary), (pProviders, pPage, pTotal), (modelPaging, costStats), (calls, total, page), (availableProviders, availableModels, keyCounts) ->
                        val trend = padTrend(period, rawTrend, tz)
                        val costs = withContext(Dispatchers.Default) {
                            val perCall = calls.associate {
                                it.record.id to callCostUsd(it.record.providerId, it.record.model, it.record.inputTokens.toLong(), it.record.cachedInputTokens.toLong(), it.record.outputTokens.toLong(), it.record.cacheCreationTokens.toLong())
                            }
                            val periodTotal = costStats.sumOf { s ->
                                callCostUsd(s.providerId, s.model, s.inputTokens, s.cachedInputTokens, s.outputTokens, s.cacheCreationTokens) ?: 0.0
                            }
                            perCall to periodTotal
                        }
                        TokenStatsUiState(
                            period = period,
                            summary = summary,
                            trend = trend,
                            providers = pProviders,
                            models = modelPaging.paged,
                            recentCalls = calls,
                            callsPage = page,
                            callsTotal = total,
                            providersPage = pPage,
                            providersTotal = pTotal,
                            modelsPage = modelPaging.page,
                            modelsTotal = modelPaging.total,
                            totalCostUsd = costs.second,
                            recentCallCosts = costs.first,
                            filterProviderId = filterProviderId,
                            filterModel = filterModel,
                            availableProviders = availableProviders,
                            availableModels = availableModels,
                            providerKeyCounts = keyCounts
                        )
                    }
                }.collectLatest { _tokenStats.value = it }
            }
        }
    }

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        viewModelScope.launch {
            saveMcpServerInternal(originalName, initialScope, config, scope)
        }
    }

    private suspend fun saveMcpServerInternal(
        originalName: String?,
        initialScope: McpScope?,
        config: McpServerConfig,
        scope: McpScope
    ) {
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

    /** 保存最新表单配置并立即发起 OAuth 授权，确保弹窗中修改的 client_id 等字段即时生效。 */
    fun saveAndAuthorizeMcpServer(
        originalName: String?,
        initialScope: McpScope?,
        config: McpServerConfig,
        scope: McpScope
    ) {
        if (_mcpAuthState.value.inProgress) return
        viewModelScope.launch {
            saveMcpServerInternal(originalName, initialScope, config, scope)
            _mcpAuthState.value = McpAuthUiState(serverName = config.name, inProgress = true)
            val result = mcpManager.authorize(config.name)
            _mcpAuthState.value = when {
                result.isSuccess -> McpAuthUiState(serverName = config.name, success = true)
                result.exceptionOrNull() is McpOAuthCancelledException -> McpAuthUiState()
                else -> McpAuthUiState(serverName = config.name, error = result.exceptionOrNull()?.message)
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

    /**
     * 批量删除 MCP server（列表多选批量操作）。
     * 配置按作用域分组写：同作用域只读一次、写一次；写文件不能逐条做，否则 N 条就是 N 次全量重写。
     * 删完一次性断开（[McpManager.removeServers]），避免反复抢连接锁。
     */
    fun deleteMcpServers(targets: List<Pair<String, McpScope>>) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.groupBy({ it.second }, { it.first }).forEach { (scope, names) ->
                val nameSet = names.toSet()
                val base = if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.getGlobalServers()
                } else {
                    mcpConfigRepository.getProjectServers()
                }
                val updated = base.filterNot { it.name in nameSet }
                if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.setGlobalServers(updated)
                } else {
                    mcpConfigRepository.setProjectServers(updated)
                }
            }
            _mcpReloading.value = true
            try {
                mcpManager.removeServers(targets.map { it.first })
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 批量启用 / 停用 MCP server：配置按作用域分组各写一次，再逐个重连（停用的会落到 DISABLED 状态）。 */
    fun setMcpServersEnabled(targets: List<Pair<String, McpScope>>, enabled: Boolean) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.groupBy({ it.second }, { it.first }).forEach { (scope, names) ->
                val nameSet = names.toSet()
                val base = if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.getGlobalServers()
                } else {
                    mcpConfigRepository.getProjectServers()
                }
                val updated = base.map { if (it.name in nameSet) it.copy(enabled = enabled) else it }
                if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.setGlobalServers(updated)
                } else {
                    mcpConfigRepository.setProjectServers(updated)
                }
            }
            _mcpReloading.value = true
            try {
                targets.map { it.first }.distinct().forEach { mcpManager.reloadServer(it) }
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** 批量重连 MCP server（多选后一次性刷新连接状态）。 */
    fun reconnectMcpServers(names: Collection<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                names.distinct().forEach { mcpManager.reloadServer(it) }
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

    /** 触发某个 server 的 OAuth 授权，结果（含失败原因）写入 [mcpAuthState] 供编辑弹窗展示。 */
    fun authorizeMcpServer(name: String) {
        if (_mcpAuthState.value.inProgress) return
        viewModelScope.launch {
            _mcpAuthState.value = McpAuthUiState(serverName = name, inProgress = true)
            val result = mcpManager.authorize(name)
            _mcpAuthState.value = when {
                result.isSuccess -> McpAuthUiState(serverName = name, success = true)
                result.exceptionOrNull() is McpOAuthCancelledException -> McpAuthUiState()
                else -> McpAuthUiState(serverName = name, error = result.exceptionOrNull()?.message)
            }
        }
    }

    fun cancelMcpAuthorize(name: String) {
        mcpManager.cancelAuthorize(name)
    }

    /** 从标准 mcpServers JSON 批量导入到指定作用域；同名覆盖。 */
    fun importMcpServersFromJson(raw: String, scope: McpScope) {
        viewModelScope.launch {
            _mcpImportState.value = McpImportUiState(inProgress = true)
            val parsed = runCatching { mcpConfigRepository.parse(raw) }.getOrElse {
                _mcpImportState.value = McpImportUiState(error = it.message)
                return@launch
            }
            val valid = parsed.filter { McpServerConfig.isValidName(it.name) }
            if (valid.isEmpty()) {
                _mcpImportState.value = McpImportUiState(error = context.getString(R.string.mcp_json_import_empty))
                return@launch
            }
            runCatching {
                val existing = if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.getGlobalServers()
                } else {
                    mcpConfigRepository.getProjectServers()
                }
                val merged = LinkedHashMap<String, McpServerConfig>()
                existing.forEach { merged[it.name] = it }
                valid.forEach { merged[it.name] = it }
                val list = merged.values.toList()
                if (scope == McpScope.GLOBAL) {
                    mcpConfigRepository.setGlobalServers(list)
                } else {
                    mcpConfigRepository.setProjectServers(list)
                }
            }.onSuccess {
                _mcpImportState.value = McpImportUiState(importedCount = valid.size)
                mcpManager.reload()
            }.onFailure {
                _mcpImportState.value = McpImportUiState(error = it.message)
            }
        }
    }

    fun clearMcpImportState() {
        _mcpImportState.value = McpImportUiState()
    }

    fun logoutMcpServer(name: String) {
        viewModelScope.launch {
            mcpManager.logout(name)
            _mcpAuthState.value = McpAuthUiState()
        }
    }

    fun clearMcpAuthState() {
        _mcpAuthState.value = McpAuthUiState()
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
                val disabled = skillRepository.disabledNames()
                skillRepository.listAllSkills().map { entry ->
                    SkillUiEntry(
                        name = entry.skill.name,
                        description = entry.skill.description,
                        scope = entry.scope,
                        disabled = entry.skill.name.lowercase() in disabled,
                        instructions = entry.skill.instructions,
                        requiredTools = entry.skill.requiredTools
                    )
                }
            }
        }
    }

    /** 切换技能的启用/禁用状态（写入对应作用域的 skills.json）。 */
    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { skillRepository.setSkillDisabled(name, !enabled, scope) }
            refreshSkills()
        }
    }

    /** 批量启用 / 停用技能（列表多选批量操作）：一次 IO 写完后只刷新一次列表。 */
    fun setSkillsEnabled(targets: List<Pair<String, SkillScope>>, enabled: Boolean) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { (name, scope) -> skillRepository.setSkillDisabled(name, !enabled, scope) }
            }
            refreshSkills()
        }
    }

    /**
     * 删除指定作用域的技能（删除其目录，不可恢复），完成后刷新列表。
     * 删除本体在 IO 线程跑（远程模式经 SFTP 递归删，不能在主线程）；期间 [skillDeleting] 置名供 UI 转圈。
     */
    fun deleteSkill(name: String, scope: SkillScope) = deleteSkills(listOf(name to scope))

    /**
     * 批量删除技能（列表多选批量操作）。逐条串行删除，每删完一条从 [skillDeleting] 集合里移出；
     * 远程模式下 SFTP 递归删除较慢，串行可避免把连接压满，界面也能看出删到哪了。
     */
    fun deleteSkills(targets: List<Pair<String, SkillScope>>) {
        if (targets.isEmpty() || _skillDeleting.value.isNotEmpty()) return
        _skillDeleting.value = targets.map { it.first }.toSet()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    targets.forEach { (name, scope) ->
                        skillRepository.deleteSkill(name, scope)
                        _skillDeleting.value = _skillDeleting.value - name
                    }
                }
                refreshSkills()
            } finally {
                _skillDeleting.value = emptySet()
            }
        }
    }

    /**
     * 保存技能。[originalName] 为编辑前的名称，新建时为 null；
     * 结果转成 [skillSaveState]，编辑页据此退回列表或就地报错。
     */
    fun saveSkill(form: SkillForm, scope: SkillScope, originalName: String? = null) {
        if (_skillSaveState.value is SkillSaveState.Saving) return
        _skillSaveState.value = SkillSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                skillRepository.save(form, scope, originalName)
            }
            _skillSaveState.value = if (error == null) {
                SkillSaveState.Saved
            } else {
                SkillSaveState.Failed(error)
            }
            if (error == null) refreshSkills()
        }
    }

    fun clearSkillSaveState() {
        _skillSaveState.value = SkillSaveState.Idle
    }

    /**
     * 从所选 Markdown 文件导入技能：读取文本并写入指定作用域。
     * 扩展名不在白名单内直接报「类型不支持」，不落盘。
     */
    fun importSkillFromMarkdown(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(MARKDOWN_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw java.io.IOException("openInputStream returned null")
                        skillRepository.importMarkdown(text, name.substringBeforeLast('.'), scope)
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED) }
                }
            }
            finishSkillImport(report)
        }
    }

    /** 从所选 zip 压缩包导入技能（可含多个技能），写入指定作用域。 */
    fun importSkillsFromZip(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(ZIP_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            skillRepository.importZip(input, name.substringBeforeLast('.'), scope)
                        } ?: throw java.io.IOException("openInputStream returned null")
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.INVALID_ARCHIVE) }
                }
            }
            finishSkillImport(report)
        }
    }

    private suspend fun finishSkillImport(report: SkillImportReport) {
        _skillImportState.value = SkillImportState.Done(report)
        if (report.imported.isNotEmpty()) refreshSkills()
    }

    fun clearSkillImportState() {
        _skillImportState.value = SkillImportState.Idle
    }

    /** 查询所选文件的显示名（含扩展名）；取不到时回退到 URI 末段。 */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** 重新扫描子代理定义（进入设置页 / 删除后调用，反映盘上增删改）。 */
    fun refreshSubAgents() {
        viewModelScope.launch {
            _subAgents.value = withContext(Dispatchers.IO) {
                val disabled = agentDefinitionRepository.disabledNames()
                agentDefinitionRepository.listAll().map { entry ->
                    SubAgentUiEntry(
                        name = entry.definition.name,
                        description = entry.definition.description,
                        scope = entry.scope,
                        disabled = entry.definition.name.lowercase() in disabled,
                        providerId = entry.definition.providerId,
                        model = entry.definition.model,
                        reasoningEffort = entry.definition.reasoningEffort,
                        mode = entry.definition.mode,
                        allowedTools = entry.definition.allowedTools,
                        disallowedTools = entry.definition.disallowedTools,
                        inject = entry.definition.inject,
                        prompt = entry.definition.prompt,
                        filePath = entry.definition.filePath
                    )
                }
            }
        }
    }

    /** 切换子代理启用/禁用（写入对应作用域的 agents.json）；禁用后不再进主代理的可派发清单。 */
    fun setSubAgentEnabled(name: String, enabled: Boolean, scope: AgentDefinitionScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agentDefinitionRepository.setDisabled(name, !enabled, scope) }
            refreshSubAgents()
        }
    }

    /** 批量启用 / 停用子代理（列表多选批量操作）：一次 IO 写完后只刷新一次列表。 */
    fun setSubAgentsEnabled(targets: List<Pair<String, AgentDefinitionScope>>, enabled: Boolean) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { (name, scope) -> agentDefinitionRepository.setDisabled(name, !enabled, scope) }
            }
            refreshSubAgents()
        }
    }

    /**
     * 保存子代理定义。[originalName] 为编辑前的名称，新建时为 null；
     * 结果转成 [subAgentSaveState]，编辑页据此退回列表或就地报错。
     */
    fun saveSubAgent(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ) {
        if (_subAgentSaveState.value is SubAgentSaveState.Saving) return
        _subAgentSaveState.value = SubAgentSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                agentDefinitionRepository.save(form, scope, originalName)
            }
            _subAgentSaveState.value = if (error == null) {
                SubAgentSaveState.Saved
            } else {
                SubAgentSaveState.Failed(error)
            }
            if (error == null) refreshSubAgents()
        }
    }

    fun clearSubAgentSaveState() {
        _subAgentSaveState.value = SubAgentSaveState.Idle
    }

    /** 当前已注册的全部工具名（含 MCP 动态工具），供编辑页勾选工具白名单与黑名单。 */
    fun availableToolNames(): List<String> = toolRegistry.getAvailableTools().map { it.name }

    /** 删除指定作用域的子代理定义文件（不可恢复），随后立即刷新列表。 */
    fun deleteSubAgent(name: String, scope: AgentDefinitionScope) = deleteSubAgents(listOf(name to scope))

    /**
     * 批量删除子代理（列表多选批量操作）。语义同 [deleteSkills]：串行删、逐条报进度。
     */
    fun deleteSubAgents(targets: List<Pair<String, AgentDefinitionScope>>) {
        if (targets.isEmpty() || _subAgentDeleting.value.isNotEmpty()) return
        _subAgentDeleting.value = targets.map { it.first }.toSet()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    targets.forEach { (name, scope) ->
                        agentDefinitionRepository.delete(name, scope)
                        _subAgentDeleting.value = _subAgentDeleting.value - name
                    }
                }
                refreshSubAgents()
            } finally {
                _subAgentDeleting.value = emptySet()
            }
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
        val current = _updateCheckState.value
        // 下载中 / 安装包已就绪：不打断当前流程；手动点「版本」行只把弹窗重新亮出来，不重下
        if (current is UpdateCheckUiState.Downloading || current is UpdateCheckUiState.ReadyToInstall) {
            if (manual) _updateCheckVisible.value = true
            return
        }
        // 已在检测中：手动点击同样只需把弹窗亮回来（用户可能刚把它关掉）
        if (current == UpdateCheckUiState.Checking) {
            if (manual) _updateCheckVisible.value = true
            return
        }
        if (!manual) {
            if (!updateCheckSettingsRepository.autoCheckEnabled) return
            if (updateCheckSettingsRepository.hasCheckedToday()) return
        }
        viewModelScope.launch {
            // 手动检查显示「检测中」反馈；自动检查全程无感，仅检测到新版本才弹窗
            if (manual) {
                _updateCheckVisible.value = true
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
                is UpdateCheckResult.NewVersion -> {
                    _updateCheckVisible.value = true
                    UpdateCheckUiState.NewVersion(
                        latestTag = result.info.latestTag,
                        changelog = result.info.changelog,
                        asset = result.info.latestAsset
                    )
                }
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

    /**
     * 应用内安装是否可用：只对正式包开放。beta/debug 变体的 applicationId 带后缀
     * （`com.aicode.beta` / `com.aicode.debug`），装 GitHub 上的正式包等于「装上第二个 App」，
     * 包名自检也会把它拦下——索性不给下载入口，省掉一次十几 MB 的白下载。
     */
    val inAppInstallSupported: Boolean
        get() = context.packageName == RELEASE_APPLICATION_ID

    /**
     * 下载并安装（更新弹窗主按钮）。
     *
     * 按当前状态分派：已就绪 → 直接拉起安装器（用户可能刚授完权回来）；检测到新版本 → 下载；
     * 下载失败 → 用保留的版本信息重试。下载走「镜像 → 直连」两跳，下完先做包名/签名/versionCode
     * 自检，全过才交给系统安装器。
     */
    fun downloadAndInstall() {
        if (!inAppInstallSupported) return
        when (val current = _updateCheckState.value) {
            is UpdateCheckUiState.ReadyToInstall -> startInstall(current)
            is UpdateCheckUiState.NewVersion -> startDownload(current)
            is UpdateCheckUiState.DownloadFailed -> startDownload(current.previous)
            else -> Unit
        }
    }

    private fun startDownload(target: UpdateCheckUiState.NewVersion) {
        val asset = target.asset
        if (asset == null) {
            // Release 没挂 APK 资产（或资产命名不认识）：只能引导去浏览器下载
            _updateCheckState.value = UpdateCheckUiState.DownloadFailed(
                previous = target,
                message = context.getString(R.string.about_update_verify_not_apk)
            )
            return
        }
        if (apkDownloadJob?.isActive == true) return
        _updateCheckVisible.value = true
        apkDownloadJob = viewModelScope.launch {
            _updateCheckState.value = UpdateCheckUiState.Downloading(
                latestTag = target.latestTag,
                percent = 0,
                downloaded = 0,
                total = asset.size
            )
            val result = apkDownloader.download(
                url = asset.url,
                mirror = updateCheckSettingsRepository.downloadMirror,
                fileName = "aicode-${target.latestTag}.apk",
                expectedSize = asset.size,
                expectedSha256 = asset.sha256
            ) { downloaded, total ->
                _updateCheckState.value = UpdateCheckUiState.Downloading(
                    latestTag = target.latestTag,
                    percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0,
                    downloaded = downloaded,
                    total = total
                )
            }
            when (result) {
                is ApkDownloadResult.Success -> {
                    val verify = apkVerifier.verify(result.file)
                    if (verify is ApkVerifyResult.Ok) {
                        val ready = UpdateCheckUiState.ReadyToInstall(
                            previous = target,
                            apk = result.file,
                            needPermission = !ApkInstaller.canInstall(context)
                        )
                        _updateCheckState.value = ready
                        // 已授权就直接拉起安装器；未授权则停在「就绪」态，授权后回来再点一次即可
                        if (!ready.needPermission) startInstall(ready)
                    } else {
                        _updateCheckState.value = UpdateCheckUiState.DownloadFailed(
                            previous = target,
                            message = verifyFailureMessage(verify)
                        )
                    }
                }
                is ApkDownloadResult.Failure -> {
                    _updateCheckState.value = UpdateCheckUiState.DownloadFailed(
                        previous = target,
                        message = result.message
                    )
                }
            }
        }
    }

    /**
     * 拉起系统安装器。未授权「安装未知应用」时先引导去设置页，授权后回到 App 再点一次即装
     * （安装包已落盘，不重下）。
     */
    private fun startInstall(ready: UpdateCheckUiState.ReadyToInstall) {
        when (ApkInstaller.install(context, ready.apk)) {
            ApkInstaller.Result.STARTED -> {
                FileLogger.i("SettingsViewModel", "已拉起系统安装器：${ready.apk.name}")
                _updateCheckVisible.value = false
                _updateCheckState.value = UpdateCheckUiState.Idle
            }
            ApkInstaller.Result.NEED_PERMISSION -> {
                _updateCheckState.value = ready.copy(needPermission = true)
                _updateCheckVisible.value = true
                if (!ApkInstaller.openInstallPermissionSettings(context)) {
                    _updateCheckState.value = UpdateCheckUiState.DownloadFailed(
                        previous = ready.previous,
                        message = context.getString(R.string.about_update_install_failed)
                    )
                }
            }
            ApkInstaller.Result.NO_HANDLER -> {
                _updateCheckState.value = UpdateCheckUiState.DownloadFailed(
                    previous = ready.previous,
                    message = context.getString(R.string.about_update_install_failed)
                )
            }
        }
    }

    private fun verifyFailureMessage(result: ApkVerifyResult): String = when (result) {
        is ApkVerifyResult.Ok -> ""
        is ApkVerifyResult.NotAnApk -> context.getString(R.string.about_update_verify_not_apk)
        is ApkVerifyResult.PackageMismatch -> context.getString(R.string.about_update_verify_package)
        is ApkVerifyResult.SignatureMismatch -> context.getString(R.string.about_update_verify_signature)
        is ApkVerifyResult.NotNewer -> context.getString(R.string.about_update_verify_not_newer)
    }

    /** 关闭更新弹窗：只收起界面，下载中的任务继续跑（状态保留，重开可见）。 */
    fun dismissUpdateCheck() {
        _updateCheckVisible.value = false
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

    /** 更新包下载镜像源；留空表示直连 GitHub。 */
    fun setUpdateCheckMirror(mirror: String) {
        updateCheckSettingsRepository.downloadMirror = mirror
        _updateCheckMirror.value = updateCheckSettingsRepository.downloadMirror
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * 判断日志行是否属于某个 MCP server：只认 MCP 组件写下的日志（tag 以 `Mcp` 开头，
     * 如 McpManager/McpClient/McpStdioTransport/McpTool），且消息里引用了该 server——
     * 要么是消息前缀 `[名字]`，要么是命名空间工具名 `mcp__名字__`。
     * 不能只按 server 名做子串匹配：那会把任何恰好提到该名字的无关日志一并捞进来。
     */
    private fun isMcpServerLogLine(line: String, serverName: String): Boolean {
        if (!line.contains("[Mcp")) return false
        return line.contains("[$serverName]") || line.contains("mcp__${serverName}__")
    }

    /** 标准日志行的开头（时间 + 等级），用于区分堆栈续行。与 FileLogger 落盘格式对齐。 */
    private val logLinePrefixRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} (?:VERBOSE|DEBUG|INFO|WARN|ERROR) """)

    /**
     * 按 MCP server 过滤日志：命中的标准日志行连同其后的堆栈续行一并保留。
     * 堆栈续行本身不含 tag 与 server 名，逐行匹配会把报错的堆栈整段丢掉。
     */
    private fun filterMcpServerLines(rawLines: List<String>, serverName: String): List<String> {
        val result = ArrayList<String>()
        var keepContinuation = false
        for (line in rawLines) {
            if (logLinePrefixRegex.containsMatchIn(line)) {
                keepContinuation = isMcpServerLogLine(line, serverName)
                if (keepContinuation) result.add(line)
            } else if (keepContinuation) {
                result.add(line)
            }
        }
        return result
    }

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
                        filterMcpServerLines(rawLines, filterServerName)
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

    // 仅持久化标志位——窗口 FLAG_KEEP_SCREEN_ON 的增删由 MainActivity 监听 enabledFlow 统一完成。
    fun setScreenOnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            screenOnSettingsRepository.setEnabled(enabled)
        }
    }

    fun setAgentSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentSoundSettingsRepository.setEnabled(enabled)
        }
    }

    fun setLiveNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            liveNotificationSettingsRepository.setEnabled(enabled)
        }
    }

    fun setApprovalNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            approvalNotificationSettingsRepository.setEnabled(enabled)
        }
    }

    /** 拉取模型成功后是否自动移除远端已不存在的本地模型。 */
    fun setAutoRemoveStaleModels(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setAutoRemoveStaleModels(enabled)
        }
    }

    /** 启动（含切换工作区）时进入新会话还是最近会话。 */
    fun setStartupSessionMode(mode: StartupSessionMode) {
        viewModelScope.launch {
            generalSettingsRepository.setStartupSessionMode(mode)
        }
    }

    /** 流式请求首字超时（秒）；0 表示不限制。 */
    fun setFirstByteTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setFirstByteTimeoutSec(sec)
        }
    }

    /** 流式响应相邻数据块间隔超时（秒）；0 表示不限制。 */
    fun setStreamIdleTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setStreamIdleTimeoutSec(sec)
        }
    }

    /** 网络请求最大重试次数；0 表示不重试。 */
    fun setMaxNetworkRetries(count: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setMaxNetworkRetries(count)
        }
    }

    /** 回车键是否直接发送消息。 */
    fun setEnterToSend(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setEnterToSend(enabled)
        }
    }

    /** 自动压缩触发阈值（上下文窗口百分比，1..100）。 */
    fun setCompactionThresholdPercent(percent: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setCompactionThresholdPercent(percent)
        }
    }

    /** sendFile 单个文件大小上限（MB，不小于 1）。 */
    fun setSendFileMaxSizeMb(mb: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setSendFileMaxSizeMb(mb)
        }
    }

    /** goal 自主循环最大续跑轮数（不小于 1）。 */
    fun setGoalMaxTurns(turns: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setGoalMaxTurns(turns)
        }
    }

    /** goal 自主循环 token 预算（不小于 1000）。 */
    fun setGoalMaxTokens(tokens: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setGoalMaxTokens(tokens)
        }
    }

    /** 移除外部本地工作区时是否一并删除其聊天记录。 */
    fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setDeleteExternalWorkspaceSessions(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    fun setThemePreset(id: String) {
        viewModelScope.launch {
            themeSettingsRepository.setThemePresetId(id)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themeSettingsRepository.setDynamicColorEnabled(enabled)
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

    /**
     * 调节背景图透明度（0~0.2，实际范围由 BackgroundSettingsRepository 的 MIN_ALPHA/MAX_ALPHA 决定）。
     *
     * 滑块拖动会连续打进数十个值，DataStore 的写是串行的，逐个落盘会积压出肉眼可见的延迟，
     * 回读又会把整个设置页带着重组。此处只保留最后一个值，停手约 80ms 后写一次。
     */
    fun setBackgroundAlpha(alpha: Float) {
        backgroundAlphaWriteJob?.cancel()
        backgroundAlphaWriteJob = viewModelScope.launch {
            delay(BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS)
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
     * 连接参数由 ActiveRemoteConnectionResolver 按激活 profile 实时解析，AIEditorApp 的常驻订阅据此重连。
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
                if (profile.rootfsSource !is RootfsSource.RemoteSsh) return
                // 连接参数由 ActiveRemoteConnectionResolver 按激活 profile 实时解析，AIEditorApp 的常驻订阅据此重连。
                executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
            }
        }
    }

    /** 重置容器：内置恢复出厂（清覆盖配置 + 删 rootfs），自定义本地镜像删 rootfs 下次重新解压。远程 SSH 无本地数据，UI 不提供入口。 */
    fun resetContainer(profile: ContainerProfile) {
        if (_containerReset.value != null) return
        viewModelScope.launch {
            if (profile.isBuiltin) {
                containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
            }
            withResetProgress(profile) { onProgress ->
                containerInstaller.resetRootfs(profile, onProgress)
            }
        }
    }

    /** 删 profile 附带的 rootfs 清理，带进度；内置与远程 SSH 没本地 rootfs 可删，不弹进度框。 */
    private suspend fun cleanRootfsWithProgress(profile: ContainerProfile) {
        if (profile.isBuiltin || profile.rootfsSource is RootfsSource.RemoteSsh) {
            containerInstaller.deleteCustomRootfs(profile)
            return
        }
        withResetProgress(profile) { onProgress ->
            containerInstaller.deleteCustomRootfs(profile, onProgress)
        }
    }

    /**
     * 包住一次 rootfs 删除：期间把已删条目数写进 [_containerReset] 驱动进度框，结束后清掉。
     * 大容器删一遍要几十秒，删除本体在 IO 线程跑，界面只等不卡。
     */
    private suspend fun withResetProgress(
        profile: ContainerProfile,
        block: suspend ((Int) -> Unit) -> Unit
    ) {
        _containerReset.value = ContainerResetUiState(profile.name, 0)
        try {
            block { deleted -> _containerReset.value = ContainerResetUiState(profile.name, deleted) }
        } finally {
            _containerReset.value = null
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

    /**
     * 容器列表长按拖拽排序：同步更新内存顺序（列表已重排 UI 才重绘，避免松手瞬间回跳），
     * 再异步持久化新顺序（存储顺序即显示顺序）。
     */
    fun reorderContainerProfiles(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _customProfiles.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _customProfiles.value = reordered
        viewModelScope.launch {
            containerSettingsRepository.reorderCustomProfiles(reordered.map { it.id })
        }
    }

    /** 编辑自定义 profile：覆盖配置；若镜像来源变了则删旧 rootfs 触发重新解压。 */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                cleanRootfsWithProgress(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** 删除容器 profile（内置 Alpine 也可删，删光后由空态恢复），连带清理本地 rootfs。 */
    fun deleteContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            cleanRootfsWithProgress(profile)
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

    /** 标记「提示词」页公告已展示；与容器公告同理，内容更新（哈希变）后自动重新弹。 */
    fun markPromptsAnnouncementShown() {
        viewModelScope.launch {
            generalSettingsRepository.markPromptsAnnouncementShown(sha256(_promptsAnnouncementText.value))
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
        // 记录里存「探测到的精确版本」（如 Debian 的 12.15），文件名与后续展示都以此为准，
        // 避免用户下载到 12.15 却在文件名/容器名里看到 12。
        val version = _imageVersions.value[entry.id] ?: entry.version
        val fileName = "download_${entry.id}_${version}_${System.currentTimeMillis()}.$suffix"
        // 目录里带了官方校验和就校验（Alpine/Ubuntu 有，Debian 没有 → 传 null 跳过）
        val expectedSha256 = containerImageCatalog.sha256For(entry, ContainerImageCatalog.CURRENT_ABI)
        downloadJob = viewModelScope.launch {
            _containerImageDownload.value =
                ContainerImageDownloadUiState.Downloading(entry.id, entry.name, version, sourceId, 0, 0)
            try {
                val uri = containerImageDownloader.download(url, fileName, expectedSha256) { read, total ->
                    _containerImageDownload.value = ContainerImageDownloadUiState.Downloading(
                        entry.id, entry.name, version, sourceId, read, total
                    )
                }
                // 下载完成即持久化记录，安装与否都保留，跨重启可见。版本一并记下，
                // 之后上游发新补丁时列表才能提示「有新版」而不是一直显示「已下载」。
                containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entry.id, uri, version = version))
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Done(entry.id, entry.name, version, uri)
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
     */
    fun importDownloadedImage(entryId: String, fileUri: String) {
        val entry = _imageCatalog.value.firstOrNull { it.id == entryId } ?: return
        val record = _downloadedImages.value[entryId]
        viewModelScope.launch {
            val profile = ContainerProfile(
                id = "custom-${System.currentTimeMillis()}",
                // 用**磁盘上这份文件的实际版本**命名：记录字段优先，升级前的旧记录退回文件名解析，
                // 都拿不到才用目录当前版本。否则会出现「名字写着 3.21.8、实际解压的是 3.21.7」。
                name = entry.displayLabel(
                    record?.effectiveVersion()?.takeIf { it.isNotBlank() } ?: _imageVersions.value[entry.id]
                ),
                rootfsSource = RootfsSource.LocalFile(fileUri),
                shellPath = null,
                extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS,
                isBuiltin = false
            )
            containerSettingsRepository.upsertCustomProfile(profile)
            // 保留已下载记录（含下载时的版本号，供「有新版」判定），仅标记已导入；UI 状态不消失。
            // 旧记录在这里顺带把文件名解析出的版本回写进去，下次就不必再解析。
            containerSettingsRepository.upsertDownloadedImage(
                DownloadedImageRecord(
                    entryId = entryId,
                    fileUri = fileUri,
                    version = record?.effectiveVersion().orEmpty(),
                    installed = true
                )
            )
            _containerImageDownload.value = ContainerImageDownloadUiState.Idle
        }
    }

    /** 删除已下载的镜像：清记录 + 删下载文件（仅限 rootfs_images 目录，防误删）；若正在下载该条目先取消。 */
    fun deleteDownloadedImage(entryId: String) = deleteDownloadedImages(setOf(entryId))

    /**
     * 批量删除已下载镜像（列表多选批量操作）。逐条清记录 + 删文件；
     * 正在下载的条目若在选中集合里，先取消下载再删。
     */
    fun deleteDownloadedImages(entryIds: Set<String>) {
        if (entryIds.isEmpty()) return
        val downloadingEntryId = (_containerImageDownload.value as? ContainerImageDownloadUiState.Downloading)?.entryId
        if (downloadingEntryId != null && downloadingEntryId in entryIds) {
            cancelContainerImageDownload()
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                entryIds.forEach { entryId ->
                    val record = _downloadedImages.value[entryId] ?: return@forEach
                    runCatching {
                        val file = java.io.File(Uri.parse(record.fileUri).path ?: return@runCatching)
                        if (file.parentFile?.name == "rootfs_images") file.delete()
                    }
                    containerSettingsRepository.removeDownloadedImage(entryId)
                }
            }
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

    /** 设置某子代理模型档位绑定的模型；两个参数都留空等同 [clearSubagentTier]。 */
    fun setSubagentTierModel(tier: SubagentModelTier, providerId: String, model: String) {
        viewModelScope.launch {
            if (providerId.isBlank() || model.isBlank()) {
                subagentTierSettingsRepository.clear(tier)
            } else {
                subagentTierSettingsRepository.setSelection(tier, providerId, model)
            }
        }
    }

    /** 清空某档位——该档回退到「未配置」，派发时按降级链处理。 */
    fun clearSubagentTier(tier: SubagentModelTier) {
        viewModelScope.launch {
            subagentTierSettingsRepository.clear(tier)
        }
    }

    /** 设置生图专用模型；providerId 留空等同 [clearImageGenModel]（未配置）。 */
    fun setImageGenModel(providerId: String, model: String) {
        viewModelScope.launch {
            imageGenModelSettingsRepository.setImageGenModel(providerId, model)
        }
    }

    /** 清空生图专用模型——回到未配置状态（生图工具将提示先配置）。 */
    fun clearImageGenModel() {
        viewModelScope.launch {
            imageGenModelSettingsRepository.clear()
        }
    }

    fun setTokenStatsPeriod(period: TokenStatsPeriod) {
        _tokenStatsPeriod.value = period
        // 切周期后明细与统计切片回到第一页
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** 设置供应商筛选（null 为全部）。 */
    fun setFilterProviderId(providerId: String?) {
        if (_filterProviderId.value == providerId) return
        _filterProviderId.value = providerId
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** 设置模型筛选（null 为全部）。 */
    fun setFilterModel(model: String?) {
        if (_filterModel.value == model) return
        _filterModel.value = model
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** 清除所有筛选条件。 */
    fun clearTokenStatsFilters() {
        if (_filterProviderId.value == null && _filterModel.value == null) return
        _filterProviderId.value = null
        _filterModel.value = null
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /**
     * 单次调用的预估费用（USD）；模型无单价返回 null。
     * 单价解析与缓存价回退统一在 [ModelCostCalculator]。
     */
    private suspend fun callCostUsd(
        providerId: String?,
        model: String?,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val modelId = model ?: return null
        val provider = _providers.value.firstOrNull { it.id == providerId }
        return modelCostCalculator.costUsd(
            providerId = providerId.orEmpty(),
            providerType = provider?.type ?: ProviderType.OPENAI,
            model = modelId,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens
        )
    }

    /** 调用明细翻页；越界时钳制到合法范围。 */
    fun setTokenStatsPage(page: Int) {
        val total = _tokenStats.value.callsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / CALLS_PAGE_SIZE
        _tokenStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** 渠道统计翻页；越界时钳制到合法范围。 */
    fun setProviderStatsPage(page: Int) {
        val total = _tokenStats.value.providersTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _providerStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** 模型统计翻页；越界时钳制到合法范围。 */
    fun setModelStatsPage(page: Int) {
        val total = _tokenStats.value.modelsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _modelStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** 清空全部调用记录（Token 统计页右上角重置）；Room Flow 自动把各聚合刷新为空。 */
    fun resetTokenStats() {
        viewModelScope.launch {
            llmCallRecordDao.deleteAll()
            _filterProviderId.value = null
            _filterModel.value = null
            _tokenStatsPage.value = 0
            _providerStatsPage.value = 0
            _modelStatsPage.value = 0
        }
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    /** 批量启用 / 停用提供商（列表多选批量操作）。 */
    fun setProvidersEnabled(ids: Collection<String>, isEnabled: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setProvidersEnabled(ids, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            val removedModels = (repository.getProviderById(provider.id)?.models ?: emptyList())
                .toSet() - provider.models.toSet()
            repository.saveProvider(provider)
            if (removedModels.isNotEmpty()) cleanupRemovedModels(provider, removedModels)
            // 保存后重置该提供商在内存中的面板状态，以便主页即时以最新脚本与配置重新加载
            _providerDashboards.update { it - provider.id }
        }
    }

    /**
     * 模型被移出列表后，清掉仍指向它的各处选中态，否则主页与各专用模型仍会拿已删除的模型发请求。
     * 会话绑定模型不在此清理，由 resolveProviderConfig / AIChatPanel 读取时校验回退，避免遍历全部历史会话。
     */
    private suspend fun cleanupRemovedModels(provider: AIProviderConfig, removed: Set<String>) {
        if (provider.selectedModel in removed) {
            repository.setSelectedModel(provider.id, provider.models.firstOrNull() ?: "")
        }
        if (defaultModelSettingsRepository.getDefaultProviderId() == provider.id &&
            defaultModelSettingsRepository.getDefaultModel() in removed
        ) {
            defaultModelSettingsRepository.clear()
        }
        if (visionModelSettingsRepository.getVisionProviderId() == provider.id &&
            visionModelSettingsRepository.getVisionModel() in removed
        ) {
            visionModelSettingsRepository.clear()
        }
        if (imageGenModelSettingsRepository.getImageGenProviderId() == provider.id &&
            imageGenModelSettingsRepository.getImageGenModel() in removed
        ) {
            imageGenModelSettingsRepository.clear()
        }
        if (compactionModelSettingsRepository.getCompactionProviderId() == provider.id &&
            compactionModelSettingsRepository.getCompactionModel() in removed
        ) {
            compactionModelSettingsRepository.clear()
        }
        if (titleModelSettingsRepository.getTitleProviderId() == provider.id &&
            titleModelSettingsRepository.getTitleModel() in removed
        ) {
            titleModelSettingsRepository.clear()
        }
        // 档位绑定的是 provider + model，提供商整个被删时按 provider 直接清档。
        subagentTierSettingsRepository.clearTiersUsingProvider(provider.id)
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    /** 批量删除提供商（列表多选批量操作）。 */
    fun deleteProviders(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteProviders(ids)
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
            modelApiService.fetchModels(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.customHeaders)
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
            _modelMetadata.update { current ->
                current + metadata.mapKeys { (model, _) -> modelMetadataKey(providerId, model) }
            }
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
                modelMetadataService.resolveAll(provider.id, provider.type, ids)
                    .forEach { (model, meta) -> resolved[modelMetadataKey(provider.id, model)] = meta }
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
            val result = modelApiService.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    /**
     * 主页模型弹层用：直接跑一次连通性测试并返回结果。
     * 不落 ViewModel 状态——弹层跨多个 provider，同名模型会撞 key，
     * 测试中的行集合与结果由弹层自己按 provider+模型持有。
     */
    suspend fun runModelTest(provider: AIProviderConfig, model: String): ModelTestResult =
        modelApiService.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    /**
     * 加载可用面板脚本列表（打开脚本选择器时调用）。经引擎在 IO 线程枚举，
     * 远程模式下即远端 `~/.aicode/scripts`，避免在主线程上做 SFTP 枚举。
     */
    fun loadDashboardScripts() {
        viewModelScope.launch {
            _dashboardScripts.value = withContext(Dispatchers.IO) {
                providerDashboardRunner.listAvailableScripts()
            }
        }
    }

    fun testDashboardScript(provider: AIProviderConfig, scriptPath: String) {
        viewModelScope.launch {
            _dashboardTestState.value = ProviderDashboardState.Loading
            providerDashboardRunner.runScript(provider, scriptPath)
                .onSuccess { result ->
                    _dashboardTestState.value = ProviderDashboardState.Success(result)
                }
                .onFailure { error ->
                    _dashboardTestState.value = ProviderDashboardState.Error(
                        error.message ?: context.getString(R.string.dashboard_exec_failed),
                        error.localizedMessage ?: ""
                    )
                }
        }
    }

    fun clearDashboardTestState() {
        _dashboardTestState.value = ProviderDashboardState.Idle
    }

    fun refreshProviderDashboard(
        provider: AIProviderConfig,
        context: DashboardContext? = null,
        force: Boolean = false
    ) {
        if (provider.dashboardScriptPath.isBlank()) {
            _providerDashboards.update { it - provider.id }
            return
        }
        val current = _providerDashboards.value[provider.id]
        if (!force && current is ProviderDashboardState.Success) return
        if (current is ProviderDashboardState.Loading) return

        // 已有 Success 时不切 Loading，避免 UI 高度塌缩再恢复（收起→展开闪烁）
        val showLoading = current !is ProviderDashboardState.Success
        viewModelScope.launch {
            if (showLoading) {
                _providerDashboards.update { it + (provider.id to ProviderDashboardState.Loading) }
            }
            providerDashboardRunner.runScript(provider, context = context)
                .onSuccess { result ->
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Success(result)) }
                }
                .onFailure { error ->
                    // 函数参数 context: DashboardContext 遮蔽了类的 ApplicationContext；
                    // 且此处位于 launch{} 内，this 已变为 CoroutineScope，必须用类标签限定
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Error(error.message ?: this@SettingsViewModel.context.getString(R.string.dashboard_query_failed))) }
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

    fun setDisableSafetyInterception(disabled: Boolean) {
        viewModelScope.launch { toolSafetySettingsRepository.setDisableSafetyInterception(disabled) }
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

/** 读取「提示词」页公告 md（zh 用中文版，其余英文版）；读取失败返回空串（空内容不弹窗）。 */
private fun loadPromptsAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/prompts-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** SHA-256 十六进制摘要，用于公告内容比对：内容更新则哈希变，自动重新弹出。 */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** 文件名的扩展名（小写，不含点）是否在 [exts] 白名单内。 */
private fun String.hasExtension(exts: Set<String>): Boolean {
    val dot = lastIndexOf('.')
    if (dot < 0 || dot == length - 1) return false
    return substring(dot + 1).lowercase() in exts
}
