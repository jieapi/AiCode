package com.aicode.feature.backup.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.backup.data.SyncIntervalUnit
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.UploadCloud
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WebDAV 备份同步独立界面：
 *
 * - 「连接」tab：地址 / 用户名 / 密码 + 连接测试 + 立即同步。
 * - 「自动同步」tab：自动备份同步开关；开启后选择同步内容与间隔（数值 + 单位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSyncScreen(
    onNavigateBack: () -> Unit,
    viewModel: WebDavSyncViewModel = hiltViewModel()
) {
    val url by viewModel.url.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val autoSync by viewModel.autoSync.collectAsStateWithLifecycle()
    val syncProviders by viewModel.syncProviders.collectAsStateWithLifecycle()
    val syncRemote by viewModel.syncRemote.collectAsStateWithLifecycle()
    val syncAppSettings by viewModel.syncAppSettings.collectAsStateWithLifecycle()
    val syncChatHistory by viewModel.syncChatHistory.collectAsStateWithLifecycle()
    val syncMcp by viewModel.syncMcp.collectAsStateWithLifecycle()
    val syncPermissions by viewModel.syncPermissions.collectAsStateWithLifecycle()
    val syncWorkspaceFiles by viewModel.syncWorkspaceFiles.collectAsStateWithLifecycle()
    val intervalValue by viewModel.intervalValue.collectAsStateWithLifecycle()
    val intervalUnit by viewModel.intervalUnit.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val opState by viewModel.opState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState { 2 }
    val connScrollState = rememberScrollState()
    val autoScrollState = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf { connScrollState.isScrollInProgress || autoScrollState.isScrollInProgress }
    }

    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(opState) {
        if (opState is WebDavOpState.Success || opState is WebDavOpState.Error) showResult = true
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.webdav_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { tab ->
                when (tab) {
                    0 -> ConnectionTab(
                        scrollStateHolder = connScrollState,
                        url = url,
                        username = username,
                        password = password,
                        lastSyncAt = lastSyncAt,
                        testing = opState is WebDavOpState.Testing,
                        syncing = opState is WebDavOpState.Syncing,
                        onUrlChange = viewModel::setUrl,
                        onUsernameChange = viewModel::setUsername,
                        onPasswordChange = viewModel::setPassword,
                        onTest = viewModel::testConnection,
                        onSyncNow = viewModel::syncNow
                    )
                    1 -> AutoSyncTab(
                        scrollStateHolder = autoScrollState,
                        autoSync = autoSync,
                        syncProviders = syncProviders,
                        syncRemote = syncRemote,
                        syncChatHistory = syncChatHistory,
                        syncMcp = syncMcp,
                        syncPermissions = syncPermissions,
                        syncAppSettings = syncAppSettings,
                        syncWorkspaceFiles = syncWorkspaceFiles,
                        intervalValue = intervalValue,
                        intervalUnit = intervalUnit,
                        onToggleAutoSync = viewModel::setAutoSync,
                        onToggleProviders = viewModel::setSyncProviders,
                        onToggleRemote = viewModel::setSyncRemote,
                        onToggleChatHistory = viewModel::setSyncChatHistory,
                        onToggleMcp = viewModel::setSyncMcp,
                        onTogglePermissions = viewModel::setSyncPermissions,
                        onToggleAppSettings = viewModel::setSyncAppSettings,
                        onToggleWorkspaceFiles = viewModel::setSyncWorkspaceFiles,
                        onIntervalValueChange = viewModel::setIntervalValue,
                        onIntervalUnitChange = viewModel::setIntervalUnit
                    )
                }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Cloud, stringResource(R.string.webdav_tab_connection)),
                    FloatingTabItem(FeatherIcons.RefreshCw, stringResource(R.string.webdav_tab_auto))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showResult) {
        val message = when (val s = opState) {
            is WebDavOpState.Success -> s.message
            is WebDavOpState.Error -> s.message
            else -> ""
        }
        val isError = opState is WebDavOpState.Error
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResult = false; viewModel.resetOpState() },
            title = { Text(stringResource(if (isError) R.string.webdav_op_failed else R.string.webdav_op_done)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showResult = false; viewModel.resetOpState() }) {
                    Text(stringResource(R.string.common_got_it))
                }
            }
        )
    }
}

@Composable
private fun ConnectionTab(
    scrollStateHolder: androidx.compose.foundation.ScrollState,
    url: String,
    username: String,
    password: String,
    lastSyncAt: Long?,
    testing: Boolean,
    syncing: Boolean,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateHolder)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = 90.dp)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.webdav_server))
        SettingsGroup {
            Column(modifier = Modifier.padding(Spacing.md)) {
                AppTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = stringResource(R.string.webdav_address),
                    placeholder = "https://example.com/dav/",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = stringResource(R.string.webdav_username),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.webdav_password),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = !testing && !syncing,
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.webdav_test))
                }
            }
            Button(
                onClick = onSyncNow,
                enabled = !testing && !syncing,
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(FeatherIcons.UploadCloud, contentDescription = null, modifier = Modifier.width(18.dp).height(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.webdav_sync_now))
                }
            }
        }

        lastSyncAt?.let { ts ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.webdav_last_sync, formatTime(ts)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AutoSyncTab(
    scrollStateHolder: androidx.compose.foundation.ScrollState,
    autoSync: Boolean,
    syncProviders: Boolean,
    syncRemote: Boolean,
    syncChatHistory: Boolean,
    syncMcp: Boolean,
    syncPermissions: Boolean,
    syncAppSettings: Boolean,
    syncWorkspaceFiles: Boolean,
    intervalValue: Int,
    intervalUnit: SyncIntervalUnit,
    onToggleAutoSync: (Boolean) -> Unit,
    onToggleProviders: (Boolean) -> Unit,
    onToggleRemote: (Boolean) -> Unit,
    onToggleChatHistory: (Boolean) -> Unit,
    onToggleMcp: (Boolean) -> Unit,
    onTogglePermissions: (Boolean) -> Unit,
    onToggleAppSettings: (Boolean) -> Unit,
    onToggleWorkspaceFiles: (Boolean) -> Unit,
    onIntervalValueChange: (Int) -> Unit,
    onIntervalUnitChange: (SyncIntervalUnit) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateHolder)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = 90.dp)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.webdav_auto_sync))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.webdav_auto_sync_toggle),
                subtitle = stringResource(R.string.webdav_auto_sync_toggle_desc),
                trailing = {
                    AppSwitch(checked = autoSync, onCheckedChange = onToggleAutoSync)
                }
            )
        }

        if (autoSync) {
            SettingsGroupHeader(text = stringResource(R.string.webdav_sync_content))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.common_ai_providers),
                    trailing = { AppSwitch(checked = syncProviders, onCheckedChange = onToggleProviders) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_remote),
                    trailing = { AppSwitch(checked = syncRemote, onCheckedChange = onToggleRemote) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_chat_history),
                    trailing = { AppSwitch(checked = syncChatHistory, onCheckedChange = onToggleChatHistory) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_mcp),
                    trailing = { AppSwitch(checked = syncMcp, onCheckedChange = onToggleMcp) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_permissions),
                    trailing = { AppSwitch(checked = syncPermissions, onCheckedChange = onTogglePermissions) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_app_settings),
                    trailing = { AppSwitch(checked = syncAppSettings, onCheckedChange = onToggleAppSettings) }
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.backup_data_workspace),
                    trailing = { AppSwitch(checked = syncWorkspaceFiles, onCheckedChange = onToggleWorkspaceFiles) }
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.webdav_interval))
            SettingsGroup {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    IntervalRow(
                        value = intervalValue,
                        unit = intervalUnit,
                        onValueChange = onIntervalValueChange,
                        onUnitChange = onIntervalUnitChange
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalRow(
    value: Int,
    unit: SyncIntervalUnit,
    onValueChange: (Int) -> Unit,
    onUnitChange: (SyncIntervalUnit) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val units = listOf(SyncIntervalUnit.MINUTE, SyncIntervalUnit.HOUR, SyncIntervalUnit.DAY)
    val labels = listOf(
        stringResource(R.string.webdav_unit_minute),
        stringResource(R.string.webdav_unit_hour),
        stringResource(R.string.webdav_unit_day)
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = text,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(4)
                    text = digits
                    digits.toIntOrNull()?.let { if (it > 0) onValueChange(it) }
                },
                label = stringResource(R.string.webdav_interval_number),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = stringResource(R.string.webdav_interval_every),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        SegmentedTabs(
            selected = units.indexOf(unit).coerceAtLeast(0),
            labels = labels,
            onSelect = { index -> onUnitChange(units[index]) }
        )
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
