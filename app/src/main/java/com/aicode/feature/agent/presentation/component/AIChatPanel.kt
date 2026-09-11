package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ImageViewerHost
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.readableContentMaxWidth
import com.aicode.core.ui.rememberImageViewerState
import com.aicode.core.ui.rememberViewerDecodeSpec
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ProviderBalanceState
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, NONE }

/** 悬浮层（横幅/面板/输入框）与最后一条消息的间距。 */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** 内容底部驱动校准的容差（px）：最后内容底部超出安全区超过该值才向下校准，避免亚像素抖动。 */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** 流式结束后尾巴保留时长（ms）：等落库消息接管，避免高度骤减导致视口上跳。 */
private const val STREAMING_TAIL_RETAIN_MS = 150L

/** 滚动到底部按钮直径（dp）。 */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** 连续新消息的入场错开间隔（ms）与总上限：一次插入很多卡片时不能让最后一张等好几秒。 */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** AI 收工后继续逐帧校准的时长（ms）：md 异步解析仍可能改高度，不能一停就收手。 */
private const val CALIBRATE_TAIL_MS = 1_200L

/** 展开/收起工具卡片后等待 item 高度稳定的最大帧数：diff 渲染、实时输出会分帧长高，
 *  过早读位置会按瞬时高度算出过大的滚动目标（中间位置长卡片展开被滚过头、标题出视口）。 */
private const val MAX_TOGGLE_SETTLE_FRAMES = 12

/** 消息未就绪时延迟多久才显示加载提示（ms）：本地读库很快，立即显示反而闪。 */
private const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * 长消息拆块渲染：
 *
 * 超长助手正文（长文档复述）如果塞成单条 LazyColumn item，撑到几屏深时手势命中区
 * 会在列表可见区之外，深处表格的横滑/长按复制/点击全部失效（原版深处交互失效的根因）。
 * 限高+内滚的单窗口方案又会让窗口与列表世界之间出现「接不上、独立一块」的断接。
 *
 * 拆块的思路是：分裂成多条「有界高度」的列表 item，每条都是普通气泡（思考只在首块、
 * 操作行只在末块、相邻块零间距无缝衔接），列表单一滚动轴——世界上下完全接通、深处
 * 交互（表 8+ 横滑/复制/点击）因每条 item 高度有界而全部恢复。
 *
 * 若正文长度不超过阈值，不拆块，与普通消息完全一致。
 */
private data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
)

/** 超过该长度（字符）的助手正文拆成多条有界 chunk。 */
private const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** 每条 chunk 的目标字符预算：正文按 markdown 块打包，单块超出预算（如巨大表格）时
 *  按行硬切兜底，保证任意 chunk 高度有界（≈0.5-0.7 屏）。 */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * 长正文拆块：以行为单位识别三类 markdown 块——代码围栏（整段）、表格（连续 | 行，
 * 整表保持完整）、普通段落（以空行分隔），然后按字符预算贪心打包成 chunk。
 * 超预算的单块按行拆分为多个块，宁可打断表格也不让某条 item 无界长高。
 */
private fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // 超预算单块（如巨型表格/巨型段落）按行切成预算内的小块，兜底保证有界。
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        if (block.length <= CHUNK_BUDGET_CHARS) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // 块间必须留空行：丢空行会让 markdown 语义粘连（段落 + 紧跟 --- 会被解析成 setext 标题，段落被夸成标题字号）。
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** 大屏右栏当前开的是终端 / Git 时，顶栏对应图标高亮。 */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // 工具卡片入场调度：只排本次浏览期间新追加到尾部的 TOOL 消息，逐个错开淡入。
    // 换会话时调度器重建，新会话的存量消息不入场。
    val entryScheduler = remember(currentSessionId) { MessageEntryScheduler() }
    val messageEntryDelays = remember(messages, entryScheduler) { entryScheduler.schedule(messages) }
    val currentSessionState by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val currentSession = currentSessionState
    val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_session_btn)
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()
    val targetRewindMessageId by viewModel.targetRewindMessageId.collectAsStateWithLifecycle()
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val defaultProviderId = settingsViewModel?.defaultModelProviderId?.collectAsStateWithLifecycle()?.value ?: ""
    val defaultModelName = settingsViewModel?.defaultModel?.collectAsStateWithLifecycle()?.value ?: ""
    // 未绑定会话回退：新会话默认模型（主页空会话中选择后记忆），未设置则为 null（UI 显示默认图标/顶栏模型名留空）。
    val defaultFallbackProvider = providers
        .find { it.id == defaultProviderId }
        ?.takeIf { it.hasUsableApiKey }
        ?.let { if (defaultModelName.isNotBlank() && defaultModelName in it.models) it.copy(selectedModel = defaultModelName) else it }
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // 与 workflow.resolveProviderConfig 保持一致：绑定 provider 须启用且已填 apiKey，否则回退默认模型
            providers.find { it.id == boundProviderId }
                ?.takeIf { it.hasUsableApiKey }
                // 绑定模型已被移出列表时绑定失效，回退默认模型（与 workflow.resolveProviderConfig 一致）
                ?.takeIf { boundModel.isNullOrBlank() || boundModel in it.models }
                ?.let {
                    if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
                } ?: defaultFallbackProvider
        } else {
            defaultFallbackProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    LaunchedEffect(inputDraft) {
        if (inputText != inputDraft) inputText = inputDraft
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    var messageForMenu by remember { mutableStateOf<AgentUIMessage?>(null) }
    var editingMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    val listState = rememberLazyListState()
    // 消息未就绪时的加载提示：本地读库通常几十毫秒，立刻显示反而闪一下，等一小会儿还没就绪才提示。
    var showMessagesLoading by remember(currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(currentSessionId, messagesReady) {
        if (messagesReady) {
            showMessagesLoading = false
            return@LaunchedEffect
        }
        delay(MESSAGES_LOADING_HINT_DELAY_MS)
        showMessagesLoading = true
    }
    // 贴底滚动留白：首帧测量前用兜底值（约输入框 + 间距），实测悬浮层高度后改为动态值，
    // 横幅/面板/输入框任何形态下最后一条消息都停在悬浮层上方不被遮挡。
    val inputBarBottomReserveDp = 156.dp
    var floatingLayerHeightPx by remember { mutableStateOf(0) }
    val inputBarReservePx = with(LocalDensity.current) {
        (if (floatingLayerHeightPx > 0) floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()
        else inputBarBottomReserveDp.toPx()).toInt()
    }
    val markdownCache = remember { MarkdownRenderCache() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    // 每轮任务的总耗时（用户发送 → 本轮 AI 收工），挂在轮末助手气泡下方
    val taskDurations = remember(messages, isBusy) {
        computeTaskDurations(messages, lastTurnFinished = !isBusy)
    }
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = modelMetadata[activeModel]
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerBalances by (settingsViewModel?.providerBalances?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentBalanceState = activeProvider?.let { providerBalances[it.id] } ?: ProviderBalanceState.Idle

    // 键盘弹出时收起面板，避免输入框被挤压
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // 余额面板展开状态：展开时叠加面板联动折叠，避免输入框被双重顶开
    var balanceExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderBalanceBar 仅在有余额脚本的 provider 下渲染（见 ChatInputBar）。无该栏时
    // balanceExpanded 可能残留 true：面板曾展开后随 provider 切换/脚本移除而卸载，LaunchedEffect
    // 上报链路中断无法复位，直接拿它做 forceCollapse 会把授权/询问/计划面板永久压成收起态。
    // 故叠加可见性，只在余额面板当前可见且展开时才折叠叠加面板。
    val balanceBarVisible = activeProvider?.balanceScriptPath?.isNotBlank() == true
    val balanceCollapseActive = balanceExpanded && balanceBarVisible

    fun buildDashboardContext(
        lastInput: Int = 0,
        lastOutput: Int = 0,
        lastCached: Int = 0,
        refreshReason: String = ""
    ): DashboardContext {
        val curSessionId = currentSessionId.orEmpty()
        val agentStateStr = when (agentState) {
            is AgentUIState.Idle -> "idle"
            is AgentUIState.Loading -> "loading"
            is AgentUIState.Streaming -> "streaming"
            is AgentUIState.Result -> "result"
            is AgentUIState.Error -> "error"
        }
        // 未显式传最近 token（切会话/任务完成/手动刷新等场景）时，从最后一条含 token 的助手消息兜底：
        // 否则面板的「最近输入/输出/缓存」会显示 0。llm 事件触发的刷新仍用事件带的实时值（更及时）。
        val lastTokenMsg = messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && (it.inputTokens > 0 || it.outputTokens > 0)
        }
        val effLastInput = if (lastInput > 0) lastInput else (lastTokenMsg?.inputTokens ?: 0)
        val effLastOutput = if (lastOutput > 0) lastOutput else (lastTokenMsg?.outputTokens ?: 0)
        val effLastCached = if (lastCached > 0) lastCached else (lastTokenMsg?.cachedInputTokens ?: 0)
        return DashboardContext(
            model = activeModel,
            // 面板脚本在容器内执行，工作区路径必须是容器视角（~/workspace）；宿主真实路径在容器内不存在
            workspacePath = WorkspacePathMapper.CONTAINER_ROOT,
            workspaceName = if (projectRoot.isNotBlank()) File(projectRoot).name else "",
            sessionId = curSessionId,
            lastInputTokens = effLastInput,
            lastOutputTokens = effLastOutput,
            lastCachedTokens = effLastCached,
            totalInputTokens = currentSession?.totalInputTokens ?: 0,
            totalOutputTokens = currentSession?.totalOutputTokens ?: 0,
            modelContextTokens = activeModelMetadata?.contextTokens ?: 0,
            modelMaxInputTokens = activeModelMetadata?.inputTokens ?: 0,
            modelMaxOutputTokens = activeModelMetadata?.outputTokens ?: 0,
            modelInputCostUsdPerM = activeModelMetadata?.inputCostUsdPerM ?: 0.0,
            modelOutputCostUsdPerM = activeModelMetadata?.outputCostUsdPerM ?: 0.0,
            modelCacheReadCostUsdPerM = activeModelMetadata?.cacheReadCostUsdPerM ?: 0.0,
            modelSupportsTools = activeModelMetadata?.supportsTools ?: false,
            modelSupportsVision = activeModelMetadata?.supportsVision ?: false,
            modelSupportsReasoning = activeModelMetadata?.supportsReasoning ?: false,
            messageCount = messages.size,
            agentState = agentStateStr,
            sessionMode = currentMode.name.lowercase(),
            reasoningEffort = reasoningEffort.apiValue,
            refreshReason = refreshReason
        )
    }

    // 首次进入、切换提供商、切换脚本路径或切换会话时拉取一次面板。
    // 会话切换时等 currentSession / messages 都落到新会话（sessionReady）再刷新：
    // 否则 buildDashboardContext 读到的是切换瞬间的旧会话快照，面板会显示旧数据或空 token。
    val sessionReady = currentSession?.id == currentSessionId && messagesReady
    LaunchedEffect(activeProvider?.id, activeProvider?.balanceScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.balanceScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderBalance(provider, context = context, force = true)
    }

    // 每次单次 LLM 请求返回时，立即带上最新 Token 与上下文实时刷新面板
    // 注意：LaunchedEffect(Unit) 只启动一次，collect 闭包会捕获首次组合时的变量快照，
    // 必须用 rememberUpdatedState 取最新值，否则会话累计 Token/消息数等永远停留在旧值。
    val latestBuildDashboardContext by rememberUpdatedState(
        { lastInput: Int, lastOutput: Int, lastCached: Int, refreshReason: String ->
            buildDashboardContext(lastInput, lastOutput, lastCached, refreshReason)
        }
    )
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    LaunchedEffect(Unit) {
        viewModel.llmCallEvents.collect { callEvent ->
            val provider = latestActiveProvider ?: return@collect
            if (provider.balanceScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderBalance(provider, context = context, force = true)
        }
    }

    // AI 一轮任务完成后刷新面板：让脚本拿到最终 agentState（result/idle/error），
    // 否则面板状态会一直停在「生成中」。busy→非busy 每轮只发生一次（Result 后再置 Idle 属
    // 非busy→非busy，不会重复触发），故这里触发的刷新恰好落在最终状态上。
    var lastAgentStateForPanel by remember { mutableStateOf<AgentUIState?>(null) }
    LaunchedEffect(agentState) {
        val prev = lastAgentStateForPanel
        lastAgentStateForPanel = agentState
        val wasBusy = prev is AgentUIState.Loading || prev is AgentUIState.Streaming
        val nowDone = agentState !is AgentUIState.Loading && agentState !is AgentUIState.Streaming
        if (wasBusy && nowDone) {
            val provider = latestActiveProvider ?: return@LaunchedEffect
            if (provider.balanceScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderBalance(provider, context = context, force = true)
        }
    }

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.id, provider.type, listOf(activeModel))
        }
    }

    // 主页模型选择面板复用 ModelSelectionSheet，能力 Tag 依赖全量模型元数据；
    // 仅 resolve 当前激活模型会导致未选过的模型无 Tag（设置页进入时已全量加载，主页补齐）。
    // providers 异步加载，首次组合时为空，需等非空后再解析（providers 后续变化也会触发，成本低有缓存）。
    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            settingsViewModel?.loadAllModelMetadata()
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            selected.forEach { uri ->
                runCatching {
                    copyUriToWorkspace(context, uri, projectRoot, includeImageData = images)
                }.onSuccess { uploaded ->
                    pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                    successCount += 1
                }.onFailure { error ->
                    failures += (error.message ?: uploadFallbackError(context))
                }
            }

            // 结果提示：全失败展示首个错误；有文件被上限截断或上传失败时用 partial 文案；全成功用 success 文案。
            val skipped = uris.size - selected.size
            when {
                successCount == 0 && failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
                skipped > 0 || failures.isNotEmpty() ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // 拍照：输出到 cache 临时文件（FileProvider 授权 uri），拍完按图片附件处理。
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.chat_camera_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    // 流式结束过渡：streamingText 清空后保留最后文本一小段（落库消息通常在此窗口内接管），
    // 避免尾巴 item 高度骤减导致视口被 clamp 上移、露出历史消息（结束瞬间“闪回”看到用户消息）。
    var tailStreamingText by remember { mutableStateOf<String?>(null) }
    // 会话切换时立即丢掉尾巴：保留窗口只用于同一会话内「流式结束 → 落库消息接管」的交接，
    // 跳会话留着会让新会话底部先闪一下上一个会话的流式气泡。必须声明在下面那个 effect 之前：
    // 两者同帧重启时按声明顺序执行，清空得先跑，否则会把新会话刚填上的尾巴又抹掉。
    LaunchedEffect(currentSessionId) {
        tailStreamingText = null
    }
    LaunchedEffect(streamingText) {
        val st = streamingText
        if (st != null && st.hasVisibleContent()) {
            tailStreamingText = st
        } else {
            delay(STREAMING_TAIL_RETAIN_MS)
            tailStreamingText = null
        }
    }

    // 打字机渲染进度：持有在 LazyColumn 之外，尾巴 item 滚出视口被 dispose 后进度不丢。
    // active = 上游仍在吐字；streamingText 清空后（active=false）打字机立即补全为完整
    // 文本，与上面保留期内尾巴无缝交接给落库消息。
    // 文本优先取 streamingText（组合外的 ViewModel 状态）：切页返回的首帧 tailStreamingText
    // 还没被上面的 LaunchedEffect 回填，此刻若传空文本，打字机会把恢复出的进度判成换轮从头重打。
    val typewriterRenderText = rememberTypewriterStreamingText(
        text = streamingText ?: tailStreamingText ?: "",
        active = streamingText != null,
        sessionKey = currentSessionId
    )
    // 思考过程同样走打字机：与回复文本共用同一速率自适应逻辑。
    // 正文开始输出（streamingText 非空）即视为思考结束：思考打字机立即补全，
    // 避免思考还没打完、正文已开始导致两者叠着慢慢打。
    val typewriterReasoningText = rememberTypewriterStreamingText(
        text = streamingReasoning ?: "",
        active = streamingReasoning != null && streamingText == null,
        sessionKey = currentSessionId
    )

    // 自动滚动跟随
    // 两个状态都必须 saveable：窄窗打开编辑器 / 终端 / Git / 设置都是全屏路由，聊天页整棵组合
    // 被 dispose。用普通 remember 的话返回时 positionedSession 归 null（当成换了会话重新贴底）、
    // followBottom 归 true（校准循环把恢复出的位置拉回底部），浏览历史的位置就丢了。
    var positionedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var followBottom by rememberSaveable { mutableStateOf(true) }

    // key 必须带上 inputBarReservePx：闭包捕获的是创建时的值，用无 key 的 remember 会让
    // 判定永远停在首帧的兜底留白（156dp）上，面板展开把悬浮层顶高后仍按旧安全区算。
    val isAtBottom by remember(inputBarReservePx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // 列表还没测量（首帧、或消息未就绪期间 LazyColumn 未挂载）时 layoutInfo 为空、
            // canScrollForward 恒 false。此时不能判「在底部」——从别的页面返回的首帧会因此
            // 恢复跟随，把 listState 刚恢复出的滚动位置拉回底部。
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            // 到底 = 最后内容底部停在安全区（悬浮层上沿）附近，而非视口底：
            // 判定更严格，避免用户拖走一小段后仍被判「在底部」而恢复跟随、立即被拉回。
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= safeBottom + AUTO_SCROLL_TOLERANCE_PX
        }
    }

    // 回底按钮的显示门槛：只用「不在底部」会让流式增长的那一两帧（校准循环还没把视口拉回）
    // 也算离底，按钮跟着闪。要求离底超过半个视口，用户真的翻上去看历史时才出现。
    // messagesReady / messages.isEmpty() 与 totalItemsCount 是同一枚硬币的两面：layoutInfo 是
    // 「最后一次布局 pass」的产物，LazyColumn 卸载（空会话 WelcomeState、加载占位）后不会自动
    // 清空——旧会话翻历史后切到空会话，残留布局会让按钮悬在新会话上。totalItemsCount 再拦截
    // 「新列表尚未按当前消息重测」（layout 开始前 layoutInfo 仍是旧会话的）那一帧。
    val isFarFromBottom by remember(inputBarReservePx, messagesReady, messages.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != messages.size + 1) return@derivedStateOf false
            if (lastVisible.index < layout.totalItemsCount - 1) return@derivedStateOf true
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            (lastVisible.offset + lastVisible.size) - safeBottom > layout.viewportEndOffset / 2
        }
    }

    // 用户开始拖拽：停止跟随。松手时若已到底则恢复跟随（旧逻辑）。
    // 额外：流式输出时内容持续增长，用户可能松手后又被「顶」离底部——
    // 用 snapshotFlow { isAtBottom } 持续监测，只要滑到底部就恢复跟随，
    // 满足「流式中滚到底部自动继续跟随」。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 松手后延迟判定是否恢复跟随：等惯性滚动稳定，
                    // 避免「松手在底部但惯性上滑」被立即拉回。
                    scope.launch {
                        delay(150)
                        followBottom = isAtBottom
                    }
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    // 贴底定位（发送消息、切换会话）：直接滚到锚点——scrollToItem 的目标 offset 使
    // 最后一项底部恰好停在悬浮层上方（contentPadding 预留 reserve，滚到该位置即列表
    // 可滚的最底部，无需依赖动画与逐步对齐）。
    val snapToBottom: suspend () -> Unit = {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            // 滚到列表可滚最底部：scrollToItem 的 offset 会被 clamp 到 maxScroll，
            // 最后一项底部恰好停在 contentPadding 底部（= 悬浮层上沿预留），无需手算项高度。
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    val sendMessage: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val attachments = pendingAttachments
            val modelSupportsVision = activeModelMetadata?.supportsVision == true
            val promptAttachments = if (modelSupportsVision) attachments.filterNot { it.isImage } else attachments
            val modelRequest = appendAttachmentsToRequest(context, text, promptAttachments)
            val images = if (modelSupportsVision) attachments.toAgentImages() else emptyList()
            // 统一走队列：AI 忙时入队（等本轮结束后自动发送下一条），空闲时直接发送。
            // 斜杠命令在 ViewModel 内（agent workflow 之前）分流执行，无需在此区分。
            viewModel.enqueueAgentRequest(
                request = text,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments.toAgentAttachments()
            )
            inputText = ""
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom()
            }
        }
    }

    // 切换会话：定位到最新内容并恢复跟随（之后由校准循环持续跟随）。
    LaunchedEffect(currentSessionId, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        if (positionedSession != currentSessionId) {
            // 等一帧让 LazyColumn 按新会话完成重组，再滚到锚点（maxScroll）。
            withFrameNanos { }
            snapToBottom()
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // 锚点式常驻校准循环：锚点 = 最后内容底部恰好停在悬浮层（输入框）上沿。
    // scrollToItem(最后一项, Int.MAX_VALUE) 会被 LazyColumn clamp 到可滚的最底部
    // （contentPadding 底部预留 reserve 保证），即最后一项底部停在悬浮层上沿，
    // 数学上任何时刻都成立——消息足够时，最后一条消息永不落入输入框之下，
    // 且不依赖“最后可见项 == 最后一项”的高度假设（高度跳变时也不会算错目标）。
    // 每帧检查最后可见项：最后内容被增长推下（底部超安全区）或有内容被推出视口下方
    // （最后可见项不是最后一项，即跟丢）时，滚回锚点；md 异步解析的高度跳变也会在
    // 下一帧被检测到，不存在信号与渲染错位。
    // 只向下校准：内容变矮（流式结束、折叠）时保持当前位置，避免「往回滚」与拉锯。
    // reserve 经 State 传递：下面这个 lambda 只创建一次，直接捕获局部 Int 会一直用首帧的兜底值。
    val reservePxState = rememberUpdatedState(inputBarReservePx)
    val busyState = rememberUpdatedState(isBusy)
    val calibrateToAnchor: suspend () -> Unit = remember(listState) {
        {
            // 无向下滚动空间（内容不满屏或已滚到锚点）：最后内容必然在安全区上方，无需校准。
            if (followBottom && listState.canScrollForward) {
                val layout = listState.layoutInfo
                val lastIndex = layout.totalItemsCount - 1
                if (lastIndex >= 0) {
                    val lastVisible = layout.visibleItemsInfo.lastOrNull()
                    val safeBottom = layout.viewportEndOffset - reservePxState.value
                    // 最后一项被推出视口下方（跟丢）或最后内容底部越过安全区：滚回锚点。
                    // 用户在别处浏览时 followBottom 已为 false，不会走到这里。
                    val lost = lastVisible == null || lastVisible.index < lastIndex
                    val pushedDown = lastVisible != null &&
                        lastVisible.offset + lastVisible.size > safeBottom + AUTO_SCROLL_TOLERANCE_PX
                    if (lost || pushedDown) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
        }
    }

    // 只在「跟随中且内容可能还在动」时逐帧校准。原来是无条件 while(true)，followBottom
    // 为 false 也只 continue、帧回调照旧注册，等于让主线程全程每帧醒一次（空闲也在耗电）。
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { followBottom && (busyState.value || listState.isScrollInProgress) }
            .collectLatest { active ->
                if (active) {
                    while (true) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                } else {
                    // 收工那一刻内容未必已稳定（md 异步解析往往落在后面），再兜一小段再收手。
                    val deadline = System.nanoTime() + CALIBRATE_TAIL_MS * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                }
            }
    }

    // 内容变化信号旁路：文本/思考/消息条数变化时立即校准一次，不等下一帧——
    // 与常驻校准循环互为补充，覆盖「无动画帧」的间隙，杜绝跟丢窗口。
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow {
            Triple(streamingText?.length, streamingReasoning?.length, messages.size)
        }.collect { calibrateToAnchor() }
    }

    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, messagesReady, messagesState.hasMore, messagesState.isLoadingMore) {
        if (messagesReady && firstVisibleItemIndex <= 3 && messagesState.hasMore && !messagesState.isLoadingMore) {
            viewModel.loadMoreMessages()
        }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH

    val markdownImageTransformer = remember(viewModel.fileAccess) {
        MarkdownImageTransformer(viewModel.fileAccess)
    }
    val imageViewerState = rememberImageViewerState()
    val viewerDecodeSpec = rememberViewerDecodeSpec()
    val chatImageLoad = remember(viewModel.fileAccess, viewerDecodeSpec) {
        chatImageLoader(viewModel.fileAccess, viewerDecodeSpec.maxEdge, viewerDecodeSpec.maxPixels)
    }

    CompositionLocalProvider(
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState
    ) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatHeader(
                sessionTitle = sessionTitle,
                modelName = activeProvider?.effectiveModel,
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    onOpenDrawer()
                },
                onNewChat = { viewModel.newSession() },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        // 大屏正文列限宽居中：铺满整个平板宽度会让一行文字过长、气泡横跨全屏，读起来很累。
        // 窄窗下 readableContentMaxWidth() 返回 Dp.Unspecified，widthIn 不产生任何约束。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = readableContentMaxWidth())
                .fillMaxSize()
        ) {
            // 内容层：消息列表延伸到屏幕底部，输入框悬浮其上，滚动时卡片可滑入输入框后面
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (!messagesReady) {
                    // 远程模式连接未就绪时显示连接状态占位，避免空白或旧工作区记录闪烁
                    if (isRemote && connectionState != null && connectionState != com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    } else if (showMessagesLoading) {
                        // 本地模式读库偏慢时的占位：以前这里什么都不画，切会话会先闪一下空白。
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(modifier = Modifier.fillMaxSize())
                } else {
                    // 拆块：超长助手消息展开成多条有界 item（单条滚动轴、外观连续的气泡），
                    // 普通消息保持 1:1。chatItems 的顺序即 LazyColumn item 顺序（尾随尾巴 item）。
                    val chatItems = remember(messages) {
                        messages.map { message ->
                            val canSplit = message.role == MessageRole.ASSISTANT &&
                                !message.isCompactionMarker &&
                                !message.isContextSummary &&
                                !message.isCompactionFailure &&
                                !message.isBackgroundNotification &&
                                message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
                            if (!canSplit) {
                                listOf(
                                    ChatRenderItem(
                                        message = message,
                                        key = message.id,
                                        contentType = message.role.name,
                                    )
                                )
                            } else {
                                val slices = splitLongContent(message.content)
                                if (slices.size <= 1) {
                                    // 只拆出一块（如无空行的超长单段）：等同普通消息，避免单块走分块描边。
                                    listOf(
                                        ChatRenderItem(
                                            message = message,
                                            key = message.id,
                                            contentType = message.role.name,
                                        )
                                    )
                                } else {
                                    slices.mapIndexed { idx, slice ->
                                        ChatRenderItem(
                                            message = message,
                                            key = "${message.id}#chunk$idx",
                                            contentType = "assistant-chunk",
                                            slice = slice,
                                            isChunkHeader = idx == 0,
                                            isChunkFooter = idx == slices.lastIndex,
                                        )
                                    }
                                }
                            }
                        }.flatten()
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            top = Spacing.md,
                            bottom = with(LocalDensity.current) { inputBarReservePx.toDp() }
                        )
                    ) {
                        itemsIndexed(chatItems, key = { _, it -> it.key }, contentType = { _, it -> it.contentType }) { index, item ->
                            val message = item.message
                            val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                            AgentMessageItem(
                                message = message,
                                liveOutput = live,
                                markdownCache = markdownCache,
                                contentSlice = item.slice,
                                isChunkHeader = item.isChunkHeader,
                                isChunkFooter = item.isChunkFooter,
                                onRewindClick = { viewModel.openRewindMenu(it) },
                                onMoreClick = { messageForMenu = it },
                                onToolToggle = {
                                    // 用户主动展开/收起工具卡片：先暂停自动跟随，避免校准循环把视口拉走造成跳动；
                                    // 用户滚回底部（isAtBottom 监测）时自动恢复跟随。
                                    followBottom = false
                                    // 折叠后卡片可能整体缩出视口上方（长卡片双击折叠）：等一帧按折叠后的布局判断，
                                    // 仅当卡片完全不可见时才滚回顶部让标题可见；仍可见（含贴底）时不做任何主动滚动，
                                    // 避免用折叠前的旧 offset 定位导致「收起时跳动、位置不对」。
                                    // 展开后卡片底部可能被悬浮层（输入框）遮挡：滚动让卡片底部停在悬浮层上沿，
                                    // 与消息气泡的贴底跟随统一。
                                    scope.launch {
                                        // 展开/收起后 item 高度可能连续变几帧（diff 渲染、实时输出逐行增长），
                                        // 等高度稳定（>0 且连续两帧相同）再读位置：既避免按瞬时高度算出过大的滚动目标，
                                        // 也避免重组延迟时把折叠前的旧高度误判成「稳定」提前退出。
                                        var prevSize = -1
                                        var stableFrames = 0
                                        for (i in 0 until MAX_TOGGLE_SETTLE_FRAMES) {
                                            withFrameNanos { }
                                            val curSize = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == index }?.size ?: -1
                                            if (curSize > 0 && curSize == prevSize) {
                                                stableFrames++
                                                if (stableFrames >= 2) break
                                            } else {
                                                stableFrames = 0
                                            }
                                            prevSize = curSize
                                        }
                                        val layout = listState.layoutInfo
                                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                                        if (item == null || item.offset + item.size <= 0) {
                                            // animateScrollToItem 对超一屏的大 item 按估算高度算滚动量，终点会系统性
                                            // 滚过头（大卡片过头多、小卡片精准）；统一用无估算参与的瞬移落位。
                                            listState.scrollToItem(index)
                                        } else {
                                            val safeBottom = layout.viewportEndOffset - inputBarReservePx
                                            if (item.offset + item.size > safeBottom + AUTO_SCROLL_TOLERANCE_PX) {
                                                // 目标 = 让卡片底部停在 safeBottom 的顶部位置，但夹在 [0, 当前顶部] 之间：
                                                // 只向上滚、顶部永不越过视口顶（卡片比可视区还高时对齐到顶部 0），
                                                // 避免中间位置的长卡片被一次性滚过头、标题滚出屏幕。
                                                val target = (safeBottom - item.size)
                                                    .coerceIn(0, item.offset.coerceAtLeast(0))
                                                listState.scrollToItem(index, target)
                                                // 兜底：内容高度在滚动后仍可能微变（diff 渲染、实时输出），等布局稳定后
                                                // 若用户没在拖列表，再精确吸一次位到约束目标（底部尽量压到 safeBottom、
                                                // 顶部不越视口顶），保证最终位置以实测布局为准。
                                                var postSize = -1
                                                var postStable = 0
                                                for (i in 0 until MAX_TOGGLE_SETTLE_FRAMES) {
                                                    withFrameNanos { }
                                                    val cur = listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.index == index }?.size ?: -1
                                                    if (cur > 0 && cur == postSize) {
                                                        postStable++
                                                        if (postStable >= 2) break
                                                    } else {
                                                        postStable = 0
                                                    }
                                                    postSize = cur
                                                }
                                                val after = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == index }
                                                if (after != null && !listState.isScrollInProgress) {
                                                    val corrected = (safeBottom - after.size)
                                                        .coerceIn(0, after.offset.coerceAtLeast(0))
                                                    if (kotlin.math.abs(corrected - after.offset) > AUTO_SCROLL_TOLERANCE_PX) {
                                                        listState.scrollToItem(index, corrected)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                taskDurationMs = taskDurations[message.id],
                                entryDelayMs = messageEntryDelays[message.id]
                            )
                        }
                        val reasoning = streamingReasoning
                        val showReasoning = reasoning != null && reasoning.isNotEmpty()
                        val streaming = tailStreamingText
                        val showStreaming = streaming != null && streaming.hasVisibleContent()
                        val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && runningTool.isEmpty() && pendingPermission == null && pendingQuestion == null
                        val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val tailKind = when {
                            showStreaming -> TailKind.STREAMING
                            isCompacting -> TailKind.COMPACTING
                            showRetrying -> TailKind.RETRYING
                            showThinking -> TailKind.THINKING
                            else -> TailKind.NONE
                        }
                        // 尾巴 item：思考气泡与状态尾巴合并进同一个永久挂载的 item，二者都不按状态增删。
                        // 思考开始/结束或流式开始/结束若让 totalItemsCount 突增突减，LazyColumn 会把
                        // firstVisibleItemIndex 向下 clamp → 视口上跳（旧症状2根因）。item 数量恒为 1，
                        // anchor 不会被 clamp：showReasoning 时渲染思考气泡（内部自带折叠），否则为空；
                        // tailKind 为 NONE 时尾巴为空 Box（0 高度）。流结束落库后跟随 effect 会把新消息贴底。
                        item(key = "__active__", contentType = "tail") {
                            Column {
                                if (showReasoning) {
                                    // 流式实时：短文本默认展开边想边看，过长（超 REASONING_COLLAPSE_LINE_LIMIT）时由气泡内部自动折叠，不刷屏
                                    ReasoningBubble(text = typewriterReasoningText, initiallyExpanded = true, cache = markdownCache, showTimer = true, preRendered = true, sessionKey = currentSessionId)
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble()
                                    TailKind.STREAMING -> StreamingBubble(text = typewriterRenderText, cache = markdownCache)
                                    TailKind.COMPACTING -> CompactionProgressBubble()
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.NONE -> Box(Modifier)
                                }
                            }
                        }
                    }
                }
            }
            } // 内容层结束

            // 悬浮层：错误气泡 / 面板 / 输入框（蒙版在 ChatInputBar 内部，跟随键盘上移）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 实测悬浮层实际高度作为滚动留白，横幅/面板/输入框任何形态都不遮挡最后一条
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            StatusBanner(state = agentState)

            // 三个面板都用「最后一次非空值」渲染：退出动画期间源状态已置空，直接在 content 里
            // 解引用会淡出一个空面板，看起来是瞬间消失而不是淡出。位移也一并补上——只淡入的话
            // 面板在 Column 里占的高度是瞬间生效的，输入框会先跳一格再看到面板浮现。
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            AnimatedVisibility(
                visible = pendingPermission != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = balanceCollapseActive
                    )
                }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = balanceCollapseActive
                    )
                }
            }

            val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
            val planForPanel = rememberLastNonNull(planApproval)
            AnimatedVisibility(
                visible = planApproval != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() },
                        forceCollapse = balanceCollapseActive
                    )
                }
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it; viewModel.updateInputDraft(it) },
                onSend = sendMessage,
                onStop = { viewModel.stopAgent() },
                isBusy = isBusy,
                workspaceViewModel = workspaceViewModel,
                hasRunningSessions = { viewModel.hasRunningSessionsInCurrentWorkspace() },
                onSwitchWorkspaceConfirmed = { viewModel.stopAllAndCloseTerminal() },
                activeProvider = activeProvider,
                providers = providers,
                modelMetadata = modelMetadata,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = ::takePhoto,
                slashCommands = viewModel.slashCommands,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                balanceState = currentBalanceState,
                forceCollapseBalance = pendingPermission != null || pendingQuestion != null || planApproval != null || imeVisible,
                onBalanceExpandedChange = { balanceExpanded = it },
                onRefreshBalance = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderBalance(it, context = context, force = true)
                    }
                },
                onRefreshBalanceByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderBalance(it, context = context, force = true)
                    }
                },
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.contextTokens ?: 0
                    if (contextLimit > 0) {
                        sessionLastInputTokens.toFloat() / contextLimit
                    } else 0f
                },
                isScrolling = listState.isScrollInProgress,
                modifier = Modifier.fillMaxWidth()
            )
            } // 悬浮层结束

            // 滚动到底部按钮：悬浮在输入框右上角上方（悬浮层高度 + 间距定位），离底超过半屏时
            // 显示，不看滚动方向——往上翻历史后停住恰恰是最需要一键回底的时刻；滚动时跟随输入框淡出。
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
                    .graphicsLayer { alpha = if (listState.isScrollInProgress) 0.4f else 1f },
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ScrollToBottomButton(
                    onClick = {
                        followBottom = true
                        scope.launch { snapToBottom() }
                    }
                )
            }

            targetRewindMessageId?.let { targetId ->
                val targetMsg = messages.find { it.id == targetId }
                RewindOptionsBottomSheet(
                    promptSnippet = targetMsg?.content ?: "",
                    onOptionSelected = { option ->
                        viewModel.executeRewindOption(targetId, option) { text, attachments ->
                            inputText = text
                            pendingAttachments = attachments.map { it.toPendingAttachment() }
                        }
                    },
                    onDismissRequest = { viewModel.dismissRewindMenu() }
                )
            }

            messageForMenu?.let { message ->
                val clipboard = LocalClipboard.current
                val copyScope = rememberCoroutineScope()
                MessageActionsBottomSheet(
                    message = message,
                    onDismiss = { messageForMenu = null },
                    onEditClick = { editingMessage = message },
                    onCopyClick = {
                        copyScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content)))
                        }
                    },
                    onDeleteClick = { viewModel.deleteMessage(message.id) }
                )
            }

            editingMessage?.let { message ->
                EditMessageDialog(
                    initialText = message.content,
                    onDismiss = { editingMessage = null },
                    onConfirm = { newContent ->
                        viewModel.updateMessageContent(message.id, newContent)
                        editingMessage = null
                    }
                )
            }
        }
        }
    }

        // Dialog 是独立 window、不占父布局尺寸，挂在 Scaffold 之后即可覆盖整屏 ——
        // 平板双栏下不会只盖住聊天列，也不会被 MainActivity 画在最上层的全局背景水印压住。
        ImageViewerHost(state = imageViewerState, load = chatImageLoad)
    }
}

/**
 * 记住最后一个非空值，供 [AnimatedVisibility] 的退出动画继续渲染旧内容。
 *
 * 用普通对象而不是 [androidx.compose.runtime.MutableState] 持有：这里只需要跨重组留住上一个值，
 * 不需要它自己触发重组（源值变化本身就会重组读取点），进快照系统反而会多引发一次无效重组。
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * 工具卡片入场动画的调度器：决定哪些消息该播入场、以及各自错开多久。
 *
 * 判据是「本调度器存续期间新追加到尾部」，而不是「timestamp 距今 N 秒内」——
 * 后者在切页返回时会把仍在时间窗内的那批消息再判成新消息，动画重播一遍。
 * 首次调用时列表里已有的消息一律记为存量；向上翻页加载进来的历史比已见最大时间戳更旧，
 * 同样不入场。会话切换时整个调度器重建，新会话的存量消息也不入场。
 */
private class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** 返回「消息 id → 入场延迟（ms）」；不在表内的消息直接显示。 */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}

/** 滚动到底部按钮：圆形，悬浮在输入框右上角，不在底部时显示。 */
@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(SCROLL_TO_BOTTOM_BTN_SIZE.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = FeatherIcons.ArrowDown,
                contentDescription = stringResource(R.string.common_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

