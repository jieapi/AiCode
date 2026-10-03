package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.THUMBNAIL_MAX_EDGE
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Camera
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import compose.icons.feathericons.Menu
import compose.icons.feathericons.X
import compose.icons.feathericons.Zap
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 待发送队列面板：AI 忙时排队的消息，风格与斜杠命令菜单一致。
 * 内容过长时在面板内部滚动（heightIn 限制 + LazyColumn）；支持长按手柄拖拽排序、
 * 编辑单条、立即插入当前轮次、逐条删除。
 */
@Composable
internal fun QueuedRequestPanel(
    queuedRequests: List<QueuedRequest>,
    sessionId: String,
    forceCollapse: Boolean = false,
    onRemoveQueued: (String) -> Unit,
    onMoveQueued: (Int, Int) -> Unit,
    onEditQueued: (QueuedRequest) -> Unit,
    onInterjectQueued: (String) -> Unit
) {
    // 按会话记忆展开状态，默认展开；弹窗/键盘叠加时联动强制收起（同待办面板）。
    var isExpanded by rememberSaveable(sessionId) { mutableStateOf(true) }
    val effectiveExpanded = isExpanded && !forceCollapse
    // 拖拽中读取最新队列与回调：pointerInput 只在 key 变化时重启，用 rememberUpdatedState 避免捕获陈旧值。
    val currentQueue by rememberUpdatedState(queuedRequests)
    val moveQueued by rememberUpdatedState(onMoveQueued)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.chat_queue_title, queuedRequests.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) {
                        stringResource(R.string.common_collapse_action)
                    } else {
                        stringResource(R.string.common_expand)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 16.dp
                )
            }
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    itemsIndexed(queuedRequests, key = { _, req -> req.id }) { index, req ->
                        val isDragging = draggingId == req.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                FeatherIcons.Menu,
                                contentDescription = stringResource(R.string.chat_queue_drag),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .pointerInput(req.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = req.id
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val current = currentQueue.indexOfFirst { it.id == req.id }
                                                if (current != -1) {
                                                    if (dragOffset > rowHeightPx && current < currentQueue.lastIndex) {
                                                        moveQueued(current, current + 1)
                                                        dragOffset -= rowHeightPx
                                                    } else if (dragOffset < -rowHeightPx && current > 0) {
                                                        moveQueued(current, current - 1)
                                                        dragOffset += rowHeightPx
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffset = 0f
                                            }
                                        )
                                    }
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = req.request,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { onInterjectQueued(req.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    FeatherIcons.Zap,
                                    contentDescription = stringResource(R.string.chat_queue_interject),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { onEditQueued(req) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.chat_queue_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { onRemoveQueued(req.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    FeatherIcons.X,
                                    contentDescription = stringResource(R.string.chat_queue_remove),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 队列行拖拽位移换算基准：内容高度（28dp）+ 上下 padding（Spacing.xs×2）。 */
private val QueueRowHeight = 36.dp

@Composable
internal fun PendingAttachmentPreviewList(
    attachments: List<PendingUploadAttachment>,
    onRemoveAttachment: (Int) -> Unit
) {
    if (attachments.isEmpty()) return

    val scrollState = rememberScrollState()
    // 新附件加入时自动滚到最右，保证刚上传的附件可见（横向滑动，不改竖向）。
    LaunchedEffect(attachments.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        attachments.forEachIndexed { index, attachment ->
            PendingAttachmentPreviewItem(
                attachment = attachment,
                onRemove = { onRemoveAttachment(index) }
            )
        }
    }
}

@Composable
private fun PendingAttachmentPreviewItem(
    attachment: PendingUploadAttachment,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.size(76.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (attachment.image != null) {
                val viewer = LocalImageViewer.current
                val previewLabel = stringResource(R.string.common_image_preview)
                ImageThumbnail(
                    attachment = attachment,
                    // 右上角的移除按钮是 Box 里后声明的兄弟节点，绘制在上层、命中测试也先到，两者不抢
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClickLabel = previewLabel) {
                            viewer.show(attachment.toViewerRequest())
                        }
                )
            } else {
                FileAttachmentPreview(attachment = attachment)
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.chat_remove_attachment),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnail(
    attachment: PendingUploadAttachment,
    modifier: Modifier = Modifier.size(44.dp)
) {
    val base64Data = attachment.image?.base64Data.orEmpty()
    // Base64 解码 + 位图解码都放到 IO 线程：写在 remember 里的话，选图后的首帧会在主线程
    // 组合阶段同步解码，大图直接掉帧。采样与 OOM 兜底统一走 decodeSampledBitmap。
    val bitmap by produceState<ImageBitmap?>(null, base64Data) {
        if (base64Data.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            // Base64 解码本身会对非法输入抛异常，位图解码那一层已自带兜底
            runCatching {
                decodeSampledBitmap(Base64.getDecoder().decode(base64Data), THUMBNAIL_MAX_EDGE)
            }.getOrNull()
        }
    }
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        val loaded = bitmap
        if (loaded != null) {
            ComposeImage(
                bitmap = loaded,
                contentDescription = attachment.fileName.ifBlank { stringResource(R.string.common_image_preview) },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FileAttachmentPreview(attachment: PendingUploadAttachment) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            FeatherIcons.FileText,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatBytes(attachment.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 上传进行中的提示条：大文件传输耗时较久，没有反馈会像卡死。 */
@Composable
internal fun UploadingBanner(count: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_attachment_uploading, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 加号底部弹层：文件 / 图片 / 拍照上传入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(
    canUploadFiles: Boolean,
    canUploadImages: Boolean,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
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
            Text(
                text = stringResource(com.aicode.R.string.chat_add_attachment),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.sm)
            )
            AttachmentSheetItem(
                icon = FeatherIcons.FileText,
                title = stringResource(com.aicode.R.string.chat_upload_file),
                enabled = canUploadFiles,
                onClick = onUploadFile
            )
            AttachmentSheetItem(
                icon = FeatherIcons.Image,
                title = stringResource(com.aicode.R.string.chat_upload_image),
                enabled = canUploadImages,
                onClick = onUploadImage
            )
            AttachmentSheetItem(
                icon = FeatherIcons.Camera,
                title = stringResource(com.aicode.R.string.chat_take_photo),
                enabled = canUploadImages,
                onClick = onTakePhoto
            )
        }
    }
}

@Composable
private fun AttachmentSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
