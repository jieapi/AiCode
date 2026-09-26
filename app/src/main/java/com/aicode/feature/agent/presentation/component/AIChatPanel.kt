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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

/** 悬浮层（横幅/面板/输入框）与最后一条消息的间距。 */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** 内容底部驱动校准的容差（px）：最后内容底部超出安全区超过该值才向下校准，避免亚像素抖动。 */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** 流式结束后尾巴保留时长（ms）：等落库消息接管，避免高度骤减导致视口上跳。 */

/** 滚动到底部按钮直径（dp）。 */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** 连续新消息的入场错开间隔（ms）与总上限：一次插入很多卡片时不能让最后一张等好几秒。 */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** AI 收工后继续逐帧校准的时长（ms）：md 异步解析仍可能改高度，不能一停就收手。 */
private const val CALIBRATE_TAIL_MS = 1_200L

/**
 * 该 item 是否是「当前展开的工具分组」的成员行（用于加一级缩进）。
 *
 * 成员 item 自身不带所属分组信息（key 就是消息 id），但一个展开的分组，其成员一定是紧跟分组头的一串
 * 连续 TOOL 行；故从本项往前**只走连续的 TOOL 行**，遇到的第一个非 TOOL 行就是分组头，它上面的
 * [ChatRenderItem.groupExpanded] 已经是「手动选择优先」的终值。中途一旦遇到非 TOOL 行（如助手正文）
 * 立即停止并判定「不属于任何分组」——否则分组之后被打断的孤立 TOOL 行会错误继承前一个分组的缩进。
 *
 * @param index 本项在 [chatItems] 中的下标
 * @param isToolRow 本项是否为 TOOL 消息
 */
internal fun isExpandedGroupMember(
    chatItems: List<ChatRenderItem>,
    index: Int,
    isToolRow: Boolean,
): Boolean {
    if (!isToolRow || index <= 0 || index >= chatItems.size) return false
    if (chatItems[index].toolGroup != null) return false
    for (k in index - 1 downTo 0) {
        val candidate = chatItems[k]
        if (candidate.toolGroup != null) return candidate.groupExpanded
        // 回退路径只允许是连续的 TOOL 行；遇到别的内容说明本行不在任何分组的成员序列里
        if (candidate.message.role != MessageRole.TOOL) return false
    }
    return false
}

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
 *
 * internal（而非 private）是为了让 [buildChatItems] 的用例能断言渲染项，见 ToolGroupExpansionTest。
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /**
     * 非空表示这是一条「连续工具调用」分组头 item。成员各自仍是独立 item（仅在该组展开时生成），
     * 因此无论展开与否，任何一条 item 的高度都有界——这是深处交互（表格横滑、长按复制）不失效的前提。
     */
    val toolGroup: List<AgentUIMessage>? = null,
    /** 分组当前是否展开（已含用户手动覆盖的结果）。 */
    val groupExpanded: Boolean = true,
)

/** 工具调用分组头 item 的 contentType。 */
private const val TOOL_GROUP_CONTENT_TYPE = "tool-group"

/**
 * 分组展开时成员行的左缩进：分组头与成员行行首元素原本左右完全对齐（同一格 16dp 图标 + 同一间距），
 * 看不出从属关系；给成员整行缩进一级，分组头是父、成员行是子，层级一眼可辨。
 * 固定取 16dp（不用 [Spacing.lg]：紧凑密度下它只有 14dp，缩进会随屏幕形态忽大忽小）。
 */
private val ToolGroupMemberPadding = PaddingValues(start = 16.dp)

/**
 * 该消息是否参与「连续工具调用」分组。
 *
 * 上下文压缩失败/摘要、后台通知这些 TOOL 消息各有专用渲染分支（见 [AgentMessageItem] 的早退），
 * 混进分组会被当成普通工具行，故一并排除；普通消息（用户/助手）天然打断分组。
 *
 * **带附件的工具（`sendFile` / `generateImage`）也不分组**：它们产出的文件行就是结果本身，
 * 折进「N 次工具调用」后随分组默认收起，等于把发来的文件藏起来；留作顶层 item 才常显。
 */
private fun AgentUIMessage.isGroupableTool(): Boolean =
    role == MessageRole.TOOL && !isCompactionFailure && !isContextSummary &&
        !isCompactionMarker && !isBackgroundNotification && attachments.isEmpty()

/** 分组标识：取组内首条消息 id，保证一批工具调用在追加过程中 item key 稳定（不会重建导致视口跳动）。 */
private fun toolGroupKey(first: AgentUIMessage): String = "toolgroup:${first.id}"

/**
 * 该助手消息是否会渲染出「气泡下方的元信息行」——即能不能承接那排「复制 / 更多」按钮。
 *
 * 压缩标记、上下文摘要、压缩失败、后台通知条在 [AgentMessageItem] 里各有专用渲染分支并提前
 * return，不产生这一行；纯思考无正文的助手消息同理（没有正文可复制）。把「最新一条」的按钮
 * 挂到它们身上，整段会话就一个按钮都不剩。
 */
private fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * 单条消息（非工具分组）在一次渲染中占据的 item：
 * 超长助手正文拆成多条有界 chunk，其余消息 1:1。
 */
private fun messageRenderItems(message: AgentUIMessage): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // 只拆出一块（如无空行的超长单段）：等同普通消息。
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    return slices.mapIndexed { idx, slice ->
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

/**
 * 消息列表 → LazyColumn item 列表。
 *
 * 两件事：长文拆块（原逻辑）与**连续工具调用分组**。分组规则对齐参考图：
 * 连续 TOOL 消息折成一条「N 次工具调用」头行，成员行只在展开时生成。
 *
 * 分组**默认收起**——工具调用一律不自动展开（运行中也不弹开），要不要看细节由用户点开；
 * [groupOverrides] 是宿主持久化的手动选择（key = [toolGroupKey]），只认它，没有记录即收起。
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    groupOverrides: Map<String, Boolean>,
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (!message.isGroupableTool()) {
            items += messageRenderItems(message)
            i++
            continue
        }
        var j = i
        while (j < messages.size && messages[j].isGroupableTool()) j++
        val members = messages.subList(i, j).toList()
        val key = toolGroupKey(members.first())
        val expanded = groupOverrides[key] == true
        items += ChatRenderItem(
            message = members.first(),
            key = key,
            contentType = TOOL_GROUP_CONTENT_TYPE,
            toolGroup = members,
            groupExpanded = expanded,
        )
        if (expanded) {
            members.forEach { member ->
                items += ChatRenderItem(
                    message = member,
                    key = member.id,
                    contentType = member.role.name,
                )
            }
        }
        i = j
    }
    return items
}

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
    onNavigateToBrowser: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** 大屏右栏当前开的是终端 / Git 时，顶栏对应图标高亮。 */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    onboardingStep: OnboardingStep? = null,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onDismissModelSheetInOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val pendingScroll by viewModel.pendingScrollMessage.collectAsStateWithLifecycle()

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
    val keySwitchState by viewModel.keySwitchState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val preparingTool by viewModel.preparingTool.collectAsStateWithLifecycle()
    // 等待模型或工具执行时的状态文案：
    // 1. 若当前有正在执行的工具（runningTool）或模型正在准备的工具（preparingTool，工具名先到参数还在流式），
    //    直接说清在做什么（「正在读取文件」、「正在编辑文件」等，见 toolRunningLabelRes）；
    // 2. 否则（如等待模型首字吐出、纯思考间隙）兜底显示「正在思考」。
    val activeToolName = runningTool.lastOrNull()?.toolName?.takeIf { it.isNotBlank() } ?: preparingTool
    val thinkingLabel = activeToolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    val currentTodoItems by viewModel.currentSessionTodoItems.collectAsStateWithLifecycle()
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
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    LaunchedEffect(inputDraft) {
        if (inputText != inputDraft) inputText = inputDraft
    }
    // 输入 "/" 打开命令菜单时重扫技能，反映磁盘上技能的增删改。
    LaunchedEffect(inputText) {
        if (inputText == "/") viewModel.refreshSlashCommands()
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    var uploadingCount by remember { mutableStateOf(0) }
    // 串行化附件上传：两次上传并发时 pendingAttachments 的读-改-写会互相覆盖，导致先选的附件预览丢失。
    val attachmentUploadMutex = remember { Mutex() }
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
    // 工具调用（分组头 / 单条工具行）的手动展开态：放在 ViewModel 里，切页、滚出视口回收后仍保留。
    // key = 分组 key（`toolgroup:<组内首条消息 id>`）或单条消息 id。
    val toolExpansionOverrides = viewModel.toolExpansionOverrides
    // 快照：读一次 map 让组合订阅到它的变化，同时给下面的 remember 一个可比较的 key。
    val toolGroupOverrideSnapshot = toolExpansionOverrides.toMap()
    // 正在执行的工具 id 集合：只让集合内容参与 remember key，避免实时输出逐字刷新导致整表重建。
    val runningToolIds by remember { derivedStateOf { runningTool.mapTo(HashSet()) { it.messageId } } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    // 拆块 + 工具分组：超长助手消息展开成多条有界 item（单条滚动轴、外观连续），
    // 连续的工具调用折成一个「N 次工具调用」分组；chatItems 的顺序即 LazyColumn item 顺序。
    // 提到这里（而不是 LazyColumn 分支内）是因为 isFarFromBottom 的「布局是否对应当前消息」判定
    // 需要它：分组会让 item 数 ≠ 消息数 + 1，不能再拿消息数当期望值。
    val chatItems = remember(messages, toolGroupOverrideSnapshot) {
        buildChatItems(
            messages = messages,
            groupOverrides = toolGroupOverrideSnapshot,
        )
    }
    // 每轮任务的总耗时（用户发送 → 本轮 AI 收工）与 token 合计，都只挂在轮末助手气泡下方
    val taskDurations = remember(messages, isBusy) {
        computeTaskDurations(messages, lastTurnFinished = !isBusy)
    }
    val turnUsages = remember(messages, isBusy) {
        computeTurnUsage(messages, lastTurnFinished = !isBusy)
    }
    // 「复制 / 更多」只挂在整段会话最新的一条助手消息下面（工具消息不算），
    // 否则每条回复都吊一排小按钮，既吵又打断文档流的阅读。用户消息不受此限，逐条常驻（见 AgentMessageItem）。
    // **本轮收工前不挂**：一轮任务里 AI 常常分好几步（工具调用后继续生成），轮内就把按钮挂到
    // 当前的"最后一条"上，下一步一到按钮又跳到下一条，看起来像按钮在追着消息跑；判据与
    // computeTaskDurations / computeTurnUsage 的 lastTurnFinished 一致（忙 = 本轮还没收工）。
    val lastActionableMessageId = remember(messages, isBusy) {
        if (isBusy) null else messages.lastOrNull { it.rendersActionRow() }?.id
    }
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = activeProvider?.let { modelMetadata[modelMetadataKey(it.id, activeModel)] }
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerDashboards by (settingsViewModel?.providerDashboards?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentDashboardState = activeProvider?.let { providerDashboards[it.id] } ?: ProviderDashboardState.Idle

    // 键盘弹出时收起面板，避免输入框被挤压
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // 余额/待办面板展开状态：展开时叠加面板联动折叠，避免输入框被双重顶开
    var dashboardExpanded by rememberSaveable { mutableStateOf(false) }
    var todoExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderDashboardBar 仅在有面板脚本的 provider 下渲染（见 ChatInputBar）。无该栏时
    // dashboardExpanded 可能残留 true：面板曾展开后随 provider 切换/脚本移除而卸载，LaunchedEffect
    // 上报链路中断无法复位，直接拿它做 forceCollapse 会把授权/询问/计划面板永久压成收起态。
    // 故叠加可见性，只在面板当前可见且展开时才折叠叠加面板。
    val dashboardBarVisible = activeProvider?.dashboardScriptPath?.isNotBlank() == true
    val dashboardCollapseActive = (dashboardExpanded && dashboardBarVisible) || (todoExpanded && currentTodoItems.isNotEmpty())

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
    LaunchedEffect(activeProvider?.id, activeProvider?.dashboardScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
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
            if (provider.dashboardScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
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
            if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
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
            // 串行化：并发上传会让 pendingAttachments 的读-改-写互相覆盖，先选的附件预览丢失。
            attachmentUploadMutex.withLock {
                uploadingCount = selected.size
                try {
                    selected.forEach { uri ->
                        runCatching {
                            copyUriToWorkspace(context, uri, viewModel.fileAccess, includeImageData = images)
                        }.onSuccess { uploaded ->
                            pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                            successCount += 1
                        }.onFailure { error ->
                            failures += (error.message ?: uploadFallbackError(context))
                        }
                    }
                } finally {
                    uploadingCount = 0
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

    // 缓冲流式文本与思考过程：流式期间跟随 streamingText / streamingReasoning；流式刚结束而数据库尚未完成派发期间（messages 末尾仍是 USER/TOOL 消息），
    // 持续保留本轮完整文本与思考，撑住底部气泡，彻底杜绝「字快打完了突然整段蒸发消失（空白闪回）」或「思考块突然消失又冒出」；
    // 一旦 messages 列表末尾正式接纳了本轮助手消息，立即清空让位。
    var retainedStreamingText by remember { mutableStateOf<String?>(null) }
    var retainedStreamingReasoning by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentSessionId) {
        retainedStreamingText = null
        retainedStreamingReasoning = null
    }

    val lastMsg = messages.lastOrNull()
    // 本轮助手消息是否已经在消息列表中正式就位渲染：
    // 只有末尾消息是 ASSISTANT 且正文与思考前缀吻合，才代表本轮输出已落库进 messages 列表
    val isAssistantSettled = lastMsg?.role == MessageRole.ASSISTANT && run {
        val currentText = streamingText ?: retainedStreamingText
        val currentReasoning = streamingReasoning ?: retainedStreamingReasoning
        val textSettled = if (currentText.isNullOrBlank()) {
            true
        } else {
            val prefix = currentText.trimStart().take(20)
            prefix.isEmpty() || lastMsg.content.trimStart().startsWith(prefix)
        }
        val reasoningSettled = if (currentReasoning.isNullOrBlank()) {
            true
        } else {
            val prefix = currentReasoning.trimStart().take(20)
            prefix.isEmpty() || (lastMsg.reasoning?.trimStart()?.startsWith(prefix) == true)
        }
        textSettled && reasoningSettled
    }

    LaunchedEffect(streamingText, isAssistantSettled) {
        val st = streamingText
        if (st != null && st.hasVisibleContent()) {
            retainedStreamingText = st
        } else if (isAssistantSettled) {
            retainedStreamingText = null
        }
    }

    LaunchedEffect(streamingReasoning, isAssistantSettled) {
        val sr = streamingReasoning
        if (sr != null && sr.hasVisibleContent()) {
            retainedStreamingReasoning = sr
        } else if (isAssistantSettled) {
            retainedStreamingReasoning = null
        }
    }

    val displayStreamingText = if (isAssistantSettled) null else (streamingText ?: retainedStreamingText)
    val showStreaming = displayStreamingText?.hasVisibleContent() == true

    val displayStreamingReasoning = if (isAssistantSettled) null else (streamingReasoning ?: retainedStreamingReasoning)
    val showReasoning = displayStreamingReasoning?.hasVisibleContent() == true

    // 打字机渲染进度：持有在 LazyColumn 之外，尾巴 item 滚出视口被 dispose 后进度不丢。
    val typewriter = rememberTypewriterStreamingText(
        text = displayStreamingText ?: "",
        active = streamingText != null,
        sessionKey = currentSessionId
    )
    val typewriterRenderText = typewriter.text
    // 思考过程同样走打字机：与回复文本共用同一速率自适应逻辑。
    // 正文开始输出（streamingText 非空）即视为思考结束：思考打字机立即收尾，
    // 避免思考还没打完、正文已开始导致两者叠着慢慢打。
    val typewriterReasoningText = rememberTypewriterStreamingText(
        text = displayStreamingReasoning ?: "",
        active = streamingReasoning != null && streamingText == null,
        sessionKey = currentSessionId
    ).text

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
    val isFarFromBottom by remember(inputBarReservePx, messagesReady, messages.size, chatItems.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != chatItems.size + 1) return@derivedStateOf false
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

    // 切换会话 / 打开搜索命中：优先定位到目标消息，否则贴底并恢复跟随。
    LaunchedEffect(currentSessionId, messagesReady, pendingScroll, chatItems) {
        if (!messagesReady) return@LaunchedEffect
        val pending = pendingScroll
        if (pending != null && pending.first == currentSessionId) {
            val index = chatItems.indexOfFirst { it.message.id == pending.second }
            if (index >= 0) {
                // 等一帧让 LazyColumn 按新会话完成重组，再瞬移到目标消息。
                withFrameNanos { }
                followBottom = false
                listState.scrollToItem(index)
                positionedSession = currentSessionId
                viewModel.consumePendingScroll()
            } else if (!messagesState.hasMore) {
                // 全部消息已加载仍找不到（消息可能已被删除）：放弃，避免卡在待定位态。
                viewModel.consumePendingScroll()
            }
            return@LaunchedEffect
        }
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

    // 展开/收起一条 item（工具卡片、工具分组头）之后**不做任何主动滚动**：就地展开、就地收起，
    // 视口一动不动；只暂停贴底跟随（用户滚回底部时由 isAtBottom 监测自动恢复）。
    //
    // 从前这里会按「让 item 底部露出安全区」重定位视口，还带一条「item 完全不可见就 scrollToItem」
    // 的兜底；卡片比一屏高时前者算出的目标被夹到 0，后者干脆把 item 顶到视口顶——两条路径都会让
    // 被点的那一行整条跳到顶部（概率性出现，取决于点的那一刻布局稳定到哪一帧）。这个跳动比
    // 「展开后底部被输入框挡一点」难接受得多，整段重定位逻辑去掉。
    val onToolItemToggled: () -> Unit = { followBottom = false }

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

    // 文件卡片点击用系统 app 打开：重取/拷贝需要在 IO 线程跑（远程模式要下载），故由这里注入协程。
    val attachmentOpener = remember(viewModel.fileAccess, context) {
        AttachmentOpener { attachment ->
            scope.launch { openSentAttachment(context, attachment, viewModel.fileAccess) }
        }
    }

    CompositionLocalProvider(
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState,
        LocalAttachmentOpener provides attachmentOpener
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
                onNavigateToBrowser = onNavigateToBrowser,
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive,
                browserActive = browserActive
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
                            val group = item.toolGroup
                            // 成员行缩进：由纯函数按下标关系定位所属分组头（见 isExpandedGroupMember）
                            val inExpandedGroup = isExpandedGroupMember(
                                chatItems = chatItems,
                                index = index,
                                isToolRow = item.message.role == MessageRole.TOOL,
                            )
                            if (group != null) {
                                // 分组头：点一下展开/收起整组工具调用，并复用同一套视口重定位。
                                // 还在跑时由「N 次工具调用」这行文案自己走涟漪高光（见 ToolCallGroupHeader）。
                                ToolCallGroupHeader(
                                    count = group.size,
                                    running = group.any { it.id in runningToolIds || it.isToolRunning(null) },
                                    expanded = item.groupExpanded,
                                    onToggle = {
                                        viewModel.setToolExpanded(item.key, !item.groupExpanded)
                                        onToolItemToggled()
                                    }
                                )
                            } else {
                                val message = item.message
                                val live = runningTool.firstOrNull { it.messageId == message.id }?.text
                                AgentMessageItem(
                                    message = message,
                                    showActions = message.id == lastActionableMessageId,
                                    liveOutput = live,
                                    markdownCache = markdownCache,
                                    contentSlice = item.slice,
                                    isChunkHeader = item.isChunkHeader,
                                    isChunkFooter = item.isChunkFooter,
                                    onRewindClick = { viewModel.openRewindMenu(it) },
                                    onMoreClick = { messageForMenu = it },
                                    toolExpandedOverride = toolGroupOverrideSnapshot[message.id],
                                    onToolExpandedChange = { isExpanded ->
                                        viewModel.setToolExpanded(message.id, isExpanded)
                                    },
                                    onToolToggle = onToolItemToggled,
                                    // 分组展开时成员行内缩一级，与分组头区分层级
                                    contentPadding = if (inExpandedGroup) ToolGroupMemberPadding else PaddingValues(0.dp),
                                    taskDurationMs = taskDurations[message.id],
                                    turnUsage = turnUsages[message.id],
                                    entryDelayMs = messageEntryDelays[message.id]
                                )
                            }
                        }
                        val reasoning = displayStreamingReasoning
                        val showReasoning = reasoning?.hasVisibleContent() == true
                        // 忙碌状态指示器：在没有正文/思考流式输出、未在压缩、未被权限弹窗或询问挂起时展示。
                        // 工具正在执行或模型正在准备工具时，文案会由 thinkingLabel 动态呈现为对应场景（如「正在读取文件」）；
                        // 既无流式又无具体工具时兜底显示「正在思考」。
                        val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && pendingPermission == null && pendingQuestion == null
                        val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val showKeySwitched = keySwitchState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                        val tailKind = when {
                            showStreaming -> TailKind.STREAMING
                            isCompacting -> TailKind.COMPACTING
                            showKeySwitched -> TailKind.KEY_SWITCHED
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
                                    // 流式实时：默认收起，折叠行跟着正在写的那一行滚动；点开看全文
                                    ReasoningBubble(text = typewriterReasoningText, cache = markdownCache, showTimer = true, preRendered = true, sessionKey = currentSessionId, live = true)
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble(label = thinkingLabel)
                                    TailKind.STREAMING -> StreamingBubble(text = typewriterRenderText, cache = markdownCache)
                                    TailKind.COMPACTING -> CompactionProgressBubble()
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.KEY_SWITCHED -> {
                                        val ks = keySwitchState
                                        if (ks != null) KeySwitchedBubble(ks.newIndex, ks.total) else Box(Modifier)
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
            val floatingPanelAlpha by animateFloatAsState(
                targetValue = if (listState.isScrollInProgress) 0.4f else 1f,
                animationSpec = tween(200),
                label = "floating-panel-alpha"
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 实测悬浮层实际高度作为滚动留白，横幅/面板/输入框任何形态都不遮挡最后一条
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = floatingPanelAlpha }) {
                StatusBanner(state = agentState)
            }

            // 退场动画期间 uploadingCount 已归零，直接读会淡出一个「正在上传 0 个文件」，
            // 与 StatusBanner 同样用非空记忆值兜住退场。
            val lastUploadingCount = rememberLastNonNull(uploadingCount.takeIf { it > 0 })
            AnimatedVisibility(
                visible = uploadingCount > 0,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastUploadingCount?.let { UploadingBanner(count = it) }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
            val planForPanel = rememberLastNonNull(planApproval)
            AnimatedVisibility(
                visible = planApproval != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it; viewModel.updateInputDraft(it) },
                onSend = sendMessage,
                enterToSend = settingsViewModel?.enterToSend?.collectAsStateWithLifecycle()?.value ?: false,
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
                slashCommands = slashCommands,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                dashboardState = currentDashboardState,
                todoItems = currentTodoItems,
                sessionId = currentSessionId.orEmpty(),
                onTodoExpandedChange = { todoExpanded = it },
                forceCollapseDashboard = pendingPermission != null || pendingQuestion != null || planApproval != null || imeVisible,
                onDashboardExpandedChange = { dashboardExpanded = it },
                onRefreshDashboard = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                onRefreshDashboardByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.contextTokens ?: 0
                    if (contextLimit > 0) {
                        sessionLastInputTokens.toFloat() / contextLimit
                    } else 0f
                },
                isScrolling = listState.isScrollInProgress,
                forceOpenModelSheet = onboardingStep == OnboardingStep.SIMULATE_CHOOSE_MODEL,
                onSelectModelInOnboarding = onSelectModelInOnboarding,
                onModelSheetDismiss = onDismissModelSheetInOnboarding,
                modifier = Modifier
                    .fillMaxWidth()
                    .onboardingTarget(OnboardingStep.SEND_MESSAGE)
            )
            } // 悬浮层结束

            // 悬浮授权弹窗：独立于底栏排版流，悬浮于输入框上方，避免撑大底栏高度导致消息列表被顶上去。
            // 用户上下滑动消息列表时跟随透明弱化，不遮挡阅读视线。
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            var permissionPanelHeightPx by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = pendingPermission != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { floatingLayerHeightPx.toDp() } + Spacing.xs)
                    .onGloballyPositioned { permissionPanelHeightPx = if (pendingPermission != null) it.size.height else 0 },
                enter = fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 3 } +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) +
                       slideOutVertically(tween(160)) { it / 3 } +
                       scaleOut(targetScale = 0.96f, animationSpec = tween(160))
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = dashboardCollapseActive,
                        isScrolling = listState.isScrollInProgress
                    )
                }
            }

            // 滚动到底部按钮：悬浮在输入框右上角上方（避让底栏及可能悬浮的授权弹窗），离底超过半屏时
            // 显示，不看滚动方向——往上翻历史后停住恰恰是最需要一键回底的时刻；滚动时跟随输入框淡出。
            val permissionOffsetPx = with(LocalDensity.current) {
                if (pendingPermission != null) permissionPanelHeightPx + Spacing.xs.toPx() else 0f
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + permissionOffsetPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
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

