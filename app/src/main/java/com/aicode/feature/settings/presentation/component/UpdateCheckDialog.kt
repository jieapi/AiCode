package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.presentation.UpdateCheckUiState

/**
 * 检查更新结果弹窗（全局宿主渲染，自动/手动共用）。
 *
 * 新版本弹窗直接列出从当前版本到最新版本的更新日志，可滚动；主按钮在应用内下载安装
 * （默认走镜像源，失败自动回退直连），次按钮保留「浏览器下载」作为兜底——镜像与直连都
 * 不通、或安装包签名与当前应用不一致时，用户仍能自己拿包。
 *
 * [canInstallInApp] 为 false（beta/debug 变体）时不显示应用内安装入口，只留浏览器下载。
 */
@Composable
internal fun UpdateCheckDialog(
    state: UpdateCheckUiState,
    currentVersion: String,
    canInstallInApp: Boolean,
    onDismiss: () -> Unit,
    onOpenRelease: (tag: String) -> Unit,
    onDownload: () -> Unit
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
            text = { ChangelogText(state.changelog) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { onOpenRelease(state.latestTag) }) {
                        Text(stringResource(R.string.about_update_browser))
                    }
                    // 只有正式包才给应用内安装：beta/debug 变体的包名带后缀，装正式包会变成第二个 App
                    if (canInstallInApp) {
                        TextButton(onClick = onDownload) {
                            Text(stringResource(R.string.about_update_download_install))
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_later)) } }
        )
        is UpdateCheckUiState.Downloading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_new_version_found)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.about_update_downloading, state.percent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.about_update_download_size,
                            formatBytes(state.downloaded),
                            formatBytes(state.total)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            // 关掉弹窗不打断下载：状态留在 ViewModel 里，再点「版本」行即可回到这个进度
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_update_background)) } }
        )
        is UpdateCheckUiState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_new_version_found)) },
            text = {
                Text(
                    text = stringResource(
                        if (state.needPermission) R.string.about_update_need_permission
                        else R.string.about_update_apk_ready
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.about_update_install)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_later)) } }
        )
        is UpdateCheckUiState.DownloadFailed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_update_download_failed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(state.message)
                    if (state.showMirrorHint) {
                        Text(
                            text = stringResource(R.string.about_update_download_failed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.about_update_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { onOpenRelease(state.previous.latestTag) }) {
                    Text(stringResource(R.string.about_update_browser))
                }
            }
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

/** 更新日志正文：限高可滚动，避免超长 changelog 把弹窗撑出屏幕。 */
@Composable
private fun ChangelogText(changelog: String) {
    Text(
        text = changelog,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(end = Spacing.xs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
