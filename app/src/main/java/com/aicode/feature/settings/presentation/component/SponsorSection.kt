package com.aicode.feature.settings.presentation.component

import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.MARKDOWN_MAX_EDGE
import com.aicode.core.ui.THUMBNAIL_MAX_EDGE
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.presentation.currentLanguageTag
import com.aicode.feature.settings.data.remote.SponsorMethod
import com.aicode.feature.settings.data.remote.SponsorUser
import com.aicode.feature.settings.presentation.SponsorUiState
import com.aicode.feature.settings.presentation.SponsorViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Heart
import compose.icons.feathericons.Link
import compose.icons.feathericons.User
import compose.icons.feathericons.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request

@Composable
internal fun SponsorSectionHost(viewModel: SponsorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.refresh(clearExistingUsers = true)
    }

    LaunchedEffect(state.errorMessage) {
        val err = state.errorMessage ?: return@LaunchedEffect
        val message = if (err.isBlank()) {
            context.getString(R.string.sponsor_refresh_failed_short)
        } else {
            context.getString(R.string.sponsor_refresh_failed, err)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }

    SponsorSection(
        state = state,
        onRetry = { viewModel.refresh() }
    )
}

/**
 * 赞助页：上方「赞助方式」分组卡片 + 下方自适应列数（手机 5 列）的「赞助用户」圆形头像网格。
 * 排版参考常见的赞助页结构，视觉组件严格复用 AiCode 的 [SettingsGroupHeader] / [SettingsGroup] /
 * [SettingsRow] 与语义色板。
 */
@Composable
internal fun SponsorSection(
    state: SponsorUiState,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val languageTag = currentLanguageTag()
    var activeQrMethod by remember { mutableStateOf<SponsorMethod?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentWidth = (maxWidth - Spacing.lg * 2).coerceAtLeast(240.dp)
        val columns = (contentWidth / 66.dp).toInt().coerceAtLeast(5)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.xs,
                bottom = Spacing.xl
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item(key = "methods_section", span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsGroupHeader(text = stringResource(R.string.sponsor_methods_header))
                    SettingsGroup {
                        state.methods.forEachIndexed { index, method ->
                            if (index > 0) SettingsDivider()
                            val title = resolveMethodTitle(method, languageTag)
                            val subtitle = resolveLocalizedMap(method.subtitle, languageTag)
                            val hasAction = !method.qrImageUrl.isNullOrBlank() || !method.url.isNullOrBlank()
                            SettingsRow(
                                icon = resolveMethodIcon(method),
                                title = title,
                                subtitle = subtitle,
                                onClick = if (hasAction) {
                                    {
                                        if (!method.qrImageUrl.isNullOrBlank()) {
                                            activeQrMethod = method
                                        } else if (!method.url.isNullOrBlank()) {
                                            openUrl(context, method.url)
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            item(key = "users_header", span = { GridItemSpan(maxLineSpan) }) {
                SettingsGroupHeader(text = stringResource(R.string.sponsor_users_header))
            }

            if (state.users.isEmpty()) {
                item(key = "users_empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (state.isRefreshing) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xxl),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        }
                    } else {
                        SettingsEmptyState(
                            icon = FeatherIcons.Users,
                            title = stringResource(R.string.sponsor_users_empty_title),
                            hint = stringResource(R.string.sponsor_users_empty_hint),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                            actions = {
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(R.string.common_retry))
                                }
                            }
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = state.users,
                    key = { index, user -> "$index:${user.name}" }
                ) { _, user ->
                    SponsorUserGridItem(
                        user = user,
                        onClick = {
                            user.url?.takeIf { it.isNotBlank() }?.let { openUrl(context, it) }
                        }
                    )
                }
            }
        }
    }

    activeQrMethod?.let { method ->
        SponsorQrDialog(
            method = method,
            title = resolveMethodTitle(method, languageTag),
            onDismiss = { activeQrMethod = null },
            onOpenUrl = { url -> openUrl(context, url) }
        )
    }
}

@Composable
private fun SponsorUserGridItem(
    user: SponsorUser,
    onClick: () -> Unit
) {
    val clickable = !user.url.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Spacing.xs, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SponsorAvatar(
            avatarUrl = user.avatar,
            contentDescription = user.name,
            size = 52.dp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = user.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SponsorAvatar(
    avatarUrl: String?,
    contentDescription: String,
    size: Dp
) {
    val cleanUrl = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, cleanUrl) {
        value = if (cleanUrl == null) {
            null
        } else {
            SponsorImageLoader.load(cleanUrl, THUMBNAIL_MAX_EDGE)
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.semanticColors.cardSurface)
            .border(0.75.dp, MaterialTheme.semanticColors.subtleBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = FeatherIcons.User,
                contentDescription = contentDescription,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}

@Composable
private fun SponsorQrDialog(
    method: SponsorMethod,
    title: String,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val qrUrl = method.qrImageUrl?.trim().orEmpty()
    val state by produceState<Pair<Boolean, ImageBitmap?>>(initialValue = true to null, qrUrl) {
        val loaded = if (qrUrl.isBlank()) null else SponsorImageLoader.load(qrUrl, MARKDOWN_MAX_EDGE)
        value = false to loaded
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                contentAlignment = Alignment.Center
            ) {
                val (loading, bitmap) = state
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(Radius.md))
                    )
                    else -> Text(
                        text = stringResource(R.string.sponsor_qr_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        dismissButton = method.url?.takeIf { it.isNotBlank() }?.let { url ->
            {
                TextButton(onClick = { onOpenUrl(url) }) {
                    Text(stringResource(R.string.sponsor_open_link))
                }
            }
        }
    )
}

@Composable
private fun resolveMethodTitle(method: SponsorMethod, languageTag: String): String {
    val localized = resolveLocalizedMap(method.name, languageTag)
    if (!localized.isNullOrBlank()) return localized
    return when (method.id.lowercase()) {
        "afdian" -> stringResource(R.string.sponsor_method_afdian)
        "wechat" -> stringResource(R.string.sponsor_method_wechat)
        else -> method.id
    }
}

private fun resolveLocalizedMap(values: Map<String, String>, languageTag: String): String? {
    if (values.isEmpty()) return null
    val primary = languageTag.substringBefore('-').lowercase()
    return values[languageTag]
        ?: values[primary]
        ?: values["zh"]
        ?: values["en"]
        ?: values.values.firstOrNull()
}

private fun resolveMethodIcon(method: SponsorMethod): ImageVector = when (method.icon?.lowercase() ?: method.id.lowercase()) {
    "heart", "afdian" -> FeatherIcons.Heart
    "link", "wechat" -> FeatherIcons.Link
    "external", "external-link" -> FeatherIcons.ExternalLink
    else -> FeatherIcons.Globe
}

/**
 * 赞助页远程图片加载器：实时拉取，不写磁盘缓存；单次会话内用内存 [LruCache] 避免列表滚动重复请求。
 */
private object SponsorImageLoader {
    private const val TAG = "SponsorImageLoader"
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

    private val memoryCache = LruCache<String, ImageBitmap>(64)

    suspend fun load(url: String, maxEdge: Int): ImageBitmap? {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return null
        }
        val cacheKey = "$maxEdge:$url"
        memoryCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            memoryCache.get(cacheKey)?.let { return@withContext it }
            val downloaded = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "aicode-android")
                    .get()
                    .build()
                RepoDataFetcher.DEFAULT_CLIENT.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_IMAGE_BYTES) return@use null
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@use null
                    bytes
                }
            }.onFailure { e ->
                FileLogger.d(TAG, "加载赞助图片失败 url=$url: ${e.message}")
            }.getOrNull() ?: return@withContext null

            decodeSampledBitmap(downloaded, maxEdge)?.also { bmp ->
                memoryCache.put(cacheKey, bmp)
            }
        }
    }
}
