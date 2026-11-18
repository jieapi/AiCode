package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.ReasoningCapability
import com.aicode.feature.agent.domain.model.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap

/**
 * 从服务端 400 文案里学习「这个模型到底支持哪些档位」。
 *
 * 实测两种错误都会把白名单原文吐出来：
 * - `level "minimal" not supported, valid levels: low, medium, high, xhigh, max`
 * - `Unsupported value: 'none' is not supported with the 'gpt-6-astra' model. Supported values are: 'low', 'medium', …`
 *
 * 学习结果按 `providerId:model` 缓存（同一模型换渠道白名单可能不同），后续请求直接用学到的档位，
 * 不再重复踩同一个 400；不自动重发被拒的请求，避免重复计费。
 */
object ReasoningLearning {

    private val learned = ConcurrentHashMap<String, ReasoningCapability>()

    /** 取某个渠道下该模型学到的档位（没学过返回 null）。 */
    fun recall(providerId: String, model: String): ReasoningCapability? {
        if (providerId.isBlank() || model.isBlank()) return null
        return learned[key(providerId, model)]
    }

    /**
     * 记录一次失败：能解析出白名单就覆盖档位集合，解析不出则什么都不做。
     * [current] 是当前已知能力，用来保留「能否关闭」等无法从错误里读到的信息。
     */
    fun observe(providerId: String, model: String, error: Throwable?, current: ReasoningCapability?) {
        val message = error?.message ?: return
        val levels = parseSupportedLevels(message) ?: return
        if (providerId.isBlank() || model.isBlank()) return
        val base = current ?: ReasoningCapabilityTables.forProtocol(ReasoningProtocol.OPENAI_CHAT, model)
        val noneAllowed = levels.contains(ReasoningEffort.NONE)
        // EFFORT_NONE 的「关闭」与档位同值空间（effort="none"）：白名单里没有 none 说明
        // 该渠道连关闭也拒，必须摘掉 canDisable，否则下次仍发 none、永远重复同一个 400。
        // 其余关闭形态（thinking.type 开关、预算 0 等）不走这个值空间，保留原有可关闭性。
        val canDisable = when {
            base.disableKind == ReasoningCapability.DisableKind.EFFORT_NONE -> noneAllowed
            else -> base.canDisable || noneAllowed
        }
        learned[key(providerId, model)] = base.copy(
            levels = levels.filter { it != ReasoningEffort.NONE },
            canDisable = canDisable,
            disableKind = when {
                !canDisable -> ReasoningCapability.DisableKind.UNSUPPORTED
                base.disableKind != ReasoningCapability.DisableKind.UNSUPPORTED -> base.disableKind
                // 原本不可关闭、但白名单明确收下了 none：唯一能推断的关闭方式就是档位置 none。
                else -> ReasoningCapability.DisableKind.EFFORT_NONE
            },
            source = ReasoningCapability.Source.LEARNED
        )
    }

    /**
     * 从错误文案里解析受支持的档位；两种常见写法都支持。
     * 返回 null 表示文案里没有可用的白名单（此时不应做任何学习，避免误收窄档位）。
     */
    fun parseSupportedLevels(message: String?): List<ReasoningEffort>? {
        if (message.isNullOrBlank()) return null
        val raw = VALID_LEVELS.findAll(message).map { it.groupValues[1] }.firstOrNull()
            ?: SUPPORTED_VALUES.findAll(message).map { it.groupValues[1] }.firstOrNull()
            ?: return null
        val levels = raw.split(',', ';', '|')
            .map { it.trim().trim('\'', '"', '.', '`') }
            .mapNotNull { ReasoningEffort.fromApiValue(it) }
            .distinct()
            .sortedBy { it.ordinal }
        return levels.takeIf { it.isNotEmpty() }
    }

    /** 测试与「切换提供商后重试」用：清掉某渠道或全部学习结果。 */
    fun clear(providerId: String? = null, model: String? = null) {
        if (providerId.isNullOrBlank()) {
            learned.clear()
            return
        }
        if (model.isNullOrBlank()) {
            learned.keys.removeIf { it.startsWith("$providerId:") }
        } else {
            learned.remove(key(providerId, model))
        }
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    /** `valid levels: low, medium, high` */
    private val VALID_LEVELS = Regex("""valid levels?:\s*([a-zA-Z_,\s]+)""")

    /** `Supported values are: 'low', 'medium', 'high'` */
    private val SUPPORTED_VALUES = Regex("""[Ss]upported values are:\s*([a-zA-Z_,'\s"]+)""")
}
