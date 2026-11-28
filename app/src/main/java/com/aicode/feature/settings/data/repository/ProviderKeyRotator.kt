package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Key 切换的结果，供调用方改写凭据并提示「已切换到第 N 个 Key」。 */
data class KeySwitchResult(
    val newEntry: ProviderKey,
    /** 1 起的新 Key 序号。 */
    val newIndex: Int,
    val total: Int
)

/**
 * 多 Key 轮换器：决定某个 provider 在某条会话上该用哪个 API Key，并在 Key 不可用时切到下一个。
 *
 * 状态全部是运行时内存态、不落库：进程重启后回到第一个 Key、冷却记录清空——多 Key 的意义是
 * 遇到鉴权/额度/限流问题时能立刻绕开，没有必须跨进程保持的语义。
 *
 * 所有状态按 Key 条目的身份 [ProviderKey.id]（值 + 覆盖地址）记录，而不是只按 Key 值：同一条
 * Key 配不同中转/反代是两个独立通道，各自会失败、各自冷却，失败时也能切到同值不同地址的另一条。
 *
 * **会话粘性**是这里的核心约束：服务端 prompt 缓存按 API Key + 端点隔离，同一会话逐请求换 Key
 * 会让每轮都落到没有缓存的 Key 上，缓存命中率归零、成本和首字延迟一起恶化。因此只在会话首次取
 * Key、或当前 Key 被判定不可用时才换，其余情况一直粘住同一个 Key。
 */
@Singleton
class ProviderKeyRotator @Inject constructor() {

    /** 由最近一次 [activeKey] 记录的 provider Key 配置，供失败上报时读取候选与冷却时长。 */
    private data class KeySetup(
        val keys: List<ProviderKey>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + Key 条目身份 → 冷却截止时间戳（毫秒）。 */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → Key 条目身份 → 平滑加权轮询的当前权重（运行时内存态）。 */
    private val weightedState = mutableMapOf<String, MutableMap<String, Long>>()

    /** providerId → 最近一次选中的 Key 条目身份，供余额查询等旁路请求取「当前活动 Key」。 */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → 该会话粘住的 Key 条目身份。会话数量无上界，用访问序 LRU 兜住内存，
     * 被淘汰的老会话下次请求重新分配 Key 即可，没有正确性问题。
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * 取 [config] 在 [sessionId] 上当前该用的 Key 条目；没有任何可用 Key、或所有 Key 都在冷却时返回 null。
     * 同时把该 provider 的 Key 配置快照下来，供后续 [reportFailure] 使用。
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): ProviderKey? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val boundId = bindKey?.let { sessionKeys[it] }
            val bound = boundId?.let { id -> keys.firstOrNull { it.id == id } }
            if (bound != null && !isCoolingDown(config.id, bound.id)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet()) ?: return null
            if (bindKey != null) sessionKeys[bindKey] = chosen.id
            lastSelected[config.id] = chosen.id
            return chosen
        }
    }

    /**
     * 只读地取该 provider 当前活动的 Key 条目：不建会话绑定、不推进轮询游标。
     * 供余额查询这类旁路请求使用——它们要反映「聊天正在用哪个 Key」，而不该干扰轮换节奏。
     */
    fun currentKey(config: AIProviderConfig): ProviderKey? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]?.let { id -> keys.firstOrNull { it.id == id } }
            if (last != null && !isCoolingDown(config.id, last.id)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it.id) } ?: keys.first()
        }
    }

    /**
     * 上报一次可归因于 [failedKeyId] 的失败：**立即**切到下一个可用 Key、把该 Key 打入冷却并重绑会话，
     * 返回切换结果；没有可切换的候选（候选已试完、或全部在冷却）时返回 null——此时**不冷却**该 Key，
     * 避免把最后一个可用 Key 也锁死、让用户在一段时间内完全无法发起请求。
     *
     * [triedKeyIds] 为本次请求已试过的 Key 条目身份（含 [failedKeyId] 自身），用于避免同一次请求内
     * 重复试同一个通道。
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        failedKeyId: String,
        triedKeyIds: Set<String> = emptySet()
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            val failed = setup.keys.firstOrNull { it.id == failedKeyId } ?: return null

            // 先确认有可切换的候选，没有就别冷却——把最后一个可用 Key 冷却只会让用户在一段时间内完全不可用
            val next = pick(providerId, setup.keys, setup.strategy, exclude = triedKeyIds + failed.id) ?: return null
            if (setup.cooldownMillis > 0) {
                cooldownUntil[stateKey(providerId, failed.id)] = System.currentTimeMillis() + setup.cooldownMillis
            }
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next.id }
            lastSelected[providerId] = next.id
            val index = setup.keys.indexOfFirst { it.id == next.id } + 1
            FileLogger.i(
                TAG,
                "Key 切换 provider=$providerId ${failed.value.masked()} → ${next.value.masked()} (第 $index/${setup.keys.size} 个)"
            )
            return KeySwitchResult(newEntry = next, newIndex = index, total = setup.keys.size)
        }
    }

    /**
     * 按策略挑一个可选 Key：排除 [exclude]（本次已试过）与处于冷却中的 Key，权重 ≤ 0 的也不参与；
     * 没有可选时返回 null。不做「全冷却就退回最早到期者再试一遍」的回退——那等于拿已知不可用的 Key 硬打。
     */
    private fun pick(
        providerId: String,
        keys: List<ProviderKey>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>
    ): ProviderKey? {
        val participants = keys.filter {
            it.weight > 0 && it.id !in exclude && !isCoolingDown(providerId, it.id)
        }
        if (participants.isEmpty()) return null
        return when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> participants.first()
            KeyRotationStrategy.ROUND_ROBIN -> weightedRoundRobin(providerId, participants)
        }
    }

    /**
     * 平滑加权轮询（SWRR）：每轮给各候选加上自身权重、取当前权重最大者，再给选中者减去候选总权重。
     * 无需按权重展开成虚拟列表，权重可到百万级；分布均匀且不会一直落在同一个 Key 上。
     */
    private fun weightedRoundRobin(providerId: String, participants: List<ProviderKey>): ProviderKey {
        val current = weightedState.getOrPut(providerId) { mutableMapOf() }
        var total = 0L
        var best = participants.first()
        var bestWeight = Long.MIN_VALUE
        for (candidate in participants) {
            val next = (current[candidate.id] ?: 0L) + candidate.weight
            current[candidate.id] = next
            total += candidate.weight
            if (next > bestWeight) {
                bestWeight = next
                best = candidate
            }
        }
        current[best.id] = (current[best.id] ?: 0L) - total
        return best
    }

    private fun isCoolingDown(providerId: String, keyId: String): Boolean {
        val key = stateKey(providerId, keyId)
        val until = cooldownUntil[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(key)
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, keyId: String) = "$providerId\u0000$keyId"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** 日志脱敏：只留末 4 位，避免把完整 Key 写进日志文件。 */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
