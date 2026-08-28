package com.aicode.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.FloatingTabBar
import com.aicode.core.ui.FloatingTabItem
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.model.mergeModelMetadata
import com.aicode.feature.settings.presentation.FetchState
import com.aicode.feature.settings.presentation.SettingsViewModel
import androidx.compose.ui.platform.LocalFocusManager
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import android.widget.Toast
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Slash
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.X
import com.aicode.feature.agent.presentation.component.AdaptiveCardView
import com.aicode.feature.settings.domain.model.ProviderBalanceResult
import com.aicode.feature.settings.domain.model.ProviderBalanceState
import androidx.compose.ui.res.stringResource
import com.aicode.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorScreen(
    viewModel: SettingsViewModel,
    initialProvider: AIProviderConfig?,
    onNavigateBack: () -> Unit,
    onSave: (AIProviderConfig) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var name by remember { mutableStateOf(initialProvider?.name ?: "") }
    var apiKey by remember { mutableStateOf(initialProvider?.apiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(initialProvider?.baseUrl ?: "") }
    var useFullUrl by remember { mutableStateOf(initialProvider?.useFullUrl ?: false) }
    var useResponseApi by remember { mutableStateOf(initialProvider?.useResponseApi ?: false) }
    var anthropicCacheBreakpoints by remember { mutableStateOf(initialProvider?.anthropicCacheBreakpoints ?: true) }
    var openaiChatCacheKey by remember { mutableStateOf(initialProvider?.openaiChatCacheKey ?: false) }
    var balanceScriptPath by remember { mutableStateOf(initialProvider?.balanceScriptPath ?: "") }
    var balanceRefreshInterval by remember { mutableIntStateOf(initialProvider?.balanceRefreshInterval ?: 5) }
    var userAgent by remember { mutableStateOf(initialProvider?.userAgent ?: "") }
    var proxyEnabled by remember { mutableStateOf(initialProvider?.proxyEnabled ?: false) }
    var proxyType by remember { mutableStateOf(initialProvider?.proxyType ?: ProxyType.HTTP) }
    var proxyHost by remember { mutableStateOf(initialProvider?.proxyHost ?: "") }
    var proxyPort by remember { mutableIntStateOf(initialProvider?.proxyPort ?: 0) }
    var proxyUsername by remember { mutableStateOf(initialProvider?.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(initialProvider?.proxyPassword ?: "") }
    var isEnabled by remember { mutableStateOf(initialProvider?.isEnabled ?: true) }
    var type by remember { mutableStateOf(initialProvider?.type ?: ProviderType.OPENAI) }
    val providerId = remember { initialProvider?.id ?: System.currentTimeMillis().toString() }
    val models = remember { mutableStateListOf<String>().apply { addAll(initialProvider?.models ?: emptyList()) } }
    val customMetadataStore = remember { CustomModelMetadataStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var customMetadata by remember { mutableStateOf<Map<String, ModelMetadata>>(emptyMap()) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    var showTypeSheet by remember { mutableStateOf(false) }
    var showAddModelSheet by remember { mutableStateOf(false) }
    var showFetchDialog by remember { mutableStateOf(false) }
    var showScriptPickerSheet by remember { mutableStateOf(false) }
    var showIntervalSheet by remember { mutableStateOf(false) }
    var showProxyPage by remember { mutableStateOf(false) }
    var fetchDialogKey by remember { mutableIntStateOf(0) }

    // 两个 tab 的滚动状态提升到页面层，聚合出「是否正在滚动」供底部 tab栏滚动弱化（同 Git 页面）。
    val configScrollState = rememberScrollState()
    val modelsScrollState = rememberScrollState()
    val tabsScrolling by remember {
        derivedStateOf {
            configScrollState.isScrollInProgress || modelsScrollState.isScrollInProgress
        }
    }

    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    val testResults by viewModel.testResults.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val proxyTestState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    val balanceTestState by viewModel.balanceTestState.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val modelSnapshot = models.toList()

    DisposableEffect(Unit) {
        viewModel.resetFetchState()
        viewModel.clearTestResults()
        viewModel.clearBalanceTestState()
        onDispose {
            viewModel.resetFetchState()
            viewModel.clearTestResults()
            viewModel.clearBalanceTestState()
        }
    }

    LaunchedEffect(type, modelSnapshot) {
        viewModel.resolveModelMetadata(providerId, type, modelSnapshot)
    }

    LaunchedEffect(providerId, modelSnapshot) {
        customMetadata = customMetadataStore.all()
    }

    fun currentConfig() = AIProviderConfig(
        id = providerId,
        name = name.ifEmpty { context.getString(R.string.provider_new) },
        type = type,
        apiKey = apiKey,
        baseUrl = baseUrl.ifBlank { defaultProviderBaseUrl(type) },
        useFullUrl = useFullUrl,
        isEnabled = isEnabled,
        defaultModel = initialProvider?.defaultModel ?: "",
        models = models.toList(),
        selectedModel = initialProvider?.selectedModel ?: "",
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints,
        openaiChatCacheKey = openaiChatCacheKey,
        balanceScriptPath = balanceScriptPath,
        balanceRefreshInterval = balanceRefreshInterval,
        userAgent = userAgent,
        sortOrder = initialProvider?.sortOrder ?: -1,
        proxyEnabled = proxyEnabled,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUsername = proxyUsername,
        proxyPassword = proxyPassword
    )

    // 新建场景下判断用户是否填写了实质内容：名称、API Key、Base URL 任一非空白，或已添加模型。
    // 全空白时退出不应落库，否则会存入一条名为“新提供商”的空记录。
    fun hasSubstantiveInput(): Boolean =
        initialProvider != null ||
            name.isNotBlank() ||
            apiKey.isNotBlank() ||
            baseUrl.isNotBlank() ||
            balanceScriptPath.isNotBlank() ||
            models.isNotEmpty()

    fun saveCurrent() {
        if (!hasSubstantiveInput()) return
        onSave(currentConfig())
    }

    fun saveAndNavigateBack() {
        saveCurrent()
        onNavigateBack()
    }

    if (showProxyPage) {
        BackHandler { showProxyPage = false }
    } else {
        BackHandler { saveAndNavigateBack() }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(if (initialProvider == null) stringResource(R.string.provider_add) else stringResource(R.string.provider_edit)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = { saveAndNavigateBack() }) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                        showAddModelSheet = true
                    }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.provider_add_model))
                    }
                }
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { tab ->
                if (tab == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(configScrollState)
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = 70.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                    // ── 基本信息 ──
                    SettingsGroupHeader(text = stringResource(R.string.provider_section_basic))
                    SettingsGroup {
                        ProviderTextFieldRow(
                            label = stringResource(R.string.common_name),
                            value = name,
                            onValueChange = { name = it }
                        )
                        SettingsDivider()
                        ProviderTextFieldRow(
                            label = "API Key",
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            visualTransformation = if (apiKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailing = {
                                Icon(
                                    imageVector = if (apiKeyVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                    contentDescription = stringResource(
                                        if (apiKeyVisible) R.string.provider_hide_api_key else R.string.provider_show_api_key
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { apiKeyVisible = !apiKeyVisible }
                                        .padding(2.dp)
                                )
                            }
                        )
                        SettingsDivider()
                        ProviderTextFieldRow(
                            label = "Base URL",
                            value = baseUrl,
                            onValueChange = { baseUrl = it }
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.provider_section_type),
                            onClick = { showTypeSheet = true },
                            trailing = {
                                Text(
                                    text = providerTypeLabel(type),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }

                    // ── 选项 ──
                    SettingsGroupHeader(text = stringResource(R.string.provider_section_options))
                    SettingsGroup {
                        ProviderSwitchRow(
                            title = stringResource(R.string.common_enabled),
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it }
                        )
                        SettingsDivider()
                        ProviderSwitchRow(
                            title = stringResource(R.string.provider_full_url),
                            subtitle = stringResource(R.string.provider_full_url_desc),
                            checked = useFullUrl,
                            onCheckedChange = { useFullUrl = it }
                        )
                        if (type == ProviderType.OPENAI) {
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(R.string.provider_response_api),
                                checked = useResponseApi,
                                onCheckedChange = { useResponseApi = it }
                            )
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(R.string.provider_cache_openai_chat_title),
                                subtitle = stringResource(R.string.provider_cache_openai_chat_subtitle),
                                checked = openaiChatCacheKey,
                                onCheckedChange = { openaiChatCacheKey = it }
                            )
                        }
                        if (type == ProviderType.ANTHROPIC) {
                            SettingsDivider()
                            ProviderSwitchRow(
                                title = stringResource(R.string.provider_cache_anthropic_title),
                                subtitle = stringResource(R.string.provider_cache_anthropic_subtitle),
                                checked = anthropicCacheBreakpoints,
                                onCheckedChange = { anthropicCacheBreakpoints = it }
                            )
                        }
                        SettingsDivider()
                        ProviderTextFieldRow(
                            label = stringResource(R.string.provider_user_agent),
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            placeholder = stringResource(R.string.provider_user_agent_hint)
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.proxy_title),
                            onClick = { showProxyPage = true },
                            trailing = {
                                Text(
                                    text = if (proxyEnabled) {
                                        "${proxyTypeLabel(proxyType)} ${proxyHost}:${proxyPort}"
                                    } else {
                                        stringResource(R.string.provider_proxy_off)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }

                    // ── 自定义面板 (DIY) ──
                    SettingsGroupHeader(text = stringResource(R.string.provider_section_balance))
                    SettingsGroup {
                        ProviderTextFieldRow(
                            label = stringResource(R.string.provider_balance_script),
                            value = balanceScriptPath,
                            onValueChange = { balanceScriptPath = it },
                            placeholder = stringResource(R.string.provider_balance_script_placeholder),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (balanceScriptPath.isNotBlank()) {
                                        IconButton(
                                            onClick = { balanceScriptPath = "" },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = FeatherIcons.X,
                                                contentDescription = stringResource(R.string.provider_balance_clear_script),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { showScriptPickerSheet = true },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = FeatherIcons.Folder,
                                            contentDescription = stringResource(R.string.provider_balance_select_script),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        )
                        SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.provider_balance_test_btn),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.provider_balance_script_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.testBalanceScript(currentConfig(), balanceScriptPath)
                                },
                                enabled = balanceScriptPath.isNotBlank() && balanceTestState !is ProviderBalanceState.Loading,
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (balanceTestState is ProviderBalanceState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = FeatherIcons.Play,
                                        contentDescription = stringResource(R.string.provider_balance_run_test),
                                        tint = if (balanceScriptPath.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        if (balanceTestState !is ProviderBalanceState.Idle) {
                            SettingsDivider()
                            BalanceTestResultBox(
                                state = balanceTestState,
                                providerName = name.ifBlank { stringResource(R.string.provider_balance_preview_title) }
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(modelsScrollState)
                        .padding(horizontal = Spacing.lg)
                        .padding(bottom = 70.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // ── 模型管理 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.provider_models_count, models.size),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                fetchDialogKey++
                                showFetchDialog = true
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(FeatherIcons.DownloadCloud, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.provider_fetch_models))
                        }
                    }
                    if (models.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = Spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Text(
                                    text = stringResource(R.string.provider_no_models),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.provider_no_models_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        SettingsGroup {
                            models.forEachIndexed { index, model ->
                                if (index > 0) {
                                    SettingsDivider()
                                }
                                ProviderModelRow(
                                    model = model,
                                    metadata = mergeModelMetadata(model, modelMetadata[model], customMetadata["$providerId:$model"]),
                                    testing = model in testing,
                                    result = testResults[model],
                                    onTest = { viewModel.testModel(currentConfig(), model) },
                                    onEdit = {
                                        editingModel = model
                                        showAddModelSheet = true
                                    },
                                    onRemove = {
                                        models.remove(model)
                                        scope.launch {
                                            customMetadataStore.remove(providerId, model)
                                            customMetadata = customMetadataStore.all()
                                        }
                                        saveCurrent()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            }

            FloatingTabBar(
                pagerState = pagerState,
                items = listOf(
                    FloatingTabItem(FeatherIcons.Sliders, stringResource(R.string.provider_config)),
                    FloatingTabItem(FeatherIcons.Cpu, stringResource(R.string.common_model))
                ),
                maskColor = settingsPageBackground(),
                isScrolling = tabsScrolling,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showTypeSheet) {
        ProviderTypeSelectionSheet(
            selected = type,
            onSelected = { type = it },
            onDismiss = { showTypeSheet = false }
        )
    }

    if (showProxyPage) {
        ProviderProxyPage(
            config = ProxyConfig(
                enabled = proxyEnabled,
                type = proxyType,
                host = proxyHost,
                port = proxyPort,
                username = proxyUsername,
                password = proxyPassword
            ),
            testState = proxyTestState,
            onBack = { showProxyPage = false },
            onSetEnabled = { proxyEnabled = it },
            onSetType = { proxyType = it },
            onSetHost = { proxyHost = it },
            onSetPort = { proxyPort = it },
            onSetUsername = { proxyUsername = it },
            onSetPassword = { proxyPassword = it },
            onTestProxy = { url ->
                viewModel.testProxy(
                    ProxyConfig(
                        enabled = true,
                        type = proxyType,
                        host = proxyHost,
                        port = proxyPort,
                        username = proxyUsername,
                        password = proxyPassword
                    ),
                    url
                )
            }
        )
    }

    if (showScriptPickerSheet) {
        ScriptPickerBottomSheet(
            scripts = viewModel.listAvailableBalanceScripts(),
            onSelect = { selectedScript ->
                balanceScriptPath = selectedScript
                showScriptPickerSheet = false
            },
            onDismiss = { showScriptPickerSheet = false }
        )
    }

    if (showIntervalSheet) {
        IntervalSelectionSheet(
            selected = balanceRefreshInterval,
            onSelected = {
                balanceRefreshInterval = it
                showIntervalSheet = false
            },
            onDismiss = { showIntervalSheet = false }
        )
    }

    if (showAddModelSheet) {
        key(editingModel) {
            AddModelSheet(
                existingModels = models,
                title = if (editingModel != null) {
                    stringResource(R.string.provider_edit_model)
                } else {
                    stringResource(R.string.provider_add_model)
                },
                confirmLabel = if (editingModel != null) {
                    stringResource(R.string.common_save)
                } else {
                    stringResource(R.string.common_add)
                },
                initial = editingModel?.let { mergeModelMetadata(it, modelMetadata[it], customMetadata["$providerId:$it"]) },
                onSave = { model, meta ->
                    val editing = editingModel
                    if (editing != null && model != editing) {
                        val idx = models.indexOf(editing)
                        if (idx >= 0) models[idx] = model else models.add(model)
                    } else if (model !in models) {
                        models.add(model)
                    }
                    scope.launch {
                        if (editing != null && model != editing) {
                            customMetadataStore.remove(providerId, editing)
                        }
                        customMetadataStore.put(providerId, model, meta)
                        customMetadata = customMetadataStore.all()
                    }
                    saveCurrent()
                    editingModel = null
                    showAddModelSheet = false
                },
                onDismiss = {
                    editingModel = null
                    showAddModelSheet = false
                }
            )
        }
    }

    // 模型拉取结果弹窗
    if (showFetchDialog) {
        key(fetchDialogKey) {
            FetchModelsDialog(
                fetchState = fetchState,
                modelMetadata = modelMetadata,
                existingModels = models,
                onFetchModels = { viewModel.fetchModels(currentConfig()) },
                onAddModel = { m ->
                    if (m !in models) {
                        models.add(m)
                        saveCurrent()
                    }
                },
                onDismiss = {
                    showFetchDialog = false
                    viewModel.resetFetchState()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelSheet(
    existingModels: List<String>,
    title: String,
    confirmLabel: String,
    initial: ModelMetadata?,
    onSave: (String, ModelMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)
    var modelName by remember { mutableStateOf(initial?.id ?: "") }
    var supportsVision by remember { mutableStateOf(initial?.supportsVision ?: false) }
    var supportsImageOutput by remember { mutableStateOf(initial?.supportsImageOutput ?: false) }
    var supportsTools by remember { mutableStateOf(initial?.supportsTools ?: false) }
    var supportsReasoning by remember { mutableStateOf(initial?.supportsReasoning ?: false) }
    var inputTokens by remember { mutableStateOf((initial?.inputTokens ?: initial?.contextTokens?.takeIf { it > 0 })?.toString() ?: "") }
    var outputTokens by remember { mutableStateOf(initial?.outputTokens?.toString() ?: "") }
    var inputPrice by remember { mutableStateOf(initial?.inputCostUsdPerM?.toString() ?: "") }
    var outputPrice by remember { mutableStateOf(initial?.outputCostUsdPerM?.toString() ?: "") }
    var cacheReadPrice by remember { mutableStateOf(initial?.cacheReadCostUsdPerM?.toString() ?: "") }
    val trimmedModel = modelName.trim()
    val duplicate = existingModels.any { it == trimmedModel && it != initial?.id }
    val canSave = trimmedModel.isNotEmpty() && !duplicate

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
        ) {
            // ── 顶部标题栏：居中标题（仿 MCP 编辑对话框）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(36.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(36.dp))
            }

            // ── 表单区：输入框直接铺背景，卡片承载能力开关 ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .nestedScroll(flingFix),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_name),
                    value = modelName,
                    onValueChange = { modelName = it }
                )
                if (duplicate) {
                    Text(
                        text = stringResource(R.string.provider_model_already_added),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_context_input),
                    value = inputTokens,
                    onValueChange = { inputTokens = it }
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_context_output),
                    value = outputTokens,
                    onValueChange = { outputTokens = it }
                )

                SectionLabel(stringResource(R.string.provider_model_section_price))
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_input),
                    value = inputPrice,
                    onValueChange = { inputPrice = it },
                    keyboardType = KeyboardType.Decimal
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_output),
                    value = outputPrice,
                    onValueChange = { outputPrice = it },
                    keyboardType = KeyboardType.Decimal
                )
                ModelSheetTextField(
                    label = stringResource(R.string.provider_model_price_cache_read),
                    value = cacheReadPrice,
                    onValueChange = { cacheReadPrice = it },
                    keyboardType = KeyboardType.Decimal
                )

                SectionLabel(stringResource(R.string.provider_model_capabilities))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        // 中性浅灰，避免 surfaceVariant 在蓝调主题下偏蓝。
                        containerColor = MaterialTheme.semanticColors.mutedSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_cap_vision),
                            checked = supportsVision,
                            onCheckedChange = { supportsVision = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_cap_image_output),
                            checked = supportsImageOutput,
                            onCheckedChange = { supportsImageOutput = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_capability_tools),
                            checked = supportsTools,
                            onCheckedChange = { supportsTools = it }
                        )
                        SettingsDivider()
                        CapabilitySwitchRow(
                            title = stringResource(R.string.provider_model_capability_reasoning),
                            checked = supportsReasoning,
                            onCheckedChange = { supportsReasoning = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── 底部保存按钮（仿 MCP 编辑对话框）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Button(
                    enabled = canSave,
                    onClick = {
                        val input = inputTokens.trim().toIntOrNull()
                        val output = outputTokens.trim().toIntOrNull()
                        val meta = ModelMetadata(
                            id = trimmedModel,
                            displayName = trimmedModel,
                            contextTokens = input ?: 0,
                            inputTokens = input,
                            outputTokens = output,
                            inputCostUsdPerM = inputPrice.trim().toDoubleOrNull(),
                            outputCostUsdPerM = outputPrice.trim().toDoubleOrNull(),
                            cacheReadCostUsdPerM = cacheReadPrice.trim().toDoubleOrNull(),
                            supportsVision = supportsVision,
                            supportsImageOutput = supportsImageOutput,
                            supportsTools = supportsTools,
                            supportsReasoning = supportsReasoning
                        )
                        onSave(trimmedModel, meta)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(confirmLabel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/** 分段组小标题：灰色小字、紧凑间距，直接铺在弹窗背景上（无卡片）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.semanticColors.subtleText,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.xs)
    )
}

/** 添加/编辑模型弹窗内的全宽输入框：样式与 MCP 编辑对话框一致。 */
@Composable
private fun ModelSheetTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 能力开关行：标题 + 右侧 Switch，卡片内一行。 */
@Composable
private fun CapabilitySwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FetchModelsDialog(
    fetchState: FetchState,
    modelMetadata: Map<String, ModelMetadata>,
    existingModels: List<String>,
    onFetchModels: () -> Unit,
    onAddModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var searchQuery by remember { mutableStateOf("") }
    var showDebugSheet by remember { mutableStateOf(false) }

    val debugInfo = when (fetchState) {
        is FetchState.Success -> fetchState.debugInfo
        is FetchState.Error -> fetchState.debugInfo
        else -> null
    }

    LaunchedEffect(Unit) {
        // Wait for bottom sheet animation to smooth out before firing network request
        delay(300)
        onFetchModels()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.provider_fetch_models),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint),
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )

            when (fetchState) {
                is FetchState.Loading -> {
                    FetchModelsSkeleton()
                }
                is FetchState.Error -> {
                    SettingsGroup {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val displayMsg = if (debugInfo != null && debugInfo.responseCode > 0) {
                                "HTTP ${debugInfo.responseCode} · ${debugInfo.latencyMs}ms"
                            } else {
                                val codeMatch = Regex("""(?i)(HTTP\s*\d{3}|code[:\s]+[a-zA-Z0-9_]+)""").find(fetchState.message)
                                if (codeMatch != null) codeMatch.value
                                else fetchState.message.lines().firstOrNull()?.let { if (it.length > 28) it.take(28) + "..." else it } ?: "Error"
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (debugInfo != null) Modifier.clickable { showDebugSheet = true } else Modifier
                                    )
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.AlertCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = displayMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                if (debugInfo != null) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = FeatherIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                is FetchState.Success -> {
                    val newModels = fetchState.models.filter { it !in existingModels && it.contains(searchQuery, ignoreCase = true) }
                    if (newModels.isEmpty()) {
                        SettingsGroup {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 360.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(R.string.provider_no_matching_models), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        // 按品牌分组，每个分类一个独立卡片。"other" 分组永远在最后，其他按显示名称排序。
                        val grouped = newModels.groupBy { m -> modelBrandKey(m) }
                            .toSortedMap(compareBy<String> { it == "other" }.thenBy { brandDisplayName(context, it) })

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp, max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            grouped.forEach { (brandKey, models) ->
                                item(key = "header_$brandKey") {
                                    SettingsGroupHeader("${brandDisplayName(context, brandKey)} (${models.size})")
                                }
                                item(key = "card_$brandKey") {
                                    SettingsGroup {
                                        models.forEachIndexed { index, m ->
                                            if (index > 0) {
                                                SettingsDivider()
                                            }
                                            FetchModelRow(
                                                model = m,
                                                metadata = modelMetadata[m],
                                                onAdd = { onAddModel(m) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    SettingsGroup {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 360.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.provider_please_wait), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (showDebugSheet && debugInfo != null) {
        ModelTestDetailBottomSheet(
            model = stringResource(R.string.provider_fetch_models),
            result = debugInfo,
            onDismiss = { showDebugSheet = false }
        )
    }
}

/** 拉取模型加载骨架屏：模拟品牌标题 + 模型行占位块，避免加载时空白/转圈。 */
@Composable
private fun FetchModelsSkeleton() {
    val block = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        MaterialTheme.semanticColors.subtleBorder
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(3) {
            SkeletonBlock(
                width = 80.dp,
                height = 14.dp,
                color = block,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
            SettingsGroup {
                repeat(3) { idx ->
                    if (idx > 0) {
                        SettingsDivider()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SkeletonBlock(width = 24.dp, height = 24.dp, color = block, shape = RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SkeletonBlock(width = 120.dp, height = 14.dp, color = block)
                            SkeletonBlock(width = 80.dp, height = 10.dp, color = block)
                        }
                        SkeletonBlock(width = 48.dp, height = 24.dp, color = block, shape = RoundedCornerShape(50))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    color: Color,
    shape: Shape = RoundedCornerShape(4.dp),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(color)
    )
}

internal fun defaultProviderBaseUrl(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "https://api.anthropic.com/"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/"
    else -> "https://api.openai.com/"
}

/** 分组内输入行：全宽 AppTextField，可选密文转换与尾随操作。 */
@Composable
private fun ProviderTextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = if (placeholder.isNotBlank()) placeholder else null,
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

/** 分组内开关行：标题 + 可选副标题 + 右侧 Switch。 */
@Composable
private fun ProviderSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 提供商类型选择底部弹窗，样式与主题选择弹窗一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderProxyPage(
    config: ProxyConfig,
    testState: SettingsViewModel.ProxyTestUiState,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetType: (ProxyType) -> Unit,
    onSetHost: (String) -> Unit,
    onSetPort: (Int) -> Unit,
    onSetUsername: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    onTestProxy: (String) -> Unit
) {
    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.proxy_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        ProxySection(
            config = config,
            testState = testState,
            onTestProxy = onTestProxy,
            onSetEnabled = onSetEnabled,
            onSetType = onSetType,
            onSetHost = onSetHost,
            onSetPort = onSetPort,
            onSetUsername = onSetUsername,
            onSetPassword = onSetPassword,
            onSetNoProxy = {},
            showNoProxy = false,
            modifier = Modifier.padding(padding)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeSelectionSheet(
    selected: ProviderType,
    onSelected: (ProviderType) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.provider_section_type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            ProviderType.entries.forEach { providerType ->
                val isSelected = providerType == selected
                Surface(
                    onClick = {
                        onDismiss()
                        onSelected(providerType)
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = providerTypeLabel(providerType),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun providerTypeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> "OpenAI"
    ProviderType.ANTHROPIC -> "Anthropic"
    ProviderType.GEMINI -> "Gemini"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptPickerBottomSheet(
    scripts: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.provider_balance_select_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.sm)
            )
            Text(
                text = stringResource(R.string.provider_balance_script_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.provider_balance_no_scripts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                scripts.forEach { scriptName ->
                    Surface(
                        onClick = { onSelect(scriptName) },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = FeatherIcons.FileText,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                text = scriptName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 脚本原始输出底部弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawOutputBottomSheet(
    rawOutput: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val light = settingsLightMode()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (light) Color.White else MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 顶栏：标题 + 复制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.provider_balance_raw_output),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("rawOutput", rawOutput)))
                        }
                        copied = true
                        Toast.makeText(context, context.getString(R.string.common_copy_success), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = stringResource(R.string.common_copy),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 原始输出内容容器
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.semanticColors.mutedSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.md)
                ) {
                    Text(
                        text = rawOutput,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceTestResultBox(
    state: ProviderBalanceState,
    providerName: String = ""
) {
    var lastSuccessResult by remember { mutableStateOf<ProviderBalanceResult?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lastRawOutput by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    var showRawOutputSheet by remember { mutableStateOf(false) }
    val light = settingsLightMode()

    LaunchedEffect(state) {
        when (state) {
            is ProviderBalanceState.Success -> {
                lastSuccessResult = state.result
                lastRawOutput = state.result.rawOutput
                lastError = null
            }
            is ProviderBalanceState.Error -> {
                lastError = state.message
                lastRawOutput = state.rawOutput
                lastSuccessResult = null
            }
            is ProviderBalanceState.Loading -> {
                // 保持已有的 lastSuccessResult，不清除，防止高度塌陷
            }
            ProviderBalanceState.Idle -> {
                lastSuccessResult = null
                lastError = null
                lastRawOutput = ""
            }
        }
    }

    val isRunning = state is ProviderBalanceState.Loading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (lastSuccessResult != null) {
            val card = lastSuccessResult!!.card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.semanticColors.mutedSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (isRunning) 0.6f else 1f)
            ) {
                if (!isExpanded) {
                    // 折叠状态（Compact）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { isExpanded = true }
                            .padding(start = Spacing.md, top = 8.dp, bottom = 8.dp, end = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (providerName.isNotBlank()) {
                            Text(
                                text = providerName,
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = Spacing.md)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            AdaptiveCardView(
                                card = card,
                                isExpanded = false
                            )
                        }
                        if (lastRawOutput.isNotBlank()) {
                            IconButton(
                                onClick = { showRawOutputSheet = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Terminal,
                                    contentDescription = stringResource(R.string.provider_balance_raw_output),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = { isExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.ChevronDown,
                                contentDescription = stringResource(R.string.common_expand),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 展开状态
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = providerName.ifBlank { stringResource(R.string.provider_balance_preview_title) },
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (lastRawOutput.isNotBlank()) {
                                    IconButton(
                                        onClick = { showRawOutputSheet = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = FeatherIcons.Terminal,
                                            contentDescription = stringResource(R.string.provider_balance_raw_output),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { isExpanded = false },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = FeatherIcons.ChevronUp,
                                        contentDescription = stringResource(R.string.common_collapse),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        AdaptiveCardView(
                            card = card,
                            isExpanded = true
                        )
                    }
                }
            }
        } else if (lastError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.AlertCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(R.string.provider_balance_test_failed),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                                lastError?.takeIf { it.isNotBlank() }?.let { message ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        if (lastRawOutput.isNotBlank()) {
                            IconButton(
                                onClick = { showRawOutputSheet = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.Terminal,
                                    contentDescription = stringResource(R.string.provider_balance_raw_output),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        } else if (isRunning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.provider_balance_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showRawOutputSheet && lastRawOutput.isNotBlank()) {
        RawOutputBottomSheet(
            rawOutput = lastRawOutput,
            onDismiss = { showRawOutputSheet = false }
        )
    }
}

private val INTERVAL_OPTIONS = listOf(0, 1, 3, 5, 10, 15, 30)

@Composable
private fun formatIntervalLabel(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.provider_balance_interval_manual)
    1 -> stringResource(R.string.provider_balance_interval_1m)
    3 -> stringResource(R.string.provider_balance_interval_3m)
    5 -> stringResource(R.string.provider_balance_interval_5m)
    10 -> stringResource(R.string.provider_balance_interval_10m)
    15 -> stringResource(R.string.provider_balance_interval_15m)
    30 -> stringResource(R.string.provider_balance_interval_30m)
    else -> stringResource(R.string.provider_balance_interval_minutes, minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSelectionSheet(
    selected: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.provider_balance_interval_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            INTERVAL_OPTIONS.forEach { interval ->
                val isSelected = interval == selected
                Surface(
                    onClick = {
                        onSelected(interval)
                        onDismiss()
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatIntervalLabel(interval),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}