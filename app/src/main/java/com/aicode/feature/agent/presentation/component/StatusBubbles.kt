package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Star
import kotlinx.coroutines.delay

@Composable
internal fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
                contentAlignment = Alignment.Center
            ) {
                TypingDots(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 上下文压缩期间的临时状态气泡，不落库。 */
@Composable
internal fun CompactionProgressBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.chat_compressing_context),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                TypingDots(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 网络重试期间的临时状态气泡，不落库。首行展示触发重试的具体错误（如 429/500/网络断开），次行展示重试进度。 */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int, error: RetryErrorInfo?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                if (error != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            FeatherIcons.AlertCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = retryErrorLabel(error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.chat_retrying, attempt, maxRetries),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TypingDots(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** 错误摘要文本：类别文案 + 状态码（如「速率限制 (429)」）；无状态码时仅类别文案。 */
@Composable
private fun retryErrorLabel(error: RetryErrorInfo): String {
    val base = stringResource(
        when (error.kind) {
            RetryErrorKind.RATE_LIMIT -> R.string.retry_error_rate_limit
            RetryErrorKind.SERVER_OVERLOADED -> R.string.retry_error_server_overloaded
            RetryErrorKind.SERVER_ERROR -> R.string.retry_error_server
            RetryErrorKind.TIMEOUT -> R.string.retry_error_timeout
            RetryErrorKind.CONNECTION_REFUSED -> R.string.retry_error_connection_refused
            RetryErrorKind.DNS_FAILED -> R.string.retry_error_dns_failed
            RetryErrorKind.CONNECTION_RESET -> R.string.retry_error_connection_reset
            RetryErrorKind.SSL_ERROR -> R.string.retry_error_ssl
            RetryErrorKind.NETWORK -> R.string.retry_error_network
            RetryErrorKind.UNKNOWN -> R.string.retry_error_unknown
        }
    )
    val code = error.statusCode
    return if (code != null) stringResource(R.string.retry_error_with_code, base, code) else base
}

/** 流式渲染节流：文本变化后延迟该时长再更新渲染文本，降低 md 解析频率。 */
private const val STREAMING_RENDER_DEBOUNCE_MS = 120L

/**
 * 对持续增长的流式文本做节流渲染：首帧立即渲染当前文本，之后每次文本变化最多
 * [STREAMING_RENDER_DEBOUNCE_MS] 更新一次。上游每个 delta 都携带完整累积文本，
 * 若不节流，每个 token 都会触发一次完整 md 解析（长文本下解析慢、渲染滞后）。
 * 返回的文本只用于渲染，折叠判定等实时逻辑仍直接用原始 [text]。
 */
@Composable
private fun rememberThrottledStreamingText(text: String): String {
    var renderText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (renderText == text) return@LaunchedEffect
        delay(STREAMING_RENDER_DEBOUNCE_MS)
        if (renderText != text) renderText = text
    }
    return renderText
}

/** 打字机最低显示速率（字符/秒）：上游停顿时仍匀速追赶，保证能看到结尾。 */
private const val TYPEWRITER_MIN_RATE = 30f

/** 打字机显示速率上限（字符/秒）：防止模型爆发式吐字时显示被拉爆。 */
private const val TYPEWRITER_MAX_RATE = 200f

/** 显示速率 = 上游速率 × 该系数：略小于 1，保持「始终慢半拍」的滞后感。 */
private const val TYPEWRITER_FOLLOW_RATIO = 0.9f

/** 追赶系数（/秒）：显示进度落后越多追得越快，时间常数约 1/该值 秒。 */
private const val TYPEWRITER_CATCHUP_PER_SEC = 0.5f

/** 上游吐字速率估算的滑动窗口时长（ms）。 */
private const val TYPEWRITER_RATE_WINDOW_MS = 500L

/** 打字机渲染节流间隔（ms）：Markdown 解析频率上限约 1/间隔。 */
private const val TYPEWRITER_RENDER_INTERVAL_MS = 100L

/** 按码点数量截断字符串，避免把 emoji 等代理对截成孤立的半个字符。 */
private fun truncateToCodePoints(text: String, codePoints: Int): String {
    if (codePoints <= 0) return ""
    if (codePoints >= text.codePointCount(0, text.length)) return text
    var index = 0
    var count = 0
    while (index < text.length && count < codePoints) {
        index += Character.charCount(text.codePointAt(index))
        count++
    }
    return text.substring(0, index)
}

/** 延续判据的前缀采样上限（字符）：只存指纹不存全文，避免大段流式文本进 saveable。 */
private const val CONTINUITY_HEAD_CHARS = 64

/** 已见文本的前缀指纹，与其长度一起构成「同一轮延续」的判据。 */
internal fun streamHeadFingerprint(text: String): Int =
    text.take(CONTINUITY_HEAD_CHARS).hashCode()

/**
 * [text] 是否是「长度 [seenChars]、前缀指纹 [seenHead]」那段已见文本的延续。
 *
 * 流式文本逐 delta 前缀增长，同一轮内当前文本必然以已见文本为前缀。切页或 item 回收后
 * 重挂载时用它校验恢复出的打字进度 / 计时起点是否仍属于同一轮：期间若已换轮，新文本更短
 * 或开头不同，判为不延续，进度与计时从头开始。
 */
internal fun isStreamContinuation(text: String, seenChars: Int, seenHead: Int): Boolean {
    if (seenChars <= 0 || text.length < seenChars) return false
    return text.take(minOf(seenChars, CONTINUITY_HEAD_CHARS)).hashCode() == seenHead
}

/**
 * 速率自适应打字机：显示文本滞后于上游累积文本，打字速度跟随模型吐字速度。
 *
 * 上游每个 delta 都携带完整累积文本，到达节奏即模型吐字节奏。此处维护两个进度：
 * 到达进度（[text] 的码点数）与显示进度（已展示的码点数）。显示进度由动画帧驱动，
 * 每帧取「上游速率 × 跟随系数」与「按滞后量追赶」两者较大值（封顶 [TYPEWRITER_MAX_RATE]），
 * 上游停顿时以 [TYPEWRITER_MIN_RATE] 兜底匀速追赶直至追平，避免停在半截。
 *
 * 渲染文本每 [TYPEWRITER_RENDER_INTERVAL_MS] 快照一次（throttle 而非 debounce，
 * 保证打字期间渲染持续可见增长），把 md 解析频率压在 ~10fps；text 突变（换会话 /
 * 新一轮 / 重试）时补全为当前全文，之后继续跟着 delta 打字。上游结束（[active] 变 false）
 * 时立即显示完整文本，与落库消息无缝交接。
 *
 * 调用方应在 LazyColumn 之外持有本状态，避免尾巴 item 滚出视口被 dispose 后
 * 重新组合导致打字进度丢失。切页（chat 整棵子树离开 NavHost 组合）无法靠持有位置规避，
 * 由内部 saveable 进度承接。
 */
@Composable
internal fun rememberTypewriterStreamingText(
    text: String,
    active: Boolean,
    /**
     * 文本所属会话。切到另一个正在输出的会话时，它已产出的部分是既成事实，必须直接补全显示——
     * 本函数的状态挂在调用点上，会话切换并不会让它重建，不显式区分就会被下面的「换轮」
     * 判定当成新一轮，把那段内容当着用户的面再逐字打一遍。
     */
    sessionKey: String? = null
): String {
    // 已渲染文本的长度与前缀指纹进 saveable：切页返回后据此延续打字进度，避免已输出的
    // 正文从头重打。校验不通过（期间换过轮或换过会话）时补全为当前全文而不是从头打字：
    // 挂载这一刻才第一次看到的文本对用户就是历史，重打一遍只会让人以为模型在重复输出。
    var shownChars by rememberSaveable { mutableStateOf(0) }
    var shownHead by rememberSaveable { mutableStateOf(0) }
    val restored = remember {
        if (isStreamContinuation(text, shownChars, shownHead)) text.substring(0, shownChars) else text
    }
    var shownCodePoints by remember {
        mutableStateOf(restored.codePointCount(0, restored.length).toFloat())
    }
    var renderText by remember { mutableStateOf(restored) }
    // 上游到达事件窗口：(帧时间戳, 累计码点数)，用于估算吐字速率
    val arrivals = remember { ArrayDeque<Pair<Long, Int>>() }
    var lastArrivalNanos by remember { mutableStateOf(0L) }
    var lastText by remember { mutableStateOf(restored) }
    var lastSessionKey by remember { mutableStateOf(sessionKey) }
    // 渲染文本与其 saveable 指纹必须同步更新，否则恢复时会拿指纹去校验另一段文本
    val commitRender: (String) -> Unit = { snapshot ->
        renderText = snapshot
        shownChars = snapshot.length
        shownHead = streamHeadFingerprint(snapshot)
    }

    LaunchedEffect(text, active, sessionKey) {
        // 文本不是当前进度的延续（换会话 / 新一轮 / 重试）：补全到当前全文，再跟着后续 delta 打字。
        // 不能归零重打——切到另一个正在输出的会话时，它已产出的几百字会当着用户的面再来一遍。
        // 换会话必须单独判：currentSessionId 与 streamingText 未必同一帧到达，只靠前缀判定
        // 会漏掉先到的那一帧（那一帧文本还是旧会话的，看不出突变）。
        // 新一轮开头也走这条路径，但那时 text 只有第一个 delta 的几个字，补全与重打视觉上无差别。
        val sessionChanged = sessionKey != lastSessionKey
        lastSessionKey = sessionKey
        if (sessionChanged || (lastText.isNotEmpty() && !text.startsWith(lastText))) {
            shownCodePoints = text.codePointCount(0, text.length).toFloat()
            commitRender(text)
            arrivals.clear()
            lastArrivalNanos = 0L
        }
        lastText = text

        if (!active) {
            // 上游已结束：直接显示完整文本，交给落库消息无缝接管
            shownCodePoints = text.codePointCount(0, text.length).toFloat()
            commitRender(text)
            return@LaunchedEffect
        }

        // 记录本次到达事件，裁剪速率窗口（保留最近 WINDOW 内至少 2 条）
        val now = System.nanoTime()
        val codePoints = text.codePointCount(0, text.length)
        if (lastArrivalNanos != 0L) {
            arrivals.addLast(now to codePoints)
            val windowNanos = TYPEWRITER_RATE_WINDOW_MS * 1_000_000L
            while (arrivals.size > 2 && now - arrivals.first().first > windowNanos) {
                arrivals.removeFirst()
            }
        }
        lastArrivalNanos = now

        // 帧驱动推进显示进度，追平本次文本即退出（text 再变化时本协程被取消重启）
        var lastRenderNanos = 0L
        var lastFrameNanos = 0L
        while (shownCodePoints < codePoints) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    // 跟随项：上游速率 × 系数（滑动窗口估算）；追赶项：滞后越多追得越快
                    var arrivalRate = 0f
                    val oldest = arrivals.firstOrNull()
                    if (arrivals.size >= 2 && oldest != null) {
                        val spanSec = (frameNanos - oldest.first) / 1_000_000_000f
                        if (spanSec > 0f) {
                            arrivalRate = (arrivals.last().second - oldest.second) / spanSec
                        }
                    }
                    val followRate = arrivalRate * TYPEWRITER_FOLLOW_RATIO
                    val catchUpRate = (codePoints - shownCodePoints) * TYPEWRITER_CATCHUP_PER_SEC
                    val rate = maxOf(followRate, catchUpRate)
                        .coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_MAX_RATE)
                    shownCodePoints = (shownCodePoints + rate * dtSec)
                        .coerceAtMost(codePoints.toFloat())
                }
                lastFrameNanos = frameNanos

                // 渲染节流：到间隔就快照当前显示进度；追平瞬间强制渲染完整文本
                val intervalNanos = TYPEWRITER_RENDER_INTERVAL_MS * 1_000_000L
                if (frameNanos - lastRenderNanos >= intervalNanos || shownCodePoints >= codePoints) {
                    val snapshot = truncateToCodePoints(text, shownCodePoints.toInt())
                    if (snapshot != renderText) {
                        lastRenderNanos = frameNanos
                        commitRender(snapshot)
                    }
                }
            }
        }
        // 追平后确保渲染完整文本（while 退出时 shownCodePoints 已到 available）
        if (renderText != text) commitRender(text)
    }
    return renderText
}

/**
 * 模型流式吐字时的实时气泡：左对齐、与助手气泡同款。
 * 尾部带三个跳动的点表示仍在生成。本轮结束后由落库的助手气泡接管。
 *
 * 流式阶段以打字机效果渲染（打字进度由调用方经
 * [rememberTypewriterStreamingText] 驱动，见 [AIChatPanel]），打字速度随模型吐字
 * 速度自适应，上游结束时自动补全为完整文本，与落库消息无缝接力。
 */
@Composable
internal fun StreamingBubble(
    text: String,
    cache: MarkdownRenderCache? = null
) {
    val renderText = text
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
                MarkdownContent(
                    text = renderText,
                    color = MaterialTheme.colorScheme.onSurface,
                    cache = cache
                )
                Spacer(Modifier.height(Spacing.sm))
                TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
            }
        }
    }
}

/** 思维链折叠阈值：超过此行数视为过长，自动折叠为前 N 行 + 「展开剩余 X 行」。 */
internal const val REASONING_COLLAPSE_LINE_LIMIT = 8

/** 思考时长格式化：<1 分钟显示 `5s`，超过显示 `1:05`。 */
private fun formatThinkingTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "$m:${s.toString().padStart(2, '0')}" else "${s}s"
}

/**
 * 思考过程可折叠气泡：左对齐、浅色弱化，与正式回复区分。点击标题栏折叠/展开。
 *
 * 折叠判定按行数阈值：超过 [REASONING_COLLAPSE_LINE_LIMIT] 行视为「过长」，自动折叠为
 * 前 N 行 + 「展开剩余 X 行」。流式实时展示时，短文本边想边看，一旦长度越过阈值即自动
 * 折叠（折叠态下新内容仍持续追加，保持折叠不刷屏，用户可随时点开看最新）；落库后的历史
 * 气泡默认折叠，避免刷屏。用户手动 toggle 后以用户选择为准，不再被自动折叠覆盖。
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    initiallyExpanded: Boolean = true,
    cache: MarkdownRenderCache? = null,
    showTimer: Boolean = false,
    /** 文本已由外部打字机驱动（流式尾巴场景），跳过内部防抖直接渲染。 */
    preRendered: Boolean = false,
    /** 思考所属会话：切会话时重新计时，否则会拿上一个会话的起点算出离谱的时长。 */
    sessionKey: String? = null
) {
    var userToggled by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // 思考计时：仅流式思考场景开启，思考结束组件卸载自然停止。存绝对起始时间戳而非累加
    // 秒数，切页返回或气泡滚出视口重挂载后显示的仍是真实时长；起始戳连同已见文本的长度与
    // 指纹一起进 saveable，恢复时文本若不是同一轮的延续（期间已换轮）则重新计时。
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(showTimer, sessionKey) {
        if (!showTimer) return@LaunchedEffect
        if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - timerStartMillis) / 1000).toInt()
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(1000)
        }
    }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    // 折叠判定/折叠预览用实时文本，展开渲染用节流文本（流式思考时降低 md 解析频率）；
    // preRendered 时外部已按打字机节奏给出渲染文本，直接使用。
    val renderText = if (preRendered) text else rememberThrottledStreamingText(text)
    val overThreshold = lineCount > REASONING_COLLAPSE_LINE_LIMIT
    // 自动折叠：仅在用户尚未手动 toggle 过时生效；用户手动展开/折叠后以用户选择为准
    val effectiveExpanded = if (userToggled) expanded else (initiallyExpanded && !overThreshold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md, Radius.md, Radius.md, Radius.xs),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            userToggled = true
                            expanded = !expanded
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        FeatherIcons.Star,
                        contentDescription = null,
                        tint = Brand.IconGray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.chat_thinking_process),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (showTimer) {
                        Spacer(Modifier.width(Spacing.sm))
                        Icon(
                            FeatherIcons.Clock,
                            contentDescription = null,
                            tint = Brand.IconGray,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatThinkingTime(elapsedSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (effectiveExpanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                        contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                        tint = Brand.IconGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (effectiveExpanded) {
                    Spacer(Modifier.height(Spacing.sm))
                    MarkdownContent(
                        text = renderText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        cache = cache,
                        compact = true,
                        modifier = Modifier.pointerInput(text) {
                            detectTapGestures(
                                onDoubleTap = {
                                    userToggled = true
                                    expanded = false
                                }
                            )
                        }
                    )
                } else if (overThreshold) {
                    // 折叠态：显示最新内容（尾部 N 行）+「还有 X 行」
                    Spacer(Modifier.height(Spacing.sm))
                    val tailText = remember(text) {
                        text.lines().takeLast(REASONING_COLLAPSE_LINE_LIMIT).joinToString("\n")
                    }
                    Text(
                        text = tailText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = REASONING_COLLAPSE_LINE_LIMIT,
                        overflow = TextOverflow.Ellipsis
                    )
                    val hidden = lineCount - REASONING_COLLAPSE_LINE_LIMIT
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable {
                                userToggled = true
                                expanded = true
                            }
                            .padding(vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.ChevronDown,
                            contentDescription = stringResource(R.string.common_expand),
                            tint = Brand.IconGray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = stringResource(R.string.chat_expand_remaining, hidden),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 三个循环跳动的点：通用「正在输入/生成」指示器，取代转圈 spinner。
 * 三点以固定相位差依次上下弹跳，形成波浪式律动。
 *
 * 性能优化：用 graphicsLayer { translationY } 替代 offset(y)，动画值变化在 draw 阶段
 * 处理而不触发 compose/recompose，消除无限动画导致父布局每帧重组的开销。
 * 容器高度固定，防止布局波动传递到 LazyColumn。
 */
@Composable
internal fun TypingDots(
    color: Color,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val label = stringResource(R.string.chat_status_generating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 给读屏一个语义：三个跳动的点对 TalkBack 本来完全不可见。
        modifier = Modifier
            .height(dotSize + 10.dp)
            .semantics { contentDescription = label }
    ) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5f at 180
                        0f at 360
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            if (index < 2) Spacer(Modifier.width(4.dp))
        }
    }
}
