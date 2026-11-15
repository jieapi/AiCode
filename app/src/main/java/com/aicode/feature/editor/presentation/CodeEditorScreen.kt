package com.aicode.feature.editor.presentation

import android.graphics.Typeface
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.util.LineDiff
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.editor.presentation.component.MarkdownPreviewWebView
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.presentation.PluginEntryContext
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Save
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs

/**
 * 待确认的离开动作：确认保存后执行。
 *
 * [confirmLabelRes] 是未保存提示里确认键的文案：关编辑页是「保存并退出」，
 * 大屏右栏换个文件接着看是「保存并切换」。
 */
private data class PendingLeave(val confirmLabelRes: Int, val action: () -> Unit)

/**
 * 独立全屏代码编辑页。支持语法高亮、撤销/重做、底部符号快捷栏与保存。
 * 保存直接覆盖写回，不做外部并发写检测——AI 工具或终端可能同时改同一文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    path: String,
    onBack: () -> Unit,
    initialLine: Int = 0,
    embedded: Boolean = false,
    leaveGuard: EditorLeaveGuard? = null,
    pluginEntries: List<PluginUiEntry> = emptyList(),
    pluginLanguageTag: String = "zh-CN",
    onPluginEntry: (PluginUiEntry, PluginEntryContext) -> Unit = { _, _ -> },
    viewModel: CodeEditorViewModel = hiltViewModel()
) {
    LaunchedEffect(path) { viewModel.load(path) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    // 脏标记的 State 单独留一份引用：注册给宿主的回调要读「调用时」的值，不能捕获布尔快照。
    val dirtyState = remember { mutableStateOf(false) }
    var dirty by dirtyState
    // 已保存（或刚加载）的内容。脏标记按与它的差异判定，撤销回原样时才能自行复位。
    val baselineText = remember { mutableStateOf("") }
    var pendingSaveText by remember { mutableStateOf("") }
    // 待确认的离开动作（关页面 / 大屏右栏换文件），保存成功后执行。
    var pendingLeave by remember { mutableStateOf<PendingLeave?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    val isMarkdown = remember(path) {
        path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    var editorBackground by remember { mutableStateOf<Color?>(null) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    // 插件槽位上下文：文件路径 + 当前选区；没有选中文本时不传 selection。
    val buildPluginContext: () -> PluginEntryContext = {
        val editor = editorRef.value
        val cursor = editor?.cursor
        val left = cursor?.left()
        val right = cursor?.right()
        val selected = if (cursor != null && cursor.isSelected && left != null && right != null) {
            val from = minOf(left.index, right.index)
            val to = minOf(maxOf(left.index, right.index), from + MAX_PLUGIN_SELECTION_CHARS)
            runCatching { editor.text.substring(from, to) }.getOrNull()
        } else {
            null
        }
        PluginEntryContext(
            path = path,
            selection = selected,
            language = path.substringAfterLast('.', "").ifBlank { null },
            lineStart = if (selected != null && left != null && right != null) {
                minOf(left.line, right.line) + 1
            } else null,
            lineEnd = if (selected != null && left != null && right != null) {
                maxOf(left.line, right.line) + 1
            } else null
        )
    }

    // 把插件动作挂到选中文本的浮动菜单里；入口与回调随组合更新。
    val latestPluginEntries = rememberUpdatedState(pluginEntries)
    val latestPluginLanguage = rememberUpdatedState(pluginLanguageTag)
    val latestOnPluginEntry = rememberUpdatedState(onPluginEntry)
    val latestBuildPluginContext = rememberUpdatedState(buildPluginContext)
    LaunchedEffect(editorRef.value) {
        val editor = editorRef.value ?: return@LaunchedEffect
        editor.replaceComponent(
            EditorTextActionWindow::class.java,
            PluginTextActionWindow(
                hostEditor = editor,
                entriesProvider = { latestPluginEntries.value },
                languageTagProvider = { latestPluginLanguage.value },
                contextProvider = { latestBuildPluginContext.value() },
                onEntry = { entry, context -> latestOnPluginEntry.value(entry, context) }
            )
        )
    }

    // 切换文件时重置编辑态，避免旧文件的撤销/脏标记残留到新文件。
    LaunchedEffect(path) {
        canUndo = false
        canRedo = false
        dirty = false
        pendingLeave = null
        cursorLine = 1
        cursorColumn = 1
        previewMode = false
    }

    // 大屏右栏宿主（点文件树 / 点聊天里的路径链接）要换文件时，先过这里：无脏数据直接放行，
    // 有脏数据复用同一套保存提示，确认后再换，避免静默丢弃未保存的修改。
    LaunchedEffect(leaveGuard) {
        leaveGuard?.register { onLeave ->
            if (!dirtyState.value) {
                onLeave()
            } else {
                pendingLeave = PendingLeave(R.string.editor_save_and_switch, onLeave)
                showUnsavedDialog = true
            }
        }
    }
    // 脏状态实时上报给宿主。
    LaunchedEffect(leaveGuard, dirty) { leaveGuard?.isDirty = dirtyState.value }
    // 离开组合（右栏切到终端、收起等）时撤掉上报与处理，不拿旧状态拦人。
    DisposableEffect(leaveGuard) {
        onDispose {
            leaveGuard?.register(null)
            leaveGuard?.isDirty = false
        }
    }

    val context = LocalContext.current
    val savedText = stringResource(R.string.editor_save_success)
    val saveFailedText = stringResource(R.string.editor_save_failed)
    val conflictDiscardedText = stringResource(R.string.editor_save_conflict_discarded)
    // 保存冲突：磁盘内容在编辑器打开之后被改过。非空时弹对话框让用户选，不自动覆盖也不自动丢弃。
    var saveConflictDiskContent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { result ->
            when (result) {
                is SaveResult.Success -> {
                    baselineText.value = pendingSaveText
                    dirty = false
                    Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                    pendingLeave?.let { leave ->
                        pendingLeave = null
                        leave.action()
                    }
                }
                is SaveResult.Conflict -> {
                    // 冲突时放弃"保存并退出"的意图：先让用户决定怎么处理这次冲突。
                    // rc5 起 pendingExit 已被 pendingLeave（+ EditorLeaveGuard）取代，这里跟着换成它。
                    pendingLeave = null
                    saveConflictDiskContent = result.diskContent
                }
                is SaveResult.Error -> {
                    pendingLeave = null
                    Toast.makeText(
                        context,
                        result.detail ?: saveFailedText,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun requestSave() {
        val editor = editorRef.value ?: return
        pendingSaveText = editor.text.toString()
        viewModel.save(pendingSaveText)
    }

    fun handleBack() {
        if (dirty) {
            pendingLeave = PendingLeave(R.string.editor_save_and_exit) { onBack() }
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = !showSettings && !previewMode) { handleBack() }
    BackHandler(enabled = showSettings) { showSettings = false }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(
                                    if (embedded) R.string.common_close else R.string.common_back
                                )
                            )
                        }
                    },
                    actions = {
                        val editable = state is EditorUiState.Success
                        if (isMarkdown && editable) {
                            IconButton(onClick = { previewMode = !previewMode }) {
                                Icon(
                                    if (previewMode) FeatherIcons.Code else FeatherIcons.Eye,
                                    contentDescription = stringResource(
                                        if (previewMode) R.string.editor_md_show_source
                                        else R.string.editor_md_preview
                                    )
                                )
                            }
                        }
                        IconButton(
                            onClick = { editorRef.value?.undo() },
                            enabled = editable && canUndo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.editor_undo)
                            )
                        }
                        IconButton(
                            onClick = { editorRef.value?.redo() },
                            enabled = editable && canRedo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = stringResource(R.string.editor_redo)
                            )
                        }
                        IconButton(
                            onClick = { requestSave() },
                            enabled = editable && dirty && !saving
                        ) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    FeatherIcons.Save,
                                    contentDescription = stringResource(R.string.common_save)
                                )
                            }
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            enabled = editable
                        ) {
                            Icon(
                                FeatherIcons.Settings,
                                contentDescription = stringResource(R.string.editor_settings)
                            )
                        }
                    }
                )
                HorizontalDivider(thickness = 0.5.dp)
                FileTitleBar(
                    fileName = path.substringAfterLast('/'),
                    dirty = dirty,
                    line = cursorLine,
                    column = cursorColumn
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (state is EditorUiState.Success && !previewMode) {
                EditorSymbolBar(
                    backgroundColor = editorBackground ?: MaterialTheme.colorScheme.background,
                    onInsert = { editorRef.value?.commitText(it) },
                    onIndent = { editorRef.value?.commitText("\t") },
                    onMoveLeft = { editorRef.value?.let { moveCursorHorizontally(it, forward = false) } },
                    onMoveRight = { editorRef.value?.let { moveCursorHorizontally(it, forward = true) } }
                )
            }
        }
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        when (val s = state) {
            is EditorUiState.Loading -> CenterBox(content) { CircularProgressIndicator() }
            is EditorUiState.Success -> Box(modifier = content) {
                // 文档身份作 key：窄窗全屏路由每次导航都是新组合，编辑器自然重建；
                // 但大屏右栏常驻在聊天页里，切换文件时这个组合位置不变，AndroidView 的 factory
                // 不会重跑，会一直显示上一个文件的内容、沿用上一个文件的语法语言。
                // 这里按加载出的文档（内容 + 语法）重建编辑器，与窄窗行为对齐。
                // 只按内容/语法而非路径作 key：路径先变、内容后到，按路径会先用旧内容白建一次。
                key(s.content, s.scopeName) {
                    EditorSurface(
                        state = s,
                        modifier = Modifier.fillMaxSize(),
                        editorRef = editorRef,
                        settings = settings,
                        baselineText = baselineText,
                        initialLine = initialLine,
                        onBackgroundResolved = { editorBackground = it },
                        onCursorChanged = { l, c ->
                            cursorLine = l
                            cursorColumn = c
                        },
                        onContentChanged = { undo, redo, changed ->
                            canUndo = undo
                            canRedo = redo
                            dirty = changed
                        }
                    )
                }
                if (previewMode) {
                    // 预览覆盖在编辑器上方（编辑器保留在组合中不销毁，切回源码不丢状态）。
                    // 进入预览时快照编辑器当前文本，确保反映未保存的编辑。
                    val previewText = remember(previewMode) {
                        editorRef.value?.text?.toString() ?: s.content
                    }
                    MarkdownPreviewWebView(
                        text = previewText,
                        path = path,
                        onExitPreview = { previewMode = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
            is EditorUiState.TooLarge -> CenterBox(content) {
                HintText(
                    stringResource(
                        R.string.editor_file_too_large,
                        s.sizeBytes / 1024 / 1024
                    )
                )
            }
            is EditorUiState.Binary -> CenterBox(content) {
                HintText(stringResource(R.string.editor_binary_unsupported))
            }
            is EditorUiState.Error -> CenterBox(content) {
                HintText(s.detail ?: stringResource(R.string.editor_load_failed))
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = {
                // 取消：丢掉待执行的离开动作，留在当前文件。
                showUnsavedDialog = false
                pendingLeave = null
            },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    requestSave()
                }) { Text(stringResource(pendingLeave?.confirmLabelRes ?: R.string.editor_save_and_exit)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    pendingLeave?.let { leave ->
                        pendingLeave = null
                        leave.action()
                    }
                }) { Text(stringResource(R.string.editor_dont_save)) }
            }
        )
    }

    saveConflictDiskContent?.let { diskContent ->
        AlertDialog(
            onDismissRequest = { saveConflictDiskContent = null },
            title = { Text(stringResource(R.string.editor_save_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.editor_save_conflict_message))
                    // 差异按「磁盘 → 你的编辑」方向渲染：`-` 是磁盘现有内容，`+` 是你的修改。
                    val diff = remember(diskContent, pendingSaveText) {
                        LineDiff.toUnified(diskContent, pendingSaveText)
                    }
                    Text(
                        text = stringResource(R.string.editor_save_conflict_diff, diff),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = Spacing.sm)
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveConflictDiskContent = null
                    viewModel.saveForce(pendingSaveText)
                }) { Text(stringResource(R.string.editor_save_conflict_overwrite)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { saveConflictDiskContent = null }) {
                        Text(stringResource(R.string.editor_save_conflict_cancel))
                    }
                    TextButton(onClick = {
                        saveConflictDiskContent = null
                        viewModel.reloadFromDisk()
                        Toast.makeText(context, conflictDiscardedText, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.editor_save_conflict_discard)) }
                }
            }
        )
    }

    if (showSettings) {
        EditorSettingsScreen(onBack = { showSettings = false })
    }
    }
}

/** 导航栏下方的文件名小栏：左侧文件名（未保存时名前加星号），右侧行:列与文件编码。 */
@Composable
private fun FileTitleBar(fileName: String, dirty: Boolean, line: Int, column: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(FILE_TITLE_BAR_HEIGHT)
            .padding(horizontal = FILE_TITLE_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dirty) {
            Text(
                text = "*",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$line:$column",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
        Text(
            text = FILE_ENCODING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )
    }
}

@Composable
private fun EditorSurface(
    state: EditorUiState.Success,
    modifier: Modifier,
    editorRef: MutableState<CodeEditor?>,
    settings: EditorSettings,
    baselineText: MutableState<String>,
    initialLine: Int = 0,
    onBackgroundResolved: (Color) -> Unit,
    onCursorChanged: (line: Int, column: Int) -> Unit,
    onContentChanged: (canUndo: Boolean, canRedo: Boolean, dirty: Boolean) -> Unit
) {
    // 与实际渲染出的 Compose 主题保持一致，而非跟随系统设置——应用内可单独切换主题。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeSurface = MaterialTheme.colorScheme.surface
    // sora 的语法分析在后台线程跑，首帧必然还没上色。用一次渐显护住这段窗口，
    // 把「先纯文本后突然上色」的跳变变成内容渐现。
    var revealed by remember(state.content) { mutableStateOf(false) }
    LaunchedEffect(state.content) { revealed = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = HIGHLIGHT_REVEAL_MS),
        label = "editor-reveal"
    )

    // 用户自己滚过之后就不再自动定位到目标行。View 只创建一次，标记要用实例稳定的 State
    // 承载（factory 闭包捕获的是首次那个实例），换文件时由下面的 effect 显式复位。
    val scrolledByUser = remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = contentAlpha },
        factory = { ctx ->
            TextMateSetup.applyTheme(dark)
            // sora 默认把 8 秒内的连续输入并成一条撤销记录，一次撤销会吞掉整段输入。
            UndoManager.setMergeTimeLimit(UNDO_MERGE_WINDOW_MS)
            CodeEditor(ctx).apply {
                // sora 的 getScrollMaxY() 在 LayoutParams 为空或高度是 WRAP_CONTENT 时会跳过
                // verticalExtraSpaceFactor，底部额外滚动空间（下面那行设的半屏留白）就没了。
                // Compose 的 AndroidView 添加子 View 时用的正是默认 WRAP_CONTENT，必须显式改掉。
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = true
                typefaceText = Typeface.MONOSPACE
                setTextSize(settings.fontSizeSp.toFloat())
                setWordwrap(settings.wordWrap)
                // 关闭光标移动动画：切换行/列时当前行高亮原位消失、目标位出现，不逐行滑动。
                isCursorAnimationEnabled = false
                // 行号左侧固定预留一点间距，避免数字贴边。
                setLineNumberMarginLeft(spToPx(ctx, LINE_NUMBER_MARGIN_SP))
                // 滚到底后仍可再上滑一段：额外视口空间为半屏（sora 默认值，显式固定）。
                verticalExtraSpaceFactor = VERTICAL_EXTRA_SPACE_FACTOR
                isBlockLineEnabled = settings.showIndentGuide
                nonPrintablePaintingFlags =
                    nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                state.scopeName?.let { scope ->
                    setEditorLanguage(TextMateLanguage.create(scope, false))
                }
                // 先 setText 再订阅：初始设置不计入脏标记，只有用户后续编辑才触发。
                setText(state.content)
                val editorView = this
                subscribeAlways(ContentChangeEvent::class.java) {
                    // UndoManager 先改文本、后移动栈指针，事件回调里同帧读 canUndo/canRedo 拿到的
                    // 是移动前的旧值（撤销后重做键不亮、撤销到底后撤销键仍亮），延后一帧才准。
                    editorView.post {
                        val baseline = baselineText.value
                        val text = editorView.text
                        onContentChanged(
                            editorView.canUndo(),
                            editorView.canRedo(),
                            text.length != baseline.length || text.toString() != baseline
                        )
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) {
                    onCursorChanged(cursor.leftLine + 1, cursor.leftColumn + 1)
                }
                subscribeAlways(ScrollEvent::class.java) { event ->
                    // sora 在尺寸变化后会经 touchHandler 做一次滚动 clamp，事件 cause 同样是
                    // CAUSE_USER_DRAG 但首尾坐标相同（y=0->0）。编辑器刚打开就会连发几条这种
                    // 零位移事件，只看 cause 会把它们当成「用户已手动滚动」而放弃行号定位。
                    val moved = event.startY != event.endY || event.startX != event.endX
                    if (moved &&
                        (event.cause == ScrollEvent.CAUSE_USER_DRAG ||
                            event.cause == ScrollEvent.CAUSE_USER_FLING)
                    ) {
                        scrolledByUser.value = true
                    }
                }
                editorRef.value = this
            }
        },
        onRelease = {
            editorRef.value = null
            it.release()
        }
    )

    LaunchedEffect(state.content) { baselineText.value = state.content }

    // 深浅主题切换时重设配色，而非重建编辑器——保住未保存内容与撤销栈。
    val editor = editorRef.value
    LaunchedEffect(dark, themeSurface, editor) {
        editor ?: return@LaunchedEffect
        TextMateSetup.applyTheme(dark)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        applyThemedBackground(editor, themeSurface)
        applyLineNumberBackground(editor, dark)
        editor.invalidate()
        onBackgroundResolved(Color(editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)))
    }
    LaunchedEffect(settings.fontSizeSp, editor) {
        editor?.setTextSize(settings.fontSizeSp.toFloat())
    }
    LaunchedEffect(settings.wordWrap, editor) {
        editor?.setWordwrap(settings.wordWrap)
    }
    // 从聊天区链接带行号打开时跳到目标行并滚动到可视区（行号 1 基，sora 为 0 基）。
    //
    // 必须等编辑器完成首次布局：ensurePositionVisible 按 getHeight() 算滚动量，而 onSizeChanged
    // 会按新尺寸 clamp 滚动偏移、自动换行下还会重建整个布局——宽高仍为 0 时跳的那一下会被随后的
    // 首次布局抹掉，表现就是「停在文件开头没跳过去」。
    //
    // 字号与自动换行读自 DataStore，真实值往往比首帧晚到，setTextSize / setWordwrap 同样重建布局
    // 打乱位置，所以它们也进 key 重新定位一次；用户自己滚动过之后（scrolledByUser）不再拽回目标行。
    LaunchedEffect(state.content, initialLine) { scrolledByUser.value = false }
    LaunchedEffect(editor, initialLine, state.content, settings.wordWrap, settings.fontSizeSp) {
        val editorView = editor ?: return@LaunchedEffect
        if (initialLine <= 0 || scrolledByUser.value) return@LaunchedEffect
        var frames = 0
        while ((editorView.width == 0 || editorView.height == 0) &&
            frames < LINE_JUMP_LAYOUT_WAIT_FRAMES
        ) {
            withFrameNanos { }
            frames++
        }
        if (editorView.width == 0 || editorView.height == 0 || scrolledByUser.value) {
            return@LaunchedEffect
        }
        val line = (initialLine - 1).coerceIn(0, (editorView.lineCount - 1).coerceAtLeast(0))
        // 定位后校验目标行是否真的停在期望位置，没停上就重试：布局重建（设置迟到、语法
        // 分析完成、自动换行重排）会把刚定好的位置冲掉，单次定位不够。
        var attempt = 0
        while (attempt < LINE_JUMP_MAX_ATTEMPTS && !scrolledByUser.value) {
            editorView.setSelection(line, 0)
            val desired = centerScrollOffset(editorView, line)
            if (desired != null) {
                scrollVerticallyTo(editorView, desired)
            } else {
                // 拿不到布局信息时退回官方的「仅保证可见」，无动画避免被布局重建打断在半路。
                editorView.ensurePositionVisible(line, 0, true)
            }
            repeat(LINE_JUMP_VERIFY_FRAMES) { withFrameNanos { } }
            val settled = if (desired != null) {
                abs(editorView.offsetY - desired) <= editorView.rowHeight
            } else {
                runCatching {
                    val layout = editorView.layout
                    line in layout.getLineNumberForRow(editorView.firstVisibleRow)..
                        layout.getLineNumberForRow(editorView.lastVisibleRow)
                }.getOrDefault(true)
            }
            if (settled) break
            attempt++
        }
    }
    LaunchedEffect(settings.showIndentGuide, editor) {
        editor?.isBlockLineEnabled = settings.showIndentGuide
    }
    LaunchedEffect(settings.showWhitespace, settings.showWrapArrow, editor) {
        editor?.nonPrintablePaintingFlags =
            nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
    }
}

@Composable
private fun EditorSymbolBar(
    backgroundColor: Color,
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SymbolKey(onClick = onMoveLeft) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.editor_cursor_left)
                )
            }
            SymbolKey(onClick = onMoveRight) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.editor_cursor_right)
                )
            }
            SymbolKey(onClick = onIndent) {
                Text(
                    text = stringResource(R.string.editor_indent),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            EDITOR_SYMBOLS.forEach { symbol ->
                SymbolKey(onClick = { onInsert(symbol) }) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CenterBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl)
    )
}

/**
 * 给编辑器底色掺入当前主题的表面色。
 *
 * TextMate 主题的背景（Dark+ 的 #1E1E1E、Light+ 的纯白）是照 VSCode 的中性色调的，
 * 原样使用会让编辑器在带色温的主题下明显脱离其余界面。保留一成原色以维持编辑区的基调。
 */
private fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, EDITOR_THEME_TINT).toArgb()
    )
}

private fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

/** 行号 gutter 背景与编辑区拉开一点亮度差便于区分。基于编辑器背景色自适应，随主题走。 */

/** 把 sp 换算为像素，用于行号左边距等需 px 的 sora API。 */
private fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** 根据“显示空白符号”与“显示自动换行箭头”开关合成 sora 的非打印字符绘制标志。 */
private fun nonPrintableFlags(showWhitespace: Boolean, showWrapArrow: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}

/** 符号栏方向键：把光标水平移动一个字符，跨越行首/行尾时换到相邻行。 */
private fun moveCursorHorizontally(editor: CodeEditor, forward: Boolean) {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (forward) {
        val lineLength = editor.text.getColumnCount(line)
        when {
            column < lineLength -> editor.setSelection(line, column + 1)
            line < editor.lineCount - 1 -> editor.setSelection(line + 1, 0)
        }
    } else {
        when {
            column > 0 -> editor.setSelection(line, column - 1)
            line > 0 -> editor.setSelection(line - 1, editor.text.getColumnCount(line - 1))
        }
    }
}

/** 底部快捷栏的常用符号，点击在光标处插入。 */
private val EDITOR_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">",
    "=", "+", "-", "*", "/", "\\",
    ";", ":", ",", ".", "_", "\"", "'", "`",
    "|", "&", "!", "?", "@", "#", "\$", "%"
)

/** 内容渐显时长：给后台语法分析留出窗口，同时不致于让用户觉得打开变慢。 */
private const val HIGHLIGHT_REVEAL_MS = 200

/** 撤销合并窗口：间隔超过它的输入不再并入上一条撤销记录，撤销才是分步的。sora 默认 8000ms。 */
private const val UNDO_MERGE_WINDOW_MS = 500L

/** 行号左侧预留间距（sp）。 */
private const val LINE_NUMBER_MARGIN_SP = 2f

/** 文件名小栏高度与左右内边距。 */
private val FILE_TITLE_BAR_HEIGHT = 16.dp
private val FILE_TITLE_HORIZONTAL_PADDING = 12.dp

/** 文件读写统一按 UTF-8，小栏右侧展示用。 */
private const val FILE_ENCODING = "UTF-8"

/** 垂直额外视口空间系数（占编辑器高度的比例，取值 [0,1]），用于底部过度滚动。 */
private const val VERTICAL_EXTRA_SPACE_FACTOR = 0.5f

/** 编辑器底色向主题表面色靠拢的比例。 */
private const val EDITOR_THEME_TINT = 0.9f

/** 带行号打开时等编辑器完成首次布局的最多帧数，避免宽高迟迟不就绪时死等。 */
private const val LINE_JUMP_LAYOUT_WAIT_FRAMES = 30

/** 定位后校验目标行是否停在期望位置的重试次数，以及每次校验前等待的帧数。 */
private const val LINE_JUMP_MAX_ATTEMPTS = 8
private const val LINE_JUMP_VERIFY_FRAMES = 3

/**
 * 让目标行居中所需的垂直滚动量（已按可滚范围收敛，文件首尾附近自然贴边）。
 * 布局尚不可用时返回 null，由调用方退回官方的「仅保证可见」。
 */
private fun centerScrollOffset(editor: CodeEditor, line: Int): Int? = runCatching {
    // getCharLayoutOffset 返回的 [0] 是该行所在 row 的底部 y。
    val rowBottom = editor.layout.getCharLayoutOffset(line, 0)[0]
    val rowTop = rowBottom - editor.rowHeight
    (rowTop - (editor.height - editor.rowHeight) / 2f).toInt()
        .coerceIn(0, editor.scrollMaxY.coerceAtLeast(0))
}.getOrNull()

/** 无动画落位到指定垂直滚动量，与 sora 内部 ensurePositionVisible 的无动画分支同款做法。 */
private fun scrollVerticallyTo(editor: CodeEditor, offsetY: Int) {
    val scroller = editor.scroller
    scroller.startScroll(editor.offsetX, editor.offsetY, 0, offsetY - editor.offsetY, 0)
    scroller.abortAnimation()
    editor.invalidate()
}

/** 传给插件动作/页面的选中文本上限，避免大型选区撑爆 JSON 与页面桥。 */
private const val MAX_PLUGIN_SELECTION_CHARS = 16_000
