package com.aicode.feature.settings.presentation.component

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.BatchDeleteConfirmDialog
import com.aicode.core.ui.FloatingSelectionBar
import com.aicode.core.ui.ListSelectionState
import com.aicode.core.ui.SelectionActionDangerButton
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.agent.domain.container.ContainerImageEol
import com.aicode.feature.agent.domain.container.displayLabel
import com.aicode.feature.agent.domain.container.eolStatus
import com.aicode.feature.agent.domain.container.hasNewerVersion
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.effectiveVersion
import com.aicode.feature.settings.presentation.ContainerImageDownloadUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.HardDrive
import java.io.File
import kotlinx.coroutines.launch

/** 字节数转可读体积；容器镜像下载与更新包下载共用（同包内 internal，避免各写一份）。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 发行版 logo（与容器与镜像页一致的图标风格）；未识别返回 null（调用方回退通用图标）。 */
@Composable
private fun distroLogo(distro: String): Painter? = when (distro) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    "centos" -> painterResource(R.drawable.logo_centos)
    else -> null
}

/**
 * 下载镜像页：从内置目录（assets/container-images.json）列出可下载镜像。
 * 行样式与「容器与镜像」一致：左侧发行版图标 + 名称/简介 + 右箭头；点击行弹出详情弹窗；
 * 已下载的行可左滑删除（删文件+记录）。下载源在页面右上角底部弹窗切换；
 * 下载异步进行，关闭弹窗后行内继续显示进度；「导入」把镜像加入容器列表，可重复导入。
 */
@Composable
internal fun ContainerImageDownloadSection(
    catalog: List<ContainerImageEntry>,
    imageVersions: Map<String, String>,
    state: ContainerImageDownloadUiState,
    downloadedImages: Map<String, DownloadedImageRecord>,
    sourceUnavailableIds: Set<String>,
    selectedSourceName: String,
    selection: ListSelectionState,
    onDownload: (ContainerImageEntry) -> Unit,
    onCancel: () -> Unit,
    onImport: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBatchDelete: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var detailEntry by remember { mutableStateOf<ContainerImageEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ContainerImageEntry?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 只显示当前源下可用的镜像；切源后列表自动过滤
    val visibleCatalog = catalog.filter { it.id !in sourceUnavailableIds }
    // 只有「已下载且文件在」的条目能删，也就只有它们参与多选。
    val downloadableIds = remember(visibleCatalog, downloadedImages) {
        visibleCatalog.filter { entry ->
            downloadedImages[entry.id]?.let { record ->
                File(Uri.parse(record.fileUri).path ?: "").exists()
            } == true
        }.map { it.id }
    }

    // 删完 / 换源后剔除已消失的选中项；没有可删条目就退出多选。
    LaunchedEffect(downloadableIds, selection.isActive) {
        selection.retainOnly(downloadableIds.toSet())
        if (selection.isActive && downloadableIds.isEmpty()) selection.exit()
    }

    val allSelected = selection.selectedCount > 0 && selection.selectedCount == downloadableIds.size

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = if (selection.isActive) 88.dp else Spacing.xl)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.container_download_image))
            if (visibleCatalog.isEmpty()) {
                SettingsGroup {
                    Text(
                        text = stringResource(R.string.container_download_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg)
                    )
                }
            } else {
                SettingsGroup {
                    visibleCatalog.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        val record = downloadedImages[entry.id]
                        val fileExists = record?.let { File(Uri.parse(it.fileUri).path ?: "").exists() } == true
                        // 展示版本以运行时探测值为准（Debian 的补丁号靠它），「有新版」比对也用同一个值
                        val resolvedVersion = imageVersions[entry.id] ?: entry.version
                        DownloadImageRow(
                            entry = entry,
                            displayName = entry.displayLabel(imageVersions[entry.id]),
                            eol = entry.eolStatus(),
                            hasNewerVersion = hasNewerVersion(record?.effectiveVersion(), resolvedVersion),
                            state = state,
                            isDownloaded = record != null && fileExists,
                            onOpenDetail = { detailEntry = entry },
                            onDelete = { deleteEntry = entry },
                            selectionMode = selection.isActive,
                            selected = selection.isSelected(entry.id),
                            onToggleSelect = { selection.toggle(entry.id) }
                        )
                    }
                }
            }
        }

        FloatingSelectionBar(
            visible = selection.isActive,
            selectedCount = selection.selectedCount,
            allSelected = allSelected,
            onToggleSelectAll = {
                if (allSelected) selection.clear() else selection.replaceAll(downloadableIds)
            },
            onExit = { selection.exit() },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionActionDangerButton(
                text = stringResource(R.string.common_batch_delete),
                enabled = selection.selectedCount > 0,
                onClick = { showBatchDeleteConfirm = true }
            )
        }
    }

    if (showBatchDeleteConfirm) {
        val targets = selection.selectedKeys.toSet()
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.container_download_batch_delete_title),
            message = stringResource(R.string.container_download_batch_delete_confirm, targets.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                selection.exit()
                onBatchDelete(targets)
            },
            onDismiss = { showBatchDeleteConfirm = false }
        )
    }

    detailEntry?.let { entry ->
        ImageDetailSheet(
            entry = entry,
            displayName = entry.displayLabel(imageVersions[entry.id]),
            eol = entry.eolStatus(),
            hasNewerVersion = hasNewerVersion(
                downloadedImages[entry.id]?.effectiveVersion(),
                imageVersions[entry.id] ?: entry.version
            ),
            state = state,
            record = downloadedImages[entry.id],
            sourceUnavailable = entry.id in sourceUnavailableIds,
            sourceName = selectedSourceName,
            onDownload = { onDownload(entry) },
            onCancel = onCancel,
            onImport = { fileUri ->
                onImport(entry.id, fileUri)
                Toast.makeText(
                    context,
                    context.getString(R.string.container_download_imported_toast),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { detailEntry = null }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.container_download_delete_title)) },
            text = { Text(stringResource(R.string.container_download_delete_confirm, entry.displayLabel(imageVersions[entry.id]))) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry.id)
                    deleteEntry = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 行内状态徽章：淡色底 + 语义色文字（「已下载」与 EOL 标注共用）。 */
@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 支持状态文案与配色；无需标注返回 null。行内徽章与详情页共用，避免两处文案漂移。 */
@Composable
private fun eolStatusText(entry: ContainerImageEntry, eol: ContainerImageEol): Pair<String, Color>? = when (eol) {
    ContainerImageEol.SOON ->
        stringResource(R.string.container_download_eol_soon) to MaterialTheme.semanticColors.warning

    ContainerImageEol.EXTENDED -> {
        val text = if (entry.supportExtension.isBlank()) {
            stringResource(R.string.container_download_eol_extended_generic)
        } else {
            stringResource(R.string.container_download_eol_extended, entry.supportExtension)
        }
        text to MaterialTheme.semanticColors.warning
    }

    ContainerImageEol.ENDED ->
        stringResource(R.string.container_download_eol_ended) to MaterialTheme.colorScheme.error

    ContainerImageEol.NONE -> null
}

/** 分支支持状态徽章；无需标注时什么都不画。 */
@Composable
private fun EolBadge(entry: ContainerImageEntry, eol: ContainerImageEol) {
    val status = eolStatusText(entry, eol) ?: return
    StatusBadge(text = status.first, color = status.second)
}

/** 列表行：图标 + 名称（已下载/EOL 带徽章）/简介（下载中显示进度条）+ 右箭头；所有行可左滑，未下载时删除按钮禁用。 */
@Composable
private fun DownloadImageRow(
    entry: ContainerImageEntry,
    displayName: String,
    eol: ContainerImageEol,
    hasNewerVersion: Boolean,
    state: ContainerImageDownloadUiState,
    isDownloaded: Boolean,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val downloading = state as? ContainerImageDownloadUiState.Downloading

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onOpenDetail,
        deleteEnabled = isDownloaded,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect,
        selectable = isDownloaded
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧发行版图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val logo = distroLogo(entry.distro)
                if (logo != null) {
                    Icon(
                        painter = logo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = FeatherIcons.HardDrive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 名称 + 简介（下载中显示进度条）
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDownloaded) {
                        if (hasNewerVersion) {
                            StatusBadge(
                                text = stringResource(R.string.container_download_newer_available),
                                color = MaterialTheme.semanticColors.warning
                            )
                        } else {
                            StatusBadge(
                                text = stringResource(R.string.container_download_done),
                                color = MaterialTheme.semanticColors.success
                            )
                        }
                    }
                    EolBadge(entry, eol)
                }
                downloading?.takeIf { it.entryId == entry.id }?.let { d ->
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (d.totalBytes > 0) {
                                (d.bytesRead.toFloat() / d.totalBytes).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${formatBytes(d.bytesRead)} / " +
                            (if (d.totalBytes > 0) formatBytes(d.totalBytes) else "?"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } ?: run {
                    Text(
                        text = entry.description.ifBlank { if (isDownloaded) stringResource(R.string.container_download_done) else "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // 右侧箭头
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 镜像详情底部弹窗：信息 + 下载/进度/导入操作区。关闭弹窗不影响后台下载。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDetailSheet(
    entry: ContainerImageEntry,
    displayName: String,
    eol: ContainerImageEol,
    hasNewerVersion: Boolean,
    state: ContainerImageDownloadUiState,
    record: DownloadedImageRecord?,
    sourceUnavailable: Boolean,
    sourceName: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val fileExists = record?.let { File(Uri.parse(it.fileUri).path ?: "").exists() } == true
    val isDownloaded = record != null && fileExists
    val downloading = state as? ContainerImageDownloadUiState.Downloading
    val isDownloading = downloading?.entryId == entry.id
    val error = (state as? ContainerImageDownloadUiState.Error)?.takeIf { it.entryId == entry.id }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val logo = distroLogo(entry.distro)
                    if (logo != null) {
                        Icon(
                            painter = logo,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = FeatherIcons.HardDrive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isDownloaded) {
                        Text(
                            text = stringResource(
                                if (hasNewerVersion) R.string.container_download_newer_available
                                else R.string.container_download_done
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasNewerVersion) MaterialTheme.semanticColors.warning
                            else MaterialTheme.semanticColors.success
                        )
                    }
                    eolStatusText(entry, eol)?.let { (text, color) ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }

            Column(modifier = Modifier.padding(top = Spacing.md), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                if (entry.sizeBytes > 0) {
                    Text(
                        text = stringResource(R.string.container_download_size, formatBytes(entry.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.container_download_architecture, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.container_download_current_source, sourceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 两级支持周期的确切日期：徽章只说「已进入延长支持」，具体到哪天要能查到
                if (entry.supportEnds.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.container_download_eol_support_ends, entry.supportEnds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.eol.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.container_download_eol_fully_ends, entry.eol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            when {
                isDownloading -> {
                    val d = downloading
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatBytes(d.bytesRead)} / " +
                                    (if (d.totalBytes > 0) formatBytes(d.totalBytes) else "?"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.container_download_cancel))
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (d.totalBytes > 0) {
                                    (d.bytesRead.toFloat() / d.totalBytes).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                isDownloaded -> {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_redownload))
                        }
                        Button(
                            onClick = { onImport(record!!.fileUri) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_import))
                        }
                    }
                }
                error != null -> {
                    Column {
                        Text(
                            text = stringResource(R.string.container_download_failed, error.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_redownload))
                        }
                    }
                }
                else -> {
                    if (sourceUnavailable) {
                        Text(
                            text = stringResource(R.string.container_download_source_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = onDownload,
                        enabled = !sourceUnavailable,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_download_start))
                    }
                }
            }
        }
    }
}
