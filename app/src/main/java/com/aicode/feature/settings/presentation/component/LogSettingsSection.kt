package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.LogLevelColors
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.util.LogLevel
import com.aicode.feature.settings.presentation.AiLogBlock
import com.aicode.feature.settings.presentation.AiLogDetail
import com.aicode.feature.settings.presentation.LogFileItem
import com.aicode.feature.settings.presentation.LogLevelFilter
import com.aicode.feature.settings.presentation.LogSearchMode
import com.aicode.feature.settings.presentation.LogSourceTab
import com.aicode.feature.settings.presentation.LogViewerUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Search
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Tag
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 诊断工作台式日志页：
 * - 顶部紧凑工具栏：文件选择 + 记录等级药丸、搜索栏（过滤/定位切换）、筛选胶囊行 + 右侧固定复制按钮；
 * - 中部圆角卡片视口：主日志与堆栈归并为同一条目共用底色、堆栈默认可折叠隐藏、分段加载更早、折行/横滑切换；
 * - 底部复用 Git 同款 [FloatingTabBar] 悬浮切换三类日志源（应用日志 / AI 请求 / 崩溃追踪）。
 */
@Composable
internal fun LogSection(
    current: LogLevel,
    onOpenLevelSheet: () -> Unit,
    state: LogViewerUiState,
    onSelectSource: (LogSourceTab) -> Unit,
    onSelectFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onSelectLevelFilter: (LogLevelFilter) -> Unit,
    onSelectTagFilter: (String?) -> Unit,
    onClearMcpFilter: () -> Unit,
    onLoadEarlier: () -> Unit,
    onLoadAiDetail: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    var showFileSheet by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(LogSearchMode.FILTER) }
    var aiErrorsOnly by remember { mutableStateOf(false) }
    var wrapLines by remember { mutableStateOf(true) }

    val textListState = rememberLazyListState()
    val aiListState = rememberLazyListState()
    val tabsScrolling by remember {
        derivedStateOf {
            when (state.source) {
                LogSourceTab.AI -> aiListState.isScrollInProgress
                LogSourceTab.APP, LogSourceTab.CRASH -> textListState.isScrollInProgress
            }
        }
    }

    val groupedEntries = remember(state.content, state.source) {
        if (state.source == LogSourceTab.AI) emptyList()
        else parseGroupedLogEntries(state.content)
    }

    val filteredEntries = remember(groupedEntries, query, searchMode, state.source) {
        if (state.source == LogSourceTab.AI) emptyList()
        else if (query.isBlank() || searchMode == LogSearchMode.FIND) groupedEntries
        else groupedEntries.filter { it.raw.contains(query, ignoreCase = true) }
    }

    val filteredAiBlocks = remember(state.aiBlocks, query, aiErrorsOnly, state.source) {
        if (state.source != LogSourceTab.AI) emptyList()
        else state.aiBlocks.filter { block ->
            val passErr = !aiErrorsOnly || block.isError
            val passQuery = query.isBlank() ||
                block.providerModel.contains(query, ignoreCase = true) ||
                block.methodUrl.contains(query, ignoreCase = true) ||
                "#${block.seq}".contains(query, ignoreCase = true)
            passErr && passQuery
        }
    }

    val copyPayload = remember(state.source, filteredEntries, filteredAiBlocks, state.aiBlockDetails, query) {
        when (state.source) {
            LogSourceTab.AI -> filteredAiBlocks.joinToString("\n\n") { block ->
                state.aiBlockDetails[block.seq]?.rawText
                    ?: "#${block.seq} [${block.providerModel}] ${block.requestTime} ${block.methodUrl}"
            }
            LogSourceTab.APP, LogSourceTab.CRASH -> {
                val linesToCopy = if (query.isNotBlank() && searchMode == LogSearchMode.FIND) {
                    filteredEntries.filter { it.raw.contains(query, ignoreCase = true) }
                } else {
                    filteredEntries
                }
                linesToCopy.joinToString("\n") { it.raw }
            }
        }
    }

    val selectedFileItem = remember(state.files, state.selectedFileName) {
        state.files.firstOrNull { it.fileName == state.selectedFileName }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // ── 1. 文件选择 + 记录等级药丸 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogFilePickerPill(
                    selected = selectedFileItem,
                    hasFiles = state.files.isNotEmpty(),
                    onClick = { showFileSheet = true },
                    modifier = Modifier.weight(1f)
                )
                LogLevelPill(
                    level = current,
                    onClick = onOpenLevelSheet
                )
            }

            // ── 2. 搜索框 + 过滤/定位切换胶囊 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                ModelSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.log_search_hint),
                    modifier = Modifier.weight(1f),
                    trailing = if (query.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    FeatherIcons.X,
                                    contentDescription = stringResource(R.string.common_clear),
                                    tint = MaterialTheme.semanticColors.subtleText,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    } else null
                )
                if (state.source != LogSourceTab.AI) {
                    SearchModeTogglePill(
                        mode = searchMode,
                        onToggle = {
                            searchMode = if (searchMode == LogSearchMode.FILTER) {
                                LogSearchMode.FIND
                            } else {
                                LogSearchMode.FILTER
                            }
                        }
                    )
                }
            }

            // ── 3. 筛选胶囊条 + 同行右侧固定复制按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                when (state.source) {
                    LogSourceTab.APP -> AppFilterChipsRow(
                        levelFilter = state.levelFilter,
                        onSelectLevelFilter = onSelectLevelFilter,
                        mcpServerName = state.filterServerName,
                        onClearMcpFilter = onClearMcpFilter,
                        tagFilter = state.filterTag,
                        onClearTagFilter = { onSelectTagFilter(null) },
                        modifier = Modifier.weight(1f)
                    )

                    LogSourceTab.AI -> {
                        val errCount = remember(state.aiBlocks) { state.aiBlocks.count { it.isError } }
                        AiFilterChipsRow(
                            errorsOnly = aiErrorsOnly,
                            errorCount = errCount,
                            onSelectErrorsOnly = { aiErrorsOnly = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LogSourceTab.CRASH -> Spacer(Modifier.weight(1f))
                }

                CompactCopyButton(
                    text = copyPayload,
                    enabled = copyPayload.isNotEmpty()
                )
            }

            // ── 4. 圆角卡片日志视口 ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.semanticColors.cardSurface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            ) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }

                    state.error != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    state.source == LogSourceTab.AI -> AiLogsViewport(
                        currentLevel = current,
                        onOpenLevelSheet = onOpenLevelSheet,
                        state = state,
                        blocks = filteredAiBlocks,
                        listState = aiListState,
                        query = query,
                        filterActive = query.isNotBlank() || aiErrorsOnly,
                        onLoadEarlier = onLoadEarlier,
                        onLoadDetail = onLoadAiDetail
                    )

                    else -> TextLogsViewport(
                        state = state,
                        entries = filteredEntries,
                        listState = textListState,
                        query = query,
                        searchMode = searchMode,
                        wrapLines = wrapLines,
                        onToggleWrap = { wrapLines = !wrapLines },
                        onTagClick = onSelectTagFilter,
                        onLoadEarlier = onLoadEarlier,
                        onRefresh = onRefresh
                    )
                }
            }
        }

        // ── 5. 底部悬浮液态玻璃 Tab 栏（对齐 GitScreen） ──
        FloatingTabBar(
            selected = state.source.ordinal,
            onSelect = { index -> onSelectSource(LogSourceTab.entries[index]) },
            items = listOf(
                FloatingTabItem(FeatherIcons.FileText, stringResource(R.string.log_source_app)),
                FloatingTabItem(FeatherIcons.Cpu, stringResource(R.string.log_source_ai)),
                FloatingTabItem(FeatherIcons.AlertCircle, stringResource(R.string.log_source_crash))
            ),
            maskColor = settingsPageBackground(),
            isScrolling = tabsScrolling,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showFileSheet) {
        LogFileSheet(
            files = state.files,
            selectedFileName = state.selectedFileName,
            onSelect = {
                onSelectFile(it)
                showFileSheet = false
            },
            onDelete = onDeleteFile,
            onDismiss = { showFileSheet = false }
        )
    }
}

/** 当前文件下拉胶囊：展示文件名与体积。 */
@Composable
private fun LogFilePickerPill(
    selected: LogFileItem?,
    hasFiles: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = hasFiles,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.FileText,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = selected?.displayName ?: stringResource(R.string.settings_log_no_files),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatLogSize(selected.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                FeatherIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** 右侧紧凑的「记录等级」药丸（全页唯一等级入口）。 */
@Composable
private fun LogLevelPill(
    level: LogLevel,
    onClick: () -> Unit
) {
    val color = logLevelColor(level)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = level.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1
            )
        }
    }
}

/** 搜索模式切换胶囊（带图标，语义更直观）：过滤（只看匹配）vs 定位（保留上下文跳转）。 */
@Composable
private fun SearchModeTogglePill(
    mode: LogSearchMode,
    onToggle: () -> Unit
) {
    val isFind = mode == LogSearchMode.FIND
    val accent = if (isFind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        color = if (isFind) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.semanticColors.capsuleSurface
        },
        border = BorderStroke(
            0.5.dp,
            if (isFind) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isFind) FeatherIcons.Search else FeatherIcons.Sliders,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = stringResource(
                    if (isFind) R.string.log_search_mode_find else R.string.log_search_mode_filter
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1
            )
        }
    }
}

/** 应用日志筛选胶囊行：等级快捷过滤 + 动态 MCP / Tag 过滤徽章。 */
@Composable
private fun AppFilterChipsRow(
    levelFilter: LogLevelFilter,
    onSelectLevelFilter: (LogLevelFilter) -> Unit,
    mcpServerName: String?,
    onClearMcpFilter: () -> Unit,
    tagFilter: String?,
    onClearTagFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterPillChip(
            label = stringResource(R.string.log_filter_all),
            selected = levelFilter == LogLevelFilter.ALL,
            accentColor = MaterialTheme.colorScheme.primary,
            onClick = { onSelectLevelFilter(LogLevelFilter.ALL) }
        )
        FilterPillChip(
            label = stringResource(R.string.log_filter_error),
            selected = levelFilter == LogLevelFilter.ERROR,
            accentColor = LogLevelColors.Error,
            onClick = { onSelectLevelFilter(LogLevelFilter.ERROR) }
        )
        FilterPillChip(
            label = stringResource(R.string.log_filter_warn_plus),
            selected = levelFilter == LogLevelFilter.WARN_PLUS,
            accentColor = LogLevelColors.Warn,
            onClick = { onSelectLevelFilter(LogLevelFilter.WARN_PLUS) }
        )
        FilterPillChip(
            label = stringResource(R.string.log_filter_info_plus),
            selected = levelFilter == LogLevelFilter.INFO_PLUS,
            accentColor = LogLevelColors.Info,
            onClick = { onSelectLevelFilter(LogLevelFilter.INFO_PLUS) }
        )
        if (!mcpServerName.isNullOrBlank()) {
            RemovableFilterChip(
                label = stringResource(R.string.log_mcp_prefix, mcpServerName),
                onClear = onClearMcpFilter
            )
        }
        if (!tagFilter.isNullOrBlank()) {
            RemovableFilterChip(
                label = stringResource(R.string.log_tag_prefix, tagFilter),
                onClear = onClearTagFilter
            )
        }
    }
}

/** AI 请求日志筛选胶囊行：全部 / 仅失败。 */
@Composable
private fun AiFilterChipsRow(
    errorsOnly: Boolean,
    errorCount: Int,
    onSelectErrorsOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterPillChip(
            label = stringResource(R.string.log_filter_all),
            selected = !errorsOnly,
            accentColor = MaterialTheme.colorScheme.primary,
            onClick = { onSelectErrorsOnly(false) }
        )
        FilterPillChip(
            label = if (errorCount > 0) {
                "${stringResource(R.string.log_filter_errors_only)} ($errorCount)"
            } else {
                stringResource(R.string.log_filter_errors_only)
            },
            selected = errorsOnly,
            accentColor = LogLevelColors.Error,
            onClick = { onSelectErrorsOnly(true) }
        )
    }
}

@Composable
private fun FilterPillChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        color = if (selected) accentColor.copy(alpha = 0.16f) else MaterialTheme.semanticColors.cardSurface,
        border = if (selected) BorderStroke(0.5.dp, accentColor.copy(alpha = 0.4f)) else null
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun RemovableFilterChip(
    label: String,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.log_clear_filter),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .padding(2.dp)
            )
        }
    }
}

/** 放在筛选行最右侧的紧凑复制按钮。 */
@Composable
private fun CompactCopyButton(text: String, enabled: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        onClick = {
            if (enabled) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
                    copied = true
                }
            }
        },
        shape = RoundedCornerShape(Radius.pill),
        color = if (copied) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.semanticColors.cardSurface
        },
        border = BorderStroke(
            0.5.dp,
            if (copied) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                contentDescription = stringResource(if (copied) R.string.log_copied else R.string.log_copy),
                tint = when {
                    copied -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = stringResource(if (copied) R.string.log_copied else R.string.log_copy),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    copied -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                maxLines = 1
            )
        }
    }
}

/** 文本型日志视口（应用日志 & 崩溃追踪共用）。 */
@Composable
private fun TextLogsViewport(
    state: LogViewerUiState,
    entries: List<GroupedLogEntry>,
    listState: LazyListState,
    query: String,
    searchMode: LogSearchMode,
    wrapLines: Boolean,
    onToggleWrap: () -> Unit,
    onTagClick: (String) -> Unit,
    onLoadEarlier: () -> Unit,
    onRefresh: () -> Unit
) {
    val isAppSource = state.source == LogSourceTab.APP
    val hasActiveFilter = query.isNotBlank() ||
        state.filterServerName != null ||
        state.filterTag != null ||
        state.levelFilter != LogLevelFilter.ALL

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 72.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    when {
                        hasActiveFilter -> R.string.log_no_match
                        state.source == LogSourceTab.CRASH -> R.string.log_crash_empty
                        else -> R.string.settings_log_no_files
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val horizontalScrollState = rememberScrollState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copiedIndex by remember { mutableStateOf<Int?>(null) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var detailText by remember { mutableStateOf<String?>(null) }
    var expandedStacks by remember(state.selectedFileName, state.source) { mutableStateOf(setOf<Int>()) }
    var followLatest by remember(state.source) { mutableStateOf(isAppSource) }

    val matchIndices = remember(entries, query, searchMode) {
        if (query.isBlank() || searchMode != LogSearchMode.FIND) emptyList()
        else entries.indices.filter { entries[it].raw.contains(query, ignoreCase = true) }
    }
    var activeMatchPos by remember(matchIndices) {
        mutableIntStateOf((matchIndices.size - 1).coerceAtLeast(0))
    }
    val activeMatchEntryIndex = matchIndices.getOrNull(activeMatchPos)

    LaunchedEffect(activeMatchEntryIndex) {
        if (activeMatchEntryIndex != null) {
            followLatest = false
            listState.animateScrollToItem(activeMatchEntryIndex)
        }
    }

    LaunchedEffect(followLatest, isAppSource, state.selectedFileName, state.filterServerName, state.filterTag, state.levelFilter) {
        if (followLatest && isAppSource) {
            while (true) {
                delay(3000)
                onRefresh()
            }
        }
    }

    LaunchedEffect(entries.size, state.selectedFileName, state.filterServerName, state.filterTag, state.levelFilter, followLatest) {
        if (followLatest && entries.isNotEmpty() && searchMode != LogSearchMode.FIND) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    LaunchedEffect(copiedIndex) {
        if (copiedIndex != null) {
            delay(1500)
            copiedIndex = null
        }
    }

    fun copyLog(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
        }
    }

    val showScrollTop by remember { derivedStateOf { listState.canScrollBackward } }
    val showScrollBottom by remember { derivedStateOf { listState.canScrollForward } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 卡片内顶部信息栏 ──
            val context = LocalContext.current
            val summary = when {
                query.isNotBlank() && searchMode == LogSearchMode.FILTER ->
                    context.getString(R.string.log_search_results, entries.size)
                state.totalLines > state.shownLines ->
                    context.getString(R.string.log_show_last_lines, state.shownLines, state.totalLines)
                else ->
                    context.getString(R.string.log_show_lines, state.shownLines)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (query.isNotBlank() && searchMode == LogSearchMode.FIND) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (matchIndices.isEmpty()) {
                                stringResource(R.string.log_find_no_match)
                            } else {
                                stringResource(R.string.log_find_counter, activeMatchPos + 1, matchIndices.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                if (matchIndices.isNotEmpty()) {
                                    activeMatchPos = if (activeMatchPos > 0) activeMatchPos - 1 else matchIndices.lastIndex
                                }
                            },
                            enabled = matchIndices.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(FeatherIcons.ChevronUp, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                if (matchIndices.isNotEmpty()) {
                                    activeMatchPos = if (activeMatchPos < matchIndices.lastIndex) activeMatchPos + 1 else 0
                                }
                            },
                            enabled = matchIndices.isNotEmpty(),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(FeatherIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                } else if (state.hasMoreEarlier) {
                    CompactToggleChip(
                        label = "+ ${stringResource(R.string.log_load_earlier)}",
                        active = false,
                        onClick = onLoadEarlier
                    )
                }

                CompactToggleChip(
                    label = stringResource(R.string.log_wrap_lines),
                    active = wrapLines,
                    onClick = onToggleWrap
                )

                if (isAppSource) {
                    CompactToggleChip(
                        label = stringResource(R.string.log_follow_latest),
                        active = followLatest,
                        dotColor = if (followLatest) LogLevelColors.Info else null,
                        onClick = { followLatest = !followLatest }
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = Spacing.xs, bottom = 76.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            modifier = Modifier.padding(horizontal = Spacing.md)
                        )
                    }
                    // 若搜索命中了堆栈内容，自动展开方便定位，否则由用户点开
                    val stackMatched = query.isNotBlank() && entry.stackLines.any {
                        it.contains(query, ignoreCase = true)
                    }
                    val stackExpanded = (entry.id in expandedStacks) || stackMatched
                    GroupedLogItemView(
                        entry = entry,
                        query = query,
                        isActiveFindMatch = index == activeMatchEntryIndex,
                        copied = copiedIndex == index,
                        wrapLines = wrapLines,
                        stackExpanded = stackExpanded,
                        horizontalScrollState = horizontalScrollState,
                        onToggleStack = {
                            expandedStacks = if (entry.id in expandedStacks) {
                                expandedStacks - entry.id
                            } else {
                                expandedStacks + entry.id
                            }
                        },
                        onTagClick = if (isAppSource) onTagClick else null,
                        onClick = {
                            copyLog(entry.raw)
                            copiedIndex = index
                        },
                        onLongClick = { menuIndex = index }
                    )
                }
            }
        }

        // 悬浮置顶 / 置底：抬高避开底部 FloatingTabBar
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Spacing.md, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            AnimatedVisibility(visible = showScrollTop, enter = fadeIn(), exit = fadeOut()) {
                FloatingJumpButton(
                    icon = FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.log_scroll_top),
                    onClick = {
                        followLatest = false
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                )
            }
            AnimatedVisibility(visible = showScrollBottom, enter = fadeIn(), exit = fadeOut()) {
                FloatingJumpButton(
                    icon = FeatherIcons.ArrowDown,
                    contentDescription = stringResource(R.string.log_scroll_bottom),
                    onClick = {
                        if (isAppSource) followLatest = true
                        if (entries.isNotEmpty()) {
                            scope.launch { listState.animateScrollToItem(entries.lastIndex) }
                        }
                    }
                )
            }
        }
    }

    menuIndex?.let { index ->
        val entry = entries.getOrNull(index) ?: return@let
        LogLineActionsSheet(
            tag = entry.tag.takeIf { isAppSource },
            hasStack = entry.stackLines.isNotEmpty(),
            onCopyLine = {
                copyLog(entry.firstLineRaw)
                copiedIndex = index
                menuIndex = null
            },
            onCopyWithStack = {
                copyLog(entry.raw)
                copiedIndex = index
                menuIndex = null
            },
            onCopyMessage = {
                copyLog(entry.message)
                copiedIndex = index
                menuIndex = null
            },
            onFilterByTag = entry.tag?.takeIf { isAppSource }?.let { tag ->
                {
                    onTagClick(tag)
                    menuIndex = null
                }
            },
            onViewDetail = {
                detailText = entry.raw
                menuIndex = null
            },
            onDismiss = { menuIndex = null }
        )
    }

    detailText?.let { text ->
        LogDetailDialog(
            text = text,
            onCopy = { copyLog(text) },
            onDismiss = { detailText = null }
        )
    }
}

/**
 * AI 请求日志卡片视口：
 * 列表仅渲染轻量索引头，点开卡片按需从磁盘读取单次交互正文；等级高于 DEBUG 时提示可点击开启记录。
 */
@Composable
private fun AiLogsViewport(
    currentLevel: LogLevel,
    onOpenLevelSheet: () -> Unit,
    state: LogViewerUiState,
    blocks: List<AiLogBlock>,
    listState: LazyListState,
    query: String,
    filterActive: Boolean,
    onLoadEarlier: () -> Unit,
    onLoadDetail: (Int) -> Unit
) {
    val recordingDisabled = currentLevel.ordinal > LogLevel.DEBUG.ordinal

    if (blocks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    if (filterActive) R.string.log_no_match else R.string.log_ai_empty
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!filterActive && recordingDisabled) {
                Spacer(Modifier.height(Spacing.sm))
                Surface(
                    onClick = onOpenLevelSheet,
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = stringResource(R.string.log_ai_level_hint, currentLevel.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        return
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var expandedSeqs by remember(state.selectedFileName) { mutableStateOf(setOf<Int>()) }
    var detailSeq by remember { mutableStateOf<Int?>(null) }

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ai-log", text)))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            val countText = if (state.totalLines > state.shownLines) {
                stringResource(R.string.log_show_last_lines, blocks.size, state.totalLines)
            } else {
                stringResource(R.string.log_ai_calls_count, blocks.size)
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (state.hasMoreEarlier) {
                CompactToggleChip(
                    label = "+ ${stringResource(R.string.log_load_earlier)}",
                    active = false,
                    onClick = onLoadEarlier
                )
            }
        }
        if (recordingDisabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LogLevelColors.Warn.copy(alpha = 0.10f))
                    .clickable(onClick = onOpenLevelSheet)
                    .padding(horizontal = Spacing.md, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.log_ai_level_hint, currentLevel.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = LogLevelColors.Warn
                )
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(blocks.reversed(), key = { it.seq }) { block ->
                val expanded = block.seq in expandedSeqs
                val detail = state.aiBlockDetails[block.seq]
                AiLogBlockCard(
                    block = block,
                    detail = detail,
                    query = query,
                    expanded = expanded,
                    onToggleExpand = {
                        val next = !expanded
                        expandedSeqs = if (next) expandedSeqs + block.seq else expandedSeqs - block.seq
                        if (next && detail == null) {
                            onLoadDetail(block.seq)
                        }
                    },
                    onCopy = { copyText(it) },
                    onOpenDetail = {
                        if (detail == null) onLoadDetail(block.seq)
                        detailSeq = block.seq
                    }
                )
            }
        }
    }

    detailSeq?.let { seq ->
        val detail = state.aiBlockDetails[seq]
        if (detail != null) {
            LogDetailDialog(
                text = detail.rawText,
                onCopy = { copyText(detail.rawText) },
                onDismiss = { detailSeq = null }
            )
        } else {
            AlertDialog(
                onDismissRequest = { detailSeq = null },
                title = { Text(stringResource(R.string.log_detail_title)) },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { detailSeq = null }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            )
        }
    }
}

@Composable
private fun AiLogBlockCard(
    block: AiLogBlock,
    detail: AiLogDetail?,
    query: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenDetail: () -> Unit
) {
    val statusColor = when {
        block.isError -> LogLevelColors.Error
        block.isStream -> LogLevelColors.Debug
        block.responseHeader != null -> LogLevelColors.Info
        else -> LogLevelColors.Warn
    }
    val statusText = when {
        block.isError -> "ERROR"
        block.isStream -> stringResource(R.string.log_ai_stream_badge)
        block.responseHeader != null -> "OK"
        else -> "..."
    }
    val shortTime = block.requestTime.substringAfter(' ', block.requestTime)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.semanticColors.capsuleSurface.copy(alpha = 0.65f),
        border = BorderStroke(
            width = if (block.isError) 1.dp else 0.5.dp,
            color = if (block.isError) LogLevelColors.Error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "#${block.seq}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = highlightQueryText(AnnotatedString(block.providerModel), query, highlightColor),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (block.approxBytes > 0) {
                    Text(
                        text = formatLogSize(block.approxBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = shortTime,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (block.methodUrl.isNotEmpty()) {
                Text(
                    text = highlightQueryText(AnnotatedString(block.methodUrl), query, highlightColor),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)
                )
            }

            if (expanded) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                if (detail == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        AiLogSectionBlock(
                            title = stringResource(R.string.log_ai_request_body),
                            body = detail.requestBody.ifEmpty { stringResource(R.string.log_ai_empty_body) },
                            query = query,
                            highlightColor = highlightColor,
                            onCopy = { onCopy(detail.requestBody) }
                        )
                        detail.responseBody?.let { resp ->
                            AiLogSectionBlock(
                                title = stringResource(
                                    if (block.isError) R.string.log_ai_error_body else R.string.log_ai_response_body
                                ),
                                body = resp.ifEmpty { stringResource(R.string.log_ai_empty_body) },
                                query = query,
                                highlightColor = highlightColor,
                                accentError = block.isError,
                                onCopy = { onCopy(resp) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onOpenDetail) {
                                Icon(FeatherIcons.FileText, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.log_view_detail),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiLogSectionBlock(
    title: String,
    body: String,
    query: String,
    highlightColor: Color,
    accentError: Boolean = false,
    onCopy: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (accentError) LogLevelColors.Error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Surface(
                onClick = {
                    onCopy()
                    copied = true
                },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.semanticColors.cardSurface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = null,
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (copied) R.string.log_copied else R.string.log_copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(Radius.sm),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            val preview = remember(body) {
                if (body.length > 12000) body.take(12000) + "\n… (${body.length} chars)" else body
            }
            SelectionContainer {
                Text(
                    text = highlightQueryText(AnnotatedString(preview), query, highlightColor),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun CompactToggleChip(
    label: String,
    active: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.semanticColors.capsuleSurface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FloatingJumpButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 合并后的完整日志条：
 * - 错误与堆栈共用同一段背景色（无割裂）；
 * - 去掉栈前空占位/细竖线；
 * - 堆栈默认折叠，点击「N 行堆栈」可原地展开/收起。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedLogItemView(
    entry: GroupedLogEntry,
    query: String,
    isActiveFindMatch: Boolean,
    copied: Boolean,
    wrapLines: Boolean,
    stackExpanded: Boolean,
    horizontalScrollState: ScrollState,
    onToggleStack: () -> Unit,
    onTagClick: ((String) -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val rowBg = when {
        isActiveFindMatch -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        entry.level == LogLevel.ERROR -> LogLevelColors.Error.copy(alpha = 0.06f)
        entry.level == LogLevel.WARN -> LogLevelColors.Warn.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    val stackAccentColor = when (entry.level) {
        LogLevel.ERROR -> LogLevelColors.Error
        LogLevel.WARN -> LogLevelColors.Warn
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.md, vertical = 6.dp)
    ) {
        if (entry.level != null) {
            // 头行：等级方标 + 时间 + Tag + 已复制图标
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogLetterBadge(level = entry.level)
                Spacer(Modifier.width(6.dp))
                if (entry.shortTime != null) {
                    Text(
                        text = entry.shortTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (entry.tag != null) {
                    Spacer(Modifier.width(6.dp))
                    val tag = entry.tag
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (onTagClick != null) Modifier.clickable { onTagClick(tag) }
                                else Modifier
                            )
                    ) {
                        Text(
                            text = highlightQueryText(AnnotatedString(tag), query, highlightColor),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (copied) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        FeatherIcons.Check,
                        contentDescription = stringResource(R.string.log_copied),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // 正文（标准日志从 24dp 缩进起始；无头续行整段对齐）
        if (entry.message.isNotEmpty()) {
            val startPad = if (entry.level != null) 24.dp else 0.dp
            val msgModifier = Modifier
                .fillMaxWidth()
                .padding(start = startPad, top = if (entry.level != null) 2.dp else 0.dp)
                .then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontalScrollState))
            Box(modifier = msgModifier) {
                Text(
                    text = highlightQueryText(AnnotatedString(entry.message), query, highlightColor),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = wrapLines,
                    maxLines = if (wrapLines) Int.MAX_VALUE else 1
                )
            }
        }

        // 堆栈折叠切换 + 展开内容（与正文同一底色、同左对齐、无左侧空占位/竖线）
        if (entry.stackLines.isNotEmpty()) {
            val startPad = if (entry.level != null) 24.dp else 0.dp
            Spacer(Modifier.height(4.dp))
            Surface(
                onClick = onToggleStack,
                shape = RoundedCornerShape(6.dp),
                color = stackAccentColor.copy(alpha = 0.10f),
                modifier = Modifier.padding(start = startPad)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = if (stackExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                        contentDescription = null,
                        tint = stackAccentColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = stringResource(R.string.log_stack_lines, entry.stackLines.size),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Medium,
                        color = stackAccentColor
                    )
                }
            }

            if (stackExpanded) {
                val stackText = remember(entry.stackLines) { entry.stackLines.joinToString("\n") }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = startPad, top = 4.dp)
                        .then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                ) {
                    Text(
                        text = highlightQueryText(AnnotatedString(stackText), query, highlightColor),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.5.sp
                        ),
                        color = if (entry.level == LogLevel.ERROR) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        softWrap = wrapLines,
                        maxLines = if (wrapLines) Int.MAX_VALUE else Int.MAX_VALUE
                    )
                }
            }
        }
    }
}

/** 单字母等级徽章（18dp × 16dp）。 */
@Composable
private fun LogLetterBadge(level: LogLevel) {
    val color = logLevelColor(level)
    Box(
        modifier = Modifier
            .size(width = 18.dp, height = 16.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = level.name.first().toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = color,
            maxLines = 1
        )
    }
}

/** 精简版日志等级底部面板：仅保留标题与 6 级单选列表（移除冗长说明与底部路径）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogLevelSheet(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_level),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            LogLevel.entries.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(logLevelColor(level), RoundedCornerShape(Radius.pill))
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = level.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (level == current) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 升级版日志文件底部面板：显示文件体积、副标题与内嵌二次确认的单文件删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileSheet(
    files: List<LogFileItem>,
    selectedFileName: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingDeleteFile by remember { mutableStateOf<String?>(null) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.log_files),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            files.forEach { item ->
                val selected = item.fileName == selectedFileName
                val confirming = pendingDeleteFile == item.fileName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            pendingDeleteFile = null
                            onSelect(item.fileName)
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val meta = buildString {
                            append(formatLogSize(item.sizeBytes))
                            item.subLabel?.let { append(" · ").append(it) }
                        }
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selected) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = Spacing.xs)
                                .size(18.dp)
                        )
                    }
                    if (confirming) {
                        Surface(
                            onClick = {
                                pendingDeleteFile = null
                                onDelete(item.fileName)
                            },
                            shape = RoundedCornerShape(Radius.pill),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(R.string.log_delete_file_confirm),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { pendingDeleteFile = item.fileName },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.log_delete_file),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 日志行长按操作面板：复制本条 / 含堆栈 / 仅消息 / 按 Tag 过滤 / 查看完整详情。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLineActionsSheet(
    tag: String?,
    hasStack: Boolean,
    onCopyLine: () -> Unit,
    onCopyWithStack: () -> Unit,
    onCopyMessage: () -> Unit,
    onFilterByTag: (() -> Unit)?,
    onViewDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_copy_menu_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            LogActionRow(
                icon = FeatherIcons.Copy,
                title = stringResource(R.string.log_copy_line),
                onClick = onCopyLine
            )
            if (hasStack) {
                LogActionRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.log_copy_with_stack),
                    onClick = onCopyWithStack
                )
            }
            LogActionRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.log_copy_message),
                onClick = onCopyMessage
            )
            if (tag != null && onFilterByTag != null) {
                LogActionRow(
                    icon = FeatherIcons.Tag,
                    title = stringResource(R.string.log_filter_this_tag, tag),
                    onClick = onFilterByTag
                )
            }
            LogActionRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(R.string.log_view_detail),
                onClick = onViewDetail
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** 可自由框选的日志完整详情弹窗。 */
@Composable
private fun LogDetailDialog(
    text: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.log_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCopy()
                onDismiss()
            }) {
                Text(stringResource(R.string.log_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** 操作面板行：左侧图标 + 标题，整行可点击。 */
@Composable
private fun LogActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 等级主题色：统一使用 LogLevelColors 调色板。 */
private fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> LogLevelColors.Verbose
    LogLevel.DEBUG -> LogLevelColors.Debug
    LogLevel.INFO -> LogLevelColors.Info
    LogLevel.WARN -> LogLevelColors.Warn
    LogLevel.ERROR -> LogLevelColors.Error
    LogLevel.NONE -> LogLevelColors.None
}

/** 把关键字所有忽略大小写匹配的高亮样式叠加到已有 [AnnotatedString] 上。 */
private fun highlightQueryText(
    source: AnnotatedString,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) return source
    val text = source.text
    var idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0) return source
    return buildAnnotatedString {
        append(source)
        while (idx >= 0) {
            addStyle(
                style = SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.SemiBold
                ),
                start = idx,
                end = idx + query.length
            )
            idx = text.indexOf(query, startIndex = idx + query.length, ignoreCase = true)
        }
    }
}

private fun formatLogSize(bytes: Long): String = when {
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
}

/**
 * 合并后的完整日志条：主日志行与紧随其后的堆栈/续行归为同一条。
 */
private data class GroupedLogEntry(
    val id: Int,
    val level: LogLevel?,
    val shortTime: String?,
    val tag: String?,
    val message: String,
    val stackLines: List<String>,
    val firstLineRaw: String,
    val raw: String
)

/** 与 [com.aicode.core.util.FileLogger] 落盘格式匹配：`yyyy-MM-dd HH:mm:ss.SSS LEVEL [TAG] message`。 */
private val LOG_LINE_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2} (\d{2}:\d{2}:\d{2}\.\d{3})) (VERBOSE|DEBUG|INFO|WARN|ERROR)(?: \[([^\]]*)\])? (.*)$"""
)

/** 把日志文本解析并归并为 [GroupedLogEntry] 列表：主日志行与随后的堆栈续行合并为一条。 */
private fun parseGroupedLogEntries(content: String): List<GroupedLogEntry> {
    if (content.isEmpty()) return emptyList()
    val result = ArrayList<GroupedLogEntry>()
    var curLevel: LogLevel? = null
    var curShortTime: String? = null
    var curTag: String? = null
    var curMessage: String? = null
    var curFirstLineRaw: String? = null
    val curStacks = ArrayList<String>()
    val orphanLines = ArrayList<String>()

    fun flushCurrent() {
        val firstRaw = curFirstLineRaw ?: return
        val fullRaw = if (curStacks.isEmpty()) firstRaw else "$firstRaw\n${curStacks.joinToString("\n")}"
        result.add(
            GroupedLogEntry(
                id = result.size,
                level = curLevel,
                shortTime = curShortTime,
                tag = curTag,
                message = curMessage.orEmpty(),
                stackLines = curStacks.toList(),
                firstLineRaw = firstRaw,
                raw = fullRaw
            )
        )
        curLevel = null
        curShortTime = null
        curTag = null
        curMessage = null
        curFirstLineRaw = null
        curStacks.clear()
    }

    fun flushOrphans() {
        if (orphanLines.isEmpty()) return
        val text = orphanLines.joinToString("\n")
        result.add(
            GroupedLogEntry(
                id = result.size,
                level = null,
                shortTime = null,
                tag = null,
                message = text,
                stackLines = emptyList(),
                firstLineRaw = text,
                raw = text
            )
        )
        orphanLines.clear()
    }

    for (line in content.lineSequence()) {
        val match = LOG_LINE_REGEX.matchEntire(line)
        if (match != null) {
            flushOrphans()
            flushCurrent()
            curLevel = LogLevel.valueOf(match.groupValues[3])
            curShortTime = match.groupValues[2]
            curTag = match.groupValues[4].takeIf { it.isNotEmpty() }
            curMessage = match.groupValues[5]
            curFirstLineRaw = line
        } else if (curFirstLineRaw != null) {
            curStacks.add(line)
        } else {
            orphanLines.add(line)
        }
    }
    flushOrphans()
    flushCurrent()
    return result
}
