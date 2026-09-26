package com.aicode.feature.browser.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import com.aicode.feature.agent.domain.tool.browser.BrowserTabState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Sun
import compose.icons.feathericons.X
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserManager: BrowserManager,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by browserManager.state.collectAsStateWithLifecycle()
    val activeTab = state.activeTab
    val scope = rememberCoroutineScope()

    var showTabsSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var isEditingAddress by rememberSaveable { mutableStateOf(false) }

    // 标签页切换时退出编辑态
    LaunchedEffect(state.activeTabId) {
        isEditingAddress = false
    }

    // 拦截物理/手势返回键：若处于地址栏编辑态，优先退出编辑态
    BackHandler(enabled = isEditingAddress) {
        isEditingAddress = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrowserTopBar(
                tabId = state.activeTabId,
                title = activeTab?.title.orEmpty(),
                url = activeTab?.url.orEmpty(),
                loading = activeTab?.loading == true,
                devToolsOpen = state.devToolsOpen,
                isEditing = isEditingAddress,
                onEditingChange = { isEditingAddress = it },
                embedded = embedded,
                onNavigateBack = onNavigateBack,
                onReload = { scope.launch { browserManager.reload() } },
                onToggleDevTools = { scope.launch { browserManager.toggleDevTools() } },
                onNavigate = { url ->
                    isEditingAddress = false
                    scope.launch { browserManager.navigate(url) }
                }
            )
        },
        bottomBar = {
            BrowserBottomBar(
                canGoBack = activeTab?.loading == false,
                canGoForward = activeTab?.loading == false,
                tabsCount = state.tabs.size,
                nightMode = state.nightMode,
                embedded = embedded,
                onGoBack = { scope.launch { browserManager.goBack() } },
                onGoForward = { scope.launch { browserManager.goForward() } },
                onNewTab = { scope.launch { browserManager.newTab() } },
                onToggleNightMode = { browserManager.toggleNightMode() },
                onOpenTabs = { showTabsSheet = true }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { context ->
                    browserManager.attachVisible()
                    browserManager.getOrCreateContainerView(context)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 当地址栏处于编辑态时覆盖遮罩，拦截手势并支持轻点任意空白区域退出编辑态
            if (isEditingAddress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isEditingAddress = false
                        }
                )
            }

            // 错误提示
            if (activeTab?.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(Spacing.lg)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = activeTab.error.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        IconButton(onClick = { scope.launch { browserManager.reload() } }) {
                            Icon(
                                FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.browser_reload),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 空白标签页状态：引导输入网址
            if (activeTab != null && activeTab.url.isEmpty() && !activeTab.loading && activeTab.error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(Spacing.xl)
                    ) {
                        Icon(
                            FeatherIcons.Globe,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.browser_empty_click_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 多标签页管理抽屉
    if (showTabsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xl)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.browser_tabs_count, state.tabs.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                browserManager.newTab()
                                showTabsSheet = false
                            }
                        }
                    ) {
                        Icon(
                            FeatherIcons.Plus,
                            contentDescription = stringResource(R.string.browser_new_tab),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(state.tabs, key = { it.id }) { tab ->
                        BrowserTabItemCard(
                            tab = tab,
                            selected = tab.id == state.activeTabId,
                            onClick = {
                                scope.launch {
                                    browserManager.selectTab(tab.id)
                                    showTabsSheet = false
                                }
                            },
                            onClose = {
                                scope.launch {
                                    browserManager.closeTab(tab.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            browserManager.detachFromViewHierarchy()
        }
    }
}

/**
 * 顶部栏独立组件：将输入态和文字变化隔离在此处，避免打字导致整屏重组卡顿。
 */
@Composable
private fun BrowserTopBar(
    tabId: String,
    title: String,
    url: String,
    loading: Boolean,
    devToolsOpen: Boolean,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    embedded: Boolean,
    onNavigateBack: () -> Unit,
    onReload: () -> Unit,
    onToggleDevTools: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var addressInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(url))
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 标签页切换或新建时，重置输入框内容
    LaunchedEffect(tabId) {
        addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
    }

    // 当非编辑态下 URL 变更时同步
    LaunchedEffect(url, isEditing) {
        if (!isEditing) {
            addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
        }
    }

    // 处理编辑态切换聚焦与键盘
    LaunchedEffect(isEditing) {
        if (isEditing) {
            addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!embedded) Modifier.statusBarsPadding() else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：返回 / 取消编辑
                IconButton(onClick = {
                    if (isEditing) {
                        onEditingChange(false)
                    } else {
                        onNavigateBack()
                    }
                }) {
                    Icon(
                        if (isEditing || embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                        contentDescription = stringResource(
                            if (isEditing || embedded) R.string.common_close else R.string.common_back
                        )
                    )
                }

                // 中间：标题与输入胶囊框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                        .clickable(!isEditing) { onEditingChange(true) }
                        .padding(horizontal = Spacing.sm),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (!isEditing) {
                        val displayTitle = when {
                            title.isNotBlank() -> title
                            url.isNotBlank() -> url.removePrefix("https://").removePrefix("http://")
                            else -> stringResource(R.string.browser_title)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                FeatherIcons.Globe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (title.isNotBlank() || url.isNotBlank()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                FeatherIcons.Globe,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            BasicTextField(
                                value = addressInput,
                                onValueChange = { addressInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    val input = addressInput.text.trim()
                                    if (input.isNotEmpty()) {
                                        onEditingChange(false)
                                        onNavigate(input)
                                    }
                                }),
                                decorationBox = { innerTextField ->
                                    if (addressInput.text.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.browser_address_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (addressInput.text.isNotEmpty()) {
                                IconButton(
                                    onClick = { addressInput = TextFieldValue("") },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        FeatherIcons.X,
                                        contentDescription = stringResource(R.string.common_clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 右侧：开发者工具 + 刷新按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleDevTools,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            FeatherIcons.Code,
                            contentDescription = stringResource(R.string.browser_devtools),
                            tint = if (devToolsOpen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = onReload,
                        enabled = !loading,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.browser_reload),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 底部操作栏独立组件
 */
@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabsCount: Int,
    nightMode: Boolean,
    embedded: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onNewTab: () -> Unit,
    onToggleNightMode: () -> Unit,
    onOpenTabs: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!embedded) Modifier.navigationBarsPadding() else Modifier)
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = DividerDefaults.color.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 后退
                IconButton(
                    onClick = onGoBack,
                    enabled = canGoBack
                ) {
                    Icon(
                        FeatherIcons.ChevronLeft,
                        contentDescription = stringResource(R.string.browser_back),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 2. 前进
                IconButton(
                    onClick = onGoForward,
                    enabled = canGoForward
                ) {
                    Icon(
                        FeatherIcons.ChevronRight,
                        contentDescription = stringResource(R.string.browser_forward),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 3. 新建标签页
                IconButton(onClick = onNewTab) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.browser_new_tab),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 4. 夜间模式切换
                IconButton(onClick = onToggleNightMode) {
                    Icon(
                        if (nightMode) FeatherIcons.Sun else FeatherIcons.Moon,
                        contentDescription = stringResource(
                            if (nightMode) R.string.browser_night_mode_on else R.string.browser_night_mode_off
                        ),
                        tint = if (nightMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 5. 标签页管理按钮（数字方框徽标）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            1.8.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            RoundedCornerShape(7.dp)
                        )
                        .clickable(onClick = onOpenTabs),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$tabsCount",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserTabItemCard(
    tab: BrowserTabState,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when {
        tab.title.isNotBlank() -> tab.title
        tab.url.isNotBlank() -> tab.url.removePrefix("https://").removePrefix("http://")
        else -> stringResource(R.string.browser_tab_default_title)
    }

    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.Globe,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(Spacing.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tab.url.isNotBlank()) {
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (tab.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = Spacing.xs),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.browser_close_tab),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
