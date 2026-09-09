package com.aicode.feature.agent.presentation

import androidx.compose.runtime.Immutable
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.serialization.Serializable

/**
 * 侧边栏文件树的一个可见节点（已按展开状态扁平化，携带 [depth] 供 UI 缩进）。
 * 从工作区根就地展开：只有 [FileBrowseState.Success] 里出现的节点才是当前可见的。
 */
@Immutable
data class FileTreeNode(
    val entry: FileEntry,
    /** 容器绝对路径（如 `~/workspace/app/src`）；[isRoot] 时为工作区根。 */
    val path: String,
    /** 缩进层级：根为 0，其直接子项为 1，依此类推。 */
    val depth: Int,
    /** 工作区根节点：不可重命名/删除，只能在其下新建。 */
    val isRoot: Boolean,
    /** 目录是否已展开；非目录恒为 false。 */
    val isExpanded: Boolean,
    /** 展开该目录时读取子项失败（如权限不足）；根读取失败走 [FileBrowseState.Error]。 */
    val hasError: Boolean = false,
    /** 被工作区根 .gitignore 命中：UI 以橙色弱化显示（类 VSCode）。 */
    val ignored: Boolean = false
)

/** 文件浏览剪切板：一次只能持有一项，复制或剪切后待粘贴。 */
@Immutable
data class BrowseClipboard(
    /** 源条目所在目录路径。 */
    val sourcePath: String,
    /** 源条目显示名（UI 提示用）。 */
    val sourceName: String,
    /** 是否剪切（粘贴成功后删除源）。 */
    val isCut: Boolean
)

/** 单轮工作流的最终结果状态（供 [AgentUIState.Result] 使用）。 */
enum class WorkflowStatus {
    SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED
}

sealed class AgentUIState {
    object Idle : AgentUIState()
    object Loading : AgentUIState()
    object Streaming : AgentUIState()
    data class Result(val status: WorkflowStatus) : AgentUIState()
    data class Error(val message: String) : AgentUIState()
}

/** 侧边栏「文件」Tab 的文件树读取状态。远程模式走 SFTP/exec，读取可能失败或较慢，故区分三态。 */
sealed interface FileBrowseState {
    data object Loading : FileBrowseState
    data class Success(val nodes: List<FileTreeNode>) : FileBrowseState
    data class Error(val detail: String?) : FileBrowseState
}

/**
 * 单次 LLM 请求完成事件（含当次消耗的 Token）。
 */
@Immutable
data class LlmCallEvent(
    val sessionId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 网络请求重试状态（仅用于 UI 实时展示「正在重试 (N/M)...」提示）。
 * [error] 为触发重试的错误摘要，UI 据此展示具体原因（如 429/500/网络断开）。
 * 与 [AgentUIState] 解耦：重试是 Streaming 的子状态，不改变顶层 agent 状态机。
 */
@Immutable
data class RetryState(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo? = null)

@Immutable
data class AgentUIMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<AgentAttachment> = emptyList(),
    // 仅 TOOL 消息：渲染用，不参与上下文回放。
    val toolName: String? = null,
    // 仅 TOOL 消息：本次调用传入的参数（JSON 文本），渲染「执行的指令」用。
    val toolArgs: String? = null,
    val isError: Boolean = false,
    // 仅 ASSISTANT 消息：本轮模型的思考过程，渲染为可折叠「思考过程」气泡；无则为 null。
    val reasoning: String? = null,
    // 上下文压缩内部锚点：不显示用户气泡，渲染为压缩分隔线。
    val isCompactionMarker: Boolean = false,
    // 上下文压缩生成的摘要消息：渲染为可展开的压缩摘要卡片，颜色与工具调用区分。
    val isContextSummary: Boolean = false,
    // 上下文压缩失败记录（TOOL 消息）：渲染为可展开的失败卡片，展示失败原因。
    val isCompactionFailure: Boolean = false,
    // 后台任务完成通知：参与模型上下文但不显示为普通用户气泡，渲染为轻量提示条。
    val isBackgroundNotification: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    // 仅群聊成员发言：消息发送者（成员 agent 名）；普通消息为 null。
    val senderName: String? = null,
    // 仅 ASSISTANT 消息：本次调用输入中命中服务端缓存的 token 数，气泡下方据此算缓存命中率。
    val cachedInputTokens: Int = 0
)

@Immutable
@Serializable
data class AgentAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
)

enum class MessageRole {
    USER, ASSISTANT, TOOL
}

/**
 * 后台任务完成通知的固定前缀。既是
 * [com.aicode.feature.agent.domain.notification.AgentNotificationFormatter] 生成通知消息时的起首文本，
 * 也是 UI 层识别此类消息（不渲染为普通用户气泡）的依据。改这里需同步两边。
 */
const val BACKGROUND_NOTIFICATION_PREFIX = "[系统通知 - 非用户输入]"

/**
 * 上下文压缩失败记录的 TOOL 消息 toolName。既是落库时的标记，也是 UI 识别失败卡片的依据；
 * 该消息无配对 toolCallId，[MessagePersistenceUseCase.buildHistory] 回放时会自动丢弃，不进入模型上下文。
 */
const val COMPACTION_FAILURE_TOOL_NAME = "上下文压缩"

/**
 * 一次会话消息查询的结果快照。
 * - [sessionId]：这批消息所属会话；null 表示当前还没有解析出会话（冷启动中）。
 * - [messages]：已过滤、可直接渲染的消息列表。
 * - [loaded]：是否已经从数据库读到该会话的数据，用以区分「加载中」与「空会话」。
 */
@Immutable
data class ChatMessagesState(
    val sessionId: String?,
    val messages: List<AgentUIMessage>,
    val loaded: Boolean,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/**
 * 「不出墨」的码点：虽非空白、也不属格式(Cf)/控制(Cc)，却渲染为零宽或纯空白。
 * 关键是 Hangul filler 一族——它们的 Unicode 类别是 Lo(其他字母)，所以**任何按类别判定的
 * 方案都抓不到**，必须显式列举。这是上一版「只过滤 Cf/Cc」修复失效的真正原因：部分模型在
 * 纯工具调用轮次吐出 U+3164 等填充字符，每调一次工具就漏出一个空气泡。
 */
internal val BLANK_GLYPH_CODE_POINTS: Set<Char> = setOf(
    0x115F, 0x1160, 0x3164, 0xFFA0, // Hangul filler（Lo，零宽，按类别抓不到）
    0x2800,                         // Braille pattern blank（So，纯空点）
    0x034F,                         // 组合用字位连接符 CGJ
    0x17B4, 0x17B5,                 // Khmer 固有元音（零宽）
    0x2060, 0xFEFF,                 // 词连接符 / BOM（Cf，冗余兜底）
    0x200B, 0x200C, 0x200D,         // 零宽空格 / ZWNJ / ZWJ（Cf，冗余兜底）
).mapTo(HashSet()) { it.toChar() }

/**
 * 文本是否含「可见(出墨)」内容。判定 = 至少有一个字符既非空白、又不属不可见类别(Cf/Cc/代理)、
 * 也不在 [BLANK_GLYPH_CODE_POINTS] 黑名单内。比 [CharSequence.isBlank] 严格得多：
 * 后者只把 whitespace 当空，会让零宽/填充字符漏出空白气泡。
 * 这是「是否渲染助手气泡」的唯一关门，持久化归一化与各渲染/过滤层共用它，改此一处即全链路生效。
 * 注意：保留代理对(emoji 等)与普通可见字符；零宽连接符 ZWJ 只在「整条文本是否为空」上当空，
 * 不会从展示文本里被剔除，故 emoji 连字序列不受影响。
 */
fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        val ch = get(i)
        if (!ch.isWhitespace() && ch.category != CharCategory.FORMAT &&
            ch.category != CharCategory.CONTROL && ch !in BLANK_GLYPH_CODE_POINTS
        ) {
            // 代理区划（emoji 所在补全平面）仅在成对的合法高/低代理时算可见，孤立或残缺代理仍判空
            if (ch.category != CharCategory.SURROGATE) return true
            if (Character.isHighSurrogate(ch) && i + 1 < length && Character.isLowSurrogate(get(i + 1))) return true
        }
        i++
    }
    return false
}

/**
 * 运行中工具的实时累积输出。仅存内存、不落库：用于在该工具消息气泡里实时叠加显示
 * 命令逐行 stdout；命令结束后清空，最终完整结果走正常 persist 落库。
 */
@Immutable
data class RunningToolOutput(val messageId: String, val text: String, val toolName: String = "", val toolArgs: String = "")

data class QueuedRequest(
    val id: String,
    val request: String,
    val modelRequest: String = request,
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val inputImages: List<AgentImage> = emptyList(),
    val inputAttachments: List<AgentAttachment> = emptyList(),
    val isAutoTrigger: Boolean = false
)
