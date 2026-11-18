package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.settings.domain.model.ModelMetadata

/**
 * 模型元数据 → 思考能力。档位与开关标记来自 models.dev；
 * 关闭方式、代际限制（如某代不能关闭）由 [ReasoningResolver.capability] 用厂商表补全。
 *
 * 注意 models.dev 会把「关闭」写成 effort values 里的 `none`（如 gpt-5.1/5.2），
 * 这里把它从档位集合里摘出来转成 [ReasoningCapability.canDisable]，避免 UI 把「关闭」当普通档位。
 */
fun ModelMetadata.toReasoningCapability(): ReasoningCapability? {
    val declared = reasoningLevels.orEmpty().mapNotNull { ReasoningEffort.fromApiValue(it) }
    val levels = declared.filter { it != ReasoningEffort.NONE }.distinct().sortedBy { it.ordinal }
    val canDisable = reasoningCanDisable || declared.contains(ReasoningEffort.NONE)
    if (levels.isEmpty() && !canDisable && reasoningBudgetMin == null) return null
    return ReasoningCapability(
        levels = levels,
        canDisable = canDisable,
        disableKind = if (canDisable) {
            ReasoningCapability.DisableKind.TOGGLE_FIELD
        } else {
            ReasoningCapability.DisableKind.UNSUPPORTED
        },
        budgetMin = reasoningBudgetMin,
        budgetMax = reasoningBudgetMax,
        defaultLevel = reasoningDefaultLevel?.let { ReasoningEffort.fromApiValue(it) },
        source = ReasoningCapability.Source.MODELS_DEV
    )
}
