package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.presentation.SettingsViewModel.ProxyTestUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/** 默认测试目标：返回 204 的连通性端点，不依赖响应体格式。 */
private const val DEFAULT_TEST_URL = "https://www.google.com"

/**
 * 全局 HTTP 代理设置页：开关/类型/服务器/认证/NO_PROXY 合并在一张卡片，
 * 连通性测试独立一张卡片（含自定义测试 URL）。
 *
 * 所有字段即时写入持久化，不设保存按钮——代理链路（App 请求与容器内命令）每次
 * 读取最新配置，新连接立即生效，无需重启。
 */
@Composable
internal fun ProxySection(
    modifier: Modifier = Modifier,
    config: ProxyConfig,
    testState: ProxyTestUiState,
    onTestProxy: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetType: (ProxyType) -> Unit,
    onSetHost: (String) -> Unit,
    onSetPort: (Int) -> Unit,
    onSetUsername: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    onSetNoProxy: (String) -> Unit,
    showNoProxy: Boolean = true
) {
    var showTypeSheet by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 代理设置（开关/类型/服务器/认证/NO_PROXY 合并一张卡片） ──
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.proxy_enable),
                subtitle = stringResource(R.string.proxy_enable_subtitle),
                trailing = {
                    AppSwitch(
                        checked = config.enabled,
                        onCheckedChange = onSetEnabled
                    )
                }
            )

            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.proxy_type),
                onClick = { showTypeSheet = true },
                trailing = {
                    Text(
                        text = proxyTypeLabel(config.type),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                AppTextField(
                    value = config.host,
                    onValueChange = onSetHost,
                    label = stringResource(R.string.proxy_host),
                    placeholder = stringResource(R.string.proxy_host_placeholder),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = if (config.port == 0) "" else config.port.toString(),
                    onValueChange = { text ->
                        onSetPort(text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                    },
                    label = stringResource(R.string.proxy_port),
                    placeholder = stringResource(R.string.proxy_port_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsDivider()
            var passwordVisible by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                AppTextField(
                    value = config.username,
                    onValueChange = onSetUsername,
                    label = stringResource(R.string.proxy_username),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = config.password,
                    onValueChange = onSetPassword,
                    label = stringResource(R.string.proxy_password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    trailingIcon = {
                        Icon(
                            imageVector = if (passwordVisible) FeatherIcons.Eye else FeatherIcons.EyeOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { passwordVisible = !passwordVisible }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsDivider()
            if (showNoProxy) {
                Column(modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)) {
                    AppTextField(
                        value = config.noProxy,
                        onValueChange = onSetNoProxy,
                        label = stringResource(R.string.proxy_no_proxy),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.proxy_no_proxy_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
            }
        }

        if (showTypeSheet) {
            ProxyTypeSelectionSheet(
                selected = config.type,
                onSelected = onSetType,
                onDismiss = { showTypeSheet = false }
            )
        }

        // ── 连通性测试（独立卡片：测试 URL + 按钮） ──
        if (config.valid) {
            SettingsGroupHeader(text = stringResource(R.string.proxy_test_title))
            SettingsGroup {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    var testUrl by remember { mutableStateOf(DEFAULT_TEST_URL) }
                    AppTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        label = stringResource(R.string.proxy_test_url_label),
                        placeholder = stringResource(R.string.proxy_test_url_placeholder),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = { onTestProxy(testUrl) },
                        enabled = testState !is ProxyTestUiState.Testing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (testState is ProxyTestUiState.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(
                            text = stringResource(
                                if (testState is ProxyTestUiState.Testing) R.string.proxy_testing else R.string.proxy_test
                            )
                        )
                    }
                    when (val s = testState) {
                        is ProxyTestUiState.Success -> Text(
                            text = "✓ ${s.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                        is ProxyTestUiState.Error -> Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.sm)
                        )
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
internal fun proxyTypeLabel(type: ProxyType): String = stringResource(
    if (type == ProxyType.HTTP) R.string.proxy_type_http else R.string.proxy_type_socks5
)

/** 代理类型底部弹窗选择（与提供商类型选择同风格）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyTypeSelectionSheet(
    selected: ProxyType,
    onSelected: (ProxyType) -> Unit,
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
                text = stringResource(R.string.proxy_type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            ProxyType.entries.forEach { type ->
                val isSelected = type == selected
                Surface(
                    onClick = {
                        onDismiss()
                        onSelected(type)
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
                            text = proxyTypeLabel(type),
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
