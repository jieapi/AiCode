package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.feature.settings.presentation.UpdateCheckUiState

/**
 * 检查更新结果弹窗（全局宿主渲染，自动/手动共用）。
 * 新版本弹窗直接列出从当前版本到最新版本的更新日志，可滚动。
 */
@Composable
internal fun UpdateCheckDialog(
    state: UpdateCheckUiState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenRelease: (tag: String) -> Unit
) {
    when (state) {
        UpdateCheckUiState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_update)) },
            text = { Text(stringResource(R.string.about_checking_update)) },
            confirmButton = {}
        )
        UpdateCheckUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_up_to_date)) },
            text = { Text(stringResource(R.string.about_up_to_date_detail, currentVersion)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
        )
        is UpdateCheckUiState.NewVersion -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_new_version_found)) },
            text = {
                Text(
                    text = state.changelog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { onOpenRelease(state.latestTag) }) {
                    Text(stringResource(R.string.about_download))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_later)) } }
        )
        is UpdateCheckUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_failed)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_ok)) } }
        )
        UpdateCheckUiState.Idle -> {}
    }
}

/** 用隐式 Intent 打开浏览器，捕获异常避免崩溃。 */
internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * 指定版本 tag 的 GitHub Release 页面地址；tag 为空时回退到最新正式版页面。
 *
 * GitHub 的 `releases/latest` 只解析到最新的**正式** release（预发布不计入），最新版通道
 * 检测到 RC 时跳过去会看到旧的稳定版，因此必须按 tag 直达对应版本页面。
 */
internal fun githubReleaseUrl(tag: String?): String = when {
    tag.isNullOrBlank() -> "$GITHUB_RELEASES_URL/latest"
    else -> "$GITHUB_RELEASES_URL/tag/${Uri.encode(tag)}"
}

private const val GITHUB_RELEASES_URL = "https://github.com/jieapi/aicode/releases"
