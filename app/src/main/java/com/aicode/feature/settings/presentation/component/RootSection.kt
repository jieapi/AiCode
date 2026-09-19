package com.aicode.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.root.RootState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal

/**
 * 「Root」二级页：展示 root 可用状态并提供授权/重新探测入口。
 *
 * 状态由 [com.aicode.feature.agent.domain.root.RootManager] 统一维护。
 * root 没有可编程的授权 API，因此「重新探测」本身就会触发 root 管理器的授权弹窗。
 */
@Composable
internal fun RootSection(
    state: RootState,
    onRefresh: () -> Unit
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.settings_root),
                subtitle = stringResource(state.hintRes()),
                onClick = onRefresh,
                trailing = {
                    Text(
                        text = stringResource(state.statusRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        Text(
            text = stringResource(R.string.settings_root_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
    }
}

@StringRes
private fun RootState.statusRes(): Int = when (this) {
    RootState.UNAVAILABLE -> R.string.settings_root_status_unavailable
    RootState.DENIED -> R.string.settings_root_status_denied
    RootState.READY -> R.string.settings_root_status_ready
}

@StringRes
private fun RootState.hintRes(): Int = when (this) {
    RootState.UNAVAILABLE -> R.string.settings_root_hint_unavailable
    RootState.DENIED -> R.string.settings_root_hint_denied
    RootState.READY -> R.string.settings_root_hint_ready
}