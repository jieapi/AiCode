package com.aicode.feature.starprompt.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors

/**
 * 求 Star 弹窗：居中精致卡片，提供「前往 GitHub 点 Star」「下次一定」「不再提示」三项操作。
 * 点击空白或系统返回键等价于「下次一定」，按 3 / 7 / 14 天阶梯推迟。
 */
@Composable
fun StarPromptDialog(
    onGoToStar: () -> Unit,
    onSnooze: () -> Unit,
    onDismissForever: () -> Unit
) {
    Dialog(
        onDismissRequest = onSnooze,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
                .widthIn(max = 360.dp),
            shape = RoundedCornerShape(Radius.xl),
            color = MaterialTheme.semanticColors.cardSurface,
            border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StarBadge()

                Spacer(modifier = Modifier.height(Spacing.lg))

                Text(
                    text = stringResource(R.string.star_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                Text(
                    text = stringResource(R.string.star_prompt_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                Button(
                    onClick = onGoToStar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(Radius.mdLarge),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.star_prompt_action_star),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismissForever,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.md)
                    ) {
                        Text(
                            text = stringResource(R.string.star_prompt_action_dismiss),
                            color = MaterialTheme.semanticColors.subtleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = onSnooze,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.md)
                    ) {
                        Text(
                            text = stringResource(R.string.star_prompt_action_later),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarBadge() {
    val context = LocalContext.current
    val appIcon = remember(context) { loadAppIconBitmap(context) }

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = MaterialTheme.semanticColors.warningContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.semanticColors.warning
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.BottomEnd),
            shape = CircleShape,
            color = MaterialTheme.semanticColors.warningContainer,
            border = BorderStroke(1.5.dp, MaterialTheme.semanticColors.cardSurface)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.semanticColors.warning
                )
            }
        }
    }
}

private fun loadAppIconBitmap(context: Context): ImageBitmap? = runCatching {
    val pm = context.packageManager
    val drawable: Drawable = pm.getApplicationInfo(context.packageName, 0).loadIcon(pm)
    val sizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        ICON_PX_DP.toFloat(),
        context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    Canvas(bitmap).also { drawable.draw(it) }
    bitmap.asImageBitmap()
}.getOrNull()

private const val ICON_PX_DP = 54
