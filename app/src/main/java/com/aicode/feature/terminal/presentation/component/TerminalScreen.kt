package com.aicode.feature.terminal.presentation.component

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.domain.RunState
import com.aicode.feature.terminal.domain.TerminalTab
import com.aicode.feature.terminal.domain.font.TerminalFontManager
import com.aicode.feature.terminal.domain.model.TerminalThemePreset
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val prepareState by viewModel.prepareState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val allTabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val creatingTab by viewModel.creatingTab.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    val tabs = remember(allTabs, terminalSettings.showPluginTabs) {
        if (terminalSettings.showPluginTabs) allTabs else allTabs.filter { it.ownerPluginId == null }
    }
    val visibleActiveTabId = activeTabId?.takeIf { id -> tabs.any { it.id == id } }
        ?: tabs.firstOrNull()?.id
    // 隐藏插件标签时 active 可能仍指向它；先切回可见标签，避免输入落到看不见的标签。
    LaunchedEffect(visibleActiveTabId, activeTabId) {
        if (visibleActiveTabId != null && visibleActiveTabId != activeTabId) {
            viewModel.activate(visibleActiveTabId)
        }
    }
    var showToolsSheet by remember { mutableStateOf(false) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgArgb = MaterialTheme.colorScheme.background.toArgb()
    val fgArgb = MaterialTheme.colorScheme.onBackground.toArgb()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val effectiveTheme = remember(terminalSettings.theme, isDark, bgArgb, fgArgb, primaryArgb) {
        TerminalThemePreset.resolve(
            preset = terminalSettings.theme,
            isDark = isDark,
            backgroundArgb = bgArgb,
            foregroundArgb = fgArgb,
            cursorArgb = primaryArgb
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(
                                if (embedded) R.string.common_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showToolsSheet = true }) {
                        Icon(FeatherIcons.Settings, contentDescription = stringResource(R.string.terminal_settings_title))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = rememberImeBottomInset())
        ) {
            when (val state = prepareState) {
                is TerminalViewModel.PrepareState.Loading -> StatusView(
                    loading = true,
                    message = containerInitMessage(context, containerInit)
                )

                is TerminalViewModel.PrepareState.Error -> StatusView(
                    loading = false,
                    message = stringResource(R.string.terminal_start_failed, state.message),
                    actionLabel = stringResource(R.string.terminal_retry),
                    onAction = { viewModel.prepare() }
                )

                is TerminalViewModel.PrepareState.Ready -> {
                    @Suppress("UNUSED_EXPRESSION") revision

                    TabBar(
                        tabs = tabs,
                        activeTabId = visibleActiveTabId,
                        creating = creatingTab,
                        onSelect = { viewModel.activate(it) },
                        onClose = { viewModel.closeTab(it) },
                        onNew = { viewModel.newTab() }
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val active = tabs.firstOrNull { it.id == visibleActiveTabId }
                        if (active == null) {
                            StatusView(
                                loading = creatingTab,
                                message = stringResource(R.string.terminal_no_open_tabs),
                                actionLabel = stringResource(R.string.common_new_tab),
                                onAction = { viewModel.newTab() }
                            )
                        } else {
                            key(active.id) {
                                TerminalSurface(
                                    tab = active,
                                    viewModel = viewModel,
                                    settings = terminalSettings,
                                    effectiveTheme = effectiveTheme
                                )
                            }
                        }
                    }

                    if (visibleActiveTabId != null) {
                        ExtraKeysRow(viewModel)
                    }
                }
            }
        }

        if (showToolsSheet) {
            TerminalSettingsSheet(
                settings = terminalSettings,
                onDismiss = { showToolsSheet = false },
                onSelectTheme = { viewModel.setTheme(it) },
                onChangeFontSize = { viewModel.setFontSize(it) },
                onChangeCursorStyle = { viewModel.setCursorStyle(it) },
                onChangeFontPath = { viewModel.setFontPath(it) },
                onChangeShowPluginTabs = { viewModel.setShowPluginTabs(it) }
            )
        }
    }
}

/** 可横滑的标签栏：每个标签显示状态点 + 标题 + 关闭；末尾「+」新建。激活标签变化时自动滚动，让当前标签可见。 */
@Composable
private fun TabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    creating: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    val scrollState = rememberScrollState()
    val tabBounds = remember { mutableStateMapOf<String, Rect>() }
    var barWidth by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeTabId, barWidth) {
        val id = activeTabId ?: return@LaunchedEffect
        if (barWidth <= 0) return@LaunchedEffect
        val bounds = snapshotFlow { tabBounds[id] }.filterNotNull().first()
        val left = bounds.left.toInt()
        val right = bounds.right.toInt()
        val visibleStart = scrollState.value
        val visibleEnd = visibleStart + barWidth
        val target = when {
            left < visibleStart -> left
            right > visibleEnd -> right - barWidth
            else -> return@LaunchedEffect
        }
        scrollState.animateScrollTo(target.coerceAtLeast(0))
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { barWidth = it.width }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    selected = tab.id == activeTabId,
                    onClick = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                    modifier = Modifier.onGloballyPositioned { tabBounds[tab.id] = it.boundsInParent() }
                )
            }
            // SSH 模式下新建需等服务器响应，等待期间显示转圈并禁用按钮，避免网络卡顿时重复点击连开多个标签。
            IconButton(onClick = onNew, enabled = !creating, modifier = Modifier.size(32.dp)) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.common_new_tab),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    tab: TerminalTab,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = tab.runState is RunState.Running
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val dot = when {
        running -> MaterialTheme.semanticColors.success // 鲜明活跃绿
        tab.isBackground -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    // 选中态高对比度设计：醒目主色描边 + 浅主色填充 + 加粗文字
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = if (isLight) 0.14f else 0.22f)
        else -> MaterialTheme.semanticColors.capsuleSurface
    }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.subtleBorder
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.primary
        isLight -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(start = Spacing.md, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 运行状态指示点（激活且运行时带微发光外圈）
        Box(
            modifier = Modifier
                .size(if (selected && running) 10.dp else 8.dp)
                .clip(CircleShape)
                .background(dot)
                .then(
                    if (selected && running) Modifier.border(1.5.dp, dot.copy(alpha = 0.4f), CircleShape)
                    else Modifier
                )
        )
        Text(
            text = tab.title,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.terminal_close_tab),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun applyTerminalColors(
    view: TerminalView,
    tab: TerminalTab,
    settings: TerminalSettings,
    effectiveTheme: TerminalThemePreset = settings.theme
) {
    val preset = effectiveTheme
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
    for (i in 0 until minOf(16, preset.ansiColors.size)) {
        TerminalColors.COLOR_SCHEME.mDefaultColors[i] = preset.ansiColors[i]
    }

    tab.session.emulator?.mColors?.let { colors ->
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
        colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
        colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
        for (i in 0 until minOf(16, preset.ansiColors.size)) {
            colors.mCurrentColors[i] = preset.ansiColors[i]
        }
    }

    (tab.client as? AppTerminalSessionClient)?.cursorStyle = settings.cursorStyle
    tab.session.emulator?.setCursorStyle()

    view.onScreenUpdated()
    view.invalidate()
}

/** Termux TerminalView 的 Compose 包装：渲染与输入全部由该开源组件负责。 */
@Composable
private fun TerminalSurface(
    tab: TerminalTab,
    viewModel: TerminalViewModel,
    settings: TerminalSettings,
    effectiveTheme: TerminalThemePreset = settings.theme
) {
    val preset = effectiveTheme
    val bgColor = Color(preset.background)
    // 字号单一事实源：捏合缩放只改这里（预览），手势结束才落盘；设置面板的改动反向同步进来。
    var liveFontSizeSp by remember { mutableIntStateOf(settings.fontSizeSp) }
    LaunchedEffect(settings.fontSizeSp) { liveFontSizeSp = settings.fontSizeSp }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        factory = { ctx ->
            val view = TerminalView(ctx, null)
            view.setBackgroundColor(preset.background)
            val density = ctx.resources.displayMetrics.density
            view.setTextSize((liveFontSizeSp * density).toInt())
            view.setTypeface(TerminalFontManager.loadTypeface(ctx, settings.fontPath) ?: Typeface.MONOSPACE)
            view.tag = (liveFontSizeSp * density).toInt() to settings.fontPath
            view.setTerminalViewClient(
                AppTerminalViewClient(
                    context = ctx,
                    viewProvider = { view },
                    modifiers = viewModel.modifiers,
                    currentFontSizeSp = { liveFontSizeSp },
                    onFontSizeCommit = { sp ->
                        liveFontSizeSp = sp
                        viewModel.setFontSize(sp)
                    }
                )
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            tab.view = view
            view.attachSession(tab.session)

            applyTerminalColors(view, tab, settings, effectiveTheme)
            view.requestFocus()
            view
        },
        update = { view ->
            view.setBackgroundColor(preset.background)
            val density = view.context.resources.displayMetrics.density
            val targetTextSize = (liveFontSizeSp * density).toInt()
            val targetFontKey = targetTextSize to settings.fontPath
            if (view.tag != targetFontKey) {
                view.setTextSize(targetTextSize)
                view.setTypeface(TerminalFontManager.loadTypeface(view.context, settings.fontPath) ?: Typeface.MONOSPACE)
                view.tag = targetFontKey
            }

            applyTerminalColors(view, tab, settings, effectiveTheme)
        },
        onRelease = { view ->
            if (tab.view === view) tab.view = null
        }
    )
}

@Composable
private fun StatusView(
    loading: Boolean,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(Spacing.md))
        }
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 把容器初始化进度状态映射为 Loading 阶段展示给用户的文案。 */
private fun containerInitMessage(context: Context, state: ContainerInitState): String = when (state) {
    is ContainerInitState.CleaningOldRootfs ->
        context.getString(R.string.terminal_cleaning_old_container, state.processed)
    is ContainerInitState.ExtractingRootfs ->
        context.getString(R.string.terminal_extracting_env, state.processed)
    is ContainerInitState.InstallingPackages ->
        context.getString(R.string.terminal_installing_packages, state.line ?: "")
    is ContainerInitState.Failed ->
        context.getString(R.string.terminal_preparing_env_failed, state.reason)
    ContainerInitState.Idle, ContainerInitState.Ready ->
        context.getString(R.string.terminal_preparing_env_first_run)
}

/** Termius 风格的现代化极客辅助按键栏。 */
@Composable
private fun ExtraKeysRow(viewModel: TerminalViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 控制键
            KeyChip("ESC") { viewModel.write("\u001b") }
            KeyChip("TAB") { viewModel.write("\t") }
            KeyChip("CTRL", active = viewModel.modifiers.ctrl) {
                viewModel.modifiers.ctrl = !viewModel.modifiers.ctrl
            }
            KeyChip("ALT", active = viewModel.modifiers.alt) {
                viewModel.modifiers.alt = !viewModel.modifiers.alt
            }

            // 方向键
            KeyChip("←", repeatOnHold = true) { viewModel.write("\u001b[D") }
            KeyChip("↑", repeatOnHold = true) { viewModel.write("\u001b[A") }
            KeyChip("↓", repeatOnHold = true) { viewModel.write("\u001b[B") }
            KeyChip("→", repeatOnHold = true) { viewModel.write("\u001b[C") }

            // 快捷控制组合
            KeyChip("C-c") { viewModel.writeBytes(0x03) }
            KeyChip("C-d") { viewModel.writeBytes(0x04) }
            KeyChip("C-z") { viewModel.writeBytes(0x1A) }
            KeyChip("C-l") { viewModel.writeBytes(0x0C) }

            // 常用编程与 shell 符号
            KeyChip("|") { viewModel.write("|") }
            KeyChip("~") { viewModel.write("~") }
            KeyChip("/") { viewModel.write("/") }
            KeyChip("-") { viewModel.write("-") }
            KeyChip("_") { viewModel.write("_") }
            KeyChip(">") { viewModel.write(">") }
            KeyChip(":") { viewModel.write(":") }
            KeyChip("$") { viewModel.write("$") }
            KeyChip("\"") { viewModel.write("\"") }
            KeyChip("'") { viewModel.write("'") }
        }
    }
}

/** 方向键长按重复节奏：按下后延迟多久开始连续发送，以及每次发送的间隔。 */
private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
private const val KEY_REPEAT_INTERVAL_MS = 50L

@Composable
private fun KeyChip(
    label: String,
    active: Boolean = false,
    repeatOnHold: Boolean = false,
    onClick: () -> Unit
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.capsuleSurface
    }
    val borderColor = when {
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.subtleBorder
    }
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    var pressed by remember { mutableStateOf(false) }
    var sentRepeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        sentRepeated = false
        delay(KEY_REPEAT_INITIAL_DELAY_MS)
        while (pressed) {
            onClick()
            sentRepeated = true
            delay(KEY_REPEAT_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .widthChip(label)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .then(
                if (repeatOnHold) {
                    Modifier.pointerInput(onClick) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            waitForUpOrCancellation()
                            pressed = false
                            if (!sentRepeated) onClick()
                        }
                    }
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/** 单字符按键给固定宽度，多字符按键自适应。 */
private fun Modifier.widthChip(label: String): Modifier =
    if (label.length <= 1) this.width(36.dp) else this
