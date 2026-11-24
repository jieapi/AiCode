package com.aicode.feature.credentials.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.presentation.CredentialViewModel
import com.aicode.feature.credentials.presentation.QuickLoginUiState
import com.aicode.feature.git.presentation.component.SectionHeader
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsEmptyState
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Github
import compose.icons.feathericons.Key
import compose.icons.feathericons.Plus
import compose.icons.feathericons.User

/**
 * 凭据与署名独立页：底部悬浮 tab 切换「署名」与「凭据」两个标签页。
 * 顶栏「+」新增凭据，编辑/新增态用 [CredentialEditorSheet] 弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialScreen(
    viewModel: CredentialViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 每次进入重新读远程状态：用户可能在终端改过项目级/全局署名，或连接已切换服务器。
    LaunchedEffect(Unit) {
        viewModel.refreshIdentity()
        viewModel.refreshAutoInjectState()
    }

    // toast → Snackbar 一次性消费。
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // 两个标签页的滚动状态提升到页面层，聚合出「是否正在滚动」用于底部 tab 栏滚动弱化。
    val identityScrollState = rememberScrollState()
    val credentialListState = rememberLazyListState()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val tabsScrolling by remember {
        derivedStateOf {
            identityScrollState.isScrollInProgress || credentialListState.isScrollInProgress
        }
    }

    // editingCredential != null -> 编辑现有；editingCredential == null && isAddingCredential -> 新增；否则列表态。
    var editingCredential by remember { mutableStateOf<GitCredential?>(null) }
    var isAddingCredential by remember { mutableStateOf(false) }
    // 右上角 + 打开「添加凭据」方式选择弹层；再进入手动添加或快速登录子流程。
    var showAddSheet by remember { mutableStateOf(false) }
    var showQuickLoginSheet by remember { mutableStateOf(false) }
    val quickLoginState by viewModel.quickLogin.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_credentials_and_identity)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.credential_add))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                when (tab) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(identityScrollState)
                            .padding(horizontal = Spacing.lg)
                            // 底部留出悬浮 tab 栏高度：滚动时内容可滚过 tab 区域被蒙版渐隐，
                            // 滚到底时最后一项停在 tab 上方不被遮挡。
                            .padding(bottom = 70.dp)
                    ) {
                        GitUserIdentityCard(
                            initialName = state.userName,
                            initialEmail = state.userEmail,
                            initialRepoUrl = state.repoUrl,
                            globalHint = state.globalUserName,
                            onSave = viewModel::saveUserIdentity
                        )
                        if (state.isRemote) {
                            Spacer(Modifier.height(Spacing.md))
                            SettingsGroup {
                                SettingsRow(
                                    icon = FeatherIcons.Key,
                                    title = stringResource(R.string.credential_auto_inject_title),
                                    subtitle = stringResource(R.string.credential_auto_inject_hint),
                                    trailing = {
                                        AppSwitch(
                                            checked = state.autoInjectEnabled,
                                            onCheckedChange = viewModel::setAutoInject
                                        )
                                    }
                                )
                            }
                        }
                    }
                    1 -> if (state.credentials.isEmpty()) {
                        SettingsEmptyState(
                            icon = FeatherIcons.Key,
                            title = stringResource(R.string.credential_empty_title),
                            hint = stringResource(R.string.credential_empty_desc),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = Spacing.xl)
                                .padding(bottom = 70.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = credentialListState,
                            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 70.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            item(key = "header") {
                                SectionHeader(stringResource(R.string.git_credentials_count, state.credentials.size))
                            }
                            item(key = "credential-group") {
                                SettingsGroup {
                                    state.credentials.forEachIndexed { index, cred ->
                                        if (index > 0) SettingsDivider()
                                        CredentialItem(
                                            credential = cred,
                                            onEdit = { editingCredential = cred },
                                            onDelete = { viewModel.deleteCredential(cred.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.User, stringResource(R.string.git_tab_identity)),
                    FloatingTabItem(FeatherIcons.Key, stringResource(R.string.git_tab_credentials))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    val editing = editingCredential
    if (editing != null) {
        CredentialEditorSheet(
            initial = editing,
            onDismiss = { editingCredential = null },
            onSave = { viewModel.saveCredential(it); editingCredential = null }
        )
    }

    if (isAddingCredential) {
        CredentialEditorSheet(
            initial = null,
            onDismiss = { isAddingCredential = false },
            onSave = { viewModel.saveCredential(it); isAddingCredential = false }
        )
    }

    if (showAddSheet) {
        CredentialAddSheet(
            onManual = {
                showAddSheet = false
                isAddingCredential = true
            },
            onQuickLogin = {
                showAddSheet = false
                viewModel.startGitHubLogin()
            },
            onDismiss = { showAddSheet = false }
        )
    }

    if (showQuickLoginSheet) {
        QuickLoginSheet(
            onSelectGitHub = {
                showQuickLoginSheet = false
                viewModel.startGitHubLogin()
            },
            onDismiss = { showQuickLoginSheet = false }
        )
    }

    // 设备码流进行中（请求/等待/成功/失败）时展示登录弹层。
    if (quickLoginState !is QuickLoginUiState.Idle) {
        GitHubDeviceFlowSheet(
            state = quickLoginState,
            onCancel = { viewModel.cancelQuickLogin() },
            onRetry = { viewModel.startGitHubLogin() },
            onSuccessDismiss = { viewModel.consumeQuickLoginResult() }
        )
    }

    // 远程模式下首次进入该服务器：询问是否需要 aicode 自动注入凭证
    if (state.showAutoInjectPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAutoInjectPrompt(false) },
            title = { Text(stringResource(R.string.credential_auto_inject_prompt_title)) },
            text = { Text(stringResource(R.string.credential_auto_inject_prompt_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAutoInjectPrompt(true) }) {
                    Text(stringResource(R.string.credential_auto_inject_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAutoInjectPrompt(false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun CredentialItem(
    credential: GitCredential,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标方块：与容器/远程连接行一致，图标按 host 显示平台 logo（github/gitee），其余回退 Key。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                PlatformIcon(
                    host = credential.host,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = credential.host,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    HostBadge(host = credential.host)
                }
                Text(
                    text = credential.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 右侧大于号：与远程连接/MCP 行一致，整行点击编辑。
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.common_edit),
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 平台图标：按 host 显示对应 Git 托管服务 logo（目前支持 GitHub / Gitee），
 * 其余回退通用钥匙图标。单色 tint，与容器/镜像页系统 logo 一致。
 */
@Composable
private fun PlatformIcon(host: String, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        host.contains("github", ignoreCase = true) ->
            Icon(FeatherIcons.Github, contentDescription = null, tint = tint, modifier = modifier)
        host.contains("gitee", ignoreCase = true) ->
            Icon(painterResource(R.drawable.logo_gitee), contentDescription = null, tint = tint, modifier = modifier)
        else ->
            Icon(FeatherIcons.Key, contentDescription = null, tint = tint, modifier = modifier)
    }
}

/** 远程主机品牌徽章：按 host 识别常见 Git 托管服务并显示品牌名，未知取 host 首段域名。 */
@Composable
private fun HostBadge(host: String) {
    val label = remember(host) { hostBadgeLabel(host) }
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

private fun hostBadgeLabel(host: String): String {
    val clean = host
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
    return when {
        clean.contains("github", ignoreCase = true) -> "GitHub"
        clean.contains("gitlab", ignoreCase = true) -> "GitLab"
        clean.contains("gitee", ignoreCase = true) -> "Gitee"
        clean.contains("bitbucket", ignoreCase = true) -> "Bitbucket"
        clean.contains("codeberg", ignoreCase = true) -> "Codeberg"
        clean.contains("azure", ignoreCase = true) || clean.contains("visualstudio", ignoreCase = true) -> "Azure DevOps"
        else -> clean.substringBefore('.').ifBlank { clean }.replaceFirstChar { it.uppercase() }
    }
}
