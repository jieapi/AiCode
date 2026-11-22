package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.ReasoningPlan
import com.aicode.feature.agent.domain.provider.ReasoningProtocol
import com.aicode.feature.agent.domain.provider.ReasoningResolver
import com.aicode.feature.agent.domain.provider.preferredOnLevel
import com.aicode.feature.agent.domain.provider.selectableLevelsForUi
import com.aicode.feature.agent.domain.provider.showsThinkingToggle
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import com.aicode.feature.settings.presentation.component.ModelSelectionSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap
import kotlinx.coroutines.launch

/**
 * 输入区下行的模型切换图标按钮
 */
@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = provider?.effectiveModel.orEmpty(), size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            },
            runModelTest = runModelTest
        )
    }
}

/**
 * 思考强度选择器：图标按钮 → 底部弹层，「思考」总开关 + 只列模型真实支持的档位。
 *
 * 档位集合与「实际生效」全部来自 [ReasoningResolver]，与请求侧完全同源：
 * 最高只到「高」的模型不会再列出「最高」，思考恒开 / 端点不认「关闭」时也会明确提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    capability: ReasoningCapability,
    protocol: ReasoningProtocol,
    model: String,
    onChange: (ReasoningEffort) -> Unit,
    fastMode: Boolean,
    showFastMode: Boolean,
    onFastModeChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    var showSheet by remember { mutableStateOf(false) }
    // 与实际请求同源：这里算出的「实际生效档位」就是适配器接下来会发出的档位。
    val plan = remember(effort, capability, protocol, model) {
        ReasoningResolver.plan(protocol, model, effort.apiValue, capability)
    }
    val levels = remember(capability) { capability.selectableLevelsForUi() }
    val thinkingOn = effort != ReasoningEffort.NONE
    // 输入栏里只显示「实际会生效」的短名：真被降级时显示降级后的档位，关得掉才显示「不思考」。
    val reasoningLabel = when {
        plan.isDisableRequested && plan.reason != ReasoningPlan.Reason.CANNOT_DISABLE ->
            stringResource(R.string.chat_reasoning_off_short)
        plan.effectiveLevel != null -> stringResource(plan.effectiveLevel!!.labelRes())
        else -> stringResource(effort.labelRes())
    }
    val fastModeActive = showFastMode && fastMode
    val fastModeLabel = stringResource(R.string.chat_fast_mode_label)
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        fastModeActive -> MaterialTheme.colorScheme.primary
        thinkingOn -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    // 闪电 + 当前档位：整块可点，点开同一个弹层。
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled) { showSheet = true }
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.Zap,
            contentDescription = if (fastModeActive) {
                stringResource(R.string.chat_fast_mode_active_description, reasoningLabel)
            } else {
                stringResource(effort.labelRes())
            },
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = reasoningLabel,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 64.dp)
        )
        if (fastModeActive) {
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = fastModeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
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
                    Text(
                        text = stringResource(com.aicode.R.string.chat_reasoning_effort),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (capability.showsThinkingToggle()) {
                        Text(
                            text = stringResource(R.string.chat_reasoning_toggle),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        AppSwitch(
                            checked = thinkingOn,
                            enabled = enabled,
                            onCheckedChange = { on ->
                                onChange(
                                    if (on) {
                                        capability.preferredOnLevel() ?: ReasoningEffort.MEDIUM
                                    } else {
                                        ReasoningEffort.NONE
                                    }
                                )
                            }
                        )
                    }
                }

                ReasoningHintLines(plan = plan, capability = capability)

                levels.forEach { e ->
                    val selected = thinkingOn && e == effort
                    // 想关却关不掉时，把「实际会发的档位」标出来，避免用户以为思考真的停了。
                    val effectiveWhenOff = !thinkingOn &&
                        plan.reason == ReasoningPlan.Reason.CANNOT_DISABLE &&
                        plan.effectiveLevel == e
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable(enabled = enabled) {
                                showSheet = false
                                onChange(e)
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.Zap,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = e.withApiLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (e == capability.defaultLevel) {
                            Text(
                                text = stringResource(R.string.chat_reasoning_default_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (effectiveWhenOff) {
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = stringResource(R.string.chat_reasoning_effective_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (selected) {
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (showFastMode) {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_fast_mode_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.chat_fast_mode_cost_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(Spacing.md))
                        AppSwitch(
                            checked = fastMode,
                            enabled = enabled,
                            onCheckedChange = onFastModeChange
                        )
                    }
                }
            }
        }
    }
}

/** 弹层顶部的说明行：未识别 / 关不掉 / 被降级 / 端点可能忽略，各自只出现一条。 */
@Composable
private fun ReasoningHintLines(plan: ReasoningPlan, capability: ReasoningCapability) {
    if (!capability.isKnown) {
        ReasoningHint(stringResource(R.string.chat_reasoning_unknown_hint))
    }
    when (plan.reason) {
        ReasoningPlan.Reason.CANNOT_DISABLE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_cannot_disable))
        ReasoningPlan.Reason.ENDPOINT_MAY_IGNORE ->
            ReasoningHint(stringResource(R.string.chat_reasoning_endpoint_weak))
        ReasoningPlan.Reason.CLAMPED_DOWN, ReasoningPlan.Reason.CLAMPED_UP ->
            plan.effectiveLevel?.let {
                ReasoningHint(stringResource(R.string.chat_reasoning_effective, it.withApiLabel()))
            }
        else -> Unit
    }
}

@Composable
private fun ReasoningHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

/** 档位显示名：中文 + 对应接口值，便于对照模型文档与报错文案（如「最高（max）」）。 */
@Composable
private fun ReasoningEffort.withApiLabel(): String =
    stringResource(R.string.common_label_with_api, stringResource(labelRes()), apiValue)

private fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.NONE -> com.aicode.R.string.chat_reasoning_effort_none
    ReasoningEffort.MINIMAL -> com.aicode.R.string.chat_reasoning_effort_minimal
    ReasoningEffort.LOW -> com.aicode.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aicode.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aicode.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aicode.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aicode.R.string.chat_reasoning_effort_max
}

/**
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 传入 [runModelTest] 时每个模型行提供「测试」按钮（不显示选中小勾）。测试态与结果按 provider+模型
 * 在弹层内持有：跨 provider 的同名模型不会串结果，弹层关闭随作用域取消在途请求。
 */
@Composable
internal fun ModelSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    runModelTest: (suspend (AIProviderConfig, String) -> ModelTestResult)? = null
) {
    val testScope = rememberCoroutineScope()
    var testingModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var testResults by remember { mutableStateOf<Map<String, ModelTestResult>>(emptyMap()) }

    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss,
        onTestModel = runModelTest?.let { runner ->
            { provider, model ->
                val key = modelMetadataKey(provider.id, model)
                testingModels = testingModels + key
                testResults = testResults - key
                testScope.launch {
                    val result = runner(provider, model)
                    testResults = testResults + (key to result)
                    testingModels = testingModels - key
                }
            }
        },
        testingModels = testingModels,
        testResults = testResults
    )
}
