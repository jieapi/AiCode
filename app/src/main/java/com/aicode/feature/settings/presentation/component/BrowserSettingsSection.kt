package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.tool.browser.BrowserActionCatalog
import com.aicode.feature.agent.domain.tool.browser.BrowserUserAgent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Globe

/**
 * 浏览器设置二级页：
 *
 * - UA 设置组：User-Agent 预设（默认 / 安卓手机 / 安卓平板 / Windows 10 / Windows 11 / Mac）。
 * - AI 权限组：逐 action 的控制开关，默认全开；关闭后 AI 调用对应操作会被拒绝。
 */
@Composable
internal fun BrowserSettingsSection(
    userAgent: BrowserUserAgent,
    actionEnabled: Map<String, Boolean>,
    onSelectUserAgent: (BrowserUserAgent) -> Unit,
    onToggleAction: (String, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        // ── UA 设置 ──
        SettingsGroupHeader(text = stringResource(R.string.browser_settings_group_ua))
        SettingsGroup {
            BrowserUserAgent.entries.forEachIndexed { index, entry ->
                if (index > 0) SettingsDivider()
                UserAgentRow(
                    title = userAgentTitle(entry),
                    selected = userAgent == entry,
                    onClick = { onSelectUserAgent(entry) }
                )
            }
        }
        Text(
            text = stringResource(R.string.browser_settings_ua_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
        )

        // ── AI 控制权限 ──
        SettingsGroupHeader(text = stringResource(R.string.browser_settings_group_ai))
        SettingsGroup {
            BrowserActionCatalog.ALL.forEachIndexed { index, action ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    title = browserActionTitle(action),
                    subtitle = browserActionSubtitle(action),
                    trailing = {
                        AppSwitch(
                            checked = actionEnabled[action] ?: true,
                            onCheckedChange = { enabled -> onToggleAction(action, enabled) }
                        )
                    }
                )
            }
        }
        Text(
            text = stringResource(R.string.browser_settings_ai_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
        )
    }
}

/** UA 单选行：左侧地球图标 + 标题，右侧选中勾。 */
@Composable
private fun UserAgentRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SettingsRow(
        icon = FeatherIcons.Globe,
        title = title,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = Spacing.xs)
                        .size(18.dp)
                )
            }
        }
    )
}

@Composable
private fun userAgentTitle(entry: BrowserUserAgent): String = stringResource(
    when (entry) {
        BrowserUserAgent.DEFAULT -> R.string.browser_ua_default
        BrowserUserAgent.ANDROID_PHONE -> R.string.browser_ua_android_phone
        BrowserUserAgent.ANDROID_TABLET -> R.string.browser_ua_android_tablet
        BrowserUserAgent.WINDOWS_10 -> R.string.browser_ua_windows_10
        BrowserUserAgent.WINDOWS_11 -> R.string.browser_ua_windows_11
        BrowserUserAgent.MAC_INTEL -> R.string.browser_ua_mac_intel
        BrowserUserAgent.MAC_APPLE_SILICON -> R.string.browser_ua_mac_apple
    }
)

/** action 的本地化标题；未知 action 回退为原始名。 */
@Composable
private fun browserActionTitle(action: String): String {
    val res = browserActionTitleRes(action)
    return if (res != null) stringResource(res) else action
}

@Composable
private fun browserActionSubtitle(action: String): String {
    val res = browserActionSubtitleRes(action)
    return if (res != null) stringResource(res) else action
}

private fun browserActionTitleRes(action: String): Int? = when (action) {
    "navigate" -> R.string.browser_action_navigate
    "evaluate" -> R.string.browser_action_evaluate
    "click" -> R.string.browser_action_click
    "fill" -> R.string.browser_action_fill
    "select" -> R.string.browser_action_select
    "hover" -> R.string.browser_action_hover
    "press" -> R.string.browser_action_press
    "getText" -> R.string.browser_action_get_text
    "getHtml" -> R.string.browser_action_get_html
    "getBackbone" -> R.string.browser_action_get_backbone
    "screenshot" -> R.string.browser_action_screenshot
    "console" -> R.string.browser_action_console
    "wait" -> R.string.browser_action_wait
    "scroll" -> R.string.browser_action_scroll
    "dialog" -> R.string.browser_action_dialog
    "back" -> R.string.browser_action_back
    "forward" -> R.string.browser_action_forward
    "reload" -> R.string.browser_action_reload
    "newTab" -> R.string.browser_action_new_tab
    "closeTab" -> R.string.browser_action_close_tab
    "selectTab" -> R.string.browser_action_select_tab
    "listTabs" -> R.string.browser_action_list_tabs
    else -> null
}

private fun browserActionSubtitleRes(action: String): Int? = when (action) {
    "navigate" -> R.string.browser_action_navigate_desc
    "evaluate" -> R.string.browser_action_evaluate_desc
    "click" -> R.string.browser_action_click_desc
    "fill" -> R.string.browser_action_fill_desc
    "select" -> R.string.browser_action_select_desc
    "hover" -> R.string.browser_action_hover_desc
    "press" -> R.string.browser_action_press_desc
    "getText" -> R.string.browser_action_get_text_desc
    "getHtml" -> R.string.browser_action_get_html_desc
    "getBackbone" -> R.string.browser_action_get_backbone_desc
    "screenshot" -> R.string.browser_action_screenshot_desc
    "console" -> R.string.browser_action_console_desc
    "wait" -> R.string.browser_action_wait_desc
    "scroll" -> R.string.browser_action_scroll_desc
    "dialog" -> R.string.browser_action_dialog_desc
    "back" -> R.string.browser_action_back_desc
    "forward" -> R.string.browser_action_forward_desc
    "reload" -> R.string.browser_action_reload_desc
    "newTab" -> R.string.browser_action_new_tab_desc
    "closeTab" -> R.string.browser_action_close_tab_desc
    "selectTab" -> R.string.browser_action_select_tab_desc
    "listTabs" -> R.string.browser_action_list_tabs_desc
    else -> null
}
