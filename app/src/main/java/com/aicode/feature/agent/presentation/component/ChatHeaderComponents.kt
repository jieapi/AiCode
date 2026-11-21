package com.aicode.feature.agent.presentation.component

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.domain.PluginUiEntryKind
import com.aicode.feature.plugin.presentation.pluginPageIcon
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Menu
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal

@Composable
internal fun ChatHeader(
    sessionTitle: String,
    modelName: String?,
    inputTokens: Int,
    outputTokens: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onEnterTouchLock: () -> Unit = {},
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    connectionState: com.aicode.feature.agent.domain.container.ConnectionState? = null,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    pluginEntries: List<PluginUiEntry> = emptyList(),
    pluginLanguageTag: String = "zh-CN",
    onPluginEntry: (PluginUiEntry) -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大屏常驻侧栏时隐掉汉堡键：侧栏已经摆在左边，再给个开关反而困惑。
                if (showMenuButton) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                    ) {
                        Icon(
                            FeatherIcons.Menu,
                            contentDescription = stringResource(R.string.chat_open_sidebar),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (!modelName.isNullOrBlank()) {
                            ModelLogoIcon(modelName = modelName, size = 14.dp)
                        }
                        Text(
                            text = modelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_no_model_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onNewChat) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chat_new_session),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkbenchMenuButton(
                    terminalActive = terminalActive,
                    gitActive = gitActive,
                    onNavigateToTerminal = onNavigateToTerminal,
                    onNavigateToGit = onNavigateToGit,
                    onEnterTouchLock = onEnterTouchLock,
                    pluginEntries = pluginEntries,
                    pluginLanguageTag = pluginLanguageTag,
                    onPluginEntry = onPluginEntry
                )
            }
            // 远程模式：左边 SSH 连接状态，右边 token 累计统计
            if (connectionState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ConnectionIndicator(state = connectionState)
                    TokenStats(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            }
        }
    }
}

/** 顶栏工作台菜单：终端 / Git 与插件槽位共用同一个溢出入口，避免顶栏继续挤图标。 */
@Composable
private fun WorkbenchMenuButton(
    terminalActive: Boolean,
    gitActive: Boolean,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onEnterTouchLock: () -> Unit,
    pluginEntries: List<PluginUiEntry>,
    pluginLanguageTag: String,
    onPluginEntry: (PluginUiEntry) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                FeatherIcons.MoreVertical,
                contentDescription = stringResource(R.string.common_more),
                tint = if (terminalActive || gitActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WorkbenchMenuItem(
                icon = FeatherIcons.Terminal,
                label = stringResource(R.string.terminal_title),
                active = terminalActive,
                onClick = {
                    expanded = false
                    onNavigateToTerminal()
                }
            )
            WorkbenchMenuItem(
                icon = FeatherIcons.GitBranch,
                label = stringResource(R.string.git_title),
                active = gitActive,
                onClick = {
                    expanded = false
                    onNavigateToGit()
                }
            )
            WorkbenchMenuItem(
                icon = FeatherIcons.Lock,
                label = stringResource(R.string.chat_touch_lock),
                active = false,
                onClick = {
                    expanded = false
                    onEnterTouchLock()
                }
            )
            if (pluginEntries.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))
                pluginEntries.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(entry.title.resolve(pluginLanguageTag)) },
                        leadingIcon = {
                            Icon(
                                pluginPageIcon(entry.icon),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            expanded = false
                            onPluginEntry(entry)
                        }
                    )
                }
            }
        }
    }
}

/** 菜单项尾部用勾表示右栏已打开，替代原先顶栏按钮的高亮。 */
@Composable
private fun WorkbenchMenuItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (active) {
            {
                Icon(
                    FeatherIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            null
        },
        onClick = onClick
    )
}

@Composable
private fun ConnectionIndicator(
    state: com.aicode.feature.agent.domain.container.ConnectionState
) {
    val (dotColor, text) = when (state) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.chat_ssh_connected)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_ssh_connecting)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.chat_ssh_failed)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.chat_ssh_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenStats(inputTokens: Int, outputTokens: Int) {
    val inStr = formatTokenCount(inputTokens.toLong())
    val outStr = formatTokenCount(outputTokens.toLong())
    Text(
        text = "↑$inStr ↓$outStr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: com.aicode.feature.agent.domain.container.ConnectionState
) {
    val text = when (state) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(
    bottomReserve: Dp,
    pluginEntries: List<PluginUiEntry> = emptyList(),
    languageTag: String = "zh-CN",
    onPluginEntry: (PluginUiEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pluginEntries.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.lg))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    pluginEntries.take(3).forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(Radius.sm),
                            modifier = Modifier.widthIn(max = 360.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { onPluginEntry(entry) }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Icon(
                                    pluginPageIcon(entry.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = entry.title.resolve(languageTag),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.kind == PluginUiEntryKind.ACTION) {
                                    Text(
                                        text = stringResource(R.string.plugins_entry_action),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}