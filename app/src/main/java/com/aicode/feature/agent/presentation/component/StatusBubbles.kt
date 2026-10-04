package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Key
import kotlinx.coroutines.delay

/** 涟漪高光一个来回的周期（ms）。 */
private const val RIPPLE_PERIOD_MS = 1600

/** 高光带宽度占内容宽度的比例：太宽像整段变色，太窄看不出来。 */
private const val RIPPLE_BAND_RATIO = 0.5f

/** 高光带边缘的不透明度（渐变两端）。 */
private const val RIPPLE_EDGE_ALPHA = 0.12f

/**
 * 高光带峰值不透明度。
 *
 * 高光是白色（主流做法）：深色主题下把字「打亮」，浅色主题下把字朝背景方向提亮成一道光泽。
 * 峰值不能太高——浅色主题下白色 0.85 会直接把字擦掉，0.5 左右是「亮一下但仍认得出字」。
 */
private const val RIPPLE_PEAK_ALPHA = 0.5f

/**
 * 涟漪高光：一条中间亮、两端透明的高光带周期性从左划到右。
 *
 * 用 [BlendMode.SrcAtop] 叠在已绘制内容上——它只作用在不透明像素上，所以不会在空白处
 * 画出一条色带方块。高光默认白色（Claude Code / 主流 shimmer 的做法）；浅色主题下白色会在
 * 文字像素上把字朝背景提亮，形成一道掠过的光泽，而不是盖上一层灰。
 *
 * 性能：进度在 [drawWithContent] 的绘制阶段读取（`by` 委托在 lambda 内取值），只重绘、不重组。
 */
@Composable
internal fun Modifier.rippleHighlight(
    highlight: Color = Color.White,
    bandRatio: Float = RIPPLE_BAND_RATIO,
    periodMs: Int = RIPPLE_PERIOD_MS
): Modifier {
    val transition = rememberInfiniteTransition(label = "ripple-highlight")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-progress"
    )
    return drawWithContent {
        drawContent()
        val band = size.width * bandRatio
        // 起点在左侧完全移出、终点在右侧完全移出，两端各留一个带的余量做「停顿感」
        val center = -band + progress * (size.width + 2 * band)
        drawRect(
            brush = Brush.linearGradient(
                // 五档渐变：两端全透明、中间一个软峰，扫过时是「亮一下」而不是一条硬边色带
                colors = listOf(
                    Color.Transparent,
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    highlight.copy(alpha = RIPPLE_PEAK_ALPHA),
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    Color.Transparent
                ),
                start = Offset(center - band / 2f, 0f),
                end = Offset(center + band / 2f, 0f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

/**
 * 一条会走涟漪高光的文字（[Modifier.rippleHighlight] 的文字版）。
 *
 * 涟漪画在**文字自身宽度**上：调用方不要给它 `weight`，否则光带会在整段可分配宽度里爬、
 * 扫过这几个字只是一瞬间（要占位就让外层容器去占）。
 *
 * [highlight] 传 null 表示只画静态文字：同一处文案要在「空闲」与「进行中」之间切换时，
 * 用同一个组件、只换这一个参数即可（避免两个分支各写一套 Text 参数）。
 */
@Composable
internal fun RippleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    highlight: Color? = Color.White
) {
    val rippleModifier = if (highlight != null) modifier.rippleHighlight(highlight) else modifier
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = rippleModifier
    )
}

/**
 * 统一的「正在忙什么」状态行：涟漪高光文案（+ 可选的三点跳动）。
 *
 * 高光只盖在文案上，三个跳动的点是独立的动画，不参与高光（两层动画叠在一起会糊）。
 * 外层 [modifier]（撑满宽度、定高）交给容器，内层内容按自身宽度靠左排。
 *
 * 读屏语义统一由本行给出（文案即语义），所以内部的 [TypingDots] 关掉自己的播报。
 */
@Composable
internal fun AgentBusyIndicator(
    label: String,
    modifier: Modifier = Modifier,
    showDots: Boolean = false,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 6.dp
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RippleText(text = label)
            if (showDots) TypingDots(color = dotColor, dotSize = dotSize, announce = false)
        }
    }
}

/**
 * 等待模型响应时的状态行：涟漪高光的文案 + 三个跳动的点，不套描边卡片。
 *
 * [label] 由调用方决定说「正在思考」还是更具体的场景（模型已在吐某次工具调用的参数时，
 * 说「正在编辑文件」），见 [toolRunningLabelRes]。
 */
@Composable
internal fun ThinkingBubble(label: String) {
    AgentBusyIndicator(
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** 上下文压缩期间的临时状态行，不落库。 */
@Composable
internal fun CompactionProgressBubble() {
    AgentBusyIndicator(
        label = stringResource(R.string.chat_compressing_context),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** 网络重试期间的临时状态行，不落库。首行展示触发重试的具体错误（如 429/500/网络断开），次行展示重试进度。 */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int, error: RetryErrorInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
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
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.chat_retrying, attempt, maxRetries),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            TypingDots(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 多 Key 自动切换期间的临时状态行，不落库。展示当前 Key 不可用、已改用第 N/M 个 Key 重发。
 *
 * 与 [RetryingBubble]、[CompactionProgressBubble] 同为尾巴里的瞬时状态行，沿用扁平文档流：
 * 不套描边卡片，只有图标 + 文案 + 三点跳动。
 */
@Composable
internal fun KeySwitchedBubble(newIndex: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            FeatherIcons.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.chat_key_switched, newIndex, total),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        TypingDots(color = MaterialTheme.colorScheme.primary)
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

/** 常规显示速率上限（字符/秒）：滞后在 [TYPEWRITER_LAG_TARGET] 以内时的天花板。 */
private const val TYPEWRITER_MAX_RATE = 220f

/** 滞后超标后的应急速率上限（字符/秒）：允许突破常规上限，把积压迅速吃掉。 */
private const val TYPEWRITER_BURST_RATE = 1600f

/** 显示速率 = 上游速率 × 该系数：略小于 1，保留「始终慢半拍」的滞后感。 */
private const val TYPEWRITER_FOLLOW_RATIO = 0.95f

/**
 * 允许的滞后（字符）：显示最多落在上游这么多字之后。
 *
 * 超过它就不再受 [TYPEWRITER_MAX_RATE] 约束。这条上限是「结束瞬间别抽搐」的关键——
 * 上游结束时要补的永远是最后这一小段，而不是攒了几百字的整段。
 */
private const val TYPEWRITER_LAG_TARGET = 12f

/** 追赶时间常数（秒）：滞后按该节奏收敛，越小追得越紧。 */
private const val TYPEWRITER_SETTLE_SECONDS = 0.06f

/**
 * 上游结束后把剩余文字打完的目标时长（秒）。
 *
 * 结束后不再一帧补全（那正是「突然跳到结尾」的来源），而是按「剩余 ÷ 该时长」匀速打完：
 * 既不会瞬移，也不会拖到用户以为卡住。常见剩余量（十几字）约 0.2 秒打完。
 */
private const val TYPEWRITER_DRAIN_SECONDS = 0.2f

/** 收尾阶段的显示速率上限（字符/秒）：剩余很多时也不至于一闪而过。 */
private const val TYPEWRITER_DRAIN_MAX_RATE = 400f

/** 收尾硬上限（ms）：极端情况（文本被整体替换、渲染跟不上）也必须在这之内追平。 */
private const val TYPEWRITER_DRAIN_HARD_MS = 600L

/** 上游吐字速率估算的滑动窗口时长（ms）。 */
private const val TYPEWRITER_RATE_WINDOW_MS = 500L

/** 打字机渲染节流间隔（ms）：~40fps 高频平滑快照，彻底消除低速模型的顿挫感。 */
private const val TYPEWRITER_RENDER_INTERVAL_MS = 24L

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
 * 打字机单帧显示速率（码点/秒）。[lag] = 上游已到字数 − 已显示字数，[arrivalRate] 为滑窗估算的
 * 上游吐字速率，[drainRate] 非空表示上游已结束的收尾阶段（按该恒定速率匀速打完剩余）。
 *
 * 播出阶段取两者较大值：
 * - 跟随项 = 上游速率 × [TYPEWRITER_FOLLOW_RATIO]（略慢于上游，保留「慢半拍」的观感）；
 * - 追赶项 = 滞后 ÷ [TYPEWRITER_SETTLE_SECONDS]（滞后越大追得越快）。
 *
 * 常规上限 [TYPEWRITER_MAX_RATE] 只在滞后未超标时生效：滞后一旦超过 [TYPEWRITER_LAG_TARGET]
 * 就放宽到 [TYPEWRITER_BURST_RATE]。这条规则是「结束时别抽搐」的关键——旧实现把速率死封在
 * 200 字符/秒，模型吐得快或爆发式吐字时滞后只增不减（稳态约 1.8×上游速率，动辄上百字），
 * 上游一结束整段一次跳出，看起来就是突然抽搐一下。现在滞后被压在十几字以内，
 * 收尾阶段再按 [TYPEWRITER_DRAIN_SECONDS] 匀速把这一小段打完，不会瞬移。
 */
internal fun typewriterRate(lag: Float, arrivalRate: Float, drainRate: Float? = null): Float {
    if (lag <= 0f) return 0f
    if (drainRate != null) {
        return drainRate.coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_BURST_RATE)
    }
    val followRate = arrivalRate * TYPEWRITER_FOLLOW_RATIO
    val catchUpRate = lag / TYPEWRITER_SETTLE_SECONDS
    val ceiling = if (lag > TYPEWRITER_LAG_TARGET) TYPEWRITER_BURST_RATE else TYPEWRITER_MAX_RATE
    return maxOf(followRate, catchUpRate).coerceIn(TYPEWRITER_MIN_RATE, ceiling)
}

/**
 * 收尾速率（码点/秒）：把 [lag] 个剩余码点在 [TYPEWRITER_DRAIN_SECONDS] 内匀速打完。
 *
 * 取「按剩余量摊到目标时长」而不是「固定倍数追赶」，保证收尾时间是常数级（约 0.2 秒）：
 * 剩余十几字看得清是打字，剩余几百字也不会一格一格磨到用户以为卡住。
 */
internal fun typewriterDrainRate(lag: Float): Float =
    (lag / TYPEWRITER_DRAIN_SECONDS).coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_DRAIN_MAX_RATE)

/** 打字机当前渲染结果：[text] 为应渲染文本（上游全文的前缀），[settled] 表示是否已追平全文。 */
internal data class TypewriterText(val text: String, val settled: Boolean)

/**
 * 速率自适应打字机：显示文本滞后于上游累积文本，打字速度跟随模型吐字速度。
 *
 * 上游每个 delta 都携带完整累积文本，到达节奏即模型吐字节奏。此处维护两个进度：
 * 到达进度（[text] 的码点数）与显示进度（已展示的码点数）。显示进度由动画帧驱动，速率见
 * [typewriterRate]：滞后被压在 [TYPEWRITER_LAG_TARGET] 附近，不会越积越多。
 *
 * 渲染文本每 [TYPEWRITER_RENDER_INTERVAL_MS] 快照一次（throttle 而非 debounce，
 * 保证打字期间渲染持续可见增长），把 md 解析频率压在 ~10fps；text 突变（换会话 /
 * 新一轮 / 重试）时补全为当前全文，之后继续跟着 delta 打字。
 *
 * 上游结束（[active] 变 false）**不再一帧补全**：剩余那一小段按 [typewriterDrainRate] 匀速
 * 打完（约 0.2 秒，硬上限 [TYPEWRITER_DRAIN_HARD_MS] 兜底），期间 [TypewriterText.settled]
 * 保持 false，调用方据此把这段文字的渲染交棒给刚落库的助手消息（见 AIChatPanel），
 * 打完后再让落库消息完全接管——用户看不到「整段突然跳出」，也看不到同一段文字重复两份。
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
): TypewriterText {
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

        // 上游结束进入收尾阶段：不再一帧补全（那正是「结束瞬间抽搐一下」的来源），
        // 剩余那点字按恒定速率匀速打完。此时 text 不会再变，收尾期间本协程不会被打断。
        val draining = !active
        val now = System.nanoTime()
        val codePoints = text.codePointCount(0, text.length)
        // 收尾速率按「进入收尾时的剩余量」定，保证收尾时长恒定、且能精确打完（不残留小数）
        val drainRate = if (draining) {
            typewriterDrainRate((codePoints - shownCodePoints).coerceAtLeast(0f))
        } else {
            null
        }
        val drainDeadlineNanos = now + TYPEWRITER_DRAIN_HARD_MS * 1_000_000L
        if (!draining) {
            // 记录本次到达事件，裁剪速率窗口（保留最近 WINDOW 内至少 2 条）
            if (lastArrivalNanos != 0L) {
                arrivals.addLast(now to codePoints)
                val windowNanos = TYPEWRITER_RATE_WINDOW_MS * 1_000_000L
                while (arrivals.size > 2 && now - arrivals.first().first > windowNanos) {
                    arrivals.removeFirst()
                }
            }
            lastArrivalNanos = now
        }

        // 帧驱动推进显示进度，追平本次文本即退出（text 再变化时本协程被取消重启）
        var lastRenderNanos = 0L
        var lastFrameNanos = 0L
        while (shownCodePoints < codePoints) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    // 播出阶段才需要估算上游速率：收尾阶段上游已经不动了
                    var arrivalRate = 0f
                    if (!draining) {
                        val oldest = arrivals.firstOrNull()
                        if (arrivals.size >= 2 && oldest != null) {
                            val spanSec = (frameNanos - oldest.first) / 1_000_000_000f
                            if (spanSec > 0f) {
                                arrivalRate = (arrivals.last().second - oldest.second) / spanSec
                            }
                        }
                    }
                    val rate = typewriterRate(
                        lag = codePoints - shownCodePoints,
                        arrivalRate = arrivalRate,
                        drainRate = drainRate
                    )
                    shownCodePoints = (shownCodePoints + rate * dtSec)
                        .coerceAtMost(codePoints.toFloat())
                }
                lastFrameNanos = frameNanos

                // 收尾硬上限：极端情况（文本被整体替换、渲染跟不上）也必须在这之内追平，
                // 否则尾巴会一直挂在未追平状态，落库消息永远等不到交棒。
                if (draining && frameNanos >= drainDeadlineNanos) {
                    shownCodePoints = codePoints.toFloat()
                }

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
    // settled 直接由「渲染文本是否已等于上游全文」给出：收尾期间调用方据此决定渲染归属
    return TypewriterText(text = renderText, settled = renderText == text)
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
    // 与落库助手正文同构：不套容器，直接铺在页面底色上，底部挂「生成中」的点
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownContent(
            text = renderText,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            cache = cache
        )
        Spacer(Modifier.height(Spacing.xs))
        TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
    }
}

/**
 * 折叠行里显示的那一行思考内容：思考进行中取**最后一行**（跟着模型正在写的内容快速滚动），
 * 思考结束后取**第一行**（内容已定，固定成一句预览）。
 *
 * 调用方按单行 + 省略号渲染，所以这里把行首的 Markdown 记号（标题、列表、引用）与行尾的闭合
 * 记号（加粗、行内代码）一并清掉，免得折叠行里露出 `##` / `**` 这种源码记号；整行仍过长时
 * 交给省略号截断。没有任何可见内容时返回空串，调用方回落到「思考过程」文案。
 */
internal fun reasoningPreviewLine(raw: String, live: Boolean): String {
    val line = if (live) {
        // 从末尾回退跳过空白与换行，切出最后一行。不整串 trimEnd()：流式思考下这个函数每帧都跑，
        // 长思考文本的整串复制纯属浪费。
        var end = raw.length
        while (end > 0 && raw[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && raw[start - 1] != '\n') start--
        raw.substring(start, end)
    } else {
        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
    // 空格与 Markdown 记号在两端交替出现（`- **要点**`、`## 标题`），放同一个集合里一次剥干净
    return line.trim(*REASONING_PREVIEW_MARKERS)
}

/** 折叠行预览两端要剥掉的字符：空白 + Markdown 记号（标题 `#`、列表 `-` `+` `*`、引用 `>`、加粗与行内代码的 `*` `` ` ``）。 */
private val REASONING_PREVIEW_MARKERS = charArrayOf(' ', '\t', '#', '*', '-', '>', '`', '+')

/** 思考展开窗口的最大高度：超出后在窗口内滚动，不再无限撑高、拖走整页滚动。 */
private val ReasoningWindowMaxHeight = 300.dp

/** 思考窗口底部渐隐遮罩的高度（仅当内容还可继续下滚时显示）。 */
private val ReasoningWindowFadeHeight = 28.dp

/**
 * 将超长思考文本按块切分，供展开时惰性渲染。
 * 优先在换行处切；单块目标长度适中，避免单个 MarkdownContent 解析过大文本造成主线程卡顿。
 */
private fun chunkReasoningText(text: String, chunkSize: Int = 4_000): List<String> {
    if (text.length <= chunkSize) return listOf(text)
    val result = ArrayList<String>((text.length / chunkSize) + 1)
    var start = 0
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end)
            if (newline > start) end = newline + 1
        }
        result.add(text.substring(start, end))
        start = end
    }
    return result
}

/**
 * 思考过程折叠行：左对齐、浅色弱化，与正式回复区分。**默认收起**，点这一行随时展开/收起。
 *
 * 收起态只占一行：行首思考图标 + 一行内容预览（见 [reasoningPreviewLine]，超宽省略号截断）；
 * 展开态换成完整正文（Markdown 渲染）。两态之间没有「显示尾巴几行」的中间态。
 *
 * [live] 表示思考仍在进行中：折叠行的预览取最后一行、跟着内容滚动，不展开也能看到模型在想什么；
 * 思考结束后预览固定为第一行。
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    showTimer: Boolean = false,
    /** 已完成思考的总耗时（毫秒）。非空且大于 0 时，折叠行文案展示精确耗时。 */
    durationMs: Long? = null,
    timerStartMs: Long? = null,
    /** 文本已由外部打字机驱动（流式尾巴场景），跳过内部防抖直接渲染。 */
    preRendered: Boolean = false,
    /** 思考所属会话：切会话时重新计时，否则会拿上一个会话的起点算出离谱的时长。 */
    sessionKey: String? = null,
    /** 思考仍在进行中：折叠行的预览取最后一行（跟着滚动），见上方 KDoc。 */
    live: Boolean = false,
    /** 展开态的外部覆盖（宿主持久化，见 [AIAgentViewModel.reasoningExpansionOverrides]）；null = 用内部状态。 */
    expandedOverride: Boolean? = null,
    /** 手动展开/收起时回传新状态，由上层持久化。 */
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var localExpanded by remember { mutableStateOf(false) }
    val expanded = expandedOverride ?: localExpanded
    val toggleExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) onExpandedChange(next) else localExpanded = next
    }
    // 存绝对起始时间戳而非累加
    // 毫秒数，切页返回或气泡滚出视口重挂载后显示的仍是真实时长；起始戳连同已见文本的长度与
    // 指纹一起进 saveable，恢复时文本若不是同一轮的延续（期间已换轮）则重新计时。
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(showTimer, live, sessionKey, timerStartMs) {
        if (!showTimer || !live) return@LaunchedEffect
        if (timerStartMs != null) {
            timerStartMillis = timerStartMs
        } else if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - timerStartMillis).coerceAtLeast(0L)
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(100)
        }
    }
    // 展开渲染用节流文本（流式思考时降低 md 解析频率）；preRendered 时外部已按打字机节奏给出渲染文本。
    val renderText = if (preRendered) text else rememberThrottledStreamingText(text)
    val effectiveDurationMs = durationMs?.takeIf { it > 0 } ?: elapsedMillis.takeIf { it > 0 }
    val formattedDuration = remember(effectiveDurationMs, live) {
        effectiveDurationMs?.let { ms ->
            val sec = (ms / 1000.0).coerceAtLeast(0.1)
            String.format(java.util.Locale.US, "%.1f", sec)
        }
    }
    // 折叠行文案：进行中显示实时耗时，完成后显示总耗时（拿不到耗时数据时只显示「思考完成」）。
    val label = when {
        live -> {
            val secText = formattedDuration ?: "0.1"
            stringResource(R.string.chat_thinking_running_time, secText)
        }
        formattedDuration != null -> stringResource(R.string.chat_thinking_done_time, formattedDuration)
        else -> stringResource(R.string.chat_thinking_done)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .clickable { toggleExpanded(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkingGlyph(tint = Brand.IconGray, iconSize = 16.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.xs))
            ExpandableChevronIcon(
                expanded = expanded,
                contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                tint = Brand.IconGray,
                size = 18.dp
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(Spacing.sm))
                // 超长思考文本分块惰性渲染：避免一次性把巨量 Markdown 交给解析器造成主线程卡死/崩溃。
                val chunks = remember(renderText) { chunkReasoningText(renderText) }
                val listState = rememberLazyListState()
                val fadeColor = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = ReasoningWindowMaxHeight)
                            .pointerInput(text) {
                                detectTapGestures(
                                    onDoubleTap = { toggleExpanded(false) }
                                )
                            }
                    ) {
                        items(chunks.size) { index ->
                            MarkdownContent(
                                text = chunks[index],
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                cache = cache,
                                compact = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    // 底部渐隐：只有还能继续往下滚时才盖一层，提示“下面还有内容”
                    if (listState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(ReasoningWindowFadeHeight)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
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
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    /** 是否单独给读屏播报「正在生成」：嵌在 [AgentBusyIndicator] 里时由外层状态行统一播报。 */
    announce: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val label = stringResource(R.string.chat_status_generating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 给读屏一个语义：三个跳动的点对 TalkBack 本来完全不可见。
        modifier = Modifier
            .height(dotSize + 10.dp)
            .then(
                if (announce) Modifier.semantics { contentDescription = label } else Modifier
            )
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
