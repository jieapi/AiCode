package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Image
import compose.icons.feathericons.Minimize2
import compose.icons.feathericons.Type

/**
 * 默认模型二级页：集中管理应用中的默认/特定用途模型设置（如识图模型、压缩模型）。
 */
@Composable
internal fun DefaultModelsSection(
    providers: List<AIProviderConfig>,
    visionProviderId: String,
    visionModel: String,
    compactionProviderId: String,
    compactionModel: String,
    titleProviderId: String,
    titleModel: String,
    imageGenProviderId: String,
    imageGenModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onLoadMetadata: () -> Unit,
    onSelectVisionModel: (providerId: String, model: String) -> Unit,
    onClearVisionModel: () -> Unit,
    onSelectCompactionModel: (providerId: String, model: String) -> Unit,
    onClearCompactionModel: () -> Unit,
    onSelectTitleModel: (providerId: String, model: String) -> Unit,
    onClearTitleModel: () -> Unit,
    onSelectImageGenModel: (providerId: String, model: String) -> Unit,
    onClearImageGenModel: () -> Unit
) {
    var showVisionSheet by remember { mutableStateOf(false) }
    var showCompactionSheet by remember { mutableStateOf(false) }
    var showTitleSheet by remember { mutableStateOf(false) }
    var showImageGenSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onLoadMetadata() }

    val visionValue = if (visionProviderId.isBlank() || visionModel.isBlank()) {
        stringResource(R.string.settings_vision_follow_chat)
    } else {
        visionModel
    }

    val compactionValue = if (compactionProviderId.isBlank() || compactionModel.isBlank()) {
        stringResource(R.string.settings_compaction_follow_chat)
    } else {
        compactionModel
    }

    val titleValue = if (titleProviderId.isBlank() || titleModel.isBlank()) {
        stringResource(R.string.settings_title_follow_chat)
    } else {
        titleModel
    }

    val imageGenValue = if (imageGenProviderId.isBlank() || imageGenModel.isBlank()) {
        stringResource(R.string.settings_image_gen_unconfigured)
    } else {
        imageGenModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_vision_model),
                onClick = { showVisionSheet = true },
                trailing = {
                    Text(
                        text = visionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Minimize2,
                title = stringResource(R.string.settings_compaction_model),
                onClick = { showCompactionSheet = true },
                trailing = {
                    Text(
                        text = compactionValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Type,
                title = stringResource(R.string.settings_title_model),
                onClick = { showTitleSheet = true },
                trailing = {
                    Text(
                        text = titleValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Camera,
                title = stringResource(R.string.settings_image_gen_model),
                onClick = { showImageGenSheet = true },
                trailing = {
                    Text(
                        text = imageGenValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(2f)
                    )
                }
            )
        }
    }

    if (showVisionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_vision_model),
            noModelsText = stringResource(R.string.vision_no_models),
            providers = providers,
            currentProviderId = visionProviderId,
            currentModel = visionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectVisionModel(pid, model)
                showVisionSheet = false
            },
            onClear = {
                onClearVisionModel()
                showVisionSheet = false
            },
            onDismiss = { showVisionSheet = false }
        )
    }

    if (showCompactionSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_compaction_model),
            noModelsText = stringResource(R.string.compaction_no_models),
            providers = providers,
            currentProviderId = compactionProviderId,
            currentModel = compactionModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectCompactionModel(pid, model)
                showCompactionSheet = false
            },
            onClear = {
                onClearCompactionModel()
                showCompactionSheet = false
            },
            onDismiss = { showCompactionSheet = false }
        )
    }

    if (showTitleSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_title_model),
            noModelsText = stringResource(R.string.title_no_models),
            providers = providers,
            currentProviderId = titleProviderId,
            currentModel = titleModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectTitleModel(pid, model)
                showTitleSheet = false
            },
            onClear = {
                onClearTitleModel()
                showTitleSheet = false
            },
            onDismiss = { showTitleSheet = false }
        )
    }

    if (showImageGenSheet) {
        ModelSelectionSheet(
            title = stringResource(R.string.settings_image_gen_model),
            noModelsText = stringResource(R.string.image_gen_no_models),
            providers = providers,
            currentProviderId = imageGenProviderId,
            currentModel = imageGenModel,
            modelMetadata = modelMetadata,
            onSelect = { pid, model ->
                onSelectImageGenModel(pid, model)
                showImageGenSheet = false
            },
            onClear = {
                onClearImageGenModel()
                showImageGenSheet = false
            },
            onDismiss = { showImageGenSheet = false }
        )
    }
}

/**
 * 模型选择弹窗：风格与拉取模型弹窗保持一致（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 识图模型、压缩模型与主页聊天模型共用此组件，仅文案不同；右上角「重置」清除专用模型配置（回退跟随聊天模型），主页场景传 null 不显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(
    title: String,
    noModelsText: String,
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onClear: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = settingsPageBackground()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                onClear?.let { onClear ->
                    Text(
                        text = stringResource(R.string.common_reset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }

            ModelSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.provider_filter_models_hint)
            )

            val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            if (activeProviders.isEmpty()) {
                SettingsGroup {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = noModelsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    activeProviders.forEach { provider ->
                        val filteredModels = provider.models.filter {
                            searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                        }
                        if (filteredModels.isNotEmpty()) {
                            item(key = "header_${provider.id}") {
                                SettingsGroupHeader("${provider.name} (${filteredModels.size})")
                            }
                            item(key = "card_${provider.id}") {
                                SettingsGroup {
                                    filteredModels.forEachIndexed { index, model ->
                                        if (index > 0) {
                                            SettingsDivider()
                                        }
                                        ModelSelectionRow(
                                            model = model,
                                            selected = provider.id == currentProviderId && model == currentModel,
                                            metadata = modelMetadata[model],
                                            onClick = { onSelect(provider.id, model) }
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
}

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm, horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            ModelMetadataTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelMetadataTags(metadata: ModelMetadata?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (metadata != null) {
            if (metadata.supportsVision) {
                ModelTag(text = "Image", isHighlight = true)
            }
            if (metadata.supportsTools) {
                ModelTag(text = "Tools")
            }
            val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
            if (input != null) {
                ModelTag(text = formatTokenLimit(input), icon = FeatherIcons.ArrowUp)
            }
            metadata.outputTokens?.takeIf { it > 0 }?.let { output ->
                ModelTag(text = formatTokenLimit(output), icon = FeatherIcons.ArrowDown)
            }
        }
    }
}

@Composable
private fun ModelTag(
    text: String,
    isHighlight: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val backgroundColor = if (isHighlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isHighlight) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = textColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor
            )
        }
    }
}

private fun formatTokenLimit(tokens: Int): String =
    when {
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
        tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
        tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
        else -> tokens.toString()
    }

private fun String.trimDecimal(): String =
    replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
