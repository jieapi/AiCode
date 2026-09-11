package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.ModelMetadata
import compose.icons.FeatherIcons
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalContext
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Plus
import kotlinx.coroutines.launch

private val ModelLogoSize = 40.dp
private val ModelTestButtonWidth = 56.dp
private val ModelContentStart = ModelLogoSize + Spacing.md
private val ModelTestAreaWidth = ModelTestButtonWidth + Spacing.sm

/** 模型能力标签。Image / Tools 各自独立，token 上下限合并进同一个胶囊。 */
@Composable
private fun ModelMetadataTags(
    metadata: ModelMetadata?,
    modifier: Modifier = Modifier
) {
    metadata ?: return
    val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
    val output = metadata.outputTokens?.takeIf { it > 0 }
    val tokens = listOfNotNull(
        input?.let { "↑${formatTokenLimit(it)}" },
        output?.let { "↓${formatTokenLimit(it)}" }
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (metadata.supportsVision) {
            ModelTagPill(texts = listOf("Image"))
        }
        if (metadata.supportsTools) {
            ModelTagPill(texts = listOf("Tools"))
        }
        if (tokens.isNotEmpty()) {
            ModelTagPill(texts = tokens)
        }
    }
}

/** 胶囊标签。多段文本用固定间距并排，不用空格字符撑间距。 */
@Composable
private fun ModelTagPill(texts: List<String>) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(Radius.pill)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        texts.forEach { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 模型行。logo 与「测试」相对名称+标签整块垂直居中，对齐提供商列表。 */
@Composable
internal fun ProviderModelRow(
    model: String,
    metadata: ModelMetadata?,
    testing: Boolean,
    result: ModelTestResult?,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit = {},
    showDivider: Boolean = false,
    dragModifier: Modifier = Modifier
) {
    var showDetail by remember { mutableStateOf(false) }
    val sortDescription = stringResource(R.string.provider_sort_long_press)

    SwipeToDeleteRow(
        onDelete = onRemove,
        onClick = onEdit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            model,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = ModelContentStart, end = ModelTestAreaWidth)
                        )
                        if (metadata.hasVisibleTags()) {
                            Spacer(Modifier.height(Spacing.xs))
                            ModelMetadataTags(
                                metadata = metadata,
                                modifier = Modifier.padding(start = ModelContentStart)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(end = ModelTestAreaWidth)
                            .then(dragModifier)
                            .semantics {
                                contentDescription = sortDescription
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(ModelLogoSize)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(Radius.sm)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            ModelLogoIcon(modelName = model, size = 22.dp)
                        }
                        Spacer(Modifier.width(Spacing.md))
                    }
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides 0.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(ModelTestButtonWidth)
                                .height(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(
                                    onClick = onTest,
                                    contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp),
                                    modifier = Modifier
                                        .width(ModelTestButtonWidth)
                                        .height(32.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.provider_test),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
                result?.let { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = Spacing.xs, start = ModelContentStart)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showDetail = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (r.success) FeatherIcons.Check else FeatherIcons.AlertCircle,
                            contentDescription = null,
                            tint = if (r.success) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        val displayMsg = if (r.success) {
                            r.message
                        } else {
                            val codeMatch = Regex("""(?i)(HTTP\s*\d{3}|code[:\s]+[a-zA-Z0-9_]+)""").find(r.message)
                            if (codeMatch != null) codeMatch.value
                            else r.message.lines().firstOrNull()?.let { if (it.length > 20) it.take(20) + "..." else it } ?: "Error"
                        }
                        Text(
                            text = displayMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.success) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = FeatherIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            if (showDivider) {
                SettingsDivider()
            }
        }
    }

    if (showDetail && result != null) {
        ModelTestDetailBottomSheet(
            model = model,
            result = result,
            onDismiss = { showDetail = false }
        )
    }
}

/** 模型测试调试详情底部弹窗（展示完整的请求/响应调试信息，支持一键复制）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelTestDetailBottomSheet(
    model: String,
    result: ModelTestResult,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val bgCardColor = MaterialTheme.semanticColors.mutedSurface

    fun buildFullDebugLog(): String {
        return buildString {
            appendLine("=== Model Test Debug Info ===")
            appendLine("Model: $model")
            appendLine("Success: ${result.success}")
            appendLine("Latency: ${result.latencyMs}ms")
            if (result.responseCode > 0) appendLine("Status Code: ${result.responseCode}")
            if (result.message.isNotBlank()) appendLine("Message: ${result.message}")
            appendLine()
            appendLine("--- Request ---")
            appendLine("URL: ${result.requestUrl}")
            if (result.requestHeaders.isNotEmpty()) {
                appendLine("Headers:")
                result.requestHeaders.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            if (result.requestBody.isNotBlank()) {
                appendLine("Body:")
                appendLine(result.requestBody)
            }
            appendLine()
            appendLine("--- Response ---")
            if (result.responseHeaders.isNotEmpty()) {
                appendLine("Headers:")
                result.responseHeaders.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            if (result.responseBody.isNotBlank()) {
                appendLine("Body:")
                appendLine(result.responseBody)
            }
            if (result.errorDetail.isNotBlank() && result.errorDetail != result.responseBody) {
                appendLine()
                appendLine("--- Error Detail ---")
                appendLine(result.errorDetail)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 顶栏：标题 + 状态/耗时 + 复制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.provider_test_detail_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (result.success) MaterialTheme.semanticColors.success.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (result.success) "200 OK · ${result.latencyMs}ms" else "${if (result.responseCode > 0) "HTTP ${result.responseCode}" else "Failed"} · ${result.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (result.success) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("debug_log", buildFullDebugLog())))
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

            // 可滚动详细信息内容区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // 1. 请求 URL 与 Header
                if (result.requestUrl.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_req_url),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bgCardColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = result.requestUrl,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
                }

                if (result.requestHeaders.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_req_headers),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bgCardColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                result.requestHeaders.forEach { (k, v) ->
                                    Text(
                                        text = "$k: $v",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                if (result.requestBody.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_req_body),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bgCardColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = result.requestBody,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
                }

                // 2. 响应 Headers 与 Body
                if (result.responseHeaders.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_resp_headers),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bgCardColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                result.responseHeaders.forEach { (k, v) ->
                                    Text(
                                        text = "$k: $v",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                if (result.responseBody.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_resp_body),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = bgCardColor,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = result.responseBody,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
                }

                if (result.errorDetail.isNotBlank() && result.errorDetail != result.responseBody) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.provider_test_error_detail),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = result.errorDetail,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(Spacing.sm)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ModelMetadata?.hasVisibleTags(): Boolean {
    if (this == null) return false
    if (supportsVision || supportsTools) return true
    val input = inputTokens?.takeIf { it > 0 } ?: contextTokens.takeIf { it > 0 }
    return input != null || (outputTokens != null && outputTokens > 0)
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}M".trimDecimal()
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}K".trimDecimal()
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")

@Composable
internal fun FetchModelRow(
    model: String,
    metadata: ModelMetadata?,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onAdd() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (metadata.hasVisibleTags()) {
                Spacer(Modifier.height(4.dp))
                ModelMetadataTags(metadata)
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
