package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * 某个「提供商 + 模型」真实具备的思考控制能力，是 UI 档位与适配器映射的唯一事实源。
 *
 * 三种控制形态由 [levels] / [budgetMin] / [canDisable] 组合表达：
 * - 档位型：[levels] 非空、[budgetMin] 为空，档位直传厂商参数；
 * - 预算型：[budgetMin] / [budgetMax] 非空，档位需换算成 token 预算；
 * - 开关型：[levels] 为空、[canDisable] 为 true，只有开关没有中间档。
 *
 * 数据来源见 [source]：models.dev 目录 > 厂商能力兜底表 > 用户手动覆盖 > 完全未知。
 */
@Serializable
data class ReasoningCapability(
    /** 真实支持的档位（不含「关闭」；关闭由 [canDisable] 单独表达）。为空表示没有档位可调。 */
    val levels: List<ReasoningEffort> = emptyList(),
    /** 能否关闭思考。部分模型思考恒开（如 Claude Opus 5.5、Gemini 3 Pro）。 */
    val canDisable: Boolean = false,
    /** 关闭思考时该用哪种线上表达。 */
    val disableKind: DisableKind = DisableKind.UNSUPPORTED,
    /** 关闭思考时仍需随请求携带的档位（Anthropic 关闭思考时仍要带 effort，且受模型上限约束）。 */
    val effortWhileDisabled: ReasoningEffort? = null,
    /** 预算型的下界（token）。为 null 表示该模型不是预算型。 */
    val budgetMin: Int? = null,
    /** 预算型的上界（token）。 */
    val budgetMax: Int? = null,
    /** 服务端默认档位，仅用于 UI 标注，不参与请求。 */
    val defaultLevel: ReasoningEffort? = null,
    val source: Source = Source.UNKNOWN
) {
    /** 关闭思考的线上表达方式。 */
    @Serializable
    enum class DisableKind {
        /** 厂商私有开关字段（`thinking.type` / `enable_thinking`）。 */
        TOGGLE_FIELD,

        /** OpenAI 系：`reasoning.effort = "none"`（仅 Responses 端点可靠，chat/completions 常被降级）。 */
        EFFORT_NONE,

        /** 预算型：把预算设为 0（Gemini 2.5 Flash / Flash-Lite）。 */
        BUDGET_ZERO,

        /** 降低工具调用之间的思考强度（Claude Sonnet 5.5 专有）。 */
        BETWEEN_TOOLS,

        /** 无法关闭。 */
        UNSUPPORTED
    }

    @Serializable
    enum class Source {
        /** 来自 models.dev 目录（随仓库快照每日同步）。 */
        MODELS_DEV,

        /** 来自内置厂商能力表（按模型名推断）。 */
        VENDOR_TABLE,

        /** 用户手动指定。 */
        MANUAL,

        /** 从服务端 400 文案里学到的真实白名单（同一模型换渠道可能不同）。 */
        LEARNED,

        /** 未知：按协议保守兜底。 */
        UNKNOWN
    }

    /** 能力是否可信（未知时调用方应保留全部档位并提示「可能不生效」）。 */
    val isKnown: Boolean get() = source != Source.UNKNOWN

    /** UI 可选档位：能关闭时把「关闭」排在最前。 */
    val selectableLevels: List<ReasoningEffort>
        get() {
            val sorted = levels.distinct().sortedBy { it.ordinal }
            return if (canDisable) listOf(ReasoningEffort.NONE) + sorted else sorted
        }

    companion object {
        /** 未知能力的兜底：不做任何断言，供 UI 标「可能不生效」。 */
        val UNKNOWN = ReasoningCapability()
    }
}
